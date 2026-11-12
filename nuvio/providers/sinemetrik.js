// @provider {"id":"sinemetrik","name":"Sinemetrik","types":["movie","tv"],"domain":"sinemetrik.com","formats":["m3u8"],"description":"Sinemetrik film/dizi kaynaklari (kendi player'i, TR altyazi).","sample":"tt0468569:movie:1:1","version":"1.0.0","verified":true}
/**
 * sinemetrik — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /api/search (TMDB slug dogrulamasi) -> film/dizi detayi ->
 * s1.php (pv/ULTRA) veya s2.php (ULTRA hash) -> media-relay HLS + TR altyazi.
 * HTML sayfalari Turnstile kapili; yalniz bot UA'si gecer (Googlebot).
 */
var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BOT = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)";
var BASE = "https://www.sinemetrik.com";

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": o.bot ? BOT : UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
  return fetch(url, { method: "GET", headers: headers })
    .then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status + " " + url);
      return r.text();
    });
}

function first(pattern, body) {
  var m = pattern.exec(String(body || ""));
  return m ? m[1] : null;
}

function sameTitle(have, want) {
  function f(v) {
    return String(v || "").replace(/[\u0131\u0130I]/g, "i").replace(/[\u015f\u015e]/g, "s").replace(/[\u011f\u011e]/g, "g")
      .replace(/[\u00e7\u00c7]/g, "c").replace(/[\u00f6\u00d6]/g, "o").replace(/[\u00fc\u00dc]/g, "u")
      .toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
  }
  have = f(have);
  want = f(want);
  return !!want && have === want;
}

function metaOf(id, tv) {
  var kind = tv ? "series" : "movie";
  return text("https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json")
    .then(function (body) { return JSON.parse(body).meta || null; })
    .catch(function () { return null; });
}

function subsOf(d) {
  var out = [];
  var list = (d && d.subtitles) || [];
  for (var i = 0; i < list.length; i++) {
    var s = list[i] || {};
    if (!/^https?:/i.test(String(s.url || ""))) continue;
    var lang = String(s.lang || "tr").toLowerCase();
    out.push({ url: s.url, language: lang, name: String(s.label || lang).trim() + " [Sinemetrik]" });
  }
  return out;
}

function streamOf(label, d) {
  if (!d || !d.success || !/^https?:/i.test(String(d.stream || ""))) return null;
  return { name: "Sinemetrik", title: label, url: d.stream, quality: "", headers: { "User-Agent": UA }, subtitles: subsOf(d) };
}

function resolve(url, label) {
  return text(url, { referer: BASE + "/test-player.php?embed=1", bot: true })
    .then(function (body) { return streamOf(label, JSON.parse(body)); })
    .catch(function () { return null; });
}

// Ayni icerigin alternatif sunuculari: hepsi denenir, basarili olanlar listeye eklenir.
function resolveAll(endpoints) {
  var out = [];
  function step(i) {
    if (i >= endpoints.length) return Promise.resolve(out);
    return resolve(endpoints[i][0], endpoints[i][1]).then(function (s) {
      if (s) out.push(s);
      return step(i + 1);
    });
  }
  return step(0);
}

function pick(list, meta, tv) {
  var want = tv ? "tv" : "movie";
  var tmdb = String((meta && meta.moviedb_id) || "");
  var year = String((meta && meta.year) || "");
  var best = null;
  for (var i = 0; i < list.length; i++) {
    var r = list[i] || {};
    if (r.type !== want) continue;
    if (!sameTitle(r.name, meta.name)) continue;
    if (year && r.date && String(r.date).slice(0, 4) !== year) continue;
    if (tmdb) {
      if (new RegExp("-" + tmdb + "$").test(String(r.slug || ""))) return r;
      continue;
    }
    if (!best) best = r;
  }
  return best;
}

function hintsOf(ep) {
  var out = [];
  var pv = first(/pv=([A-Za-z0-9_-]+)/, ep.server1_player_url || "");
  if (pv) out.push([BASE + "/s1.php?t=main&id=" + pv, "Sinemetrik"]);
  var dp = String(ep.server1_dp_id || "");
  if (/^\d+$/.test(dp)) out.push([BASE + "/s1.php?t=ultra&id=" + dp + "&k=jw", "Sinemetrik ULTRA"]);
  var s2 = String(ep.server2_ultra_hash || "");
  if (s2 && s2 !== "None") {
    var cdp = String(ep.server2_ultra_provider || "") === "hlszone" ? "hlszone" : "canliplayer";
    out.push([BASE + "/s2.php?t=ultra&id=" + encodeURIComponent(s2) + "&cdp=" + cdp, "Sinemetrik ULTRA"]);
  }
  return out;
}

function movieStreams(hit) {
  return text(BASE + "/film/" + hit.slug, { bot: true }).then(function (html) {
    var endpoints = [];
    var pv = first(/_s1url\s*=\s*"[^"]*pv=([A-Za-z0-9_-]+)/, html);
    if (pv) endpoints.push([BASE + "/s1.php?t=main&id=" + pv, "Sinemetrik"]);
    var dp = first(/_s1dpUrl\s*=\s*"[^"]*dp=(\d+)/, html);
    if (dp) endpoints.push([BASE + "/s1.php?t=ultra&id=" + dp + "&k=jw", "Sinemetrik ULTRA"]);
    return resolveAll(endpoints);
  });
}

