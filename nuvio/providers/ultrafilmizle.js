// @provider {"id":"ultrafilmizle","name":"UltraFilmizle","types":["movie"],"domain":"ufilmizle.com","formats":["m3u8"],"sample":"tt0117359:movie:1:1","description":"UltraFilmizle film arsivi (bePlayer AES + HLS).","verified":true}
/**
 * ultrafilmizle — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> WP REST search -> film page -> bePlayer embed (AES) -> HLS.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://ufilmizle.com";
var ID = "ultrafilmizle";

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

function norm(value) {
  return String(value || "")
    .replace(/ı/g, "i").replace(/ş/g, "s").replace(/ğ/g, "g")
    .replace(/ü/g, "u").replace(/ö/g, "o").replace(/ç/g, "c")
    .replace(/&#\d+;|&[a-z]+;/g, " ")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "");
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

// --- crypto: md5 + aes-256-cbc (plain JS) ---
var MD5_K = [], MD5_S = [7, 12, 17, 22, 5, 9, 14, 20, 4, 11, 16, 23, 6, 10, 15, 21];
for (var i = 0; i < 64; i++) MD5_K[i] = Math.floor(Math.abs(Math.sin(i + 1)) * 4294967296) >>> 0;

function md5(bytes) {
  var n = bytes.length, msglen = ((n + 8) >>> 6) * 64 + 64;
  var m = new Array(msglen), i;
  for (i = 0; i < msglen; i++) m[i] = 0;
  for (i = 0; i < n; i++) m[i] = bytes[i];
  m[n] = 0x80;
  var bits = n * 8;
  for (i = 0; i < 4; i++) m[msglen - 8 + i] = (bits >>> (8 * i)) & 255;
  var a0 = 0x67452301, b0 = 0xefcdab89, c0 = 0x98badcfe, d0 = 0x10325476;
  for (var off = 0; off < msglen; off += 64) {
    var w = new Array(16);
    for (i = 0; i < 16; i++) w[i] = m[off + 4 * i] | (m[off + 4 * i + 1] << 8) | (m[off + 4 * i + 2] << 16) | (m[off + 4 * i + 3] << 24);
    var a = a0, b = b0, c = c0, d = d0;
    for (i = 0; i < 64; i++) {
      var f, g;
      if (i < 16) { f = (b & c) | (~b & d); g = i; }
      else if (i < 32) { f = (d & b) | (~d & c); g = (5 * i + 1) % 16; }
      else if (i < 48) { f = b ^ c ^ d; g = (3 * i + 5) % 16; }
      else { f = c ^ (b | ~d); g = (7 * i) % 16; }
      var t = d;
      d = c;
      c = b;
      var x = (a + f + MD5_K[i] + w[g]) | 0;
      var s = MD5_S[(i >>> 4) * 4 + (i % 4)];
      b = (b + ((x << s) | (x >>> (32 - s)))) | 0;
      a = t;
    }
    a0 = (a0 + a) | 0; b0 = (b0 + b) | 0; c0 = (c0 + c) | 0; d0 = (d0 + d) | 0;
  }
  var out = [];
  var words = [a0, b0, c0, d0];
  for (var k = 0; k < 4; k++)
    for (var j = 0; j < 4; j++) out.push((words[k] >>> (8 * j)) & 255);
  return out;
}

var SBOX = [
0x63,0x7c,0x77,0x7b,0xf2,0x6b,0x6f,0xc5,0x30,0x01,0x67,0x2b,0xfe,0xd7,0xab,0x76,
0xca,0x82,0xc9,0x7d,0xfa,0x59,0x47,0xf0,0xad,0xd4,0xa2,0xaf,0x9c,0xa4,0x72,0xc0,
0xb7,0xfd,0x93,0x26,0x36,0x3f,0xf7,0xcc,0x34,0xa5,0xe5,0xf1,0x71,0xd8,0x31,0x15,
0x04,0xc7,0x23,0xc3,0x18,0x96,0x05,0x9a,0x07,0x12,0x80,0xe2,0xeb,0x27,0xb2,0x75,
0x09,0x83,0x2c,0x1a,0x1b,0x6e,0x5a,0xa0,0x52,0x3b,0xd6,0xb3,0x29,0xe3,0x2f,0x84,
0x53,0xd1,0x00,0xed,0x20,0xfc,0xb1,0x5b,0x6a,0xcb,0xbe,0x39,0x4a,0x4c,0x58,0xcf,
0xd0,0xef,0xaa,0xfb,0x43,0x4d,0x33,0x85,0x45,0xf9,0x02,0x7f,0x50,0x3c,0x9f,0xa8,
0x51,0xa3,0x40,0x8f,0x92,0x9d,0x38,0xf5,0xbc,0xb6,0xda,0x21,0x10,0xff,0xf3,0xd2,
0xcd,0x0c,0x13,0xec,0x5f,0x97,0x44,0x17,0xc4,0xa7,0x7e,0x3d,0x64,0x5d,0x19,0x73,
0x60,0x81,0x4f,0xdc,0x22,0x2a,0x90,0x88,0x46,0xee,0xb8,0x14,0xde,0x5e,0x0b,0xdb,
0xe0,0x32,0x3a,0x0a,0x49,0x06,0x24,0x5c,0xc2,0xd3,0xac,0x62,0x91,0x95,0xe4,0x79,
0xe7,0xc8,0x37,0x6d,0x8d,0xd5,0x4e,0xa9,0x6c,0x56,0xf4,0xea,0x65,0x7a,0xae,0x08,
0xba,0x78,0x25,0x2e,0x1c,0xa6,0xb4,0xc6,0xe8,0xdd,0x74,0x1f,0x4b,0xbd,0x8b,0x8a,
0x70,0x3e,0xb5,0x66,0x48,0x03,0xf6,0x0e,0x61,0x35,0x57,0xb9,0x86,0xc1,0x1d,0x9e,
0xe1,0xf8,0x98,0x11,0x69,0xd9,0x8e,0x94,0x9b,0x1e,0x87,0xe9,0xce,0x55,0x28,0xdf,
0x8c,0xa1,0x89,0x0d,0xbf,0xe6,0x42,0x68,0x41,0x99,0x2d,0x0f,0xb0,0x54,0xbb,0x16];

var INV_SBOX = new Array(256);
for (var sb = 0; sb < 256; sb++) INV_SBOX[SBOX[sb]] = sb;

function xt(a) { return ((a << 1) ^ (a & 0x80 ? 0x1b : 0)) & 255; }

function mul(a, b) {
  var r = 0;
  while (b) {
    if (b & 1) r ^= a;
    a = xt(a);
    b >>= 1;
  }
  return r;
}

function aesExpand(key) {
  var Nk = 8, Nr = 14, w = [], i;
  for (i = 0; i < Nk; i++) w[i] = key.slice(4 * i, 4 * i + 4);
  var rcon = 1;
  for (i = Nk; i < 4 * (Nr + 1); i++) {
    var t = w[i - 1].slice();
    if (i % Nk === 0) {
      t = [SBOX[t[1]] ^ rcon, SBOX[t[2]], SBOX[t[3]], SBOX[t[0]]];
      rcon = xt(rcon);
    } else if (i % Nk === 4) {
      t = [SBOX[t[0]], SBOX[t[1]], SBOX[t[2]], SBOX[t[3]]];
    }
    w[i] = [w[i - Nk][0] ^ t[0], w[i - Nk][1] ^ t[1], w[i - Nk][2] ^ t[2], w[i - Nk][3] ^ t[3]];
  }
  var rk = [];
  for (i = 0; i < 4 * (Nr + 1); i += 4) rk.push([].concat(w[i], w[i + 1], w[i + 2], w[i + 3]));
  return rk;
}

function invShiftRows(s) {
  var t = s.slice();
  for (var r = 1; r < 4; r++) for (var c = 0; c < 4; c++) s[r + 4 * ((c + r) % 4)] = t[r + 4 * c];
}

function aesDecryptBlock(s, rk) {
  var i, round;
  for (i = 0; i < 16; i++) s[i] ^= rk[14][i];
  for (round = 13; round >= 1; round--) {
    invShiftRows(s);
    for (i = 0; i < 16; i++) s[i] = INV_SBOX[s[i]];
    for (i = 0; i < 16; i++) s[i] ^= rk[round][i];
    for (var c = 0; c < 4; c++) {
      var o = 4 * c, a0 = s[o], a1 = s[o + 1], a2 = s[o + 2], a3 = s[o + 3];
      s[o] = mul(a0, 14) ^ mul(a1, 11) ^ mul(a2, 13) ^ mul(a3, 9);
      s[o + 1] = mul(a0, 9) ^ mul(a1, 14) ^ mul(a2, 11) ^ mul(a3, 13);
      s[o + 2] = mul(a0, 13) ^ mul(a1, 9) ^ mul(a2, 14) ^ mul(a3, 11);
      s[o + 3] = mul(a0, 11) ^ mul(a1, 13) ^ mul(a2, 9) ^ mul(a3, 14);
    }
  }
  invShiftRows(s);
  for (i = 0; i < 16; i++) s[i] = INV_SBOX[s[i]];
  for (i = 0; i < 16; i++) s[i] ^= rk[0][i];
  return s;
}

function aesCbcDecrypt(key, iv, ct) {
  var rk = aesExpand(key), out = [], prev = iv, i, j;
  for (i = 0; i + 16 <= ct.length; i += 16) {
    var block = aesDecryptBlock(ct.slice(i, i + 16), rk);
    for (j = 0; j < 16; j++) out.push(block[j] ^ prev[j]);
    prev = ct.slice(i, i + 16);
  }
  var pad = out.length ? out[out.length - 1] : 0;
  if (pad > 0 && pad <= 16 && pad <= out.length) out = out.slice(0, out.length - pad);
  return out;
}

var B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

function b64Decode(str) {
  var clean = String(str).replace(/[^A-Za-z0-9+/]/g, ""), out = [], buf = 0, bits = 0;
  for (var i = 0; i < clean.length; i++) {
    buf = (buf << 6) | B64.indexOf(clean.charAt(i));
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out.push((buf >>> bits) & 255);
    }
  }
  return out;
}

function utf8(bytes) {
  var s = "", i = 0;
  while (i < bytes.length) {
    var c = bytes[i++];
    if (c < 0x80) s += String.fromCharCode(c);
    else if (c < 0xe0) s += String.fromCharCode(((c & 0x1f) << 6) | (bytes[i++] & 0x3f));
    else if (c < 0xf0) s += String.fromCharCode(((c & 0x0f) << 12) | ((bytes[i++] & 0x3f) << 6) | (bytes[i++] & 0x3f));
    else {
      var cp = (((c & 0x07) << 18) | ((bytes[i++] & 0x3f) << 12) | ((bytes[i++] & 0x3f) << 6) | (bytes[i++] & 0x3f)) - 0x10000;
      s += String.fromCharCode(0xd800 + (cp >> 10), 0xdc00 + (cp & 0x3ff));
    }
  }
  return s;
}

function latin1Bytes(str) {
  var out = [];
  for (var i = 0; i < str.length; i++) out.push(str.charCodeAt(i) & 255);
  return out;
}

function hexBytes(str) {
  var out = [];
  for (var i = 0; i + 1 < String(str).length; i += 2) out.push(parseInt(String(str).substr(i, 2), 16));
  return out;
}
// --- end crypto ---

/** bePlayer(hash, {s,iv,ct}): key = MD5(hash+salt) + MD5(that+hash+salt), AES-256-CBC. */
function decryptBePlayer(hash, params) {
  var passphrase = latin1Bytes(hash);
  var salt = hexBytes(params.s);
  var iv = hexBytes(params.iv);
  var ct = b64Decode(params.ct);
  var part1 = md5(passphrase.concat(salt));
  var part2 = md5(part1.concat(passphrase, salt));
  return utf8(aesCbcDecrypt(part1.concat(part2).slice(0, 32), iv, ct));
}

