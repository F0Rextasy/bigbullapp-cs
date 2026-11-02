// @provider {"id":"netfilmizle","name":"NetFilmizle","types":["movie"],"domain":"netfilmizle.com","formats":["m3u8"],"sample":"tt44929926:movie:1:1","description":"NetFilmizle film arşivi: vidmixi oynatıcısı çözülüp HLS akışı ve Türkçe altyazılar verilir."}
/**
 * netfilmizle — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /arama/ card -> film page -> base64 ilkpartkod iframe
 * -> vidmixi bePlayer (AES-256-CBC) -> HLS master + VTT subtitles.
 */
var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://netfilmizle.com";

function text(url, referer) {
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (referer) headers.Referer = referer;
  return fetch(url, { headers: headers }).then(function (r) {
    if (!r.ok) throw new Error("HTTP " + r.status + " " + url);
    return r.text();
  });
}

function fold(value) {
  return String(value || "").toLowerCase()
    .replace(/ı/g, "i").replace(/ş/g, "s").replace(/ğ/g, "g")
    .replace(/ü/g, "u").replace(/ö/g, "o").replace(/ç/g, "c");
}

function titleOf(id, mediaType) {
  var kind = mediaType === "tv" ? "series" : "movie";
  return fetch("https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json")
    .then(function (r) { return r.json(); })
    .then(function (j) { return String((j.meta || {}).name || "").trim(); })
    .catch(function () { return ""; });
}

/* base64 -> bytes (no atob / node buffers in the Nuvio sandbox) */
var B64C = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
function b64(s) {
  s = String(s).replace(/[^A-Za-z0-9+/=]/g, "");
  var idx = {}, i, acc = 0, bits = 0, out = [];
  for (i = 0; i < 64; i++) idx[B64C.charAt(i)] = i;
  for (i = 0; i < s.length; i++) {
    if (s.charAt(i) === "=") break;
    acc = (acc << 6) | idx[s.charAt(i)];
    bits += 6;
    if (bits >= 8) { bits -= 8; out.push((acc >> bits) & 255); }
  }
  return out;
}

function utf8(bytes) {
  var out = "", i = 0, c, n;
  while (i < bytes.length) {
    c = bytes[i++];
    if (c < 128) out += String.fromCharCode(c);
    else if (c > 191 && c < 224) out += String.fromCharCode(((c & 31) << 6) | (bytes[i++] & 63));
    else if (c > 223 && c < 240) out += String.fromCharCode(((c & 15) << 12) | ((bytes[i++] & 63) << 6) | (bytes[i++] & 63));
    else {
      n = (((c & 7) << 18) | ((bytes[i++] & 63) << 12) | ((bytes[i++] & 63) << 6) | (bytes[i++] & 63)) - 0x10000;
      out += String.fromCharCode(0xD800 + (n >> 10), 0xDC00 + (n & 1023));
    }
  }
  return out;
}

function hexBytes(value) {
  var s = String(value || ""), out = [], i;
  for (i = 0; i + 1 < s.length; i += 2) out.push(parseInt(s.substr(i, 2), 16));
  return out;
}

/* ---- AES-256-CBC decrypt (PKCS7) ---- */
var SB = [], ISB = [], MX = [];
(function () {
  var s = "637c777bf26b6fc53001672bfed7ab76ca82c97dfa5947f0add4a2af9ca472c0b7fd9326363ff7cc34a5e5f171d8311504c723c31896059a071280e2eb27b27509832c1a1b6e5aa0523bd6b329e32f8453d100ed20fcb15b6acbbe394a4c58cfd0efaafb434d338545f9027f503c9fa851a3408f929d38f5bcb6da2110fff3d2cd0c13ec5f974417c4a77e3d645d197360814fdc222a908846eeb814de5e0bdbe0323a0a4906245cc2d3ac629195e479e7c8376d8dd54ea96c56f4ea657aae08ba78252e1ca6b4c6e8dd741f4bbd8b8a703eb5664803f60e613557b986c11d9ee1f8981169d98e949b1e87e9ce5528df8ca1890dbfe6426841992d0fb054bb16";
  var i;
  for (i = 0; i < 256; i++) { SB[i] = parseInt(s.substr(i * 2, 2), 16); ISB[SB[i]] = i; }
})();
function xt(a) { return ((a << 1) ^ ((a & 0x80) ? 0x1b : 0)) & 255; }
function mul(a, b) { var p = 0; while (b) { if (b & 1) p ^= a; a = xt(a); b >>= 1; } return p & 255; }
(function () { for (var c = 0; c < 256; c++) MX[c] = [mul(c, 2), mul(c, 3), mul(c, 9), mul(c, 11), mul(c, 13), mul(c, 14)]; })();

