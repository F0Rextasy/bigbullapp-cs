// @provider {"id":"fullhdfilmizlesene", "name":"FullHDFilmizlesene", "types":["movie"], "domain":"fullhdfilmizlesene.now", "formats":["m3u8"], "sample":"tt0133093:movie:1:1", "description":"FullHDFilmizlesene - Turkce dublaj/altyazili filmler; scx kaynagi -> rapidvid atom HLS + altyazi.", "verified":true, "version":"1.0.15"}
/**
 * fullhdfilmizlesene — Nuvio provider (bb-nuvio).
 * Flow: TMDB id -> Wikidata -> IMDb -> Cinemeta title -> /arama/<q> -> film page
 *       -> `var scx` (base64 rot13 sources) -> rapidvid atom player -> atomCodecTelemetry
 *       h264 HLS master (kept whole: it owns the audio renditions) + jwSetup captions.
 * Cloudflare answers Node's default TLS cipher list with a managed challenge (curl and a
 * Chrome-cipher client get 200 from the same IP), so the header stays verified:false.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
var BASE = "https://www.fullhdfilmizlesene.now";
var WIKI_UA = "bb-nuvio/1.0 (nuvio provider; bb-nuvio@users.noreply.github.com)";
var B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
  if (o.headers) for (var k in o.headers) headers[k] = o.headers[k];
  return fetch(url, { method: o.method || "GET", headers: headers }).then(function (r) {
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
  while ((m = pattern.exec(String(body || ""))) !== null) out.push(m);
  return out;
}

/** atob is absent in the Nuvio sandbox: base64 -> byte string by hand. */
function decode(value) {
  var s = String(value || "").trim();
  if (!s) return "";
  s += "====".slice(0, (4 - (s.length % 4)) % 4);
  var out = "";
  var buf = 0;
  var bits = 0;
  for (var i = 0; i < s.length; i++) {
    var v = B64.indexOf(s.charAt(i));
    if (v < 0) continue;
    buf = (buf << 6) | v;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out += String.fromCharCode((buf >> bits) & 255);
    }
  }
  return out;
}

/** rapidvid `av('...')`: ters base64 -> K9L kaydırması -> base64. */
function avDecode(value) {
  var first = decode(String(value || "").split("").reverse().join(""));
  var salt = "K9L";
  var shifted = "";
  for (var i = 0; i < first.length; i++) {
    shifted += String.fromCharCode(first.charCodeAt(i) - (salt.charCodeAt(i % salt.length) % 5 + 1));
  }
  return decode(shifted);
}

/** Sources are stored as base64(rot13(url)); titles as plain base64. */
function decodeSource(value) {
  if (!value) return null;
  var rotated = String(value).replace(/[a-zA-Z]/g, function (c) {
    var code = c.charCodeAt(0);
    var base = code <= 90 ? 65 : 97;
    return String.fromCharCode(((code - base + 13) % 26) + base);
  });
  return decode(rotated);
}

/** Nuvio can pass a numeric TMDB id; Wikidata maps it to the IMDb id Cinemeta needs. */
function imdbId(id, mediaType) {
  var value = String(id || "").trim();
  if (/^tt\d+/.test(value)) return Promise.resolve(value);
  if (!/^\d+$/.test(value)) return Promise.resolve("");
  var property = mediaType === "tv" ? "P4983" : "P4947";
  var query = "SELECT ?imdb WHERE { ?w wdt:" + property + ' "' + value + '". ?w wdt:P345 ?imdb. } LIMIT 1';
  return fetch("https://query.wikidata.org/sparql?format=json&query=" + encodeURIComponent(query), {
    headers: { "User-Agent": WIKI_UA, Accept: "application/sparql-results+json" }
  })
    .then(function (r) {
      return r.ok ? r.text() : "";
    })
    .then(function (body) {
      var rows = JSON.parse(body).results.bindings;
      return rows && rows[0] && rows[0].imdb ? rows[0].imdb.value : "";
    })
    .catch(function () {
      return "";
    });
}

