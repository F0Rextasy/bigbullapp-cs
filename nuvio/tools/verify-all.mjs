#!/usr/bin/env node
/**
 * Re-verifies every shipped provider against the live site using the sample
 * recorded in its `// @provider` header (`sample: "imdbId:type:season:episode"`).
 *
 *   node nuvio/tools/verify-all.mjs           # all providers, sequential
 *   node nuvio/tools/verify-all.mjs diziyou   # one provider
 *
 * Prints a PASS/FAIL table; exit code 1 if any provider returns no stream.
 */
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";

const root = path.resolve(import.meta.dirname, "..");
const providersDir = path.join(root, "providers");
const only = process.argv[2];
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

const rows = [];
for (const file of fs.readdirSync(providersDir).sort()) {
  if (!file.endsWith(".js")) continue;
  const id = path.basename(file, ".js");
  if (only && only !== id) continue;
  const code = fs.readFileSync(path.join(providersDir, file), "utf8");
  const head = code.split("\n").slice(0, 12).join("\n");
  const meta = JSON.parse(head.match(/^\/\/\s*@provider\s+(\{.*\})\s*$/m)[1]);
  if (!meta.sample) {
    rows.push({ id, ok: false, note: "no sample in header" });
    continue;
  }
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
    ok = rowsOut.length > 0;
    note = ok ? `${rowsOut.length} stream(s) — ${String(rowsOut[0].url).slice(0, 60)}` : "0 streams";
  } catch (e) {
    note = e.name === "AbortError" ? `timeout ${timeoutMs}ms` : `threw: ${e.message}`;
  } finally {
    clearTimeout(timer);
  }
  rows.push({ id, ok, note });
}

const width = Math.max(...rows.map((r) => r.id.length), 4);
for (const r of rows) console.log(`${r.ok ? "PASS" : "FAIL"} ${r.id.padEnd(width)} ${r.note}`);
const failed = rows.filter((r) => !r.ok).length;
console.log(`\n${rows.length - failed}/${rows.length} passed`);
process.exit(failed ? 1 : 0);
