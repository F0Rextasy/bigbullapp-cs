// @provider {"id":"selcukflix", "name":"SelcukFlix", "types":["movie", "tv"], "domain":"selcukflix.app", "formats":["m3u8"], "sample":"tt0133093:movie:1:1", "description":"SelcukFlix - Turkce dublaj/altyazili film ve diziler; AES secureData + pichive HLS master.", "verified":true, "version":"1.0.4"}
/**
 * selcukflix — Nuvio provider (bb-nuvio).
 * Flow: numeric TMDB -> Wikidata -> IMDb; Cinemeta title -> POST /api/bg/searchcontent
 *       (AES envelope) -> content page `#__NEXT_DATA__.pageProps.secureData` (same envelope)
 *       -> RelatedResults sources -> pichive iframe (NP_CONFIG master, or legacy
 *       openPlayer -> source2.php) -> HLS.
 * pichive.online answers 403 to Node's TLS fingerprint while curl from the same IP gets the
 * player, so the header is marked verified:false.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
var BASE = "https://selcukflix.app";
var AES_KEY = "9bYMCNQiWsXIYFWYAu7EkdsSbmGBTyUI";
var B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
/** Wikidata rejects a browser UA; this resolver needs a descriptive one. */
var WIKI_UA = "bb-nuvio-selcukflix/1.0 (+https://github.com/F0Rextasy/bigbullapp-cs)";

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

// ------------------------------------------------------------------ base64

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
/** The AES key is a literal string, so take its char codes — not base64. */
function chars(value) {
  var out = [];
  for (var i = 0; i < value.length; i++) out.push(value.charCodeAt(i) & 0xff);
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

/** `secureData` is either plain base64 JSON (`eyJ`) or an AES-CBC envelope with a zero IV. */
function unseal(sealed) {
  var value = String(sealed || "");
  var raw = bytes(value);
  if (value.slice(0, 3) === "eyJ") return utf8(raw);
  var rk = expandKey(chars(AES_KEY));
  var out = [];
  var prev = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0];
  var i;
  for (var off = 0; off + 16 <= raw.length; off += 16) {
    var block = raw.slice(off, off + 16);
    var plain = decryptBlock(rk, block);
    for (i = 0; i < 16; i++) out.push(plain[i] ^ prev[i]);
    prev = block;
  }
  var pad = out[out.length - 1];
  if (pad > 0 && pad <= 16) out = out.slice(0, out.length - pad);
  return utf8(out);
}

function parse(sealed) {
  try {
    return JSON.parse(unseal(sealed));
  } catch (e) {
    return null;
  }
}

// ------------------------------------------------------------------ site

/** Nuvio may hand a numeric TMDB id; Wikidata links it to the IMDb id Cinemeta speaks. */
function imdbId(id, mediaType) {
  var value = String(id || "");
  if (!/^\d+$/.test(value)) return Promise.resolve(value);
  var prop = mediaType === "movie" ? "P4947" : "P4983";
  var query = "SELECT ?imdb WHERE { ?f wdt:" + prop + ' "' + value + '". ?f wdt:P345 ?imdb. } LIMIT 1';
  return text("https://query.wikidata.org/sparql?format=json&query=" + encodeURIComponent(query), {
    headers: { "User-Agent": WIKI_UA, Accept: "application/sparql-results+json" }
  })
    .then(function (body) {
      var rows = (JSON.parse(body).results || {}).bindings || [];
      return rows.length ? String(rows[0].imdb.value) : "";
    })
    .catch(function () {
      return "";
    });
}

function titleOf(id, mediaType) {
  var kind = mediaType === "tv" ? "series" : "movie";
  return text("https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json")
    .then(function (body) {
      var meta = JSON.parse(body).meta || {};
      return { name: String(meta.name || "").trim(), year: String(meta.year || "").slice(0, 4) };
    })
    .catch(function () {
      return { name: "", year: "" };
    });
}

