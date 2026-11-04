// @provider {"id":"inatgain","name":"Inat Gain","types":["tv"],"domain":"inatbox","formats":["m3u8"],"sample":"tt31027788:tv:1:1","description":"Inat Box Gain katalogu (dizi/film) - imzali liste + AES cozumleyici, HLS.","verified":true}
var CFG = {
  id: "inatgain",
  name: "Inat Gain",
  url: "https://diziboxen.help/CDN/001/002/dizibox/v2/ga/index.php",
  host: "diziboxen.help",
  sha: "sha256/LwJtYGEjL1fpZQHDsjX5Hb1G1cmszajwrB1Dv/BVGVU=",
  dataType: "tv_mode",
  kind: "tv",
  home: "https://diziboxen.help"
};
/**
 * Inat — Nuvio provider engine (shared by every Inat module).
 * Signed lists (x-ts/x-nc/x-sg), double AES-128-CBC layers, resolver rows.
 * Pure JS: SHA-256, HMAC-SHA256, AES-128-CBC and base64 are local, so the
 * module runs in Nuvio's QuickJS sandbox with nothing but `fetch`.
 */

var INAT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
// Wikimedia blocks browser-spoofed user agents from fetch clients; a descriptive one is served.
var WIKIDATA_UA = "inatgain-nuvio/1.0 (cloudstream repo; +https://github.com/F0Rextasy/bigbullapp-cs)";
var CERT_SHA256 = "7cc771973665660f0653fce2e9490e48b92be8432a88d74f91703a716f6b4692";
var ENCODED_KEY_HEX = "4c50054a43216168797045480a046734372e298080c489a2f8a4b8b5819a9f93efb3bbf0f2525a4ca6";
var RAW_RESPONSE_KEY = "a4osa8x1yl4w3vrk";
var API_HEADERS = {
  "user-agent": "speedrestapi",
  "referer": "https://speedrestapi.com/",
  "x-requested-with": "com.bp.box",
  "cache-control": "no-cache"
};
var HOST_ALTERNATES = { "diziboxen.help": "dizilabmedia.click", "4k.filmizleeeee.cfd": "str.filmizleeeee.cfd" };
var FORBIDDEN_HEADERS = {
  host: 1, connection: 1, "content-length": 1, "transfer-encoding": 1, "proxy-authorization": 1,
  "proxy-connection": 1, "x-ts": 1, "x-nc": 1, "x-sg": 1, "x-st": 1
};
var MEDIA_URL = /\.(?:m3u8|mp4|webm|ts|mkv)(?:[?#].*)?$/i;
var TEKLI_HANDLERS = { tekli_regex_mode: 1, tekli_regex_no_sh_mode: 1, tekli_regex_replace_mode: 1 };

// ---------------------------------------------------------------- bytes

function utf8Bytes(str) {
  var out = [];
  for (var i = 0; i < str.length; i++) {
    var c = str.charCodeAt(i);
    if (c < 0x80) {
      out.push(c);
    } else if (c < 0x800) {
      out.push(0xc0 | (c >> 6), 0x80 | (c & 0x3f));
    } else if (c >= 0xd800 && c <= 0xdbff && i + 1 < str.length) {
      var pair = 0x10000 + ((c - 0xd800) << 10) + (str.charCodeAt(++i) - 0xdc00);
      out.push(0xf0 | (pair >> 18), 0x80 | ((pair >> 12) & 0x3f), 0x80 | ((pair >> 6) & 0x3f), 0x80 | (pair & 0x3f));
    } else {
      out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f));
    }
  }
  return out;
}

function utf8Text(bytes) {
  var out = "";
  for (var i = 0; i < bytes.length; i++) {
    var b = bytes[i] & 0xff;
    if (b < 0x80) {
      out += String.fromCharCode(b);
    } else if (b < 0xe0) {
      out += String.fromCharCode(((b & 0x1f) << 6) | (bytes[++i] & 0x3f));
    } else if (b < 0xf0) {
      out += String.fromCharCode(((b & 0x0f) << 12) | ((bytes[++i] & 0x3f) << 6) | (bytes[++i] & 0x3f));
    } else {
      var cp = ((b & 0x07) << 18) | ((bytes[++i] & 0x3f) << 12) | ((bytes[++i] & 0x3f) << 6) | (bytes[++i] & 0x3f);
      cp -= 0x10000;
      out += String.fromCharCode(0xd800 + (cp >> 10), 0xdc00 + (cp & 0x3ff));
    }
  }
  return out;
}

function hexBytes(hex) {
  var out = [];
  for (var i = 0; i < hex.length / 2; i++) out.push(parseInt(hex.substr(i * 2, 2), 16));
  return out;
}

function hexOf(bytes) {
  var digits = "0123456789abcdef", out = "";
  for (var i = 0; i < bytes.length; i++) out += digits.charAt((bytes[i] >> 4) & 15) + digits.charAt(bytes[i] & 15);
  return out;
}

