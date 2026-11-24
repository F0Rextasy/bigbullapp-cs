// @provider {"id":"hdfilmdiziizle","name":"HDFilmDiziIzle","types":["movie","tv"],"domain":"www.hdfilmdiziizle.com","formats":["m3u8","mp4"],"sample":"tt10838180:movie:1:1","description":"HDFilmDiziIzle film ve dizi arsivi (AES AJAX + Vidrame/Vidmoly/OkRu).","verified":true,"version":"1.0.0"}
/**
 * hdfilmdiziizle — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta/alt title -> /wp-json/wp/v2/search?search= -> postid + button.playhd-btn[data-source]
 *       -> POST /wp-admin/admin-ajax.php (get_source_url) -> AES-256-CBC fixed key/IV
 *       -> xorFile (vidrame) | Vidmoly | OkRu | direct m3u8/mp4.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://www.hdfilmdiziizle.com";
var ID = "HDFilmDiziIzle";
var B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
  if (o.headers) for (var k in o.headers) if (o.headers[k]) headers[k] = o.headers[k];
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
  href = String(href).replace(/&amp;/g, "&").trim();
  if (/^https?:/i.test(href)) return href;
  var origin = /^(https?:\/\/[^/]+)/.exec(base);
  if (!origin) return href;
  if (href.indexOf("//") === 0) return "https:" + href;
  if (href.charAt(0) === "/") return origin[1] + href;
  return base.replace(/[^/]*$/, "") + href;
}

function titleOf(id, mediaType) {
  if (/^__alt__:/.test(String(id))) return Promise.resolve(decodeURIComponent(String(id).slice(8)));
  var kind = mediaType === "tv" ? "series" : "movie";
  return text("https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json")
    .then(function (body) { return String(((JSON.parse(body).meta) || {}).name || "").trim(); })
    .catch(function () { return ""; });
}

function sameTitle(have, want) {
  function f(v) {
    return String(v || "").replace(/[\u0131\u0130I]/g, "i").replace(/[\u015f\u015e]/g, "s").replace(/[\u011f\u011e]/g, "g")
      .replace(/[\u00e7\u00c7]/g, "c").replace(/[\u00f6\u00d6]/g, "o").replace(/[\u00fc\u00dc]/g, "u")
      .toLowerCase().replace(/[^a-z0-9]+/g, " ").trim().replace(/^(?:the|a|an)\s+/, "");
  }
  have = f(have);
  want = f(want);
  return !!want && (have === want || have.indexOf(want + " ") === 0);
}

function cleanTitle(raw) {
  return String(raw || "")
    .replace(/&#(\d+);/g, function (_, d) { return String.fromCharCode(parseInt(d, 10)); })
    .replace(/&amp;/g, "&").replace(/&quot;/g, '"').replace(/&#039;/g, "'")
    .replace(/\s*\|.*$/, "")
    .replace(/\s*[-–]\s*(?:HDFilm|Film|Dizi|Full).*$/i, "")
    .replace(/\s+izle\b.*$/i, "")
    .replace(/\s*\(\d{4}\).*$/, "")
    .replace(/\s+(?:Türkçe|Dublaj|Altyazılı)\b.*$/i, "")
    .trim().replace(/^[-–|,]+|[-–|,]+$/g, "");
}

function hlsNoDownload(url) {
  var u = String(url || "");
  if (/\.m3u8(\?|$)/i.test(u) || u.indexOf("#") !== -1) return u;
  return u + "#.m3u8";
}

function b64Bytes(value) {
  var s = String(value || "").replace(/-/g, "+").replace(/_/g, "/").replace(/[^A-Za-z0-9+/=]/g, "");
  while (s.length % 4) s += "=";
  var out = [], i, a, b, c, d;
  for (i = 0; i < s.length; i += 4) {
    a = B64.indexOf(s.charAt(i)); b = B64.indexOf(s.charAt(i + 1));
    c = B64.indexOf(s.charAt(i + 2)); d = B64.indexOf(s.charAt(i + 3));
    if (a < 0 || b < 0) break;
    out.push((a << 2) | (b >> 4));
    if (c > -1 && c !== 64) out.push(((b & 15) << 4) | (c >> 2));
    if (d > -1 && d !== 64) out.push(((c & 3) << 6) | d);
  }
  return out;
}

function chars(value) {
  var out = [];
  for (var i = 0; i < value.length; i++) out.push(value.charCodeAt(i) & 0xff);
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
    if (i % 8 === 0) { t = [SBOX[t[1]] ^ rcon, SBOX[t[2]], SBOX[t[3]], SBOX[t[0]]]; rcon = mul(rcon, 2); }
    else if (i % 8 === 4) { t = [SBOX[t[0]], SBOX[t[1]], SBOX[t[2]], SBOX[t[3]]]; }
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

var FIXED_RK = expandKey(chars("0123456789abcdef0123456789abcdef"));
var FIXED_IV = chars("abcdef9876543210");

function decryptSource(value) {
  var cipher = b64Bytes(value);
  if (cipher.length < 16) return "";
  var out = [], p = FIXED_IV;
  for (var off = 0; off + 16 <= cipher.length; off += 16) {
    var blk = cipher.slice(off, off + 16), pl = decryptBlock(FIXED_RK, blk);
    for (var i = 0; i < 16; i++) out.push(pl[i] ^ p[i]);
    p = blk;
  }
  var pad = out[out.length - 1];
  if (pad > 0 && pad <= 16) out = out.slice(0, out.length - pad);
  return utf8Str(out);
}

function xorFile(html) {
  var re = /\(function\(d,\s*k\)[\s\S]{0,300}?\}\)\(\[([\d,\s]+)\],\s*"([^"]+)"\)/g, m;
  while ((m = re.exec(html)) !== null) {
    var key = m[2], nums = m[1].split(","), out = "";
    for (var i = 0; i < nums.length; i++) {
      var b = parseInt(nums[i].trim(), 10);
      if (!isNaN(b)) out += String.fromCharCode(b ^ key.charCodeAt(i % key.length) ^ ((i * 17 + 13) & 255));
    }
    if (/^https?:\/\//i.test(out)) return out;
  }
  return null;
}

function okru(embed, label) {
  var id = first(/(?:videoembed|video)\/(\d+)/, embed);
  if (!id) return Promise.resolve([]);
  return text("https://ok.ru/dk?cmd=videoPlayerMetadata&mid=" + id, {
    method: "POST",
    headers: { "User-Agent": "Mozilla/5.0 (Linux; Android 13)", Referer: "https://ok.ru/" }
  }).then(function (body) {
    var qmap = { lowest: "240p", low: "360p", sd: "480p", hd: "720p", full: "1080p" };
    var vids = JSON.parse(body).videos || [], out = [];
    for (var i = 0; i < vids.length; i++) {
      var v = vids[i];
      if (!v || v.disallowed || /^mobile$/i.test(v.name || "")) continue;
      var u = String(v.url || "").replace(/\\\//g, "/");
      if (!/^http/.test(u)) continue;
      var q = qmap[String(v.name || "").toLowerCase()] || "auto";
      out.push({ name: ID, title: ID + " - " + label + " " + q + " [MP4]", url: u, quality: q, headers: { Referer: "https://ok.ru/" }, subtitles: [] });
    }
    return out.reverse();
  }).catch(function () { return []; });
}

function vidmoly(embed, referer, label) {
  var target = embed.replace(/\/\/(?:www\.)?vidmoly\.(?:to|me|biz|sx|org)\//i, "//vidmoly.net/");
  return text(target, { referer: referer, headers: { "Sec-Fetch-Dest": "iframe" } }).then(function (html) {
    var m3u8 = first(/sources:\s*\[\s*\{\s*file:\s*["']([^"']+\.m3u8[^"']*)["']/, html)
      || first(/file:\s*["'](https?:\/\/[^"']+\.m3u8[^"']*)["']/, html);
    if (!m3u8) return [];
    var ref = (first(/^(https?:\/\/[^/]+)/, target) || "https://vidmoly.net") + "/";
    return [{ name: ID, title: ID + " - " + label + " [HLS]", url: hlsNoDownload(m3u8), quality: "auto", headers: { Referer: ref }, subtitles: [] }];
  }).catch(function () { return []; });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var isMovie = mediaType === "movie";
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var wantTitle = "";
  var playUrl = null;

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      wantTitle = title;
      if (!title) return null;
      var q = String(title).replace(/^(?:the|a|an)\s+/i, "");
      return text(BASE + "/wp-json/wp/v2/search?search=" + encodeURIComponent(q) + "&per_page=20");
    })
    .then(function (raw) {
      if (!raw) return null;
      var arr;
      try { arr = JSON.parse(raw); } catch (e) { return null; }
      if (!Array.isArray(arr)) return null;
      var best = null, bestScore = 0;
      for (var i = 0; i < arr.length; i++) {
        var it = arr[i] || {};
        var u = abs(BASE, it.url);
        if (!u) continue;
        var path = u.replace(BASE, "");
        var isSeries = path.indexOf("/dizi/") === 0 || path.indexOf("/seri/") !== -1;
        if (isMovie && isSeries) continue;
        if (!isMovie && !isSeries) continue;
        if (path.indexOf("/dizi/") !== 0 && path.indexOf("/film/") !== 0 && path.indexOf("/film_series/") !== 0) continue;
        var t = cleanTitle(it.title);
        if (!t) continue;
        if (sameTitle(t, wantTitle)) {
          var score = t.toLowerCase() === wantTitle.toLowerCase() ? 100 : 80;
          if (score > bestScore) { bestScore = score; best = u; }
          if (bestScore === 100) break;
        }
      }
      if (!best) return null;
      if (isMovie) { playUrl = best; return text(playUrl); }
      return text(best).then(function (detail) {
        var re = /href="([^"]*(?:bolum|episode)[^"]*)"/gi, m, epUrl = null;
        while ((m = re.exec(detail)) !== null) {
          var eu = abs(best, m[1]);
          var sm = /(?:sezon|season)[-_\/]?(\d+).*?(?:bolum|episode)[-_\/]?(\d+)/i.exec(eu);
          if (sm && parseInt(sm[1], 10) === want && parseInt(sm[2], 10) === wantEp) { epUrl = eu; break; }
          var em = parseInt(first(/(?:bolum|episode)[-_\/]?(\d+)/i, eu) || first(/(\d+)[-_]bolum/i, eu) || "0", 10);
          if (want === 1 && em === wantEp) { epUrl = eu; break; }
        }
        if (!epUrl) return null;
        playUrl = epUrl;
        return text(playUrl);
      });
    })
    .then(function (page) {
      if (!page) return [];
      var postId = first(/postid-(\d+)/, page) || first(/data-id="(\d+)"/, page);
      var srcs = [], seenS = {}, m;
      var btnRe = /<button[^>]*class="[^"]*playhd-btn[^"]*"[^>]*data-source="([^"]+)"[^>]*>([\s\S]*?)<\/button>/gi;
      while ((m = btnRe.exec(page)) !== null) {
        var s = m[1].trim();
        if (!s || s === "fragman_url" || seenS[s]) continue;
        seenS[s] = 1;
        srcs.push({ src: s, label: m[2].replace(/<[^>]+>/g, "").trim() || s.replace(/_url$/, "") });
      }
      var jobs = [];
      if (postId && srcs.length) {
        srcs.slice(0, 4).forEach(function (item) {
          jobs.push(text(BASE + "/wp-admin/admin-ajax.php", {
            method: "POST",
            referer: playUrl,
            headers: { "Content-Type": "application/x-www-form-urlencoded" },
            body: "action=get_source_url&post_id=" + encodeURIComponent(postId) + "&source=" + encodeURIComponent(item.src)
          }).then(function (enc) {
            enc = enc.trim();
            if (!enc || enc === "0" || enc === "error") return [];
            var target = abs(playUrl, decryptSource(enc));
            if (!target || !/^https?:/i.test(target)) return [];
            if (/\.(?:m3u8|mp4)(?:\?|$)/i.test(target)) {
              var isH = target.indexOf(".m3u8") !== -1;
              return [{ name: ID, title: ID + " - " + item.label, url: isH ? hlsNoDownload(target) : target, quality: "auto", headers: { Referer: playUrl }, subtitles: [] }];
            }
            if (/ok\.ru|odnoklassniki/i.test(target)) return okru(target, item.label);
            if (/vidmoly\./i.test(target)) return vidmoly(target, playUrl, item.label);
            return text(target, { referer: playUrl }).then(function (inner) {
              var wBase = first(/WORKER_BASE\s*=\s*"([^"]+)"/, inner);
              var wId = first(/VIDEO_ID\s*=\s*(\d+)/, inner);
              if (wBase && wId) {
                wBase = wBase.replace(/\\\//g, "/").replace(/\/+$/, "");
                return text(wBase + "/v/" + wId + "/info", { referer: target }).then(function (info) {
                  var qs = first(/"qualities"\s*:\s*\[([^\]]*)\]/, info) || "";
                  var parts = qs.split(","), qualities = [];
                  for (var qi = 0; qi < parts.length; qi++) {
                    var qn = parseInt(parts[qi], 10);
                    if (qn) qualities.push(qn);
                  }
                  if (!qualities.length) return [{ name: ID, title: ID + " - " + item.label + " [MP4]", url: wBase + "/v/" + wId, quality: "auto", headers: { Referer: wBase + "/", "User-Agent": UA }, subtitles: [] }];
                  return qualities.reverse().map(function (qh) {
                    return { name: ID, title: ID + " - " + item.label + " " + qh + "p [MP4]", url: wBase + "/v/" + wId + "?q=" + qh, quality: qh + "p", headers: { Referer: wBase + "/", "User-Agent": UA }, subtitles: [] };
                  });
                }).catch(function () {
                  return [{ name: ID, title: ID + " - " + item.label + " [MP4]", url: wBase + "/v/" + wId, quality: "auto", headers: { Referer: wBase + "/", "User-Agent": UA }, subtitles: [] }];
                });
              }
              var xf = xorFile(inner);
              if (xf) {
                var baseRef = first(/<base\s+href="([^"]+)"/i, inner) || "https://vidrame.pro/";
                var isHx = xf.indexOf(".m3u8") !== -1 || /vidrame/i.test(baseRef);
                return [{ name: ID, title: ID + " - " + item.label + " [HLS]", url: isHx ? hlsNoDownload(xf) : xf, quality: "auto", headers: { Referer: baseRef, "User-Agent": UA }, subtitles: [] }];
              }
              var clean = inner.replace(/\\\//g, "/");
              var dm = first(/(https?:\/\/[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?)/i, clean);
              if (dm && dm.indexOf("cloudflare.com/static/") === -1) {
                var isH2 = dm.indexOf(".m3u8") !== -1;
                return [{ name: ID, title: ID + " - " + item.label, url: isH2 ? hlsNoDownload(dm) : dm, quality: "auto", headers: { Referer: target, "User-Agent": UA }, subtitles: [] }];
              }
              return [];
            }).catch(function () { return []; });
          }).catch(function () { return []; }));
        });
      }
      return Promise.all(jobs).then(function (lists) {
        var out = [], seenU = {};
        for (var i = 0; i < lists.length; i++) {
          for (var j = 0; j < lists[i].length; j++) {
            if (!seenU[lists[i][j].url]) { seenU[lists[i][j].url] = 1; out.push(lists[i][j]); }
          }
        }
        return out;
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][hdfilmdiziizle] " + (e && e.message));
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
