// @provider {"id":"hdfilmcehennemi", "name":"HDFilmCehennemi", "types":["movie"], "domain":"hdfilmcehennemi.nl", "formats":["m3u8"], "sample":"tt0133093:movie:1:1", "description":"HDFilmCehennemi - Turkce dublaj/altyazili filmler; obfuscated jwplayer token -> HLS + altyazi.", "verified":true, "version":"1.0.12"}
/**
 * hdfilmcehennemi — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /search/?q= JSON -> film page -> hdfilmcehennemi.mobi embed
 *       -> split-string / packed token decode -> HLS master + jwplayer caption tracks.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://www.hdfilmcehennemi.nl";
var B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

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

/** atob is absent in the Nuvio sandbox: base64 -> byte string by hand. */
function atobJs(value) {
  var s = String(value || "").replace(/[^A-Za-z0-9+/=]/g, "");
  var out = "";
  var buf = 0;
  var bits = 0;
  for (var i = 0; i < s.length; i++) {
    var v = B64.indexOf(s.charAt(i));
    if (v < 0) continue;
    buf = (buf << 6) | v;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out += String.fromCharCode((buf >> bits) & 255);
    }
  }
  return out;
}

function origin(url) {
  var m = /^(https?:\/\/[^/]+)/.exec(String(url || ""));
  return m ? m[1] : null;
}

function host(url) {
  var m = /^https?:\/\/([^/]+)/.exec(String(url || ""));
  return m ? m[1] : "";
}

