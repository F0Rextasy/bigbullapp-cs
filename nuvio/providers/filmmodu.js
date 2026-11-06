// @provider {"id":"filmmodu", "name":"FilmModu", "types":["movie"], "domain":"filmmodu.news", "formats":["m3u8"], "sample":"tt0133093:movie:1:1", "description":"FilmModu Türkçe dublaj/altyazılı film arşivi (pilavyerplay HLS + altyazı).", "version":"1.1.0", "verified":true}
/**
 * filmmodu — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /ara?q= -> strict card match -> film page -> data-pv slug ->
 *       pilavyerplay s.php -> window.__PLAYER__ {stream, audios, subs} -> HLS master + VTT.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://filmmodu.news";
var PLAYER_FALLBACK = "https://play2.pilavyerplay.top";

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
  if (String(href).charAt(0) === "/") return origin ? origin[1] + href : href;
  return base.replace(/[^/]*$/, "") + href;
}

function norm(value) {
  return String(value || "")
    .toLowerCase()
    .replace(/[\u0131]/g, "i")
    .replace(/[\u015f]/g, "s")
    .replace(/[\u011f]/g, "g")
    .replace(/[\u00fc]/g, "u")
    .replace(/[\u00f6]/g, "o")
    .replace(/[\u00e7]/g, "c")
    .replace(/[^a-z0-9]/g, "");
}

function spaced(value) {
  return String(value || "")
    .replace(/\s*\([^)]*\)/g, " ")
    .replace(/\b(?:izle|film|full|hd|turkce|türkçe|dublaj|altyazili|altyazılı)\b/gi, " ")
    .split(/[\s:\-]+/)
    .map(norm)
    .filter(Boolean)
    .join(" ")
    .replace(/^(?:the|an|a) /, "");
}

function pick(cards, wanted, year) {
  var w = spaced(wanted);
  if (!w) return null;
  var exact = null;
  var prefix = null;
  for (var i = 0; i < cards.length; i++) {
    var n = spaced(cards[i].title);
    if (!n) continue;
    if (n === w) {
      if (year && cards[i].year === year) return cards[i];
      if (!exact) exact = cards[i];
    } else if (n.indexOf(w + " ") === 0) {
      if (year && cards[i].year === year && !prefix) prefix = cards[i];
      else if (!prefix) prefix = cards[i];
    }
  }
  return exact || prefix;
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

function findFilm(title, year) {
  return text(BASE + "/ara?q=" + encodeURIComponent(title))
    .then(function (page) {
      var cards = [];
      var re = /<a\b[^>]*href="([^"]*\/film\/[^"]+)"[^>]*>([\s\S]*?)<\/a>/g;
      var m;
      while ((m = re.exec(page)) !== null) {
        var h3 = first(/<h3[^>]*>([\s\S]*?)<\/h3>/, m[2]);
        if (!h3) continue;
        var cardYear = first(/<p[^>]*>\s*(\d{4})\s*<\/p>/, m[2]) || "";
        cards.push({ url: abs(BASE, m[1]), title: h3.replace(/<[^>]+>/g, "").trim(), year: cardYear });
      }
      var hit = pick(cards, title, year);
      return hit ? hit.url : null;
    })
    .catch(function () {
      return null;
    });
}

function getStreams(tmdbId, mediaType, season, episode) {
  if (mediaType === "tv") return Promise.resolve([]);
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
      var core = first(/<script[^>]+src="([^"]*\/assets\/js\/core\.js[^"]*)"/, page);
      var playerBase = core && /^https?:/i.test(core) ? core.replace(/\/assets\/js\/core\.js.*$/, "") : PLAYER_FALLBACK;

      var slugs = [];
      var re = /data-pv="([^"]+)"/g;
      var m;
      while ((m = re.exec(page)) !== null) {
        if (m[1] && slugs.indexOf(m[1]) < 0) slugs.push(m[1].trim());
      }
      if (!slugs.length) return [];

      return Promise.all(slugs.map(function (slug) {
        var shell = playerBase + "/assets/js/s.php?s=" + encodeURIComponent(slug);
        return text(shell, { referer: pageUrl })
          .then(function (html) {
            var json = first(/window\.__PLAYER__\s*=\s*(\{[\s\S]*?\});\s*<\/script>/, html);
            if (!json) return [];
            var player = JSON.parse(json);
            var stream = String(player.stream || "").trim();
            if (!/^https?:/i.test(stream)) return [];
            var audios = [];
            (player.audios || []).forEach(function (a) {
              var lbl = String((a && a.label) || "").trim();
              if (lbl) audios.push(lbl);
            });
            var subs = [];
            (player.subs || []).forEach(function (sub) {
              if (!sub || !/^https?:/i.test(String(sub.src || ""))) return;
              var lang = String(sub.lang || "tr").toLowerCase();
              subs.push({ url: sub.src, language: lang, name: String(sub.label || lang).trim() + " [FilmModu]" });
            });
            return qualityOf(stream, shell).then(function (q) {
              return [{
                name: "FilmModu",
                title: "FilmModu - " + (audios.length ? audios.join(" / ") : "HLS"),
                url: stream,
                quality: q,
                headers: { Referer: shell, "User-Agent": UA },
                subtitles: subs.slice(0, 6)
              }];
            });
          })
          .catch(function () {
            return [];
          });
      }));
    })
    .then(function (lists) {
      var out = [];
      var seen = {};
      (lists || []).forEach(function (list) {
        (list || []).forEach(function (item) {
          if (!item || !item.url || seen[item.url]) return;
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

/* Dil etiketi (AGENTS.md): Nuvio karti "quality \u2022 ..." satirini gosterir; dil etiketi kalitenin yanina yazilir. */
var __subOnly = false;
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
