#!/usr/bin/env node
/**
 * Regenerates nuvio/manifest.json from the `// @provider {...}` header that every
 * provider file carries. Keeps the repo meta block, so the manifest never needs
 * hand editing when a provider lands or changes type/format.
 */
import fs from "node:fs";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "..");
const providersDir = path.join(root, "providers");
const manifestPath = path.join(root, "manifest.json");

// Depo sürümü tek yerde: CHANGELOG'un en üst başlığı.
function repoVersion() {
  const log = fs.readFileSync(path.join(root, "..", "CHANGELOG.md"), "utf8");
  const m = /^## \[(\d+\.\d+\.\d+)\]/m.exec(log);
  return m ? m[1] : current.version || "1.0.0";
}

// CHANGELOG'daki "**<Ad> v<N>**: ..." satırlarından sağlayıcı başına sürüm; ilk eşleşme en güncel.
function changelogVersions() {
  const log = fs.readFileSync(path.join(root, "..", "CHANGELOG.md"), "utf8");
  const map = new Map();
  for (const m of log.matchAll(/\*\*([^*]+?) v(\d+)\*\*/g)) {
    const key = m[1].toLocaleLowerCase("tr").trim();
    if (!map.has(key)) map.set(key, Number(m[2]));
  }
  return map;
}

// Sürüm yalnız ileri gider: aday, saklanan değerden düşükse saklanan kalır.
function bumpVersion(cand, base) {
  if (!cand) return base;
  if (!base) return cand;
  const c = cand.split(".").map(Number);
  const b = base.split(".").map(Number);
  for (let i = 0; i < Math.max(c.length, b.length); i++) {
    if ((c[i] || 0) > (b[i] || 0)) return cand;
    if ((c[i] || 0) < (b[i] || 0)) return base;
  }
  return base;
}

const changelogVer = changelogVersions();

const current = fs.existsSync(manifestPath)
  ? JSON.parse(fs.readFileSync(manifestPath, "utf8"))
  : { name: "BB Nuvio", version: "1.0.0", description: "Bigbull Turkish streaming providers for Nuvio, running on-device.", author: "bigbull", scrapers: [] };

const previous = new Map((current.scrapers || []).map((s) => [s.id, s]));
const scrapers = [];
const problems = [];

for (const file of fs.readdirSync(providersDir).sort()) {
  if (!file.endsWith(".js")) continue;
  const head = fs.readFileSync(path.join(providersDir, file), "utf8").split("\n").slice(0, 12).join("\n");
  const line = head.match(/^\/\/\s*@provider\s+(\{.*\})\s*$/m);
  if (!line) {
    problems.push(`${file}: missing "// @provider {...}" header`);
    continue;
  }
  let meta;
  try {
    meta = JSON.parse(line[1]);
  } catch (e) {
    problems.push(`${file}: header is not valid JSON (${e.message})`);
    continue;
  }
  for (const key of ["id", "name", "types", "domain"]) {
    if (!meta[key]) problems.push(`${file}: header missing "${key}"`);
  }
  const id = meta.id || path.basename(file, ".js");
  const base = previous.get(id)?.version || "1.0.0";
  const cv = changelogVer.get(String(meta.name || id).toLocaleLowerCase("tr").trim());
  const candidate = meta.version !== undefined ? String(meta.version) : cv !== undefined ? `1.0.${cv}` : null;
  scrapers.push({
    id,
    name: meta.name || id,
    description: meta.description || `${meta.name || id} kaynaklari.`,
    version: bumpVersion(candidate, base),
    author: "bigbull",
    supportedTypes: meta.types,
    filename: `providers/${file}`,
    // "verified": false = could not be proven from this egress (IP ban/CF); shipped off by default.
    enabled: meta.verified !== false,
    formats: meta.formats || ["m3u8"],
    logo: `https://www.google.com/s2/favicons?domain=${meta.domain}&sz=128`,
    contentLanguage: ["tr"]
  });
}

if (problems.length) {
  console.error(problems.join("\n"));
  if (process.argv.includes("--strict")) process.exit(1);
  console.error(`(skipped ${problems.length} file(s); --strict fails hard)`);
}

scrapers.sort((a, b) => a.id.localeCompare(b.id));
const next = { ...current, version: repoVersion(), scrapers };
fs.writeFileSync(manifestPath, JSON.stringify(next, null, 2) + "\n");
console.log(`manifest.json: ${scrapers.length} provider(s)`);
