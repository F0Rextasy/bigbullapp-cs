// @provider {"id":"diziyou","name":"DiziYou","types":["movie","tv"],"domain":"diziyou.one","formats":["m3u8"],"description":"DiziYou dizi/film kaynaklari (HLS + altyazi).","sample":"tt0903747:tv:1:1"}
/**
 * diziyou — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> site search -> series -> episode -> player iframe -> m3u8 + VTT tracks.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://diziyou.one";

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

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var series = null;
  var episodeUrl = null;
  var playerUrl = null;
  var origin = null;

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return null;
      return text(BASE + "/?s=" + encodeURIComponent(title));
    })
    .then(function (search) {
      if (!search) return null;
      var link = first(/<div id="list-series">[\s\S]*?<div class="cat-img"><a href="([^"]+)"/, search);
      if (!link) link = first(/<a href="([^"]+)"[^>]*class="[^"]*cat-img[^"]*"/, search);
      if (!link) return null;
      series = abs(BASE, link);
      origin = /^(https?:\/\/[^/]+)/.exec(series)[1];
      return text(series);
    })
    .then(function (detail) {
      if (!detail) return null;
      var links = [];
      var re = /href="([^"]*-sezon-[^"]*-bolum\/)"/g;
      var m;
      while ((m = re.exec(detail)) !== null) links.push(m[1]);
      if (!links.length) return null;
      var exact = "-" + want + "-sezon-" + wantEp + "-bolum/";
      episodeUrl = null;
      for (var i = 0; i < links.length; i++) {
        if (links[i].indexOf(exact) !== -1) {
          episodeUrl = links[i];
          break;
        }
      }
      if (!episodeUrl) episodeUrl = links[0];
      return text(episodeUrl);
    })
    .then(function (page) {
      if (!page) return [];
      var iframe = first(/id="diziyouPlayer"[^>]+src="([^"]+)"/, page);
      if (!iframe) iframe = first(/<iframe[^>]+src="([^"]+)"/, page);
      if (!iframe) return [];
      playerUrl = abs(episodeUrl, iframe);
      return text(playerUrl, { referer: episodeUrl });
    })
    .then(function (player) {
      if (!player) return [];
      var src = first(/<source[^>]+src="([^"]+\.m3u8[^"]*)"/, player);
      if (!src) return [];
      var url = abs(playerUrl, src);
      var subs = [];
      var re = /<track[^>]+src="([^"]+)"[^>]*(?:label="([^"]*)")?/g;
      var m;
      while ((m = re.exec(player)) !== null) {
        var lang = (first(/([a-z]{2})\.vtt/i, m[1]) || "tr").toLowerCase();
        subs.push({ url: abs(playerUrl, m[1]), language: lang, name: lang.toUpperCase() + " [diziyou]" });
      }
      return [
        {
          name: "diziyou",
          title: "diziyou - 1080p [HLS]",
          url: url,
          quality: "1080p",
          headers: { Referer: episodeUrl },
          subtitles: subs.slice(0, 4)
        }
      ];
    })
    .catch(function (e) {
      console.log("[bb-nuvio][diziyou] " + (e && e.message));
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