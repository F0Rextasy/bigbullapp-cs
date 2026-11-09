// @provider {"id":"animecix", "name":"AnimeciX", "types":["tv"], "domain":"animecix.tv", "formats":["mp4"], "sample":"tt12343534:tv:1:1", "description":"AnimeciX Turkce altyazili anime arsivi (MP4 akislari).", "version":"1.0.6"}
/**
 * animecix — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /secure/search/<q> -> /secure/related-videos -> secure/best-video
 * (302 to tau-video.xyz/embed/<key>) -> tau /api/video/<key> -> mp4 renditions.
 * shortcut: the site's X-E-H AES token is only needed by /secure/titles, which this flow skips;
 * season+episode resolution uses related-videos, upgrade if that route starts requiring the token.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
var BASE = "https://animecix.tv";
var TAU = "https://tau-video.xyz";

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
  if (o.headers) for (var k in o.headers) headers[k] = o.headers[k];
  return fetch(url, { method: o.method || "GET", headers: headers, body: o.body })
    .then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status + " " + url);
      return r.text();
    });
}

function fetchJson(url) {
  return text(url, { referer: BASE + "/" }).then(function (body) {
    return JSON.parse(body);
  });
}

function fold(value) {
  return String(value || "")
    .replace(/ı/g, "i").replace(/İ/g, "i")
    .replace(/ş/g, "s").replace(/Ş/g, "s")
    .replace(/ğ/g, "g").replace(/Ğ/g, "g")
    .replace(/ü/g, "u").replace(/Ü/g, "u")
    .replace(/ö/g, "o").replace(/Ö/g, "o")
    .replace(/ç/g, "c").replace(/Ç/g, "c")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
}

function titleOf(id, mediaType) {
  if (/^__alt__:/.test(String(id))) return Promise.resolve(decodeURIComponent(String(id).slice(8)));
  var kind = mediaType === "tv" ? "series" : "movie";
  return text("https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json")
    .then(function (body) {
      var meta = JSON.parse(body).meta || {};
      return String(meta.name || "").trim();
    })
    .catch(function () {
      return "";
    });
}

function pick(items, title) {
  var want = fold(title);
  var best = null;
  var bestScore = -1;
  for (var i = 0; i < (items || []).length; i++) {
    var item = items[i] || {};
    if (!item.id) continue;
    var have = fold(item.name);
    var score = have === want ? 100 : 0;
    if (!score && have.indexOf(want + " ") === 0) score = 60;
    if (score > bestScore) {
      bestScore = score;
      best = item;
    }
  }
  return bestScore > 0 ? best : null;
}

/** best-video answers 302 to tau-video.xyz/embed/<key>; the key is only in the redirect. */
function embedKey(url) {
  function fromUrl(target) {
    var m = /\/embed\/([A-Za-z0-9]+)/.exec(String(target || ""));
    return m ? m[1] : null;
  }
  return fetch(url, { headers: { "User-Agent": UA, "Referer": BASE + "/" }, redirect: "manual" })
    .then(function (r) {
      return fromUrl(r && r.headers && r.headers.get ? r.headers.get("location") : null);
    })
    .catch(function () {
      return null;
    })
    .then(function (key) {
      if (key) return key;
      // Some sandbox fetch shims ignore `redirect: manual`; retry following it and read r.url.
      return fetch(url, { headers: { "User-Agent": UA, "Referer": BASE + "/" } })
        .then(function (r) {
          return fromUrl(r.url);
        })
        .catch(function () {
          return null;
        });
    });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var title = "";

  return titleOf(tmdbId, mediaType)
    .then(function (name) {
      title = name;
      if (!title) return null;
      return fetchJson(BASE + "/secure/search/" + encodeURIComponent(title) + "?limit=20");
    })
    .then(function (body) {
      if (!body) return null;
      var hit = pick(body.results, title);
      if (!hit) return null;
      return fetchJson(
        BASE + "/secure/related-videos?episode=1&season=" + want + "&videoId=0&titleId=" + encodeURIComponent(hit.id)
      );
    })
    .then(function (body) {
      if (!body) return null;
      var videos = body.videos || [];
      var inSeason = [];
      for (var i = 0; i < videos.length; i++) {
        if (Number((videos[i] || {}).season_num) !== want) continue;
        inSeason.push(videos[i]);
      }
      if (!inSeason.length) return null;
      var chosen = null;
      for (var j = 0; j < inSeason.length; j++) {
        if (Number(inSeason[j].episode_num) === wantEp) {
          chosen = inSeason[j];
          break;
        }
      }
      if (!chosen) return null;
      var path = String(chosen.url || "");
      if (!path) return null;
      return embedKey(/^https?:/.test(path) ? path : BASE + "/" + path);
    })
    .then(function (key) {
      if (!key) return [];
      return fetchJson(TAU + "/api/video/" + encodeURIComponent(key));
    })
    .then(function (video) {
      if (!video) return [];
      var out = [];
      var urls = video.urls || [];
      for (var i = 0; i < urls.length; i++) {
        var url = String((urls[i] || {}).url || "");
        if (!/^https?:/.test(url)) continue;
        var label = String(urls[i].label || "Kaynak");
        out.push({
          name: "AnimeciX",
          title: "AnimeciX - " + label + (url.indexOf(".m3u8") !== -1 ? " [HLS]" : " [MP4]"),
          url: url,
          quality: label,
          headers: { "User-Agent": UA, "Referer": TAU + "/" },
          subtitles: []
        });
      }
      return out;
    })
    .catch(function (e) {
      console.log("[bb-nuvio][animecix] " + (e && e.message));
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
  var headers = { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36", "Accept": "application/json" };
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

/* Cinemeta basligi yerellestirir ("Gibi" -> "As If"); sonuc bossa Wikidata TR/EN basliklariyla yeniden dene. */
function __altTitles(raw, mediaType) {
  var h = { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36", "Accept": "application/json" };
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
var __subOnly = true;
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
