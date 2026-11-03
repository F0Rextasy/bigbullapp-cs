// @provider {"id":"sinemakolik","name":"Sinemakolik","types":["movie"],"domain":"sinemakolik.com","formats":["m3u8"],"sample":"tt0133093:movie:1:1","description":"Sinemakolik Türkçe dublaj/altyazılı film arşivi (AES korumalı vidmixi oynatıcı, HLS)."}
/**
 * sinemakolik — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /arama/?s= -> /film/<slug> -> ilkpartkod (base64 iframe)
 *       -> vidmixi embed -> bePlayer(hash, {s,iv,ct}) -> AES-256-CBC -> video_location
 *       -> HLS master + strSubtitles.
 * The player key is md5(pass+salt) || md5(md5(pass+salt)+pass+salt); MD5 and AES run in
 * plain JS because the Nuvio sandbox only provides fetch.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
var BASE = "https://sinemakolik.com";
var SUB_BASE = "https://vidmixi.com";

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

function all(pattern, body) {
  var out = [];
  var m;
  var re = new RegExp(pattern.source, pattern.flags.indexOf("g") === -1 ? pattern.flags + "g" : pattern.flags);
  while ((m = re.exec(String(body || ""))) !== null) out.push(m);
  return out;
}

function abs(base, href) {
  if (!href) return null;
  href = String(href).trim();
  if (/^https?:/i.test(href)) return href;
  var origin = /^(https?:\/\/[^/]+)/.exec(base);
  if (href.charAt(0) === "/") return origin ? origin[1] + href : href;
  return base.replace(/[^/]*$/, "") + href;
}

function norm(value) {
  return String(value || "")
    .toLowerCase()
    .replace(/[\u0131]/g, "i")
    .replace(/[\u015f]/g, "s")
    .replace(/[\u011f]/g, "g")
    .replace(/[\u00fc]/g, "u")
    .replace(/[\u00f6]/g, "o")
    .replace(/[\u00e7]/g, "c")
    .replace(/[^a-z0-9]/g, "");
}

function pick(cards, wanted) {
  var w = norm(wanted);
  if (!w) return null;
  var subset = null;
  for (var i = 0; i < cards.length; i++) {
    var n = norm(cards[i].title);
    if (!n) continue;
    if (n === w) return cards[i];
    if (!subset && (w.indexOf(n) !== -1 || n.indexOf(w) !== -1)) subset = cards[i];
  }
  return subset;
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

var B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

function b64(value) {
  var raw = String(value || "").replace(/[^A-Za-z0-9+/=]/g, "");
  var out = "";
  var buf = 0;
  var bits = 0;
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

function bytes(value) {
  var s = String(value || "");
  var out = [];
  for (var i = 0; i < s.length; i++) out.push(s.charCodeAt(i) & 0xff);
  return out;
}

function fromUtf8(b) {
  var out = "";
  var i = 0;
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

function hexBytes(value) {
  var s = String(value || "");
  var out = [];
  for (var i = 0; i + 1 < s.length; i += 2) out.push(parseInt(s.substr(i, 2), 16) & 0xff);
  return out;
}

// ---- AES-256-CBC decryption (inverse cipher, PKCS#7) ----
var SBOX = [];
var INV = [];
var RCON = [0, 0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1b, 0x36];
(function () {
  var p = 1;
  var q = 1;
  do {
    p = (p ^ (p << 1) ^ (p & 0x80 ? 0x1b : 0)) & 0xff;
    q ^= q << 1;
    q ^= q << 2;
    q ^= q << 4;
    q &= 0xff;
    if (q & 0x80) q ^= 0x09;
    var x = q ^ ((q << 1) | (q >> 7)) ^ ((q << 2) | (q >> 6)) ^ ((q << 3) | (q >> 5)) ^ ((q << 4) | (q >> 4));
    SBOX[p] = (x ^ 0x63) & 0xff;
  } while (p !== 1);
  SBOX[0] = 0x63;
  for (var i = 0; i < 256; i++) INV[SBOX[i]] = i;
})();

function xt(a) {
  return ((a << 1) ^ (a & 0x80 ? 0x1b : 0)) & 0xff;
}

function gm(a, b) {
  var r = 0;
  while (b) {
    if (b & 1) r ^= a;
    a = xt(a);
    b >>= 1;
  }
  return r;
}

function expandKey(key) {
  var nk = 8;
  var nr = 14;
  var w = [];
  var i;
  var t;
  for (i = 0; i < nk; i++) {
    w[i] = ((key[4 * i] << 24) | (key[4 * i + 1] << 16) | (key[4 * i + 2] << 8) | key[4 * i + 3]) >>> 0;
  }
  for (i = nk; i < 4 * (nr + 1); i++) {
    t = w[i - 1];
    if (i % nk === 0) {
      t = (((t << 8) | (t >>> 24)) >>> 0);
      t = ((SBOX[t >>> 24] << 24) | (SBOX[(t >>> 16) & 0xff] << 16) | (SBOX[(t >>> 8) & 0xff] << 8) | SBOX[t & 0xff]) >>> 0;
      t = (t ^ (RCON[i / nk] << 24)) >>> 0;
    } else if (i % nk === 4) {
      t = ((SBOX[t >>> 24] << 24) | (SBOX[(t >>> 16) & 0xff] << 16) | (SBOX[(t >>> 8) & 0xff] << 8) | SBOX[t & 0xff]) >>> 0;
    }
    w[i] = (w[i - nk] ^ t) >>> 0;
  }
  return w;
}

function addKey(s, w, round) {
  for (var c = 0; c < 4; c++) {
    var k = w[round * 4 + c];
    s[4 * c] ^= (k >>> 24) & 0xff;
    s[4 * c + 1] ^= (k >>> 16) & 0xff;
    s[4 * c + 2] ^= (k >>> 8) & 0xff;
    s[4 * c + 3] ^= k & 0xff;
  }
}

function invShiftRows(s) {
  for (var r = 1; r < 4; r++) {
    var row = [s[r], s[r + 4], s[r + 8], s[r + 12]];
    for (var i = 0; i < 4; i++) s[r + 4 * ((i + r) % 4)] = row[i];
  }
}

function invMixColumns(s) {
  for (var c = 0; c < 4; c++) {
    var i = 4 * c;
    var a = s[i];
    var b = s[i + 1];
    var d = s[i + 2];
    var e = s[i + 3];
    s[i] = gm(a, 14) ^ gm(b, 11) ^ gm(d, 13) ^ gm(e, 9);
    s[i + 1] = gm(a, 9) ^ gm(b, 14) ^ gm(d, 11) ^ gm(e, 13);
    s[i + 2] = gm(a, 13) ^ gm(b, 9) ^ gm(d, 14) ^ gm(e, 11);
    s[i + 3] = gm(a, 11) ^ gm(b, 13) ^ gm(d, 9) ^ gm(e, 14);
  }
}

function decryptBlock(w, block) {
  var s = block.slice(0, 16);
  var i;
  addKey(s, w, 14);
  for (var round = 13; round >= 1; round--) {
    invShiftRows(s);
    for (i = 0; i < 16; i++) s[i] = INV[s[i]];
    addKey(s, w, round);
    invMixColumns(s);
  }
  invShiftRows(s);
  for (i = 0; i < 16; i++) s[i] = INV[s[i]];
  addKey(s, w, 0);
  return s;
}

function aesCbcDecrypt(key, iv, data) {
  var w = expandKey(key);
  var out = [];
  var prev = iv.slice();
  for (var off = 0; off < data.length; off += 16) {
    var block = data.slice(off, off + 16);
    var plain = decryptBlock(w, block);
    for (var i = 0; i < 16; i++) out.push(plain[i] ^ prev[i]);
    prev = block;
  }
  var pad = out[out.length - 1];
  if (pad > 0 && pad <= 16) out.length -= pad;
  return out;
}

// ---- MD5 over byte arrays ----
var MSHIFT = [7, 12, 17, 22, 5, 9, 14, 20, 4, 11, 16, 23, 6, 10, 15, 21];
var MK = [];
for (var mk = 0; mk < 64; mk++) MK[mk] = Math.floor(Math.abs(Math.sin(mk + 1)) * 4294967296) >>> 0;

function md5(inBytes) {
  var len = inBytes.length;
  var msg = inBytes.slice();
  var i;
  msg.push(0x80);
  while (msg.length % 64 !== 56) msg.push(0);
  var bits = len * 8;
  var lo = bits % 4294967296;
  for (i = 0; i < 4; i++) msg.push((lo >>> (8 * i)) & 0xff);
  for (i = 0; i < 4; i++) msg.push((Math.floor(bits / 4294967296) >>> (8 * i)) & 0xff);
  var a0 = 0x67452301;
  var b0 = 0xefcdab89;
  var c0 = 0x98badcfe;
  var d0 = 0x10325476;
  var words = [];
  for (var j = 0; j < msg.length; j += 4) {
    words.push((msg[j] | (msg[j + 1] << 8) | (msg[j + 2] << 16) | (msg[j + 3] << 24)) >>> 0);
  }
  for (var off = 0; off < words.length; off += 16) {
    var a = a0;
    var b = b0;
    var c = c0;
    var d = d0;
    for (var step = 0; step < 64; step++) {
      var f;
      var g;
      if (step < 16) {
        f = (b & c) | (~b & d);
        g = step;
      } else if (step < 32) {
        f = (d & b) | (~d & c);
        g = (5 * step + 1) % 16;
      } else if (step < 48) {
        f = b ^ c ^ d;
        g = (3 * step + 5) % 16;
      } else {
        f = c ^ (b | ~d);
        g = (7 * step) % 16;
      }
      f = (f + a + MK[step] + words[off + g]) >>> 0;
      a = d;
      d = c;
      c = b;
      var sh = MSHIFT[(step >> 4) * 4 + (step % 4)];
      b = (b + (((f << sh) | (f >>> (32 - sh))) >>> 0)) >>> 0;
    }
    a0 = (a0 + a) >>> 0;
    b0 = (b0 + b) >>> 0;
    c0 = (c0 + c) >>> 0;
    d0 = (d0 + d) >>> 0;
  }
  var digest = [];
  [a0, b0, c0, d0].forEach(function (v) {
    for (var k = 0; k < 4; k++) digest.push((v >>> (8 * k)) & 0xff);
  });
  return digest;
}

/** bePlayer config -> decrypted JSON. */
function decryptConfig(hash, params) {
  var pass = bytes(hash);
  var salt = hexBytes(params.s);
  var iv = hexBytes(params.iv);
  var first = md5(pass.concat(salt));
  var key = first.concat(md5(first.concat(pass).concat(salt))).slice(0, 32);
  return JSON.parse(fromUtf8(aesCbcDecrypt(key, iv, bytes(b64(params.ct)))));
}

