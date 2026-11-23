// @provider {"id": "turkcedizi", "name": "TurkceDizi", "types": ["tv", "movie"], "domain": "turkcedizi.com", "formats": ["m3u8", "mp4"], "sample": "tt0903747:tv:1:1", "description": "TurkceDizi yerli ve yabancı dizi/film arşivi; HLS ve OkRu akışları.", "version":"1.0.2"}
/**
 * turkcedizi — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta (+ Wikidata TR / IMDb / TVMaze) -> /api/search?q= -> /<slug>?sezon=S&bolum=E
 *       -> iframeUrl -> s.php (__PLAYER__) | AES player (Salted__ MD5+CBC) | ok.ru -> HLS/MP4.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
var BASE = "https://turkcedizi.com";
var B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
  if (o.form) headers["Content-Type"] = "application/x-www-form-urlencoded";
  return fetch(url, { method: o.method || "GET", headers: headers, body: o.body }).then(function (r) {
    if (!r.ok) throw new Error("HTTP " + r.status + " " + url);
    return r.text();
  });
}

function abs(base, href) {
  if (!href) return null;
  if (/^https?:/i.test(href)) return href;
  var origin = /^(https?:\/\/[^/]+)/.exec(base);
  if (!origin) return href;
  if (href.indexOf("//") === 0) return "https:" + href;
  if (href.charAt(0) === "/") return origin[1] + href;
  return base.replace(/[^/]*$/, "") + href;
}

function fold(v) {
  return String(v || "")
    .replace(/[\u0131\u0130I]/g, "i").replace(/[\u015f\u015e]/g, "s").replace(/[\u011f\u011e]/g, "g")
    .replace(/[\u00e7\u00c7]/g, "c").replace(/[\u00f6\u00d6]/g, "o").replace(/[\u00fc\u00dc]/g, "u")
    .toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
}

function sameTitle(have, want) {
  var h = fold(have);
  var w = fold(want);
  return !!w && (h === w || h.indexOf(w + " ") === 0);
}

function hlsNoDownload(url) {
  var u = String(url || "");
  if (/\.m3u8(\?|$)/i.test(u) || u.indexOf("#") !== -1) return u;
  return u + "#.m3u8";
}

function b64Bytes(value) {
  var s = String(value || "").replace(/-/g, "+").replace(/_/g, "/").replace(/[^A-Za-z0-9+/=]/g, "");
  while (s.length % 4) s += "=";
  var out = [];
  var i, a, b, c, d;
  for (i = 0; i < s.length; i += 4) {
    a = B64.indexOf(s.charAt(i));
    b = B64.indexOf(s.charAt(i + 1));
    c = B64.indexOf(s.charAt(i + 2));
    d = B64.indexOf(s.charAt(i + 3));
    if (a < 0 || b < 0) break;
    out.push((a << 2) | (b >> 4));
    if (c > -1 && c !== 64) out.push(((b & 15) << 4) | (c >> 2));
    if (d > -1 && d !== 64) out.push(((c & 3) << 6) | d);
  }
  return out;
}

function utf8Str(list) {
  var out = "";
  for (var i = 0; i < list.length;) {
    var c = list[i++];
    if (c < 128) out += String.fromCharCode(c);
    else if (c > 191 && c < 224) out += String.fromCharCode(((c & 31) << 6) | (list[i++] & 63));
    else if (c > 223 && c < 240) out += String.fromCharCode(((c & 15) << 12) | ((list[i++] & 63) << 6) | (list[i++] & 63));
    else {
      var cp = (((c & 7) << 18) | ((list[i++] & 63) << 12) | ((list[i++] & 63) << 6) | (list[i++] & 63)) - 0x10000;
      out += String.fromCharCode(0xd800 + (cp >> 10), 0xdc00 + (cp & 1023));
    }
  }
  return out;
}

// ------------------------------------------------------------------ MD5 + AES-256-CBC (OpenSSL EVP_BytesToKey)

var MD5_S = [7, 12, 17, 22, 5, 9, 14, 20, 4, 11, 16, 23, 6, 10, 15, 21];
var MD5_K = [];
for (var _k = 0; _k < 64; _k++) MD5_K[_k] = Math.floor(Math.abs(Math.sin(_k + 1)) * 4294967296) >>> 0;

function md5Bytes(input) {
  var msg = input.slice();
  var bitLen = msg.length * 8;
  msg.push(0x80);
  while (msg.length % 64 !== 56) msg.push(0);
  for (var b = 0; b < 8; b++) msg.push((b < 4 ? (bitLen >>> (b * 8)) : Math.floor(bitLen / 4294967296) >>> ((b - 4) * 8)) & 255);
  var a0 = 0x67452301, b0 = 0xefcdab89, c0 = 0x98badcfe, d0 = 0x10325476;
  for (var off = 0; off < msg.length; off += 64) {
    var M = [];
    for (var j = 0; j < 16; j++) {
      var p = off + j * 4;
      M[j] = (msg[p] | (msg[p + 1] << 8) | (msg[p + 2] << 16) | (msg[p + 3] << 24)) >>> 0;
    }
    var A = a0, B = b0, C = c0, D = d0;
    for (var i = 0; i < 64; i++) {
      var F, g;
      if (i < 16) { F = (B & C) | (~B & D); g = i; }
      else if (i < 32) { F = (D & B) | (~D & C); g = (5 * i + 1) % 16; }
      else if (i < 48) { F = B ^ C ^ D; g = (3 * i + 5) % 16; }
      else { F = C ^ (B | ~D); g = (7 * i) % 16; }
      var s = MD5_S[(i >> 4) * 4 + (i % 4)];
      var sum = (F + A + MD5_K[i] + M[g]) >>> 0;
      A = D; D = C; C = B;
      B = (B + ((sum << s) | (sum >>> (32 - s)))) >>> 0;
    }
    a0 = (a0 + A) >>> 0; b0 = (b0 + B) >>> 0; c0 = (c0 + C) >>> 0; d0 = (d0 + D) >>> 0;
  }
  var out = [];
  var words = [a0, b0, c0, d0];
  for (var w = 0; w < 4; w++) {
    out.push(words[w] & 255, (words[w] >>> 8) & 255, (words[w] >>> 16) & 255, (words[w] >>> 24) & 255);
  }
  return out;
}

function mul(a, b) {
  var p = 0;
  for (var i = 0; i < 8; i++) {
    if (b & 1) p ^= a;
    var hi = a & 0x80;
    a = (a << 1) & 0xff;
    if (hi) a ^= 0x1b;
    b >>= 1;
  }
  return p;
}

function rotl(b, n) { return ((b << n) | (b >> (8 - n))) & 0xff; }

var SBOX = [], ISBOX = [];
(function () {
  for (var x = 0; x < 256; x++) {
    var inv = 0;
    if (x !== 0) {
      inv = 1;
      for (var e = 0; e < 254; e++) inv = mul(inv, x);
    }
    var s = inv ^ rotl(inv, 1) ^ rotl(inv, 2) ^ rotl(inv, 3) ^ rotl(inv, 4) ^ 0x63;
    SBOX[x] = s;
    ISBOX[s] = x;
  }
})();

function expandKey(key) {
  var w = [], i;
  for (i = 0; i < 8; i++) w[i] = [key[4 * i], key[4 * i + 1], key[4 * i + 2], key[4 * i + 3]];
  var rcon = 1;
  for (i = 8; i < 60; i++) {
    var t = w[i - 1].slice();
    if (i % 8 === 0) {
      t = [SBOX[t[1]] ^ rcon, SBOX[t[2]], SBOX[t[3]], SBOX[t[0]]];
      rcon = mul(rcon, 2);
    } else if (i % 8 === 4) {
      t = [SBOX[t[0]], SBOX[t[1]], SBOX[t[2]], SBOX[t[3]]];
    }
    w[i] = [w[i - 8][0] ^ t[0], w[i - 8][1] ^ t[1], w[i - 8][2] ^ t[2], w[i - 8][3] ^ t[3]];
  }
  var rk = [];
  for (i = 0; i < 60; i++) rk = rk.concat(w[i]);
  return rk;
}

function decryptBlock(rk, block) {
  var s = [], i, r, c;
  for (i = 0; i < 16; i++) s[i] = block[i] ^ rk[224 + i];
  for (var round = 13; round >= 0; round--) {
    var t = s.slice();
    for (r = 0; r < 4; r++) for (c = 0; c < 4; c++) s[r + 4 * c] = t[r + 4 * ((c - r + 4) % 4)];
    for (i = 0; i < 16; i++) s[i] = ISBOX[s[i]];
    for (i = 0; i < 16; i++) s[i] ^= rk[round * 16 + i];
    if (!round) break;
    for (c = 0; c < 4; c++) {
      var a0 = s[4 * c], a1 = s[4 * c + 1], a2 = s[4 * c + 2], a3 = s[4 * c + 3];
      s[4 * c] = mul(a0, 14) ^ mul(a1, 11) ^ mul(a2, 13) ^ mul(a3, 9);
      s[4 * c + 1] = mul(a0, 9) ^ mul(a1, 14) ^ mul(a2, 11) ^ mul(a3, 13);
      s[4 * c + 2] = mul(a0, 13) ^ mul(a1, 9) ^ mul(a2, 14) ^ mul(a3, 11);
      s[4 * c + 3] = mul(a0, 11) ^ mul(a1, 13) ^ mul(a2, 9) ^ mul(a3, 14);
    }
  }
  return s;
}

function decryptSaltedAes(blob, passBytes) {
  if (blob.length < 24) return "";
  var magic = utf8Str(blob.slice(0, 8));
  if (magic !== "Salted__") return "";
  var salt = blob.slice(8, 16);
  var derived = [], prev = [];
  while (derived.length < 48) {
    prev = md5Bytes(prev.concat(passBytes).concat(salt));
    derived = derived.concat(prev);
  }
  var rk = expandKey(derived.slice(0, 32));
  var iv = derived.slice(32, 48);
  var cipher = blob.slice(16);
  var out = [], p = iv;
  for (var off = 0; off + 16 <= cipher.length; off += 16) {
    var blk = cipher.slice(off, off + 16);
    var pl = decryptBlock(rk, blk);
    for (var i = 0; i < 16; i++) out.push(pl[i] ^ p[i]);
    p = blk;
  }
  var pad = out[out.length - 1];
  if (pad > 0 && pad <= 16) out = out.slice(0, out.length - pad);
  return utf8Str(out);
}

function decodeAesPlayer(html) {
  var dec = /var\s+(_\w+)\s*=\s*function\s*\(\s*s\s*\)\s*\{\s*return\s+atob\(\s*s\.split\(\s*''\s*\)\.reverse\(\)\.join\(\s*''\s*\)\s*\)\s*\}/.exec(html);
  if (!dec) return "";
  var re = new RegExp(dec[1] + "\\(\"([^\"]*)\"\\)", "g");
  var parts = [];
  var m;
  while ((m = re.exec(html)) !== null) parts.push(m[1]);
  if (parts.length < 2) return "";
  var payload = utf8Str(b64Bytes(parts[0].split("").reverse().join("")));
  var blob = b64Bytes(payload);
  var pass = b64Bytes(parts[1].split("").reverse().join(""));
  var plain = decryptSaltedAes(blob, pass);
  if (!plain) return "";
  var jb = /\[\s*((?:"[^"]*"\s*,?\s*)+)\]\s*\.join\(\s*''\s*\)/.exec(html);
  if (jb) {
    var junk = "";
    var jr = /"([^"]*)"/g;
    var jm;
    while ((jm = jr.exec(jb[1])) !== null) junk += jm[1];
    if (junk) plain = plain.split(junk).join("");
  }
  return plain;
}

// ------------------------------------------------------------------ site

function titleOf(id, mediaType) {
  if (/^__alt__:/.test(String(id))) return Promise.resolve(decodeURIComponent(String(id).slice(8)));
  var kind = mediaType === "tv" ? "series" : "movie";
  return text("https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json")
    .then(function (body) { return String(((JSON.parse(body).meta) || {}).name || "").trim(); })
    .catch(function () { return ""; });
}

function resolvePlayerUrl(url, pageUrl) {
  var u = abs(BASE, url);
  if (!u) return Promise.resolve([]);
  var playerOrigin = (/^(https?:\/\/[^/]+)/.exec(u) || [])[1] || BASE;
  return text(u, { referer: pageUrl }).then(function (html) {
    var pm = /window\.__PLAYER__\s*=\s*(\{[\s\S]*?\});/.exec(html);
    if (pm) {
      try {
        var cfg = JSON.parse(pm[1]);
        if (cfg && cfg.stream) {
          var subs = [];
          var list = cfg.subs || [];
          for (var i = 0; i < list.length && subs.length < 4; i++) {
            if (list[i] && list[i].src) subs.push({ url: list[i].src, language: String(list[i].lang || "tr"), name: String(list[i].label || "Altyazı") });
          }
          return [{
            name: "TurkceDizi",
            title: "TurkceDizi - HLS",
            url: hlsNoDownload(cfg.stream),
            quality: "1080p",
            headers: { Referer: playerOrigin + "/" },
            subtitles: subs
          }];
        }
      } catch (e) {}
    }
    var plain = decodeAesPlayer(html);
    var fm = /file\s*:\s*"([^"]+)/.exec(plain);
    if (fm && /^https?:/i.test(fm[1])) {
      var isHls = /\.m3u8/i.test(fm[1]);
      return [{
        name: "TurkceDizi",
        title: "TurkceDizi - " + (isHls ? "HLS" : "MP4"),
        url: isHls ? hlsNoDownload(fm[1]) : fm[1],
        quality: "1080p",
        headers: { Referer: playerOrigin + "/" },
        subtitles: []
      }];
    }
    return [];
  }).catch(function () { return []; });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var isMovie = mediaType !== "tv";
  var wantTitle = "";

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      wantTitle = title;
      if (!title) return null;
      return text(BASE + "/api/search?q=" + encodeURIComponent(title));
    })
    .then(function (raw) {
      if (!raw) return null;
      var arr;
      try { arr = JSON.parse(raw); } catch (e) { return null; }
      if (!Array.isArray(arr)) return null;
      var hit = null;
      for (var i = 0; i < arr.length; i++) {
        var item = arr[i] || {};
        if (isMovie && item.type === "series") continue;
        if (!isMovie && item.type !== "series") continue;
        if (sameTitle(item.title, wantTitle)) { hit = item; break; }
      }
      if (!hit || !hit.href) return null;
      var target = abs(BASE, hit.href);
      if (!isMovie) target += "?sezon=" + want + "&bolum=" + wantEp;
      return text(target, { referer: BASE + "/" }).then(function (html) {
        return { html: html, pageUrl: target };
      });
    })
    .then(function (res) {
      if (!res || !res.html) return [];
      var clean = res.html.replace(/\\\//g, "/").replace(/\\"/g, "\"");
      var frames = [];
      var seen = {};
      var re = /iframeUrl"\s*:\s*"([^"]+)/g;
      var m;
      while ((m = re.exec(clean)) !== null) {
        if (!seen[m[1]]) { seen[m[1]] = 1; frames.push(m[1]); }
      }
      var jobs = [];
      for (var i = 0; i < frames.length; i++) {
        jobs.push(resolvePlayerUrl(frames[i], res.pageUrl));
      }
      return Promise.all(jobs).then(function (lists) {
        var out = [];
        for (var j = 0; j < lists.length; j++) out = out.concat(lists[j]);
        return out;
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][turkcedizi] " + (e && e.message));
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
  var mt = mediaType === "series" ? "tv" : mediaType;
  if (!/^\d+$/.test(raw)) {
    try {
      return Promise.resolve(__origGetStreams(raw, mt, season, episode)).catch(function () { return []; });
    } catch (e) {
      return Promise.resolve([]);
    }
  }
  var tv = mt !== "movie";
  var headers = { "User-Agent": "BigBullNuvio/1.0 (https://github.com/F0Rextasy/bigbullapp-cs)", "Accept": "application/json" };
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
    try {
      return Promise.resolve(__origGetStreams(imdb || raw, mt, season, episode)).catch(function () { return []; });
    } catch (e) {
      return [];
    }
  }).catch(function () { return []; });
};

/* Cinemeta basligi yerellestirir ("Gibi" -> "As If"); sonuc bossa Wikidata TR/EN basliklariyla yeniden dene. */
function __altTitles(raw, mediaType) {
  var wdHeaders = { "User-Agent": "BigBullNuvio/1.0 (https://github.com/F0Rextasy/bigbullapp-cs)", "Accept": "application/json" };
  var h = { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36", "Accept": "application/json" };
  var api = "https://www.wikidata.org/w/api.php?format=json";
  var stmt = /^tt\d+$/.test(raw) ? "P345=" + raw : (mediaType === "movie" ? "P4947=" : "P4983=") + raw;
  var wd = fetch(api + "&action=query&list=search&srlimit=1&srsearch=" + encodeURIComponent("haswbstatement:" + stmt), { headers: wdHeaders })
    .then(function (r) { return r.ok ? r.json() : null; })
    .then(function (j) {
      var hits = (j && j.query && j.query.search) || [];
      if (!hits.length) return null;
      return fetch(api + "&action=wbgetentities&props=labels|aliases&languages=tr|en&ids=" + hits[0].title, { headers: wdHeaders }).then(function (r) { return r.ok ? r.json() : null; });
    })
    .then(function (j) {
      var res = [], ents = (j && j.entities) || {}, k, l, a, i;
      for (k in ents) {
        l = ents[k].labels || {}; a = ents[k].aliases || {};
        if (l.tr && l.tr.value) res.push(l.tr.value);
        if (l.en && l.en.value) res.push(l.en.value);
        for (i = 0; i < (a.tr || []).length && i < 2; i++) res.push(a.tr[i].value);
      }
      return res;
    }).catch(function () { return []; });
  var imdb = /^tt\d+$/.test(raw)
    ? fetch("https://v3.sg.media-imdb.com/suggestion/t/" + raw + ".json", { headers: h })
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (j) { return (j && j.d && j.d[0] && j.d[0].l) ? [j.d[0].l] : []; })
        .catch(function () { return []; })
    : Promise.resolve([]);
  var tvm = /^tt\d+$/.test(raw) && mediaType !== "movie"
    ? fetch("https://api.tvmaze.com/lookup/shows?imdb=" + raw, { headers: h })
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (j) { return (j && j.name) ? [j.name] : []; })
        .catch(function () { return []; })
    : Promise.resolve([]);
  return Promise.all([wd, imdb, tvm]).then(function (lists) {
    var out = [], seen = {};
    for (var i = 0; i < lists.length; i++) {
      for (var j = 0; j < lists[i].length; j++) {
        var v = String(lists[i][j] || "").replace(/\s*\([^)]*\)\s*$/, "").trim();
        if (v && !seen[v.toLowerCase()]) { seen[v.toLowerCase()] = 1; out.push(v); }
      }
    }
    return out;
  });
}
var __cinemetaGetStreams = module.exports.getStreams;
module.exports.getStreams = function (id, mediaType, season, episode) {
  var raw = String(id || "");
  try {
    return Promise.resolve(__cinemetaGetStreams(raw, mediaType, season, episode)).then(function (streams) {
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
  } catch (e) {
    return Promise.resolve([]);
  }
};

/* Dil etiketi (AGENTS.md): Nuvio karti "quality \u2022 ..." satirini gosterir; dil etiketi kalitenin yanina yazilir. */
var __subOnly = false;
var __langLabeled = module.exports.getStreams;
module.exports.getStreams = function (id, mediaType, season, episode) {
  try {
    return Promise.resolve(__langLabeled.apply(this, arguments)).then(function (streams) {
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
    }).catch(function () { return []; });
  } catch (e) {
    return Promise.resolve([]);
  }
};
