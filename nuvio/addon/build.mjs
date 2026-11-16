import { execFileSync } from "node:child_process";
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
 *     "strip": "\\s+Full\\s+Türkçe.*$",      // opsiyonel, başlıktan kırpılır
 *     "poster": "src=\"([^\"]+)\"",          // opsiyonel, karttan kapak
 *     "imdb": "\\b(tt\\d{7,9})\\b",          // opsiyonel, karttan IMDb id
 *     "href": "href=\"([^\"]+)\"",           // opsiyonel, sadece doğrulama için
 *     "headers": { "User-Agent": "..." },    // opsiyonel
 *     "group": ["popular-series"],           // opsiyonel, toplu satıra girer
 *     "hidden": true,                        // opsiyonel, tek başına satır olmaz
 *     "limit": 40 }
 *
 * Başlıklar IMDb suggestion (anahtarsız) ile `tt` id'sine çevrilir, bulunamazsa
 * TVMaze araması (externals.imdb) denenir. Meta ve akışı Nuvio'nun metadata
 * zenginleştirmesi/addonları ve BigBull Nuvio eklentileri sağlar. Canlı TV satırlarını
 * live.mjs üretir: kanal kataloğu, meta ve `isLive` akışı doğrudan bu addon'dan gelir.
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

// Ana ekranda en üstte duran toplu satırlar (tariflerdeki `group` alanıyla beslenir).
const GROUPS = [
  { id: "latest-series", type: "series", name: "1 · Son Eklenen Diziler" },
  { id: "latest-movie", type: "movie", name: "2 · Son Eklenen Filmler" },
  { id: "popular-series", type: "series", name: "3 · Popüler Diziler" },
];

// Depo sürümü tek yerde: CHANGELOG'un en üst başlığı (cloudstream ile aynı).
function repoVersion() {
  const log = readFileSync(join(HERE, "..", "..", "CHANGELOG.md"), "utf8");
  const m = /^## \[(\d+\.\d+\.\d+)\]/m.exec(log);
  return m ? m[1] : "1.0.0";
}

const argv = process.argv.slice(2);
const check = argv.includes("--check");
const only = argv.filter((a) => !a.startsWith("--"));
const DEFAULT_HEADERS = {
  "User-Agent": UA,
  Accept: "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
  "Accept-Language": "tr-TR,tr;q=0.9",
};

// Wikimedia tarayıcı UA'sını reddediyor; iletişim adresi taşıyan UA şart.
const WD_HEADERS = { "User-Agent": "BigBullCatalog/1.0 (https://github.com/F0Rextasy/bigbullapp-cs)" };
// Bazı kaynaklar Node fetch'in TLS parmak izine 403 döner; aynı başlıklarla curl geçer.
async function get(url, headers) {
  const hdrs = { ...DEFAULT_HEADERS, ...(headers || {}) };
  let status = 0;
  try {
    const r = await fetch(url, { headers: hdrs, signal: AbortSignal.timeout(15000) });
    if (r.ok) return r.text();
    status = r.status;
  } catch {
    status = 0;
  }
  if (status && status !== 403 && status !== 429) throw new Error(`HTTP ${status} ${url}`);
  const args = ["-sSL", "--max-time", "25"];
  for (const [k, v] of Object.entries(hdrs)) args.push("-H", `${k}: ${v}`);
  args.push(url);
  try {
    const body = execFileSync("curl", args, { encoding: "utf8", maxBuffer: 64 * 1024 * 1024 });
    if (body) return body;
  } catch {
    /* curl de başarısız */
  }
  throw new Error(`HTTP ${status || "network"} ${url}`);
}
const recipes = readdirSync(SRC)
  .filter((f) => f.endsWith(".json"))
  .map((f) => JSON.parse(readFileSync(join(SRC, f), "utf8")))
  .filter((r) => !only.length || only.includes(r.id));

const cache = existsSync(CACHE) ? JSON.parse(readFileSync(CACHE, "utf8")) : {};

const TR_FOLD = { ç: "c", ğ: "g", ı: "i", ö: "o", ş: "s", ü: "u", â: "a", î: "i", û: "u" };
const norm = (s) =>
  String(s || "")
    .toLocaleLowerCase("tr")
    .replace(/[çğıöşüâîû]/g, (c) => TR_FOLD[c] || c)
    .replace(/[^a-z0-9]+/g, " ")
    .trim();

const STOP_WORDS = new Set(["the", "a", "an", "of", "in", "on", "at", "to", "for", "and", "or", "ve", "bir", "ile", "da", "de"]);

