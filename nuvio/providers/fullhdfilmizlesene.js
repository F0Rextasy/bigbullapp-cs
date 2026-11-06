// @provider {"id":"fullhdfilmizlesene","name":"FullHDFilmizlesene","types":["movie"],"domain":"fullhdfilmizlesene.now","formats":["m3u8"],"sample":"tt0133093:movie:1:1","description":"FullHDFilmizlesene - Turkce dublaj/altyazili filmler; scx kaynagi -> rapidvid atom HLS + altyazi.","verified":true}
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
  if (!want) return list[0] || null;
  var best = null;
  var top = 0;
  for (var i = 0; i < list.length; i++) {
    var card = list[i];
    var name = norm(card.title);
    var score = name === want ? 3 : name.indexOf(want) === 0 ? 2 : (" " + name + " ").indexOf(" " + want + " ") !== -1 ? 1 : 0;
    if (!score) continue;
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

/** rapidvid embed -> atomCodecTelemetry h264 HLS master + jwSetup.tracks subtitles. */
function resolveAtom(embed, label) {
  return text(embed, { referer: BASE + "/" }).then(function (html) {
    var raw = first(/atomCodecTelemetry=(\{[\s\S]*?\});/, html);
    if (!raw) return null;
    var telemetry;
    try {
      telemetry = JSON.parse(raw);
    } catch (e) {
      return null;
    }
    var stream = /^http/.test(telemetry.h264 || "") ? telemetry.h264 : /^http/.test(telemetry.av1 || "") ? telemetry.av1 : null;
    if (!stream) return null;

    var subs = [];
    var tracks = first(/jwSetup\.tracks\s*=\s*(\[[\s\S]*?\]);/, html);
    if (tracks) {
      var parsed;
      try {
        parsed = JSON.parse(tracks);
      } catch (e) {
        parsed = [];
      }
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
        for (var i = 0; i < source.sx.t.length; i++) jobs.push(job(source, key, source.sx.t[i], filmUrl));
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
            url: row.url,
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
