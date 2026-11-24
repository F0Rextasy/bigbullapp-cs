// @provider {"id":"ultrafilmizle","name":"UltraFilmizle","types":["movie"],"domain":"ultraiz.net","formats":["m3u8"],"sample":"tt33764258:movie:1:1","description":"UltraFilmizle - Turkce dublaj/altyazili film arsivi (BEpeak AES HLS).","verified":true,"version":"1.0.0"}
/**
 * ultrafilmizle — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /wp-json/wp/v2/search -> film page -> ilkpartkod / iframe ->
 *       bePlayer / #be-player-config EVP_BytesToKey MD5 + AES-256-CBC -> video_location.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://ultraiz.net";
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

function origin(url) {
  var m = /^(https?:\/\/[^/]+)/.exec(String(url || ""));
  return m ? m[1] : "";
}

function abs(base, href) {
  var u = String(href || "").trim();
  if (!u) return "";
  if (/^https?:\/\//i.test(u)) return u;
  if (u.indexOf("//") === 0) return "https:" + u;
  var root = origin(base || BASE) || BASE;
  if (u.charAt(0) === "/") return root + u;
  return root + "/" + u;
}

function hlsNoDownload(url) {
  var u = String(url || "").trim();
  if (!u) return u;
  if (/\.m3u8(\?|$)/i.test(u) || u.indexOf("#") !== -1) return u;
  return u + "#.m3u8";
}

function b64Bytes(value) {
  var s = String(value || "").replace(/[^A-Za-z0-9+/=]/g, "");
  var out = [], i, a, b, c, d;
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

function hexBytes(value) {
  var s = String(value || "");
  var out = [];
  for (var i = 0; i + 1 < s.length; i += 2) out.push(parseInt(s.substr(i, 2), 16));
  return out;
}

function utf8Bytes(str) {
  var s = unescape(encodeURIComponent(String(str || "")));
  var out = [];
  for (var i = 0; i < s.length; i++) out.push(s.charCodeAt(i) & 0xff);
  return out;
}

function bytesToUtf8(list) {
  var out = "", i = 0;
  while (i < list.length) {
    var c = list[i++];
    if (c < 128) out += String.fromCharCode(c);
    else if (c > 191 && c < 224) out += String.fromCharCode(((c & 31) << 6) | (list[i++] & 63));
    else if (c > 223 && c < 240) out += String.fromCharCode(((c & 15) << 12) | ((list[i++] & 63) << 6) | (list[i++] & 63));
    else {
      var cp = ((c & 7) << 18) | ((list[i++] & 63) << 12) | ((list[i++] & 63) << 6) | (list[i++] & 63);
      cp -= 0x10000;
      out += String.fromCharCode(0xd800 + (cp >> 10), 0xdc00 + (cp & 1023));
    }
  }
  return out;
}

var MD5_S = [
  7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
  5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
  4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
  6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21
];
var MD5_K = [];
(function () {
  for (var i = 0; i < 64; i++) MD5_K[i] = Math.floor(Math.abs(Math.sin(i + 1)) * 4294967296) >>> 0;
})();

function md5(bytes) {
  var msg = bytes.slice(), bitLen = msg.length * 8;
  msg.push(0x80);
  while (msg.length % 64 !== 56) msg.push(0);
  msg.push(bitLen & 0xff, (bitLen >>> 8) & 0xff, (bitLen >>> 16) & 0xff, (bitLen >>> 24) & 0xff, 0, 0, 0, 0);
  var a0 = 0x67452301, b0 = 0xefcdab89, c0 = 0x98badcfe, d0 = 0x10325476, m = [];
  for (var off = 0; off < msg.length; off += 64) {
    for (var j = 0; j < 16; j++) {
      var p = off + j * 4;
      m[j] = (msg[p] | (msg[p + 1] << 8) | (msg[p + 2] << 16) | (msg[p + 3] << 24)) >>> 0;
    }
    var A = a0, B = b0, C = c0, D = d0;
    for (var i = 0; i < 64; i++) {
      var F, g;
      if (i < 16) { F = (B & C) | ((~B) & D); g = i; }
      else if (i < 32) { F = (D & B) | ((~D) & C); g = (5 * i + 1) % 16; }
      else if (i < 48) { F = B ^ C ^ D; g = (3 * i + 5) % 16; }
      else { F = C ^ (B | (~D)); g = (7 * i) % 16; }
      var temp = D; D = C; C = B;
      var x = (A + F + MD5_K[i] + m[g]) >>> 0, s = MD5_S[i];
      B = (B + (((x << s) | (x >>> (32 - s))) >>> 0)) >>> 0;
      A = temp;
    }
    a0 = (a0 + A) >>> 0; b0 = (b0 + B) >>> 0; c0 = (c0 + C) >>> 0; d0 = (d0 + D) >>> 0;
  }
  var out = [];
  [a0, b0, c0, d0].forEach(function (w) { out.push(w & 0xff, (w >>> 8) & 0xff, (w >>> 16) & 0xff, (w >>> 24) & 0xff); });
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
    if (x !== 0) { inv = 1; for (var e = 0; e < 254; e++) inv = mul(inv, x); }
    var s = inv ^ rotl(inv, 1) ^ rotl(inv, 2) ^ rotl(inv, 3) ^ rotl(inv, 4) ^ 0x63;
    SBOX[x] = s; ISBOX[s] = x;
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

function decryptCbc(key, iv, data) {
  var rk = expandKey(key), out = [], prev = iv, i;
  for (var off = 0; off + 16 <= data.length; off += 16) {
    var block = data.slice(off, off + 16);
    var plain = decryptBlock(rk, block);
    for (i = 0; i < 16; i++) out.push(plain[i] ^ prev[i]);
    prev = block;
  }
  var pad = out[out.length - 1];
  if (pad > 0 && pad <= 16) out = out.slice(0, out.length - pad);
  return out;
}

function evpDecrypt(b64Ct, password, saltHex, ivHex) {
  var pw = utf8Bytes(password);
  var salt = hexBytes(saltHex);
  var d1 = md5(pw.concat(salt));
  var d2 = md5(d1.concat(pw).concat(salt));
  return bytesToUtf8(decryptCbc(d1.concat(d2), hexBytes(ivHex), b64Bytes(b64Ct)));
}

function decodeHtml(s) {
  return String(s || "")
    .replace(/&#x([0-9a-f]+);/gi, function (_, h) { return String.fromCharCode(parseInt(h, 16)); })
    .replace(/&#(\d+);/g, function (_, d) { return String.fromCharCode(parseInt(d, 10)); })
    .replace(/&amp;/g, "&").replace(/&quot;/g, '"').replace(/&#0?39;/g, "'");
}

function norm(value) {
  return decodeHtml(value)
    .replace(/\([^)]*\)/g, " ")
    .replace(/[\u0131\u0130I]/g, "i").replace(/[\u015f\u015e]/g, "s").replace(/[\u011f\u011e]/g, "g")
    .replace(/[\u00e7\u00c7]/g, "c").replace(/[\u00f6\u00d6]/g, "o").replace(/[\u00fc\u00dc]/g, "u")
    .toLowerCase()
    .replace(/\b(?:izle|film|full|hd)\b/g, " ")
    .replace(/[^a-z0-9]+/g, " ").trim()
    .replace(/^(?:the|a|an)\s+/, "");
}

function titleOf(id, mediaType) {
  var raw = String(id || "").trim();
  if (/^__alt__:/.test(raw)) return Promise.resolve(decodeURIComponent(raw.slice(8)));
  var kind = mediaType === "tv" ? "series" : "movie";
  return text("https://v3-cinemeta.strem.io/meta/" + kind + "/" + encodeURIComponent(raw) + ".json")
    .then(function (body) { return String(((JSON.parse(body).meta) || {}).name || "").trim(); })
    .catch(function () { return ""; });
}

function pick(cards, wantTitle) {
  var want = norm(wantTitle);
  if (!want) return null;
  var prefix = null;
  for (var i = 0; i < cards.length; i++) {
    var have = norm(cards[i].title);
    if (!have) continue;
    if (have === want) return cards[i];
    if (!prefix && have.indexOf(want + " ") === 0) prefix = cards[i];
  }
  return prefix;
}

function qualityOf(url, referer) {
  return text(url, { referer: referer, headers: { Accept: "*/*" } })
    .then(function (m3u8) {
      var best = 0, re = /RESOLUTION=\d+x(\d+)/g, m;
      while ((m = re.exec(m3u8)) !== null) {
        var h = Number(m[1]);
        if (h > best) best = h;
      }
      if (best >= 2160) return "4K";
      if (best >= 1080) return "1080p";
      if (best >= 720) return "720p";
      if (best >= 480) return "480p";
      return best > 0 ? best + "p" : "auto";
    })
    .catch(function () { return "auto"; });
}

