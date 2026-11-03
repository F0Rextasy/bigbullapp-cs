// @provider {"id":"filmmodu","name":"FilmModu","types":["movie"],"domain":"filmmodu.news","formats":["m3u8"],"description":"FilmModu Türkçe dublaj/altyazılı film arşivi (pilavyerplay oynatıcısı).","sample":"tt0133093:movie:1:1"}
/**
 * filmmodu — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /ara search -> film page -> data-pv slug -> player shell
 * (s.php?<slug>) -> window.__PLAYER__ {stream, subs} -> HLS master + VTT tracks.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
var BASE = "https://filmmodu.news";
var PLAYER_FALLBACK = "https://play2.pilavyerplay.top";

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
  if (href.charAt(0) === "/") return origin ? origin[1] + href : href;
  return base.replace(/[^/]*$/, "") + href;
}

function normTitle(value) {
  return String(value || "")
    .toLowerCase()
    .replace(/\s*\bizle\b.*$/, "")
    .replace(/\s*\bfilm\b.*$/, "")
    .replace(/^(?:the|bir)\s+/, "")
    .replace(/[^a-z0-9]/g, "");
}

function metaOf(id, mediaType) {
  var kind = mediaType === "tv" ? "series" : "movie";
  return text("https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json")
    .then(function (body) {
      var meta = JSON.parse(body).meta || {};
      return { name: String(meta.name || "").trim(), year: String(meta.year || "").slice(0, 4) };
    })
    .catch(function () {
      return { name: "", year: "" };
    });
}

/** Search cards: <a href="…/film/<slug>" class="group block">…<h3>Title</h3><p>1999</p>. */
function findFilm(title, year) {
  return text(BASE + "/ara?q=" + encodeURIComponent(title))
    .then(function (page) {
      var want = normTitle(title);
      var re = /<a href="([^"]+\/film\/[^"]+)" class="group block">([\s\S]*?)<\/a>/g;
      var m, best = null, bestScore = -1;
      while ((m = re.exec(page)) !== null) {
        var got = normTitle(first(/<h3[^>]*>([\s\S]*?)<\/h3>/, m[2]));
        if (!got) continue;
        var score = got === want ? 100 : got.indexOf(want) >= 0 || want.indexOf(got) >= 0 ? 60 : 0;
        var cardYear = first(/<p[^>]*>\s*(\d{4})\s*<\/p>/, m[2]);
        if (year && cardYear === year) score += 40;
        if (score > bestScore) {
          bestScore = score;
          best = abs(BASE, m[1]);
        }
      }
      return best;
    })
    .catch(function () {
      return null;
    });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var pageUrl = null;

  return metaOf(tmdbId, mediaType)
    .then(function (meta) {
      if (!meta.name) return null;
      return findFilm(meta.name, meta.year);
    })
    .then(function (url) {
      if (!url) return [];
      pageUrl = url;
      return text(url);
    })
    .then(function (page) {
      if (!page) return [];
      // The player host rides along in the film page's core.js <script> tag.
      var core = first(/<script[^>]+src="([^"]*\/assets\/js\/core\.js[^"]*)"/, page);
      var playerBase = core && /^https?:/i.test(core) ? core.replace(/\/assets\/js\/core\.js.*$/, "") : PLAYER_FALLBACK;

      var slugs = [], re = /data-pv="([^"]+)"/g, m;
      while ((m = re.exec(page)) !== null) if (m[1] && slugs.indexOf(m[1]) < 0) slugs.push(m[1].trim());
      if (!slugs.length) return [];

      return Promise.all(slugs.map(function (slug) {
        var shell = playerBase + "/assets/js/s.php?s=" + encodeURIComponent(slug);
        // s.php is origin-locked: it answers 403 without the film page as referer.
        return text(shell, { referer: pageUrl })
          .then(function (html) {
            var json = first(/window\.__PLAYER__\s*=\s*(\{[\s\S]*?\});\s*<\/script>/, html);
            if (!json) return [];
            var player = JSON.parse(json);
            var stream = String(player.stream || "");
            if (!/^https?:/i.test(stream)) return [];
            var subs = [];
            (player.subs || []).forEach(function (sub) {
              if (!sub || !/^https?:/i.test(String(sub.src || ""))) return;
              var lang = String(sub.lang || "tr").toLowerCase();
              subs.push({ url: sub.src, language: lang, name: String(sub.label || lang).trim() + " [filmmodu]" });
            });
            return [{
              name: "filmmodu",
              title: "filmmodu - " + (subs.length ? "Türkçe Dublaj & Altyazı" : "Film") + " [HLS]",
              url: stream,
              quality: "HD",
              headers: { Referer: shell },
              subtitles: subs.slice(0, 6)
            }];
          })
          .catch(function () {
            return [];
          });
      }));
    })
    .then(function (lists) {
      var out = [], seen = {};
      (lists || []).forEach(function (list) {
        (list || []).forEach(function (item) {
          if (!item.url || seen[item.url]) return;
          seen[item.url] = 1;
          out.push(item);
        });
      });
      return out;
    })
    .catch(function (e) {
      console.log("[bb-nuvio][filmmodu] " + (e && e.message));
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