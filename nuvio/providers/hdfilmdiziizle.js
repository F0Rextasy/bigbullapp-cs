// @provider {"id":"hdfilmdiziizle","name":"HDFilmDiziIzle","types":["movie"],"domain":"hdfilmdiziizle.com","formats":["m3u8"],"description":"HDFilmDiziIzle - Turkce dublaj/altyazili film arsivi; AES korumali playhd kaynak cozucu (HLS).","sample":"tt0795461:movie:0:0"}
/**
 * hdfilmdiziizle — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> wp-json search -> film page -> admin-ajax get_source_url
 *       -> AES-256-CBC (sabit anahtar/IV) -> playhd stream.php -> XOR'lu jwplayer file -> HLS.
 * AES is implemented inline: the sandbox has no crypto module.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://www.hdfilmdiziizle.com";
var KEY = "0123456789abcdef0123456789abcdef";
var IV = "abcdef9876543210";
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

function form(fields) {
  var out = [];
  for (var k in fields) out.push(encodeURIComponent(k) + "=" + encodeURIComponent(fields[k]));
  return out.join("&");
}

function titleOf(id, mediaType) {
  var kind = mediaType === "tv" ? "series" : "movie";
  return text("https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json")
    .then(function (body) {
      var meta = JSON.parse(body).meta || {};
      return { name: String(meta.name || "").trim(), year: String(meta.releaseInfo || meta.year || "").slice(0, 4) };
    })
    .catch(function () {
      return { name: "", year: "" };
    });
}

function norm(value) {
  return String(value || "")
    .toLowerCase()
    .replace(/ı/g, "i").replace(/ş/g, "s").replace(/ğ/g, "g")
    .replace(/ü/g, "u").replace(/ö/g, "o").replace(/ç/g, "c")
    .replace(/^the\s+/, "")
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
}

/** Site search is fuzzy (queries like "The Matrix" land on John Wick); strip the article. */
function stripArticle(value) {
  return String(value || "").replace(/^(?:the|a|an)\s+/i, "").trim();
}

function searchUrls(title) {
  if (!title) return Promise.resolve([]);
  return text(BASE + "/wp-json/wp/v2/search?search=" + encodeURIComponent(title) + "&per_page=20")
    .then(function (body) {
      var list = [];
      try {
        list = JSON.parse(body) || [];
      } catch (e) {
        list = [];
      }
      var out = [];
      for (var i = 0; i < list.length; i++) {
        var url = list[i].url || "";
        if (!/\/(?:film|dizi)\//.test(url) || out.indexOf(url) !== -1) continue;
        out.push(url);
      }
      return out.slice(0, 3);
    })
    .catch(function () {
      return [];
    });
}

function pageInfo(page) {
  var raw = first(/<meta property="og:title" content="([^"]+)"/, page) || first(/<h1[^>]*>([\s\S]*?)<\/h1>/, page) || "";
  return {
    title: norm(raw.replace(/<[^>]+>/g, " ").replace(/\s+izle\b[\s\S]*$/i, "")),
    year: first(/\((\d{4})\)/, raw)
  };
}

/** Titles are localised ("Başlangıç" for Inception), so an unambiguous release year also counts. */
function firstValid(urls, meta) {
  var want = norm(meta.name);
  return Promise.all(
    urls.map(function (url) {
      return text(url)
        .then(function (page) {
          return { url: url, page: page, info: pageInfo(page) };
        })
        .catch(function () {
          return null;
        });
    })
  ).then(function (list) {
    var byYear = [];
    for (var i = 0; i < list.length; i++) {
      var c = list[i];
      if (!c) continue;
      var title = c.info.title;
      if (!want || (title && (title.indexOf(want) !== -1 || want.indexOf(title) !== -1))) return c;
      if (meta.year && c.info.year === meta.year) byYear.push(c);
    }
    return byYear.length === 1 ? byYear[0] : null;
  });
}

// ------------------------------------------------------------------ base64

