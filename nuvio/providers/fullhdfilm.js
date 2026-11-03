// @provider {"id":"fullhdfilm","name":"FullHDFilm","types":["movie"],"domain":"fullhdfilmizlebox.vip","formats":["m3u8"],"description":"FullHDFilm Türkçe dublaj/altyazılı film arşivi (hdload oynatıcısı).","sample":"tt7286456:movie:1:1"}
/**
 * fullhdfilm — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /api/search (Laravel JSON) -> film page -> Alpine
 * data-video-player payload -> base64 iframe template -> hdload jwplayer setup
 * -> HLS master (quality read from RESOLUTION) + VTT tracks.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://fullhdfilmizlebox.vip";
var B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
  if (o.accept) headers.Accept = o.accept;
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

function b64(value) {
  var out = "", buf = 0, bits = 0, s = String(value || "").replace(/[^A-Za-z0-9+/]/g, "");
  for (var i = 0; i < s.length; i++) {
    buf = (buf << 6) | B64.indexOf(s.charAt(i));
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out += String.fromCharCode((buf >> bits) & 255);
    }
  }
  return out;
}

function flat(html) {
  return String(html || "").replace(/\\u0022/g, '"').replace(/\\\//g, "/").replace(/\\/g, "");
}

function normTitle(value) {
  return String(value || "")
    .toLowerCase()
    .replace(/\s*\bizle\b.*$/, "")
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

function findFilm(title, year) {
  return text(BASE + "/api/search?q=" + encodeURIComponent(title), { accept: "application/json" })
    .then(function (body) {
      var rows;
      try {
        rows = JSON.parse(body).data;
      } catch (e) {
        rows = null;
      }
      if (!rows || !rows.length) return null;
      var want = normTitle(title);
      var best = null, bestScore = -1;
      for (var i = 0; i < rows.length; i++) {
        var row = rows[i] || {};
        if (row.contentableType !== "App\\Models\\Movie") continue;
        var slug = String(row.slug || "").replace(/^\/+|\/+$/g, "");
        if (!slug) continue;
        var got = normTitle(row.title);
        var score = got === want ? 100 : got.indexOf(want) >= 0 || want.indexOf(got) >= 0 ? 60 : 0;
        if (year && String(row.releaseYear || "").slice(0, 4) === year) score += 40;
        if (score > bestScore) {
          bestScore = score;
          best = BASE + "/" + slug + "/";
        }
      }
      return best;
    })
    .catch(function () {
      return null;
    });
}

function playerRows(page) {
  var at = page.indexOf("data-video-player");
  if (at < 0) return [];
  var from = page.indexOf("JSON.parse('", at);
  if (from < 0) return [];
  from += 12;
  var end = page.indexOf("')", from);
  if (end <= from) return [];
  var sources;
  try {
    sources = JSON.parse(page.slice(from, end).replace(/\\u0022/g, '"'));
  } catch (e) {
    return [];
  }
  var rows = [];
  for (var language in sources) {
    var list = sources[language];
    if (!list || !list.length) continue;
    for (var i = 0; i < list.length; i++) {
      var row = list[i] || {};
      if (!row.template) continue;
      row.language = language;
      rows.push(row);
    }
  }
  return rows;
}

function languageOf(language) {
  if (language === "dual") return "Türkçe Dublaj & Altyazı";
  if (language === "tr") return "Türkçe Dublaj";
  if (language === "en") return "Türkçe Altyazı";
  return String(language || "").toUpperCase();
}

function subtitleLang(label) {
  var text = String(label || "");
  if (/rkçe|ürk|Forced/i.test(text)) return "tr";
  if (/ngiliz|English/i.test(text)) return "en";
  return text.trim() || "tr";
}

function subtitlesIn(flatHtml) {
  var subs = [], m, re = /"file":"([^"]+?\.(?:vtt|srt)[^"]*)"[^}]*?"label":"([^"]*)"/g;
  while ((m = re.exec(flatHtml)) !== null) {
    var lang = subtitleLang(m[2]);
    subs.push({ url: m[1], language: lang, name: lang.toUpperCase() + " [fullhdfilm]" });
  }
  return subs.slice(0, 4);
}

/** Highest variant height in the master playlist; the site only labels everything "HD". */
function qualityOf(stream, fallback, referer) {
  return text(stream, { referer: referer })
    .then(function (playlist) {
      var height = 0, m, re = /RESOLUTION=\d+x(\d+)/g;
      while ((m = re.exec(playlist)) !== null) height = Math.max(height, parseInt(m[1], 10) || 0);
      if (height >= 1000) return "1080p";
      if (height >= 700) return "720p";
      if (height >= 500) return "480p";
      if (height >= 350) return "360p";
      return fallback;
    })
    .catch(function () {
      return fallback;
    });
}

function resolveRow(row, pageUrl) {
  var tpl = b64(row.template);
  var src = first(/(?:data-)?src="([^"]+)"/, tpl);
  if (!src) return Promise.resolve([]);
  src = src.replace(/\{url\}/g, row.link || "").replace(/\{slug\}/g, row.slug || row.service_slug || "");
  if (src.indexOf("//") === 0) src = "https:" + src;
  var embed = abs(pageUrl, src);
  var label = [languageOf(row.language), String(row.service_name || "").trim()].filter(Boolean).join(" ");

  return text(embed, { referer: pageUrl })
    .then(function (html) {
      var body = flat(html);
      var urls = [], m, re = /https?:\/\/[^\s"'<>\\]+?\.(?:m3u8|mp4)[^\s"'<>\\]*/g;
      while ((m = re.exec(body)) !== null) if (urls.indexOf(m[0]) < 0) urls.push(m[0]);
      var subs = subtitlesIn(body);
      return Promise.all(urls.map(function (url) {
        return qualityOf(url, String(row.quality || "HD").trim(), embed).then(function (quality) {
          return {
            name: "fullhdfilm",
            title: "fullhdfilm - " + label + " " + quality,
            url: url,
            quality: quality,
            headers: { Referer: embed },
            subtitles: subs
          };
        });
      }));
    })
    .catch(function () {
      return [];
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
      var rows = playerRows(page);
      if (!rows.length) return [];
      return Promise.all(rows.map(function (row) {
        return resolveRow(row, pageUrl);
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
      console.log("[bb-nuvio][fullhdfilm] " + (e && e.message));
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