// @provider {"id":"dizisol","name":"DiziSol","types":["movie","tv"],"domain":"dizisol.com","formats":["m3u8"],"sample":"tt0133093:movie:1:1","description":"DiziSol - JSON API + viewer-proof imzali HLS film ve dizi arsivi.","verified":true,"version":"1.0.0"}
/**
 * dizisol — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /api/movies/search?q= -> /api/movies/by-tmdb/<id> ->
 *       POST /api/playback/load-mixi/config -> GET {origin}/viewer-proof ->
 *       POST /api/playback/<provider> -> signed HLS master + TR/EN VTT subtitles.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://dizisol.com";

function text(url, opts) {
  var o = opts || {};
  var headers = {
    "User-Agent": UA,
    "Accept-Language": "tr-TR,tr;q=0.9",
    Accept: "application/json, text/plain, */*",
    Referer: BASE + "/",
    Origin: BASE
  };
  if (o.headers) for (var k in o.headers) headers[k] = o.headers[k];
  return fetch(url, { method: o.method || "GET", headers: headers, body: o.body }).then(function (r) {
    if (!r.ok) throw new Error("HTTP " + r.status + " " + url);
    return r.text();
  });
}

function json(url, opts) {
  return text(url, opts).then(function (body) { return JSON.parse(body); });
}

function origin(url) {
  var m = /^(https?:\/\/[^/]+)/.exec(String(url || ""));
  return m ? m[1] : null;
}

function hlsNoDownload(url) {
  var u = String(url || "");
  if (/\.m3u8(\?|$)/i.test(u) || u.indexOf("#") !== -1) return u;
  return u + "#.m3u8";
}

function titleOf(id, mediaType) {
  if (/^__alt__:/.test(String(id))) return Promise.resolve({ name: decodeURIComponent(String(id).slice(8)), year: 0 });
  var kind = mediaType === "movie" ? "movie" : "series";
  return text("https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json")
    .then(function (body) {
      var meta = JSON.parse(body).meta || {};
      var yr = parseInt(String(meta.year || meta.releaseInfo || "").slice(0, 4), 10) || 0;
      return { name: String(meta.name || "").trim(), year: yr };
    })
    .catch(function () { return { name: "", year: 0 }; });
}

function norm(value) {
  return String(value || "")
    .replace(/[\u0131\u0130I]/g, "i").replace(/[\u015f\u015e]/g, "s").replace(/[\u011f\u011e]/g, "g")
    .replace(/[\u00fc\u00dc]/g, "u").replace(/[\u00f6\u00d6]/g, "o").replace(/[\u00e7\u00c7]/g, "c")
    .toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
}

function pickHit(items, wantTitle, wantYear, isTv) {
  var want = norm(wantTitle);
  var targetType = isTv ? "tv" : "movie";
  var best = null;
  for (var i = 0; i < (items || []).length; i++) {
    var it = items[i] || {};
    if (!it.tmdbId) continue;
    var rowType = String(it.type || it.media_type || "movie").toLowerCase();
    if (rowType !== targetType) continue;
    var n1 = norm(it.title);
    var n2 = norm(it.titleTr);
    var y = parseInt(it.year, 10) || 0;
    var yearOk = !wantYear || !y || Math.abs(wantYear - y) <= 1;
    if ((n1 === want || n2 === want) && yearOk) return it;
    if (!best && (n1.indexOf(want + " ") === 0 || n2.indexOf(want + " ") === 0) && yearOk) best = it;
  }
  return best;
}

