/**
 * Statik Stremio/Nuvio katalog addon'u üretir.
 *
 * Girdi:  nuvio/addon/sources/<id>.json  (site başına bir tarif)
 * Çıktı:  nuvio/addon/manifest.json + nuvio/addon/catalog/<type>/<id>.json
 *
 * Tarif alanları:
 *   { "id": "dizibox", "name": "Dizibox", "type": "series" | "movie",
 *     "url": "https://site/",              // liste sayfası
 *     "item": "<article[\\s\\S]*?</article>", // kartı ayıran regex (global)
 *     "title": "<h[23][^>]*>([\\s\\S]*?)</h[23]>",
 *     "href": "href=\"([^\"]+)\"",           // opsiyonel, sadece doğrulama için
 *     "headers": { "User-Agent": "..." },    // opsiyonel
 *     "limit": 40 }
 *
 * Başlıklar Cinemeta aramasıyla IMDb id'sine çevrilir; meta/akışı Cinemeta ve
 * nuvio plugin'leri verir. Canlı TV satırlarını live.mjs üretir: kanal kataloğu,
 * meta ve `isLive` akışı doğrudan bu addon'dan gelir.
 *
 * Kullanım: node nuvio/addon/build.mjs [--check] [id ...]
 */
import { readFileSync, writeFileSync, readdirSync, mkdirSync, existsSync, rmSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const SRC = join(HERE, "sources");
const OUT = join(HERE, "catalog");
const CACHE = join(HERE, "imdb-cache.json");
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
const ADDON_ID = "org.bigbull.trcatalog";
const MAX_ITEMS = 40;

// Depo sürümü tek yerde: CHANGELOG'un en üst başlığı (cloudstream ile aynı).
function repoVersion() {
  const log = readFileSync(join(HERE, "..", "..", "CHANGELOG.md"), "utf8");
  const m = /^## \[(\d+\.\d+\.\d+)\]/m.exec(log);
  return m ? m[1] : "1.0.0";
}

const argv = process.argv.slice(2);
const check = argv.includes("--check");
const only = argv.filter((a) => !a.startsWith("--"));

const cache = existsSync(CACHE) ? JSON.parse(readFileSync(CACHE, "utf8")) : {};
const recipes = readdirSync(SRC)
  .filter((f) => f.endsWith(".json"))
  .map((f) => JSON.parse(readFileSync(join(SRC, f), "utf8")))
  .filter((r) => !only.length || only.includes(r.id));

const norm = (s) =>
  String(s || "")
    .toLocaleLowerCase("tr")
    .replace(/[ıİ]/g, "i")
    .replace(/[^a-z0-9]+/g, " ")
    .trim();

async function get(url, headers) {
  const r = await fetch(url, { headers: { "User-Agent": UA, ...(headers || {}) }, signal: AbortSignal.timeout(15000) });
  if (!r.ok) throw new Error(`HTTP ${r.status} ${url}`);
  return r.text();
}

function stripTags(html) {
  return String(html)
    .replace(/<[^>]*>/g, " ")
    .replace(/&quot;/g, '"')
    .replace(/&#0?39;|&apos;/g, "'")
    .replace(/&amp;/g, "&")
    .replace(/&#x([0-9a-f]+);/gi, (_, h) => String.fromCodePoint(parseInt(h, 16)))
    .replace(/&#(\d+);/g, (_, d) => String.fromCodePoint(+d))
    .replace(/&nbsp;/g, " ")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/\s+/g, " ")
    .trim();
}

// Başlık -> IMDb id (Cinemeta'da anahtarsız arama).
async function resolve(type, title) {
  const key = type + "|" + norm(title);
  if (Object.prototype.hasOwnProperty.call(cache, key)) return cache[key];
  const kind = type === "movie" ? "movie" : "series";
  const url = `https://v3-cinemeta.strem.io/catalog/${kind}/top/search=${encodeURIComponent(title)}.json`;
  let metas = [];
  try {
    metas = JSON.parse(await get(url)).metas || [];
  } catch {
    metas = [];
  }
  const want = norm(title);
  const hit =
    metas.find((m) => norm(m.name) === want) ||
    metas.find((m) => {
      const mn = norm(m.name);
      return mn.length >= 3 && want.length >= 3 && (mn.startsWith(want + " ") || want.startsWith(mn + " "));
    });
  const value = hit ? { id: hit.id, poster: String(hit.poster || hit.background || "").replace("/small/", "/medium/") } : null;
  cache[key] = value;
  if (!value) console.warn(`  ~ çözülemedi: ${title}`);
  return value;
}

const written = [];
for (const rc of recipes) {
  let items;
  try {
    const html = await get(rc.url, rc.headers);
    const blocks = html.match(new RegExp(rc.item, "gi")) || [];
    const titleRe = new RegExp(rc.title, "i");
    const seen = new Set();
    items = [];
    for (const b of blocks) {
      const m = titleRe.exec(b);
      const title = m ? stripTags(m[1]) : "";
      if (title.length < 2) continue;
      const k = norm(title);
      if (seen.has(k)) continue;
      seen.add(k);
      items.push({ title, href: rc.href ? (new RegExp(rc.href, "i").exec(b) || [])[1] : undefined });
      if (items.length >= (rc.limit || MAX_ITEMS)) break;
    }
  } catch (e) {
    console.error(`! ${rc.id}: ${e.message}`);
    continue;
  }
  if (items.length < 8) {
    console.error(`! ${rc.id}: yalnız ${items.length} kart (en az 8 gerekli)`);
    continue;
  }
  const metas = [];
  for (const it of items) {
    const hit = await resolve(rc.type, it.title);
    if (!hit) continue;
    if (metas.some((m) => m.id === hit.id)) continue;
    metas.push({ id: hit.id, type: rc.type, name: it.title, poster: hit.poster });
  }
  if (metas.length < 5) {
    console.error(`! ${rc.id}: ${items.length} kart, ${metas.length} IMDb eşleşmesi (en az 5 gerekli)`);
    continue;
  }
  if (check) {
    console.log(`ok ${rc.id} (${rc.type}) ${metas.length}/${items.length}`);
    continue;
  }
  const dir = join(OUT, rc.type);
  mkdirSync(dir, { recursive: true });
  writeFileSync(join(dir, rc.id + ".json"), JSON.stringify({ metas }) + "\n");
  written.push({ id: rc.id, type: rc.type, name: rc.name, count: metas.length });
  console.log(`ok ${rc.id} (${rc.type}) ${metas.length}/${items.length}`);
}

if (!check) {
  writeFileSync(CACHE, JSON.stringify(cache, null, 1) + "\n");
  // Tarifi silinen katalogları kaldır; geçici hata alan tarifin eski listesi korunur.
  const allRecipes = readdirSync(SRC)
    .filter((f) => f.endsWith(".json"))
    .map((f) => JSON.parse(readFileSync(join(SRC, f), "utf8")));
  for (const t of ["movie", "series"]) {
    const d = join(OUT, t);
    if (!existsSync(d)) continue;
    for (const f of readdirSync(d)) {
      const id = f.replace(/\.json$/, "");
      if (!allRecipes.some((r) => r.id === id && r.type === t)) rmSync(join(d, f));
    }
  }
  const all = [];
  for (const t of ["movie", "series"]) {
    const d = join(OUT, t);
    if (!existsSync(d)) continue;
    for (const f of readdirSync(d)) {
      const id = f.replace(/\.json$/, "");
      const rc = allRecipes.find((r) => r.id === id && r.type === t);
      all.push({ type: t, id, name: (rc && rc.name) || id });
    }
  }
  for (const t of ["movie", "series"]) {
    const metas = [];
    const seen = new Set();
    for (const entry of all.filter((c) => c.type === t)) {
      const file = join(OUT, t, entry.id + ".json");
      if (!existsSync(file)) continue;
      const rows = JSON.parse(readFileSync(file, "utf8")).metas || [];
      for (const row of rows) {
        if (!row || !row.id || !row.name || seen.has(row.id)) continue;
        seen.add(row.id);
        metas.push(row);
      }
    }
    if (metas.length) {
      writeFileSync(join(OUT, t, "all.json"), JSON.stringify({ metas }) + "\n");
      all.push({ type: t, id: "all", name: t === "movie" ? "Tümü - Filmler" : "Tümü - Diziler" });
    }
  }
  all.sort((a, b) => a.name.localeCompare(b.name, "tr"));
  const manifest = {
    id: ADDON_ID,
    version: repoVersion(),
    name: "BigBull TR Katalog",
    description:
      "Cloudstream sağlayıcılarının film ve dizi listeleri. Katalog verir; meta ve akışı Cinemeta ile BigBull Nuvio eklentileri sağlar; canlı TV ayrı addon'dadır.",
    resources: ["catalog"],
    types: ["movie", "series"],
    catalogs: all.map((c) => ({ type: c.type, id: c.id, name: c.name })),
  };
  writeFileSync(join(HERE, "manifest.json"), JSON.stringify(manifest, null, 2) + "\n");
  console.log(`manifest: ${all.length} katalog`);
}
