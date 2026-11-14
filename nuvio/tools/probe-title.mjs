#!/usr/bin/env node
/**
 * Tek başlık için TÜM Nuvio eklentilerini paralel çalıştırır (saniyeler içinde matris).
 *
 *   node nuvio/tools/probe-title.mjs tt13675832 tv 1 1
 *   node nuvio/tools/probe-title.mjs 550 movie 1 1
 *
 * Nuvio'nun yaptığını taklit eder: her provider modülü vm sandbox'ta yüklenir,
 * getStreams(id, type, season, episode) çağrılır; adet + ilk URL/başlık basılır.
 * Çıkış kodu: herhangi bir provider hata fırlatırsa 1.
 */
import { readFileSync, readdirSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import vm from "node:vm";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const [, , id = "tt13675832", type = "tv", season = "1", episode = "1"] = process.argv;
const manifest = JSON.parse(readFileSync(join(ROOT, "manifest.json"), "utf8"));
const files = readdirSync(join(ROOT, "providers")).filter((f) => f.endsWith(".js"));

function load(file) {
  const module = { exports: {} };
  const sandbox = {
    module,
    exports: module.exports,
    console: { log: () => {} },
    fetch,
    URL,
    encodeURIComponent,
    decodeURIComponent,
    Promise,
    setTimeout,
  };
  vm.createContext(sandbox);
  vm.runInContext(readFileSync(join(ROOT, "providers", file), "utf8"), sandbox, { filename: file });
  return module.exports.getStreams;
}

async function run(file) {
  const started = Date.now();
  try {
    const streams = await load(file)(id, type, season, episode);
    const rows = Array.isArray(streams) ? streams : [];
    const first = rows[0] || {};
    return {
      id: file.replace(/\.js$/, ""),
      n: rows.length,
      ms: Date.now() - started,
      title: String(first.title || first.name || "").slice(0, 40),
      url: String(first.url || "").slice(0, 60),
    };
  } catch (e) {
    return { id: file.replace(/\.js$/, ""), n: -1, ms: Date.now() - started, error: String(e && e.message || e).slice(0, 80) };
  }
}

const limit = 8;
const out = [];
let next = 0;
await Promise.all(Array.from({ length: limit }, async () => {
  for (;;) {
    const i = next++;
    if (i >= files.length) return;
    out[i] = await run(files[i]);
  }
}));
const sorted = out.filter(Boolean).sort((a, b) => (b.n - a.n) || a.id.localeCompare(b.id));
let bad = 0;
console.log(`id=${id} type=${type} s=${season} e=${episode} · ${sorted.length} provider`);
for (const r of sorted) {
  if (r.n < 0) bad++;
  const state = r.n < 0 ? `ERR ${r.error}` : r.n === 0 ? "0" : `${r.n} stream`;
  console.log(`${r.id.padEnd(20)} ${state.padEnd(10)} ${String(r.ms).padStart(5)}ms ${r.n > 0 ? r.title + " | " + r.url : ""}`);
}
const hit = sorted.filter((r) => r.n > 0).map((r) => r.id);
console.log(`\nakış verenler (${hit.length}): ${hit.join(", ") || "-"}`);
process.exit(bad ? 1 : 0);
