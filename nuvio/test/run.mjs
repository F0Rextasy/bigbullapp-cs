#!/usr/bin/env node
/**
 * Nuvio provider harness for bb-nuvio.
 *
 * Runs each provider the way Nuvio's QuickJS sandbox does: a bare CommonJS
 * module in a context that has `fetch` and nothing else (no timers, no Buffer,
 * no Node builtins). Catches the mistakes that only show up on-device.
 *
 *   node nuvio/test/run.mjs                # static + manifest checks only
 *   node nuvio/test/run.mjs diziyou        # + sandbox load of one provider
 *   LIVE=1 node nuvio/test/run.mjs diziyou tt0903747 tv 1 1
 */
import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";

const root = path.resolve(import.meta.dirname, "..");
const manifest = JSON.parse(fs.readFileSync(path.join(root, "manifest.json"), "utf8"));
const failures = [];
const checks = [];

function check(ok, label) {
  checks.push((ok ? "ok   " : "FAIL ") + label);
  if (!ok) failures.push(label);
}

// "X-Requested-With: XMLHttpRequest" is a plain HTTP header, so only the API itself is banned.
const BANNED = [/setTimeout\s*\(/, /setInterval\s*\(/, /\bBuffer\b/, /require\(\s*["'](?:fs|path|http|https|node:)/, /\bnew\s+XMLHttpRequest\b/, /\bprocess\b/, /require\s*\(/];

for (const scraper of manifest.scrapers) {
  const file = path.join(root, scraper.filename);
  check(fs.existsSync(file), `${scraper.id}: ${scraper.filename} exists`);
  if (!fs.existsSync(file)) continue;

  const code = fs.readFileSync(file, "utf8");
  for (const re of BANNED) {
    check(!re.test(code), `${scraper.id}: no ${re.source}`);
  }
  check(/module\.exports\s*=\s*\{\s*getStreams\s*\}/.test(code), `${scraper.id}: exports getStreams`);
  check(!/\basync\s/.test(code) && !/\bawait\s/.test(code), `${scraper.id}: promise chains only`);
}

const declared = new Set(manifest.scrapers.map((s) => path.basename(s.filename)));
for (const file of fs.readdirSync(path.join(root, "providers"))) {
  if (file.endsWith(".js")) check(declared.has(file), `${file}: declared in manifest`);
}

const wanted = process.argv[2];
if (wanted) {
  const scraper = manifest.scrapers.find((s) => s.id === wanted);
  if (!scraper) {
    check(false, `${wanted}: in manifest`);
  } else {
    const file = path.join(root, scraper.filename);
    const module = { exports: {} };
    const sandbox = {
      module,
      exports: module.exports,
      console: { log: (...a) => console.log("  plugin:", ...a) },
      fetch,
      URL,
      encodeURIComponent,
      decodeURIComponent,
      Promise
    };
    vm.createContext(sandbox);
    try {
      vm.runInContext(fs.readFileSync(file, "utf8"), sandbox, { filename: scraper.filename });
      check(typeof module.exports.getStreams === "function", `${scraper.id}: getStreams is callable in sandbox`);
    } catch (e) {
      check(false, `${scraper.id}: sandbox load (${e.message})`);
    }

    if (process.env.LIVE) {
      const [, , , id = "tt0903747", type = "tv", season = "1", episode = "1"] = process.argv;
      const out = await module.exports.getStreams(id, type, season, episode);
      const rows = Array.isArray(out) ? out : [];
      check(Array.isArray(out), `${scraper.id}: returns an array`);
      check(rows.length > 0, `${scraper.id}: ${rows.length} stream(s)`);
      for (const row of rows) {
        check(typeof row.url === "string" && /^https?:/.test(row.url), `${scraper.id}: url ${String(row.url).slice(0, 70)}`);
        check(typeof row.title === "string" && row.title.length > 0, `${scraper.id}: title ${row.title}`);
      }
      console.log(JSON.stringify(rows, null, 2));
    }
  }
}

console.log(checks.join("\n"));
console.log(failures.length ? `\n${failures.length} check(s) failed` : `\nall ${checks.length} checks passed`);
process.exit(failures.length ? 1 : 0);
