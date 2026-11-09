// @provider {"id":"dizilla", "name":"Dizilla", "types":["tv"], "domain":"dizilla.la", "formats":["m3u8"], "sample":"tt27497448:tv:1:1", "description":"Dizilla yabanci dizi arsivi (HLS + Turkce altyazi).", "version":"1.0.15"}
/**
 * dizilla — Nuvio provider (bb-nuvio).
 * Next.js site: series metadata + episode list live in the JSON-LD blocks, the
 * episode page carries its player iframes in the RSC payload ("sources").
 * Flow: Cinemeta title -> /ara?q= -> JSON-LD episodes -> "sources" iframes ->
 *       player NP_CONFIG -> master m3u8 + VTT subtitles. The player only accepts a
 *       dizilla.now Referer (PLAYER_REFERER); the .la host is answered with 404 "7".
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://dizilla.la";
/** pichive's referer allowlist accepts only this origin; the site itself moved to .la. */
var PLAYER_REFERER = "https://dizilla.now/";

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

function abs(base, href) {
  if (!href) return null;
  href = String(href).replace(/&amp;/g, "&").trim();
  if (/^https?:/i.test(href)) return href;
  var origin = /^(https?:\/\/[^/]+)/.exec(base);
  if (href.charAt(0) === "/") return origin ? origin[1] + href : href;
  return base.replace(/[^/]*$/, "") + href;
}

function titleOf(id, mediaType) {
  if (/^__alt__:/.test(String(id))) return Promise.resolve(decodeURIComponent(String(id).slice(8)));
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
  return String(value || "").toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
}

/** JSON sits inside a JS string / script tag, so cut a balanced {...} by hand. */
function sliceBalanced(body, open) {
  var depth = 0;
  var inString = false;
  var escaped = false;
  for (var i = open; i < body.length; i++) {
    var c = body.charAt(i);
    if (inString) {
      if (escaped) escaped = false;
      else if (c === "\\") escaped = true;
      else if (c === '"') inString = false;
      continue;
    }
    if (c === '"') inString = true;
    else if (c === "{" || c === "[") depth++;
    else if (c === "}" || c === "]") {
      depth--;
      if (depth === 0) return body.slice(open, i + 1);
    }
  }
  return null;
}

function jsonAfter(body, marker, offset) {
  var at = body.indexOf(marker, offset || 0);
  if (at < 0) return null;
  var open = body.indexOf("{", at + marker.length - 1);
  var slice = open < 0 ? null : sliceBalanced(body, open);
  if (!slice) return null;
  try {
    return JSON.parse(slice);
  } catch (e) {
    return null;
  }
}

/** `<a href="/dizi/<slug>" class="pcard" aria-label="Title, 2010, IMDb 8.1">`. */
function cards(html, base) {
  var out = [];
  var re = /<a[^>]*class="[^"]*pcard[^"]*"[^>]*>/g;
  var tag;
  while ((tag = re.exec(html)) !== null) {
    var href = first(/href="([^"]*)"/, tag[0]);
    var label = first(/aria-label="([^"]*)"/, tag[0]);
    if (!href || !label) continue;
    out.push({ url: abs(base, href), title: label.split(",")[0].trim() });
  }
  return out;
}

function pick(list, title) {
  var want = norm(title);
  var best = null;
  var bestScore = 0;
  for (var i = 0; i < list.length; i++) {
    var have = norm(list[i].title);
    var score = have === want ? 100 : have.indexOf(want + " ") === 0 ? 80 : 0;
    if (score > bestScore) {
      bestScore = score;
      best = list[i];
    }
    if (bestScore === 100) break;
  }
  return bestScore >= 80 ? best : null;
}

/** Every TVSeries JSON-LD block; episode lists only live in the richest one. */
function seriesObjects(html) {
  var out = [];
  var re = /<script[^>]+type="application\/ld\+json"[^>]*>([\s\S]*?)<\/script>/g;
  var m;
  while ((m = re.exec(html)) !== null) {
    var parsed;
    try {
      parsed = JSON.parse(m[1]);
    } catch (e) {
      continue;
    }
    if (parsed && parsed["@type"] === "TVSeries") out.push(parsed);
  }
  return out;
}

