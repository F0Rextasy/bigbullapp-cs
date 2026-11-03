// @provider {"id":"dizipal","name":"DiziPal","types":["movie","tv"],"domain":"dizipal1593.com","formats":["m3u8"],"sample":"tt0133093:movie:1:1","description":"DiziPal - Turkce dizi ve film arsivi; PBKDF2+SHA512 player token -> dplayer82 HLS master.","verified":false}
/**
 * dizipal — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> POST /api/mj {action:searchContent} -> content/episode page
 *       -> `enc` fields (ciphertext/iv/salt/k) -> PBKDF2-HMAC-SHA512(999) + AES-256-CBC
 *       -> dplayer82 player -> NP_CONFIG master (or legacy openPlayer) -> HLS variants.
 * The player CDN's Cloudflare rule 403s Chrome-style UAs (regardless of TLS) and undici's TLS
 * fingerprint, so requests carry a Firefox UA; that passes both the CDN and the Wikidata lookup.
 * The site moved from dizipal1592.com to dizipal1593.com; 1592 301s, and a redirected POST
 * turns into a GET, which /api/mj rejects with 405.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0";
var BASE = "https://dizipal1593.com";
var B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
var K512HEX =
  "428a2f98d728ae22 7137449123ef65cd b5c0fbcfec4d3b2f e9b5dba58189dbbc 3956c25bf348b538 59f111f1b605d019 923f82a4af194f9b ab1c5ed5da6d8118 " +
  "d807aa98a3030242 12835b0145706fbe 243185be4ee4b28c 550c7dc3d5ffb4e2 72be5d74f27b896f 80deb1fe3b1696b1 9bdc06a725c71235 c19bf174cf692694 " +
  "e49b69c19ef14ad2 efbe4786384f25e3 0fc19dc68b8cd5b5 240ca1cc77ac9c65 2de92c6f592b0275 4a7484aa6ea6e483 5cb0a9dcbd41fbd4 76f988da831153b5 " +
  "983e5152ee66dfab a831c66d2db43210 b00327c898fb213f bf597fc7beef0ee4 c6e00bf33da88fc2 d5a79147930aa725 06ca6351e003826f 142929670a0e6e70 " +
  "27b70a8546d22ffc 2e1b21385c26c926 4d2c6dfc5ac42aed 53380d139d95b3df 650a73548baf63de 766a0abb3c77b2a8 81c2c92e47edaee6 92722c851482353b " +
  "a2bfe8a14cf10364 a81a664bbc423001 c24b8b70d0f89791 c76c51a30654be30 d192e819d6ef5218 d69906245565a910 f40e35855771202a 106aa07032bbd1b8 " +
  "19a4c116b8d2d0c8 1e376c085141ab53 2748774cdf8eeb99 34b0bcb5e19b48a8 391c0cb3c5c95a63 4ed8aa4ae3418acb 5b9cca4f7763e373 682e6ff3d6b2b8a3 " +
  "748f82ee5defb2fc 78a5636f43172f60 84c87814a1f0ab72 8cc702081a6439ec 90befffa23631e28 a4506cebde82bde9 bef9a3f7b2c67915 c67178f2e372532b " +
  "ca273eceea26619c d186b8c721c0c207 eada7dd6cde0eb1e f57d4f7fee6ed178 06f067aa72176fba 0a637dc5a2c898a6 113f9804bef90dae 1b710b35131c471b " +
  "28db77f523047d84 32caab7b40c72493 3c9ebe0a15c9bebc 431d67c49c100d4c 4cc5d4becb3e42b6 597f299cfc657e2a 5fcb6fab3ad6faec 6c44198c4a475817";
var IV512HEX =
  "6a09e667f3bcc908 bb67ae8584caa73b 3c6ef372fe94f82b a54ff53a5f1d36f1 510e527fade682d1 9b05688c2b3e6c1f 1f83d9abfb41bd6b 5be0cd19137e2179";

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
  return m ? m[1] : null;
}

/** atob is absent in the Nuvio sandbox: base64 -> byte array by hand. */
function bytes(value) {
  var s = String(value || "").replace(/[^A-Za-z0-9+/=]/g, "");
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

function hexBytes(value) {
  var s = String(value || "");
  var out = [];
  for (var i = 0; i + 1 < s.length; i += 2) out.push(parseInt(s.substr(i, 2), 16));
  return out;
}

function utf8(list) {
  var out = "";
  var i = 0;
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

function chars(value) {
  var out = [];
  for (var i = 0; i < value.length; i++) out.push(value.charCodeAt(i) & 0xff);
  return out;
}

// ------------------------------------------------------------------ SHA-512

function pairs(hex) {
  var out = [];
  var parts = hex.split(" ");
  for (var i = 0; i < parts.length; i++) {
    out.push([parseInt(parts[i].slice(0, 8), 16), parseInt(parts[i].slice(8), 16)]);
  }
  return out;
}

var K512 = pairs(K512HEX);
var IV512 = pairs(IV512HEX);

function add(a, b) {
  var lo = a[1] + b[1];
  var carry = (lo - (lo % 4294967296)) / 4294967296;
  lo = lo % 4294967296;
  var hi = (a[0] + b[0] + carry) % 4294967296;
  return [hi, lo];
}

function or(a, b) {
  return [(a[0] | b[0]) >>> 0, (a[1] | b[1]) >>> 0];
}

function xor(a, b) {
  return [(a[0] ^ b[0]) >>> 0, (a[1] ^ b[1]) >>> 0];
}

function rotr(a, n) {
  var hi = a[0];
  var lo = a[1];
  if (n === 32) return [lo, hi];
  if (n < 32) return [((hi >>> n) | (lo << (32 - n))) >>> 0, ((lo >>> n) | (hi << (32 - n))) >>> 0];
  var m = n - 32;
  return [((lo >>> m) | (hi << (32 - m))) >>> 0, ((hi >>> m) | (lo << (32 - m))) >>> 0];
}

function shr(a, n) {
  var hi = a[0];
  var lo = a[1];
  if (n >= 64) return [0, 0];
  if (n === 32) return [0, hi];
  if (n < 32) return [hi >>> n, ((lo >>> n) | (hi << (32 - n))) >>> 0];
  return [0, hi >>> (n - 32)];
}

function sigma0(x) {
  return xor(xor(rotr(x, 1), rotr(x, 8)), shr(x, 7));
}

function sigma1(x) {
  return xor(xor(rotr(x, 19), rotr(x, 61)), shr(x, 6));
}

function bigSigma0(x) {
  return xor(xor(rotr(x, 28), rotr(x, 34)), rotr(x, 39));
}

function bigSigma1(x) {
  return xor(xor(rotr(x, 14), rotr(x, 18)), rotr(x, 41));
}

function sha512(list) {
  var msg = list.slice();
  var bitLenHi = Math.floor((msg.length * 8) / 4294967296);
  var bitLenLo = (msg.length * 8) % 4294967296;
  msg.push(0x80);
  while (msg.length % 128 !== 112) msg.push(0);
  msg.push(
    0, 0, 0, 0, 0, 0, 0, 0,
    (bitLenHi >>> 24) & 255, (bitLenHi >>> 16) & 255, (bitLenHi >>> 8) & 255, bitLenHi & 255,
    (bitLenLo >>> 24) & 255, (bitLenLo >>> 16) & 255, (bitLenLo >>> 8) & 255, bitLenLo & 255
  );

  var h = IV512.slice();
  var w = [];
  for (var off = 0; off < msg.length; off += 128) {
    var t;
    for (t = 0; t < 16; t++) {
      var p = off + t * 8;
      w[t] = [
        ((msg[p] << 24) | (msg[p + 1] << 16) | (msg[p + 2] << 8) | msg[p + 3]) >>> 0,
        ((msg[p + 4] << 24) | (msg[p + 5] << 16) | (msg[p + 6] << 8) | msg[p + 7]) >>> 0
      ];
    }
    for (t = 16; t < 80; t++) {
      w[t] = add(add(sigma1(w[t - 2]), w[t - 7]), add(sigma0(w[t - 15]), w[t - 16]));
    }
    var a = h[0];
    var b = h[1];
    var c = h[2];
    var d = h[3];
    var e = h[4];
    var f = h[5];
    var g = h[6];
    var hh = h[7];
    for (t = 0; t < 80; t++) {
      var ch = xor([e[0] & f[0], e[1] & f[1]], [~e[0] & g[0], ~e[1] & g[1]]);
      var maj = xor(xor([a[0] & b[0], a[1] & b[1]], [a[0] & c[0], a[1] & c[1]]), [b[0] & c[0], b[1] & c[1]]);
      var t1 = add(add(add(hh, bigSigma1(e)), add(ch, K512[t])), w[t]);
      var t2 = add(bigSigma0(a), maj);
      hh = g;
      g = f;
      f = e;
      e = add(d, t1);
      d = c;
      c = b;
      b = a;
      a = add(t1, t2);
    }
    h = [add(h[0], a), add(h[1], b), add(h[2], c), add(h[3], d), add(h[4], e), add(h[5], f), add(h[6], g), add(h[7], hh)];
  }

  var out = [];
  for (var i = 0; i < 8; i++) {
    out.push((h[i][0] >>> 24) & 255, (h[i][0] >>> 16) & 255, (h[i][0] >>> 8) & 255, h[i][0] & 255);
    out.push((h[i][1] >>> 24) & 255, (h[i][1] >>> 16) & 255, (h[i][1] >>> 8) & 255, h[i][1] & 255);
  }
  return out;
}

function hmac512(key, message) {
  var block = key.length > 128 ? sha512(key) : key;
  var pad = block.slice();
  while (pad.length < 128) pad.push(0);
  var inner = [];
  var outer = [];
  for (var i = 0; i < 128; i++) {
    inner.push(pad[i] ^ 0x36);
    outer.push(pad[i] ^ 0x5c);
  }
  return sha512(outer.concat(sha512(inner.concat(message))));
}

/** PBKDF2 with a 32-byte output is a single HMAC block: xor of the first 999 rounds. */
function pbkdf2(password, salt, rounds, size) {
  var seed = salt.slice();
  seed.push(0, 0, 0, 1);
  var u = hmac512(password, seed);
  var out = u.slice();
  for (var i = 1; i < rounds; i++) {
    u = hmac512(password, u);
    for (var j = 0; j < size; j++) out[j] ^= u[j];
  }
  return out.slice(0, size);
}

// ------------------------------------------------------------------ AES-256-CBC

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

function rotl(b, n) {
  return ((b << n) | (b >> (8 - n))) & 0xff;
}

var SBOX = [];
var ISBOX = [];
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
  var w = [];
  var i;
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
  var s = [];
  var i, r, c;
  for (i = 0; i < 16; i++) s[i] = block[i] ^ rk[224 + i];
  for (var round = 13; round >= 0; round--) {
    var t = s.slice();
    for (r = 0; r < 4; r++) for (c = 0; c < 4; c++) s[r + 4 * c] = t[r + 4 * ((c - r + 4) % 4)];
    for (i = 0; i < 16; i++) s[i] = ISBOX[s[i]];
    for (i = 0; i < 16; i++) s[i] ^= rk[round * 16 + i];
    if (!round) break;
    for (c = 0; c < 4; c++) {
      var a0 = s[4 * c];
      var a1 = s[4 * c + 1];
      var a2 = s[4 * c + 2];
      var a3 = s[4 * c + 3];
      s[4 * c] = mul(a0, 14) ^ mul(a1, 11) ^ mul(a2, 13) ^ mul(a3, 9);
      s[4 * c + 1] = mul(a0, 9) ^ mul(a1, 14) ^ mul(a2, 11) ^ mul(a3, 13);
      s[4 * c + 2] = mul(a0, 13) ^ mul(a1, 9) ^ mul(a2, 14) ^ mul(a3, 11);
      s[4 * c + 3] = mul(a0, 11) ^ mul(a1, 13) ^ mul(a2, 9) ^ mul(a3, 14);
    }
  }
  return s;
}

function decryptCbc(key, iv, data) {
  var rk = expandKey(key);
  var out = [];
  var prev = iv;
  var i;
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

// ------------------------------------------------------------------ site

/* Nuvio sends TMDB ids from its own rows; Wikidata bridges them to the IMDb id Cinemeta keys on. */
function imdbOf(id, mediaType) {
  if (!/^\d+$/.test(String(id || ""))) return Promise.resolve(String(id || ""));
  var prop = mediaType === "movie" ? "P4947" : "P4983";
  var api = "https://www.wikidata.org/w/api.php?format=json";
  return text(api + "&action=query&list=search&srlimit=1&srsearch=" +
      encodeURIComponent("haswbstatement:" + prop + "=" + id))
    .then(function (body) {
      var hits = (JSON.parse(body).query || {}).search || [];
      if (!hits.length) return "";
      return text(api + "&action=wbgetclaims&entity=" + hits[0].title + "&property=P345");
    })
    .then(function (body) {
      if (!body) return "";
      var claims = (JSON.parse(body).claims || {}).P345 || [];
      for (var i = 0; i < claims.length; i++) {
        var snak = claims[i].mainsnak || {};
        if (snak.datavalue) return String(snak.datavalue.value || "");
      }
      return "";
    })
    .catch(function () {
      return "";
    });
}

function titleOf(id, mediaType) {
  var kind = mediaType === "tv" ? "series" : "movie";
  return imdbOf(id, mediaType)
    .then(function (imdb) {
      if (!imdb) return "";
      return text("https://v3-cinemeta.strem.io/meta/" + kind + "/" + imdb + ".json");
    })
    .then(function (body) {
      if (!body) return "";
      var meta = JSON.parse(body).meta || {};
      return String(meta.name || "").trim();
    })
    .catch(function () {
      return "";
    });
}

function norm(value) {
  return String(value || "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
}

/** The service rejects queries shorter than three characters. */
function search(name) {
  if (norm(name).length < 3) return Promise.resolve(null);
  return text(BASE + "/api/mj", {
    method: "POST",
    referer: BASE + "/",
    headers: { "Content-Type": "application/json", Accept: "application/json, text/plain, */*", Origin: BASE },
    body: JSON.stringify({ action: "searchContent", params: [name] })
  }).then(function (body) {
    var rows = [];
    try {
      rows = JSON.parse(body).result || [];
    } catch (e) {}
    var target = norm(name);
    var best = null;
    for (var i = 0; i < rows.length; i++) {
      var slug = String(rows[i].used_slug || "").replace(/^\//, "");
      if (!slug) continue;
      if (norm(rows[i].object_name) === target) return rows[i];
      if (!best && norm(rows[i].object_name).indexOf(target) === 0) best = rows[i];
    }
    return best;
  });
}

/** The token lives in the RSC payload, so the regex tolerates `\"` escaping. */
function encFields(html) {
  var re = /\\{0,3}"(ciphertext|iv|salt|k)\\{0,3}":\\{0,3}"([^"\\]+)/g;
  var found = {};
  var m;
  while ((m = re.exec(String(html || ""))) !== null) {
    if (!found[m[1]]) found[m[1]] = [];
    found[m[1]].push(m[2]);
  }
  var pass = (found.k || []).sort(function (a, b) {
    return b.length - a.length;
  })[0];
  return {
    ciphertext: (found.ciphertext || [])[0],
    iv: (found.iv || [])[0],
    salt: (found.salt || [])[0],
    pass: pass
  };
}

/** PBKDF2-HMAC-SHA512(999) + AES-256-CBC -> player URL (tab/NUL-padded). */
function decryptEmbed(html, pageUrl) {
  var f = encFields(html);
  if (!f.ciphertext || !f.iv || !f.salt || !f.pass) return null;
  var key = pbkdf2(chars(f.pass), hexBytes(f.salt), 999, 32);
  var clear = utf8(decryptCbc(key, hexBytes(f.iv), bytes(f.ciphertext)));
  var url = clear.replace(/^[\t\r\n \u000f]+|[\t\r\n \u000f]+$/g, "");
  if (url.slice(0, 2) === "//") return "https:" + url;
  if (/^http:\/\//i.test(url)) return url.replace(/^http/i, "https");
  return url || null;
}

/** Series pages link `/bolum/<slug>-<S>x<E>`; the episode page carries its own token. */
function episodeUrl(html, want, wantEp) {
  var links = [];
  var re = /href="([^"]*\/bolum\/[^"]+)"/g;
  var m;
  while ((m = re.exec(String(html || ""))) !== null) links.push(m[1]);
  var exact = "-" + want + "x" + wantEp;
  var fallback = null;
  for (var i = 0; i < links.length; i++) {
    var slug = links[i].replace(/[?#].*$/, "");
    if (!fallback) fallback = slug;
    if (slug.slice(-(exact.length + 1)) === exact + "/") return slug;
    if (slug.indexOf(exact) !== -1) return slug;
  }
  return fallback;
}

function abs(base, href) {
  if (/^https?:/i.test(href)) return href;
  if (href.slice(0, 2) === "//") return "https:" + href;
  if (href.charAt(0) === "/") {
    var root = origin(base);
    return root ? root + href : href;
  }
  return base.replace(/[^/]*$/, "") + href;
}

/** Cinema-aspect masters still read as their ladder rung, so label by width first. */
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

function plain(master) {
  var root = (origin(master) || "") + "/";
  return fetch(master, { headers: { "User-Agent": UA, Accept: "*/*", Referer: root } })
    .then(function (r) {
      return r.ok ? r.text() : "";
    })
    .then(function (body) {
      var rows = body.indexOf("#EXTM3U") === 0 ? variants(master, body) : [];
      return rows.length ? rows : [{ url: master, quality: "1080p" }];
    })
    .catch(function () {
      return [{ url: master, quality: "1080p" }];
    });
}

/** `window.NP_CONFIG = {...}` body (brace counted, strings skipped). */
function objectAt(html, from) {
  var depth = 0;
  var inString = false;
  var escaped = false;
  for (var i = from; i < html.length; i++) {
    var ch = html.charAt(i);
    if (inString) {
      if (escaped) escaped = false;
      else if (ch === "\\") escaped = true;
      else if (ch === '"') inString = false;
      continue;
    }
    if (ch === '"') inString = true;
    else if (ch === "{") depth++;
    else if (ch === "}") {
      depth--;
      if (!depth) return html.slice(from, i + 1);
    }
  }
  return null;
}

/** dplayer82 player page -> master playlist URLs. */
function masters(playerUrl) {
  return text(playerUrl, { referer: BASE + "/" }).then(function (html) {
    var out = [];
    var at = html.indexOf("NP_CONFIG");
    if (at >= 0) {
      var open = html.indexOf("{", at);
      var config = open >= 0 ? objectAt(html, open) : null;
      var parsed = null;
      if (config) {
        try {
          parsed = JSON.parse(config);
        } catch (e) {}
      }
      var sources = (parsed && parsed.sources) || [];
      for (var i = 0; i < sources.length; i++) {
        if (sources[i] && /^http/.test(sources[i].master || "")) out.push(sources[i].master);
      }
      if (out.length) return out;
    }
    // Legacy player: openPlayer('<token>') -> source2.php -> playlist[].sources[].file.
    var token = first(/openPlayer\s*\(\s*['"]([^'"]+)/, html);
    if (!token) return out;
    return text(origin(playerUrl) + "/source2.php?v=" + encodeURIComponent(token), {
      referer: playerUrl,
      headers: { "X-Requested-With": "XMLHttpRequest" }
    }).then(function (body) {
      var json = null;
      try {
        json = JSON.parse(body);
      } catch (e) {}
      var list = (json && json.playlist) || [];
      for (var i = 0; i < list.length; i++) {
        var files = list[i].sources || [];
        for (var j = 0; j < files.length; j++) {
          var file = files[j].file;
          if (!file || !/^http/.test(file)) continue;
          out.push(String(file).replace("m.php", "master.m3u8"));
        }
      }
      return out;
    });
  });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var pageUrl = null;
  var slot = null;

  return titleOf(tmdbId, mediaType)
    .then(function (name) {
      if (!name) return null;
      return search(name);
    })
    .then(function (hit) {
      if (!hit) return null;
      slot = hit;
      pageUrl = BASE + "/" + String(hit.used_slug).replace(/^\//, "");
      return text(pageUrl, { referer: BASE + "/" });
    })
    .then(function (html) {
      if (!html) return null;
      if (String(slot.used_type).toLowerCase() === "series") {
        var target = episodeUrl(html, want, wantEp);
        if (!target) return null;
        pageUrl = abs(BASE, target);
        return text(pageUrl, { referer: BASE + "/" });
      }
      return html;
    })
    .then(function (html) {
      if (!html) return [];
      var player = decryptEmbed(html, pageUrl);
      if (!player) return [];
      return masters(player).then(function (urls) {
        var jobs = [];
        for (var i = 0; i < urls.length; i++) jobs.push(plain(urls[i]));
        return Promise.all(jobs).then(function (lists) {
          var rows = [];
          for (var j = 0; j < lists.length; j++) {
            for (var k = 0; k < lists[j].length; k++) rows.push({ url: lists[j][k].url, quality: lists[j][k].quality, player: player });
          }
          return rows;
        });
      });
    })
    .then(function (rows) {
      var out = [];
      var seen = {};
      for (var i = 0; i < (rows || []).length; i++) {
        var row = rows[i];
        if (!row || seen[row.url]) continue;
        seen[row.url] = 1;
        out.push({
          name: "DiziPal",
          title: "DiziPal - " + row.quality + " [HLS]",
          url: row.url,
          quality: row.quality,
          headers: { "User-Agent": UA, Referer: (origin(row.player) || "") + "/" }
        });
      }
      return out;
    })
    .catch(function (e) {
      console.log("[bb-nuvio][dizipal] " + (e && e.message));
      return [];
    });
}

module.exports = { getStreams };
