#!/usr/bin/env node
/**
 * Re-verifies every shipped provider against the live site using the sample
 * recorded in its `// @provider` header (`sample: "imdbId:type:season:episode"`).
 *
 *   node nuvio/tools/verify-all.mjs                 # all providers, 6 parallel
 *   node nuvio/tools/verify-all.mjs --jobs 12       # more parallel
 *   node nuvio/tools/verify-all.mjs diziyou         # one provider
 *
 * Prints a PASS/FAIL table; exit code 1 if any provider returns no stream.
 */
import { execFile } from "node:child_process";
import dns from "node:dns/promises";
import fs from "node:fs";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { Duplex } from "node:stream";
import tls from "node:tls";
import { promisify } from "node:util";
import vm from "node:vm";
import zlib from "node:zlib";

const execFileAsync = promisify(execFile);

const root = path.resolve(import.meta.dirname, "..");
const providersDir = path.join(root, "providers");
const argv = process.argv.slice(2);
const jobsAt = argv.indexOf("--jobs");
const jobs = Math.max(1, Number(jobsAt >= 0 ? argv[jobsAt + 1] : process.env.NUVIO_VERIFY_JOBS || 6) || 6);
const testProvider = argv.includes("--test-provider");
const only = argv.find((a, i) => !a.startsWith("--") && (jobsAt < 0 || i !== jobsAt + 1));
const curlBin = process.platform === "win32" ? "curl.exe" : "curl";

function defaultBotDir() {
  if (process.env.BIGBULL_BOT_DIR) return process.env.BIGBULL_BOT_DIR;
  if (process.env.BIGBULL_CF_CURL) return path.dirname(process.env.BIGBULL_CF_CURL);
  const home = process.env.USERPROFILE || process.env.HOME || "";
  return home ? path.join(home, ".omp", "local", "bigbull-bot") : "";
}

/** Clearance exported by the bot's solver (`cf_browser.remember_curl`) for blocked hosts. */
function cfState(host) {
  try {
    const botDir = defaultBotDir();
    const file = process.env.BIGBULL_CF_CURL || (botDir ? path.join(botDir, "cf-curl.json") : "");
    if (!file || !fs.existsSync(file)) return null;
    const data = JSON.parse(fs.readFileSync(file, "utf8"));
    const key = String(host || "").toLowerCase();
    const base = key.replace(/^www\./, "");
    // www/apex kayıtları ayrı tazelenebilir: taze olan ilk adayı kullan.
    for (const candidate of [key, base, "www." + base]) {
      const entry = data[candidate];
      if (!entry || !entry.cookie) continue;
      let ts = Number(entry.ts) || 0;
      if (ts && ts < 1e11) ts *= 1000;
      if (!ts || Date.now() - ts > 25 * 60 * 1000) continue;  // cf_clearance ~30 dk yaşıyor
      return entry;
    }
    return null;
  } catch {
    return null;
  }
}

function solverToken() {
  const envTok = (process.env.BIGBULL_CF_SOLVER_TOKEN || "").trim();
  if (envTok) return envTok;
  const botDir = defaultBotDir();
  if (!botDir) return "";
  try {
    const tokFile = path.join(botDir, ".cf-solver.token");
    if (fs.existsSync(tokFile)) return fs.readFileSync(tokFile, "utf8").trim();
  } catch {}
  return "";
}

async function solverFetchFallback(url, options = {}) {
  const base = (process.env.BIGBULL_CF_SOLVER || "http://127.0.0.1:8159").trim();
  if (!base.startsWith("http://127.0.0.1") && !base.startsWith("http://localhost")) return null;
  try {
    const tok = solverToken();
    const reqHeaders = { "Content-Type": "application/json" };
    if (tok) reqHeaders["X-BigBull-Solver-Token"] = tok;
    const res = await fetch(`${base}/fetch`, {
      method: "POST",
      headers: reqHeaders,
      body: JSON.stringify({
        url: String(url),
        method: options.method || "GET",
        headers: options.headers || {},
        body: options.body != null ? String(options.body) : undefined,
      }),
      signal: AbortSignal.timeout(35000),
    });
    if (!res.ok) return null;
    const data = await res.json();
    const status = Number(data.status) || 0;
    if (status < 200 || status >= 400) return null;
    const body = String(data.body || "");
    const headers = makeHeaders("Content-Type: text/html; charset=utf-8");
    return {
      ok: status >= 200 && status < 300,
      status,
      url: String(data.url || url),
      headers,
      text: async () => body,
      json: async () => JSON.parse(body),
    };
  } catch {
    return null;
  }
}