function providerFromHost(org) {
  var host = String(org || "").replace(/^https?:\/\//, "").split("/")[0].toLowerCase();
  if (host.indexOf("load-mixi") === 0) return "load-mixi";
  if (host.indexOf("closeload") === 0) return "closeload";
  if (host.indexOf("cortina") === 0) return "cortina";
  if (host.indexOf("rapid") === 0) return "rapidrame";
  if (host.indexOf("dizipal") === 0) return "dizipal3008";
  if (host.indexOf("dizilla") === 0) return "dizilla";
  if (host.indexOf("ydx") === 0) return "ydx";
  if (host.indexOf("videoplays") === 0) return "videoplays";
  if (host.indexOf("filmekseni") === 0) return "filmekseni";
  return null;
}

function providerFor(cfg, org) {
  var low = String(org || "").replace(/\/+$/, "").toLowerCase();
  if (cfg && cfg.providers) {
    for (var k in cfg.providers) {
      var list = cfg.providers[k] || [];
      for (var i = 0; i < list.length; i++) {
        if (String(list[i]).replace(/\/+$/, "").toLowerCase() === low) return k;
      }
    }
  }
  return providerFromHost(org) || "load-mixi";
}

function isIpBound(cfg, org) {
  if (!cfg || !cfg.ipBindingOrigins) return true;
  var low = String(org || "").replace(/\/+$/, "").toLowerCase();
  for (var i = 0; i < cfg.ipBindingOrigins.length; i++) {
    if (String(cfg.ipBindingOrigins[i]).replace(/\/+$/, "").toLowerCase() === low) return true;
  }
  return false;
}

function issueAndVerify(cfg, movieId, playUrl, sourceId) {
  var org = origin(playUrl);
  if (!org || org.indexOf("workers.dev") === -1) return Promise.resolve(null);
  var provider = providerFor(cfg, org);
  var proofTask = isIpBound(cfg, org)
    ? json(org + "/viewer-proof").then(function (p) { return p && p.proof ? p.proof : ""; }).catch(function () { return ""; })
    : Promise.resolve("");

  return proofTask.then(function (proof) {
    var payload = { movieId: movieId, playUrl: playUrl };
    if (sourceId) payload.sourceId = sourceId;
    if (proof) payload.networkProof = proof;
    return json(BASE + "/api/playback/" + provider, {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-playback-intent": provider + "-v1" },
      body: JSON.stringify(payload)
    }).then(function (ticket) {
      var signed = ticket && ticket.playUrl;
      if (!signed || !/^https?:/i.test(signed)) return null;
      return fetch(signed, { headers: { "User-Agent": UA, Referer: BASE + "/" } }).then(function (r) {
        if (!r.ok) return null;
        return r.text().then(function (master) {
          if (master.indexOf("#EXTM3U") === -1) return null;
          var subs = [];
          if (master.toLowerCase().indexOf("type=subtitles") === -1) {
            if (ticket.subtitleTr && /^https?:/i.test(ticket.subtitleTr)) {
              subs.push({ url: ticket.subtitleTr, language: "tr", name: "Türkçe [DiziSol]" });
            }
            if (ticket.subtitleEn && /^https?:/i.test(ticket.subtitleEn)) {
              subs.push({ url: ticket.subtitleEn, language: "en", name: "English [DiziSol]" });
            }
          }
          return {
            name: "DiziSol",
            title: "DiziSol - " + provider + " [HLS]",
            url: hlsNoDownload(signed),
            quality: "auto",
            headers: { "User-Agent": UA, Referer: BASE + "/" },
            subtitles: subs
          };
        });
      });
    });
  }).catch(function () { return null; });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var isTv = mediaType === "tv" || mediaType === "series";
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;

  return titleOf(tmdbId, mediaType)
    .then(function (meta) {
      if (!meta.name) return null;
      return json(BASE + "/api/movies/search?q=" + encodeURIComponent(meta.name) + "&paged=1&page=1&limit=30")
        .then(function (res) {
          var rows = Array.isArray(res) ? res : (res && res.items) || [];
          return pickHit(rows, meta.name, meta.year, isTv);
        });
    })
    .then(function (hit) {
      if (!hit) return null;
      var q = isTv ? "?season=" + want + "&episode=" + wantEp + "&type=tv" : "?type=movie";
      return json(BASE + "/api/movies/by-tmdb/" + hit.tmdbId + q);
    })
    .then(function (detail) {
      if (!detail) return [];
      var movieId = detail.id || 0;
      var cands = [], seen = {};
      if (detail.m3u8Url && /^https?:/i.test(detail.m3u8Url)) {
        seen[detail.m3u8Url] = 1;
        cands.push({ url: detail.m3u8Url, sourceId: null });
      }
      var list = detail.sources || [];
      for (var i = 0; i < list.length; i++) {
        var s = list[i] || {};
        if (s.isActive === false || !/^https?:/i.test(s.m3u8Url || "") || seen[s.m3u8Url]) continue;
        if (s.m3u8Url.indexOf("workers.dev") === -1) continue;
        seen[s.m3u8Url] = 1;
        cands.push({ url: s.m3u8Url, sourceId: s.id || null });
      }
      if (!cands.length) return [];
      return json(BASE + "/api/playback/load-mixi/config", { method: "POST" })
        .catch(function () { return null; })
        .then(function (cfg) {
          return Promise.all(cands.slice(0, 4).map(function (c) {
            return issueAndVerify(cfg, movieId, c.url, c.sourceId);
          }));
        });
    })
    .then(function (rows) {
      var out = [], seen = {};
      for (var i = 0; i < (rows || []).length; i++) {
        if (!rows[i] || seen[rows[i].url]) continue;
        seen[rows[i].url] = 1;
        out.push(rows[i]);
      }
      return out;
    })
    .catch(function (e) {
      console.log("[bb-nuvio][dizisol] " + (e && e.message));
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
  var mt = mediaType === "series" ? "tv" : mediaType;
  if (!/^\d+$/.test(raw)) {
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
            if (/^tt\d+$/.test(String(value || ""))) return value;
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
};

/* Cinemeta basligi yerellestirir ("Gibi" -> "As If"); sonuc bossa Wikidata TR/EN basliklariyla yeniden dene. */
function __altTitles(raw, mediaType) {
  var wdHeaders = { "User-Agent": "BigBullNuvio/1.0 (https://github.com/F0Rextasy/bigbullapp-cs)", "Accept": "application/json" };
  var h = { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36", "Accept": "application/json" };
  var api = "https://www.wikidata.org/w/api.php?format=json";
  var stmt = /^tt\d+$/.test(raw) ? "P345=" + raw : (mediaType === "movie" ? "P4947=" : "P4983=") + raw;
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
  var imdb = /^tt\d+$/.test(raw)
    ? fetch("https://v3.sg.media-imdb.com/suggestion/t/" + raw + ".json", { headers: h })
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (j) { return (j && j.d && j.d[0] && j.d[0].l) ? [j.d[0].l] : []; })
        .catch(function () { return []; })
    : Promise.resolve([]);
  var tvm = /^tt\d+$/.test(raw) && mediaType !== "movie"
    ? fetch("https://api.tvmaze.com/lookup/shows?imdb=" + raw, { headers: h })
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (j) { return (j && j.name) ? [j.name] : []; })
        .catch(function () { return []; })
    : Promise.resolve([]);
  return Promise.all([wd, imdb, tvm]).then(function (lists) {
    var out = [], seen = {};
    for (var i = 0; i < lists.length; i++) {
      for (var j = 0; j < lists[i].length; j++) {
        var v = String(lists[i][j] || "").replace(/\s*\([^)]*\)\s*$/, "").trim();
        if (v && !seen[v.toLowerCase()]) { seen[v.toLowerCase()] = 1; out.push(v); }
      }
    }
    return out;
  });
}
var __cinemetaGetStreams = module.exports.getStreams;
module.exports.getStreams = function (id, mediaType, season, episode) {
  var raw = String(id || "");
  try {
    if (/^__alt__:/.test(raw)) return Promise.resolve(__cinemetaGetStreams(raw, mediaType, season, episode)).catch(function () { return []; });
    function tryPrimary() {
      if (!/^tt\d+$/.test(raw)) return Promise.resolve(__cinemetaGetStreams(raw, mediaType, season, episode)).catch(function () { return []; });
      var h = { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36", "Accept": "application/json" };
      return fetch("https://v3.sg.media-imdb.com/suggestion/t/" + raw + ".json", { headers: h })
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (j) {
          var t = j && j.d && j.d[0] && j.d[0].l ? String(j.d[0].l).replace(/\s*\([^)]*\)\s*$/, "").trim() : "";
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
};

/* Dil etiketi (AGENTS.md): Nuvio karti "quality \u2022 ..." satirini gosterir; dil etiketi kalitenin yanina yazilir. */
var __subOnly = false;
var __langLabeled = module.exports.getStreams;
module.exports.getStreams = function (id, mediaType, season, episode) {
  try {
    return Promise.resolve(__langLabeled.apply(this, arguments)).then(function (streams) {
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
    }).catch(function () { return []; });
  } catch (e) {
    return Promise.resolve([]);
  }
};
