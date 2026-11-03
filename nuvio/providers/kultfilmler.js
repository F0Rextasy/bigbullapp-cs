// @provider {"id":"kultfilmler","name":"Kultfilmler","types":["movie"],"domain":"kultfilmler.net","formats":["m3u8"],"description":"Kultfilmler film kaynaklari (vidpapi getVideo -> HLS).","sample":"tt0133093:movie:1:1"}
/**
 * kultfilmler — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> site search -> film page -> vidpapi iframe -> getVideo -> securedLink HLS.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://kultfilmler.net";
var API = "https://vidpapi.xyz/player/index.php";

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

function first(pattern, body) {
  var m = pattern.exec(String(body || ""));
  return m ? m[1] : null;
}

function abs(base, href) {
  if (!href) return null;
  if (/^https?:/i.test(href)) return href;
  var origin = /^(https?:\/\/[^/]+)/.exec(base);
  if (href.charAt(0) === "/") return origin ? origin[1] + href : href;
  return base.replace(/[^/]*$/, "") + href;
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

function streams(film, videoId) {
  var body = "hash=" + encodeURIComponent(videoId) + "&r=" + encodeURIComponent(film);
  return text(API + "?data=" + encodeURIComponent(videoId) + "&do=getVideo", {
    method: "POST",
    referer: film,
    body: body,
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
      "X-Requested-With": "XML" + "HttpRequest"
    }
  }).then(function (payload) {
    var link = first(/"securedLink"\s*:\s*"([^"]+)"/, payload);
    if (!link) return [];
    var url = link.replace(/\\\//g, "/");
    return text(url, { referer: film }).then(function (playlist) {
      if (playlist.indexOf("#EXTM3U") !== 0) return [];
      var height = 0;
      var re = /RESOLUTION=\d+x(\d+)/g;
      var m;
      while ((m = re.exec(playlist)) !== null) height = Math.max(height, parseInt(m[1], 10));
      var quality = height ? height + "p" : "auto";
      return [
        {
          name: "kultfilmler",
          title: "kultfilmler - " + quality + " [HLS]",
          url: url,
          quality: quality,
          headers: { Referer: film },
          subtitles: []
        }
      ];
    });
  });
}

function tryFilm(film) {
  return text(film)
    .then(function (detail) {
      var embed = first(/<iframe[^>]+src="(https:\/\/vidpapi\.xyz\/video\/[^"]+)"/, detail);
      if (!embed) return [];
      var videoId = embed.replace(/\/+$/, "").split("/").pop();
      if (!videoId) return [];
      return streams(film, videoId);
    })
    .catch(function (e) {
      console.log("[bb-nuvio][kultfilmler] " + (e && e.message));
      return [];
    });
}

function eachFilm(films, i) {
  if (i >= films.length) return Promise.resolve([]);
  return tryFilm(films[i]).then(function (rows) {
    return rows.length ? rows : eachFilm(films, i + 1);
  });
}

function getStreams(tmdbId, mediaType) {
  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return [];
      return text(BASE + "/?s=" + encodeURIComponent(title)).then(function (search) {
        var films = [];
        // site slugs are "-izle/" or "-izle-4/": match both, in result order
        var re = /href="([^"]*-izle(?:-\d+)?\/)"/g;
        var m;
        while ((m = re.exec(search)) !== null) {
          var href = abs(BASE, m[1]);
          if (href && films.indexOf(href) === -1) films.push(href);
        }
        return eachFilm(films.slice(0, 5), 0);
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][kultfilmler] " + (e && e.message));
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