function pickPoster(hit) {
  return String((hit && hit.i && hit.i.imageUrl) || "").trim();
}

function yearFits(hitYear, wantYear, maxDiff = 2) {
  if (!wantYear || !hitYear) return true;
  return Math.abs(Number(hitYear) - Number(wantYear)) <= maxDiff;
}


const ENTITIES = {
  ouml: "ö", uuml: "ü", auml: "ä", Ouml: "Ö", Uuml: "Ü", Auml: "Ä", szlig: "ß",
  ccedil: "ç", Ccedil: "Ç", aring: "å", oslash: "ø", aelig: "æ", ntilde: "ñ",
  eacute: "é", egrave: "è", agrave: "à", iacute: "í", oacute: "ó", uacute: "ú",
  quot: '"', apos: "'", nbsp: " ", amp: "&", lt: "<", gt: ">",
};

function stripTags(html) {
  return String(html)
    .replace(/<[^>]*>/g, " ")
    .replace(/&([a-zA-Z]{2,8});/g, (m, k) => ENTITIES[k] ?? m)
    .replace(/&#x([0-9a-f]+);/gi, (_, h) => String.fromCodePoint(parseInt(h, 16)))
    .replace(/&#(\d+);/g, (_, d) => String.fromCodePoint(+d))
    .trim();
}

// Başlık -> IMDb id. Kaynak: IMDb suggestion (anahtar yok); olmazsa TVMaze.
// "Amansız - The Furious" gibi çift dilli başlıklarda parçalar da denenir.
function titleCandidates(title) {
  const parts = String(title)
    .split(/\s+[-–—/|]\s+/)
    .map((p) => p.trim())
    .filter((p) => p.length >= 3);
  return [...new Set([String(title).trim(), ...parts])];
}

function kindFits(kind, q) {
  const s = String(q || "").toLowerCase();
  if (!s) return true;
  if (kind === "series") return /series|tv mini|tv special|animation/.test(s);
  return !/series|mini-series/.test(s);
}

async function fetchImdbHits(title) {
  try {
    const d = JSON.parse(await get(`https://v3.sg.media-imdb.com/suggestion/x/${encodeURIComponent(title)}.json`));
    return ((d && d.d) || []).filter((h) => h && /^tt\d+$/.test(String(h.id || "")));
  } catch {
    return [];
  }
}

function bestByYear(list, year) {
  if (!list.length) return null;
  if (!year) return list[0];
  const near = list.filter((h) => yearFits(h.y, year, 2));
  if (!near.length) return null;
  near.sort((a, b) => Math.abs((Number(a.y) || year) - year) - Math.abs((Number(b.y) || year) - year));
  return near[0];
}

function imdbExact(hits, want, kind, year) {
  const exact = hits.filter((h) => {
    if (!h.q || !kindFits(kind, h.q)) return false;
    const n = norm(h.l);
    return n === want || (!want.startsWith("the ") && n === "the " + want);
  });
  const eHit = bestByYear(exact, year);
  return eHit ? { id: eHit.id, poster: pickPoster(eHit) } : null;
}

function imdbFuzzy(hits, want, kind, year) {
  const prefix = hits.filter((h) => {
    if (!h.q || !kindFits(kind, h.q)) return false;
    const n = norm(h.l);
    return want.length >= 6 && n.length >= 6 && (n.startsWith(want + " ") || want.startsWith(n + " "));
  });
  const pHit = bestByYear(prefix, year);
  if (pHit) return { id: pHit.id, poster: pickPoster(pHit) };

  // Yerelleştirilmiş veya alt başlıklı adlar: anlamsız bağlaçlar hariç ≥2 ortak kelime.
  const wantTokens = new Set(want.split(" ").filter((t) => t.length > 1 && !STOP_WORDS.has(t)));
  if (wantTokens.size >= 2) {
    const overlap = hits.filter((h) => {
      if (!h.q || !kindFits(kind, h.q)) return false;
      const count = norm(h.l).split(" ").filter((t) => t.length > 1 && !STOP_WORDS.has(t) && wantTokens.has(t)).length;
      return count >= 2;
    });
    const oHit = bestByYear(overlap, year);
    if (oHit) return { id: oHit.id, poster: pickPoster(oHit) };
  }
  return null;
}

function imdbAkaByYear(hits, kind, year) {
  if (!year) return null;
  const near = hits.filter((h) => h.q && kindFits(kind, h.q) && h.y && Math.abs(Number(h.y) - Number(year)) <= 1);
  if (!near.length) return null;
  near.sort((a, b) => Math.abs(Number(a.y) - year) - Math.abs(Number(b.y) - year));
  return { id: near[0].id, poster: pickPoster(near[0]) };
}

async function imdbById(id, kind, year) {
  try {
    const d = JSON.parse(await get(`https://v3.sg.media-imdb.com/suggestion/x/${encodeURIComponent(id)}.json`));
    const hit = ((d && d.d) || []).find((h) => h && h.id === id);
    if (hit && kindFits(kind, hit.q) && yearFits(hit.y, year, 2)) {
      return { id, poster: pickPoster(hit) };
    }
  } catch {
    /* yok say */
  }
  return null;
}

// Yerelleştirilmiş adlar ("Örümcek-Adam: Eve Dönüş Yok") IMDb'de İngilizce döner.
// Wikidata araması Türkçe etiketi tanır; P345 (IMDb id) yalnız etiket tam eşleşince ve
// P31 türü satırla uyumluysa kabul edilir.
const WD_TYPES = {
  movie: new Set(["Q11424", "Q506240", "Q202866", "Q24856"]),
  series: new Set(["Q5398426", "Q1259759"]),
};
async function wikidataHit(title, want, kind, year) {
  for (const lang of ["tr", "en"]) {
    try {
      const s = JSON.parse(await get(
        `https://www.wikidata.org/w/api.php?action=wbsearchentities&search=${encodeURIComponent(title)}&language=${lang}&uselang=${lang}&format=json&limit=8&origin=*`, WD_HEADERS));
      const ids = (s.search || [])
        .filter((x) => norm(x.label) === want || norm(x.match && x.match.text) === want)
        .map((x) => x.id)
        .slice(0, 6);
      if (!ids.length) continue;
      const e = JSON.parse(await get(
        `https://www.wikidata.org/w/api.php?action=wbgetentities&ids=${ids.join("|")}&props=claims&format=json&origin=*`, WD_HEADERS));
      for (const id of ids) {
        const claims = (((e.entities || {})[id] || {}).claims) || {};
        const types = (claims.P31 || [])
          .map((c) => c && c.mainsnak && c.mainsnak.datavalue && c.mainsnak.datavalue.value && c.mainsnak.datavalue.value.id)
          .filter(Boolean);
        if (!types.some((t) => WD_TYPES[kind].has(t))) continue;
        const claim = claims.P345;
        const imdb = claim && claim[0] && claim[0].mainsnak && claim[0].mainsnak.datavalue
          ? String(claim[0].mainsnak.datavalue.value || "")
          : "";
        if (!/^tt\d+$/.test(imdb)) continue;
        const verified = await imdbById(imdb, kind, year);
        if (verified) return verified;
        if (!year) return { id: imdb, poster: "" };
      }
    } catch {
      /* sıradaki dil */
    }
  }
  return null;
}

async function tvmazeHit(title, want, year) {
  try {
    const arr = JSON.parse(await get(`https://api.tvmaze.com/search/shows?q=${encodeURIComponent(title)}`));
    for (const row of Array.isArray(arr) ? arr : []) {
      const show = row && row.show;
      const imdb = show && show.externals && show.externals.imdb;
      if (!show || !/^tt\d+$/.test(String(imdb || ""))) continue;
      const n = norm(show.name);
      const sy = show.premiered ? Number(String(show.premiered).slice(0, 4)) : 0;
      if ((n === want || (n.length >= 4 && want.length >= 4 && n.startsWith(want + " "))) && yearFits(sy, year, 2)) {
        return { id: imdb, poster: (show.image && show.image.original) || "" };
      }
    }
  } catch {
    /* yok say */
  }
  return null;
}

async function resolve(type, title, year) {
  const baseKey = type + "|" + norm(title);
  const key = year ? `${type}|${year}|${norm(title)}` : baseKey;
  const cached = cache[key] || (!year ? cache[baseKey] : null);
  if (cached && cached.id) return cached;
  let value = null;
  for (const cand of titleCandidates(title)) {
    const want = norm(cand);
    if (!want) continue;
    const hits = await fetchImdbHits(cand);
    value = imdbExact(hits, want, type, year)
      || (await wikidataHit(cand, want, type, year))
      || (type === "series" ? await tvmazeHit(cand, want, year) : null)
      || imdbFuzzy(hits, want, type, year)
      || imdbAkaByYear(hits, type, year);
    if (value) break;
  }
  if (value && value.id) cache[key] = value;
  else {
    delete cache[key];
    console.warn(`  ~ çözülemedi: ${title}${year ? ` (${year})` : ""}`);
  }
  return value;
}
async function mapLimit(items, limit, fn) {
  const out = new Array(items.length);
  let next = 0;
  const workers = Array.from({ length: Math.min(limit, items.length) }, async () => {
    for (;;) {
      const i = next++;
      if (i >= items.length) return;
      out[i] = await fn(items[i]);
    }
  });
  await Promise.all(workers);
  return out;
}

const written = [];
const failed = [];
for (const rc of recipes) {
  let items;
  try {
    let html = await get(rc.url, rc.headers);
    // Next.js gövdeleri JSON'u \" kaçışıyla gömer; düz regex için açar.
    if (rc.unescape) html = html.replace(/\\"/g, '"');
    const blocks = html.match(new RegExp(rc.item, "gi")) || [];
    const titleRe = new RegExp(rc.title, "i");
    const seen = new Set();
    items = [];
    for (const b of blocks) {
      const m = titleRe.exec(b);
      let title = m ? stripTags(m[1]) : "";
      if (rc.strip) title = title.replace(new RegExp(rc.strip, "i"), "").trim();
      if (title.length < 2) continue;
      const k = norm(title);
      if (seen.has(k)) continue;
      seen.add(k);
      let poster = rc.poster ? (new RegExp(rc.poster, "i").exec(b) || [])[1] : undefined;
      if (poster && /^data:/i.test(poster)) poster = undefined;
      if (poster && poster.startsWith("/")) poster = new URL(poster, rc.url).origin + poster;
      const yearStr = rc.year ? (new RegExp(rc.year, "i").exec(b) || [])[1] : undefined;
      const year = yearStr && /^(19|20)\d{2}$/.test(yearStr) ? Number(yearStr) : 0;
      const imdb = rc.imdb ? (new RegExp(rc.imdb, "i").exec(b) || [])[1] : undefined;
      const slug = rc.title2 ? (new RegExp(rc.title2, "i").exec(b) || [])[1] : undefined;
      items.push({
        title,
        title2: slug ? slug.replace(/-/g, " ") : undefined,
        poster,
        year,
        imdb,
        href: rc.href ? (new RegExp(rc.href, "i").exec(b) || [])[1] : undefined,
      });
      if (items.length >= (rc.limit || MAX_ITEMS)) break;
    }
  } catch (e) {
    console.error(`! ${rc.id}: ${e.message}`);
    failed.push(rc);
    continue;
  }
  if (items.length < 8) {
    console.error(`! ${rc.id}: yalnız ${items.length} kart (en az 8 gerekli)`);
    failed.push(rc);
    continue;
  }
  // Çözümler ağ isteği; sıralı çalıştırmak CI'da dakikalar alıyor.
  const resolved = await mapLimit(items, 8, async (it) => ({
    it,
    hit: it.imdb
      ? { id: it.imdb, poster: it.poster || "" }
      : (await resolve(rc.type, it.title, it.year)) || (it.title2 ? await resolve(rc.type, it.title2, it.year) : null),
  }));
  const metas = [];
  for (const { it, hit } of resolved) {
    if (!hit) continue;
    if (metas.some((m) => m.id === hit.id)) continue;
    metas.push({ id: hit.id, type: rc.type, name: it.title, poster: it.poster || hit.poster || "" });
  }
  if (metas.length < 5) {
    console.error(`! ${rc.id}: ${items.length} kart, ${metas.length} IMDb eşleşmesi (en az 5 gerekli)`);
    failed.push(rc);
    continue;
  }
  if (check) {
    console.log(`ok ${rc.id} (${rc.type}) ${metas.length}/${items.length}`);
    continue;
  }
  const dir = join(OUT, rc.type);
  mkdirSync(dir, { recursive: true });
  writeFileSync(join(dir, rc.id + ".json"), JSON.stringify({ metas }) + "\n");
  written.push({ id: rc.id, type: rc.type, name: rc.name, hidden: !!rc.hidden, group: rc.group || [], count: metas.length });
  console.log(`ok ${rc.id} (${rc.type}) ${metas.length}/${items.length}`);
}

// Geçici hata (site blokajı/ağ) satır veya grup üyesi silmesin: önceki katalog dosyası korunur.
if (!check) {
  for (const rc of failed) {
    const file = join(OUT, rc.type, rc.id + ".json");
    if (!existsSync(file)) continue;
    const metas = JSON.parse(readFileSync(file, "utf8")).metas || [];
    if (!metas.length) continue;
    written.push({ id: rc.id, type: rc.type, name: rc.name, hidden: !!rc.hidden, group: rc.group || [], count: metas.length, kept: true });
    console.log(`~ ${rc.id}: önceki liste korundu (${metas.length})`);
  }
}

if (!check) {
  for (const k of Object.keys(cache)) {
    if (!cache[k] || !cache[k].id) delete cache[k];
  }
  writeFileSync(CACHE, JSON.stringify(cache, null, 1) + "\n");
  // Tarifi silinen katalogları kaldır; geçici hata alan tarifin eski listesi korunur.
  const allRecipes = readdirSync(SRC)
    .filter((f) => f.endsWith(".json"))
    .map((f) => JSON.parse(readFileSync(join(SRC, f), "utf8")));
  const keep = new Set(written.map((w) => w.type + "/" + w.id));
  for (const t of ["movie", "series"]) {
    const d = join(OUT, t);
    if (!existsSync(d)) continue;
    for (const f of readdirSync(d)) {
      const id = f.replace(/\.json$/, "");
      if (id.startsWith("grp-")) continue;
      if (!keep.has(t + "/" + id)) rmSync(join(d, f));
    }
  }
  // Toplu satırlar: üyeler sırayla harmanlanır (round-robin), id'ye göre tekilleşir.
  const groupRows = [];
  for (const g of GROUPS) {
    const members = written.filter((w) => w.type === g.type && w.group.includes(g.id));
    const lists = members
      .map((m) => (JSON.parse(readFileSync(join(OUT, g.type, m.id + ".json"), "utf8")).metas || []))
      .filter((l) => l.length);
    if (!lists.length) continue;
    const metas = [];
    const seen = new Set();
    for (let i = 0; metas.length < MAX_ITEMS; i++) {
      let any = false;
      for (const l of lists) {
        const row = l[i];
        if (!row) continue;
        any = true;
        if (!row.id || seen.has(row.id)) continue;
        seen.add(row.id);
        metas.push(row);
        if (metas.length >= MAX_ITEMS) break;
      }
      if (!any) break;
    }
    if (metas.length < 5) {
      console.error(`! grp ${g.id}: yalnız ${metas.length} öğe`);
      continue;
    }
    const file = join(OUT, g.type, "grp-" + g.id + ".json");
    writeFileSync(file, JSON.stringify({ metas }) + "\n");
    groupRows.push({ type: g.type, id: "grp-" + g.id, name: g.name, count: metas.length });
    console.log(`grp ${g.id} (${g.type}) ${metas.length} öğe / ${members.length} kaynak`);
  }
  // Kaldırılan grubun eski dosyası kalmasın.
  const produced = new Set(groupRows.map((g) => g.type + "/" + g.id.replace(/^grp-/, "")));
  for (const t of ["movie", "series"]) {
    const d = join(OUT, t);
    if (!existsSync(d)) continue;
    for (const f of readdirSync(d)) {
      if (!f.startsWith("grp-")) continue;
      const gid = f.replace(/^grp-/, "").replace(/\.json$/, "");
      if (!produced.has(t + "/" + gid)) rmSync(join(d, f));
    }
  }
  // "Tümü" satırları kaldırıldı; eski dosyalar temizlenir.
  for (const t of ["movie", "series"]) {
    const stale = join(OUT, t, "all.json");
    if (existsSync(stale)) rmSync(stale);
  }
  const manifest = {
    id: ADDON_ID,
    version: repoVersion(),
    name: "BigBull TR Katalog",
    description:
      "Cloudstream sağlayıcılarının film ve dizi listeleri; öne çıkan toplu satırlar. Katalog verir; meta Nuvio'nun yerleşik TMDB zenginleştirmesi ve kurulu metadata addon'larından, akış BigBull Nuvio eklentilerinden gelir; canlı TV ayrı addon'dadır.",
    resources: ["catalog"],
    types: ["movie", "series"],
    catalogs: [
      ...groupRows.map((c) => ({ type: c.type, id: c.id, name: c.name })),
      ...written
        .filter((w) => !w.hidden)
        .map((c) => ({ type: c.type, id: c.id, name: c.name }))
        .sort((a, b) => a.name.localeCompare(b.name, "tr")),
    ],
  };
  writeFileSync(join(HERE, "manifest.json"), JSON.stringify(manifest, null, 2) + "\n");
  console.log(`manifest: ${manifest.catalogs.length} katalog`);
}
