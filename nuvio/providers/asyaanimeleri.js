// @provider {"id":"asyaanimeleri","name":"AsyaAnimeleri","types":["tv"],"domain":"asyaanimeleri.top","formats":["m3u8"],"description":"AsyaAnimeleri anime kaynaklari (rumble, vidmoly HLS).","sample":"tt33501934:tv:1:1"}
/**
 * asyaanimeleri — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> site search -> series -> episode -> mirror iframes -> rumble/vidmoly HLS.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://asyaanimeleri.top";
var B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

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
  if (href.indexOf("//") === 0) return "https:" + href;
  if (href.charAt(0) === "/") return origin ? origin[1] + href : href;
  return base.replace(/[^/]*$/, "") + href;
}

// Nuvio's QuickJS sandbox ships no base64 helper, so decode by hand.
// Non-alphabet chars are skipped, so unpadded or padded payloads both work.
function unbase64(s) {
  var src = String(s || "").replace(/[^A-Za-z0-9+/]/g, "");
  var out = "";
  var buf = 0;
  var bits = 0;
  for (var i = 0; i < src.length; i++) {
    var c = B64.indexOf(src.charAt(i));
    if (c < 0) continue;
    buf = (buf << 6) | c;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out += String.fromCharCode((buf >> bits) & 0xff);
      buf &= (1 << bits) - 1;
    }
  }
  return out;
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

function norm(s) {
  return String(s || "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
}

function seriesLink(search, title) {
  var re = /href="([^"]*\/series\/[^"?]+)"[^>]*title="([^"]*)"/g;
  var want = norm(title);
  var fallback = null;
  var m;
  while ((m = re.exec(search)) !== null) {
    if (!fallback) fallback = m[1];
    if (want && norm(m[2]) === want) return m[1];
  }
  return fallback || first(/href="([^"]*\/series\/[^"?]+)"/, search);
}

function episodes(detail, want, wantEp) {
  var links = [];
  var seen = {};
  var re = /href="([^"]*-bolum[^"]*)"/g;
  var m;
  while ((m = re.exec(detail || "")) !== null) {
    var l = abs(BASE, m[1]);
    if (l && !seen[l]) {
      seen[l] = 1;
      links.push(l);
    }
  }
  var exact = null;
  var byNum = null;
  for (var i = 0; i < links.length; i++) {
    if (parseInt(first(/-(\d+)-bolum/, links[i]), 10) !== wantEp) continue;
    if (!byNum) byNum = links[i];
    var sezon = parseInt(first(/-(\d+)-sezon-/, links[i]), 10) || 0;
    if (!exact && (!sezon || sezon === want)) exact = links[i];
  }
  var out = [];
  var take = [exact, byNum].concat(links);
  for (var j = 0; j < take.length && out.length < 3; j++) {
    if (take[j] && out.indexOf(take[j]) === -1) out.push(take[j]);
  }
  return out;
}

function row(host, url, hls, headers) {
  return {
    name: "asyaanimeleri",
    title: "AsyaAnimeleri - " + host + (hls ? " [HLS]" : " [MP4]"),
    url: url,
    quality: hls ? "1080p" : "720p",
    headers: headers,
    subtitles: []
  };
}

// Mirrors are picked per episode; only hosts whose player HTML carries a real
// media URL are kept, never a bare embed page.
function streamFor(embed, episodeUrl) {
  var host = (first(/^https?:\/\/([^/]+)/, embed) || "").toLowerCase();
  if (/rumble\.com\/embed\//.test(embed)) {
    return text(embed, { referer: episodeUrl }).then(function (body) {
      var url = first(/"hls"\s*:\s*\{\s*"url"\s*:\s*"([^"]+)"/, body);
      return url ? [row("rumble", url.replace(/\\\//g, "/"), true, { Referer: "https://rumble.com/" })] : [];
    });
  }
  if (/vidmoly\./.test(host)) {
    return text(embed, { referer: episodeUrl }).then(function (body) {
      var url = first(/sources\s*:\s*\[\s*\{[^}]*?file\s*:\s*["']([^"']+)/, body);
      if (!url) return [];
      url = url.replace(/\\\//g, "/");
      return [row("vidmoly", url, /\.m3u8/i.test(url), { Referer: embed })];
    });
  }
  if (/\.(m3u8|mp4)(\?|$)/i.test(embed)) return Promise.resolve([row("direct", embed, /\.m3u8/i.test(embed), {})]);
  return Promise.resolve([]);
}

function mirrors(page, episodeUrl) {
  var embeds = [];
  var seen = {};
  var re = /<iframe[^>]+src="([^"]+)"/gi;
  var m;
  while ((m = re.exec(page)) !== null) embeds.push(m[1]);
  var optRe = /<option[^>]+value="([^"]+)"/gi;
  while ((m = optRe.exec(page)) !== null) {
    var decoded = unbase64(m[1]);
    if (decoded.indexOf("<iframe") === -1) continue;
    var srcRe = /src=["']([^"']+)/gi;
    var s;
    while ((s = srcRe.exec(decoded)) !== null) embeds.push(s[1]);
  }
  var jobs = [];
  for (var i = 0; i < embeds.length; i++) {
    var url = abs(episodeUrl, embeds[i]);
    if (!url || seen[url]) continue;
    seen[url] = 1;
    jobs.push(streamFor(url, episodeUrl).catch(function () { return []; }));
  }
  return Promise.all(jobs).then(function (lists) {
    var out = [];
    var have = {};
    for (var j = 0; j < lists.length; j++) {
      for (var k = 0; k < lists[j].length; k++) {
        var r = lists[j][k];
        if (!have[r.url]) {
          have[r.url] = 1;
          out.push(r);
        }
      }
    }
    return out;
  });
}

function tryEach(list, i) {
  if (i >= list.length) return Promise.resolve([]);
  return text(list[i])
    .then(function (page) {
      return mirrors(page, list[i]);
    })
    .then(function (rows) {
      return rows.length ? rows : tryEach(list, i + 1);
    })
    .catch(function () {
      return tryEach(list, i + 1);
    });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;

  var title = "";
  return titleOf(tmdbId, mediaType)
    .then(function (t) {
      title = t;
      if (!title) return null;
      return text(BASE + "/?s=" + encodeURIComponent(title));
    })
    .then(function (search) {
      if (!search) return null;
      var link = seriesLink(search, title);
      if (!link) return null;
      return text(abs(BASE, link));
    })
    .then(function (detail) {
      if (!detail) return [];
      var cands = episodes(detail, want, wantEp);
      if (!cands.length) return [];
      return tryEach(cands, 0);
    })
    .catch(function (e) {
      console.log("[bb-nuvio][asyaanimeleri] " + (e && e.message));
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