// @provider {"id":"dizifilmizle","name":"DiziFilmIzle","types":["movie","tv"],"domain":"dizifilmizle.to","formats":["m3u8"],"sample":"tt0903747:tv:1:1","description":"DiziFilmIzle - Turkce dizi/film arsivi (bePlayer AES sifreli HLS)."}
/**
 * dizifilmizle — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /api/search -> detail -> episode -> vidmixi embed ->
 *       bePlayer(key,{ct,iv,s}) -> MD5 keying + AES-256-CBC -> video_location (HLS).
 */
var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://dizifilmizle.to";

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
  if (o.method === "POST") headers["Content-Type"] = "application/json";
  return fetch(url, { method: o.method || "GET", headers: headers, body: o.body }).then(function (r) {
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
  if (String(href).charAt(0) === "/") return origin ? origin[1] + href : href;
  return base.replace(/[^/]*$/, "") + href;
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

function unescapeHtml(body) {
  return String(body || "")
    .replace(/\\\\\//g, "/")
    .replace(/\\\//g, "/")
    .replace(/\\"/g, '"');
}

// ------------------------------------------------------------------ crypto
var SBOX = [];
var INV_SBOX = [];
var RCON = [];
(function () {
  var p = 1, q = 1;
  do {
    p = p ^ (p << 1) ^ (p & 0x80 ? 0x1b : 0);
    p &= 0xff;
    q ^= q << 1;
    q ^= q << 2;
    q ^= q << 4;
    q &= 0xff;
    if (q & 0x80) q ^= 0x09;
    var x = q ^ ((q << 1) | (q >> 7)) ^ ((q << 2) | (q >> 6)) ^ ((q << 3) | (q >> 5)) ^ ((q << 4) | (q >> 4));
    SBOX[p] = (x ^ 99) & 0xff;
  } while (p !== 1);
  SBOX[0] = 99;
  for (var i = 0; i < 256; i++) INV_SBOX[SBOX[i]] = i;
  var r = 1;
  for (var j = 0; j < 11; j++) {
    RCON[j] = r;
    r = r & 0x80 ? ((r << 1) ^ 0x11b) & 0xff : (r << 1) & 0xff;
  }
})();

function md5(bytes) {
  var S = [7, 12, 17, 22, 5, 9, 14, 20, 4, 11, 16, 23, 6, 10, 15, 21];
  var K = [];
  for (var i = 0; i < 64; i++) K[i] = Math.floor(Math.abs(Math.sin(i + 1)) * 4294967296) | 0;
  var len = bytes.length;
  var total = (((len + 9 + 63) >> 6) << 6);
  var b = new Uint8Array(total);
  b.set(bytes);
  b[len] = 0x80;
  var bitsLo = (len * 8) >>> 0;
  var bitsHi = Math.floor(len / 536870912) >>> 0;
  b[total - 8] = bitsLo & 255;
  b[total - 7] = (bitsLo >>> 8) & 255;
  b[total - 6] = (bitsLo >>> 16) & 255;
  b[total - 5] = (bitsLo >>> 24) & 255;
  b[total - 4] = bitsHi & 255;
  b[total - 3] = (bitsHi >>> 8) & 255;
  var a0 = 0x67452301, b0 = 0xefcdab89 | 0, c0 = 0x98badcfe | 0, d0 = 0x10325476;
  var M = new Array(16);
  for (var off = 0; off < total; off += 64) {
    for (var k = 0; k < 16; k++) {
      var o = off + k * 4;
      M[k] = b[o] | (b[o + 1] << 8) | (b[o + 2] << 16) | (b[o + 3] << 24);
    }
    var A = a0, B = b0, C = c0, D = d0;
    for (var n = 0; n < 64; n++) {
      var f, g;
      if (n < 16) {
        f = (B & C) | (~B & D);
        g = n;
      } else if (n < 32) {
        f = (D & B) | (~D & C);
        g = (5 * n + 1) % 16;
      } else if (n < 48) {
        f = B ^ C ^ D;
        g = (3 * n + 5) % 16;
      } else {
        f = C ^ (B | ~D);
        g = (7 * n) % 16;
      }
      var s = S[(n >> 4) * 4 + (n % 4)];
      var sum = (A + f + K[n] + M[g]) | 0;
      var rot = ((sum << s) | (sum >>> (32 - s))) | 0;
      A = D;
      D = C;
      C = B;
      B = (B + rot) | 0;
    }
    a0 = (a0 + A) | 0;
    b0 = (b0 + B) | 0;
    c0 = (c0 + C) | 0;
    d0 = (d0 + D) | 0;
  }
  var out = new Uint8Array(16);
  [a0, b0, c0, d0].forEach(function (word, i) {
    out[i * 4] = word & 255;
    out[i * 4 + 1] = (word >>> 8) & 255;
    out[i * 4 + 2] = (word >>> 16) & 255;
    out[i * 4 + 3] = (word >>> 24) & 255;
  });
  return out;
}

function xtime(a) {
  return ((a << 1) ^ (a & 0x80 ? 0x1b : 0)) & 0xff;
}

function gmul(a, b) {
  var r = 0;
  while (b) {
    if (b & 1) r ^= a;
    a = xtime(a);
    b >>= 1;
  }
  return r & 0xff;
}

function addRoundKey(sched, st, round) {
  for (var c = 0; c < 4; c++) {
    var word = sched.w[round * 4 + c];
    for (var r = 0; r < 4; r++) st[r + 4 * c] ^= word[r];
  }
}

function invShiftRows(st) {
  for (var r = 1; r < 4; r++) {
    var row = [st[r], st[r + 4], st[r + 8], st[r + 12]];
    for (var c = 0; c < 4; c++) st[r + 4 * ((c + r) % 4)] = row[c];
  }
}

function invMixColumns(st) {
  for (var c = 0; c < 4; c++) {
    var a0 = st[4 * c], a1 = st[4 * c + 1], a2 = st[4 * c + 2], a3 = st[4 * c + 3];
    st[4 * c] = gmul(a0, 14) ^ gmul(a1, 11) ^ gmul(a2, 13) ^ gmul(a3, 9);
    st[4 * c + 1] = gmul(a0, 9) ^ gmul(a1, 14) ^ gmul(a2, 11) ^ gmul(a3, 13);
    st[4 * c + 2] = gmul(a0, 13) ^ gmul(a1, 9) ^ gmul(a2, 14) ^ gmul(a3, 11);
    st[4 * c + 3] = gmul(a0, 11) ^ gmul(a1, 13) ^ gmul(a2, 9) ^ gmul(a3, 14);
  }
}

/** AES-CBC decrypt (128/192/256-bit key) with PKCS#7 unpadding. */
function aesCbcDecrypt(key, iv, data) {
  var nk = key.length >> 2;
  var nr = nk + 6;
  var w = [];
  var i;
  for (i = 0; i < nk; i++) w[i] = [key[4 * i], key[4 * i + 1], key[4 * i + 2], key[4 * i + 3]];
  for (i = nk; i < 4 * (nr + 1); i++) {
    var t = w[i - 1].slice();
    if (i % nk === 0) {
      t.push(t.shift());
      for (var j = 0; j < 4; j++) t[j] = SBOX[t[j]];
      t[0] ^= RCON[i / nk - 1];
    } else if (nk > 6 && i % nk === 4) {
      for (var j2 = 0; j2 < 4; j2++) t[j2] = SBOX[t[j2]];
    }
    w[i] = [w[i - nk][0] ^ t[0], w[i - nk][1] ^ t[1], w[i - nk][2] ^ t[2], w[i - nk][3] ^ t[3]];
  }
  var sched = { w: w, nr: nr };
  var blocks = Math.floor(data.length / 16);
  var out = new Uint8Array(blocks * 16);
  var prev = iv;
  for (i = 0; i < blocks; i++) {
    var block = data.subarray(i * 16, i * 16 + 16);
    var st = new Uint8Array(block);
    addRoundKey(sched, st, nr);
    for (var round = nr - 1; round >= 1; round--) {
      invShiftRows(st);
      for (var p = 0; p < 16; p++) st[p] = INV_SBOX[st[p]];
      addRoundKey(sched, st, round);
      invMixColumns(st);
    }
    invShiftRows(st);
    for (var p2 = 0; p2 < 16; p2++) st[p2] = INV_SBOX[st[p2]];
    addRoundKey(sched, st, 0);
    for (var q2 = 0; q2 < 16; q2++) out[i * 16 + q2] = st[q2] ^ prev[q2];
    prev = block;
  }
  var pad = out.length ? out[out.length - 1] : 0;
  return pad >= 1 && pad <= 16 ? out.subarray(0, out.length - pad) : out;
}

function unb64(value) {
  var A = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  var clean = String(value || "").replace(/[^A-Za-z0-9+/]/g, "");
  var out = new Uint8Array(Math.floor((clean.length * 3) / 4));
  var p = 0, buffer = 0, bits = 0;
  for (var i = 0; i < clean.length; i++) {
    buffer = (buffer << 6) | A.indexOf(clean.charAt(i));
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out[p++] = (buffer >> bits) & 255;
    }
  }
  return out.subarray(0, p);
}

function utf8(text) {
  var s = String(text);
  var out = [];
  for (var i = 0; i < s.length; i++) {
    var code = s.charCodeAt(i);
    if (code < 0x80) out.push(code);
    else if (code < 0x800) out.push(0xc0 | (code >> 6), 0x80 | (code & 63));
    else out.push(0xe0 | (code >> 12), 0x80 | ((code >> 6) & 63), 0x80 | (code & 63));
  }
  return new Uint8Array(out);
}

function fromUtf8(bytes) {
  var out = "";
  for (var i = 0; i < bytes.length; ) {
    var b = bytes[i++];
    if (b < 0x80) out += String.fromCharCode(b);
    else if (b < 0xe0) out += String.fromCharCode(((b & 31) << 6) | (bytes[i++] & 63));
    else if (b < 0xf0) out += String.fromCharCode(((b & 15) << 12) | ((bytes[i++] & 63) << 6) | (bytes[i++] & 63));
    else {
      var cp = ((b & 7) << 18) | ((bytes[i++] & 63) << 12) | ((bytes[i++] & 63) << 6) | (bytes[i++] & 63);
      cp -= 0x10000;
      out += String.fromCharCode(0xd800 + (cp >> 10), 0xdc00 + (cp & 1023));
    }
  }
  return out;
}

function hexBytes(value) {
  var s = String(value || "").replace(/[^0-9a-fA-F]/g, "");
  var out = new Uint8Array(s.length >> 1);
  for (var i = 0; i < out.length; i++) out[i] = parseInt(s.substr(i * 2, 2), 16);
  return out;
}

function concat(list) {
  var total = 0, i;
  for (i = 0; i < list.length; i++) total += list[i].length;
  var out = new Uint8Array(total);
  var at = 0;
  for (i = 0; i < list.length; i++) {
    out.set(list[i], at);
    at += list[i].length;
  }
  return out;
}

/** bePlayer('key','{ct,iv,s}') -> MD5(key||salt) + MD5(first||key||salt) as AES-256 key. */
function bePlayer(html) {
  var m = /bePlayer\('([^']+)'\s*,\s*'(\{.*?\})'/.exec(String(html || ""));
  if (!m) return null;
  var password = utf8(m[1]);
  var params = JSON.parse(m[2]);
  var salt = hexBytes(params.s);
  var firstKey = md5(concat([password, salt]));
  var secondKey = md5(concat([firstKey, password, salt]));
  var plain = fromUtf8(aesCbcDecrypt(concat([firstKey, secondKey]), hexBytes(params.iv), unb64(params.ct)));
  var data = JSON.parse(plain);
  var location = String(data.video_location || "").trim();
  if (!/^https?:|^\/\//.test(location)) return null;
  data.video_location = location.replace(/^\/\//, "https://");
  return data;
}

// ------------------------------------------------------------------ flow
function qualityOf(masterUrl, referer) {
  return text(masterUrl, { referer: referer })
    .then(function (playlist) {
      var best = 0;
      var re = /RESOLUTION=\d+x(\d+)/g;
      var m;
      while ((m = re.exec(playlist)) !== null) best = Math.max(best, parseInt(m[1], 10) || 0);
      return best > 0 ? best + "p" : "auto";
    })
    .catch(function () {
      return "auto";
    });
}

function embedStream(embedUrl, pageUrl) {
  return text(embedUrl, { referer: pageUrl }).then(function (html) {
    var data = null;
    try {
      data = bePlayer(html);
    } catch (e) {
      data = null;
    }
    if (!data) return [];
    var url = data.video_location;
    var subs = [];
    (data.strSubtitles || []).forEach(function (track) {
      var file = String((track && track.file) || "").trim();
      if (!file) return;
      var label = String(track.label || track.language || track.lang || "tr").trim();
      var lang = /turk/i.test(label) ? "tr" : /engl/i.test(label) ? "en" : label.slice(0, 2).toLowerCase();
      subs.push({ url: abs(embedUrl, file), language: lang, name: label + " [dizifilmizle]" });
    });
    return qualityOf(url, embedUrl).then(function (quality) {
      return [
        {
          name: "dizifilmizle",
          title: "dizifilmizle - " + (data.description || data.title || "Oynat") + " [" + quality + " HLS]",
          url: url,
          quality: quality,
          headers: { Referer: embedUrl, "User-Agent": UA },
          subtitles: subs.slice(0, 4)
        }
      ];
    });
  });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var isTv = mediaType === "tv";
  var pageUrl = null;
  var detailUrl = null;

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return null;
      return text(BASE + "/api/search?q=" + encodeURIComponent(title));
    })
    .then(function (body) {
      if (!body) return null;
      var results = JSON.parse(body).results || [];
      var wantType = isTv ? "series" : "movie";
      var hit = null;
      for (var i = 0; i < results.length; i++) {
        if (String(results[i].content_type) === wantType) {
          hit = results[i];
          break;
        }
      }
      if (!hit) return null;
      detailUrl = BASE + (wantType === "series" ? "/dizi/" : "/film/") + hit.slug;
      return text(detailUrl);
    })
    .then(function (detail) {
      if (!detail || !detailUrl) return [];
      if (!isTv) {
        pageUrl = detailUrl;
        return detail;
      }
      var seriesUrl = detailUrl;

      if (!seriesUrl) return [];
      var exact = new RegExp("href=\"([^\"]*sezon-" + want + "/bolum-" + wantEp + ")\"");
      var link = first(exact, detail) || first(/href="([^"]*sezon-\d+\/bolum-\d+)"/, detail);
      if (!link) return [];
      pageUrl = abs(seriesUrl, link);
      return text(pageUrl);
    })
    .then(function (page) {
      if (!page || !pageUrl) return [];
      var flat = unescapeHtml(page);
      var embeds = [];
      var seen = {};
      var re = /https?:\/\/[^"'\s<>]+\/embed\/[^"'\s<>]+/g;
      var m;
      while ((m = re.exec(flat)) !== null) {
        var u = m[0].replace(/\\+$/, "");
        if (!seen[u]) {
          seen[u] = 1;
          embeds.push(u);
        }
      }
      if (!embeds.length) {
        var direct = [];
        var media = /https?:\/\/[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?/g;
        while ((m = media.exec(flat)) !== null) direct.push(m[0]);
        return direct.map(function (url) {
          return {
            name: "dizifilmizle",
            title: "dizifilmizle - Direct [HLS]",
            url: url,
            quality: "auto",
            headers: { Referer: pageUrl, "User-Agent": UA },
            subtitles: []
          };
        });
      }
      var number = parseInt(first(/\/bolum-(\d+)/, pageUrl) || "0", 10);
      var picked = number > 0 && embeds[number - 1] ? [embeds[number - 1]] : embeds.slice(0, 2);
      return Promise.all(
        picked.map(function (embed) {
          return embedStream(embed, pageUrl).catch(function () {
            return [];
          });
        })
      ).then(function (groups) {
        var out = [];
        groups.forEach(function (rows) {
          rows.forEach(function (row) {
            out.push(row);
          });
        });
        return out;
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][dizifilmizle] " + (e && e.message));
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