function expandKey(key) {
  var nk = key.length / 4, nr = nk + 6, w = [], i, rcon = 1, t;
  for (i = 0; i < nk; i++) w.push([key[4 * i], key[4 * i + 1], key[4 * i + 2], key[4 * i + 3]]);
  for (; i < 4 * (nr + 1); i++) {
    t = w[i - 1].slice();
    if (i % nk === 0) {
      t = [SB[t[1]] ^ (rcon & 255), SB[t[2]], SB[t[3]], SB[t[0]]];
      rcon = xt(rcon);
    } else if (nk > 6 && i % nk === 4) t = [SB[t[0]], SB[t[1]], SB[t[2]], SB[t[3]]];
    w.push([w[i - nk][0] ^ t[0], w[i - nk][1] ^ t[1], w[i - nk][2] ^ t[2], w[i - nk][3] ^ t[3]]);
  }
  return { w: w, nr: nr };
}

function decBlock(ks, input) {
  var nr = ks.nr, w = ks.w, s = input.slice(), r, i, c, u, x, t;
  for (i = 0; i < 4; i++) for (c = 0; c < 4; c++) s[i + 4 * c] ^= w[nr * 4 + c][i];
  for (r = nr - 1; r >= 0; r--) {
    u = s.slice();
    for (c = 0; c < 4; c++) for (i = 0; i < 4; i++) s[i + 4 * c] = u[i + 4 * ((c - i + 4) % 4)];
    for (i = 0; i < 16; i++) s[i] = ISB[s[i]];
    for (i = 0; i < 4; i++) for (c = 0; c < 4; c++) s[i + 4 * c] ^= w[r * 4 + c][i];
    if (r) for (c = 0; c < 4; c++) {
      x = [s[4 * c], s[4 * c + 1], s[4 * c + 2], s[4 * c + 3]];
      t = [MX[x[0]][5] ^ MX[x[1]][3] ^ MX[x[2]][4] ^ MX[x[3]][2],
           MX[x[0]][2] ^ MX[x[1]][5] ^ MX[x[2]][3] ^ MX[x[3]][4],
           MX[x[0]][4] ^ MX[x[1]][2] ^ MX[x[2]][5] ^ MX[x[3]][3],
           MX[x[0]][3] ^ MX[x[1]][4] ^ MX[x[2]][2] ^ MX[x[3]][5]];
      for (i = 0; i < 4; i++) s[4 * c + i] = t[i];
    }
  }
  return s;
}

function cbcDecrypt(key, iv, ct) {
  var ks = expandKey(key), prev = iv, out = [], o, b, i;
  for (o = 0; o < ct.length; o += 16) {
    b = decBlock(ks, ct.slice(o, o + 16));
    for (i = 0; i < 16; i++) out.push(b[i] ^ (prev[i] || 0));
    prev = ct.slice(o, o + 16);
  }
  var pad = out[out.length - 1];
  if (pad > 0 && pad <= 16) out = out.slice(0, out.length - pad);
  return out;
}

/* ---- MD5 -> bytes ---- */
function md5(bytes) {
  var R = [7,12,17,22,7,12,17,22,7,12,17,22,7,12,17,22,5,9,14,20,5,9,14,20,5,9,14,20,5,9,14,20,4,11,16,23,4,11,16,23,4,11,16,23,4,11,16,23,6,10,15,21,6,10,15,21,6,10,15,21,6,10,15,21];
  var K = [], i;
  for (i = 0; i < 64; i++) K[i] = Math.floor(Math.abs(Math.sin(i + 1)) * 4294967296);
  var msg = bytes.slice(), bitLen = bytes.length * 8;
  msg.push(0x80);
  while (msg.length % 64 !== 56) msg.push(0);
  for (i = 0; i < 8; i++) msg.push(Math.floor(bitLen / Math.pow(2, 8 * i)) & 255);
  function add(x, y) { return (x + y) >>> 0; }
  function rol(x, c) { return ((x << c) | (x >>> (32 - c))) >>> 0; }
  var a0 = 0x67452301, b0 = 0xefcdab89, c0 = 0x98badcfe, d0 = 0x10325476;
  for (var off = 0; off < msg.length; off += 64) {
    var M = [], A = a0, B = b0, C = c0, D = d0, F, g, tmp;
    for (i = 0; i < 16; i++) M[i] = msg[off + 4 * i] | (msg[off + 4 * i + 1] << 8) | (msg[off + 4 * i + 2] << 16) | (msg[off + 4 * i + 3] << 24);
    for (i = 0; i < 64; i++) {
      if (i < 16) { F = (B & C) | (~B & D); g = i; }
      else if (i < 32) { F = (D & B) | (~D & C); g = (5 * i + 1) % 16; }
      else if (i < 48) { F = B ^ C ^ D; g = (3 * i + 5) % 16; }
      else { F = C ^ (B | ~D); g = (7 * i) % 16; }
      tmp = D; D = C; C = B;
      B = add(B, rol(add(add(A, F), add(K[i], M[g])), R[i]));
      A = tmp;
    }
    a0 = add(a0, A); b0 = add(b0, B); c0 = add(c0, C); d0 = add(d0, D);
  }
  var out = [];
  [a0, b0, c0, d0].forEach(function (w) {
    for (var j = 0; j < 4; j++) out.push((w >>> (8 * j)) & 255);
  });
  return out;
}

