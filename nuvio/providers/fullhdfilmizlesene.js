// @provider {"id":"fullhdfilmizlesene","name":"FullHDFilmizlesene","types":["movie"],"domain":"fullhdfilmizlesene.now","formats":["m3u8"],"sample":"tt0133093:movie:1:1","description":"FullHDFilmizlesene - Turkce dublaj/altyazili filmler; scx kaynagi -> rapidvid atom HLS + altyazi.","verified":false}
/**
 * fullhdfilmizlesene — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /arama/<q> -> film page -> `var scx` (base64 rot13 sources)
 *       -> rapidvid atom player -> atomCodecTelemetry h264 HLS master + jwSetup caption tracks.
 * The site answers Cloudflare "managed challenge" to Node's TLS fingerprint (curl gets the
 * real page from the same IP), so the header is marked verified:false.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
var BASE = "https://www.fullhdfilmizlesene.now";
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

/** `<li class="film">`: a.tt title/href, span.film-yil year. */
function cards(html) {
  var out = [];
  var blocks = all(/<li class="film">([\s\S]*?)<\/li>/g, html);
  for (var i = 0; i < blocks.length; i++) {
    var href = first(/<a class="tt" href="([^"]+)"/, blocks[i][1]) || first(/href="([^"]+)"/, blocks[i][1]);
    if (!href) continue;
    out.push({
      url: href,
      title: first(/<span class="film-title">([^<]*)</, blocks[i][1]) || first(/>([^<]*) izle</, blocks[i][1]) || "",
      year: (first(/<span class="film-yil">(\d{4})</, blocks[i][1]) || "").trim()
    });
  }
  return out;
}

function pick(list, title, year) {
  var want = norm(title);
  var best = null;
  for (var i = 0; i < list.length; i++) {
    var card = list[i];
    var name = norm(card.title);
    if (!want || name.indexOf(want) !== 0) continue;
    if (year && card.year === year) return card;
    if (!best) best = card;
  }
  return best || (want ? null : list[0]);
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

    return plain(stream, embed, label).then(function (rows) {
      var out = [];
      for (var j = 0; j < rows.length; j++) out.push({ url: rows[j].url, quality: rows[j].quality, label: label, subtitles: subs });
      return out;
    });
  });
}

/** Master playlist -> one entry per variant; keeps the master when it has a single child. */
function plain(master, referer, label) {
  return fetch(master, { headers: { "User-Agent": UA, Accept: "*/*", Referer: referer } })
    .then(function (r) {
      return r.ok ? r.text() : "";
    })
    .then(function (body) {
      if (body.indexOf("#EXTM3U") !== 0) return [];
      var rows = variants(master, body);
      if (!rows.length) return [{ url: master, quality: "1080p" }];
      var out = [];
      for (var i = 0; i < rows.length; i++) out.push({ url: rows[i].url, quality: rows[i].quality });
      return out;
    })
    .catch(function () {
      return [{ url: master, quality: "1080p" }];
    });
}

function getStreams(tmdbId, mediaType) {
  var filmUrl = null;

  return titleOf(tmdbId, mediaType)
    .then(function (meta) {
      if (!meta.name) return null;
      var term = meta.name.replace(/ /g, "+");
      return text(BASE + "/arama/" + term).then(function (search) {
        var hit = pick(cards(search), meta.name, meta.year);
        return hit ? hit.url : null;
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
        var label = decode(source.tt).trim() || key;
        for (var i = 0; i < source.sx.t.length; i++) {
          var embed = decodeSource(source.sx.t[i]);
          if (!embed || !/^https?:/i.test(embed)) continue;
          if (embed.indexOf("rapidvid.") !== -1) {
            jobs.push(resolveAtom(embed, label).catch(function () { return null; }));
          } else if (embed.indexOf(".m3u8") !== -1) {
            jobs.push(plain(embed, filmUrl, label).then(function (rows) {
              var out = [];
              for (var j = 0; j < rows.length; j++) out.push({ url: rows[j].url, quality: rows[j].quality, label: label, subtitles: [] });
              return out;
            }).catch(function () { return null; }));
          }
        }
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