function episodesOf(obj) {
  var seasons = obj.containsSeason;
  if (!seasons) return [];
  if (!Array.isArray(seasons)) seasons = [seasons];
  var out = [];
  for (var i = 0; i < seasons.length; i++) {
    var list = seasons[i].episode || [];
    var season = parseInt(seasons[i].seasonNumber, 10) || i + 1;
    for (var j = 0; j < list.length; j++) {
      if (!list[j] || !list[j].url) continue;
      out.push({
        url: list[j].url,
        season: season,
        episode: parseInt(list[j].episodeNumber, 10) || j + 1
      });
    }
  }
  return out;
}

var LANGS = { "türkçe": "tr", "turkce": "tr", "i̇ngilizce": "en", "ingilizce": "en", "english": "en", "arapça": "ar", "almanca": "de", "ispanyolca": "es" };

var K256 = [
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
];
function sha256Words(str) {
  var msg = [];
  for (var i = 0; i < str.length; i++) msg.push(str.charCodeAt(i) & 0xff);
  var len = msg.length;
  msg.push(0x80);
  while (msg.length % 64 !== 56) msg.push(0);
  var bits = len * 8;
  msg.push(0, 0, 0, 0, (bits >>> 24) & 0xff, (bits >>> 16) & 0xff, (bits >>> 8) & 0xff, bits & 0xff);
  var h0 = 0x6a09e667, h1 = 0xbb67ae85, h2 = 0x3c6ef372, h3 = 0xa54ff53a;
  var h4 = 0x510e527f, h5 = 0x9b05688c, h6 = 0x1f83d9ab, h7 = 0x5be0cd19;
  var w = [];
  for (var off = 0; off < msg.length; off += 64) {
    for (var j = 0; j < 16; j++) w[j] = (msg[off + 4 * j] << 24) | (msg[off + 4 * j + 1] << 16) | (msg[off + 4 * j + 2] << 8) | msg[off + 4 * j + 3];
    for (var t = 16; t < 64; t++) {
      var x = w[t - 15], y = w[t - 2];
      var s0 = ((x >>> 7) | (x << 25)) ^ ((x >>> 18) | (x << 14)) ^ (x >>> 3);
      var s1 = ((y >>> 17) | (y << 15)) ^ ((y >>> 19) | (y << 13)) ^ (y >>> 10);
      w[t] = (w[t - 16] + s0 + w[t - 7] + s1) | 0;
    }
    var a = h0, b = h1, c = h2, d = h3, e = h4, f = h5, g = h6, h = h7;
    for (var r = 0; r < 64; r++) {
      var S1 = ((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7));
      var ch = (e & f) ^ (~e & g);
      var t1 = (h + S1 + ch + K256[r] + w[r]) | 0;
      var S0 = ((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10));
      var maj = (a & b) ^ (a & c) ^ (b & c);
      var t2 = (S0 + maj) | 0;
      h = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0;
    }
    h0 = (h0 + a) | 0; h1 = (h1 + b) | 0; h2 = (h2 + c) | 0; h3 = (h3 + d) | 0;
    h4 = (h4 + e) | 0; h5 = (h5 + f) | 0; h6 = (h6 + g) | 0; h7 = (h7 + h) | 0;
  }
  return [h0 >>> 0, h1 >>> 0, h2 >>> 0, h3 >>> 0, h4 >>> 0, h5 >>> 0, h6 >>> 0, h7 >>> 0];
}
function pichiveSign(html, url) {
  var u = String(url || "");
  if ((u.indexOf("/nm.php") === -1 && u.indexOf("/nsub.php") === -1) || u.indexOf("&p=") !== -1) return u;
  var gm = /"gate"\s*:\s*\{\s*"n"\s*:\s*"([^"]+)"(?:\s*,\s*"b"\s*:\s*(\d+))?/.exec(String(html || ""));
  if (!gm) return u;
  var n = gm[1], bits = parseInt(gm[2], 10) || 12;
  var fn = /window\.NP_GATE\s*=\s*function\(\s*(\w+)\s*,\s*(\w+)\s*\)\s*\{([\s\S]*?)\};/.exec(String(html || ""));
  if (!fn) {
    for (var i = 0; i < 5000000; i++) if ((sha256Words(n + ":" + i)[0] >>> (32 - bits)) === 0) return u + "&p=" + i;
    return u;
  }
  var nVar = fn[1], body = fn[3];
  var cm = /var\s+(\w+)\s*=\s*String\.fromCharCode\(([\d,\s]+)\)/.exec(body);
  var nm = /var\s+(\w+)\s*=\s*(?:\w+\.toString\((\d*)\)|String\(\w+\))/.exec(body);
  var im = /\(\s*\w+\(([^)]+)\)\[(\d+)\]\s*(>>>|&)\s*(\d+)\s*\)\s*===?\s*0/.exec(body);
  if (!cm || !nm || !im) return u;
  var saltVar = cm[1], salt = cm[2].split(",").map(function (x) { return String.fromCharCode(parseInt(x.trim(), 10)); }).join("");
  var nonceVar = nm[1], radix = parseInt(nm[2], 10) || 10, parts = im[1].split("+").map(function (s) { return s.trim(); });
  var wordIdx = parseInt(im[2], 10) || 0, op = im[3], rhs = parseInt(im[4], 10) >>> 0;
  for (var k = 0; k < 5000000; k++) {
    var nonce = k.toString(radix), msg = "";
    for (var p = 0; p < parts.length; p++) {
      var tok = parts[p];
      if (tok === nVar) msg += n;
      else if (tok === saltVar) msg += salt;
      else if (tok === nonceVar) msg += nonce;
      else msg += tok.replace(/^["']|["']$/g, "");
    }
    var w = sha256Words(msg)[wordIdx] >>> 0;
    if ((op === ">>>" ? (w >>> rhs) : ((w & rhs) >>> 0)) === 0) return u + "&p=" + nonce;
  }
  return u;
}

function langOf(label) {
  var key = String(label || "").toLowerCase().trim();
  return LANGS[key] || (key.indexOf("ingiliz") !== -1 ? "en" : "tr");
}

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var episodeUrl = null;
  var players = [];

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return null;
      return text(BASE + "/ara?q=" + encodeURIComponent(title) + "&page=1").then(function (html) {
        return pick(cards(html, BASE), title);
      });
    })
    .then(function (series) {
      if (!series) return null;
      return text(series.url).then(function (detail) {
        var objects = seriesObjects(detail);
        if (!objects.length) return null;
        var list = [];
        for (var i = 0; i < objects.length; i++) {
          var found = episodesOf(objects[i]);
          if (found.length > list.length) list = found;
        }
        for (var j = 0; j < list.length; j++) {
          if (list[j].season === want && list[j].episode === wantEp) {
            episodeUrl = list[j].url;
            break;
          }
        }
        return episodeUrl;
      });
    })
    .then(function (url) {
      if (!url) return [];
      return text(url).then(function (html) {
        var body = html.replace(/\\"/g, '"');
        var at = body.indexOf('"sources":[');
        if (at < 0) return [];
        var open = body.indexOf("[", at);
        var slice = open < 0 ? null : sliceBalanced(body, open);
        var seen = {};
        if (!slice) return [];
        var list;
        try {
          list = JSON.parse(slice);
        } catch (e) {
          return [];
        }
        for (var i = 0; i < list.length; i++) {
          var item = list[i];
          if (!item || item.trailer || !/^https?:/i.test(String(item.src || ""))) continue;
          var key = item.src;
          if (seen[key]) {
            seen[key].lang = seen[key].lang && seen[key].lang !== (item.langLabel || item.lang) ? seen[key].lang + " / " + (item.langLabel || item.lang) : seen[key].lang;
            continue;
          }
          seen[key] = {
            url: item.src,
            label: String(item.name || "").trim(),
            quality: String(item.quality || "").trim(),
            lang: String(item.langLabel || item.lang || "").trim()
          };
          players.push(seen[key]);
        }
        return url;
      });
    })
    .then(function () {
      return players.reduce(function (chain, player) {
        return chain.then(function (rows) {
          return text(player.url, { referer: PLAYER_REFERER })
            .then(function (html) {
              var config = jsonAfter(html, "NP_CONFIG");
              if (!config) return rows;
              var masters = config.sources || [];
              var height = parseInt(first(/(\d{3,4})/, player.quality) || "", 10) || 0;
              var name = ["dizilla", player.lang, player.label].filter(function (part) { return part; }).join(" ");
              for (var i = 0; i < masters.length; i++) {
                var rawStream = masters[i] && masters[i].master;
                if (!rawStream || !/^https?:/i.test(rawStream)) continue;
                var stream = pichiveSign(html, rawStream);
                if (rows.some(function (row) { return row.url === stream; })) continue;
                var subs = [];
                var tracks = config.subtitles || [];
                for (var j = 0; j < tracks.length; j++) {
                  if (!tracks[j] || !/^https?:/i.test(String(tracks[j].url || ""))) continue;
                  subs.push({
                    url: pichiveSign(html, tracks[j].url),
                    language: langOf(tracks[j].label),
                    name: String(tracks[j].label || "Türkçe") + " [dizilla]"
                  });
                }
                rows.push({
                  name: "dizilla",
                  title: name + (height ? " - " + height + "p" : " - HLS"),
                  url: stream,
                  quality: height ? height + "p" : "auto",
                  headers: { Referer: player.url, "User-Agent": UA },
                  subtitles: subs.slice(0, 6)
                });
              }
              return rows;
            })
            .catch(function () {
              return rows;
            });
        });
      }, Promise.resolve([]));
    })
    .catch(function (e) {
      console.log("[bb-nuvio][dizilla] " + (e && e.message));
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

/* Cinemeta basligi yerellestirir ("Gibi" -> "As If"); sonuc bossa Wikidata TR/EN basliklariyla yeniden dene. */
function __altTitles(raw, mediaType) {
  var h = { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36", "Accept": "application/json" };
  var api = "https://www.wikidata.org/w/api.php?format=json";
  var stmt = /^tt\d+$/.test(raw) ? "P345=" + raw : (mediaType === "movie" ? "P4947=" : "P4983=") + raw;
  return fetch(api + "&action=query&list=search&srlimit=1&srsearch=" + encodeURIComponent("haswbstatement:" + stmt), { headers: h })
    .then(function (r) { return r.ok ? r.json() : null; })
    .then(function (j) {
      var hits = (j && j.query && j.query.search) || [];
      if (!hits.length) return null;
      return fetch(api + "&action=wbgetentities&props=labels|aliases&languages=tr|en&ids=" + hits[0].title, { headers: h }).then(function (r) { return r.ok ? r.json() : null; });
    })
    .then(function (j) {
      var out = [], seen = {}, ents = (j && j.entities) || {}, k, l, a, i;
      function add(v) {
        v = String(v || "").replace(/\s*\([^)]*\)\s*$/, "").trim();
        if (v && !seen[v.toLowerCase()]) { seen[v.toLowerCase()] = 1; out.push(v); }
      }
      for (k in ents) {
        l = ents[k].labels || {}; a = ents[k].aliases || {};
        add((l.tr || {}).value); add((l.en || {}).value);
        for (i = 0; i < (a.tr || []).length && i < 2; i++) add(a.tr[i].value);
      }
      return out;
    })
    .catch(function () { return []; });
}
var __cinemetaGetStreams = module.exports.getStreams;
module.exports.getStreams = function (id, mediaType, season, episode) {
  var raw = String(id || "");
  return __cinemetaGetStreams(raw, mediaType, season, episode).then(function (streams) {
    if ((streams && streams.length) || /^__alt__:/.test(raw)) return streams || [];
    return __altTitles(raw, mediaType).then(function (titles) {
      var chain = Promise.resolve([]);
      titles.forEach(function (t) {
        chain = chain.then(function (out) {
          return out.length ? out : __cinemetaGetStreams("__alt__:" + encodeURIComponent(t), mediaType, season, episode);
        });
      });
      return chain;
    });
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