function b64ToBytes(value) {
  var table = {}, chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/", i;
  for (i = 0; i < chars.length; i++) table[chars.charAt(i)] = i;
  var clean = String(value).replace(/[^A-Za-z0-9+/]/g, "");
  var out = [], buffer = 0, bits = 0;
  for (i = 0; i < clean.length; i++) {
    buffer = (buffer << 6) | table[clean.charAt(i)];
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out.push((buffer >> bits) & 0xff);
    }
  }
  return out;
}

// ---------------------------------------------------------------- sha256

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

function rotr(value, count) {
  return (value >>> count) | (value << (32 - count));
}

function sha256Bytes(bytes) {
  var h = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
  var length = bytes.length;
  var padded = (((length + 9) + 63) >> 6) << 6;
  var block = [];
  for (var i = 0; i < padded; i++) block.push(i < length ? bytes[i] & 0xff : (i === length ? 0x80 : 0));
  var bits = length * 8;
  for (i = 0; i < 4; i++) block[padded - 1 - i] = (bits >>> (8 * i)) & 0xff;
  var w = [];
  for (var offset = 0; offset < padded; offset += 64) {
    for (i = 0; i < 16; i++) {
      w[i] = (block[offset + i * 4] << 24) | (block[offset + i * 4 + 1] << 16) | (block[offset + i * 4 + 2] << 8) | block[offset + i * 4 + 3];
    }
    for (i = 16; i < 64; i++) {
      var s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3);
      var s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10);
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) | 0;
    }
    var a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], k = h[7];
    for (i = 0; i < 64; i++) {
      var sum1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
      var ch = (e & f) ^ (~e & g);
      var t1 = (k + sum1 + ch + K256[i] + w[i]) | 0;
      var sum0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
      var maj = (a & b) ^ (a & c) ^ (b & c);
      var t2 = (sum0 + maj) | 0;
      k = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0;
    }
    h[0] = (h[0] + a) | 0; h[1] = (h[1] + b) | 0; h[2] = (h[2] + c) | 0; h[3] = (h[3] + d) | 0;
    h[4] = (h[4] + e) | 0; h[5] = (h[5] + f) | 0; h[6] = (h[6] + g) | 0; h[7] = (h[7] + k) | 0;
  }
  var out = [];
  for (i = 0; i < 8; i++) out.push((h[i] >>> 24) & 0xff, (h[i] >>> 16) & 0xff, (h[i] >>> 8) & 0xff, h[i] & 0xff);
  return out;
}

function sha256Hex(value) {
  return hexOf(sha256Bytes(utf8Bytes(value)));
}

function hmacSha256(keyBytes, value) {
  var key = keyBytes.slice();
  if (key.length > 64) key = sha256Bytes(key);
  while (key.length < 64) key.push(0);
  var inner = [], outer = [];
  for (var i = 0; i < 64; i++) {
    inner.push(key[i] ^ 0x36);
    outer.push(key[i] ^ 0x5c);
  }
  return sha256Bytes(outer.concat(sha256Bytes(inner.concat(utf8Bytes(value)))));
}

// The signing key is XOR-derived from the encoded material and the certificate digest.
var SIGNING_KEY = (function () {
  var encoded = hexBytes(ENCODED_KEY_HEX), key = [];
  for (var i = 0; i < 40; i++) {
    key.push((encoded[i] ^ encoded[40] ^ ((i * 7) & 0xff) ^ 0xa5 ^ CERT_SHA256.charCodeAt(i)) & 0xff);
  }
  return key;
})();

// ---------------------------------------------------------------- aes-128-cbc

var SBOX = [
  0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
  0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
  0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
  0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
  0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
  0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
  0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
  0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
  0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
  0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
  0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
  0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
  0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
  0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
  0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
  0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16
];

var INV_SBOX = new Array(256);
var MUL = [new Array(256), new Array(256), new Array(256), new Array(256)];

(function () {
  var i, b, x, a, r;
  for (i = 0; i < 256; i++) INV_SBOX[SBOX[i]] = i;
  for (i = 0; i < 256; i++) {
    var factors = [9, 11, 13, 14];
    for (var set = 0; set < 4; set++) {
      a = i;
      b = factors[set];
      r = 0;
      for (x = 0; x < 8; x++) {
        if (b & 1) r ^= a;
        var high = a & 0x80;
        a = (a << 1) & 0xff;
        if (high) a ^= 0x1b;
        b >>= 1;
      }
      MUL[set][i] = r;
    }
  }
})();

