// @provider {"id":"dizigom","name":"Dizigom","types":["movie"],"domain":"dizigom.news","formats":["m3u8"],"sample":"tt1375666:movie:1:1","description":"Dizigom (dizigom.news) Turkce film arsivi; imzali HLS akisi."}
/**
 * dizigom — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /ara?q= -> /film/<slug> -> data-pv -> pilavyerplay s.php -> window.__PLAYER__.stream (HLS).
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://www.dizigom.news";

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
  if (o.headers) for (var k in o.headers) headers[k] = o.headers[k];
  return fetch(url, { method: o.method || "GET", headers: headers, body: o.body }).then(function (r) {
    if (!r.ok) throw new Error("HTTP " + r.status + " " + url);
    return r.text();
  });
}

function first(pattern, body) {
  var m = pattern.exec(String(body || ""));
  return m ? m[1] : null;
}

function titleOf(id, mediaType) {
  var kind = mediaType === "tv" ? "series" : "movie";
  return text("https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json")
    .then(function (body) {
      return String((JSON.parse(body).meta || {}).name || "").trim();
    })
    .catch(function () {
      return "";
    });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var filmUrl = null;
  var playerUrl = null;

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return null;
      return text(BASE + "/ara?q=" + encodeURIComponent(title));
    })
    .then(function (search) {
      if (!search) return null;
      var href = first(/<a href="(https:\/\/www\.dizigom\.news\/film\/[^"?#]+)"/, search);
      if (!href) href = first(/href="(\/film\/[^"?#]+)"/, search);
      if (!href) return null;
      filmUrl = href.indexOf("http") === 0 ? href : BASE + href;
      return text(filmUrl);
    })
    .then(function (page) {
      if (!page) return null;
      if (/data-pv=/.test(page)) return page;
      return text(filmUrl.replace(/\/+$/, "") + "/izle");
    })
    .then(function (playerPage) {
      if (!playerPage) return null;
      var slug = first(/data-pv=["']([^"']+)/, playerPage);
      if (!slug) return null;
      var base = first(/src=["'](https?:\/\/[^"']+\/assets\/js\/core\.js)["']/, playerPage);
      base = base ? base.replace("/assets/js/core.js", "") : "https://pilavyerplay.top";
      playerUrl = base + "/assets/js/s.php?s=" + encodeURIComponent(slug);
      return text(playerUrl, { referer: filmUrl });
    })
    .then(function (body) {
      if (!body) return [];
      var json = first(/window\.__PLAYER__\s*=\s*(\{[\s\S]*?\});/, body);
      if (!json) return [];
      var stream = String(JSON.parse(json).stream || "").replace(/\\\//g, "/").trim();
      if (!/^https?:/.test(stream)) return [];
      var base = playerUrl.replace(/\/assets\/js\/s\.php.*$/, "");
      return text(stream, { referer: base + "/" }).then(function (playlist) {
        if (String(playlist).replace(/^\s+/, "").indexOf("#EXTM3U") !== 0) return [];
        return [
          {
            name: "dizigom",
            title: "dizigom - HLS [pilavyerplay]",
            url: stream,
            quality: "auto",
            headers: { Referer: base + "/" },
            subtitles: []
          }
        ];
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][dizigom] " + (e && e.message));
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