function episodeStreams(hit, season, episode) {
  var wantSeason = parseInt(season, 10) || 1;
  var wantEpisode = parseInt(episode, 10) || 1;
  return text(BASE + "/api/dizi/" + hit.id + "/sezon/" + wantSeason + "/bolumler", { bot: true }).then(function (body) {
    var list = (JSON.parse(body).episodes) || [];
    for (var i = 0; i < list.length; i++) {
      var ep = list[i] || {};
      if (parseInt(ep.episode_number, 10) !== wantEpisode) continue;
      return resolveAll(hintsOf(ep));
    }
    return [];
  });
}

function getStreams(id, mediaType, season, episode) {
  var tv = mediaType !== "movie";
  var raw = String(id || "");
  var alt = /^__alt__:/.test(raw) ? decodeURIComponent(raw.slice(8)) : "";
  if (!alt && !/^tt\d+$/.test(raw)) return Promise.resolve([]);
  var metaP = alt ? Promise.resolve({ name: alt }) : metaOf(raw, tv);
  return metaP.then(function (meta) {
    if (!meta || !meta.name) return [];
    return text(BASE + "/api/search?q=" + encodeURIComponent(meta.name), { bot: true }).then(function (body) {
      var hit = pick((JSON.parse(body).results) || [], meta, tv);
      if (!hit) return [];
      return tv ? episodeStreams(hit, season, episode) : movieStreams(hit);
    });
  }).catch(function (e) {
    console.log("[bb-nuvio][sinemetrik] " + e);
    return [];
  });
}

module.exports = { getStreams };


/* NuvioMobile always calls getStreams with args.tmdbId (JsBindings.kt); the
   value is a numeric TMDB id after TmdbService.ensureTmdbId. Resolve it to
   IMDb so the existing Cinemeta/title flow keeps working. */
var __origGetStreams = module.exports.getStreams;
module.exports.getStreams = function (id, mediaType, season, episode) {
  var raw = String(id || "");
  if (!/^\d+$/.test(raw)) return __origGetStreams(raw, mediaType, season, episode);
  var tv = mediaType !== "movie";
  var headers = { "User-Agent": UA, "Accept": "application/json" };
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
            if (/^tt\d+$/.test(String(value || ""))) return value;
          }
        }
        return "";
      })
      .catch(function () { return ""; });
  }
  return fromTmdb().then(function (imdb) { return imdb || fromWikidata(); }).then(function (imdb) {
    return __origGetStreams(imdb || raw, mediaType, season, episode);
  }).catch(function () { return []; });
};

/* Cinemeta basligi yerellestirir ("Inception" -> "Baslangic"); sonuc bossa Wikidata TR/EN basliklariyla yeniden dene. */
function __altTitles(raw, mediaType) {
  var h = { "User-Agent": UA, "Accept": "application/json" };
  var api = "https://www.wikidata.org/w/api.php?format=json";
  var stmt = /^tt\d+$/.test(raw) ? "P345=" + raw : (mediaType === "movie" ? "P4947=" : "P4983=") + raw;
  return fetch(api + "&action=query&list=search&srlimit=1&srsearch=" + encodeURIComponent("haswbstatement:" + stmt), { headers: h })
    .then(function (r) { return r.ok ? r.json() : null; })
    .then(function (j) {
      var hits = (j && j.query && j.query.search) || [];
      if (!hits.length) return null;
      return fetch(api + "&action=wbgetentities&props=labels|aliases&languages=tr|en&ids=" + hits[0].title, { headers: h }).then(function (r) { return r.ok ? r.json() : null; });
    })
    .then(function (j) {
      var out = [], seen = {}, ents = (j && j.entities) || {}, k, l, a, i;
      function add(v) {
        v = String(v || "").replace(/\s*\([^)]*\)\s*$/, "").trim();
        if (v && !seen[v.toLowerCase()]) { seen[v.toLowerCase()] = 1; out.push(v); }
      }
      for (k in ents) {
        l = ents[k].labels || {}; a = ents[k].aliases || {};
        add((l.tr || {}).value); add((l.en || {}).value);
        for (i = 0; i < (a.tr || []).length && i < 2; i++) add(a.tr[i].value);
      }
      return out;
    })
    .catch(function () { return []; });
}
var __cinemetaGetStreams = module.exports.getStreams;
module.exports.getStreams = function (id, mediaType, season, episode) {
  var raw = String(id || "");
  return __cinemetaGetStreams(raw, mediaType, season, episode).then(function (streams) {
    if ((streams && streams.length) || /^__alt__:/.test(raw)) return streams || [];
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
};

/* Dil etiketi (AGENTS.md): Nuvio karti "quality \u2022 ..." satirini gosterir; dil etiketi kalitenin yanina yazilir.
   Kanit: baslikta dublaj/altyazi, akisa eklenmis altyazi dosyasi ya da sitenin yalniz altyazili olmasi. Kanit yoksa etiket yok. */
var __subOnly = false;
var __langLabeled = module.exports.getStreams;
module.exports.getStreams = function (id, mediaType, season, episode) {
  return __langLabeled.apply(this, arguments).then(function (streams) {
    return (streams || []).map(function (s) {
      if (!s) return s;
      var hay = String(s.title || "") + " " + String(s.name || "");
      var tags = [];
      if (/dublaj/i.test(hay)) tags.push("TR Dublaj");
      if (/altyaz/i.test(hay) || (s.subtitles && s.subtitles.length) || (!tags.length && __subOnly)) tags.push("Altyaz\u0131l\u0131");
      if (!tags.length) return s;
      var q = String(s.quality || "").replace(/\s*\u2022.*$/, "") || "auto";
      s.quality = q + " \u2022 " + tags.join(" \u2022 ");
      return s;
    });
  });
};