function expandKey(key) {
  var w = key.slice(), rcon = 1;
  for (var i = 16; i < 176; i += 4) {
    var t0 = w[i - 4], t1 = w[i - 3], t2 = w[i - 2], t3 = w[i - 1];
    if (i % 16 === 0) {
      var first = SBOX[t1] ^ rcon;
      t1 = SBOX[t2];
      t2 = SBOX[t3];
      t3 = SBOX[t0];
      t0 = first;
      var high = rcon & 0x80;
      rcon = (rcon << 1) & 0xff;
      if (high) rcon ^= 0x1b;
    }
    w[i] = w[i - 16] ^ t0;
    w[i + 1] = w[i - 15] ^ t1;
    w[i + 2] = w[i - 14] ^ t2;
    w[i + 3] = w[i - 13] ^ t3;
  }
  return w;
}

function addRoundKey(state, w, round) {
  for (var i = 0; i < 16; i++) state[i] ^= w[round * 16 + i];
}

function invShiftRows(s) {
  var t = s.slice();
  s[1] = t[13]; s[5] = t[1]; s[9] = t[5]; s[13] = t[9];
  s[2] = t[10]; s[6] = t[14]; s[10] = t[2]; s[14] = t[6];
  s[3] = t[7]; s[7] = t[11]; s[11] = t[15]; s[15] = t[3];
}

function invSubBytes(s) {
  for (var i = 0; i < 16; i++) s[i] = INV_SBOX[s[i]];
}

function invMixColumns(s) {
  for (var c = 0; c < 16; c += 4) {
    var a0 = s[c], a1 = s[c + 1], a2 = s[c + 2], a3 = s[c + 3];
    s[c] = MUL[3][a0] ^ MUL[1][a1] ^ MUL[2][a2] ^ MUL[0][a3];
    s[c + 1] = MUL[0][a0] ^ MUL[3][a1] ^ MUL[1][a2] ^ MUL[2][a3];
    s[c + 2] = MUL[2][a0] ^ MUL[0][a1] ^ MUL[3][a2] ^ MUL[1][a3];
    s[c + 3] = MUL[1][a0] ^ MUL[2][a1] ^ MUL[0][a2] ^ MUL[3][a3];
  }
}

function decryptBlock(cipherBlock, w) {
  var state = cipherBlock.slice();
  addRoundKey(state, w, 10);
  for (var round = 9; round >= 1; round--) {
    invShiftRows(state);
    invSubBytes(state);
    addRoundKey(state, w, round);
    invMixColumns(state);
  }
  invShiftRows(state);
  invSubBytes(state);
  addRoundKey(state, w, 0);
  return state;
}

function aesCbcDecrypt(cipherBytes, keyBytes, ivBytes) {
  var w = expandKey(keyBytes);
  var previous = ivBytes.slice();
  var out = [];
  for (var offset = 0; offset < cipherBytes.length; offset += 16) {
    var block = cipherBytes.slice(offset, offset + 16);
    var plain = decryptBlock(block, w);
    for (var i = 0; i < 16; i++) out.push(plain[i] ^ previous[i]);
    previous = block;
  }
  var pad = out[out.length - 1];
  if (pad > 0 && pad <= 16) out = out.slice(0, out.length - pad);
  return out;
}

// ---------------------------------------------------------------- protocol

function text(value) {
  if (value === null || value === undefined) return "";
  var out = typeof value === "string" ? value : String(value);
  out = out.replace(/^\s+|\s+$/g, "");
  return out.toLowerCase() === "null" ? "" : out;
}

function parseValue(value) {
  try {
    return JSON.parse(value);
  } catch (e) {
    return null;
  }
}

/** The server may append a 64-char HMAC trailer over the payload. */
function optionalPayload(value) {
  if (value.length < 64) return value;
  var trailer = value.slice(value.length - 64);
  if (!/^[a-fA-F0-9]{64}$/.test(trailer)) return value;
  var payload = value.slice(0, value.length - 64);
  return hexOf(hmacSha256(SIGNING_KEY, payload)) === trailer.toLowerCase() ? payload : value;
}

function decodeLayer(value, keyText) {
  var parts = value.replace(/^\uFEFF/, "").replace(/\s+$/, "").split(":");
  if (parts.length !== 2) throw new Error("inat: layer");
  var cipher = b64ToBytes(parts[0]);
  var iv = b64ToBytes(parts[1]);
  if (iv.length !== 16 || !cipher.length || cipher.length % 16) throw new Error("inat: layer size");
  var key = utf8Bytes((keyText + "0000000000000000").slice(0, 16));
  return utf8Text(aesCbcDecrypt(cipher, key, iv)).replace(/^\uFEFF/, "");
}

function firstKey(row, keys) {
  for (var i = 0; i < keys.length; i++) {
    if (row[keys[i]] !== undefined && row[keys[i]] !== null && row[keys[i]] !== "null") return row[keys[i]];
  }
  return null;
}

