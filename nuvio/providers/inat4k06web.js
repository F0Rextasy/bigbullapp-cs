// @provider {"id":"inat4k06web","name":"Inat 4K 06 Web","types":["movie"],"domain":"inatbox","formats":["m3u8","mp4"],"sample":"tt6054846:movie:1:1","description":"Inat 4K Film 06 Web katalogu (filmizlego, exo uzerinden cozulur).","verified":false}
/**
 * inat4k06web — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> Inat list -> season/episode -> signed + AES resolve -> HLS/MP4.
 */

var CFG = {
  "name": "4K Film 06 | Web",
  "url": "https://4k.filmizleeeee.cfd/4k/06/public/catalog-web.php",
  "fallbackUrl": "https://4k.filmizleeeee.cfd/4k/06/public/catalog-exo.php",
  "dataType": "tv_mode",
  "movieList": true,
  "q2160": true,
  "exoSwap": true
};

var RAW_KEY = "a4osa8x1yl4w3vrk";
var ENC_HEX = "4c50054a43216168797045480a046734372e298080c489a2f8a4b8b5819a9f93efb3bbf0f2525a4ca6";
var CERT = "7cc771973665660f0653fce2e9490e48b92be8432a88d74f91703a716f6b4692";
var API_HEADERS = {
  "user-agent": "speedrestapi",
  referer: "https://speedrestapi.com/",
  "x-requested-with": "com.bp.box",
  "cache-control": "no-cache"
};
var MODES = { tv_mode: 1, dizi_mode: 1, sezon_mode: 1, bolum_mode: 1 };
var FORBIDDEN = {
  host: 1, connection: 1, "content-length": 1, "transfer-encoding": 1,
  "proxy-authorization": 1, "proxy-connection": 1, "x-ts": 1, "x-nc": 1, "x-sg": 1, "x-st": 1
};
// Preroll creatives served by the web player pages; never a title stream.
var AD_HOSTS = ["diziroom.com"];
var MEDIA = /\.(?:m3u8|mp4|webm|ts|mkv)(?:[?#].*)?$/i;
var FALLBACKS = [
  /https?:\/\/[^\s"'<>]+\.m3u8[^\s"'<>]*/i,
  /https?:\/\/[^\s"'<>]+\.(?:mp4|webm|ts|mkv)[^\s"'<>]*/i,
  /file:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']/i,
  /source:\s*["']([^"']+)["']/i,
  /src:\s*["']([^"']+)["']/i
];
var RESOLVER_URLS = [
  /https?:\/\/[^\s"'<>]+\.m3u8[^\s"'<>]*/i,
  /https?:\/\/[^\s"'<>]+\.(?:mp4|webm|ts|mkv)[^\s"'<>]*/i,
  /["'](https?:\\\/\\\/[^\s"'<>]+\.m3u8[^\s"'<>]*)["']/i
];

/* ---------- bytes / base64 / hex ---------- */
function hex2b(s) {
  var o = [], i;
  for (i = 0; i + 1 < s.length; i += 2) o.push(parseInt(s.substr(i, 2), 16));
  return o;
}
function b2hex(a) {
  var s = "", i, v;
  for (i = 0; i < a.length; i++) {
    v = a[i] & 255;
    s += (v < 16 ? "0" : "") + v.toString(16);
  }
  return s;
}
function utf8b(s) {
  var o = [], i, c, d;
  for (i = 0; i < s.length; i++) {
    c = s.charCodeAt(i);
    if (c < 0x80) o.push(c);
    else if (c < 0x800) o.push(0xc0 | (c >> 6), 0x80 | (c & 63));
    else if (c >= 0xd800 && c <= 0xdbff && i + 1 < s.length) {
      d = s.charCodeAt(++i);
      c = 0x10000 + ((c - 0xd800) << 10) + (d - 0xdc00);
      o.push(0xf0 | (c >> 18), 0x80 | ((c >> 12) & 63), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
    } else o.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
  }
  return o;
}
function utf8s(a) {
  var s = "", i, c;
  for (i = 0; i < a.length; i++) {
    c = a[i];
    if (c < 0x80) s += String.fromCharCode(c);
    else if (c < 0xe0) s += String.fromCharCode(((c & 31) << 6) | (a[++i] & 63));
    else if (c < 0xf0) s += String.fromCharCode(((c & 15) << 12) | ((a[++i] & 63) << 6) | (a[++i] & 63));
    else {
      c = ((c & 7) << 18) | ((a[++i] & 63) << 12) | ((a[++i] & 63) << 6) | (a[++i] & 63);
      c -= 0x10000;
      s += String.fromCharCode(0xd800 + (c >> 10), 0xdc00 + (c & 1023));
    }
  }
  return s.replace(/^\uFEFF/, "");
}
var B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
function b64b(s) {
  var clean = String(s).replace(/-/g, "+").replace(/_/g, "/").replace(/[^A-Za-z0-9+/=]/g, "");
  var eq = clean.indexOf("=");
  if (eq >= 0) clean = clean.slice(0, eq);
  var out = [], i, v, buf = 0, bits = 0;
  for (i = 0; i < clean.length; i++) {
    v = B64.indexOf(clean.charAt(i));
    if (v < 0) continue;
    buf = (buf << 6) | v;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out.push((buf >> bits) & 255);
    }
  }
  return out;
}

/* ---------- sha256 / hmac ---------- */
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
function rr(x, n) {
  return (x >>> n) | (x << (32 - n));
}
function sha256b(bytes) {
  var h = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
  var m = bytes.slice(), i, off, t1, t2, s0, s1;
  var ml = m.length * 8;
  m.push(0x80);
  while (m.length % 64 !== 56) m.push(0);
  m.push(0, 0, 0, 0, (ml >>> 24) & 255, (ml >>> 16) & 255, (ml >>> 8) & 255, ml & 255);
  var w = new Array(64);
  for (off = 0; off < m.length; off += 64) {
    for (i = 0; i < 16; i++) {
      w[i] = ((m[off + i * 4] << 24) | (m[off + i * 4 + 1] << 16) | (m[off + i * 4 + 2] << 8) | m[off + i * 4 + 3]) | 0;
    }
    for (i = 16; i < 64; i++) {
      s0 = rr(w[i - 15], 7) ^ rr(w[i - 15], 18) ^ (w[i - 15] >>> 3);
      s1 = rr(w[i - 2], 17) ^ rr(w[i - 2], 19) ^ (w[i - 2] >>> 10);
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) | 0;
    }
    var a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], hh = h[7];
    for (i = 0; i < 64; i++) {
      t1 = (hh + (rr(e, 6) ^ rr(e, 11) ^ rr(e, 25)) + ((e & f) ^ (~e & g)) + K256[i] + w[i]) | 0;
      t2 = ((rr(a, 2) ^ rr(a, 13) ^ rr(a, 22)) + ((a & b) ^ (a & c) ^ (b & c))) | 0;
      hh = g;
      g = f;
      f = e;
      e = (d + t1) | 0;
      d = c;
      c = b;
      b = a;
      a = (t1 + t2) | 0;
    }
    h[0] = (h[0] + a) | 0;
    h[1] = (h[1] + b) | 0;
    h[2] = (h[2] + c) | 0;
    h[3] = (h[3] + d) | 0;
    h[4] = (h[4] + e) | 0;
    h[5] = (h[5] + f) | 0;
    h[6] = (h[6] + g) | 0;
    h[7] = (h[7] + hh) | 0;
  }
  var out = [];
  for (i = 0; i < 8; i++) out.push((h[i] >>> 24) & 255, (h[i] >>> 16) & 255, (h[i] >>> 8) & 255, h[i] & 255);
  return out;
}
function sha256hex(s) {
  return b2hex(sha256b(utf8b(s)));
}
function hmacb(key, data) {
  var k = key.slice(), i, ip = [], op = [];
  if (k.length > 64) k = sha256b(k);
  while (k.length < 64) k.push(0);
  for (i = 0; i < 64; i++) {
    ip.push(k[i] ^ 0x36);
    op.push(k[i] ^ 0x5c);
  }
  return sha256b(op.concat(sha256b(ip.concat(data))));
}

/* ---------- aes-128-cbc ---------- */
function gmul(a, b) {
  var p = 0, i, hi;
  for (i = 0; i < 8; i++) {
    if (b & 1) p ^= a;
    hi = a & 0x80;
    a = (a << 1) & 0xff;
    if (hi) a ^= 0x1b;
    b >>= 1;
  }
  return p & 0xff;
}
var SBOX = [], ISBOX = [];
(function () {
  var i, j, inv, s;
  for (i = 0; i < 256; i++) {
    inv = 0;
    if (i) {
      inv = 1;
      for (j = 0; j < 254; j++) inv = gmul(inv, i);
    }
    s = inv;
    for (j = 1; j < 5; j++) s ^= ((inv << j) | (inv >> (8 - j))) & 0xff;
    s ^= 0x63;
    SBOX[i] = s;
    ISBOX[s] = i;
  }
})();
function expandKey(key) {
  var rk = key.slice(), rc = 1, i, t;
  for (i = 16; i < 176; i += 4) {
    t = [rk[i - 4], rk[i - 3], rk[i - 2], rk[i - 1]];
    if (i % 16 === 0) {
      t = [SBOX[t[1]] ^ rc, SBOX[t[2]], SBOX[t[3]], SBOX[t[0]]];
      rc = gmul(rc, 2);
    }
    rk.push(rk[i - 16] ^ t[0], rk[i - 15] ^ t[1], rk[i - 14] ^ t[2], rk[i - 13] ^ t[3]);
  }
  return rk;
}
function addRound(s, rk, round) {
  var i;
  for (i = 0; i < 16; i++) s[i] ^= rk[round * 16 + i];
}
function invShiftRows(s) {
  var r, c, last, t;
  for (r = 1; r < 4; r++) {
    for (c = 0; c < r; c++) {
      last = s[r + 12];
      for (t = 3; t > 0; t--) s[r + t * 4] = s[r + (t - 1) * 4];
      s[r] = last;
    }
  }
}
function invMixColumns(s) {
  var c, i0, i1, i2, i3, o;
  for (c = 0; c < 4; c++) {
    i0 = s[c * 4];
    i1 = s[c * 4 + 1];
    i2 = s[c * 4 + 2];
    i3 = s[c * 4 + 3];
    o = [
      gmul(i0, 14) ^ gmul(i1, 11) ^ gmul(i2, 13) ^ gmul(i3, 9),
      gmul(i0, 9) ^ gmul(i1, 14) ^ gmul(i2, 11) ^ gmul(i3, 13),
      gmul(i0, 13) ^ gmul(i1, 9) ^ gmul(i2, 14) ^ gmul(i3, 11),
      gmul(i0, 11) ^ gmul(i1, 13) ^ gmul(i2, 9) ^ gmul(i3, 14)
    ];
    for (var k = 0; k < 4; k++) s[c * 4 + k] = o[k];
  }
}
function decBlock(block, rk) {
  var s = block.slice(), r, i;
  addRound(s, rk, 10);
  for (r = 9; r >= 1; r--) {
    invShiftRows(s);
    for (i = 0; i < 16; i++) s[i] = ISBOX[s[i]];
    addRound(s, rk, r);
    invMixColumns(s);
  }
  invShiftRows(s);
  for (i = 0; i < 16; i++) s[i] = ISBOX[s[i]];
  addRound(s, rk, 0);
  return s;
}
function aesCbcDec(ct, iv, key) {
  var rk = expandKey(key), out = [], prev = iv, i, j, blk, d;
  if (!ct.length || ct.length % 16) throw new Error("cipher");
  for (i = 0; i < ct.length; i += 16) {
    blk = ct.slice(i, i + 16);
    d = decBlock(blk, rk);
    for (j = 0; j < 16; j++) out.push(d[j] ^ prev[j]);
    prev = blk;
  }
  var pad = out[out.length - 1], i2;
  if (!pad || pad > 16) throw new Error("padding");
  for (i2 = out.length - pad; i2 < out.length; i2++) if (out[i2] !== pad) throw new Error("padding");
  return out.slice(0, out.length - pad);
}
var SIGN_KEY = (function () {
  var b = hex2b(ENC_HEX), out = [], i;
  for (i = 0; i < 40; i++) out.push((b[i] ^ b[40] ^ ((i * 7) & 0xff) ^ 0xa5 ^ CERT.charCodeAt(i)) & 0xff);
  return out;
})();

function decLayer(value, keyText) {
  var parts = String(value).replace(/^\uFEFF/, "").trim().split(":");
  if (parts.length !== 2) throw new Error("layer");
  var ct = b64b(parts[0]), iv = b64b(parts[1]);
  if (iv.length !== 16) throw new Error("layer");
  return utf8s(aesCbcDec(ct, iv, utf8b((keyText + "0000000000000000").slice(0, 16))));
}
function optPayload(v) {
  if (v.length < 64) return v;
  var trailer = v.slice(-64);
  if (!/^[a-fA-F0-9]{64}$/.test(trailer)) return v;
  var payload = v.slice(0, -64);
  return b2hex(hmacb(SIGN_KEY, utf8b(payload))) === trailer.toLowerCase() ? payload : v;
}

/* ---------- http ---------- */
function pathOf(url) {
  var m = /^[a-z]+:\/\/[^/?#]*([^?#]*)/i.exec(url);
  return m && m[1] ? m[1] : "/";
}
function randStr(n) {
  var a = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789", s = "", i;
  for (i = 0; i < n; i++) s += a.charAt(Math.floor(Math.random() * a.length));
  return s;
}
function merge() {
  var out = {}, i, k;
  for (i = 0; i < arguments.length; i++) for (k in arguments[i]) out[k] = arguments[i][k];
  return out;
}
function http(url, opts) {
  var o = opts || {};
  var method = o.method || "GET";
  return fetch(url, { method: method, headers: o.headers || {}, body: method === "GET" ? undefined : o.body }).then(function (r) {
    return r.text().then(function (t) {
      return { code: r.status, text: t, header: function (n) { return r.headers.get(n); } };
    });
  });
}
var clockOffset = 0;
function signed(url, method, headers, body) {
  var path = pathOf(url);
  function attempt(tries) {
    var ts = String(Math.floor(Date.now() / 1000) + clockOffset);
    var nc = b2hex(randomBytes(16));
    var h = merge(headers, {
      "x-ts": ts,
      "x-nc": nc,
      "x-sg": b2hex(hmacb(SIGN_KEY, utf8b(method + "\n" + path + "\n" + ts + "\n" + nc + "\n" + sha256hex(body || ""))))
    });
    return http(url, { method: method, headers: h, body: body }).then(function (r) {
      var st = r.header("x-st");
      if (st && /^\d{1,12}$/.test(st)) {
        var off = parseInt(st, 10) - Math.floor(Date.now() / 1000);
        if (Math.abs(off - clockOffset) > 2) clockOffset = off;
      }
      if (r.code === 403 && tries < 1) return attempt(tries + 1);
      return r;
    });
  }
  return attempt(0);
}
function randomBytes(n) {
  var out = [], i;
  for (i = 0; i < n; i++) out.push(Math.floor(Math.random() * 256));
  return out;
}
function parseJson(value) {
  try {
    var v = JSON.parse(value);
    return v && typeof v === "object" ? v : null;
  } catch (e) {
    return null;
  }
}
function postJson(url) {
  var nonce = randStr(16);
  var body = "1=" + nonce + "&0=" + nonce;
  return signed(url, "POST", merge(API_HEADERS, { "content-type": "application/x-www-form-urlencoded; charset=UTF-8" }), body)
    .then(function (r) {
      if (r.code < 200 || r.code > 299) throw new Error("HTTP " + r.code);
      return parseJson(optPayload(decLayer(decLayer(r.text, nonce), nonce)));
    });
}
function rawJson(url) {
  return signed(url, "GET", API_HEADERS, "").then(function (r) {
    if (r.code < 200 || r.code > 299) throw new Error("HTTP " + r.code);
    return parseJson(optPayload(decLayer(decLayer(r.text, RAW_KEY), RAW_KEY)));
  });
}

/* ---------- entries ---------- */
function txt(value) {
  if (value === null || value === undefined || typeof value === "object") return "";
  var raw = String(value).trim();
  return raw.toLowerCase() === "null" ? "" : raw;
}
function firstOf(row, keys) {
  var i, v;
  for (i = 0; i < keys.length; i++) {
    if (!row || !(keys[i] in row)) continue;
    v = row[keys[i]];
    if (v === null || v === undefined) continue;
    return v;
  }
  return null;
}
function entryObject(value) {
  var cur = value, depth = 0, rows, i, k, obj, merged = {};
  while (typeof cur === "string" && depth < 2) {
    if (!txt(cur)) return {};
    cur = parseJson(cur);
    depth++;
  }
  if (cur === null || cur === undefined) return {};
  rows = Array.isArray(cur) ? cur : [cur];
  for (i = 0; i < rows.length; i++) {
    obj = rows[i];
    if (!obj || typeof obj !== "object" || Array.isArray(obj)) continue;
    for (k in obj) {
      if (k === "__proto__" || k === "constructor" || k === "prototype") continue;
      merged[k] = obj[k];
    }
  }
  return merged;
}
function stringMap(value) {
  var src = entryObject(value), out = {}, k, v;
  for (k in src) {
    v = txt(src[k]);
    if (v) out[k] = v;
  }
  return out;
}
function normalizeHeaders(value) {
  var src = entryObject(value), out = {}, k, name, v;
  for (k in src) {
    name = k.toLowerCase().replace(/-/g, "");
    name = name === "useragent" ? "user-agent" : name === "xrequestedwith" ? "x-requested-with" : name === "referrer" ? "referer" : k.toLowerCase();
    if (FORBIDDEN[name]) continue;
    v = txt(src[k]);
    if (!v) continue;
    if (!/^[!#$%&'*+.^_`|~0-9a-z-]+$/.test(name) || /[^\t\x20-\x7e\x80-\xff]/.test(v)) continue;
    out[name] = v;
  }
  return out;
}
function entryOf(row, dataType) {
  var label = txt(firstOf(row, ["sezonName", "diziName", "bolumName", "title", "name", "chName", "filmName"]));
  var rowType = txt(row.diziType) || txt(row.chType) || txt(row.catType);
  var episode = ["bolumName", "bolumUrl"].some(function (k) { return k in row; }) ||
    rowType === "bolum_mode" || /b(ö|o)l(ü|u)m|episode/i.test(label);
  var season = !episode && (["sezonName", "sezonUrl"].some(function (k) { return k in row; }) ||
    rowType === "sezon_mode" || /sezon|season/i.test(label));
  return {
    name: txt(firstOf(row, ["chName", "bolumName", "filmName", "diziName", "title", "name"])),
    url: txt(firstOf(row, ["chUrl", "bolumUrl", "filmUrl", "diziUrl", "url"])),
    type: txt(firstOf(row, ["chType", "bolumType", "filmType", "diziType", "type"])),
    img: txt(firstOf(row, ["chImg", "chResim", "filmImg", "filmResim", "diziImg", "diziResim", "img", "resim", "poster"])),
    headers: normalizeHeaders(firstOf(row, ["chHeaders", "bolumHeaders", "filmHeaders", "diziHeaders", "headers"])),
    params: stringMap(firstOf(row, ["chReg", "bolumReg", "filmReg", "diziReg", "reg"])),
    dataType: txt(firstOf(row, ["filmDataType", "diziDataType", "chDataType"])) || dataType,
    season: season,
    episode: episode
  };
}
function entries(url, dataType) {
  var p = dataType === "raw_mode" ? rawJson(url) : postJson(url);
  return p.then(function (list) {
    if (!list || !list.length) return [];
    return list.map(function (row) { return entryOf(row || {}, dataType); });
  });
}

/* ---------- capture / resolve ---------- */
function literal(value) {
  var out = "", i = 0, ch;
  while (i < value.length) {
    ch = value.charAt(i);
    if (ch === "\\") {
      i++;
      if (i >= value.length || /[A-Za-z0-9]/.test(value.charAt(i))) return null;
      out += value.charAt(i);
    } else {
      if (".*+?^${}()|[]\\".indexOf(ch) >= 0) return null;
      out += ch;
    }
    i++;
  }
  return out;
}
function captureFirst(body, pattern) {
  if (!pattern || pattern.length > 512) return null;
  var greedy = pattern.indexOf("(.*?)") < 0;
  var marker = greedy ? "(.*)" : "(.*?)";
  var idx = pattern.indexOf(marker);
  if (idx < 0) return null;
  var prefix = literal(pattern.slice(0, idx)), suffix = literal(pattern.slice(idx + marker.length));
  if (prefix === null || suffix === null) return null;
  var lines = String(body).split(/\r|\n|\u2028|\u2029/), i, line, ms, start, end;
  for (i = 0; i < lines.length; i++) {
    line = lines[i];
    ms = prefix ? line.indexOf(prefix) : 0;
    if (ms < 0) continue;
    start = ms + prefix.length;
    if (!suffix) end = greedy ? line.length : start;
    else end = greedy ? line.lastIndexOf(suffix) : line.indexOf(suffix, start);
    if (end >= start) return line.slice(start, end);
  }
  return null;
}
function decodeCaptured(value) {
  var v = String(value).trim();
  if (v.indexOf("\\") < 0) return v;
  try {
    var parsed = JSON.parse('"' + v + '"');
    return typeof parsed === "string" ? parsed : v;
  } catch (e) {
    return v;
  }
}
function matchUrl(body) {
  var i, m, found;
  for (i = 0; i < FALLBACKS.length; i++) {
    m = FALLBACKS[i].exec(String(body));
    if (!m) continue;
    found = m[1] || m[0];
    return found.replace(/\\\//g, "/").replace(/^['"]+|['"]+$/g, "");
  }
  return null;
}
function captureTarget(body, pattern, from, to) {
  var captured = pattern ? captureFirst(body, pattern) : null;
  var value = captured === null ? null : decodeCaptured(captured);
  if (value) {
    if (from) value = value.split(from).join(to);
    if (value) return value;
  }
  return matchUrl(body);
}
function targetOf(resolved) {
  var keys = ["chUrl", "url", "stream", "file", "src", "link", "video", "hls"], i, v, data;
  for (i = 0; i < keys.length; i++) {
    v = txt(resolved[keys[i]]);
    if (v) return v;
  }
  data = resolved.data;
  if (data && typeof data === "object") {
    v = txt(data.url) || txt(data.stream);
    if (v) return v;
  }
  return null;
}
function jsonOf(value) {
  return parseJson(optPayload(String(value).trim()));
}
function decodeResolver(value, params) {
  var t = String(value).trim(), i, keys, m, inner, second, j, k;
  j = jsonOf(t);
  if (j) return j;
  if (/^https?:\/\//i.test(t)) return { chUrl: t };
  m = matchUrl(t);
  if (m) return { chUrl: m };
  keys = [];
  [params.Regex1, params.Regex2, params.Regex2p, RAW_KEY].forEach(function (k) {
    var v = txt(k);
    if (v && keys.indexOf(v) < 0) keys.push(v);
  });
  for (i = 0; i < keys.length; i++) {
    inner = null;
    try {
      inner = decLayer(t, keys[i]).trim();
    } catch (e) {}
    if (!inner) continue;
    j = jsonOf(inner);
    if (j) return j;
    if (/^https?:\/\//i.test(inner)) return { chUrl: inner };
    for (k = 0; k < keys.length; k++) {
      second = null;
      try {
        second = decLayer(inner, keys[k]).trim();
      } catch (e) {}
      if (!second) continue;
      j = jsonOf(second);
      if (j) return j;
    }
  }
  throw new Error("resolve");
}
function swappedExo(url) {
  return url.replace("/web.php", "/exo.php");
}
function isAd(url) {
  return AD_HOSTS.some(function (h) { return url.indexOf(h) >= 0; });
}
function resolveEntry(entry) {
  var headers = merge(entry.headers, { "cache-control": "no-cache" });
  var handler = String(entry.type || "").replace(/_(u|s)_mode$/, "_mode");
  var request;
  if (!entry.url) return Promise.reject(new Error("no url"));
  if (handler === "web_mode" || handler === "link_mode") {
    if (!CFG.exoSwap || entry.url.indexOf("web.php") < 0) return Promise.reject(new Error("embed"));
    request = http(swappedExo(entry.url), { headers: headers });
    return request.then(function (r) {
      if (r.code < 200 || r.code > 299) throw new Error("HTTP " + r.code);
      var target = captureTarget(r.text, entry.params.Regex1);
      if (!target || isAd(target)) throw new Error("no stream");
      return { url: target, headers: entry.headers };
    });
  }
  if (handler === "SSprDrm_event") {
    return signed(entry.url, "GET", merge(entry.headers, { "content-type": "application/json", accept: "application/json" }), "")
      .then(function (r) {
        if (r.code < 200 || r.code > 299) throw new Error("HTTP " + r.code);
        var target = decodeCaptured(captureFirst(r.text, entry.params.Regex1));
        if (!target) throw new Error("event");
        return { url: target, headers: entry.headers };
      });
  }
  if (handler.indexOf("SSprDrm") === 0) return Promise.reject(new Error("event list"));
  if (handler === "tekli_regex_mode" || handler === "tekli_regex_no_sh_mode" || handler === "tekli_regex_replace_mode") {
    return http(entry.url, { headers: headers }).then(function (r) {
      if (r.code < 200 || r.code > 299) throw new Error("HTTP " + r.code);
      var from = "", to = "";
      if (handler === "tekli_regex_replace_mode") {
        from = entry.params.Regex2 || "";
        to = entry.params.Regex3 || "";
      }
      var target = captureTarget(r.text, entry.params.Regex1, from, to);
      if (!target) throw new Error("no stream");
      return { url: target, headers: entry.headers };
    });
  }
  if (handler.indexOf("regex_lb") >= 0 || entry.params.Regex1) {
    return signed(entry.url, "GET", headers, "").then(function (r) {
      if (r.code < 200 || r.code > 299) throw new Error("HTTP " + r.code);
      var resolved = decodeResolver(r.text, entry.params);
      var target = targetOf(resolved);
      if (!target) throw new Error("no stream");
      return {
        url: target,
        headers: merge(entry.headers, normalizeHeaders(firstOf(resolved, ["chHeaders", "headers", "playHeaders"])))
      };
    });
  }
  if (MEDIA.test(entry.url)) return Promise.resolve({ url: entry.url, headers: entry.headers });
  return Promise.reject(new Error("unsupported " + entry.type));
}

/* ---------- catalogue ---------- */
function isSeries(entry) {
  if (entry.type === "sezon_mode" || entry.type === "dizi_mode" || entry.type === "sezonlar") return true;
  if (entry.type === "film_mode") return false;
  return /dizi/i.test(CFG.name) && !/film/i.test(CFG.name);
}
function episodeNo(name) {
  var m = /(\d+)\s*[.\-]?\s*(?:b(?:ö|o)l(?:ü|u)m|episode)/i.exec(name);
  return m ? parseInt(m[1], 10) : 0;
}
function seasonNo(name) {
  var m = /(\d+)/.exec(name);
  return m ? parseInt(m[1], 10) : 0;
}
function pickEpisode(list, want) {
  var i, byNo = [];
  for (i = 0; i < list.length; i++) if (episodeNo(list[i].name) === want) byNo.push(list[i]);
  if (byNo.length) return byNo[0];
  return list[want - 1] || list[0] || null;
}
function norm(v) {
  return String(v || "").toLowerCase().replace(/[^a-z0-9\u00e7\u011f\u0131\u00f6\u015f\u00fc ]+/g, " ").replace(/\s+/g, " ").trim();
}
function pickCandidates(list, title) {
  var want = norm(title), words = want.split(" ").filter(function (w) { return w.length > 1; });
  var scored = [], i, entry, name, score, hits;
  for (i = 0; i < list.length; i++) {
    entry = list[i];
    if (!entry.name || entry.season || entry.episode || entry.type === "link_mode") continue;
    name = norm(entry.name);
    hits = 0;
    for (var w = 0; w < words.length; w++) if (name.indexOf(words[w]) >= 0) hits++;
    score = 0;
    if (name === want) score = 3;
    else if (name.indexOf(want) >= 0) score = 2;
    else if (name.length >= 4 && want.indexOf(name) >= 0) score = 1;
    else if (hits >= 2 && hits * 2 >= words.length) score = 1;
    if (score) scored.push({ e: entry, s: score, h: hits, n: name.length });
  }
  scored.sort(function (a, b) { return b.s - a.s || b.h - a.h || a.n - b.n; });
  return scored.slice(0, 3).map(function (x) { return x.e; });
}
function expand(entry, wantSeason, wantEpisode) {
  if (!entry.url) return Promise.resolve([]);
  if (CFG.movieList && entry.type && !MODES[entry.type]) {
    return Promise.resolve([{ label: entry.name, entry: entry }]);
  }
  return entries(entry.url, entry.dataType).then(function (children) {
    var seasons, season, byNo, i;
    if (!children.length) return [{ label: entry.name, entry: entry }];
    seasons = children.filter(function (c) { return c.season && c.url; });
    if (seasons.length) {
      season = null;
      for (i = 0; i < seasons.length; i++) if (seasonNo(seasons[i].name) === wantSeason) season = seasons[i];
      if (!season) season = seasons[Math.min(wantSeason - 1, seasons.length - 1)];
      return entries(season.url, season.dataType).then(function (eps) {
        var ep = pickEpisode(eps, wantEpisode);
        return ep ? [{ label: ep.name, entry: ep }] : [];
      });
    }
    if (isSeries(entry) || children.some(function (c) { return c.episode; })) {
      byNo = pickEpisode(children, wantEpisode);
      return byNo ? [{ label: byNo.name, entry: byNo }] : [];
    }
    return children
      .filter(function (c) { return !c.season && c.url; })
      .slice(0, 4)
      .map(function (c) { return { label: c.name, entry: c }; });
  });
}

/* ---------- output ---------- */
function qualityOf(label) {
  var t = String(label || "").toLowerCase();
  if (CFG.q2160 || t.indexOf("2160") >= 0 || t.indexOf("4k") >= 0) return "2160p";
  if (t.indexOf("1080") >= 0) return "1080p";
  if (t.indexOf("720") >= 0) return "720p";
  if (t.indexOf("480") >= 0) return "480p";
  if (t.indexOf("360") >= 0) return "360p";
  return "auto";
}
function mediaHeaders(headers) {
  var out = {}, k, ua;
  for (k in headers) {
    if (k === "authorization" || k === "cookie" || k === "accept-encoding") continue;
    out[k] = headers[k];
  }
  ua = out["user-agent"];
  if (!ua || ua === "speedrestapi") out["user-agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
  if (out.referer === "https://speedrestapi.com/") delete out.referer;
  return out;
}
function titleOf(id, mediaType) {
  var kind = mediaType === "tv" ? "series" : "movie";
  return http("https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json")
    .then(function (r) {
      if (r.code !== 200) return "";
      var meta = parseJson(r.text);
      return meta && meta.meta ? txt(meta.meta.name) : "";
    })
    .catch(function () { return ""; });
}
function resolveAll(targets) {
  var out = [];
  var chain = Promise.resolve();
  targets.forEach(function (t) {
    chain = chain.then(function () {
      return resolveEntry(t.entry)
        .then(function (stream) {
          var headers = mediaHeaders(stream.headers);
          var isHls = /\.m3u8/i.test(stream.url);
          out.push({
            name: "inat4k06web",
            title: "inat4k06web - " + t.label + " [" + (isHls ? "HLS" : "MP4") + "]",
            url: stream.url,
            quality: qualityOf(t.label),
            headers: headers,
            subtitles: []
          });
        })
        .catch(function (e) {
          console.log("[bb-nuvio][inat4k06web] " + t.label + ": " + (e && e.message));
        });
    });
  });
  return chain.then(function () { return out; });
}
function getStreams(tmdbId, mediaType, season, episode) {
  var wantSeason = parseInt(season, 10) || 1;
  var wantEpisode = parseInt(episode, 10) || 1;
  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return null;
      return entries(CFG.url, CFG.dataType)
        .catch(function (e) {
          if (!CFG.fallbackUrl) throw e;
          return entries(CFG.fallbackUrl, CFG.dataType);
        })
        .then(function (list) {
          var cands = pickCandidates(list, title), targets = [], chain = Promise.resolve();
          cands.forEach(function (entry) {
            chain = chain.then(function () {
              return expand(entry, wantSeason, wantEpisode)
                .then(function (rows) { targets = targets.concat(rows); })
                .catch(function (e) { console.log("[bb-nuvio][inat4k06web] " + (e && e.message)); });
            });
          });
          return chain.then(function () { return targets.slice(0, 4); });
        });
    })
    .then(function (targets) {
      if (!targets || !targets.length) return [];
      return resolveAll(targets);
    })
    .catch(function (e) {
      console.log("[bb-nuvio][inat4k06web] " + (e && e.message));
      return [];
    });
}

module.exports = { getStreams };
