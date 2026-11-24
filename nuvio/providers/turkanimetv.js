// @provider {"id":"turkanimetv","name":"TurkAnimeTV","types":["tv","movie"],"domain":"turkanime.live","formats":["m3u8"],"sample":"tt2560140:tv:1:1","description":"TurkAnimeTV (turkanime.live) Turkce altyazili anime arsivi (HLS + altyazi).","verified":true,"version":"1.0.0"}
/**
 * turkanimetv — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /api/search.php?q= -> /dizi/<slug> or /film/<slug> -> /bolum/...
 *       -> base64 encodedContent -> streamcorecdn.com iframe -> PLAYER_BOOT master.m3u8 + VTT.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://turkanime.live";
var ID = "TurkAnimeTV";
var B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
  if (o.headers) for (var k in o.headers) if (o.headers[k]) headers[k] = o.headers[k];
  return fetch(url, { method: o.method || "GET", headers: headers, body: o.body })
    .then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status + " " + url);
      return r.text();
    });
}

function first(re, body) {
  var m = re.exec(String(body || ""));
  return m ? m[1] : null;
}

function abs(base, href) {
  if (!href) return null;
  href = String(href).replace(/&amp;/g, "&").trim();
  if (/^https?:/i.test(href)) return href;
  if (href.indexOf("//") === 0) return "https:" + href;
  var origin = /^(https?:\/\/[^/]+)/.exec(base);
  if (href.charAt(0) === "/") return origin ? origin[1] + href : href;
  return base.replace(/[^/]*$/, "") + href;
}

function unbase64(s) {
  var src = String(s || "").replace(/[^A-Za-z0-9+/]/g, "");
  var out = "";
  var buf = 0;
  var bits = 0;
  for (var i = 0; i < src.length; i++) {
    var c = B64.indexOf(src.charAt(i));
    if (c < 0) continue;
    buf = (buf << 6) | c;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out += String.fromCharCode((buf >> bits) & 0xff);
      buf &= (1 << bits) - 1;
    }
  }
  return out;
}

function fold(value) {
  return String(value || "")
    .replace(/[\u0131\u0130I]/g, "i").replace(/[\u015f\u015e]/g, "s").replace(/[\u011f\u011e]/g, "g")
    .replace(/[\u00fc\u00dc]/g, "u").replace(/[\u00f6\u00d6]/g, "o").replace(/[\u00e7\u00c7]/g, "c")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
}

function titleOf(id, mediaType) {
  if (/^__alt__:/.test(String(id))) return Promise.resolve(decodeURIComponent(String(id).slice(8)));
  var kind = mediaType === "tv" ? "series" : "movie";
  return text("https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json")
    .then(function (body) { return String((JSON.parse(body).meta || {}).name || "").trim(); })
    .catch(function () { return ""; });
}

function pickResult(items, title, isMovie) {
  var want = fold(title);
  if (!want) return null;
  var best = null;
  var bestScore = -1;
  for (var i = 0; i < (items || []).length; i++) {
    var r = items[i] || {};
    var slug = String(r.slug || "").replace(/^\/+|\/+$/g, "");
    if (!slug) continue;
    var have = fold(r.title);
    var score = have === want ? 100 : (have.indexOf(want + " ") === 0 ? 60 : 0);
    if (!score) continue;
    var rMovie = String(r.type || "") === "movie";
    if (rMovie === isMovie) score += 20;
    if (score > bestScore) {
      bestScore = score;
      best = BASE + "/" + (rMovie ? "film" : "dizi") + "/" + slug;
    }
  }
  return bestScore > 0 ? best : null;
}

function findEpisode(detailHtml, wantSe, wantEp) {
  var re = /href="([^"]*\/bolum\/[^"]+)"/g;
  var m;
  var fallback = null;
  while ((m = re.exec(detailHtml || "")) !== null) {
    var u = abs(BASE, m[1]);
    var slot = /-(\d+)-sezon-(\d+)-bolum\/?$/.exec(u);
    if (!slot) continue;
    var se = parseInt(slot[1], 10);
    var ep = parseInt(slot[2], 10);
    if (se === wantSe && ep === wantEp) return u;
    if (ep === wantEp && !fallback) fallback = u;
  }
  return fallback;
}

function jsVal(html, key) {
  var m = new RegExp("\\b" + key + "\\s*:\\s*\"([^\"]*)\"").exec(String(html || ""));
  if (!m) return null;
  return m[1].replace(/\\(u[0-9a-fA-F]{4}|.)/g, function (_, tok) {
    if (tok.charAt(0) === "u" && tok.length === 5) return String.fromCharCode(parseInt(tok.slice(1), 16));
    return tok;
  });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var isMovie = mediaType === "movie";
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var wanted = "";
  var pageUrl = null;
  var iframeUrl = null;

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      wanted = title;
      if (!title) return null;
      return text(BASE + "/api/search.php?q=" + encodeURIComponent(title))
        .then(function (body) { return JSON.parse(body); });
    })
    .then(function (data) {
      if (!data) return null;
      var hit = pickResult(data.results, wanted, isMovie);
      if (!hit) return null;
      if (hit.indexOf("/film/") !== -1) {
        pageUrl = hit;
        return text(hit);
      }
      return text(hit).then(function (detail) {
        pageUrl = findEpisode(detail, want, wantEp);
        if (!pageUrl) return null;
        return text(pageUrl);
      });
    })
    .then(function (html) {
      if (!html) return null;
      var blob = first(/encodedContent\s*=\s*'([A-Za-z0-9+/=]+)'/, html);
      if (!blob) return null;
      var iframeHtml = unbase64(blob);
      var src = first(/<iframe[^>]+src="([^"]+)"/, iframeHtml);
      if (!src) return null;
      iframeUrl = abs(pageUrl, src);
      return text(iframeUrl, { referer: pageUrl });
    })
    .then(function (playerHtml) {
      if (!playerHtml) return [];
      var storage = jsVal(playerHtml, "hlsStorageDomain");
      var src = jsVal(playerHtml, "src");
      if (!storage || !src) return [];
      var masterUrl = storage.replace(/\/+$/, "") + "/" + src.replace(/^\/+/, "");
      var basePath = (jsVal(playerHtml, "hlsBasePath") || "").replace(/^\/+|\/+$/g, "");
      var subBase = storage.replace(/\/+$/, "") + "/" + (basePath ? basePath + "/" : "");
      var subs = [];
      var tracksRaw = first(/tracksData\s*:\s*(\{[\s\S]*?\})\s*,\s*hlsBasePath/, playerHtml);
      if (tracksRaw) {
        try {
          var tracks = JSON.parse(tracksRaw);
          var arr = tracks.subtitles || [];
          for (var i = 0; i < arr.length; i++) {
            var row = arr[i] || {};
            var p = String(row.url || "").trim();
            if (!p) continue;
            var lang = String(row.lang || "tr").trim();
            var label = String(row.name || lang || "Altyazi").trim();
            subs.push({
              url: subBase + p.replace(/^\/+/, ""),
              language: lang,
              name: label + " [turkanimetv]"
            });
          }
        } catch (e) {}
      }
      return [{
        name: ID,
        title: ID + " - HLS",
        url: masterUrl,
        quality: "auto",
        headers: { Referer: iframeUrl },
        subtitles: subs.slice(0, 4)
      }];
    })
    .catch(function (e) {
      console.log("[bb-nuvio][turkanimetv] " + (e && e.message));
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
var __subOnly = true;
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
