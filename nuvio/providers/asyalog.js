// @provider {"id":"asyalog","name":"AsyaLog","types":["tv"],"domain":"asyalog.co","formats":["mp4"],"description":"AsyaLog dizi kaynaklari (ok.ru imzali MP4 ladder).","sample":"tt40002799:tv:1:1"}
/**
 * asyalog — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> site search -> series -> episode -> iframe -> embed -> "videos"[] JSON.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://asyalog.co";

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

function abs(base, href) {
  if (!href) return null;
  if (/^https?:/i.test(href)) return href;
  if (href.slice(0, 2) === "//") return "https:" + href;
  var origin = /^(https?:\/\/[^/]+)/.exec(base);
  if (href.charAt(0) === "/") return origin ? origin[1] + href : href;
  return base.replace(/[^/]*$/, "") + href;
}

// The embed ships HTML-escaped JSON: entities must be decoded before slicing it.
function unescapeHtml(s) {
  return String(s || "")
    .replace(/&#x([0-9a-fA-F]+);/g, function (_, h) {
      return String.fromCharCode(parseInt(h, 16));
    })
    .replace(/&#(\d+);/g, function (_, d) {
      return String.fromCharCode(parseInt(d, 10));
    })
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&amp;/g, "&");
}

// Decodes \uXXXX / \/ left over inside regex-extracted JSON strings (URLs carry \u0026).
function jsonStr(s) {
  return String(s || "")
    .replace(/\\u([0-9a-fA-F]{4})/g, function (_, h) {
      return String.fromCharCode(parseInt(h, 16));
    })
    .replace(/\\\//g, "/");
}

// Returns the balanced "[ ... ]" that follows `"key"`, so JSON.parse gets a whole array.
function arrayAt(body, key) {
  var at = body.indexOf('"' + key + '"');
  if (at === -1) return null;
  var open = body.indexOf("[", at);
  if (open === -1) return null;
  var depth = 0;
  var quoted = false;
  for (var i = open; i < body.length; i++) {
    var c = body.charAt(i);
    if (quoted) {
      if (c === "\\") i++;
      else if (c === '"') quoted = false;
    } else if (c === '"') quoted = true;
    else if (c === "[") depth++;
    else if (c === "]" && --depth === 0) return body.slice(open, i + 1);
  }
  return null;
}

// The slice is JSON once unescaped; a broken payload simply yields no rows.
function entriesOf(slice) {
  try {
    var arr = JSON.parse(slice);
    return arr && arr.length !== undefined ? arr : [];
  } catch (e) {
    return [];
  }
}

function subtitlesOf(body, base) {
  var out = [];
  var keys = ["subtitle", "subtitles", "tracks"];
  for (var k = 0; k < keys.length; k++) {
    var slice = arrayAt(body, keys[k]);
    if (!slice) continue;
    var re = /\{[^{}]*\}/g;
    var m;
    while ((m = re.exec(slice)) !== null) {
      var url = first(/"(?:url|src|file)"\s*:\s*"([^"]+)"/, m[0]);
      if (!url || !/\.(vtt|srt)(\?|$)/i.test(jsonStr(url))) continue;
      var label = first(/"(?:label|language|srclang|lang)"\s*:\s*"([^"]*)"/, m[0]) || "tr";
      var lang = /^[a-z]{2,3}([_-]|$)/i.test(label) ? label.slice(0, 2).toLowerCase() : "tr";
      out.push({ url: abs(base, jsonStr(url)), language: lang, name: String(label).toUpperCase() + " [asyalog]" });
    }
  }
  return out;
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

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var series = null;
  var episodeUrl = null;
  var embedUrl = null;

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return null;
      return text(BASE + "/?s=" + encodeURIComponent(title));
    })
    .then(function (search) {
      if (!search) return null;
      var href = first(/<a class="resim" href="([^"]+)"/, search);
      if (!href) href = first(/<a href="([^"]+)"[^>]*class="[^"]*resim[^"]*"/, search);
      if (!href) return null;
      series = abs(BASE, href);
      return text(series);
    })
    .then(function (detail) {
      if (!detail) return null;
      var links = [];
      var re = /href="([^"]*\/bolum\/[^"]+)"/g;
      var m;
      while ((m = re.exec(detail)) !== null) links.push(m[1]);
      if (!links.length) return null;
      // Multi-season slugs are "<slug>-<S>-sezon-...-<E>-bolum"; single-season drop the sezon marker.
      var seasonRe = new RegExp("-" + want + "-sezon-");
      var flatRe = new RegExp("-" + want + "-" + wantEp + "-bolum/");
      var epRe = new RegExp("-" + wantEp + "-bolum/");
      var anySeason = /-sezon-/;
      episodeUrl = null;
      for (var i = 0; i < links.length && !episodeUrl; i++) {
        if (flatRe.test(links[i]) || (seasonRe.test(links[i]) && epRe.test(links[i]))) episodeUrl = links[i];
      }
      for (i = 0; i < links.length && !episodeUrl; i++) {
        if (want === 1 && !anySeason.test(links[i]) && epRe.test(links[i])) episodeUrl = links[i];
      }
      if (!episodeUrl) episodeUrl = links[0];
      episodeUrl = abs(series, episodeUrl);
      return text(episodeUrl);
    })
    .then(function (page) {
      if (!page) return null;
      var iframe = first(/id="plyg"[\s\S]*?<iframe[^>]+src="([^"]+)"/, page);
      if (!iframe) iframe = first(/<iframe[^>]+src="([^"]+)"/, page);
      if (!iframe) return null;
      embedUrl = abs(episodeUrl, iframe);
      return text(embedUrl, { referer: BASE });
    })
    .then(function (embed) {
      if (!embed) return [];
      var body = unescapeHtml(embed);
      var slice = arrayAt(body, "videos");
      if (!slice) return [];
      var entries = entriesOf(slice);
      var subs = subtitlesOf(body, embedUrl);
      var rows = [];
      for (var i = 0; i < entries.length; i++) {
        var entry = entries[i] || {};
        if (entry.disallowed === true) continue;
        var url = abs(embedUrl, jsonStr(entry.url || entry.src || entry.file || ""));
        if (!url || !/^https?:/i.test(url)) continue;
        var label = String(entry.label || entry.resim || entry.name || "").trim();
        rows.push({
          name: "asyalog",
          title: "asyalog - " + (label || "video") + (/\.m3u8/i.test(url) ? " [HLS]" : " [MP4]"),
          url: url,
          quality: label || "auto",
          headers: { Referer: embedUrl },
          subtitles: subs.slice(0, 4)
        });
      }
      return rows;
    })
    .catch(function (e) {
      console.log("[bb-nuvio][asyalog] " + (e && e.message));
      return [];
    });
}

module.exports = { getStreams };