function titleOf(id, mediaType) {
  if (/^__alt__:/.test(String(id))) return Promise.resolve(decodeURIComponent(String(id).slice(8)));
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

function norm(value) {
  return String(value || "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-|-$/g, "");
}

/** The embed's token decoder (same maths as the site's rotating function). */
function decodeToken(parts, hostname) {
  var arr = parts.slice();
  var cc3 = arr.length - 2;
  if (cc3 < 8) return "";
  var key = arr.splice(8 + (cc3 % 5), 1)[0];
  var seed = arr.splice(cc3 % 7, 1)[0] + hostname;
  var body = arr.join("");
  if (key.length > 2048) body = body.split("").reverse().join("");
  var s172 = 0;
  var mix = 0;
  for (var v = 0; v < seed.length; v++) {
    var ch = seed.charCodeAt(v);
    s172 = (s172 * 37 + ch) % 241;
    mix = (mix + ((ch << 1) ^ v)) & 255;
  }
  var start = (s172 * 3 + mix) % 256;
  var step = (mix % 11) + 5;
  var prng = ((mix * 251 + s172) % 65519) + 1;
  for (var i = key.length - 1; i >= 0; i--) {
    var op = key.charAt(i);
    if (op === "7") body = atobJs(body);
    else if (op === "3") body = body.split("").reverse().join("");
    else {
      var shift = (26 - ((op.charCodeAt(0) - 96) % 26)) % 26;
      body = body.replace(/[a-zA-Z]/g, function (c) {
        var code = c.charCodeAt(0);
        var base = code <= 90 ? 65 : 97;
        return String.fromCharCode(((code - base + shift) % 26) + base);
      });
    }
  }
  if (seed.length > 4096) body = atobJs(body);
  var n = body.length;
  var perm = [];
  for (var j = n - 1; j >= 1; j--) {
    prng = (prng * 97 + 41) % 65519;
    perm[j] = prng % (j + 1);
  }
  var chars = body.split("");
  for (var s = 1; s < n; s++) {
    var from = perm[s];
    var tmp = chars[s];
    chars[s] = chars[from];
    chars[from] = tmp;
  }
  var state = start;
  var out = "";
  for (var k = 0; k < chars.length; k++) {
    var byte = chars[k].charCodeAt(0);
    state = (state * 5 + step) % 256;
    out += String.fromCharCode((byte ^ state) & 255);
    state = (state + byte) % 256;
  }
  return out;
}

function functionBody(html, fnName) {
  var start = html.indexOf("function " + fnName + "(");
  if (start < 0) return null;
  var open = html.indexOf("{", start);
  if (open < 0) return null;
  var depth = 0;
  for (var i = open; i < html.length; i++) {
    var ch = html.charAt(i);
    if (ch === "{") depth++;
    else if (ch === "}") {
      depth--;
      if (depth === 0) return html.slice(start, i + 1);
    }
  }
  return null;
}

/** Packed variant: constants are read from the matching function body, parts from the call. */
function decodePackedParts(seed, ops, parts) {
  var body = parts.join("");
  var a = 0;
  var b = 0;
  for (var i = 0; i < seed.length; i++) {
    var c = seed.charCodeAt(i);
    a = (a * 31 + c) % 251;
    b = (b ^ (c + i)) & 255;
  }
  var start = (a + b) % 256;
  var step = (a % 13) + 3;
  var prng = ((a * 256 + b) % 65521) + 1;
  for (var j = ops.length - 1; j >= 0; j--) {
    var op = ops.charAt(j);
    if (op === "b") body = atobJs(body);
    else if (op === "v") body = body.split("").reverse().join("");
    else {
      var shift = (26 - ((op.charCodeAt(0) - 64) % 26)) % 26;
      body = body.replace(/[a-zA-Z]/g, function (ch) {
        var code = ch.charCodeAt(0);
        var base = code <= 90 ? 65 : 97;
        return String.fromCharCode(((code - base + shift) % 26) + base);
      });
    }
  }
  var n = body.length;
  var perm = [];
  for (var k = n - 1; k >= 1; k--) {
    prng = (prng * 75 + 74) % 65537;
    perm[k] = prng % (k + 1);
  }
  var chars = body.split("");
  for (var s = 1; s < n; s++) {
    var other = perm[s];
    var tmp = chars[s];
    chars[s] = chars[other];
    chars[other] = tmp;
  }
  var state = start;
  var out = "";
  for (var m = 0; m < chars.length; m++) {
    var byte = chars[m].charCodeAt(0);
    state = (state + step) % 256;
    out += String.fromCharCode((byte ^ state) & 255);
    state = (state + byte) % 256;
  }
  return out;
}

/** Every candidate the embed offers; the variable wired into `sources: [{file: <var>}]` goes first. */
function candidates(html, hostname) {
  var out = [];
  var fileVar = first(/sources\s*:\s*\[\s*\{\s*file\s*:\s*([A-Za-z0-9_$]+)/, html);
  var re = /(?:([A-Za-z0-9_$]+)\s*=\s*)?[A-Za-z0-9_$]+\(\s*"((?:[^"\\]|\\.)*)"\s*\.\s*split\(\s*["']([^"'])["']\s*\)\s*\)/g;
  var m;
  while ((m = re.exec(html)) !== null) {
    try {
      var token = m[2].replace(/\\(.)/g, "$1");
      var url = decodeToken(token.split(m[3]), hostname);
      if (/^https?:/i.test(url)) {
        if (fileVar && m[1] === fileVar) out.unshift(url);
        else out.push(url);
      }
    } catch (e) {}
  }
  var reArr = /(?:([A-Za-z0-9_$]+)\s*=\s*)?([A-Za-z0-9_$]+)\(\[((?:"(?:[^"\\]|\\.)*"\s*,?\s*)+)\]\)/g;
  var ma;
  while ((ma = reArr.exec(html)) !== null) {
    try {
      var fnBody = functionBody(html, ma[2]) || html;
      var consts = /var\s+\w+\s*=\s*"((?:[^"\\]|\\.)*)";\s*var\s+\w+\s*=\s*"((?:[^"\\]|\\.)*)";/.exec(fnBody);
      if (!consts) continue;
      var parts = JSON.parse("[" + ma[3] + "]");
      var packed = decodePackedParts(consts[1], consts[2], parts);
      if (packed && /^https?:/i.test(packed)) {
        if (fileVar && ma[1] === fileVar) out.unshift(packed);
        else out.push(packed);
      }
    } catch (e) {}
  }
  return out;
}

function abs(base, href) {
  if (/^https?:/i.test(href)) return href;
  if (href.charAt(0) === "/") {
    var origin = /^(https?:\/\/[^/]+)/.exec(base);
    return origin ? origin[1] + href : href;
  }
  return base.replace(/[^/]*$/, "") + href;
}

/** Cinema-aspect masters (3840x1600) still read as their ladder rung, so label by width first. */
function label(width, height) {
  if (width >= 3400) return "2160p";
  if (width >= 1700) return "1080p";
  if (width >= 1100) return "720p";
  return height ? height + "p" : "1080p";
}

function variants(master, playlist) {
  var list = [];
  var size = null;
  var lines = String(playlist || "").split(/\r?\n/);
  for (var i = 0; i < lines.length; i++) {
    var line = lines[i].trim();
    if (line.indexOf("#EXT-X-STREAM-INF") === 0) {
      var m = /RESOLUTION=(\d+)x(\d+)/.exec(line);
      size = m ? label(parseInt(m[1], 10), parseInt(m[2], 10)) : null;
      continue;
    }
    if (!line || line.charAt(0) === "#" || !size) continue;
    list.push({ quality: size, url: abs(master, line) });
    size = null;
  }
  return list;
}

/** First candidate that really answers with an HLS playlist wins; the rest are decoys. */
function pick(list, embed, playerUrl, index, subs) {
  if (index >= list.length) return Promise.resolve(null);
  var url = list[index];
  return fetch(url, { headers: { "User-Agent": UA, Referer: playerUrl, Accept: "*/*" } })
    .then(function (r) {
      return r.ok ? r.text() : "";
    })
    .then(function (body) {
      if (body.indexOf("#EXTM3U") !== 0) return pick(list, embed, playerUrl, index + 1, subs);
      var rows = variants(url, body);
      var out = [];
      if (!rows.length) {
        out.push({ url: url, quality: "1080p", subtitles: subs });
      } else {
        for (var i = 0; i < rows.length; i++) {
          out.push({ url: rows[i].url, quality: rows[i].quality, subtitles: subs });
        }
      }
      return out;
    })
    .catch(function () {
      return pick(list, embed, playerUrl, index + 1, subs);
    });
}

function readSubtitles(html) {
  var block = /tracks\s*:\s*(\[[\s\S]*?\])\s*,\s*(?:captions|image)/.exec(html) || /tracks\s*:\s*(\[[\s\S]*?\])\s*\}/.exec(html);
  var out = [];
  if (!block) return out;
  var re = /"file"\s*:\s*"((?:[^"\\]|\\.)*)"[^}]*?"label"\s*:\s*"((?:[^"\\]|\\.)*)"/g;
  var m;
  while ((m = re.exec(block[1])) !== null && out.length < 4) {
    var file = m[1].replace(/\\\//g, "/");
    var label = m[2].replace(/\\\//g, "/").trim();
    var lang = /t[üu]rk/i.test(label) ? "tr" : /^english/i.test(label) ? "en" : label.slice(0, 2).toLowerCase();
    out.push({ url: file, language: lang, name: label + " [HDFilmCehennemi]" });
  }
  return out;
}

function attempt(embed, html, tries) {
  return pick(candidates(html, host(embed)), embed, embed, 0, readSubtitles(html)).then(function (rows) {
    if ((rows && rows.length) || tries <= 1) return rows || [];
    return text(embed, { referer: BASE + "/" }).then(function (fresh) {
      return attempt(embed, fresh, tries - 1);
    });
  });
}

function getStreams(tmdbId, mediaType) {
  var filmUrl = null;
  var embed = null;

  return titleOf(tmdbId, mediaType)
    .then(function (meta) {
      if (!meta.name) return null;
      return text(BASE + "/search/?q=" + encodeURIComponent(meta.name), {
        headers: { "X-Requested-With": "fetch", Accept: "application/json" }
      }).then(function (body) {
        var rows = [];
        try {
          rows = JSON.parse(body).results || [];
        } catch (e) {}
        var want = norm(meta.name);
        var fallback = null;
        for (var i = 0; i < rows.length; i++) {
          var href = first(/href="([^"]+)"/, rows[i]);
          if (!href) continue;
          var title = first(/<img[^>]*alt="([^"]*)"/, rows[i]) || "";
          var titleNorm = norm(title.replace(/&[a-z]+;/gi, " "));
          if (want && titleNorm === want) return href;
          if (want && titleNorm.indexOf(want + " ") === 0 && !fallback) fallback = href;
        }
        return fallback;
      });
    })
    .then(function (url) {
      if (!url) return null;
      filmUrl = url;
      return text(filmUrl);
    })
    .then(function (page) {
      if (!page) return null;
      var raw = String(page).replace(/\\\//g, "/");
      embed = first(/<iframe[^>]*(?:data-src|src)="([^"]+)"/, raw);
      if (!embed) return null;
      return text(embed, { referer: BASE + "/" });
    })
    .then(function (player) {
      if (!player) return [];
      return attempt(embed, player, 3).then(function (rows) {
        var out = [];
        var seen = {};
        for (var i = 0; i < (rows || []).length; i++) {
          var row = rows[i];
          if (!row || seen[row.url]) continue;
          seen[row.url] = 1;
          out.push({
            name: "HDFilmCehennemi",
            title: "HDFilmCehennemi - " + row.quality + " [HLS]",
            url: row.url,
            quality: row.quality,
            headers: { "User-Agent": UA, Referer: embed, Origin: origin(embed) + "/" },
            subtitles: row.subtitles
          });
        }
        return out;
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][hdfilmcehennemi] " + (e && e.message));
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
