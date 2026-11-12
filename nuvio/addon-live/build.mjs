/**
 * BigBull Canlı TV — ayrı Stremio/Nuvio addon'u.
 *
 * Girdi:  providers/CanliTV/.../CanliTVProvider.kt  (STATIC, OVERRIDES, SKIP, ROWS)
 *         https://iptv-org.github.io/iptv/countries/tr.m3u  (canlı liste)
 * Çıktı:  nuvio/addon-live/manifest.json
 *         nuvio/addon-live/catalog/tv/<satır>.json
 *         nuvio/addon-live/meta/tv/<id>.json
 *         nuvio/addon-live/stream/tv/<id>.json
 *
 * Film/dizi kataloğu `nuvio/addon` addon'unda kalır; bu addon yalnız canlı TV
 * satırlarını ve `behaviorHints.isLive` akışlarını sunar. Nuvio canlı TV'yi
 * yalnız addon yolundan oynatır; plugin `getStreams` sözleşmesinde kanal yoktur.
 *
 * Kullanım: node nuvio/addon-live/build.mjs
 */
import { readFileSync, writeFileSync, readdirSync, mkdirSync, rmSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const PROVIDER = join(HERE, "..", "..", "providers", "CanliTV", "src", "main", "kotlin", "com", "canlitv", "CanliTVProvider.kt");
const IPTV = "https://iptv-org.github.io/iptv/countries/tr.m3u";
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
const ADDON_ID = "org.bigbull.canlitv";
const ROW_ID = "canlitv";
const STREAM_NAME = "BigBull Canlı TV";
const FOLD = { ç: "c", ğ: "g", ı: "i", ö: "o", ş: "s", ü: "u", â: "a", î: "i", û: "u" };

// Depo sürümü tek yerde: CHANGELOG'un en üst başlığı (cloudstream ile aynı).
function repoVersion() {
  const log = readFileSync(join(HERE, "..", "..", "CHANGELOG.md"), "utf8");
  const m = /^## \[(\d+\.\d+\.\d+)\]/m.exec(log);
  return m ? m[1] : "1.0.0";
}

function keyOf(name) {
  return String(name)
    .replace(/\s*\([^)]*\)/g, "")
    .replace(/\s*\[[^]]*\]/g, "")
    .trim()
    .toLowerCase()
    .split("")
    .filter((c) => c !== "\u0307")
    .map((c) => FOLD[c] || c)
    .join("");
}

function slugOf(key) {
  return key.replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") || "kanal";
}

function constants(text) {
  const out = {};
  for (const m of text.matchAll(/const val (\w+) = "([^"]+)"/g)) out[m[1]] = m[2];
  return out;
}

function block(text, marker) {
  const i = text.indexOf(marker);
  if (i < 0) return "";
  const j = text.indexOf("\n        )", i);
  return text.slice(i, j < 0 ? undefined : j);
}

