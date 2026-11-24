// @provider {"id":"sonanime","name":"SonAnime","types":["tv","movie"],"domain":"sonanime.com","formats":["mp4"],"sample":"tt0409591:tv:1:1","description":"SonAnime Turkce altyazili anime dizi ve film arsivi (1080p/720p/480p MP4).","verified":true,"version":"1.0.0"}
/**
 * sonanime — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title (+ videos absEp) -> api.sonanime.com/api/anime/search?q= -> /link/<slug>
 *       -> episode_link_1080 / 720 / 480 / 360 direct MP4 links.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://sonanime.com";
var API = "https://api.sonanime.com/api/anime";
var ID = "SonAnime";

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

function fold(value) {
  return String(value || "")
    .replace(/[\u0131\u0130I]/g, "i").replace(/[\u015f\u015e]/g, "s").replace(/[\u011f\u011e]/g, "g")
    .replace(/[\u00fc\u00dc]/g, "u").replace(/[\u00f6\u00d6]/g, "o").replace(/[\u00e7\u00c7]/g, "c")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
}

function metaOf(id, mediaType, want, wantEp) {
  if (/^__alt__:/.test(String(id))) {
    return Promise.resolve({
      title: decodeURIComponent(String(id).slice(8)),
      absEp: want === 1 ? wantEp : 0
    });
  }
  var kind = mediaType === "tv" ? "series" : "movie";
  return text("https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json")
    .then(function (body) {
      var meta = JSON.parse(body).meta || {};
      var videos = (meta.videos || []).filter(function (v) { return v && Number(v.season) > 0; });
      videos.sort(function (a, b) {
        return (Number(a.season) - Number(b.season)) || (Number(a.episode || a.number) - Number(b.episode || b.number));
      });
      var absEp = want === 1 ? wantEp : 0;
      for (var i = 0; i < videos.length; i++) {
        var v = videos[i];
        if (Number(v.season) === want && Number(v.episode || v.number) === wantEp) {
          absEp = i + 1;
          break;
        }
      }
      return { title: String(meta.name || "").trim(), absEp: absEp };
    })
    .catch(function () {
      return { title: "", absEp: want === 1 ? wantEp : 0 };
    });
}

function pickAnime(items, title, isMovie) {
  var want = fold(title);
  if (!want) return null;
  var best = null;
  var bestScore = -1;
  for (var i = 0; i < (items || []).length; i++) {
    var o = items[i] || {};
    var slug = String(o.anime_link || "").trim();
    if (!slug) continue;
    var cands = [o.anime_name, o.anime_name_en];
    var score = 0;
    for (var c = 0; c < cands.length; c++) {
      var have = fold(cands[c]);
      if (!have) continue;
      if (have === want) score = Math.max(score, 100);
      else if (have.indexOf(want + " ") === 0) score = Math.max(score, 60);
    }
    if (!score) continue;
    var itemMovie = String(o.anime_type || "").toLowerCase() === "movie";
    if (itemMovie === isMovie) score += 20;
    if (score > bestScore) {
      bestScore = score;
      best = slug;
    }
  }
  return bestScore > 0 ? best : null;
}

function getStreams(tmdbId, mediaType, season, episode) {
  var isMovie = mediaType === "movie";
  var want = isMovie ? 1 : (parseInt(season, 10) || 1);
  var wantEp = isMovie ? 1 : (parseInt(episode, 10) || 1);
  var absEp = want === 1 ? wantEp : 0;
  var wanted = "";

  return metaOf(tmdbId, mediaType, want, wantEp)
    .then(function (info) {
      wanted = info.title;
      absEp = info.absEp || absEp;
      if (!wanted) return null;
      return text(API + "/search?q=" + encodeURIComponent(wanted));
    })
    .then(function (raw) {
      if (!raw) return null;
      var arr = JSON.parse(raw);
      var slug = pickAnime(Array.isArray(arr) ? arr : (arr.results || []), wanted, isMovie);
      if (!slug) return null;
      return text(API + "/link/" + encodeURIComponent(slug));
    })
    .then(function (raw) {
      if (!raw) return [];
      var data = JSON.parse(raw);
      var eps = data.episodes || [];
      var hit = null;
      var byAbs = null;
      for (var i = 0; i < eps.length; i++) {
        var e = eps[i] || {};
        var se = Number(e.anime_season) || 1;
        var ep = Number(e.episode_number) || 0;
        if (se === want && ep === wantEp) { hit = e; break; }
        if (absEp > 0 && se === 1 && ep === absEp && !byAbs) byAbs = e;
      }
      var chosen = hit || byAbs || (isMovie && eps.length === 1 ? eps[0] : null);
      if (!chosen) return [];
      var out = [];
      var quals = ["1080", "720", "480", "360"];
      for (var q = 0; q < quals.length; q++) {
        var key = quals[q];
        var link = String(chosen["episode_link_" + key] || "").trim();
        if (!link) continue;
        if (link.indexOf("http") !== 0) link = BASE + (link.charAt(0) === "/" ? "" : "/") + link;
        out.push({
          name: ID,
          title: ID + " - " + key + "p [MP4]",
          url: link,
          quality: key + "p [MP4]",
          headers: { Referer: BASE + "/" },
          subtitles: []
        });
      }
      return out;
    })
    .catch(function (e) {
      console.log("[bb-nuvio][sonanime] " + (e && e.message));
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