function bytesOf(str) {
  var s = String(str), out = [], i, c;
  for (i = 0; i < s.length; i++) {
    c = s.charCodeAt(i);
    if (c < 128) out.push(c);
    else if (c < 2048) out.push(192 | (c >> 6), 128 | (c & 63));
    else out.push(224 | (c >> 12), 128 | ((c >> 6) & 63), 128 | (c & 63));
  }
  return out;
}

/* vidmixi bePlayer: key = md5(pw+salt) ++ md5(md5(pw+salt)+pw+salt), AES-256-CBC */
function decryptPlayer(hash, params) {
  var pw = bytesOf(hash);
  var salt = hexBytes(params.s);
  var first = md5(pw.concat(salt));
  var second = md5(first.concat(pw).concat(salt));
  var key = first.concat(second).slice(0, 32);
  var json = utf8(cbcDecrypt(key, hexBytes(params.iv), b64(params.ct)));
  return JSON.parse(json);
}

function searchUrl(term) {
  return text(BASE + "/arama/?s=" + encodeURIComponent(term)).then(function (page) {
    var re = /<a[^>]+href="(https:\/\/netfilmizle\.com\/[^"\/]+\/)"[^>]*title="([^"]*)"/g;
    var m, exact = null, loose = null;
    while ((m = re.exec(page)) !== null) {
      var label = fold(m[2]);
      if (label === term) { exact = m[1]; break; }
      if (!loose && label.indexOf(term) !== -1) loose = m[1];
    }
    return exact || loose;
  });
}

function getStreams(imdbId, mediaType, season, episode) {
  var pageUrl = null;
  var embed = null;
  return titleOf(imdbId, mediaType)
    .then(function (title) {
      var term = fold(title);
      if (!term) return null;
      return searchUrl(term);
    })
    .then(function (url) {
      if (!url) return [];
      pageUrl = url;
      return text(url);
    })
    .then(function (page) {
      if (!page) return [];
      var code = /ilkpartkod\s*=\s*'([^']+)'/.exec(page);
      if (!code) return [];
      var html = utf8(b64(code[1])).replace(/\\\//g, "/");
      var src = /src="([^"]+)"/.exec(html);
      if (!src) return [];
      embed = src[1];
      if (/^\/\//.test(embed)) embed = "https:" + embed;
      if (!/^https?:/.test(embed) || /youtube\.com|youtu\.be/i.test(embed)) return [];
      return text(embed, pageUrl);
    })
    .then(function (playerPage) {
      if (!playerPage) return [];
      var cfg = /bePlayer\(\s*'([^']+)'\s*,\s*'(\{[\s\S]*?\})'\s*\)/.exec(playerPage);
      if (!cfg) return [];
      var player = decryptPlayer(cfg[1], JSON.parse(cfg[2]));
      var stream = String(player.video_location || "").trim();
      if (!/^https?:/.test(stream)) return [];
      /* master yalnızca kalite etiketi için okunur; alınamazsa akış yine verilir */
      return text(stream, embed).catch(function () { return ""; }).then(function (master) {
        var heights = [];
        var re = /RESOLUTION=\d+x(\d+)/g;
        var m;
        while ((m = re.exec(master)) !== null) heights.push(parseInt(m[1], 10));
        var best = heights.length ? Math.max.apply(null, heights) : 0;
        var subs = [];
        var list = player.strSubtitles || [];
        for (var i = 0; i < list.length; i++) {
          var file = String(list[i].file || "").trim();
          if (file.charAt(0) !== "/") continue;
          var lang = String(list[i].language || "tr").replace(/^tur_/, "");
          subs.push({
            url: "https://vidmixi.com" + file,
            language: lang,
            name: String(list[i].label || "Altyazı")
          });
        }
        return [{
          name: "netfilmizle",
          title: "NetFilmizle - " + (best ? best + "p" : "auto") + " [HLS]",
          url: stream,
          quality: best ? best + "p" : "auto",
          headers: { Referer: embed, "User-Agent": UA },
          subtitles: subs.slice(0, 4)
        }];
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][netfilmizle] " + (e && e.message));
      return [];
    });
}

module.exports = { getStreams };
