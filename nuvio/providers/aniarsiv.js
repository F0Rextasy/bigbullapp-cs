// @provider {"id":"aniarsiv","name":"AniArsiv","types":["tv"],"domain":"aniarsiv.com","formats":["mp4"],"sample":"tt12343534:tv:1:1","description":"AniArsiv anime arsivi (Turkce altyazili MP4 akislari)."}
/**
 * aniarsiv — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /api/animes?q= -> /api/anime/<slug> -> /api/bolum/<slug> -> cdn_video mp4.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
var BASE = "https://aniarsiv.com";
var JSON_HEADERS = { "Accept": "application/json, text/plain, */*" };

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
  return text(url, { headers: JSON_HEADERS, referer: BASE + "/" }).then(function (body) {
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

/** Best match by folded title, then by leading-token overlap. */
function pick(items, title) {
  var want = fold(title);
  var best = null;
  var bestScore = -1;
  for (var i = 0; i < (items || []).length; i++) {
    var item = items[i];
    if (!item || !item.slug) continue;
    var have = fold(item.baslik || item.ingilizce || item.slug);
    var score = have === want ? 100 : 0;
    if (!score && have.indexOf(want) === 0) score = 60;
    if (!score) {
      var a = want.split(" ");
      var b = have.split(" ");
      for (var j = 0; j < a.length; j++) if (b.indexOf(a[j]) !== -1) score += 10;
    }
    if (score > bestScore) {
      bestScore = score;
      best = item;
    }
  }
  return bestScore > 0 ? best : null;
}

function episodeNumber(episode) {
  var title = String(episode.ad || "");
  var slug = String(episode.slug || "");
  return (
    parseInt((/(\d+)\.\s*B[oö]l[uü]m/.exec(title) || [])[1], 10) ||
    parseInt((/-(\d+)-bolum\b/.exec(slug) || [])[1], 10) ||
    0
  );
}

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var title = "";

  return titleOf(tmdbId, mediaType)
    .then(function (name) {
      title = name;
      if (!title) return null;
      return fetchJson(BASE + "/api/animes?q=" + encodeURIComponent(title));
    })
    .then(function (search) {
      if (!search) return null;
      var hit = pick(search.items, title);
      if (!hit) return null;
      return fetchJson(BASE + "/api/anime/" + encodeURIComponent(hit.slug));
    })
    .then(function (detail) {
      if (!detail) return null;
      var seasons = detail.seasons || [];
      for (var i = 0; i < seasons.length; i++) {
        // Season 2+ lives under its own slug ("jujutsu-kaisen-2nd-season").
        if (want > 1 && Number(seasons[i].seasonNumber) === want && seasons[i].localSlug) {
          return fetchJson(BASE + "/api/anime/" + encodeURIComponent(seasons[i].localSlug)).then(function (sub) {
            var list = sub.bolumler || [];
            return list.length ? list : detail.bolumler || [];
          });
        }
      }
      return detail.bolumler || [];
    })
    .then(function (episodes) {
      if (!episodes || !episodes.length) return null;
      var chosen = null;
      var numbered = [];
      for (var i = 0; i < episodes.length; i++) {
        var num = episodeNumber(episodes[i]);
        if (num === wantEp) chosen = episodes[i];
        numbered.push({ num: num, episode: episodes[i] });
      }
      if (!chosen) {
        numbered.sort(function (a, b) { return a.num - b.num; });
        var fallback = numbered[wantEp - 1] || numbered[0];
        chosen = fallback && fallback.episode;
      }
      return chosen ? fetchJson(BASE + "/api/bolum/" + encodeURIComponent(chosen.slug)) : null;
    })
    .then(function (page) {
      if (!page) return [];
      var out = [];
      var cdn = page.cdn_video;
      if (cdn && !cdn.is_short && /^https?:/.test(String(cdn.video_url || ""))) {
        var height = parseInt(cdn.height, 10) || 0;
        out.push({
          name: "AniArsiv",
          title: "AniArsiv - " + (height ? height + "p" : "Kaynak") + " [MP4]",
          url: cdn.video_url,
          quality: height ? height + "p" : "auto",
          headers: { "User-Agent": UA, "Referer": BASE + "/" },
          subtitles: []
        });
      }
      // shortcut: only the site's own CDN rendition is surfaced; the anizmplayer/tau/savefileway
      // mirrors in `links` are embed pages, so they are skipped until the CDN stops serving.
      return out;
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
  }).catch(function (e) {
    console.log("[bb-nuvio][aniarsiv] okuma akışı hata verdi: " + (e && e.message ? e.message : e));
    return [];
  });
};