function titleOf(id, mediaType) {
  if (/^__alt__:/.test(String(id))) return Promise.resolve({ name: decodeURIComponent(String(id).slice(8)), year: "" });
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

/** Turkish letters have no a-z spelling, so "Kış Uykusu" would otherwise normalize to "k uykusu". */
function norm(value) {
  return String(value || "")
    .replace(/[ıİI]/g, "i")
    .replace(/[şŞ]/g, "s")
    .replace(/[ğĞ]/g, "g")
    .replace(/[çÇ]/g, "c")
    .replace(/[öÖ]/g, "o")
    .replace(/[üÜ]/g, "u")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
}

/** `<li class="film">`: a.tt title/href, span.film-title (local) + span.kt (original), span.film-yil year. */
function cards(html) {
  var out = [];
  var blocks = all(/<li class="film">([\s\S]*?)<\/li>/g, html);
  for (var i = 0; i < blocks.length; i++) {
    var href = first(/<a class="tt" href="([^"]+)"/, blocks[i][1]) || first(/href="([^"]+)"/, blocks[i][1]);
    if (!href) continue;
    var local = first(/<span class="film-title">([^<]*)</, blocks[i][1]) || first(/>([^<]*) izle</, blocks[i][1]) || "";
    var original = first(/<span class="kt">([^<]*)</, blocks[i][1]) || "";
    out.push({
      url: href,
      title: (local + " " + original).trim(),
      year: (first(/<span class="film-yil">(\d{4})</, blocks[i][1]) || "").trim()
    });
  }
  return out;
}

/** The site appends the original title ("Başlangıç - Inception"), so rank instead of prefix-only. */
function pick(list, title, year) {
  var want = norm(title);
  if (!want) return null;
  var best = null;
  var top = 0;
  for (var i = 0; i < list.length; i++) {
    var card = list[i];
    var name = norm(card.title);
    var score = name === want ? 3 : name.indexOf(want + " ") === 0 ? 2 : (" " + name + " ").indexOf(" " + want + " ") !== -1 ? 1 : 0;
    if (score < 3 && !(score && year && card.year === year)) continue;
    if (year && card.year === year) score += 10;
    if (score > top) {
      best = card;
      top = score;
    }
  }
  return best;
}

function abs(base, href) {
  if (/^https?:/i.test(href)) return href;
  if (href.charAt(0) === "/") {
    var origin = /^(https?:\/\/[^/]+)/.exec(base);
    return origin ? origin[1] + href : href;
  }
  return base.replace(/[^/]*$/, "") + href;
}

/** Nuvio yalnız progressive indirir; HLS'yi işaretle ki sahte dosya inmesin (fragment HTTP'de gönderilmez). */
function hlsNoDownload(url) {
  var u = String(url || "");
  if (/\.m3u8(\?|$)/i.test(u) || u.indexOf("#") !== -1) return u;
  return u + "#.m3u8";
}

/** Cinema-aspect masters (3840x1600) still read as their ladder rung, so label by width first. */
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

function subtitleLang(label) {
  if (/rk\u00e7e|ürk|Forced/i.test(label)) return "tr";
  if (/ngiliz|English/i.test(label)) return "en";
  return String(label || "").trim().toLowerCase().slice(0, 2) || "tr";
}

/** rapidvid embed -> atomCodecTelemetry, window._p8 (/vx/) or jwSetup.sources HLS master + captions. */
function resolveAtom(embed, label) {
  return text(embed, { referer: BASE + "/" }).then(function (html) {
    var raw = first(/atomCodecTelemetry=(\{[\s\S]*?\});/, html);
    var telemetry = {};
    if (raw) {
      try {
        telemetry = JSON.parse(raw);
      } catch (e) {
        telemetry = {};
      }
    }
    var p8 = null;
    var p8Raw = first(/window\._p8\s*=\s*['"]([^'"]+)['"]/, html);
    if (p8Raw) {
      try {
        p8 = JSON.parse(avDecode(p8Raw));
      } catch (e) {
        p8 = null;
      }
    }
    var sources = first(/jwSetup\.sources\s*=\s*(\[[\s\S]*?\]);/, html);
    var encoded = sources ? first(/av\('([^']+)'\)/, sources) : null;
    var plain = sources ? first(/"file"\s*:\s*"([^"]+)"/, sources) : null;
    var stream = /^http/.test(telemetry.h264 || "") ? telemetry.h264
      : /^http/.test(telemetry.av1 || "") ? telemetry.av1
      : p8 && /^http/.test(p8.cm || "") ? p8.cm
      : p8 && /^http/.test(p8.tm || "") ? p8.tm
      : encoded ? avDecode(encoded)
      : plain;
    if (!/^http/.test(stream || "")) return null;

    var subs = [];
    var parsed = p8 && Array.isArray(p8.ct) ? p8.ct : null;
    if (!parsed) {
      var tracks = first(/jwSetup\.tracks\s*=\s*(\[[\s\S]*?\]);/, html);
      if (tracks) {
        try {
          parsed = JSON.parse(tracks);
        } catch (e) {
          parsed = [];
        }
      }
    }
    if (parsed) {
      for (var i = 0; i < parsed.length && subs.length < 4; i++) {
        var file = parsed[i] && parsed[i].file;
        if (!file) continue;
        var lang = subtitleLang(parsed[i].label);
        subs.push({ url: file, language: lang, name: (parsed[i].label || lang).trim() + " [FullHDFilmizlesene]" });
      }
    }

    return bestQuality(stream, embed).then(function (quality) {
      return [{ url: stream, quality: quality, label: label, subtitles: subs }];
    });
  });
}

