// @provider {"id":"diziabi","name":"DiziAbi","types":["tv","movie"],"domain":"diziabi.net","formats":["m3u8"],"sample":"tt0903747:tv:1:1","description":"DiziAbi yabanci dizi/film arsivi; pilavyer oynaticisindan HLS akisi ve altyazi."}
/**
 * diziabi — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /ara?q= -> /dizi/<slug>/ -> /bolum/<slug>-S-sezon-E-bolum/
 *       -> [data-pv] + core.js tabani -> s.php -> window.__PLAYER__ -> HLS + altyazi.
 */
var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://www.diziabi.net";
var ID = "diziabi";

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
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
  if (!origin) return href;
  if (href.indexOf("//") === 0) return "https:" + href;
  if (href.charAt(0) === "/") return origin[1] + href;
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

function playerConfig(frame) {
  var m = /window\.__PLAYER__\s*=\s*(\{[\s\S]*?\});/.exec(String(frame || ""));
  if (!m) return null;
  try {
    return JSON.parse(m[1]);
  } catch (e) {
    return null;
  }
}

function qualityOf(master, fallback) {
  var re = /RESOLUTION=\d+x(\d+)/g;
  var best = 0;
  var m;
  while ((m = re.exec(String(master || ""))) !== null) {
    var h = parseInt(m[1], 10);
    if (h > best) best = h;
  }
  return best ? best + "p" : fallback;
}

// Bölüm sayfası -> data-pv + oynatıcı tabanı -> s.php -> __PLAYER__
function resolvePlayer(pageUrl) {
  return text(pageUrl).then(function (page) {
    var pv = first(/data-pv="([^"]+)"/, page);
    if (!pv) return null;
    var core = first(/<script[^>]+src="([^"]*core\.js[^"]*)"/, page);
    var playerBase = core ? abs(pageUrl, core).replace(/\/assets\/js\/core\.js.*$/, "") : "https://pilavyerplay.top";
    return text(playerBase + "/assets/js/s.php?s=" + encodeURIComponent(pv), { referer: pageUrl }).then(function (frame) {
      var cfg = playerConfig(frame);
      return cfg && cfg.stream ? { cfg: cfg, playerBase: playerBase, pageUrl: pageUrl } : null;
    });
  });
}

function rowsOf(found, name) {
  return text(found.cfg.stream, { referer: found.playerBase + "/" })
    .catch(function () {
      return "";
    })
    .then(function (master) {
      var quality = qualityOf(master, "1080p");
      var subs = [];
      var list = found.cfg.subs || [];
      for (var i = 0; i < list.length && subs.length < 4; i++) {
        var sub = list[i] || {};
        if (!sub.src) continue;
        var lang = String(sub.lang || "tr").toLowerCase();
        subs.push({ url: sub.src, language: lang, name: String(sub.label || lang.toUpperCase()) + " [" + name + "]" });
      }
      return [
        {
          name: name,
          title: name + " - " + quality + " [HLS]",
          url: found.cfg.stream,
          quality: quality,
          headers: { Referer: found.playerBase + "/" },
          subtitles: subs
        }
      ];
    });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var seriesUrl = null;

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return null;
      return text(BASE + "/ara?q=" + encodeURIComponent(title));
    })
    .then(function (search) {
      if (!search) return null;
      var link = first(/href="([^"]*\/(?:dizi|film)\/[^"?#]+)"/, search);
      if (!link) return null;
      seriesUrl = abs(BASE, link);
      return text(seriesUrl);
    })
    .then(function (page) {
      if (!page) return null;
      var slug = seriesUrl.replace(/[?#].*$/, "").replace(/\/+$/, "").split("/").pop();
      // Yapı: /bolum/<slug>-<sezon>-sezon-<bolum>-bolum/
      var built = BASE + "/bolum/" + slug + "-" + want + "-sezon-" + wantEp + "-bolum/";
      var re = /href="([^"]*-(\d+)-sezon-(\d+)-bolum\/?)"/g;
      var m;
      while ((m = re.exec(page)) !== null) {
        if (parseInt(m[2], 10) === want && parseInt(m[3], 10) === wantEp) return abs(seriesUrl, m[1]);
      }
      return built;
    })
    .then(resolvePlayer)
    .then(function (found) {
      return found ? rowsOf(found, ID) : [];
    })
    .catch(function (e) {
      console.log("[bb-nuvio][" + ID + "] " + (e && e.message));
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