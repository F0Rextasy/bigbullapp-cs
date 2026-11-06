// @provider {"id":"dizibox", "name":"Dizibox", "types":["tv"], "domain":"dizibox.live", "formats":["m3u8"], "description":"Dizibox dizi kaynaklari (king.php -> sheila HLS).", "sample":"tt0903747:tv:2:5", "version":"1.0.12"}
/**
 * dizibox — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> site search -> series -> episode -> king.php player -> embed -> sheila HLS.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://www.dizibox.live";

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

function sameTitle(have, want) {
  function f(v) {
    return String(v || "").replace(/[\u0131\u0130I]/g, "i").replace(/[\u015f\u015e]/g, "s").replace(/[\u011f\u011e]/g, "g")
      .replace(/[\u00e7\u00c7]/g, "c").replace(/[\u00f6\u00d6]/g, "o").replace(/[\u00fc\u00dc]/g, "u")
      .toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
  }
  have = f(have);
  want = f(want);
  return !!want && (have === want || have.indexOf(want + " ") === 0);
}

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var candidates = [];
  var episodeUrl = null;
  var embedUrl = null;

  // The site answers a degraded (episode-less) series page now and then and throttles
  // bursts: walk at most two search hits and never re-request the same page.
  function links(detail) {
    var out = [];
    var re = /href=["']([^"']+)["'][^>]*class=["']season-episode[^"']*["']/g;
    var m;
    while ((m = re.exec(String(detail || ""))) !== null) out.push(abs(BASE, m[1]));
    return out;
  }

  function findEp(list) {
    var exact = new RegExp("-" + want + "-sezon-" + wantEp + "-bolum");
    for (var i = 0; i < list.length; i++) if (exact.test(list[i])) return list[i];
    return null;
  }

  // A series page only lists one season; its tabs give the requested one in a single hop.
  function seasonPage(detail) {
    var m = new RegExp("href=[\"']([^\"']*/" + want + "-sezon-[^/\"']+/)[\"']").exec(String(detail || ""));
    return m ? abs(BASE, m[1]) : null;
  }

  var wantTitle = "";

  function fromSeries(i) {
    if (i >= candidates.length) return Promise.resolve(null);
    return text(candidates[i])
      .then(function (detail) {
        var list = links(detail);
        var hit = findEp(list);
        if (hit) return hit;
        var first = null;
        var tab = seasonPage(detail);
        if (!tab) return first || fromSeries(i + 1);
        return text(tab)
          .then(function (season) {
            return findEp(links(season)) || first || fromSeries(i + 1);
          })
          .catch(function () {
            return first || fromSeries(i + 1);
          });
      })
      .catch(function () {
        return fromSeries(i + 1);
      });
  }

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      wantTitle = title;
      if (!title) return null;
      return text(BASE + "/search/" + encodeURIComponent(title).replace(/%20/g, "+") + "/");
    })
    .then(function (search) {
      if (!search) return null;
      var re = /<article class="detailed-article"[\s\S]*?<h3[^>]*><a href="([^"]+)"[^>]*>([^<]*)/g;
      var m;
      while ((m = re.exec(search)) !== null && candidates.length < 2) {
        if (sameTitle(m[2], wantTitle)) candidates.push(abs(BASE, m[1]));
      }
      return fromSeries(0);
    })
    .then(function (ep) {
      if (!ep) return null;
      episodeUrl = ep;
      return text(ep);
    })
    .then(function (page) {
      if (!page) return null;
      var src = first(/id="video-area"[\s\S]*?<iframe[^>]+src="([^"]+)"/, page);
      if (!src) return null;
      var player = abs(episodeUrl, src);
      if (player.indexOf("king.php") === -1) return null;
      return text(player.replace("king.php?v=", "king.php?wmode=opaque&v="), { referer: episodeUrl });
    })
    .then(function (king) {
      if (!king) return null;
      var src = first(/id="Player"[\s\S]*?<iframe[^>]+src="([^"]+)"/, king);
      if (!src) return null;
      embedUrl = abs(episodeUrl, src);
      var stream = embedUrl.replace("/embed/", "/embed/sheila/");
      if (stream === embedUrl) return null;
      // The sheila master only answers with a Referer; without one it serves a "banned" page.
      return text(stream, { referer: embedUrl }).then(function (playlist) {
        if (playlist.indexOf("#EXTM3U") === -1) return null;
        var height = first(/RESOLUTION=\d+x(\d+)/, playlist);
        var quality = height ? height + "p" : "auto";
        return [
          {
            name: "Dizibox",
            title: "Dizibox - " + quality + " [HLS]",
            url: stream,
            quality: quality,
            headers: { Referer: embedUrl },
            subtitles: []
          }
        ];
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][dizibox] " + (e && e.message));
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

/* Dil etiketi (AGENTS.md): Nuvio karti "quality \u2022 ..." satirini gosterir; dil etiketi kalitenin yanina yazilir.
   Kanit: baslikta dublaj/altyazi, akisa eklenmis altyazi dosyasi ya da sitenin yalniz altyazili olmasi. Kanit yoksa etiket yok. */
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
