#!/usr/bin/env node
/**
 * Nuvio sağlayıcılarındaki ortak 4 katmanlı sarmalayıcı bloğunu
 * (__origGetStreams, __altTitles, __cinemetaGetStreams, __langLabeled)
 * senkronlar ve denetler; QuickJS tek-dosya kuralını korur.
 *
 * Kullanım:
 *   node nuvio/tools/sync-helpers.mjs --check   # fark varsa çıkış kodu 1
 *   node nuvio/tools/sync-helpers.mjs --bump    # dosyaları günceller + @provider patch sürümünü artırır
 */
import crypto from "node:crypto";
import { readdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const DIR = new URL("../providers/", import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1");
const MODE_CHECK = process.argv.includes("--check");
const MODE_BUMP = process.argv.includes("--bump");

const CANONICAL_ORIG = `/* NuvioMobile always calls getStreams with args.tmdbId (JsBindings.kt); the
   value is a numeric TMDB id after TmdbService.ensureTmdbId. Resolve it to
   IMDb so the existing Cinemeta/title flow keeps working. */
var __origGetStreams = module.exports.getStreams;
module.exports.getStreams = function (id, mediaType, season, episode) {
  var raw = String(id || "");
  var mt = mediaType === "series" ? "tv" : mediaType;
  if (!/^\\d+$/.test(raw)) {
    try {
      return Promise.resolve(__origGetStreams(raw, mt, season, episode)).catch(function () { return []; });
    } catch (e) {
      return Promise.resolve([]);
    }
  }
  var tv = mt !== "movie";
  var headers = { "User-Agent": "BigBullNuvio/1.0 (https://github.com/F0Rextasy/bigbullapp-cs)", "Accept": "application/json" };
  var key = typeof TMDB_API_KEY !== "undefined" ? TMDB_API_KEY : "";
  var api = "https://www.wikidata.org/w/api.php?format=json";
  var prop = tv ? "P4983" : "P4947";
  function fromTmdb() {
    if (!key) return Promise.resolve("");
    return fetch("https://api.themoviedb.org/3/" + (tv ? "tv" : "movie") + "/" + raw + "/external_ids?api_key=" + encodeURIComponent(key), { headers: headers })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (json) { return String((json && json.imdb_id) || ""); })
      .catch(function () { return ""; });
  }
  function fromWikidata() {
    return fetch(api + "&action=query&list=search&srlimit=1&srsearch=" + encodeURIComponent("haswbstatement:" + prop + "=" + raw), { headers: headers })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (json) {
        var hits = (json && json.query && json.query.search) || [];
        if (!hits.length) return "";
        return fetch(api + "&action=wbgetentities&props=claims&ids=" + hits[0].title, { headers: headers });
      })
      .then(function (r) { return r && r.ok ? r.json() : null; })
      .then(function (json) {
        var entities = (json && json.entities) || {};
        for (var entity in entities) {
          var rows = ((entities[entity] || {}).claims || {}).P345 || [];
          for (var i = 0; i < rows.length; i++) {
            var value = rows[i] && rows[i].mainsnak && rows[i].mainsnak.datavalue && rows[i].mainsnak.datavalue.value;
            if (/^tt\\d+$/.test(String(value || ""))) return value;
          }
        }
        return "";
      })
      .catch(function () { return ""; });
  }
  function fromTmdbTitle() {
    if (!key) return Promise.resolve("");
    return fetch("https://api.themoviedb.org/3/" + (tv ? "tv" : "movie") + "/" + raw + "?api_key=" + encodeURIComponent(key) + "&language=tr-TR", { headers: headers })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (json) {
        var t = (json && (json.original_name || json.name || json.original_title || json.title)) || "";
        return t ? "__alt__:" + encodeURIComponent(t) : "";
      })
      .catch(function () { return ""; });
  }
  return fromTmdb().then(function (imdb) { return imdb || fromWikidata(); }).then(function (imdb) { return imdb || fromTmdbTitle(); }).then(function (imdb) {
    try {
      return Promise.resolve(__origGetStreams(imdb || raw, mt, season, episode)).catch(function () { return []; });
    } catch (e) {
      return [];
    }
  }).catch(function () { return []; });
};`;

const CANONICAL_ALT_TITLES = `/* Cinemeta basligi yerellestirir ("Gibi" -> "As If"); sonuc bossa Wikidata TR/EN basliklariyla yeniden dene. */
function __altTitles(raw, mediaType) {
  var wdHeaders = { "User-Agent": "BigBullNuvio/1.0 (https://github.com/F0Rextasy/bigbullapp-cs)", "Accept": "application/json" };
  var h = { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36", "Accept": "application/json" };
  var api = "https://www.wikidata.org/w/api.php?format=json";
  var stmt = /^tt\\d+$/.test(raw) ? "P345=" + raw : (mediaType === "movie" ? "P4947=" : "P4983=") + raw;
  var wd = fetch(api + "&action=query&list=search&srlimit=1&srsearch=" + encodeURIComponent("haswbstatement:" + stmt), { headers: wdHeaders })
    .then(function (r) { return r.ok ? r.json() : null; })
    .then(function (j) {
      var hits = (j && j.query && j.query.search) || [];
      if (!hits.length) return null;
      return fetch(api + "&action=wbgetentities&props=labels|aliases&languages=tr|en&ids=" + hits[0].title, { headers: wdHeaders }).then(function (r) { return r.ok ? r.json() : null; });
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

const CANONICAL_CINEMETA = `var __cinemetaGetStreams = module.exports.getStreams;
module.exports.getStreams = function (id, mediaType, season, episode) {
  var raw = String(id || "");
  try {
    if (/^__alt__:/.test(raw)) return Promise.resolve(__cinemetaGetStreams(raw, mediaType, season, episode)).catch(function () { return []; });
    function tryPrimary() {
      if (!/^tt\\d+$/.test(raw)) return Promise.resolve(__cinemetaGetStreams(raw, mediaType, season, episode)).catch(function () { return []; });
      var h = { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36", "Accept": "application/json" };
      return fetch("https://v3.sg.media-imdb.com/suggestion/t/" + raw + ".json", { headers: h })
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (j) {
          var t = j && j.d && j.d[0] && j.d[0].l ? String(j.d[0].l).replace(/\\s*\\([^)]*\\)\\s*$/, "").trim() : "";
          return t ? __cinemetaGetStreams("__alt__:" + encodeURIComponent(t), mediaType, season, episode) : [];
        })
        .catch(function () { return []; })
        .then(function (streams) {
          return (streams && streams.length) ? streams : __cinemetaGetStreams(raw, mediaType, season, episode);
        });
    }
    return tryPrimary().then(function (streams) {
      if (streams && streams.length) return streams;
      return __altTitles(raw, mediaType).then(function (titles) {
        var chain = Promise.resolve([]);
        titles.forEach(function (t) {
          chain = chain.then(function (out) {
            return out.length ? out : __cinemetaGetStreams("__alt__:" + encodeURIComponent(t), mediaType, season, episode);
          });
        });
        return chain;
      });
    }).catch(function () { return []; });
  } catch (e) {
    return Promise.resolve([]);
  }
};`;

const CANONICAL_CINEMETA_ABSEP = `var __cinemetaGetStreams = module.exports.getStreams;
module.exports.getStreams = function (id, mediaType, season, episode) {
  var raw = String(id || "");
  try {
    if (/^__alt__:/.test(raw)) return Promise.resolve(__cinemetaGetStreams(raw, mediaType, season, episode)).catch(function () { return []; });
    var want = parseInt(season, 10) || 1;
    var wantEp = parseInt(episode, 10) || 1;
    return Promise.resolve(__cinemetaGetStreams(raw, mediaType, season, episode)).then(function (streams) {
      if (streams && streams.length) return streams;
      return metaOf(raw, mediaType, want, wantEp).then(function (info) {
        return __altTitles(raw, mediaType).then(function (titles) {
          var chain = Promise.resolve([]);
          titles.forEach(function (t) {
            chain = chain.then(function (out) {
              return out.length ? out : __cinemetaGetStreams("__alt__:" + encodeURIComponent(t) + "|" + (info.absEp || 0), mediaType, season, episode);
            });
          });
          return chain;
        });
      });
    }).catch(function () { return []; });
  } catch (e) {
    return Promise.resolve([]);
  }
};`;

export const CANONICAL_LANG_LABELED = `var __langLabeled = module.exports.getStreams;
module.exports.getStreams = function (id, mediaType, season, episode) {
  try {
    return Promise.resolve(__langLabeled.apply(this, arguments)).then(function (streams) {
      return (streams || []).map(function (s) {
        if (!s) return s;
        var hay = String(s.title || "") + " " + String(s.name || "");
        var tags = [];
        if (/dublaj/i.test(hay)) tags.push("TR Dublaj");
        if (/altyaz/i.test(hay) || (s.subtitles && s.subtitles.length) || (!tags.length && __subOnly)) tags.push("Altyaz\\u0131l\\u0131");
        if (!tags.length) return s;
        var q = String(s.quality || "").replace(/\\s*\\u2022.*$/, "") || "auto";
        s.quality = q + " \\u2022 " + tags.join(" \\u2022 ");
        return s;
      });
    }).catch(function () { return []; });
  } catch (e) {
    return Promise.resolve([]);
  }
};`;

export const LANG_LABELED_HASH = crypto
  .createHash("sha1")
  .update(CANONICAL_LANG_LABELED.trim())
  .digest("hex")
  .slice(0, 10);

function buildCanonicalTail(subOnly, useAbsEp) {
  return (
    CANONICAL_ORIG +
    "\n\n" +
    CANONICAL_ALT_TITLES +
    "\n" +
    (useAbsEp ? CANONICAL_CINEMETA_ABSEP : CANONICAL_CINEMETA) +
    "\n\n/* Dil etiketi (AGENTS.md): Nuvio karti \"quality \\u2022 ...\" satirini gosterir; dil etiketi kalitenin yanina yazilir. */\n" +
    `var __subOnly = ${subOnly ? "true" : "false"};\n` +
    CANONICAL_LANG_LABELED +
    "\n"
  );
}

function bumpPatchVersion(firstLine) {
  return firstLine.replace(/"version"\s*:\s*"(\d+)\.(\d+)\.(\d+)"/, (_all, a, b, c) => {
    return `"version":"${a}.${b}.${Number(c) + 1}"`;
  });
}

if (process.argv[1] && process.argv[1].endsWith("sync-helpers.mjs")) {
  const files = readdirSync(DIR).filter((f) => f.endsWith(".js")).sort();
  const stale = [];
  const missing = [];

  for (const f of files) {
    const filePath = join(DIR, f);
    const orig = readFileSync(filePath, "utf8");
    const eol = orig.includes("\r\n") ? "\r\n" : "\n";
    const lf = orig.replace(/\r\n/g, "\n");
    const m = /module\.exports\s*=\s*\{\s*getStreams\s*\}\s*;(?:\n\}\)\(\);)?/.exec(lf);
    if (!m) {
      missing.push(f);
      continue;
    }
    const afterAnchor = m.index + m[0].length;
    const curTail = lf.slice(afterAnchor);
    const subMatch = /var\s+__subOnly\s*=\s*(true|false)\s*;/.exec(curTail);
    const subOnly = subMatch ? subMatch[1] === "true" : false;
    const useAbsEp = /metaOf\(\s*raw\s*,\s*mediaType\s*,\s*want\s*,\s*wantEp\s*\)/.test(curTail);
    const expectedTail = "\n\n" + buildCanonicalTail(subOnly, useAbsEp);

    if (curTail !== expectedTail) {
      stale.push(f);
      if (!MODE_CHECK) {
        let nextLf = lf.slice(0, afterAnchor) + expectedTail;
        if (MODE_BUMP) {
          const nl = nextLf.indexOf("\n");
          const head = nl >= 0 ? nextLf.slice(0, nl) : nextLf;
          const rest = nl >= 0 ? nextLf.slice(nl) : "";
          nextLf = bumpPatchVersion(head) + rest;
        }
        writeFileSync(filePath, eol === "\r\n" ? nextLf.replace(/\n/g, "\r\n") : nextLf, "utf8");
      }
    }
  }

  if (missing.length) {
    console.error("HATA: module.exports = { getStreams } eksik:", missing.join(", "));
    process.exit(1);
  }
  if (MODE_CHECK) {
    if (stale.length) {
      console.error("HATA: ortak sarmalayıcı bloğu güncel değil:", stale.join(", "));
      process.exit(1);
    }
    console.log(`OK: ${files.length}/${files.length} Nuvio sağlayıcısı güncel 4-katmanlı sarmalayıcı taşıyor (hash=${LANG_LABELED_HASH})`);
  } else {
    console.log(`güncellendi (${stale.length}/${files.length}, hash=${LANG_LABELED_HASH}): ${stale.join(", ") || "yok"}`);
  }
}
