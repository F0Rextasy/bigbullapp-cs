// @provider {"id":"filmizyon","name":"Filmizyon","types":["movie"],"domain":"filmizyon.net","formats":["m3u8"],"description":"Filmizyon Türkçe dublajlı film arşivi (vidmoly ve doğrudan HLS kaynakları).","sample":"tt0133093:movie:1:1"}
/**
 * filmizyon — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /?s= search (cards carry the IMDb id in their poster
 * filename + year) -> film page -> iframe players -> inner m3u8/mp4 + VTT tracks.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://www.filmizyon.net";

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
    .replace(/\s*(?:t(u|ü)rkçe|dublaj|altyaz)/g, " ")
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

/** Poster cards: <div class="poster-container">…<a href="…/film/<slug>" title="…">… */
function findFilm(title, year, imdbId) {
  return text(BASE + "/?s=" + encodeURIComponent(title))
    .then(function (page) {
      var want = normTitle(title);
      var chunks = String(page).split("poster-container");
      var best = null, bestScore = -1;
      for (var i = 1; i < chunks.length; i++) {
        var chunk = chunks[i];
        var href = first(/<a href="(https:\/\/[^"]+\/film\/[^"]+)"/, chunk);
        if (!href) continue;
        var cardImdb = first(/\b(tt\d{7,9})\b/, chunk);
        var label = normTitle(first(/<a[^>]+title="([^"]*)"/, chunk));
        var score = cardImdb && cardImdb === imdbId ? 100 : label === want ? 90 : label.indexOf(want) >= 0 || want.indexOf(label) >= 0 ? 50 : 0;
        var cardYear = first(/class="poster-year">\s*(\d{4})\s*</, chunk);
        if (year && cardYear === year) score += 40;
        if (score > bestScore) {
          bestScore = score;
          best = abs(BASE, href);
        }
      }
      return best;
    })
    .catch(function () {
      return null;
    });
}
function flatten(html) {
  return String(html || "").replace(/\\\//g, "/").replace(/\\"/g, '"');
}

/** The theme ships an ad bumper that must never be offered as the film. */
function mediaIn(body) {
  var flat = flatten(body);
  var out = [], m, re = /https?:\/\/[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?/g;
  while ((m = re.exec(flat)) !== null) {
    if (/\/reklam\//.test(m[0]) || out.indexOf(m[0]) >= 0) continue;
    out.push(m[0]);
  }
  return out;
}

function subtitlesIn(body) {
  var flat = flatten(body);
  var out = [], m, re = /https?:\/\/[^"'\s<>]+?\.(?:vtt|srt)(?:\?[^"'\s<>]*)?/g;
  while ((m = re.exec(flat)) !== null) {
    if (out.length >= 4) break;
    out.push({ url: m[0], language: "tr", name: "TR [filmizyon]" });
  }
  return out;
}

function qualityIn(body) {
  // vidmoly advertises "720p HD" in its jwplayer label.
  var label = first(/label:\s*["']([^"']+)["']/, flatten(body)) || "";
  var size = first(/(\d{3,4})p/i, label);
  return size ? size + "p" : "HD";
}

function getStreams(tmdbId, mediaType, season, episode) {
  var filmUrl = null;

  return metaOf(tmdbId, mediaType)
    .then(function (meta) {
      if (!meta.name) return null;
      return findFilm(meta.name, meta.year, tmdbId);
    })
    .then(function (url) {
      if (!url) return [];
      filmUrl = url;
      return text(url);
    })
    .then(function (page) {
      if (!page) return [];
      var out = [];

      mediaIn(page).forEach(function (url) {
        out.push({
          name: "filmizyon",
          title: "filmizyon - Direct [HLS]",
          url: url,
          quality: "HD",
          headers: { Referer: filmUrl },
          subtitles: subtitlesIn(page)
        });
      });

      var frames = [], re = /<iframe[^>]+(?:src|data-src|vdo-src)="([^"]+)"/g, m;
      while ((m = re.exec(page)) !== null) {
        var frame = abs(filmUrl, m[1]);
        if (frame && frames.indexOf(frame) < 0) frames.push(frame);
      }
      if (!frames.length) return out;

      return Promise.all(frames.map(function (frame) {
        if (/\.(?:m3u8|mp4)/i.test(frame)) {
          return [{ name: "filmizyon", title: "filmizyon - Embed [HLS]", url: frame, quality: "HD", headers: { Referer: filmUrl }, subtitles: [] }];
        }
        return text(frame, { referer: filmUrl })
          .then(function (inner) {
            var quality = qualityIn(inner);
            var subs = subtitlesIn(inner);
            return mediaIn(inner).map(function (url) {
              return {
                name: "filmizyon",
                title: "filmizyon - " + quality + " [HLS]",
                url: url,
                quality: quality,
                headers: { Referer: frame },
                subtitles: subs
              };
            });
          })
          .catch(function () {
            return [];
          });
      })).then(function (lists) {
        lists.forEach(function (list) {
          list.forEach(function (item) {
            out.push(item);
          });
        });
        return out;
      });
    })
    .then(function (rows) {
      var out = [], seen = {};
      (rows || []).forEach(function (row) {
        if (!row.url || seen[row.url]) return;
        seen[row.url] = 1;
        out.push(row);
      });
      return out;
    })
    .catch(function (e) {
      console.log("[bb-nuvio][filmizyon] " + (e && e.message));
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