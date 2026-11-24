// @provider {"id":"trt1","name":"TRT1","types":["tv"],"domain":"trt1.com.tr","formats":["m3u8"],"sample":"tt1831164:tv:1:1","description":"TRT 1 resmi dizi arşivi; __NEXT_DATA__ asset HLS akışları.","verified":true,"version":"1.0.0"}
/**
 * trt1 — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta/alt title -> /arama/<term>?contenttype=series -> __NEXT_DATA__ -> /diziler/<slug>/bolum -> MediaDetailCard asset m3u8.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://www.trt1.com.tr";

function text(url, referer) {
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (referer) headers.Referer = referer;
  return fetch(url, { method: "GET", headers: headers })
    .then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status + " " + url);
      return r.text();
    });
}

function first(re, body) {
  var m = re.exec(String(body || ""));
  return m ? m[1] : null;
}

function fold(v) {
  return String(v || "")
    .replace(/[\u0131\u0130I]/g, "i").replace(/[\u015f\u015e]/g, "s").replace(/[\u011f\u011e]/g, "g")
    .replace(/[\u00e7\u00c7]/g, "c").replace(/[\u00f6\u00d6]/g, "o").replace(/[\u00fc\u00dc]/g, "u")
    .toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
}

function sameTitle(have, want) {
  var h = fold(have), w = fold(want);
  return !!w && (h === w || h.indexOf(w + " ") === 0);
}

function titleOf(id, mediaType) {
  if (/^__alt__:/.test(String(id))) return Promise.resolve(decodeURIComponent(String(id).slice(8)));
  var kind = mediaType === "tv" ? "series" : "movie";
  return text("https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json")
    .then(function (body) { return String(((JSON.parse(body) || {}).meta || {}).name || "").trim(); })
    .catch(function () { return ""; });
}

function abs(base, href) {
  if (!href) return null;
  if (/^https?:/i.test(href)) return href;
  var origin = /^(https?:\/\/[^/]+)/.exec(base);
  if (href.charAt(0) === "/") return origin ? origin[1] + href : href;
  return base.replace(/[^/]*$/, "") + href;
}

function parseVariants(master, playlist) {
  if (/TYPE=AUDIO|AUDIO=/i.test(String(playlist || ""))) return [];
  var lines = String(playlist || "").split(/\r?\n/), out = [], seen = {}, q = "";
  for (var i = 0; i < lines.length; i++) {
    var line = lines[i].trim();
    if (!line) continue;
    if (line.indexOf("#EXT-X-STREAM-INF") === 0) {
      var m = /RESOLUTION=\d+x(\d+)/.exec(line);
      q = m ? m[1] + "p" : "auto";
    } else if (line.charAt(0) !== "#" && q) {
      var u = abs(master, line);
      if (u && !seen[u]) { seen[u] = 1; out.push({ quality: q, url: u }); }
      q = "";
    }
  }
  return out;
}

function component(html, name) {
  var raw = first(/<script[^>]*id="__NEXT_DATA__"[^>]*>([\s\S]*?)<\/script>/i, html);
  if (!raw) return null;
  var json = JSON.parse(raw);
  var list = ((((json || {}).props || {}).pageProps || {}).pageComponents || {}).componentData || [];
  for (var i = 0; i < list.length; i++) {
    var d = (list[i] || {}).data;
    if (d && d.component === name) return d;
  }
  return null;
}

function matchEpisode(grid, wantEp) {
  var items = (grid && grid.data) || [];
  for (var i = 0; i < items.length; i++) {
    var it = items[i] || {};
    if (it.type !== "series-episode" || !it.path) continue;
    var num = parseInt(first(/\/bolum\/(\d+)/, it.path), 10);
    if (num === wantEp) return BASE + it.path;
  }
  return "";
}

function findEpisodeUrl(listUrl, wantEp) {
  return text(listUrl, BASE + "/").then(function (html) {
    var grid = component(html, "GridGenerator");
    if (!grid) return "";
    var hit = matchEpisode(grid, wantEp);
    if (hit) return hit;
    var totalPages = Math.max(1, parseInt((grid.pagination || {}).totalPages, 10) || 1);
    if (totalPages <= 1) return "";
    var maxEp = 0, items = grid.data || [];
    for (var i = 0; i < items.length; i++) {
      var n = parseInt(first(/\/bolum\/(\d+)/, (items[i] || {}).path), 10) || 0;
      if (n > maxEp) maxEp = n;
    }
    var perPage = items.length || 12;
    var guess = maxEp > wantEp ? Math.min(totalPages, Math.max(2, Math.floor((maxEp - wantEp) / perPage) + 1)) : totalPages;
    var cand = [guess];
    if (guess - 1 >= 2) cand.push(guess - 1);
    if (guess + 1 <= totalPages) cand.push(guess + 1);
    if (cand.indexOf(totalPages) === -1) cand.push(totalPages);
    var chain = Promise.resolve("");
    cand.forEach(function (p) {
      chain = chain.then(function (res) {
        if (res) return res;
        return text(listUrl + "/" + p, BASE + "/")
          .then(function (pHtml) { return matchEpisode(component(pHtml, "GridGenerator"), wantEp); })
          .catch(function () { return ""; });
      });
    });
    return chain;
  });
}

function getStreams(id, mediaType, season, episode) {
  if (mediaType === "movie") return Promise.resolve([]);
  var wantEp = parseInt(episode, 10) || 1;
  return titleOf(id, mediaType)
    .then(function (title) {
      if (!title) return "";
      var url = BASE + "/arama/" + encodeURIComponent(title) + "?contenttype=series";
      return text(url, BASE + "/").then(function (html) {
        var grid = component(html, "GridGenerator");
        var items = (grid && grid.data) || [];
        for (var i = 0; i < items.length; i++) {
          var it = items[i] || {};
          if (it.type === "series" && it.path && sameTitle(it.title, title)) return BASE + it.path;
        }
        return "";
      });
    })
    .then(function (seriesUrl) {
      if (!seriesUrl) return "";
      return findEpisodeUrl(seriesUrl + "/bolum", wantEp);
    })
    .then(function (epUrl) {
      if (!epUrl) return [];
      return text(epUrl, BASE + "/").then(function (epHtml) {
        var card = component(epHtml, "MediaDetailCard");
        var media = ((card || {}).data || {}).media || {};
        if (media.platform !== "asset" || !/^https?:/i.test(String(media.src || ""))) return [];
        var master = media.src;
        var headers = { "User-Agent": UA, Referer: BASE + "/" };
        return text(master, BASE + "/").catch(function () { return ""; }).then(function (pl) {
          var vars = parseVariants(master, pl);
          if (!vars.length) {
            return [{ name: "TRT1", title: "TRT1 - HLS", url: master, quality: "auto", headers: headers, subtitles: [] }];
          }
          vars.sort(function (a, b) { return (parseInt(b.quality, 10) || 0) - (parseInt(a.quality, 10) || 0); });
          return vars.map(function (v) {
            return { name: "TRT1", title: "TRT1 - " + v.quality + " [HLS]", url: v.url, quality: v.quality, headers: headers, subtitles: [] };
          });
        });
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][trt1] " + (e && e.message));
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