function getStreams(tmdbId, mediaType, season, episode) {
  if (mediaType === "tv") return Promise.resolve([]);
  var pageUrl = "", embedUrl = "";

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return null;
      var q = String(title).replace(/^(?:the|a|an)\s+/i, "");
      return text(BASE + "/wp-json/wp/v2/search?search=" + encodeURIComponent(q) + "&per_page=20", {
        headers: { Accept: "application/json" }
      }).then(function (body) {
        var arr = JSON.parse(body);
        var cards = (arr || []).map(function (o) { return { url: o.url, title: o.title }; }).filter(function (c) { return c.url && c.title; });
        var hit = pick(cards, title);
        return hit ? hit.url : null;
      });
    })
    .then(function (url) {
      if (!url) return null;
      pageUrl = url;
      return text(url, { referer: BASE + "/" });
    })
    .then(function (html) {
      if (!html) return null;
      var enc = first(/ilkpartkod\s*=\s*'([^']+)'/, html);
      if (enc) {
        var dec = bytesToUtf8(b64Bytes(enc));
        embedUrl = abs(pageUrl, first(/src="([^"]+)"/i, dec));
      }
      if (!embedUrl) {
        var re = /<iframe[^>]+src="([^"]+)"/gi, m;
        while ((m = re.exec(html)) !== null) {
          if (!/youtube|facebook/i.test(m[1])) { embedUrl = abs(pageUrl, m[1]); break; }
        }
      }
      if (!embedUrl) return null;
      return text(embedUrl, { referer: pageUrl });
    })
    .then(function (embedHtml) {
      if (!embedHtml) return [];
      var player = null;
      var bm = /bePlayer\('([^']+)'\s*,\s*'(\{[\s\S]*?\})'/.exec(embedHtml);
      if (bm) {
        var p = JSON.parse(bm[2]);
        player = JSON.parse(evpDecrypt(p.ct, bm[1], p.s, p.iv));
      } else {
        var cm = /id="be-player-config"[^>]*data-config="([^"]+)"/.exec(embedHtml);
        if (cm) {
          var cfg = JSON.parse(cm[1].replace(/&quot;|&#34;/g, '"').replace(/&amp;/g, "&"));
          var st = typeof cfg.settings === "string" ? JSON.parse(cfg.settings) : cfg.settings;
          if (cfg.videoId && st && st.ct) {
            player = JSON.parse(evpDecrypt(st.ct, cfg.videoId, st.s, st.iv));
          }
        }
      }
      if (!player) return [];
      var stream = String(player.video_location || "").trim();
      if (!/^https?:\/\//i.test(stream)) return [];
      var base = embedUrl.split("/embed/")[0];
      var subs = [];
      (player.strSubtitles || []).forEach(function (sub) {
        var f = String((sub && sub.file) || "").trim();
        if (f.charAt(0) === "/") {
          var lbl = String(sub.label || "Altyaz\u0131").trim();
          subs.push({ url: base + f, language: /en/i.test(lbl) ? "en" : "tr", name: lbl + " [UltraFilmizle]" });
        }
      });
      return qualityOf(stream, embedUrl).then(function (q) {
        return [{
          name: "UltraFilmizle",
          title: "UltraFilmizle - HLS",
          url: hlsNoDownload(stream),
          quality: q,
          headers: { Referer: embedUrl, Accept: "*/*", "User-Agent": UA },
          subtitles: subs.slice(0, 6)
        }];
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][ultrafilmizle] " + (e && e.message));
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
  function fromTmdbTitle() {
    if (!key) return Promise.resolve("");
    return fetch("https://api.themoviedb.org/3/" + (tv ? "tv" : "movie") + "/" + raw + "?api_key=" + encodeURIComponent(key) + "&language=tr-TR", { headers: headers })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (json) {
        var t = (json && (json.original_name || json.name || json.original_title || json.title)) || "";
        return t ? "__alt__:" + encodeURIComponent(t) : "";
      })
      .catch(function () { return ""; });
  }
  return fromTmdb().then(function (imdb) { return imdb || fromWikidata(); }).then(function (imdb) { return imdb || fromTmdbTitle(); }).then(function (imdb) {
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
    if (/^__alt__:/.test(raw)) return Promise.resolve(__cinemetaGetStreams(raw, mediaType, season, episode)).catch(function () { return []; });
    function tryPrimary() {
      if (!/^tt\d+$/.test(raw)) return Promise.resolve(__cinemetaGetStreams(raw, mediaType, season, episode)).catch(function () { return []; });
      var h = { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36", "Accept": "application/json" };
      return fetch("https://v3.sg.media-imdb.com/suggestion/t/" + raw + ".json", { headers: h })
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (j) {
          var t = j && j.d && j.d[0] && j.d[0].l ? String(j.d[0].l).replace(/\s*\([^)]*\)\s*$/, "").trim() : "";
          return t ? __cinemetaGetStreams("__alt__:" + encodeURIComponent(t), mediaType, season, episode) : [];
        })
        .catch(function () { return []; })
        .then(function (streams) {
          return (streams && streams.length) ? streams : __cinemetaGetStreams(raw, mediaType, season, episode);
        });
    }
    return tryPrimary().then(function (streams) {
      if (streams && streams.length) return streams;
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