function qualityOf(master) {
  var heights = [];
  var m;
  var re = /RESOLUTION=\d+x(\d+)/gi;
  while ((m = re.exec(String(master || ""))) !== null) heights.push(parseInt(m[1], 10));
  var max = 0;
  for (var i = 0; i < heights.length; i++) if (heights[i] > max) max = heights[i];
  if (!max) return "auto";
  if (max >= 2160) return "2160p";
  if (max >= 1080) return "1080p";
  if (max >= 720) return "720p";
  if (max >= 480) return "480p";
  return "360p";
}

function searchCards(html) {
  var cards = [];
  var blocks = all(/<a href="(https:\/\/[^"]*\/film\/[^"]+)"[^>]*>([\s\S]*?)<\/a>/, html);
  for (var i = 0; i < blocks.length; i++) {
    var block = blocks[i][2];
    var title = first(/class="orj">([^<]*)</, block) || first(/<h2[^>]*>([^<]*)<\/h2>/, block);
    if (!title) continue;
    cards.push({ url: blocks[i][1], title: title.trim() });
  }
  return cards;
}

function subtitlesOf(player, embed) {
  var subs = [];
  var list = player.strSubtitles || [];
  for (var i = 0; i < list.length; i++) {
    var file = String((list[i] || {}).file || "").trim();
    if (!file || (!/^\//.test(file) && !/^https?:/.test(file))) continue;
    var label = String(list[i].label || "Altyazi").trim();
    subs.push({
      url: /^https?:/.test(file) ? file : SUB_BASE + file,
      language: String(list[i].language || "").trim() || label,
      name: label + " [Sinemakolik]",
      headers: { Referer: embed }
    });
  }
  return subs;
}

function getStreams(tmdbId, mediaType, season, episode) {
  var filmUrl = null;
  var embed = null;

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return null;
      return text(BASE + "/arama/?s=" + encodeURIComponent(title)).then(function (html) {
        return pick(searchCards(html), title);
      });
    })
    .then(function (card) {
      if (!card) return null;
      filmUrl = card.url;
      return text(filmUrl);
    })
    .then(function (html) {
      if (!html) return null;
      var code = first(/ilkpartkod\s*=\s*'([^']+)'/, html);
      if (!code) return null;
      embed = first(/src="([^"]+)"/, b64(code));
      if (!embed) return null;
      return text(embed, { referer: filmUrl });
    })
    .then(function (playerHtml) {
      if (!playerHtml) return [];
      var match = /bePlayer\('([^']+)'\s*,\s*'([\s\S]*?)'\s*\)/.exec(playerHtml);
      if (!match) return [];
      var player = decryptConfig(match[1], JSON.parse(match[2]));
      var url = String(player.video_location || "").trim();
      if (!/^https?:/.test(url)) return [];
      var subs = subtitlesOf(player, embed);
      return text(url, { referer: embed })
        .then(function (master) {
          var quality = qualityOf(master);
          return [
            {
              name: "Sinemakolik",
              title: "Sinemakolik - " + quality + " [HLS]",
              url: url,
              quality: quality,
              headers: { Referer: embed, "User-Agent": UA },
              subtitles: subs
            }
          ];
        })
        .catch(function () {
          return [
            {
              name: "Sinemakolik",
              title: "Sinemakolik - auto [HLS]",
              url: url,
              quality: "auto",
              headers: { Referer: embed, "User-Agent": UA },
              subtitles: subs
            }
          ];
        });
    })
    .then(function (rows) {
      return rows || [];
    })
    .catch(function (e) {
      console.log("[bb-nuvio][sinemakolik] " + (e && e.message));
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