function atobJs(value) {
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

function bytesOf(str) {
  var out = [];
  for (var i = 0; i < str.length; i++) out.push(str.charCodeAt(i) & 0xff);
  return out;
}

function expandKey(key) {
  var Nk = 8;
  var total = 60;
  var w = [];
  var i;
  for (i = 0; i < Nk; i++) w[i] = [key[4 * i], key[4 * i + 1], key[4 * i + 2], key[4 * i + 3]];
  var rcon = 1;
  for (i = Nk; i < total; i++) {
    var t = w[i - 1].slice();
    if (i % Nk === 0) {
      t = [SBOX[t[1]] ^ rcon, SBOX[t[2]], SBOX[t[3]], SBOX[t[0]]];
      rcon = mul(rcon, 2);
    } else if (i % Nk === 4) {
      t = [SBOX[t[0]], SBOX[t[1]], SBOX[t[2]], SBOX[t[3]]];
    }
    w[i] = [w[i - Nk][0] ^ t[0], w[i - Nk][1] ^ t[1], w[i - Nk][2] ^ t[2], w[i - Nk][3] ^ t[3]];
  }
  var rk = [];
  for (i = 0; i < total; i++) rk = rk.concat(w[i]);
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
      var a0 = s[4 * c], a1 = s[4 * c + 1], a2 = s[4 * c + 2], a3 = s[4 * c + 3];
      s[4 * c] = mul(a0, 14) ^ mul(a1, 11) ^ mul(a2, 13) ^ mul(a3, 9);
      s[4 * c + 1] = mul(a0, 9) ^ mul(a1, 14) ^ mul(a2, 11) ^ mul(a3, 13);
      s[4 * c + 2] = mul(a0, 13) ^ mul(a1, 9) ^ mul(a2, 14) ^ mul(a3, 11);
      s[4 * c + 3] = mul(a0, 11) ^ mul(a1, 13) ^ mul(a2, 9) ^ mul(a3, 14);
    }
  }
  return s;
}

function decryptSource(encoded) {
  var raw = atobJs(encoded);
  var rk = expandKey(bytesOf(KEY));
  var prev = bytesOf(IV);
  var out = [];
  var i;
  for (var off = 0; off + 16 <= raw.length; off += 16) {
    var block = raw.slice(off, off + 16);
    var plain = decryptBlock(rk, block);
    for (i = 0; i < 16; i++) out.push(plain[i] ^ prev[i]);
    prev = block;
  }
  var pad = out[out.length - 1];
  if (pad > 0 && pad <= 16) out = out.slice(0, out.length - pad);
  var str = "";
  for (i = 0; i < out.length; i++) str += String.fromCharCode(out[i]);
  return str;
}

// ------------------------------------------------------------------ player

/** playhd player hides the master in an inline XOR of a byte array with a key string. */
function xorFile(html) {
  var re = /\(function\(d,\s*k\)[\s\S]{0,300}?\}\)\(\[([\d,\s]+)\],\s*"([^"]+)"\)/g;
  var text = String(html || "");
  var m;
  while ((m = re.exec(text)) !== null) {
    var nums = m[1].split(",");
    var key = m[2];
    var out = "";
    for (var i = 0; i < nums.length; i++) {
      var byte = parseInt(nums[i], 10);
      if (isNaN(byte)) continue;
      out += String.fromCharCode(byte ^ key.charCodeAt(i % key.length) ^ ((i * 17 + 13) & 255));
    }
    if (/^https?:/i.test(out)) return out;
  }
  return null;
}

function readQuality(masterUrl, referer) {
  return fetch(masterUrl, { headers: { "User-Agent": UA, Referer: referer } })
    .then(function (r) {
      return r.ok ? r.text() : "";
    })
    .then(function (body) {
      var best = 0;
      var re = /RESOLUTION=\d+x(\d+)/g;
      var m;
      while ((m = re.exec(body)) !== null) {
        var h = parseInt(m[1], 10);
        if (h > best) best = h;
      }
      return best ? best + "p" : "HD";
    })
    .catch(function () {
      return "HD";
    });
}

