// @provider {"id":"diziasia","name":"DiziAsia","types":["movie","tv"],"domain":"diziasia.com","formats":["mp4","m3u8"],"sample":"tt39333617:tv:1:1","description":"DiziAsia (diziasia.com) Asya dizi ve filmleri; ok.ru / VK oynaticilari."}
/**
 * diziasia — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /arama/<q> -> series -> -S-sezon-E-bolum -> /ajax/embed -> ok.ru videos JSON.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://diziasia.com";

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
  if (o.headers) for (var k in o.headers) headers[k] = o.headers[k];
  return fetch(url, { method: o.method || "GET", headers: headers, body: o.body }).then(function (r) {
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
      return String((JSON.parse(body).meta || {}).name || "").trim();
    })
    .catch(function () {
      return "";
    });
}

/** Search results list `<a href=/dizi|film/slug class="card card-movie"> … <h3 class="title">Name</h3></a>`. */
function pickPage(html, title) {
  var re = /<a href="(https:\/\/diziasia\.com\/(?:dizi|film)\/[^"?#]+)"[^>]*class="[^"]*card[^"]*"([\s\S]*?)<\/a>/g;
  var m;
  var fallback = null;
  while ((m = re.exec(html)) !== null) {
    if (/-sezon-\d+-bolum/.test(m[1])) continue;
    var name = first(/<h3[^>]*>([\s\S]*?)<\/h3>/, m[2]);
    name = String(name || "").replace(/<[^>]*>/g, "").replace(/\s+/g, " ").trim();
    if (!fallback) fallback = m[1];
    if (name.toLowerCase() === String(title).toLowerCase()) return m[1];
  }
  return fallback;
}
function okRuQuality(label) {
  return { mobile: "240p", lowest: "240p", low: "360p", sd: "480p", hd: "720p", full: "1080p" }[label] || "";
}

var VK_Q = { 144: "144p", 240: "240p", 360: "360p", 480: "480p", 720: "720p", 1080: "1080p", 1440: "1440p", 2160: "2160p" };

function streamsFromOkRu(embed) {
  return text(embed, { referer: BASE + "/" })
    .then(function (body) {
      var arr = first(/"videos"\s*:\s*(\[[\s\S]*?])/, String(body).replace(/&quot;/g, "\""));
      if (!arr) return [];
      var videos = JSON.parse(arr);
      var out = [];
      for (var i = 0; i < videos.length; i++) {
        var v = videos[i] || {};
        if (!/^https?:/.test(v.url || "")) continue;
        var q = okRuQuality(v.name);
        if (!q) continue;
        out.push({
          name: "diziasia",
          title: "diziasia - " + q + " [ok.ru]",
          url: v.url,
          quality: q,
          headers: { Referer: "https://ok.ru/" },
          subtitles: []
        });
      }
      return out;
    })
    .catch(function () {
      return [];
    });
}

function streamsFromVk(embed) {
  return text(embed.replace("vkvideo.ru", "vk.com"), { referer: BASE + "/" })
    .then(function (page) {
      var re = /"(hls_ondemand|hls|mp4_\d+)":"(https:[^"]+)"/g;
      var m;
      var out = [];
      while ((m = re.exec(page)) !== null) {
        var url = m[2].replace(/\\\//g, "/").replace(/\\u0026/g, "&");
        var isHls = m[1].indexOf("hls") === 0;
        var q = isHls ? "auto" : VK_Q[m[1].replace("mp4_", "")] || "";
        out.push({
          name: "diziasia",
          title: "diziasia - " + q + " [VK]",
          url: url,
          quality: q || "auto",
          headers: { Referer: "https://vk.com/" },
          subtitles: []
        });
      }
      return out;
    })
    .catch(function () {
      return [];
    });
}
function streamsFromVidmoly(embed) {
  return text(embed, { referer: BASE + "/" })
    .then(function (page) {
      var file = first(/sources:\s*\[\s*\{\s*file:\s*['"]([^'"]+)['"]/, page);
      if (!file || !/^https?:/.test(file)) return [];
      var q = first(/label:\s*"(\d{3,4}p)/, page) || "auto";
      return [
        {
          name: "diziasia",
          title: "diziasia - " + q + " [vidmoly]",
          url: file,
          quality: q,
          headers: { Referer: embed },
          subtitles: []
        }
      ];
    })
    .catch(function () {
      return [];
    });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var title = "";
  var series = null;
  var episodeUrl = null;

  return titleOf(tmdbId, mediaType)
    .then(function (name) {
      if (!name) return null;
      title = name;
      return text(BASE + "/arama/" + encodeURIComponent(name));
    })
    .then(function (search) {
      if (!search) return null;
      series = pickPage(search, title);
      if (!series) return null;
      return text(series, { referer: BASE + "/" });
    })
    .then(function (detail) {
      if (!detail) return null;
      var re = /href="(https:\/\/diziasia\.com\/dizi\/[^"?#]+?-(\d+)-sezon-(\d+)-bolum)"/g;
      var m;
      var list = [];
      while ((m = re.exec(detail)) !== null) {
        list.push({ url: m[1], season: parseInt(m[2], 10), ep: parseInt(m[3], 10) });
      }
      // Movie pages carry the stream buttons themselves; series pages need the episode route.
      episodeUrl = list.length ? list[0].url : series;
      for (var i = 0; i < list.length; i++) {
        if (list[i].season === want && list[i].ep === wantEp) {
          episodeUrl = list[i].url;
          break;
        }
      }
      return text(episodeUrl, { referer: series });
    })
    .then(function (page) {
      if (!page) return [];
      var ids = [];
      var re = /class="[^"]*\bbtn-stream\b[^"]*"[^>]*data-id="(\d+)"/g;
      var m;
      while ((m = re.exec(page)) !== null) ids.push(m[1]);
      if (!ids.length) {
        var re2 = /data-id="(\d+)"[^>]*class="[^"]*\bbtn-stream\b/g;
        while ((m = re2.exec(page)) !== null) ids.push(m[1]);
      }
      return ids.reduce(function (chain, id) {
        return chain.then(function (acc) {
          return text(BASE + "/ajax/embed", {
            method: "POST",
            body: "id=" + encodeURIComponent(id),
            referer: episodeUrl,
            headers: { Origin: BASE, "Content-Type": "application/x-www-form-urlencoded" }
          })
            .then(function (html) {
              var src = first(/<iframe[^>]+data-src="([^"]+)"/, html) || first(/<iframe[^>]+src="([^"]+)"/, html);
              if (!src) return null;
              var embed = abs(BASE, src);
              return /ok\.ru|odnoklassniki/.test(embed)
                ? streamsFromOkRu(embed)
                : /vk\.com\/video_ext|vkvideo\.ru/.test(embed)
                  ? streamsFromVk(embed)
                  : /vidmoly\./.test(embed)
                    ? streamsFromVidmoly(embed)
                    : [];
            })
            .then(function (rows) {
              return acc.concat(rows || []);
            })
            .catch(function () {
              return acc;
            });
        });
      }, Promise.resolve([]));
    })
    .then(function (rows) {
      var seen = {};
      return (rows || []).filter(function (r) {
        if (!r || seen[r.url]) return false;
        seen[r.url] = 1;
        return true;
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][diziasia] " + (e && e.message));
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