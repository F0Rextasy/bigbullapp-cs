// @provider {"id":"dizimom","name":"DiziMom","types":["tv"],"domain":"ddizimom.net","formats":["m3u8"],"sample":"tt0903747:tv:1:1","description":"DiziMom - yabanci diziler Turkce altyazili/dublaj (pilavyer HLS)."}
/**
 * dizimom — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /ara?q= -> /dizi/<slug> -> /sezon-S/bolum-E -> data-pv slug ->
 *       pilavyer s.php -> window.__PLAYER__ JSON -> stream + subs.
 */
var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://ddizimom.net";
var PLAYER = "https://pilavyerplay.top";

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
  if (o.headers) for (var k in o.headers) headers[k] = o.headers[k];
  return fetch(url, { method: o.method || "GET", headers: headers }).then(function (r) {
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
  if (String(href).charAt(0) === "/") return origin ? origin[1] + href : href;
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

function episodeUrlFor(seriesHtml, seriesUrl, season, episode) {
  var re = /href="([^"]*\/sezon-(\d+)\/bolum-(\d+))"/g;
  var m;
  var fallback = null;
  while ((m = re.exec(seriesHtml)) !== null) {
    var url = abs(seriesUrl, m[1]);
    if (parseInt(m[2], 10) === season && parseInt(m[3], 10) === episode) return Promise.resolve(url);
    if (!fallback) fallback = url;
  }
  // Only the active season's episodes are printed; other seasons have their own ?sezon=N page.
  return text(seriesUrl + "?sezon=" + season + "#bolumler")
    .then(function (html) {
      var found = first(new RegExp("href=\"([^\"]*/sezon-" + season + "/bolum-" + episode + ")\""), html);
      return found ? abs(seriesUrl, found) : fallback;
    })
    .catch(function () {
      return fallback;
    });
}

function jsonObjectAt(before, body) {
  var at = String(body || "").indexOf(before);
  if (at < 0) return null;
  at = body.indexOf("{", at);
  if (at < 0) return null;
  var depth = 0;
  var inString = false;
  var escaped = false;
  for (var i = at; i < body.length; i++) {
    var ch = body.charAt(i);
    if (inString) {
      if (escaped) escaped = false;
      else if (ch === "\\") escaped = true;
      else if (ch === '"') inString = false;
      continue;
    }
    if (ch === '"') inString = true;
    else if (ch === "{") depth++;
    else if (ch === "}") {
      depth--;
      if (depth === 0) return body.slice(at, i + 1);
    }
  }
  return null;
}

function qualityOf(masterUrl, referer) {
  return text(masterUrl, { referer: referer })
    .then(function (playlist) {
      var re = /RESOLUTION=\d+x(\d+)/g;
      var m;
      var best = 0;
      while ((m = re.exec(playlist)) !== null) best = Math.max(best, parseInt(m[1], 10) || 0);
      return best > 0 ? best + "p" : "auto";
    })
    .catch(function () {
      return "auto";
    });
}

function playerOf(slug, pageUrl) {
  return text(PLAYER + "/assets/js/s.php?s=" + encodeURIComponent(slug), { referer: pageUrl }).then(function (shell) {
    var raw = jsonObjectAt("window.__PLAYER__", shell);
    if (!raw) return null;
    var cfg = JSON.parse(raw);
    var stream = String(cfg.stream || "").trim();
    if (!/^https?:/.test(stream)) return null;
    var dubs = [];
    (cfg.audios || []).forEach(function (a) {
      var label = String((a && a.label) || "").trim();
      if (label) dubs.push(label);
    });
    var subs = [];
    (cfg.subs || []).forEach(function (s) {
      var src = String((s && s.src) || "").trim();
      if (!/^https?:/.test(src)) return;
      subs.push({ url: src, language: String(s.lang || "tr"), name: String(s.lang || "tr").toUpperCase() + " [dizimom]" });
    });
    return qualityOf(stream, BASE + "/").then(function (quality) {
      return {
        name: "dizimom",
        title: "dizimom - " + (dubs.length ? dubs.join(" / ") : "Pilavyer") + " [" + quality + " HLS]",
        url: stream,
        quality: quality,
        headers: { Referer: BASE + "/", "User-Agent": UA },
        subtitles: subs.slice(0, 4)
      };
    });
  });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var seriesUrl = null;
  var pageUrl = null;

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return null;
      return text(BASE + "/ara?q=" + encodeURIComponent(title));
    })
    .then(function (search) {
      if (!search) return null;
      var link = first(/href="(https?:\/\/[^"]*\/dizi\/[^"?#]+)"/, search) || first(/href="(\/dizi\/[^"?#]+)"/, search);
      if (!link) return null;
      seriesUrl = abs(BASE, link);
      return text(seriesUrl);
    })
    .then(function (seriesHtml) {
      if (!seriesHtml) return null;
      return episodeUrlFor(seriesHtml, seriesUrl, want, wantEp);
    })
    .then(function (episodeUrl) {
      if (!episodeUrl) return [];
      pageUrl = episodeUrl;
      return text(pageUrl);
    })
    .then(function (page) {
      if (!page) return [];
      var slugs = [];
      var re = /data-pv="([A-Za-z0-9_\-]{8,})"/g;
      var m;
      while ((m = re.exec(page)) !== null) {
        if (slugs.indexOf(m[1]) === -1) slugs.push(m[1]);
      }
      if (!slugs.length) return [];
      var jobs = slugs.slice(0, 2).map(function (slug) {
        return playerOf(slug, pageUrl).catch(function () {
          return null;
        });
      });
      return Promise.all(jobs);
    })
    .then(function (players) {
      var out = [];
      (players || []).forEach(function (item) {
        if (item) out.push(item);
      });
      return out;
    })
    .catch(function (e) {
      console.log("[bb-nuvio][dizimom] " + (e && e.message));
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