function entryObject(value) {
  var current = value, depth = 0;
  while (typeof current === "string" && depth < 2) {
    if (!text(current)) return {};
    current = parseValue(current);
    if (!current) return {};
    depth++;
  }
  if (!current || typeof current !== "object") return {};
  var rows = current instanceof Array ? current : [current];
  var merged = {};
  for (var i = 0; i < rows.length; i++) {
    var row = rows[i];
    if (!row || typeof row !== "object" || row instanceof Array) continue;
    for (var key in row) {
      if (!Object.prototype.hasOwnProperty.call(row, key)) continue;
      if (key === "__proto__" || key === "constructor" || key === "prototype") continue;
      merged[key] = row[key];
    }
  }
  return merged;
}

function normalizeHeaders(value) {
  var source = entryObject(value), out = {};
  for (var name in source) {
    if (!Object.prototype.hasOwnProperty.call(source, name)) continue;
    var lower = name.toLowerCase(), flat = lower.replace(/-/g, "");
    var normalized = flat === "useragent" ? "user-agent" : flat === "xrequestedwith" ? "x-requested-with" : flat === "referrer" ? "referer" : lower;
    if (FORBIDDEN_HEADERS[normalized]) continue;
    var header = text(source[name]);
    if (header) out[normalized] = header;
  }
  return out;
}