function row(url, label, referer, subs) {
  var hls = url.indexOf(".m3u8") !== -1;
  return readQuality(url, referer).then(function (quality) {
    return {
      name: "HDFilmDiziIzle",
      title: "HDFilmDiziIzle - " + quality + " [" + label + "]",
      url: url,
      quality: quality,
      headers: { "User-Agent": UA, Referer: referer },
      subtitles: hls ? subs || [] : []
    };
  });
}

function resolveTarget(target, filmUrl, label) {
  if (/\.(?:m3u8|mp4)(?:\?|$)/.test(target)) return row(target, label, filmUrl, []);
  return text(target, { referer: filmUrl }).then(function (page) {
    var direct = xorFile(page) || first(/(https?:\/\/[^"'\s<>\\]+?\.m3u8(?:\?[^"'\s<>\\]*)?)/, page);
    if (!direct) return null;
    var subs = [];
    var re = /(https?:\/\/[^"'\s<>\\]+?\.(?:vtt|srt)(?:\?[^"'\s<>\\]*)?)/g;
    var m;
    while ((m = re.exec(page)) !== null && subs.length < 4) {
      subs.push({ url: m[1], language: (first(/_?([a-z]{3})\.(?:vtt|srt)/i, m[1]) || "tr").toLowerCase(), name: "Altyazi [HDFilmDiziIzle]" });
    }
    // Master only answers to its own player origin (the page's <base href>), not the site.
    var base = first(/<base href="([^"]+)"/, page) || "https://vidrame.pro/";
    return row(direct, label, base, subs);
  });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var filmUrl = null;

  return titleOf(tmdbId, mediaType)
    .then(function (meta) {
      if (!meta.name) return null;
      return searchUrls(meta.name)
        .then(function (urls) {
          return firstValid(urls, meta);
        })
        .then(function (hit) {
          if (hit) return hit;
          return searchUrls(stripArticle(meta.name)).then(function (urls) {
            return firstValid(urls, meta);
          });
        });
    })
    .then(function (hit) {
      if (!hit) return [];
      filmUrl = hit.url;
      var page = hit.page;
      var postId = first(/postid-(\d+)/, page) || first(/data-id="(\d+)/, page);
      if (!postId) return [];
      var sources = [];
      var re = /<button[^>]*class="[^"]*playhd-btn[^"]*"[^>]*data-source="([^"]+)"/g;
      var m;
      while ((m = re.exec(page)) !== null) {
        if (m[1] !== "fragman_url" && sources.indexOf(m[1]) === -1) sources.push(m[1]);
      }
      return Promise.all(
        sources.map(function (source) {
          return text(BASE + "/wp-admin/admin-ajax.php", {
            method: "POST",
            referer: filmUrl,
            headers: { "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8", "X-Requested-With": "XMLHttpRequest" },
            body: form({ action: "get_source_url", post_id: postId, source: source })
          })
            .then(function (enc) {
              var decoded = encryptGuard(enc.trim());
              if (!decoded) return null;
              var target = /^https?:/i.test(decoded) ? decoded : decoded.charAt(0) === "/" ? BASE + decoded : null;
              if (!target) return null;
              return resolveTarget(target, filmUrl, source.replace(/_url$/, ""));
            })
            .catch(function () {
              return null;
            });
        })
      );
    })
    .then(function (rows) {
      var out = [];
      var seen = {};
      var list = rows || [];
      for (var i = 0; i < list.length; i++) {
        if (!list[i] || seen[list[i].url]) continue;
        seen[list[i].url] = 1;
        out.push(list[i]);
      }
      return out;
    })
    .catch(function (e) {
      console.log("[bb-nuvio][hdfilmdiziizle] " + (e && e.message));
      return [];
    });
}

function encryptGuard(value) {
  if (!value || value === "0" || value === "error") return "";
  try {
    return decryptSource(value);
  } catch (e) {
    return "";
  }
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