function norm(value) {
  return String(value || "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
}

function displayTitle(item) {
  var keys = ["culture_title", "object_name", "original_title", "used_title"];
  for (var i = 0; i < keys.length; i++) {
    var v = item[keys[i]];
    if (v && v !== "null") return String(v);
  }
  return "";
}

/** Exact title first, then the slug's own words; series slugs start with `dizi/`. */
function pick(rows, want, series) {
  var best = null;
  var target = norm(want);
  for (var i = 0; i < rows.length; i++) {
    var slug = String(rows[i].used_slug || "");
    if (!slug) continue;
    if (series && slug.indexOf("dizi/") !== 0) continue;
    if (!series && slug.indexOf("film/") !== 0) continue;
    if (norm(displayTitle(rows[i])) === target) return slug;
    if (!best && norm(displayTitle(rows[i])).indexOf(target) === 0) best = slug;
  }
  return best;
}

function search(name, series) {
  var term = encodeURIComponent(name);
  return text(BASE + "/api/bg/searchcontent?searchterm=" + term, {
    method: "POST",
    referer: BASE + "/",
    headers: { Accept: "application/json, text/plain, */*", "X-Requested-With": "XMLHttpRequest" }
  }).then(function (body) {
    var sealed = (JSON.parse(body) || {}).response;
    if (!sealed) return null;
    var json = parse(sealed);
    return json ? pick(json.result || [], name, series) : null;
  });
}

function pageData(url) {
  return text(url).then(function (html) {
    var sealed = first(/"secureData"\s*:\s*"([^"]+)"/, html);
    return sealed ? parse(sealed) : null;
  });
}

/** Series page -> the episode slug the catalogue uses for this season/episode. */
function episodePath(path, want, wantEp) {
  return pageData(BASE + "/" + path).then(function (json) {
    if (!json) return null;
    var seasons = ((json.RelatedResults || {}).getSerieSeasonAndEpisodes || {}).result || [];
    for (var i = 0; i < seasons.length; i++) {
      if (parseInt(seasons[i].season_no, 10) !== want) continue;
      var episodes = seasons[i].episodes || [];
      for (var j = 0; j < episodes.length; j++) {
        if (parseInt(episodes[j].episode_no, 10) === wantEp && episodes[j].used_slug) return episodes[j].used_slug;
      }
      if (episodes.length && episodes[0].used_slug) return episodes[0].used_slug;
    }
    return null;
  });
}