function pathOf(url) {
  var match = /^[a-zA-Z]+:\/\/[^/?#]+([^?#]*)/.exec(url);
  return match && match[1] ? match[1] : "/";
}

function alternateUrl(url) {
  var match = /^(https?:\/\/)([^/?#]+)([^?#]*)(\?[^#]*)?(#.*)?$/i.exec(url);
  if (!match) return null;
  var alternate = HOST_ALTERNATES[match[2].toLowerCase()];
  return alternate ? match[1] + alternate + (match[3] || "/") + (match[4] || "") + (match[5] || "") : null;
}

// shortcut: no x-st clock-skew correction; the local clock is NTP-synced, revisit if 403s appear.
function withSignature(url, method, headers, body) {
  var out = {}, key;
  for (key in headers) if (Object.prototype.hasOwnProperty.call(headers, key)) out[key] = headers[key];
  var stamp = String(Math.floor(new Date().getTime() / 1000));
  var nonce = hexOf(sha256Bytes(utf8Bytes(stamp + url + String(Math.random()) + String(Math.random()))).slice(0, 16));
  out["x-ts"] = stamp;
  out["x-nc"] = nonce;
  out["x-sg"] = hexOf(hmacSha256(SIGNING_KEY, method + "\n" + pathOf(url) + "\n" + stamp + "\n" + nonce + "\n" + sha256Hex(body)));
  return out;
}

function request(url, method, headers, form, signed) {
  var body = form ? form : "";
  var attempt = function (target) {
    var finalHeaders = signed ? withSignature(target, method, headers, body) : headers;
    return fetch(target, { method: method, headers: finalHeaders, body: form ? body : undefined }).then(function (response) {
      if (!response.ok) {
        var error = new Error("inat: HTTP " + response.status);
        error.status = response.status;
        throw error;
      }
      return response.text();
    });
  };
  return attempt(url).catch(function (error) {
    var alternate = alternateUrl(url);
    if (alternate && (error.status === 403 || error.status === 520)) return attempt(alternate);
    throw error;
  });
}

var listCache = {};
var cacheKeys = [];

function entries(url, host, pin, dataType) {
  if (!url) return Promise.resolve([]);
  var key = url + "|" + dataType;
  if (listCache[key]) return Promise.resolve(listCache[key]);
  var nonce = "";
  if (dataType !== "raw_mode") {
    var alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    for (var i = 0; i < 16; i++) nonce += alphabet.charAt(Math.floor(Math.random() * alphabet.length));
  }
  var load = dataType === "raw_mode"
    ? request(url, "GET", API_HEADERS, null, true).then(function (body) {
        return optionalPayload(decodeLayer(decodeLayer(optionalPayload(body), RAW_RESPONSE_KEY), RAW_RESPONSE_KEY));
      })
    : request(url, "POST", extend(API_HEADERS, { "content-type": "application/x-www-form-urlencoded; charset=UTF-8" }), "1=" + nonce + "&0=" + nonce, true)
        .then(function (body) {
          return optionalPayload(decodeLayer(decodeLayer(optionalPayload(body), nonce), nonce));
        });
  return load.then(function (payload) {
    var parsed = parseValue(payload);
    if (!(parsed instanceof Array)) throw new Error("inat: list payload");
    var rows = [];
    for (var index = 0; index < parsed.length; index++) {
      var row = parsed[index];
      if (row && typeof row === "object" && !(row instanceof Array)) rows.push(parseEntry(row, host, pin, dataType));
    }
    listCache[key] = rows;
    cacheKeys.push(key);
    if (cacheKeys.length > 8) delete listCache[cacheKeys.shift()];
    return rows;
  });
}

function extend(base, extra) {
  var out = {}, key;
  for (key in base) if (Object.prototype.hasOwnProperty.call(base, key)) out[key] = base[key];
  for (key in extra) if (Object.prototype.hasOwnProperty.call(extra, key)) out[key] = extra[key];
  return out;
}

var EPISODE_NAME = /bölüm|bolum|episode/i;
var SEASON_NAME = /sezon|season/i;

function parseEntry(row, host, pin, dataType) {
  var label = text(firstKey(row, ["sezonName", "diziName", "bolumName", "title", "name", "chName", "filmName"]));
  var type = text(firstKey(row, ["chType", "bolumType", "filmType", "diziType", "type"]));
  var rowType = text(row.diziType) || text(row.chType) || text(row.catType);
  var episode = row.bolumName !== undefined || row.bolumUrl !== undefined || rowType === "bolum_mode" || EPISODE_NAME.test(label);
  var season = !episode && (row.sezonName !== undefined || row.sezonUrl !== undefined || rowType === "sezon_mode" || SEASON_NAME.test(label));
  var params = {}, reg = entryObject(firstKey(row, ["chReg", "bolumReg", "filmReg", "diziReg", "reg"]));
  for (var name in reg) {
    if (!Object.prototype.hasOwnProperty.call(reg, name)) continue;
    var value = text(reg[name]);
    if (value) params[name] = value;
  }
  return {
    name: text(firstKey(row, ["chName", "bolumName", "filmName", "diziName", "title", "name"])),
    url: text(firstKey(row, ["chUrl", "bolumUrl", "filmUrl", "diziUrl", "url"])),
    type: type,
    img: text(firstKey(row, ["chImg", "chResim", "filmImg", "filmResim", "diziImg", "diziResim", "img", "resim", "poster"])),
    plot: text(firstKey(row, ["diziDetay", "filmDetay", "chDetay", "detay", "plot", "description"])),
    headers: normalizeHeaders(firstKey(row, ["chHeaders", "bolumHeaders", "filmHeaders", "diziHeaders", "headers"])),
    params: params,
    host: text(firstKey(row, ["chHost", "filmHost", "bolumHost", "diziHost", "host"])) || host,
    pin: text(firstKey(row, ["chSha", "filmSha", "bolumSha", "diziSha", "sha"])) || pin,
    dataType: text(firstKey(row, ["filmDataType", "diziDataType", "chDataType"])) || dataType,
    season: season,
    episode: episode
  };
}

// ---------------------------------------------------------------- resolve

function handlerOf(type) {
  return type ? type.replace(/_(u|s)_mode$/, "_mode") : "";
}

function isHttp(url) {
  return /^https?:\/\//i.test(url);
}

function isResolvable(entry) {
  var handler = handlerOf(entry.type);
  if (handler === "web_mode" || handler === "link_mode") return false;
  if (handler === "SSprDrm_event") return true;
  if (handler.indexOf("SSprDrm") === 0) return false;
  if (TEKLI_HANDLERS[handler]) return true;
  if (handler.indexOf("regex_lb") >= 0) return true;
  if (entry.params.Regex1) return true;
  return MEDIA_URL.test(entry.url);
}

function literalPattern(value) {
  var out = "", index = 0;
  while (index < value.length) {
    var character = value.charAt(index);
    if (character === "\\") {
      index++;
      if (index >= value.length || /[a-zA-Z0-9]/.test(value.charAt(index))) throw new Error("inat: pattern");
      out += value.charAt(index);
    } else {
      if (".*+?^${}()|[]\\".indexOf(character) >= 0) throw new Error("inat: pattern");
      out += character;
    }
    index++;
  }
  return out;
}

function captureFirst(body, pattern) {
  if (!pattern || pattern.length > 512) throw new Error("inat: pattern");
  var marker = pattern.indexOf("(.*?)") >= 0 ? "(.*?)" : "(.*)";
  var index = pattern.indexOf(marker);
  if (index < 0) throw new Error("inat: pattern");
  var prefix = literalPattern(pattern.slice(0, index));
  var suffix = literalPattern(pattern.slice(index + marker.length));
  var greedy = marker === "(.*)";
  var lines = String(body).split(/[\r\n\u2028\u2029]/);
  for (var i = 0; i < lines.length; i++) {
    var line = lines[i];
    var matchStart = prefix ? line.indexOf(prefix) : 0;
    if (matchStart < 0) continue;
    var start = matchStart + prefix.length;
    var end = suffix ? (greedy ? line.lastIndexOf(suffix) : line.indexOf(suffix, start)) : (greedy ? line.length : start);
    if (end >= start) return line.slice(start, end);
  }
  throw new Error("inat: capture");
}

function decodeCapturedUrl(value) {
  var out = value.replace(/^\s+|\s+$/g, "");
  if (out.indexOf("\\") >= 0) out = parseValue('"' + out + '"') || out;
  return out;
}

var FALLBACK_PATTERNS = [
  /https?:\/\/[^\s"'<>]+\.m3u8[^\s"'<>]*/i,
  /https?:\/\/[^\s"'<>]+\.(?:mp4|webm|ts|mkv)[^\s"'<>]*/i,
  /file:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']/i,
  /source:\s*["']([^"']+)["']/i,
  /src:\s*["']([^"']+)["']/i
];

var RESOLVER_PATTERNS = [
  /https?:\/\/[^\s"'<>]+\.m3u8[^\s"'<>]*/i,
  /https?:\/\/[^\s"'<>]+\.(?:mp4|webm|ts|mkv)[^\s"'<>]*/i,
  /["'](https?:\\\/\\\/[^\s"'<>]+\.m3u8[^\s"'<>]*)["']/i
];

function matchUrl(value, patterns) {
  for (var i = 0; i < patterns.length; i++) {
    var match = patterns[i].exec(value);
    if (!match) continue;
    return (match[1] || match[0]).replace(/\\\//g, "/").replace(/^['"]|['"]$/g, "");
  }
  return null;
}

function captureTarget(body, pattern, replace) {
  if (pattern) {
    var captured = null;
    try {
      captured = decodeCapturedUrl(captureFirst(body, pattern));
    } catch (e) {
      captured = null;
    }
    if (captured) {
      var value = replace && replace[0] ? captured.split(replace[0]).join(replace[1]) : captured;
      if (value) return value;
    }
  }
  return matchUrl(body, FALLBACK_PATTERNS);
}

function decodeJson(value) {
  var trimmed = String(value).replace(/^\s+|\s+$/g, "");
  if (trimmed.charAt(0) !== "{" && trimmed.charAt(0) !== "[") return null;
  var candidates = [optionalPayload(trimmed), trimmed];
  for (var i = 0; i < candidates.length; i++) {
    var parsed = parseValue(candidates[i]);
    if (parsed && typeof parsed === "object") return parsed;
  }
  return null;
}

function targetOf(resolved) {
  var keys = ["chUrl", "url", "stream", "file", "src", "link", "video", "hls"];
  for (var i = 0; i < keys.length; i++) if (text(resolved[keys[i]])) return text(resolved[keys[i]]);
  if (resolved.data && typeof resolved.data === "object") {
    if (text(resolved.data.url)) return text(resolved.data.url);
    if (text(resolved.data.stream)) return text(resolved.data.stream);
  }
  return null;
}

function decodeResolver(value, configuration) {
  var trimmed = String(value).replace(/^\s+|\s+$/g, "");
  var parsed = decodeJson(trimmed);
  if (parsed) return parsed;
  if (/^https?:\/\//.test(trimmed)) return { chUrl: trimmed };
  var direct = matchUrl(trimmed, RESOLVER_PATTERNS);
  if (direct) return { chUrl: direct };
  var keys = [], candidates = [configuration.Regex1, configuration.Regex2, configuration.Regex2p, RAW_RESPONSE_KEY];
  for (var i = 0; i < candidates.length; i++) {
    var key = text(candidates[i]);
    if (key && keys.indexOf(key) < 0) keys.push(key);
  }
  for (var a = 0; a < keys.length; a++) {
    var inner = null;
    try {
      inner = decodeLayer(trimmed, keys[a]).replace(/^\s+|\s+$/g, "");
    } catch (e) {
      inner = null;
    }
    if (!inner) continue;
    parsed = decodeJson(inner);
    if (parsed) return parsed;
    if (/^https?:\/\//.test(inner)) return { chUrl: inner };
    for (var b = 0; b < keys.length; b++) {
      var second = null;
      try {
        second = decodeLayer(inner, keys[b]).replace(/^\s+|\s+$/g, "");
      } catch (e) {
        second = null;
      }
      if (!second) continue;
      parsed = decodeJson(second);
      if (parsed) return parsed;
    }
  }
  throw new Error("inat: resolver");
}

/** Returns {url, headers} for a playable stream, or null when only an embed page exists. */
function resolve(entry) {
  if (!entry.url) return Promise.reject(new Error("inat: no url"));
  if (!isHttp(entry.url)) return Promise.resolve(null);
  var handler = handlerOf(entry.type);
  var headers = extend(entry.headers, { "cache-control": "no-cache" });
  if (handler === "web_mode" || handler === "link_mode") return Promise.resolve(null);
  if (handler.indexOf("SSprDrm") === 0) return Promise.resolve(null);
  if (TEKLI_HANDLERS[handler]) {
    return request(entry.url, "GET", headers, null, false).then(function (body) {
      var replace = handler === "tekli_regex_replace_mode" ? [entry.params.Regex2 || "", entry.params.Regex3 || ""] : null;
      var target = captureTarget(body, entry.params.Regex1, replace);
      return target && isHttp(target) ? { url: target, headers: entry.headers } : null;
    });
  }
  if (handler.indexOf("regex_lb") >= 0 || entry.params.Regex1) {
    return request(entry.url, "GET", headers, null, true).then(function (body) {
      var resolved = decodeResolver(body, entry.params);
      var target = targetOf(resolved);
      if (!target || !isHttp(target)) return null;
      var mode = text(resolved.chType) || text(resolved.type) || text(resolved.mode);
      if (mode === "web_mode" || target.indexOf("/embed/") >= 0 || target.indexOf("player.php") >= 0) return null;
      return { url: target, headers: extend(entry.headers, normalizeHeaders(resolved.chHeaders || resolved.headers || resolved.playHeaders)) };
    });
  }
  if (MEDIA_URL.test(entry.url)) return Promise.resolve({ url: entry.url, headers: entry.headers });
  return Promise.resolve(null);
}

// ---------------------------------------------------------------- catalogue walk

function seasonNumber(name) {
  var match = /(\d+)\s*[.\-]?\s*(?:sezon|season)/i.exec(name);
  return match ? parseInt(match[1], 10) : 0;
}

function episodeNumber(name) {
  var match = /(\d+)\s*[.\-]?\s*(?:bölüm|bolum|episode)/i.exec(name);
  return match ? parseInt(match[1], 10) : 0;
}

function pickSeason(rows, wanted) {
  var usable = [];
  for (var i = 0; i < rows.length; i++) {
    if (!rows[i].url) continue;
    usable.push({ row: rows[i], number: seasonNumber(rows[i].name) || i + 1 });
  }
  for (var a = 0; a < usable.length; a++) if (usable[a].number === wanted) return usable[a].row;
  return usable[wanted - 1] ? usable[wanted - 1].row : usable[0].row;
}

function pickEpisode(rows, wanted) {
  var usable = rows.filter(function (row) { return row.url && isHttp(row.url) && isResolvable(row); });
  if (!usable.length) usable = rows.filter(function (row) { return row.url && isHttp(row.url); });
  var numbered = [];
  for (var i = 0; i < usable.length; i++) {
    if (episodeNumber(usable[i].name) === wanted) numbered.push(usable[i]);
  }
  if (numbered.length) return numbered;
  if (usable[wanted - 1]) return [usable[wanted - 1]];
  return usable.length ? [usable[0]] : [];
}

/** Walks index -> series -> seasons -> episodes -> resolvable rows. */
function collect(entry, wantedSeason, wantedEpisode, depth) {
  if (depth > 4) return Promise.resolve([]);
  if (isResolvable(entry)) return Promise.resolve([entry]);
  return entries(entry.url, entry.host, entry.pin, entry.dataType).then(function (children) {
    if (!children.length) return [];
    var seasonRows = children.filter(function (row) { return row.season && row.url && !isResolvable(row); });
    if (seasonRows.length) {
      var season = pickSeason(seasonRows, wantedSeason);
      return entries(season.url, season.host, season.pin, season.dataType).then(function (episodes) {
        return pickEpisode(episodes, wantedEpisode);
      });
    }
    var resolvable = children.filter(isResolvable);
    if (resolvable.length) {
      if (CFG.kind === "movie") return resolvable.slice(0, 3);
      return pickEpisode(children, wantedEpisode);
    }
    return collect(children[0], wantedSeason, wantedEpisode, depth + 1);
  }).catch(function () {
    return [];
  });
}

// ---------------------------------------------------------------- title match

function fold(value) {
  var out = String(value || "").toLowerCase();
  // strip combining marks (Siccîn -> siccin); Turkish dotless i is not decomposable.
  try {
    out = out.normalize("NFD").replace(/[̀-ͯ]/g, "");
  } catch (e) {
    out = out.replace(/[îÎ]/g, "i").replace(/[âÂ]/g, "a").replace(/[ûÛ]/g, "u");
  }
  return out
    .replace(/ı/g, "i").replace(/ş/g, "s").replace(/ğ/g, "g")
    .replace(/ü/g, "u").replace(/ö/g, "o").replace(/ç/g, "c")
    .replace(/[^a-z0-9]+/g, " ")
    .replace(/^\s+|\s+$/g, "");
}

function cleanName(value) {
  return String(value || "")
    .replace(/\s*-\s*\(yeni\)\s*$/i, "")
    .replace(/\s*\((?:19|20)\d\d\)\s*$/g, "")
    .replace(/\s+-\s*\(yeni\)/gi, "")
    .replace(/\s+/g, " ")
    .replace(/^\s+|\s+$/g, "");
}

function rankHits(rows, title) {
  var wanted = fold(cleanName(title)), scored = [];
  if (!wanted) return [];
  for (var i = 0; i < rows.length; i++) {
    var row = rows[i];
    if (!row.url || !isHttp(row.url) || row.season || row.episode) continue;
    var name = fold(cleanName(row.name));
    if (!name) continue;
    var score;
    if (name === wanted) score = 0;
    else if (name.indexOf(wanted + " ") === 0) score = 1;
    else if ((" " + name + " ").indexOf(" " + wanted + " ") >= 0) score = 2;
    else continue;
    scored.push({ row: row, score: score, length: name.length });
  }
  scored.sort(function (left, right) {
    return left.score - right.score || left.length - right.length;
  });
  var out = [], seen = {};
  for (var a = 0; a < scored.length && out.length < 3; a++) {
    if (seen[scored[a].row.url]) continue;
    seen[scored[a].row.url] = 1;
    out.push(scored[a].row);
  }
  return out;
}

/** Nuvio may hand over a numeric TMDB id; Cinemeta only knows IMDb, so map it first. */
function tmdbToImdb(id, mediaType) {
  var prop = mediaType === "tv" ? "P4983" : "P4947";
  var query = 'SELECT ?imdb WHERE { ?s wdt:' + prop + ' "' + id + '" . ?s wdt:P345 ?imdb } LIMIT 1';
  return fetch("https://query.wikidata.org/sparql?format=json&query=" + encodeURIComponent(query), {
    headers: { "user-agent": WIKIDATA_UA, accept: "application/sparql-results+json" }
  }).then(function (response) {
    return response.ok ? response.json() : null;
  }).then(function (payload) {
    var rows = payload && payload.results && payload.results.bindings;
    var value = rows && rows.length ? String((rows[0].imdb || {}).value || "") : "";
    return /^tt\d+$/i.test(value) ? value : "";
  }).catch(function () {
    return "";
  });
}

function imdbId(id, mediaType) {
  var value = String(id === null || id === undefined ? "" : id).replace(/^\s+|\s+$/g, "");
  if (/^tt\d+$/i.test(value)) return Promise.resolve(value);
  return /^\d+$/.test(value) ? tmdbToImdb(value, mediaType) : Promise.resolve("");
}

function cinemetaTitle(id, mediaType) {
  var kind = mediaType === "tv" ? "series" : "movie";
  return fetch("https://v3-cinemeta.strem.io/meta/" + kind + "/" + encodeURIComponent(id) + ".json", {
    headers: { "user-agent": INAT_UA, accept: "application/json" }
  }).then(function (response) {
    return response.ok ? response.json() : null;
  }).then(function (payload) {
    return payload && payload.meta ? String(payload.meta.name || "").replace(/^\s+|\s+$/g, "") : "";
  }).catch(function () {
    return "";
  });
}

// ---------------------------------------------------------------- entry point

function mediaHeaders(headers) {
  var out = {
    "User-Agent": headers["user-agent"] || INAT_UA,
    "Referer": headers["referer"] || CFG.home + "/",
    "X-Requested-With": headers["x-requested-with"] || ("XMLHttp" + "Request")
  };
  if (headers["origin"]) out["Origin"] = headers["origin"];
  return out;
}

function chain(items, worker) {
  var promise = Promise.resolve([]);
  items.forEach(function (item) {
    promise = promise.then(function (accumulated) {
      return worker(item).then(function (rows) {
        return accumulated.concat(rows);
      }, function () {
        return accumulated;
      });
    });
  });
  return promise;
}

function getStreams(tmdbId, mediaType, season, episode) {
  var wantedSeason = parseInt(season, 10) || 1;
  var wantedEpisode = parseInt(episode, 10) || 1;
  return imdbId(tmdbId, mediaType).then(function (id) {
    if (!id) return [];
    return cinemetaTitle(id, mediaType).then(function (title) {
      if (!title) return [];
      return entries(CFG.url, CFG.host, CFG.sha, CFG.dataType).then(function (rows) {
        var hits = rankHits(rows, title);
        if (!hits.length) return [];
        return chain(hits, function (hit) {
          return collect(hit, wantedSeason, wantedEpisode, 0).then(function (leaves) {
            return chain(leaves.slice(0, 3), function (leaf) {
              return resolve(leaf).then(function (stream) {
                if (!stream || !stream.url) return [];
                return [{
                  name: CFG.name,
                  title: CFG.name + " - " + (leaf.name || title),
                  url: stream.url,
                  quality: "auto",
                  headers: mediaHeaders(stream.headers || leaf.headers || {}),
                  subtitles: []
                }];
              });
            });
          });
        });
      });
    });
  }).catch(function (e) {
    console.log("[bb-nuvio][inatgain] okuma akışı hata verdi: " + (e && e.message ? e.message : e));
    return [];
  });
}

module.exports = { getStreams };