function rowsFromPlaylist(masterUrl, playlist, referer, subtitles) {
  var list = [], lines = String(playlist || "").split(/\r?\n/);
  for (var i = 0; i < lines.length; i++) {
    if (lines[i].indexOf("#EXT-X-STREAM-INF") !== 0) continue;
    var height = first(/RESOLUTION=\d+x(\d+)/, lines[i]);
    var j = i + 1;
    while (j < lines.length && (!lines[j].trim() || lines[j].charAt(0) === "#")) j++;
    if (j >= lines.length) continue;
    list.push({ url: abs(masterUrl, lines[j].trim()), quality: height ? height + "p" : "" });
  }
  if (!list.length) list = [{ url: masterUrl, quality: "" }];
  return list.map(function (variant) {
    return {
      name: ID,
      title: ID + " - " + (variant.quality || "auto") + " [HLS]",
      url: variant.url,
      quality: variant.quality || "auto",
      headers: { Referer: referer },
      subtitles: subtitles || []
    };
  });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var wanted = "";
  var pageUrl = null;
  var embedUrl = null;

  if (mediaType === "tv") return Promise.resolve([]);

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      wanted = title;
      if (!title) return null;
      return text(BASE + "/wp-json/wp/v2/search?per_page=10&search=" + encodeURIComponent(title));
    })
    .then(function (raw) {
      if (!raw) return null;
      var items = JSON.parse(raw), key = norm(wanted), best = null;
      for (var i = 0; i < items.length; i++) {
        var name = norm(items[i].title);
        if (!name || !items[i].url) continue;
        var score = name === key ? 3 : name.indexOf(key) !== -1 ? 2 : key.indexOf(name) === 0 ? 1 : 0;
        if (score && (!best || score > best.score)) best = { url: items[i].url, score: score };
      }
      if (!best) return null;
      pageUrl = best.url;
      return text(pageUrl);
    })
    .then(function (page) {
      if (!page) return null;
      var code = first(/ilkpartkod\s*=\s*'([^']+)'/, page);
      var frame = code ? first(/src="([^"]+)"/, utf8(b64Decode(code))) : null;
      if (!frame) {
        var re = /<iframe[^>]+src="([^"]+)"/gi, m;
        while ((m = re.exec(page)) !== null) {
          if (!/youtube|facebook|google/i.test(m[1])) { frame = m[1]; break; }
        }
      }
      if (!frame) return null;
      embedUrl = abs(pageUrl, frame);
      return text(embedUrl, { referer: pageUrl });
    })
    .then(function (embed) {
      if (!embed) return [];
      var m = /bePlayer\('([^']+)'\s*,\s*'(\{[\s\S]*?\})'/.exec(embed);
      if (!m) return [];
      var player = JSON.parse(decryptBePlayer(m[1], JSON.parse(m[2])));
      var stream = String(player.video_location || "").trim();
      if (!/^https?:/.test(stream)) return [];

      var base = embedUrl.indexOf("/embed/") !== -1 ? embedUrl.substring(0, embedUrl.indexOf("/embed/")) : embedUrl;
      var subtitles = [];
      var subs = player.strSubtitles || [];
      for (var i = 0; i < subs.length && i < 3; i++) {
        var file = String(subs[i].file || "").trim();
        if (file.charAt(0) !== "/") continue;
        subtitles.push({
          url: base + file,
          language: String(subs[i].lang || subs[i].label || "tr").toLowerCase().slice(0, 2),
          name: String(subs[i].label || "Altyazi") + " [" + ID + "]"
        });
      }

      return text(stream, { referer: embedUrl }).then(function (playlist) {
        return rowsFromPlaylist(stream, playlist, embedUrl, subtitles);
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][" + ID + "] " + (e && e.message));
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