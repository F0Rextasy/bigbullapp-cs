#!/usr/bin/env node
/**
 * Nuvio sağlayıcılarındaki ortak __altTitles (Wikidata + IMDb suggestion + TVMaze)
 * yardımcısını senkronlar ve denetler; QuickJS tek-dosya kuralını korur.
 *
 * Kullanım:
 *   node nuvio/tools/sync-helpers.mjs --check   # fark varsa çıkış kodu 1
 *   node nuvio/tools/sync-helpers.mjs --bump    # dosyaları günceller + @provider patch sürümünü artırır
 */
import { readdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const DIR = new URL("../providers/", import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1");
const MODE_CHECK = process.argv.includes("--check");
const MODE_BUMP = process.argv.includes("--bump");

const CANONICAL_ALT_TITLES = `function __altTitles(raw, mediaType) {
  var h = { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36", "Accept": "application/json" };
  var api = "https://www.wikidata.org/w/api.php?format=json";
  var stmt = /^tt\\d+$/.test(raw) ? "P345=" + raw : (mediaType === "movie" ? "P4947=" : "P4983=") + raw;
  var wd = fetch(api + "&action=query&list=search&srlimit=1&srsearch=" + encodeURIComponent("haswbstatement:" + stmt), { headers: h })
    .then(function (r) { return r.ok ? r.json() : null; })
    .then(function (j) {
      var hits = (j && j.query && j.query.search) || [];
      if (!hits.length) return null;
      return fetch(api + "&action=wbgetentities&props=labels|aliases&languages=tr|en&ids=" + hits[0].title, { headers: h }).then(function (r) { return r.ok ? r.json() : null; });
    })
    .then(function (j) {
      var res = [], ents = (j && j.entities) || {}, k, l, a, i;
      for (k in ents) {
        l = ents[k].labels || {}; a = ents[k].aliases || {};
        if (l.tr && l.tr.value) res.push(l.tr.value);
        if (l.en && l.en.value) res.push(l.en.value);
        for (i = 0; i < (a.tr || []).length && i < 2; i++) res.push(a.tr[i].value);
      }
      return res;
    }).catch(function () { return []; });
  var imdb = /^tt\\d+$/.test(raw)
    ? fetch("https://v3.sg.media-imdb.com/suggestion/t/" + raw + ".json", { headers: h })
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (j) { return (j && j.d && j.d[0] && j.d[0].l) ? [j.d[0].l] : []; })
        .catch(function () { return []; })
    : Promise.resolve([]);
  var tvm = /^tt\\d+$/.test(raw) && mediaType !== "movie"
    ? fetch("https://api.tvmaze.com/lookup/shows?imdb=" + raw, { headers: h })
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (j) { return (j && j.name) ? [j.name] : []; })
        .catch(function () { return []; })
    : Promise.resolve([]);
  return Promise.all([wd, imdb, tvm]).then(function (lists) {
    var out = [], seen = {};
    for (var i = 0; i < lists.length; i++) {
      for (var j = 0; j < lists[i].length; j++) {
        var v = String(lists[i][j] || "").replace(/\\s*\\([^)]*\\)\\s*$/, "").trim();
        if (v && !seen[v.toLowerCase()]) { seen[v.toLowerCase()] = 1; out.push(v); }
      }
    }
    return out;
  });
}`;

function findAltTitlesSpan(src) {
  const m = /function\s+__altTitles\s*\(\s*raw\s*,\s*mediaType\s*\)\s*\{/.exec(src);
  if (!m) return null;
  let i = m.index + m[0].length - 1;
  let depth = 0;
  let mode = "code"; // code | sq | dq | lc | bc
  for (; i < src.length; i++) {
    const ch = src[i];
    const nx = src[i + 1];
    if (mode === "lc") {
      if (ch === "\n") mode = "code";
      continue;
    }
    if (mode === "bc") {
      if (ch === "*" && nx === "/") {
        mode = "code";
        i++;
      }
      continue;
    }
    if (mode === "sq" || mode === "dq") {
      if (ch === "\\") {
        i++;
        continue;
      }
      if ((mode === "sq" && ch === "'") || (mode === "dq" && ch === '"')) mode = "code";
      continue;
    }
    if (ch === "/" && nx === "/") {
      mode = "lc";
      i++;
      continue;
    }
    if (ch === "/" && nx === "*") {
      mode = "bc";
      i++;
      continue;
    }
    if (ch === "'") {
      mode = "sq";
      continue;
    }
    if (ch === '"') {
      mode = "dq";
      continue;
    }
    if (ch === "{") depth++;
    else if (ch === "}") {
      depth--;
      if (depth === 0) return { start: m.index, end: i + 1, text: src.slice(m.index, i + 1) };
    }
  }
  return null;
}

function bumpPatchVersion(firstLine) {
  return firstLine.replace(/"version"\s*:\s*"(\d+)\.(\d+)\.(\d+)"/, (_all, a, b, c) => {
    return `"version":"${a}.${b}.${Number(c) + 1}"`;
  });
}

const files = readdirSync(DIR).filter((f) => f.endsWith(".js")).sort();
const stale = [];
const missing = [];

for (const f of files) {
  const path = join(DIR, f);
  const orig = readFileSync(path, "utf8");
  const eol = orig.includes("\r\n") ? "\r\n" : "\n";
  const lf = orig.replace(/\r\n/g, "\n");
  const span = findAltTitlesSpan(lf);
  if (!span) {
    missing.push(f);
    continue;
  }
  if (span.text !== CANONICAL_ALT_TITLES) {
    stale.push(f);
    if (!MODE_CHECK) {
      let nextLf = lf.slice(0, span.start) + CANONICAL_ALT_TITLES + lf.slice(span.end);
      if (MODE_BUMP) {
        const nl = nextLf.indexOf("\n");
        const head = nl >= 0 ? nextLf.slice(0, nl) : nextLf;
        const rest = nl >= 0 ? nextLf.slice(nl) : "";
        nextLf = bumpPatchVersion(head) + rest;
      }
      writeFileSync(path, eol === "\r\n" ? nextLf.replace(/\n/g, "\r\n") : nextLf, "utf8");
    }
  }
}

if (missing.length) {
  console.error("HATA: __altTitles eksik:", missing.join(", "));
  process.exit(1);
}
if (MODE_CHECK) {
  if (stale.length) {
    console.error("HATA: __altTitles güncel değil:", stale.join(", "));
    process.exit(1);
  }
  console.log(`OK: ${files.length}/${files.length} Nuvio sağlayıcısı güncel __altTitles taşıyor`);
} else {
  console.log(`güncellendi (${stale.length}/${files.length}): ${stale.join(", ") || "yok"}`);
}
