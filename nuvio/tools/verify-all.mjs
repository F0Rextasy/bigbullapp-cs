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
import { execFileSync } from "node:child_process";
import dns from "node:dns/promises";
import fs from "node:fs";
import net from "node:net";
import path from "node:path";
import { Duplex } from "node:stream";
import tls from "node:tls";
import vm from "node:vm";
import zlib from "node:zlib";

const root = path.resolve(import.meta.dirname, "..");
const providersDir = path.join(root, "providers");
const argv = process.argv.slice(2);
const jobsAt = argv.indexOf("--jobs");
const jobs = Math.max(1, Number(jobsAt >= 0 ? argv[jobsAt + 1] : process.env.NUVIO_VERIFY_JOBS || 6) || 6);
const only = argv.find((a, i) => !a.startsWith("--") && (jobsAt < 0 || i !== jobsAt + 1));
const curlBin = process.platform === "win32" ? "curl.exe" : "curl";

/** Fallback when Cloudflare/CDN blocks Node/undici's TLS JA3 with 403 while curl/Android works (AGENTS.md). */
function curlFallback(url, options = {}) {
  try {
    const args = ["-sL", "--max-time", "15", "-w", "\n__HTTP_STATUS__:%{http_code}"];
    if (options.method && options.method !== "GET") args.push("-X", options.method);
    const hdrs = options.headers || {};
    for (const [k, v] of Object.entries(hdrs)) {
      if (v != null) args.push("-H", `${k}: ${v}`);
    }
    if (options.body) args.push("--data-raw", String(options.body));
    args.push(String(url));
    const raw = execFileSync(curlBin, args, { encoding: "utf8", maxBuffer: 16 * 1024 * 1024 });
    const idx = raw.lastIndexOf("\n__HTTP_STATUS__:");
    const status = idx >= 0 ? Number(raw.slice(idx + 17).trim()) || 0 : 200;
    const body = idx >= 0 ? raw.slice(0, idx) : raw;
    if (status < 200 || status >= 400) return null;
    return {
      ok: status >= 200 && status < 300,
      status,
      url: String(url),
      text: async () => body,
      json: async () => JSON.parse(body),
    };
  } catch {
    return null;
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
              const sp = Math.min(45, recLen - 8);
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
          let bodyBuf = buf.subarray(sep + 4);
          const status = Number((headStr.match(/^HTTP\/\d\.\d\s+(\d+)/) || [])[1]) || 0;
          const loc = (headStr.match(/^location:\s*(.+)$/im) || [])[1]?.trim();
          if ([301, 302, 303, 307, 308].includes(status) && loc && redirects > 0) {
            return done(resolve, dpiFetch(new URL(loc, u).toString(), options, redirects - 1));
          }
          if (/^transfer-encoding:\s*chunked/im.test(headStr)) bodyBuf = decodeChunked(bodyBuf);
          const enc = (headStr.match(/^content-encoding:\s*(\S+)/im) || [])[1]?.toLowerCase();
          if (enc === "gzip") bodyBuf = zlib.gunzipSync(bodyBuf);
          else if (enc === "deflate") bodyBuf = zlib.inflateSync(bodyBuf);
          const bodyText = bodyBuf.toString("utf8");
          done(resolve, {
            ok: status >= 200 && status < 300,
            status,
            url: u.toString(),
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
      res = await fetch(url, reqOpts);
    } catch (e) {
      if (signal && signal.aborted) throw e;
      dpiHosts.add(hostname);
      res = await dpiFetch(url, reqOpts);
    }
  }
  if (res.status === 403) {
    const viaCurl = curlFallback(url, options);
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

if (path.resolve(process.argv[1] || "") === import.meta.filename) {
  const files = fs.readdirSync(providersDir).sort().filter((f) => f.endsWith(".js") && (!only || path.basename(f, ".js") === only));
  const rows = new Array(files.length);
  let next = 0;
  const started = Date.now();
  await Promise.all(
    Array.from({ length: Math.min(jobs, files.length) }, async () => {
      while (next < files.length) {
        const i = next++;
        rows[i] = await verify(path.basename(files[i], ".js"), files[i]);
      }
    }),
  );

  const width = Math.max(...rows.map((r) => r.id.length), 4);
  for (const r of rows) console.log(`${r.ok ? "PASS" : "FAIL"} ${r.id.padEnd(width)} ${r.note}`);
  const failed = rows.filter((r) => !r.ok).length;
  console.log(`\n${rows.length - failed}/${rows.length} passed in ${((Date.now() - started) / 1000).toFixed(1)}s (jobs=${jobs})`);
  process.exit(failed ? 1 : 0);
}