function makeHeaders(rawHeaderText) {
  const blocks = String(rawHeaderText || "").trim().split(/\r?\n\r?\n/);
  const lastBlock = blocks[blocks.length - 1] || "";
  const map = Object.create(null);
  const setCookies = [];
  for (const line of lastBlock.split(/\r?\n/)) {
    const idx = line.indexOf(":");
    if (idx <= 0) continue;
    const k = line.slice(0, idx).trim().toLowerCase();
    const v = line.slice(idx + 1).trim();
    if (k === "set-cookie") setCookies.push(v);
    if (!map[k]) map[k] = [];
    map[k].push(v);
  }
  return {
    map,
    get(name) {
      const vals = map[String(name || "").toLowerCase()];
      return vals && vals.length ? vals.join(", ") : null;
    },
    getAll(name) {
      return (map[String(name || "").toLowerCase()] || []).slice();
    },
    getSetCookie() {
      return setCookies.slice();
    },
  };
}

/** Fallback when Cloudflare/CDN blocks Node/undici's TLS JA3 with 403 while curl/Android works (AGENTS.md). */
async function curlFallback(url, options = {}) {
  const hdrFile = path.join(os.tmpdir(), `bb-curl-${process.pid}-${Date.now()}-${Math.random().toString(36).slice(2)}.hdr`);
  try {
    const args = ["-s", "--max-time", "15", "-D", hdrFile, "-w", "\n__HTTP_STATUS__:%{http_code}"];
    if (options.redirect !== "manual") args.push("-L");
    if (options.method && options.method !== "GET") args.push("-X", options.method);
    const cf = cfState(new URL(String(url)).hostname);
    const hdrs = { ...(options.headers || {}) };
    if (cf) {
      if (cf.ua) args.push("-A", cf.ua);  // cf_clearance UA'ya bağlı
      hdrs.Cookie = hdrs.Cookie ? `${hdrs.Cookie}; ${cf.cookie}` : cf.cookie;
    }
    for (const [k, v] of Object.entries(hdrs)) {
      if (v == null) continue;
      if (cf && k.toLowerCase() === "user-agent") continue;
      args.push("-H", `${k}: ${v}`);
    }
    if (options.body) args.push("--data-raw", String(options.body));
    args.push(String(url));
    const { stdout: raw } = await execFileAsync(curlBin, args, { encoding: "utf8", maxBuffer: 16 * 1024 * 1024 });
    const rawHeaders = fs.existsSync(hdrFile) ? fs.readFileSync(hdrFile, "utf8") : "";
    const headers = makeHeaders(rawHeaders);
    const idx = raw.lastIndexOf("\n__HTTP_STATUS__:");
    const status = idx >= 0 ? Number(raw.slice(idx + 17).trim()) || 0 : 200;
    const body = idx >= 0 ? raw.slice(0, idx) : raw;
    if (status >= 200 && status < 400) {
      return {
        ok: status >= 200 && status < 300,
        status,
        url: String(url),
        headers,
        text: async () => body,
        json: async () => JSON.parse(body),
      };
    }
    return await solverFetchFallback(url, options);
  } catch {
    return await solverFetchFallback(url, options);
  } finally {
    try { fs.unlinkSync(hdrFile); } catch {}
  }
}

const dpiHosts = new Set();

function normalizeCfZero(ip) {
  return String(ip || "").replace(/^(104\.21\.|172\.67\.|188\.114\.)(\d+)\.0$/, "$1$2.7");
}

async function resolveDpiIp(host) {
  if (net.isIP(host)) return normalizeCfZero(host);
  try {
    const addrs = await dns.resolve4(host);
    const valid = addrs.find((a) => !a.startsWith("195.175.254."));
    if (valid) return normalizeCfZero(valid);
  } catch {}
  try {
    const res = await fetch(`https://1.1.1.1/dns-query?name=${encodeURIComponent(host)}&type=A`, {
      headers: { Accept: "application/dns-json" },
    });
    if (res.ok) {
      const data = await res.json();
      for (const ans of data.Answer || []) {
        const ip = String(ans.data || "");
        if (net.isIPv4(ip) && !ip.startsWith("195.175.254.")) return normalizeCfZero(ip);
      }
    }
  } catch {}
  return host;
}

