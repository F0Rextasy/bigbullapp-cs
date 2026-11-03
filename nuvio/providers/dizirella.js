// @provider {"id":"dizirella","name":"Dizirella","types":["tv"],"domain":"dizirella.one","formats":["m3u8"],"sample":"tt1520211:tv:1:1","description":"Dizirella yabanci dizi arsivi (HLS + Turkce dublaj/altyazi)."}
/**
 * dizirella — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /<slug>/ (redirects to /dizi/<slug>/) -> episode -> dzembed iframe -> m3u8.
 * The search page is a fallback: it mostly lists the newest episodes, not the whole archive.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://dizirella.one";

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

/** Same as text(), plus the final URL after redirects (`/<slug>/` -> `/dizi/<slug>/`). */
function page(url) {
  return fetch(url, { headers: { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" } })
    .then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status + " " + url);
      return r.text().then(function (body) {
        return { url: r.url || url, body: body };
      });
    });
}

function abs(base, href) {
  if (!href) return null;
  href = String(href).replace(/&amp;/g, "&").trim();
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

/** Turkish-aware slug: the site builds `/<turkish-title-slug>/` from its own titles. */
function slugOf(title) {
  return String(title || "").toLowerCase()
    .replace(/ı/g, "i").replace(/ş/g, "s").replace(/ğ/g, "g")
    .replace(/ü/g, "u").replace(/ö/g, "o").replace(/ç/g, "c")
    .replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "");
}

/** Episode anchors on a series page: `/the-walking-dead-1-sezon-1-bolum-<code>-10668/`. */
function episodes(html, base, slug) {
  var out = [];
  var seen = {};
  var re = /href="([^"]*?-(\d+)-sezon-(\d+)-bolum[^"]*?)"/g;
  var m;
  while ((m = re.exec(html)) !== null) {
    var url = abs(base, m[1]);
    if (!url || seen[url]) continue;
    if (slug && url.indexOf("/" + slug + "-") === -1) continue;
    seen[url] = 1;
    out.push({ url: url, season: parseInt(m[2], 10), episode: parseInt(m[3], 10) });
  }
  return out;
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

/** Only pages whose URL carries the title slug belong to this show. */
function searchHits(html, base, slug) {
  var out = [];
  var re = /<a[^>]+href="([^"]+)"[^>]*title="([^"]*)"/g;
  var m;
  while ((m = re.exec(html)) !== null) {
    var url = abs(base, m[1]);
    if (!url || url.indexOf(BASE + "/") !== 0) continue;
    if (url.indexOf("/" + slug) === -1) continue;
    out.push({ url: url, title: m[2] });
  }
  return out;
}

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var episodeUrl = null;
  var frames = [];

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return [];
      var slug = slugOf(title);
      return page(BASE + "/" + slug + "/")
        .then(function (doc) {
          var found = episodes(doc.body, doc.url, slug);
          if (found.length) return found;
          return text(BASE + "/?s=" + encodeURIComponent(title)).then(function (html) {
            var hit = searchHits(html, BASE + "/", slug)[0];
            if (!hit) return [];
            return page(hit.url).then(function (next) {
              return episodes(next.body, next.url, slug);
            });
          });
        })
        .catch(function () {
          return [];
        });
    })
    .then(function (found) {
      for (var i = 0; i < found.length; i++) {
        if (found[i].season === want && found[i].episode === wantEp) {
          episodeUrl = found[i].url;
          break;
        }
      }
      if (!episodeUrl) episodeUrl = found.length ? found[0].url : null;
      return episodeUrl;
    })
    .then(function (url) {
      if (!url) return [];
      return text(url).then(function (html) {
        var re = /<iframe[^>]+(?:src|data-src|vdo-src)="([^"]+)"/g;
        var m;
        while ((m = re.exec(html)) !== null) {
          var frame = abs(url, m[1]);
          if (frame && /^https?:/i.test(frame) && frames.indexOf(frame) === -1) frames.push(frame);
        }
        return url;
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
              name: "dizirella",
              title: "dizirella - " + label + (item.hls ? " [HLS]" : " [MP4]"),
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
      console.log("[bb-nuvio][dizirella] " + (e && e.message));
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