function parseProvider() {
  const text = readFileSync(PROVIDER, "utf8");
  const consts = constants(text);
  const rows = [...block(text, "ROWS = listOf(").matchAll(/"([^"]+)" to "([^"]+)"/g)]
    .map((m) => ({ label: m[1], group: m[2] }));
  const skip = new Set([...block(text, "SKIP = setOf(").matchAll(/"([^"]+)"/g)].map((m) => m[1]));
  const overrides = Object.fromEntries([...block(text, "OVERRIDES = mapOf(").matchAll(/"([^"]+)" to "([^"]+)"/g)]
    .map((m) => [m[1], m[2]]));
  const statics = [...block(text, "STATIC = listOf(").matchAll(/ch\("([^"]+)",\s*"([^"]+)",\s*"([^"]+)"(?:,\s*(\w+))?\)/g)]
    .map((m) => ({
      name: m[1],
      groups: [m[2]],
      logo: consts.DEFAULT_LOGO || "",
      url: m[3],
      referer: m[4] ? consts[m[4]] || null : consts.STATIC_REFERER || null,
    }));
  return { consts, rows, skip, overrides, statics };
}

function parsePlaylist(text, consts) {
  const found = [];
  let pending = null;
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line) continue;
    if (line.startsWith("#EXTINF")) {
      const attrs = Object.fromEntries([...line.matchAll(/([\w-]+)="([^"]*)"/g)].map((m) => [m[1], m[2]]));
      pending = { name: line.slice(line.lastIndexOf(",") + 1).trim(), attrs };
      continue;
    }
    if (line.startsWith("#") || !pending) continue;
    if (pending.name) {
      found.push({
        name: pending.name,
        groups: String(pending.attrs["group-title"] || "").split(";").map((g) => g.trim()).filter(Boolean),
        logo: pending.attrs["tvg-logo"] || consts.DEFAULT_LOGO || "",
        url: line,
        referer: consts.IPTV_REFERER || null,
      });
    }
    pending = null;
  }
  return found;
}

/** GET; iptv-org erişilemezse STATIC listesi yine yayınlanır (fetch ağa bağlı). */
async function fetchPlaylist(consts) {
  try {
    const r = await fetch(IPTV, { headers: { "User-Agent": UA }, signal: AbortSignal.timeout(20000) });
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    return parsePlaylist(await r.text(), consts);
  } catch (e) {
    console.error(`! iptv-org alınamadı (${e.message}); yalnız STATIC kanallar`);
    return [];
  }
}

const writeJson = (path, value) => writeFileSync(path, JSON.stringify(value) + "\n");

async function main() {
  const { consts, rows, skip, overrides, statics } = parseProvider();
  const dynamic = await fetchPlaylist(consts);
  const dynamicLogo = new Map(
    dynamic.map((ch) => [keyOf(ch.name), ch.logo]).filter(([, l]) => l && l !== consts.DEFAULT_LOGO),
  );

  const merged = new Map();
  for (const ch of [...statics, ...dynamic]) {
    const key = keyOf(ch.name);
    if (key && !merged.has(key)) merged.set(key, { ...ch, key });
  }
  const live = [];
  for (const [key, ch] of merged) {
    if (skip.has(key)) continue;
    const url = overrides[key] || ch.url;
    if (!/^https?:\/\//i.test(url)) continue;
    live.push({
      id: `bb-${slugOf(key)}`,
      name: ch.name,
      url,
      logo: ch.logo && ch.logo !== consts.DEFAULT_LOGO ? ch.logo : dynamicLogo.get(key) || consts.DEFAULT_LOGO || "",
      groups: ch.groups,
      referer: overrides[key] ? null : ch.referer,
    });
  }
  const ids = new Set(live.map((c) => c.id));
  if (ids.size !== live.length) throw new Error("kanal id çakışması");

  const groupLabel = new Map(rows.map((r) => [r.group, r.label]));
  const metaOf = (c) => ({
    id: c.id,
    type: "tv",
    name: c.name,
    poster: c.logo,
    background: c.logo,
    description: `${c.name} canlı yayın`,
    genres: c.groups.map((g) => groupLabel.get(g) || g),
  });

  const catalogDir = join(HERE, "catalog", "tv");
  const metaDir = join(HERE, "meta", "tv");
  const streamDir = join(HERE, "stream", "tv");
  for (const d of [catalogDir, metaDir, streamDir]) mkdirSync(d, { recursive: true });

  const all = live.map(metaOf);
  const catalogs = [{ type: "tv", id: ROW_ID, name: "Canlı TV" }];
  writeJson(join(catalogDir, ROW_ID + ".json"), { metas: all });
  for (const row of rows) {
    const metas = all.filter((_, i) => live[i].groups.includes(row.group));
    if (!metas.length) continue;
    const id = `${ROW_ID}-${slugOf(row.group.toLowerCase())}`;
    writeJson(join(catalogDir, id + ".json"), { metas });
    catalogs.push({ type: "tv", id, name: `Canlı TV · ${row.label}` });
  }

  for (const c of live) {
    const meta = metaOf(c);
    writeJson(join(metaDir, c.id + ".json"), { meta: { ...meta, videos: [{ id: c.id, title: c.name }] } });
    const headers = { "User-Agent": UA };
    if (c.referer) headers.Referer = c.referer;
    writeJson(join(streamDir, c.id + ".json"), {
      streams: [{ name: STREAM_NAME, title: `${c.name} [Canlı]`, url: c.url, headers, behaviorHints: { isLive: true } }],
    });
  }

  // Liste küçüldüyse eski kanal dosyaları kalmasın.
  for (const dir of [metaDir, streamDir]) {
    for (const f of readdirSync(dir)) {
      const id = f.replace(/\.json$/, "");
      if (!ids.has(id)) rmSync(join(dir, f));
    }
  }
  const rowIds = new Set(catalogs.map((c) => c.id));
  for (const f of readdirSync(catalogDir)) {
    if (!rowIds.has(f.replace(/\.json$/, ""))) rmSync(join(catalogDir, f));
  }

  const manifest = {
    id: ADDON_ID,
    version: repoVersion(),
    name: "BigBull Canlı TV",
    description: "Türkçe canlı TV kanalları (Cloudstream CanliTV kaynaklarından). Akışlar doğrudan HLS'dir.",
    resources: ["catalog", "meta", "stream"],
    types: ["tv"],
    idPrefixes: ["bb-"],
    catalogs,
  };
  writeFileSync(join(HERE, "manifest.json"), JSON.stringify(manifest, null, 2) + "\n");
  console.log(`canlı tv: ${live.length} kanal, ${catalogs.length} satır, manifest ${manifest.version}`);
}

await main();