/** The master owns its #EXT-X-MEDIA audio groups, so it must reach the player whole:
 *  a child variant is video-only (silent) and the best variant only names the row. */
function bestQuality(master, referer) {
  return fetch(master, { headers: { "User-Agent": UA, Accept: "*/*", Referer: referer } })
    .then(function (r) {
      return r.ok ? r.text() : "";
    })
    .then(function (body) {
      var rows = variants(master, body);
      var top = 0;
      for (var i = 0; i < rows.length; i++) top = Math.max(top, parseInt(rows[i].quality, 10) || 0);
      return top ? top + "p" : "1080p";
    })
    .catch(function () {
      return "1080p";
    });
}

/** One scx entry -> rows. The label is captured here, not read after the loop. */
function job(source, key, entry, referer) {
  var label = decode(source.tt).trim() || key;
  var embed = decodeSource(entry);
  if (!embed || !/^https?:/i.test(embed)) return Promise.resolve(null);
  if (embed.indexOf("rapidvid.") !== -1) {
    return resolveAtom(embed, label).catch(function () {
      return null;
    });
  }
  if (embed.indexOf(".m3u8") !== -1) {
    return bestQuality(embed, referer)
      .then(function (quality) {
        return [{ url: embed, quality: quality, label: label, subtitles: [] }];
      })
      .catch(function () {
        return null;
      });
  }
  return Promise.resolve(null);
}

function getStreams(tmdbId, mediaType) {
  var filmUrl = null;

  return imdbId(tmdbId, mediaType)
    .then(function (id) {
      if (!id) return null;
      return titleOf(id, mediaType).then(function (meta) {
        if (!meta.name) return null;
        var term = encodeURIComponent(meta.name).replace(/%20/g, "+");
        return text(BASE + "/arama/" + term).then(function (search) {
          var hit = pick(cards(search), meta.name, meta.year);
          return hit ? abs(BASE, hit.url) : null;
        });
      });
    })
    .then(function (url) {
      if (!url) return null;
      filmUrl = url;
      return text(filmUrl);
    })
    .then(function (page) {
      if (!page) return [];
      var raw = first(/var scx = (\{[\s\S]*?\});/, page);
      if (!raw) return [];
      var scx;
      try {
        scx = JSON.parse(raw);
      } catch (e) {
        return [];
      }
      var jobs = [];
      for (var key in scx) {
        var source = scx[key];
        if (!source || !source.sx || !source.sx.t) continue;
        var entries = Array.isArray(source.sx.t)
          ? source.sx.t
          : (typeof source.sx.t === "object" ? Object.keys(source.sx.t).map(function (k) { return source.sx.t[k]; }) : []);
        for (var i = 0; i < entries.length; i++) jobs.push(job(source, key, entries[i], filmUrl));
      }
      if (!jobs.length) return [];
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
          out.push({
            name: "FullHDFilmizlesene",
            title: "FullHDFilmizlesene - " + row.quality + " [" + (row.label || "Atom") + "]",
            url: hlsNoDownload(row.url),
            quality: row.quality,
            headers: { "User-Agent": UA, Referer: filmUrl },
            subtitles: row.subtitles
          });
        }
      }
      return out;
    })
    .catch(function (e) {
      console.log("[bb-nuvio][fullhdfilmizlesene] " + (e && e.message));
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
