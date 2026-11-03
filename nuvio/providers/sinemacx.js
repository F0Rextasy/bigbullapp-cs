// @provider {"id":"sinemacx","name":"SinemaCX","types":["movie"],"domain":"sinemacc.com","formats":["m3u8"],"sample":"tt0133093:movie:1:1","description":"SinemaCX Türkçe dublaj ve altyazılı film arşivi (HLS + altyazı)."}
/**
 * sinemacx — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> ajax search -> /arama/?fsid= -> film page -> part_button rel (base64 iframe)
 *       -> player/index.php?do=getVideo -> securedLink m3u8 + playerjsSubtitle tracks.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
var BASE = "https://sinemacc.com";

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

var B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

/** Sandbox has no atob; decode enough for URLs. */
function b64(value) {
  var raw = String(value || "").replace(/[^A-Za-z0-9+/=]/g, "");
  var out = "";
  var buf = 0;
  var bits = 0;
  for (var i = 0; i < raw.length; i++) {
    var c = raw.charAt(i);
    if (c === "=") break;
    var v = B64.indexOf(c);
    if (v < 0) continue;
    buf = (buf << 6) | v;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out += String.fromCharCode((buf >> bits) & 0xff);
    }
  }
  return out;
}

/** Turkish-aware title key so "The Matrix" matches the site's "Matrix". */
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

function pick(cards, wanted) {
  var w = norm(wanted);
  if (!w) return null;
  var subset = null;
  for (var i = 0; i < cards.length; i++) {
    var n = norm(cards[i].title);
    if (!n) continue;
    if (n === w) return cards[i];
    if (!subset && (w.indexOf(n) !== -1 || n.indexOf(w) !== -1)) subset = cards[i];
  }
  return subset;
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

function searchCards(title) {
  return text(BASE + "/arama/", {
    method: "POST",
    headers: { "X-Requested-With": "XMLHttpRequest", "Content-Type": "application/x-www-form-urlencoded" },
    referer: BASE + "/",
    body: "action=ajax_search&arama_kelime=" + encodeURIComponent(title)
  }).then(function (html) {
    var cards = [];
    var re = /<a class="arama_s[^"]*"\s+href="([^"]+)"[\s\S]*?<\/a>/g;
    var m;
    while ((m = re.exec(html)) !== null) {
      var cardTitle = first(/class="title">([^<]*)</, m[0]);
      if (!cardTitle) continue;
      cards.push({ url: m[1], title: cardTitle.trim(), year: first(/class="year">(\d{4})</, m[0]) });
    }
    return cards;
  });
}

function heightsOf(master) {
  var heights = [];
  var re = /RESOLUTION=\d+x(\d+)/gi;
  var m;
  while ((m = re.exec(String(master || ""))) !== null) heights.push(parseInt(m[1], 10));
  return heights;
}

function qualityOf(master) {
  var heights = heightsOf(master);
  var max = 0;
  for (var i = 0; i < heights.length; i++) if (heights[i] > max) max = heights[i];
  if (!max) return "auto";
  if (max >= 2160) return "2160p";
  if (max >= 1080) return "1080p";
  if (max >= 720) return "720p";
  if (max >= 480) return "480p";
  return "360p";
}

function parseSubtitles(playerHtml, playerUrl) {
  var value = first(/playerjsSubtitle\s*=\s*"([\s\S]*?)";/, playerHtml);
  if (!value) return [];
  var subs = [];
  var re = /\[([^\]]+)\](https?:\/\/[^",\s]+)/g;
  var m;
  while ((m = re.exec(value)) !== null) {
    var language = m[1].trim() || "Altyazi";
    subs.push({ url: m[2], language: language, name: language + " [SinemaCX]", headers: { Referer: playerUrl } });
  }
  return subs;
}

/** One part_button embed -> one playable HLS row. */
function resolvePart(part, subsOut) {
  var origin = first(/^(https?:\/\/[^/]+)/, part.embed);
  var id = first(/([^/?]+)\/?$/, String(part.embed).split("?")[0].replace(/\/$/, ""));
  if (!origin || !id) return Promise.resolve(null);

  return text(part.embed, { referer: BASE + "/" })
    .then(function (playerHtml) {
      if (subsOut.length === 0) subsOut.push.apply(subsOut, parseSubtitles(playerHtml, part.embed));
    })
    .catch(function () {})
    .then(function () {
      return text(origin + "/player/index.php?data=" + id + "&do=getVideo", {
        method: "POST",
        headers: { "X-Requested-With": "XMLHttpRequest", "Content-Type": "application/x-www-form-urlencoded" },
        referer: part.embed,
        body: "hash=" + encodeURIComponent(id) + "&r=" + encodeURIComponent(BASE + "/")
      });
    })
    .then(function (body) {
      var panel = JSON.parse(body);
      var url = String(panel.securedLink || "");
      if (!/^https?:/.test(url)) return null;
      var type = panel.hls || url.indexOf(".m3u8") !== -1 ? "m3u8" : "mp4";
      return text(url, { referer: origin + "/" })
        .then(function (master) {
          return {
            name: "SinemaCX",
            title: "SinemaCX - " + qualityOf(master) + " [" + part.label + "]",
            url: url,
            quality: qualityOf(master),
            headers: { Referer: origin + "/", "User-Agent": UA },
            subtitles: subsOut.slice()
          };
        })
        .catch(function () {
          return {
            name: "SinemaCX",
            title: "SinemaCX - " + (type === "m3u8" ? "auto [HLS]" : "auto [MP4]") + " [" + part.label + "]",
            url: url,
            quality: "auto",
            headers: { Referer: origin + "/", "User-Agent": UA },
            subtitles: subsOut.slice()
          };
        });
    });
}

function getStreams(tmdbId, mediaType, season, episode) {
  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return null;
      return searchCards(title).then(function (cards) {
        return pick(cards, title);
      });
    })
    .then(function (card) {
      if (!card) return null;
      return text(card.url, { referer: BASE + "/" });
    })
    .then(function (html) {
      if (!html) return [];
      var parts = [];
      var seen = {};
      var re = /<button rel="([^"]+)"[^>]*part_button[^>]*>([\s\S]*?)<\/button>/g;
      var m;
      while ((m = re.exec(html)) !== null) {
        var embed = b64(m[1]);
        if (!/^https?:/.test(embed) || /youtube|youtu\.be/i.test(embed)) continue;
        var label = m[2].replace(/<[^>]*>/g, "").trim() || "Kaynak";
        if (seen[embed]) continue;
        seen[embed] = true;
        parts.push({ embed: embed, label: label });
      }
      if (!parts.length) return [];
      var subs = [];
      return Promise.all(
        parts.map(function (part) {
          return resolvePart(part, subs).catch(function () {
            return null;
          });
        })
      ).then(function (rows) {
        var out = [];
        for (var i = 0; i < rows.length; i++) {
          if (!rows[i]) continue;
          rows[i].subtitles = subs.slice();
          out.push(rows[i]);
        }
        return out;
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][sinemacx] " + (e && e.message));
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