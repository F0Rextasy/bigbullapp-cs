// @provider {"id":"hdfilmdelisi","name":"HDFilmDelisi","types":["movie"],"domain":"hdfilmdelisi1.com","formats":["mp4"],"description":"HDFilmDelisi - Turkce dublaj ve altyazili Full HD film arsivi (dogrudan MP4).","sample":"tt0133093:movie:0:0"}
/**
 * hdfilmdelisi — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /api/search JSON -> /film/<slug> JSON-LD VideoObject -> mp4/m3u8.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://hdfilmdelisi1.com";

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

function all(pattern, body) {
  var out = [];
  var re = new RegExp(pattern.source, pattern.flags.indexOf("g") < 0 ? pattern.flags + "g" : pattern.flags);
  var m;
  while ((m = re.exec(String(body || ""))) !== null) {
    out.push(m[1]);
    if (m[0] === "") re.lastIndex++;
  }
  return out;
}

function titleOf(id, mediaType) {
  var kind = mediaType === "tv" ? "series" : "movie";
  return text("https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json")
    .then(function (body) {
      var meta = JSON.parse(body).meta || {};
      return { name: String(meta.name || "").trim(), year: String(meta.releaseInfo || meta.year || "").slice(0, 4) };
    })
    .catch(function () {
      return { name: "", year: "" };
    });
}

/** Turkish-aware fold so "The Matrix" matches the site's "Matrix". */
function norm(value) {
  return String(value || "")
    .toLowerCase()
    .replace(/ı/g, "i").replace(/ş/g, "s").replace(/ğ/g, "g")
    .replace(/ü/g, "u").replace(/ö/g, "o").replace(/ç/g, "c")
    .replace(/^the\s+/, "")
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
}

function pick(results, id, title, year) {
  var want = norm(title);
  var i;
  for (i = 0; i < results.length; i++) {
    if (results[i].imdbId && results[i].imdbId === id) return results[i];
  }
  var exact = [];
  var partial = [];
  for (i = 0; i < results.length; i++) {
    var name = norm(results[i].baslik);
    var original = norm(results[i].orijinalBaslik);
    if (name === want || original === want) exact.push(results[i]);
    else if (name && (name.indexOf(want) === 0 || want.indexOf(name) === 0)) partial.push(results[i]);
  }
  var pool = exact.length ? exact : partial;
  if (pool.length) {
    if (pool.length > 1 && year) {
      for (i = 0; i < pool.length; i++) if (String(pool[i].yayinYili) === year) return pool[i];
    }
    return pool[0];
  }
  // Site stores Turkish titles ("Başlangıç"); trust the release year when it names one film.
  var byYear = [];
  for (i = 0; i < results.length; i++) if (year && String(results[i].yayinYili) === year) byYear.push(results[i]);
  return byYear.length === 1 ? byYear[0] : null;
}

function getStreams(tmdbId, mediaType, season, episode) {
  var title = "";
  var filmUrl = null;

  return titleOf(tmdbId, mediaType)
    .then(function (meta) {
      title = meta.name;
      if (!title) return null;
      return text(BASE + "/api/search?q=" + encodeURIComponent(title)).then(function (body) {
        var results = [];
        try {
          results = JSON.parse(body).results || [];
        } catch (e) {
          results = [];
        }
        return pick(results, tmdbId, title, meta.year);
      });
    })
    .then(function (hit) {
      if (!hit || !hit.slug) return null;
      filmUrl = /^https?:/i.test(hit.slug) ? hit.slug : BASE + "/film/" + String(hit.slug).replace(/^\/+/, "");
      return text(filmUrl);
    })
    .then(function (page) {
      if (!page) return [];
      var url = null;
      var embed = null;
      var ld = first(/<script[^>]*id="jsonld-video"[^>]*>([\s\S]*?)<\/script>/, page) || first(/<script[^>]*ld\+json[^>]*>(\{[^<]*"VideoObject"[\s\S]*?)<\/script>/, page);
      if (ld) {
        try {
          var obj = JSON.parse(ld);
          url = obj.contentUrl || null;
          embed = obj.embedUrl || null;
        } catch (e) {}
      }
      if (!url) {
        var found = all(/https?:\/\/[^"'\s<>\\]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>\\]*)?/, page).filter(function (u) {
          return u.indexOf("/vids/") !== -1 || u.indexOf(".m3u8") !== -1;
        });
        url = found[0] || null;
      }
      if (!url && embed) {
        return text(embed, { referer: filmUrl }).then(function (inner) {
          var media = all(/https?:\/\/[^"'\s<>\\]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>\\]*)?/, inner);
          return media.length ? [row(media[0], filmUrl)] : [];
        });
      }
      if (!url) return [];
      return [row(url, filmUrl)];
    })
    .catch(function (e) {
      console.log("[bb-nuvio][hdfilmdelisi] " + (e && e.message));
      return [];
    });
}

function row(url, page) {
  var hls = url.indexOf(".m3u8") !== -1;
  return {
    name: "HDFilmDelisi",
    title: "HDFilmDelisi - " + (hls ? "HLS" : "Full HD"),
    url: url,
    quality: hls ? "1080p" : "HD",
    headers: { "User-Agent": UA, Referer: page },
    subtitles: []
  };
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