function decodeChunked(buf) {
  const out = [];
  let p = 0;
  while (p < buf.length) {
    const eol = buf.indexOf("\r\n", p);
    if (eol < 0) break;
    const sz = parseInt(buf.subarray(p, eol).toString("ascii").split(";")[0].trim(), 16);
    if (!sz || Number.isNaN(sz)) break;
    out.push(buf.subarray(eol + 2, eol + 2 + sz));
    p = eol + 2 + sz + 2;
  }
  return Buffer.concat(out);
}

const tlsWarned = new Set();

/** Bypass SNI/DPI TCP resets (ddizimom, diziyou) by fragmenting the TLS ClientHello record. */
export function dpiFetch(urlStr, options = {}, redirects = 4) {
  return new Promise(async (resolve, reject) => {
    const sig = options.signal;
    if (sig && sig.aborted) {
      const err = new Error("Aborted");
      err.name = "AbortError";
      return reject(err);
    }
    let raw = null;
    let s = null;
    let settled = false;
    const done = (fn, val) => {
      if (settled) return;
      settled = true;
      if (sig) sig.removeEventListener("abort", onAbort);
      fn(val);
    };
    const onAbort = () => {
      try { if (s) s.destroy(); } catch {}
      try { if (raw) raw.destroy(); } catch {}
      const err = new Error("Aborted");
      err.name = "AbortError";
      done(reject, err);
    };
    if (sig) sig.addEventListener("abort", onAbort, { once: true });
    try {
      const u = new URL(String(urlStr));
      if (u.protocol !== "https:") {
        return done(resolve, await fetch(urlStr, options));
      }
      const ip = await resolveDpiIp(u.hostname);
      raw = net.connect(Number(u.port) || 443, ip);
      raw.setNoDelay(true);
      raw.setTimeout(15000, () => raw.destroy(new Error("tcp timeout")));
      raw.on("error", (e) => done(reject, e));
      let firstWrite = true;
      const proxy = new Duplex({
        read() {},
        write(chunk, enc, cb) {
          if (raw.destroyed || !raw.writable) return cb();
          if (firstWrite && chunk.length > 60 && chunk[0] === 0x16) {
            firstWrite = false;
            const recLen = (chunk[3] << 8) | chunk[4];
            if (5 + recLen <= chunk.length) {
              let sp = Math.min(45, recLen - 8);
              for (let i = 5; i < 5 + recLen - 12; i++) {
                if (chunk[i] === 0 && chunk[i + 1] === 0 && chunk[i + 6] === 0) {
                  const extLen = (chunk[i + 2] << 8) | chunk[i + 3];
                  const lLen = (chunk[i + 4] << 8) | chunk[i + 5];
                  const nameLen = (chunk[i + 7] << 8) | chunk[i + 8];
                  if (nameLen >= 4 && nameLen <= 253 && lLen === nameLen + 3 && extLen === lLen + 2 && i + 9 + nameLen <= 5 + recLen) {
                    sp = (i - 5) + 9 + (nameLen >> 1);
                    break;
                  }
                }
              }
              const r2 = recLen - sp;
              const b1 = Buffer.concat([chunk.subarray(0, 3), Buffer.from([sp >> 8, sp & 0xff]), chunk.subarray(5, 5 + sp)]);
              const b2 = Buffer.concat([chunk.subarray(0, 3), Buffer.from([r2 >> 8, r2 & 0xff]), chunk.subarray(5 + sp)]);
              raw.write(b1);
              setTimeout(() => {
                if (!raw.destroyed && raw.writable) raw.write(b2, cb);
                else cb();
              }, 15);
              return;
            }
          }
          raw.write(chunk, enc, cb);
        },
        final(cb) {
          if (!raw.destroyed && raw.writable) raw.end();
          cb();
        },
      });
      raw.on("data", (d) => { if (!proxy.destroyed) proxy.push(d); });
      raw.on("end", () => { if (!proxy.destroyed) proxy.push(null); });
      s = tls.connect({ socket: proxy, servername: u.hostname, rejectUnauthorized: false, ALPNProtocols: ["http/1.1"] }, () => {
        if (!s.authorized && !tlsWarned.has(u.hostname)) {
          tlsWarned.add(u.hostname);
          process.stderr.write(`[tls-warn] ${u.hostname}: ${s.authorizationError || "unauthorized"}\n`);
        }
        const hdrs = { ...(options.headers || {}) };
        const has = (k) => Object.keys(hdrs).some((x) => x.toLowerCase() === k);
        if (!has("host")) hdrs.Host = u.host;
        if (!has("user-agent")) hdrs["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
        if (!has("accept")) hdrs.Accept = "*/*";
        hdrs["Accept-Encoding"] = "gzip, deflate";
        hdrs.Connection = "close";
        const bodyBuf = options.body ? Buffer.from(String(options.body)) : null;
        if (bodyBuf && !has("content-length")) hdrs["Content-Length"] = String(bodyBuf.length);
        const head = `${options.method || "GET"} ${u.pathname || "/"}${u.search || ""} HTTP/1.1\r\n` +
          Object.entries(hdrs).filter(([, v]) => v != null).map(([k, v]) => `${k}: ${v}`).join("\r\n") + "\r\n\r\n";
        s.write(head);
        if (bodyBuf) s.write(bodyBuf);
      });
      const chunks = [];
      s.on("data", (c) => chunks.push(c));
      s.on("end", () => {
        try {
          const buf = Buffer.concat(chunks);
          const sep = buf.indexOf("\r\n\r\n");
          if (sep < 0) return done(reject, new Error("invalid HTTP response"));
          const headStr = buf.subarray(0, sep).toString("latin1");
          const headers = makeHeaders(headStr);
          let bodyBuf = buf.subarray(sep + 4);
          const status = Number((headStr.match(/^HTTP\/\d\.\d\s+(\d+)/) || [])[1]) || 0;
          const loc = headers.get("location");
          if ([301, 302, 303, 307, 308].includes(status) && loc && options.redirect !== "manual" && redirects > 0) {
            const origMethod = String(options.method || "GET").toUpperCase();
            const dropBody = status === 303 || ((status === 301 || status === 302) && origMethod === "POST");
            const nextHeaders = {};
            for (const [k, v] of Object.entries(options.headers || {})) {
              const lk = k.toLowerCase();
              if (lk === "host" || (dropBody && (lk === "content-length" || lk === "content-type"))) continue;
              nextHeaders[k] = v;
            }
            const nextOpts = {
              ...options,
              method: dropBody ? "GET" : origMethod,
              headers: nextHeaders,
              body: dropBody ? undefined : options.body,
            };
            return done(resolve, dpiFetch(new URL(loc, u).toString(), nextOpts, redirects - 1));
          }
          if (/^transfer-encoding:\s*chunked/im.test(headStr)) bodyBuf = decodeChunked(bodyBuf);
          const enc = (headStr.match(/^content-encoding:\s*(\S+)/im) || [])[1]?.toLowerCase();
          if (bodyBuf.length > 0) {
            if (enc === "gzip") bodyBuf = zlib.gunzipSync(bodyBuf);
            else if (enc === "deflate") {
              try { bodyBuf = zlib.inflateSync(bodyBuf); } catch { bodyBuf = zlib.inflateRawSync(bodyBuf); }
            }
          }
          const bodyText = bodyBuf.toString("utf8");
          done(resolve, {
            ok: status >= 200 && status < 300,
            status,
            url: u.toString(),
            headers,
            text: async () => bodyText,
            json: async () => JSON.parse(bodyText),
          });
        } catch (err) {
          done(reject, err);
        }
      });
      s.on("error", (e) => done(reject, e));
      s.setTimeout(15000, () => s.destroy(new Error("timeout")));
    } catch (e) {
      done(reject, e);
    }
  });
}

export async function fetchWithDpi(url, options = {}, signal = null) {
  const hostname = new URL(String(url)).hostname;
  const reqOpts = signal ? { ...options, signal } : options;
  let res;
  if (dpiHosts.has(hostname)) {
    res = await dpiFetch(url, reqOpts);
  } else {
    try {
      const directSignal = signal ? AbortSignal.any([signal, AbortSignal.timeout(5000)]) : AbortSignal.timeout(5000);
      res = await fetch(url, { ...options, signal: directSignal });
    } catch (e) {
      if (signal && signal.aborted) throw e;
      dpiHosts.add(hostname);
      res = await dpiFetch(url, reqOpts);
    }
  }
  if (res.status === 403 || res.status === 503) {
    const viaCurl = await curlFallback(url, options);
    if (viaCurl) return viaCurl;
  }
  return res;
}

async function verify(id, file) {
  const code = fs.readFileSync(path.join(providersDir, file), "utf8");
  const head = code.split("\n").slice(0, 12).join("\n");
  const meta = JSON.parse(head.match(/^\/\/\s*@provider\s+(\{.*\})\s*$/m)[1]);
  if (!meta.sample) return { id, ok: false, note: "no sample in header" };
  const [imdbId, type, season, episode] = meta.sample.split(":");
  const module = { exports: {} };
  const controller = new AbortController();
  const timeoutMs = Number(process.env.NUVIO_VERIFY_TIMEOUT_MS || 20000);
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  const timedFetch = (url, options = {}) => fetchWithDpi(url, options, controller.signal);
  const sandbox = { module, exports: module.exports, console: { log() {} }, fetch: timedFetch, URL, encodeURIComponent, decodeURIComponent, Promise };
  vm.createContext(sandbox);
  let note = "";
  let ok = false;
  try {
    vm.runInContext(code, sandbox, { filename: file });
    let out;
    for (let attempt = 0; attempt < 2; attempt++) {
      try {
        out = await module.exports.getStreams(imdbId, type, season, episode);
      } catch (e) {
        if (attempt === 1) throw e;
      }
      if (Array.isArray(out) && out.length > 0) break;
      if (attempt === 0) await new Promise((resolve) => setTimeout(resolve, 1000));
    }
    const rowsOut = Array.isArray(out) ? out : [];
    if (rowsOut.length === 0) {
      ok = false;
      note = "0 streams";
    } else {
      const allUrls = new Set(rowsOut.map((s) => String(s.url || "")));
      let playableCount = 0;
      let silentVariant = false;
      let firstPlayableUrl = "";
      for (const s of rowsOut.slice(0, 3)) {
        const u = String(s.url || "");
        if (!u.startsWith("http")) continue;
        const hdrs = { ...(s.headers || {}) };
        const isM3u8 = /\.m3u8(\?|$)|#\.m3u8|\/secure-hls\/|\/nm\.php|master\.txt|\/sheila\/|\[HLS\]/i.test(u + " " + String(s.title || ""));
        if (!isM3u8 && !hdrs.Range && !hdrs.range) hdrs.Range = "bytes=0-1023";
        try {
          const r = await timedFetch(u, { method: "GET", headers: hdrs });
          if (!r || (r.status < 200 || r.status >= 300)) continue;
          let body = "";
          if (r.body && typeof r.body.getReader === "function") {
            const reader = r.body.getReader();
            const chunks = [];
            let total = 0;
            try {
              while (total < 65536) {
                const { done, value } = await reader.read();
                if (done || !value) break;
                chunks.push(value);
                total += value.byteLength;
              }
            } finally {
              try { await reader.cancel(); } catch {}
            }
            body = Buffer.concat(chunks).toString("utf8");
          } else {
            body = await r.text();
          }
          if (isM3u8 || body.includes("#EXTM3U")) {
            if (!body.includes("#EXTM3U")) continue;
            if (/TYPE=AUDIO|AUDIO=/i.test(body)) {
              const baseDir = u.slice(0, u.lastIndexOf("/"));
              const children = body.split(/\r?\n/).map((l) => l.trim()).filter((l) => l && !l.startsWith("#"));
              if (children.some((c) => allUrls.has(c.startsWith("http") ? c : `${baseDir}/${c}`))) {
                silentVariant = true;
              }
            }
            playableCount++;
            if (!firstPlayableUrl) firstPlayableUrl = u;
          } else if (!/^\s*<!DOCTYPE|^\s*<html/i.test(body)) {
            playableCount++;
            if (!firstPlayableUrl) firstPlayableUrl = u;
          }
        } catch {
          // try next stream candidate
        }
      }
      if (silentVariant) {
        ok = false;
        note = `silent-video-variant (${rowsOut.length} streams split from TYPE=AUDIO master)`;
      } else if (playableCount === 0) {
        ok = false;
        note = `stream-unplayable (${rowsOut.length} stream(s), 0 HTTP 200 media) — ${String(rowsOut[0].url).slice(0, 60)}`;
      } else {
        ok = true;
        note = `${rowsOut.length} stream(s), ${playableCount} verified — ${firstPlayableUrl.slice(0, 60)}`;
      }
    }
  } catch (e) {
    note = e.name === "AbortError" ? `timeout ${timeoutMs}ms` : `threw: ${e.message}`;
  } finally {
    clearTimeout(timer);
  }
  return { id, ok, note };
}

/** NuvioMobile/NuvioTV Test Provider dugmesinin birebir simulasyonu: TMDB "603" + movie/tv/series asla throw/reject/hang etmemeli. */
async function verifyTestProvider(id, file) {
  const code = fs.readFileSync(path.join(providersDir, file), "utf8");
  const head = code.split("\n").slice(0, 12).join("\n");
  const meta = JSON.parse(head.match(/^\/\/\s*@provider\s+(\{.*\})\s*$/m)[1]);
  const types = Array.isArray(meta.types) && meta.types.length ? meta.types : ["movie", "tv"];
  const mobileType = types.includes("movie") ? "movie" : "tv";
  const tvType = types.includes("movie") ? "movie" : "series";
  const probes = mobileType === tvType ? [mobileType] : [mobileType, tvType];
  const module = { exports: {} };
  const controller = new AbortController();
  const timeoutMs = Number(process.env.NUVIO_TEST_PROVIDER_TIMEOUT_MS || 25000);
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  const timedFetch = (url, options = {}) => fetchWithDpi(url, options, controller.signal);
  const sandbox = { module, exports: module.exports, console: { log() {} }, fetch: timedFetch, URL, encodeURIComponent, decodeURIComponent, Promise };
  vm.createContext(sandbox);
  let ok = false;
  let note = "";
  try {
    vm.runInContext(code, sandbox, { filename: file });
    const summary = [];
    for (const mt of probes) {
      const out = await module.exports.getStreams("603", mt, 1, 1);
      if (controller.signal.aborted) {
        throw new Error(`timeout ${timeoutMs}ms on ${mt}`);
      }
      if (!Array.isArray(out)) {
        throw new Error(`${mt}: non-array result`);
      }
      for (const s of out) {
        if (!s || typeof s.url !== "string" || !/^https?:\/\//i.test(s.url)) {
          throw new Error(`${mt}: invalid stream url (${s && s.url})`);
        }
      }
      summary.push(`${mt}=${out.length}`);
    }
    if (controller.signal.aborted) {
      ok = false;
      note = `timeout ${timeoutMs}ms`;
    } else {
      ok = true;
      note = `test-provider ok (${summary.join(", ")})`;
    }
  } catch (e) {
    ok = false;
    note = controller.signal.aborted ? `timeout ${timeoutMs}ms` : `threw: ${e.message}`;
  } finally {
    clearTimeout(timer);
  }
  return { id, ok, note };
}

if (path.resolve(process.argv[1] || "") === import.meta.filename) {
  const files = fs.readdirSync(providersDir).sort().filter((f) => f.endsWith(".js") && (!only || path.basename(f, ".js") === only));
  const rows = new Array(files.length);
  let next = 0;
  const started = Date.now();
  await Promise.all(
    Array.from({ length: Math.min(jobs, files.length) }, async () => {
      while (next < files.length) {
        const i = next++;
        rows[i] = testProvider
          ? await verifyTestProvider(path.basename(files[i], ".js"), files[i])
          : await verify(path.basename(files[i], ".js"), files[i]);
      }
    }),
  );

  const width = Math.max(...rows.map((r) => r.id.length), 4);
  for (const r of rows) console.log(`${r.ok ? "PASS" : "FAIL"} ${r.id.padEnd(width)} ${r.note}`);
  const failed = rows.filter((r) => !r.ok).length;
  console.log(`\n${rows.length - failed}/${rows.length} passed in ${((Date.now() - started) / 1000).toFixed(1)}s (jobs=${jobs})`);
  process.exit(failed ? 1 : 0);
}
