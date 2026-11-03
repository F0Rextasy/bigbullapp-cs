// @provider {"id":"dizilab","name":"Dizilab","types":["tv"],"domain":"dizilab.cc","formats":["m3u8"],"sample":"tt1520211:tv:1:1","description":"Dizilab yabanci dizi arsivi (HLS + Turkce dublaj/altyazi)."}
/**
 * dizilab — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /?s= search -> series -> episode -> dzembed iframe -> m3u8.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://dizilab.cc";

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
  return fetch(url, { method: o.method || "GET", headers: headers })
    .then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status + " " + url);
      return r.text();
    });
}

function first(pattern, body) {
  var m = pattern.exec(String(body || ""));
  return m ? m[1] : null;
}

function unescape(href) {
  return String(href || "").replace(/&amp;/g, "&").replace(/&#0*39;/g, "'");
}

function abs(base, href) {
  if (!href) return null;
  href = unescape(href).trim();
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

function norm(value) {
  return String(value || "").toLowerCase()
    .replace(/[^a-z0-9]+/g, " ")
    .replace(/\b(izle|hd|full|turkce|dublaj|altyazili)\b/g, " ")
    .replace(/\s+/g, " ").trim();
}

/** `article.card` blocks of the search archive, in page order. */
function cards(html) {
  var out = [];
  var re = /<article[^>]*class="[^"]*\bcard\b[^"]*"[\s\S]*?<\/article>/g;
  var block;
  while ((block = re.exec(html)) !== null) {
    var href = first(/<a[^>]+href="([^"]*\/dizi\/[^"]*)"/, block[0]);
    var title = first(/<h3[^>]*>([\s\S]*?)<\/h3>/, block[0]);
    if (!href || !title) continue;
    out.push({ url: abs(BASE, href), title: title.replace(/<[^>]+>/g, "").trim() });
  }
  return out;
}

function pick(cards, title) {
  var want = norm(title);
  var best = null;
  var bestScore = -1;
  for (var i = 0; i < cards.length; i++) {
    var have = norm(cards[i].title);
    if (!have) continue;
    var score = have === want ? 100 : have.indexOf(want) === 0 || want.indexOf(have) === 0 ? 80 : want.indexOf(have) !== -1 ? 60 : have.indexOf(want) !== -1 ? 50 : 0;
    if (score > bestScore) {
      bestScore = score;
      best = cards[i];
    }
    if (bestScore === 100) break;
  }
  return bestScore >= 60 ? best : null;
}

/** Master playlist names its variant (`RESOLUTION=1920x1080`); fall back to auto. */
function quality(item) {
  if (!item.hls) return Promise.resolve("auto");
  return text(item.url, { referer: item.frame })
    .then(function (body) {
      var best = 0;
      var re = /RESOLUTION=\d+x(\d+)/g;
      var m;
      while ((m = re.exec(body)) !== null) {
        var height = parseInt(m[1], 10) || 0;
        if (height > best) best = height;
      }
      return best ? best + "p" : "auto";
    })
    .catch(function () {
      return "auto";
    });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var episodeUrl = null;
  var frames = [];

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return null;
      return text(BASE + "/?s=" + encodeURIComponent(title)).then(function (html) {
        return pick(cards(html), title);
      });
    })
    .then(function (series) {
      if (!series) return null;
      return text(series.url).then(function (detail) {
        var links = [];
        var seen = {};
        var re = /href="([^"]*\/bolum\/[^"]+)"/g;
        var m;
        while ((m = re.exec(detail)) !== null) {
          var url = abs(series.url, m[1]);
          if (url && !seen[url]) {
            seen[url] = 1;
            links.push(url);
          }
        }
        var exact = new RegExp("-" + want + "-sezon-" + wantEp + "-bolum");
        for (var i = 0; i < links.length; i++) {
          if (exact.test(links[i])) {
            episodeUrl = links[i];
            break;
          }
        }
        if (!episodeUrl) episodeUrl = links[0] || null;
        return episodeUrl;
      });
    })
    .then(function (page) {
      if (!page) return [];
      return text(page).then(function (html) {
        var re = /<iframe[^>]+(?:src|data-src)="([^"]+)"/g;
        var m;
        while ((m = re.exec(html)) !== null) {
          var url = abs(page, m[1]);
          if (url && /^https?:/i.test(url) && frames.indexOf(url) === -1) frames.push(url);
        }
        return episodeUrl;
      });
    })
    .then(function (referer) {
      return frames.reduce(function (chain, frame) {
        return chain.then(function (found) {
          return text(frame, { referer: referer })
            .then(function (html) {
              var body = html.replace(/\\\//g, "/");
              var re = /https?:\/\/[^"'\s<>\\]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>\\]*)?/g;
              var m;
              while ((m = re.exec(body)) !== null) {
                if (found.some(function (item) { return item.url === m[0]; })) continue;
                found.push({ url: m[0], frame: frame, hls: m[0].indexOf(".m3u8") !== -1 });
              }
              return found;
            })
            .catch(function () {
              return found;
            });
        });
      }, Promise.resolve([]));
    })
    .then(function (found) {
      return found.reduce(function (chain, item) {
        return chain.then(function (rows) {
          return quality(item).then(function (label) {
            rows.push({
              name: "dizilab",
              title: "dizilab - " + label + (item.hls ? " [HLS]" : " [MP4]"),
              url: item.url,
              quality: label,
              headers: { Referer: item.frame },
              subtitles: []
            });
            return rows;
          });
        });
      }, Promise.resolve([]));
    })
    .catch(function (e) {
      console.log("[bb-nuvio][dizilab] " + (e && e.message));
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