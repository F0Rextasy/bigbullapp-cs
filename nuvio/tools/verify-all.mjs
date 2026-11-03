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
import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";

const root = path.resolve(import.meta.dirname, "..");
const providersDir = path.join(root, "providers");
const only = process.argv[2];

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
  const timedFetch = (url, options = {}) => fetch(url, { ...options, signal: controller.signal });
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
