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
import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";

const root = path.resolve(import.meta.dirname, "..");
const providersDir = path.join(root, "providers");
const argv = process.argv.slice(2);
const jobsAt = argv.indexOf("--jobs");
const jobs = Math.max(1, Number(jobsAt >= 0 ? argv[jobsAt + 1] : process.env.NUVIO_VERIFY_JOBS || 6) || 6);
const only = argv.find((a, i) => !a.startsWith("--") && i !== jobsAt + 1);
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
  const timedFetch = async (url, options = {}) => {
    const res = await fetch(url, { ...options, signal: controller.signal });
    if (res.status === 403) {
      const viaCurl = curlFallback(url, options);
      if (viaCurl) return viaCurl;
    }
    return res;
  };
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
        const isM3u8 = /\.m3u8(\?|$)|\/nm\.php|master\.txt|\/sheila\/|\[HLS\]/i.test(u + " " + String(s.title || ""));
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