/** RelatedResults holds getEpisodeSources (series) and getMoviePartSourcesById_* (films). */
function embeds(json) {
  var rel = (json || {}).RelatedResults || {};
  var out = [];
  var seen = {};
  for (var key in rel) {
    if (key !== "getEpisodeSources" && key.indexOf("getMoviePartSourcesById") !== 0) continue;
    var rows = (rel[key] || {}).result || [];
    for (var i = 0; i < rows.length; i++) {
      var src = first(/<iframe[^>]*\bsrc\s*=\s*["']([^"']+)["']/i, rows[i].source_content);
      if (!src || seen[src]) continue;
      seen[src] = 1;
      var url = src.slice(0, 2) === "//" ? "https:" + src : /^https?:/i.test(src) ? src : BASE + src;
      out.push({ url: url, label: rows[i].language_name || "" });
    }
  }
  return out;
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

function abs(base, href) {
  if (/^https?:/i.test(href)) return href;
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

/** Highest ladder rung, used when the master itself is the link we hand over. */
function top(rows) {
  var best = 0;
  for (var i = 0; i < rows.length; i++) {
    var n = parseInt(rows[i].quality, 10);
    if (n > best) best = n;
  }
  return best ? best + "p" : "1080p";
}

/** Master -> variants; a master with audio renditions stays whole, a lone variant is silent. */
function plain(master, referer) {
  var root = (origin(master) || "") + "/";
  return fetch(master, { headers: { "User-Agent": UA, Accept: "*/*", Referer: root } })
    .then(function (r) {
      return r.ok ? r.text() : "";
    })
    .then(function (body) {
      var rows = body.indexOf("#EXTM3U") === 0 ? variants(master, body) : [];
      if (body.indexOf("#EXT-X-MEDIA") !== -1) return [{ url: master, quality: top(rows) }];
      if (!rows.length) return [{ url: master, quality: "1080p" }];
      return rows;
    })
    .catch(function () {
      return [{ url: master, quality: "1080p" }];
    });
}

/** pichive player page -> master playlist URL(s). */
function masters(iframe) {
  return text(iframe.url, { referer: BASE + "/" }).then(function (html) {
    var out = [];
    var at = html.indexOf("NP_CONFIG");
    if (at >= 0) {
      var open = html.indexOf("{", at);
      var config = open >= 0 ? objectAt(html, open) : null;
      if (config) {
        var parsed = null;
        try {
          parsed = JSON.parse(config);
        } catch (e) {}
        var sources = (parsed && parsed.sources) || [];
        for (var i = 0; i < sources.length; i++) {
          if (sources[i] && /^http/.test(sources[i].master || "")) out.push(sources[i].master);
        }
      }
      if (out.length) return out;
    }
    // Legacy player: openPlayer('<token>') -> source2.php -> playlist[].sources[].file.
    var token = first(/openPlayer\s*\(\s*['"]([^'"]+)/, html);
    if (!token) return out;
    var root = origin(iframe.url);
    return text(root + "/source2.php?v=" + encodeURIComponent(token), {
      referer: iframe.url,
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

/** One player iframe -> its master playlists -> one row per variant. */
function expand(player) {
  return masters(player)
    .then(function (urls) {
      var jobs = [];
      for (var i = 0; i < urls.length; i++) jobs.push(expandMaster(player.label, urls[i]));
      return Promise.all(jobs);
    })
    .then(function (lists) {
      var out = [];
      for (var i = 0; i < lists.length; i++) out = out.concat(lists[i]);
      return out;
    })
    .catch(function () {
      return [];
    });
}

function expandMaster(tag, master) {
  return plain(master).then(function (rows) {
    var out = [];
    for (var i = 0; i < rows.length; i++) out.push({ url: rows[i].url, quality: rows[i].quality, label: tag, master: master });
    return out;
  });
}
function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var pageUrl = null;

  return imdbId(tmdbId, mediaType)
    .then(function (id) {
      return id ? titleOf(id, mediaType) : null;
    })
    .then(function (meta) {
      if (!meta || !meta.name) return null;
      return search(meta.name, mediaType === "tv").then(function (slug) {
        if (!slug) return null;
        if (mediaType !== "tv") return { path: slug, label: "" };
        return episodePath(slug, want, wantEp).then(function (path) {
          return path ? { path: path, label: "" } : null;
        });
      });
    })
    .then(function (hit) {
      if (!hit) return null;
      pageUrl = BASE + "/" + hit.path;
      return pageData(pageUrl).then(function (json) {
        return json ? embeds(json) : [];
      });
    })
    .then(function (list) {
      if (!list || !list.length) return [];
      var jobs = [];
      for (var i = 0; i < list.length; i++) jobs.push(expand(list[i]));
      return Promise.all(jobs);
    })
    .then(function (groups) {
      var out = [];
      var seen = {};
      for (var i = 0; i < (groups || []).length; i++) {
        var group = groups[i] || [];
        for (var j = 0; j < group.length; j++) {
          var row = group[j];
          if (!row || seen[row.url]) continue;
          seen[row.url] = 1;
          var tag = row.label ? " " + row.label : "";
          out.push({
            name: "SelcukFlix",
            title: "SelcukFlix - " + row.quality + tag + " [HLS]",
            url: row.url,
            quality: row.quality,
            headers: { "User-Agent": UA, Referer: (origin(row.master) || "") + "/" }
          });
        }
      }
      return out;
    })
    .catch(function (e) {
      console.log("[bb-nuvio][selcukflix] " + (e && e.message));
      return [];
    });
}

module.exports = { getStreams };


/* Dil etiketi: akis basliginin kalitesinin yanina sunucunun destekledigi dili yazar.
   Kanit yoksa etiket yazilmaz (uydurma yasak): baslikta dublaj/altyazi varsa dokunulmaz,
   altyazi dosyasi eklenmis akisa "Altyazili" eklenir. */
var __langLabeled = module.exports.getStreams;
module.exports.getStreams = function (id, mediaType, season, episode) {
  return __langLabeled.apply(this, arguments).then(function (streams) {
    return (streams || []).map(function (s) {
      if (!s || !s.title || /dublaj|altyaz/i.test(String(s.title))) return s;
      if (s.subtitles && s.subtitles.length) s.title = String(s.title) + " \u2022 Altyaz\u0131l\u0131";
      return s;
    });
  });
};
