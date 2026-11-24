// @provider {"id":"dizigecesi","name":"Dizigecesi","types":["movie"],"domain":"dizigecesi.com","formats":["m3u8"],"sample":"tt0107120:movie:1:1","description":"Dizigecesi - film arsivi (Filemoon Byse HLS + Vidmoly).","verified":true,"version":"1.0.0"}
/**
 * dizigecesi — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /arama/<q> -> film page -> [data-embed] -> POST /ajax/embed ->
 *       wrapper iframe -> Filemoon /e/<code> (Byse P-256 + PoW + AES-256-GCM) or Vidmoly HLS.
 */

(function () {
var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://dizigecesi.com";
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

function origin(url) {
  var m = /^(https?:\/\/[^/]+)/.exec(String(url || ""));
  return m ? m[1] : "";
}

function abs(base, href) {
  if (!href) return null;
  href = String(href).replace(/&amp;/g, "&").trim();
  if (/^https?:/i.test(href)) return href;
  if (href.slice(0, 2) === "//") return "https:" + href;
  var root = origin(base);
  if (href.charAt(0) === "/") return root ? root + href : href;
  return String(base || BASE).replace(/[^/]*$/, "") + href;
}

function hlsNoDownload(url) {
  var u = String(url || "");
  if (/\.m3u8(\?|$)/i.test(u) || u.indexOf("#") !== -1) return u;
  return u + "#.m3u8";
}

function b64(value) {
  var raw = String(value || "").replace(/[^A-Za-z0-9+/=]/g, "");
  var out = "", buf = 0, bits = 0;
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

function b64urlEncode(inBytes) {
  var out = "";
  for (var i = 0; i < inBytes.length; i += 3) {
    var b0 = inBytes[i] & 0xff;
    var b1 = i + 1 < inBytes.length ? inBytes[i + 1] & 0xff : 0;
    var b2 = i + 2 < inBytes.length ? inBytes[i + 2] & 0xff : 0;
    out += B64.charAt(b0 >> 2);
    out += B64.charAt(((b0 & 3) << 4) | (b1 >> 4));
    out += i + 1 < inBytes.length ? B64.charAt(((b1 & 15) << 2) | (b2 >> 6)) : "";
    out += i + 2 < inBytes.length ? B64.charAt(b2 & 63) : "";
  }
  return out.replace(/\+/g, "-").replace(/\//g, "_");
}

function bytes(value) {
  var s = String(value || "");
  var out = [];
  for (var i = 0; i < s.length; i++) out.push(s.charCodeAt(i) & 0xff);
  return out;
}

function b64urlDecode(value) {
  var s = String(value || "").replace(/-/g, "+").replace(/_/g, "/");
  return bytes(b64(s));
}

function fromUtf8(b) {
  var out = "", i = 0;
  while (i < b.length) {
    var c = b[i++];
    if (c < 0x80) out += String.fromCharCode(c);
    else if (c < 0xe0) out += String.fromCharCode(((c & 0x1f) << 6) | (b[i++] & 0x3f));
    else if (c < 0xf0) out += String.fromCharCode(((c & 0x0f) << 12) | ((b[i++] & 0x3f) << 6) | (b[i++] & 0x3f));
    else {
      var cp = ((c & 0x07) << 18) | ((b[i++] & 0x3f) << 12) | ((b[i++] & 0x3f) << 6) | (b[i++] & 0x3f);
      cp -= 0x10000;
      out += String.fromCharCode(0xd800 + (cp >> 10), 0xdc00 + (cp & 0x3ff));
    }
  }
  return out;
}

function randomBytes(size) {
  var out = [];
  for (var i = 0; i < size; i++) out.push(Math.floor(Math.random() * 256));
  return out;
}

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
function ror(x, n) { return (x >>> n) | (x << (32 - n)); }

function sha256(inBytes) {
  var msg = inBytes.slice(), len = inBytes.length;
  msg.push(0x80);
  while (msg.length % 64 !== 56) msg.push(0);
  var bits = len * 8;
  for (var hb = 3; hb >= 0; hb--) msg.push((Math.floor(bits / 4294967296) >>> (8 * hb)) & 0xff);
  for (var b = 3; b >= 0; b--) msg.push((bits >>> (8 * b)) & 0xff);
  var h0 = 0x6a09e667, h1 = 0xbb67ae85, h2 = 0x3c6ef372, h3 = 0xa54ff53a;
  var h4 = 0x510e527f, h5 = 0x9b05688c, h6 = 0x1f83d9ab, h7 = 0x5be0cd19;
  var w = [];
  for (var off = 0; off < msg.length; off += 64) {
    for (var i = 0; i < 16; i++) w[i] = (msg[off + 4 * i] << 24) | (msg[off + 4 * i + 1] << 16) | (msg[off + 4 * i + 2] << 8) | msg[off + 4 * i + 3];
    for (var t = 16; t < 64; t++) {
      var s0 = ror(w[t - 15], 7) ^ ror(w[t - 15], 18) ^ (w[t - 15] >>> 3);
      var s1 = ror(w[t - 2], 17) ^ ror(w[t - 2], 19) ^ (w[t - 2] >>> 10);
      w[t] = (w[t - 16] + s0 + w[t - 7] + s1) | 0;
    }
    var a = h0, bb = h1, c = h2, d = h3, e = h4, f = h5, g = h6, h = h7;
    for (var r = 0; r < 64; r++) {
      var S1 = ror(e, 6) ^ ror(e, 11) ^ ror(e, 25);
      var ch = (e & f) ^ (~e & g);
      var t1 = (h + S1 + ch + K256[r] + w[r]) | 0;
      var S0 = ror(a, 2) ^ ror(a, 13) ^ ror(a, 22);
      var maj = (a & bb) ^ (a & c) ^ (bb & c);
      var t2 = (S0 + maj) | 0;
      h = g; g = f; f = e; e = (d + t1) | 0; d = c; c = bb; bb = a; a = (t1 + t2) | 0;
    }
    h0 = (h0 + a) | 0; h1 = (h1 + bb) | 0; h2 = (h2 + c) | 0; h3 = (h3 + d) | 0;
    h4 = (h4 + e) | 0; h5 = (h5 + f) | 0; h6 = (h6 + g) | 0; h7 = (h7 + h) | 0;
  }
  var out = [];
  [h0, h1, h2, h3, h4, h5, h6, h7].forEach(function (v) {
    out.push((v >>> 24) & 0xff, (v >>> 16) & 0xff, (v >>> 8) & 0xff, v & 0xff);
  });
  return out;
}

var SBOX = [];
(function () {
  var p = 1, q = 1;
  do {
    p = (p ^ (p << 1) ^ (p & 0x80 ? 0x1b : 0)) & 0xff;
    q ^= q << 1; q ^= q << 2; q ^= q << 4; q &= 0xff;
    if (q & 0x80) q ^= 0x09;
    var x = q ^ ((q << 1) | (q >> 7)) ^ ((q << 2) | (q >> 6)) ^ ((q << 3) | (q >> 5)) ^ ((q << 4) | (q >> 4));
    SBOX[p] = (x ^ 0x63) & 0xff;
  } while (p !== 1);
  SBOX[0] = 0x63;
})();

var RCON = [0, 0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1b, 0x36];
function xt(a) { return ((a << 1) ^ (a & 0x80 ? 0x1b : 0)) & 0xff; }
function gm(a, b) { var r = 0; while (b) { if (b & 1) r ^= a; a = xt(a); b >>= 1; } return r; }

function expandKey(key) {
  var nk = 8, w = [], i, t;
  for (i = 0; i < nk; i++) w[i] = ((key[4 * i] << 24) | (key[4 * i + 1] << 16) | (key[4 * i + 2] << 8) | key[4 * i + 3]) >>> 0;
  for (i = nk; i < 60; i++) {
    t = w[i - 1];
    if (i % nk === 0) {
      t = (t << 8) | (t >>> 24);
      t = ((SBOX[t >>> 24] << 24) | (SBOX[(t >>> 16) & 0xff] << 16) | (SBOX[(t >>> 8) & 0xff] << 8) | SBOX[t & 0xff]) >>> 0;
      t = (t ^ (RCON[i / nk] << 24)) >>> 0;
    } else if (i % nk === 4) {
      t = ((SBOX[t >>> 24] << 24) | (SBOX[(t >>> 16) & 0xff] << 16) | (SBOX[(t >>> 8) & 0xff] << 8) | SBOX[t & 0xff]) >>> 0;
    }
    w[i] = (w[i - nk] ^ t) >>> 0;
  }
  return w;
}

function addRoundKey(s, w, round) {
  for (var c = 0; c < 4; c++) {
    var k = w[round * 4 + c];
    s[4 * c] ^= (k >>> 24) & 0xff;
    s[4 * c + 1] ^= (k >>> 16) & 0xff;
    s[4 * c + 2] ^= (k >>> 8) & 0xff;
    s[4 * c + 3] ^= k & 0xff;
  }
}
function shiftRows(s) {
  for (var r = 1; r < 4; r++) {
    var row = [s[r], s[r + 4], s[r + 8], s[r + 12]];
    for (var i = 0; i < 4; i++) s[r + 4 * ((i - r + 4) % 4)] = row[i];
  }
}
function mixColumns(s) {
  for (var c = 0; c < 4; c++) {
    var i = 4 * c, a = s[i], b = s[i + 1], d = s[i + 2], e = s[i + 3];
    s[i] = gm(a, 2) ^ gm(b, 3) ^ d ^ e;
    s[i + 1] = a ^ gm(b, 2) ^ gm(d, 3) ^ e;
    s[i + 2] = a ^ b ^ gm(d, 2) ^ gm(e, 3);
    s[i + 3] = gm(a, 3) ^ b ^ d ^ gm(e, 2);
  }
}
function encryptBlock(w, block) {
  var s = block.slice(0, 16);
  addRoundKey(s, w, 0);
  for (var round = 1; round <= 14; round++) {
    for (var i = 0; i < 16; i++) s[i] = SBOX[s[i]];
    shiftRows(s);
    if (round !== 14) mixColumns(s);
    addRoundKey(s, w, round);
  }
  return s;
}
function gmul128(x, y) {
  var z = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0], v = x.slice();
  for (var i = 0; i < 128; i++) {
    if ((y[i >> 3] >> (7 - (i & 7))) & 1) for (var j = 0; j < 16; j++) z[j] ^= v[j];
    var lsb = v[15] & 1;
    for (var k = 15; k > 0; k--) v[k] = ((v[k] >>> 1) | ((v[k - 1] & 1) << 7)) & 0xff;
    v[0] = (v[0] >>> 1) & 0xff;
    if (lsb) v[0] ^= 0xe1;
  }
  return z;
}
function aesGcmDecrypt(key, iv, data) {
  var w = expandKey(key);
  var h = encryptBlock(w, [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
  var ctr = iv.slice();
  while (ctr.length < 12) ctr.push(0);
  ctr = ctr.slice(0, 12).concat([0, 0, 0, 1]);
  var payload = data.slice();
  var tag = payload.splice(payload.length - 16, 16);
  var out = [], counter = ctr.slice();
  for (var pre = 15; pre >= 0; pre--) { counter[pre] = (counter[pre] + 1) & 0xff; if (counter[pre] !== 0) break; }
  for (var off = 0; off < payload.length; off += 16) {
    var ks = encryptBlock(w, counter);
    for (var b = 0; b < 16 && off + b < payload.length; b++) out.push(payload[off + b] ^ ks[b]);
    for (var up = 15; up >= 0; up--) { counter[up] = (counter[up] + 1) & 0xff; if (counter[up] !== 0) break; }
  }
  var y = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0];
  for (var p = 0; p < payload.length; p += 16) {
    var block = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0];
    for (var q = 0; q < 16 && p + q < payload.length; q++) block[q] = payload[p + q];
    for (var m = 0; m < 16; m++) y[m] ^= block[m];
    y = gmul128(y, h);
  }
  var lens = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0], bits = payload.length * 8;
  lens[12] = (bits >>> 24) & 0xff; lens[13] = (bits >>> 16) & 0xff; lens[14] = (bits >>> 8) & 0xff; lens[15] = bits & 0xff;
  for (var m2 = 0; m2 < 16; m2++) y[m2] ^= lens[m2];
  y = gmul128(y, h);
  var mask = encryptBlock(w, ctr);
  for (var v2 = 0; v2 < 16; v2++) if ((y[v2] ^ mask[v2]) !== tag[v2]) return null;
  return out;
}

var P256 = BigInt("0xffffffff00000001000000000000000000000000ffffffffffffffffffffffff");
var P256A = P256 - 3n;
var P256N = BigInt("0xffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551");
var GX = BigInt("0x6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296");
var GY = BigInt("0x4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5");

function mod(a, m) { var r = a % m; return r < 0n ? r + m : r; }
function invMod(a, m) {
  var oldR = m, r = mod(a, m), oldS = 0n, s = 1n;
  while (r !== 0n) {
    var q = oldR / r, tr = oldR - q * r; oldR = r; r = tr;
    var ts = oldS - q * s; oldS = s; s = ts;
  }
  return mod(oldS, m);
}
function pointAdd(p, q) {
  if (p === null) return q;
  if (q === null) return p;
  if (p[0] === q[0]) {
    if (mod(p[1] + q[1], P256) === 0n) return null;
    var l = mod((3n * p[0] * p[0] + P256A) * invMod(2n * p[1], P256), P256);
    var x = mod(l * l - 2n * p[0], P256);
    return [x, mod(l * (p[0] - x) - p[1], P256)];
  }
  var slope = mod((q[1] - p[1]) * invMod(q[0] - p[0], P256), P256);
  var x2 = mod(slope * slope - p[0] - q[0], P256);
  return [x2, mod(slope * (p[0] - x2) - p[1], P256)];
}
function scalarMult(k, point) {
  var result = null, addend = point, n = k;
  while (n > 0n) { if (n & 1n) result = pointAdd(result, addend); addend = pointAdd(addend, addend); n >>= 1n; }
  return result;
}
function bytesToBig(inBytes) {
  var hex = "";
  for (var i = 0; i < inBytes.length; i++) hex += (inBytes[i] < 16 ? "0" : "") + inBytes[i].toString(16);
  return BigInt("0x" + (hex || "0"));
}
function bigToBytes(value, size) {
  var hex = value.toString(16);
  while (hex.length < size * 2) hex = "0" + hex;
  var out = [];
  for (var i = 0; i < hex.length; i += 2) out.push(parseInt(hex.substr(i, 2), 16));
  return out.slice(out.length - size);
}
function newKeyPair() {
  var d = mod(bytesToBig(randomBytes(32)), P256N);
  if (d === 0n) d = 1n;
  var pub = scalarMult(d, [GX, GY]);
  return { d: d, x: pub[0], y: pub[1] };
}
function ecdsaSign(d, messageBytes) {
  var z = bytesToBig(sha256(messageBytes));
  for (var attempt = 0; attempt < 64; attempt++) {
    var k = mod(bytesToBig(randomBytes(32)), P256N);
    if (k === 0n) continue;
    var r = mod(scalarMult(k, [GX, GY])[0], P256N);
    if (r === 0n) continue;
    var s = mod(invMod(k, P256N) * (z + r * d), P256N);
    if (s === 0n) continue;
    return bigToBytes(r, 32).concat(bigToBytes(s, 32));
  }
  return null;
}

var IMUL = Math.imul, CLZ32 = Math.clz32, Int32 = Int32Array, Uint8 = Uint8Array;
function rotl(v, n) { return (v << n) | (v >>> (32 - n)); }
function powMix(state) {
  state[0] = (state[0] + state[1]) | 0; state[3] = rotl(state[3] ^ state[0], 16);
  state[2] = (state[2] + state[3]) | 0; state[1] = rotl(state[1] ^ state[2], 12);
  state[0] = (state[0] + state[1]) | 0; state[3] = rotl(state[3] ^ state[0], 8);
  state[2] = (state[2] + state[3]) | 0; state[1] = rotl(state[1] ^ state[2], 7);
}
function powDigest(input) {
  var state = new Int32(4);
  state[0] = 1779033703; state[1] = 3144134277; state[2] = 1013904242; state[3] = 2773480762;
  var lanes = new Int32(512), out = new Int32(8), i;
  for (i = 0; i < input.length; i++) { state[0] = state[0] + input[i]; state[0] = rotl(state[0], 7); powMix(state); }
  for (i = 0; i < 8; i++) powMix(state);
  for (i = 0; i < 512; i++) { powMix(state); lanes[i] = state[0] ^ state[2]; }
  for (var round = 0; round < 2; round++) {
    for (var slot = 0; slot < 512; slot++) {
      var value = lanes[slot] + lanes[lanes[slot] & 511];
      value = rotl(value, 13);
      value = value ^ IMUL(lanes[(slot + 1) & 511], 2654435761);
      lanes[slot] = value; state[0] = state[0] ^ value; powMix(state);
    }
  }
  for (var index = 0; index < 8; index++) {
    powMix(state);
    var acc = state[0];
    for (var offset = 0; offset < 64; offset++) {
      var lane = lanes[index * 64 + offset];
      acc = acc + lane; acc = rotl(acc, 5); acc = acc ^ IMUL(lane, 2246822519);
    }
    out[index] = acc ^ state[2];
  }
  return out;
}
function leadingZeroBits(digest) {
  var total = 0;
  for (var i = 0; i < digest.length; i++) {
    if (digest[i] === 0) { total += 32; continue; }
    return total + CLZ32(digest[i]);
  }
  return total;
}
function solvePow(nonce, difficulty) {
  if (!nonce) return null;
  if (difficulty <= 0) return "0";
  var prefix = [];
  for (var i = 0; i < nonce.length; i++) prefix.push(nonce.charCodeAt(i) & 0xff);
  prefix.push(58);
  for (var attempt = 0; attempt < 4000000; attempt++) {
    var digits = String(attempt);
    var input = new Uint8(prefix.length + digits.length);
    input.set(prefix, 0);
    for (var d = 0; d < digits.length; d++) input[prefix.length + d] = digits.charCodeAt(d);
    if (leadingZeroBits(powDigest(input)) >= difficulty) return String(attempt);
  }
  return null;
}

function playerHeaders(embed, pageUrl) {
  return {
    "User-Agent": UA,
    Accept: "*/*",
    Referer: BASE + "/",
    "x-embed-origin": "dizigecesi.com",
    "x-embed-parent": embed,
    "x-embed-referer": pageUrl || (BASE + "/")
  };
}
function postJson(url, body, headers, extra) {
  var all = { "Content-Type": "application/json" };
  for (var k in headers) all[k] = headers[k];
  if (extra) for (var j in extra) all[j] = extra[j];
  return text(url, { method: "POST", headers: all, referer: BASE + "/", body: JSON.stringify(body || {}) })
    .then(function (raw) { try { return JSON.parse(raw); } catch (e) { return null; } });
}
function attestation(challenge) {
  var nonce = challenge && challenge.nonce;
  if (!nonce) return null;
  var pair = newKeyPair();
  var signature = ecdsaSign(pair.d, bytes(nonce));
  if (!signature) return null;
  return {
    viewer_id: b64urlEncode(randomBytes(16)),
    device_id: b64urlEncode(randomBytes(16)),
    challenge_id: challenge.challenge_id,
    nonce: nonce,
    signature: b64urlEncode(signature),
    public_key: { crv: "P-256", ext: true, key_ops: ["verify"], kty: "EC", x: b64urlEncode(bigToBytes(pair.x, 32)), y: b64urlEncode(bigToBytes(pair.y, 32)) },
    client: { user_agent: UA },
    storage: {},
    attributes: { entropy: "high" }
  };
}
function playbackPayload(playback) {
  var parts = playback && playback.key_parts;
  if (!parts || !parts.length) return null;
  var version = parseInt(playback.version, 10) || 1;
  var firstIdx = Math.max(0, Math.min(parts.length - 1, version - 1));
  var secondIdx = Math.max(0, Math.min(parts.length - 1, 30 - version));
  var key = b64urlDecode(parts[firstIdx]).concat(b64urlDecode(parts[secondIdx]));
  if (key.length !== 32) return null;
  var plain = aesGcmDecrypt(key, b64urlDecode(playback.iv), b64urlDecode(playback.payload));
  if (!plain) return null;
  try { return JSON.parse(fromUtf8(plain)); } catch (e) { return null; }
}

function loadFilemoon(embedPage, pageUrl) {
  var code = first(/\/e\/([A-Za-z0-9]+)/, embedPage);
  var embedOrigin = origin(embedPage);
  if (!code || !embedOrigin) return Promise.resolve([]);
  var headers = playerHeaders(embedPage, pageUrl);
  return text(embedOrigin + "/api/videos/" + code + "/embed/details", { headers: headers, referer: embedPage })
    .then(function (raw) {
      var details = JSON.parse(raw);
      var apiOrigin = origin(details.embed_frame_url) || embedOrigin;
      var api = apiOrigin + "/api/videos/" + code + "/embed";
      return text(api + "/settings", { headers: headers, referer: details.embed_frame_url || embedPage })
        .then(function (sRaw) {
          var settings = JSON.parse(sRaw);
          if (!settings.captcha_required) return null;
          return postJson(apiOrigin + "/api/videos/access/challenge", {}, headers)
            .then(function (challenge) {
              var body = attestation(challenge);
              if (!body) return null;
              return postJson(apiOrigin + "/api/videos/access/attest", body, headers).then(function (res) {
                if (!res || !res.token) return null;
                return { token: res.token, viewer_id: res.viewer_id || "", device_id: res.device_id || "", confidence: res.confidence || 0 };
              });
            });
        })
        .then(function (fingerprint) {
          return postJson(api + "/captcha", fingerprint ? { fingerprint: fingerprint } : {}, headers).then(function (captcha) {
            if (!captcha || !captcha.pow_token) return [];
            var solution = solvePow(captcha.pow_nonce, parseInt(captcha.pow_difficulty, 10) || 0);
            if (solution === null) return [];
            var vBody = { pow_token: captcha.pow_token, solution: solution };
            if (fingerprint) vBody.fingerprint = fingerprint;
            return postJson(api + "/captcha/verify", vBody, headers).then(function (verified) {
              if (!verified || verified.status !== "ok") return [];
              return postJson(api + "/playback", fingerprint ? { fingerprint: fingerprint } : {}, headers, { "X-Captcha-Token": verified.token });
            }).then(function (response) {
              var payload = response && response.playback ? playbackPayload(response.playback) : null;
              if (!payload) return [];
              var subs = [];
              var tracks = payload.tracks || [];
              for (var i = 0; i < tracks.length; i++) {
                var tr = tracks[i] || {};
                if (!tr.url) continue;
                subs.push({ url: tr.url, language: /eng|ing/i.test(tr.language || tr.title || "") ? "en" : "tr", name: String(tr.title || "Altyazı") + " [Dizigecesi]" });
              }
              var rows = [], sources = payload.sources || [];
              for (var j = 0; j < sources.length; j++) {
                var src = sources[j] || {};
                if (!src.url || !/^https?:/i.test(src.url)) continue;
                var q = first(/(\d{3,4}p)/, src.label || "") || "auto";
                var isHls = src.url.indexOf(".m3u8") !== -1 || /mpegurl/i.test(src.mime_type || "");
                rows.push({
                  name: "Dizigecesi",
                  title: "Dizigecesi - Filemoon " + q + (isHls ? " [HLS]" : " [MP4]"),
                  url: isHls ? hlsNoDownload(src.url) : src.url,
                  quality: q,
                  headers: { Referer: apiOrigin + "/", Origin: apiOrigin, "User-Agent": UA },
                  subtitles: subs.slice(0, 6)
                });
              }
              return rows;
            });
          });
        });
    })
    .catch(function () { return []; });
}

function loadVidmoly(embedUrl, pageUrl) {
  var normUrl = String(embedUrl).replace(/\/\/(?:www\.)?vidmoly\.(?:to|me|sx)\//i, "//vidmoly.biz/");
  return text(normUrl, { referer: pageUrl, headers: { "Sec-Fetch-Dest": "iframe" } })
    .then(function (html) {
      var m3u8 = first(/sources\s*:\s*\[\s*\{\s*file\s*:\s*['"]([^'"]+\.m3u8[^'"]*)['"]/, html) ||
        first(/file\s*:\s*['"](https?:\/\/[^'"]+\.m3u8[^'"]*)['"]/, html);
      if (!m3u8) return [];
      return [{
        name: "Dizigecesi",
        title: "Dizigecesi - Vidmoly [HLS]",
        url: hlsNoDownload(m3u8),
        quality: "auto",
        headers: { Referer: origin(normUrl) + "/", "User-Agent": UA },
        subtitles: []
      }];
    })
    .catch(function () { return []; });
}

function titleOf(id, mediaType) {
  if (/^__alt__:/.test(String(id))) return Promise.resolve(decodeURIComponent(String(id).slice(8)));
  var kind = mediaType === "movie" ? "movie" : "series";
  return text("https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json")
    .then(function (body) { return String((JSON.parse(body).meta || {}).name || "").trim(); })
    .catch(function () { return ""; });
}

function norm(value) {
  return String(value || "")
    .replace(/[\u0131\u0130I]/g, "i").replace(/[\u015f\u015e]/g, "s").replace(/[\u011f\u011e]/g, "g")
    .replace(/[\u00fc\u00dc]/g, "u").replace(/[\u00f6\u00d6]/g, "o").replace(/[\u00e7\u00c7]/g, "c")
    .toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
}

function searchCards(html) {
  var out = [], seen = {};
  var re = /<a[^>]*href="([^"]*\/film\/[^"]+)"[^>]*class="[^"]*card-series[^"]*"[^>]*>([\s\S]*?)<\/a>/g;
  var m;
  while ((m = re.exec(String(html || ""))) !== null) {
    var href = abs(BASE, m[1]);
    var t = first(/card-series__content-title[^>]*>([^<]+)</, m[2]) || first(/alt="([^"]+)"/, m[2]);
    if (!href || !t || seen[href]) continue;
    seen[href] = 1;
    out.push({ url: href, title: t.trim() });
  }
  return out;
}

function pickCard(cards, wantTitle) {
  var want = norm(wantTitle);
  if (!want) return null;
  var best = null;
  for (var i = 0; i < cards.length; i++) {
    var have = norm(cards[i].title);
    if (have === want) return cards[i];
    if (!best && have.indexOf(want + " ") === 0) best = cards[i];
  }
  return best;
}

function getStreams(tmdbId, mediaType, season, episode) {
  if (mediaType === "tv" || mediaType === "series") return Promise.resolve([]);
  var pageUrl = null;

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return null;
      return text(BASE + "/arama/" + encodeURIComponent(title)).then(function (html) {
        return pickCard(searchCards(html), title);
      });
    })
    .then(function (card) {
      if (!card) return null;
      pageUrl = card.url;
      return text(pageUrl, { referer: BASE + "/" });
    })
    .then(function (html) {
      if (!html) return [];
      var ids = [], seen = {};
      var re = /data-embed="([^"]+)"/g, m;
      while ((m = re.exec(html)) !== null) {
        var id = m[1].trim();
        if (id && !seen[id]) { seen[id] = 1; ids.push(id); }
      }
      if (!ids.length) return [];
      var chain = Promise.resolve([]);
      ids.slice(0, 5).forEach(function (id) {
        chain = chain.then(function (acc) {
          if (acc && acc.length) return acc;
          return text(BASE + "/ajax/embed", {
            method: "POST",
            referer: pageUrl,
            headers: { "Content-Type": "application/x-www-form-urlencoded" },
            body: "id=" + encodeURIComponent(id)
          }).then(function (emb) {
            var w = first(/<iframe[^>]+src=["']([^"']+)["']/, emb);
            if (!w) return [];
            var wu = abs(BASE, w);
            return text(wu, { referer: pageUrl }).then(function (wHtml) {
              var inner = (first(/<iframe[^>]+src=["']([^"']+)["']/, wHtml) || wu).replace(/#\d+\.\d+\.\d+\.\d+$/, "");
              inner = abs(wu, inner);
              if (inner.indexOf("filemoon") !== -1) return loadFilemoon(inner, pageUrl);
              if (inner.indexOf("vidmoly") !== -1) return loadVidmoly(inner, pageUrl);
              return [];
            });
          }).catch(function () { return []; });
        });
      });
      return chain;
    })
    .catch(function (e) {
      console.log("[bb-nuvio][dizigecesi] " + (e && e.message));
      return [];
    });
}

module.exports = { getStreams };
})();

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
