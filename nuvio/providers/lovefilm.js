// @provider {"id":"lovefilm","name":"LoveFilm","types":["movie"],"domain":"lovefilmizle.net","formats":["m3u8","mp4"],"sample":"tt2872462:movie:1:1","description":"LoveFilmIzle.net Turkce dublaj/altyazili film arsivi; bemoly sarmalayici + vidmoly HLS."}
/**
 * lovefilm — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /?s= -> film sayfasi -> bemoly sarmalayici -> embed
 * -> jwplayer file: m3u8 + VTT altyazilar.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
var BASE = "https://lovefilmizle.net";

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
  if (!m) return null;
  return m[1] === undefined ? m[0] : m[1];
}

function decode(s) {
  return String(s || "")
    .replace(/&#x([0-9a-f]+);/gi, function (_, h) { return String.fromCharCode(parseInt(h, 16)); })
    .replace(/&#(\d+);/g, function (_, d) { return String.fromCharCode(parseInt(d, 10)); })
    .replace(/&quot;/g, '"').replace(/&#39;|&apos;/g, "'").replace(/&amp;/g, "&");
}

function norm(s) {
  return decode(s)
    .toLowerCase()
    .replace(/ı/g, "i").replace(/ş/g, "s").replace(/ğ/g, "g").replace(/ü/g, "u")
    .replace(/ö/g, "o").replace(/ç/g, "c").replace(/â/g, "a")
    .replace(/\([^)]*\)/g, " ")
    .replace(/\b(?:izle|full|hd|film)\b/g, " ")
    .replace(/[^a-z0-9]+/g, " ")
    .trim()
    .replace(/^(?:the|a|an)\s+/, "");
}

function pick(items, title) {
  var want = norm(title);
  var best = null;
  var score = 0;
  for (var i = 0; i < items.length; i++) {
    var have = norm(items[i].title);
    var s = have === want ? 3 : have.indexOf(want) === 0 ? 2 : have.indexOf(want) !== -1 ? 1 : 0;
    if (s > score) { score = s; best = items[i]; }
  }
  return best || items[0] || null;
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

function search(title) {
  var q = String(title).replace(/^(?:the|a|an)\s+/i, "");
  return text(BASE + "/?s=" + encodeURIComponent(q)).then(function (html) {
    var items = [];
    var re = /<div class="[^"]*moviebox-item[^"]*"[\s\S]{0,1200}?<a href="([^"]+)"[^>]*title="([^"]*)"/g;
    var m;
    while ((m = re.exec(html)) !== null) {
      if (m[1].indexOf(BASE) !== 0) continue;
      items.push({ url: m[1], title: decode(m[2]).trim() });
    }
    return items;
  });
}

function frameOf(html) {
  var m = /<iframe\b[^>]*>/g;
  var found;
  while ((found = m.exec(html)) !== null) {
    var tag = found[0];
    var src = first(/data-litespeed-src="([^"]+)"/, tag) || first(/data-src="([^"]+)"/, tag) || first(/(?:^|\s)src="([^"]+)"/, tag);
    if (!src || src === "about:blank" || src.indexOf("data:") === 0) continue;
    return src.indexOf("//") === 0 ? "https:" + src : src;
  }
  return null;
}

/** Film sayfasindaki yerel sarmalayiciyi (bemoly.php) bir kat acar. */
function embedOf(page, pageUrl) {
  var frame = frameOf(page);
  if (!frame) return Promise.resolve(null);
  if (frame.charAt(0) === "/") frame = BASE + frame;
  if (frame.indexOf(BASE) !== 0) return Promise.resolve(frame);
  return text(frame, { referer: pageUrl })
    .then(function (wrapper) {
      return frameOf(wrapper);
    })
    .catch(function () {
      return null;
    });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var pageUrl = null;

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return [];
      return search(title).then(function (items) {
        var hit = pick(items, title);
        return hit ? hit.url : null;
      });
    })
    .then(function (url) {
      if (!url) return [];
      pageUrl = url;
      return text(url);
    })
    .then(function (page) {
      if (!page) return [];
      return embedOf(page, pageUrl);
    })
    .then(function (embed) {
      if (!embed) return [];
      return text(embed, { referer: pageUrl }).then(function (html) {
        var rows = [];
        var seen = {};
        var subs = [];
        var re = /file\s*:\s*['"]([^'"]+)['"]/g;
        var m;
        while ((m = re.exec(html)) !== null) {
          var url = m[1].replace(/\\\//g, "/");
          if (/\.(?:m3u8|mp4)(?:\?|$)/.test(url)) {
            if (seen[url]) continue;
            seen[url] = 1;
            rows.push({
              name: "lovefilm",
              title: "LoveFilm - " + (embed.indexOf("vidmoly") !== -1 ? "Vidmoly" : "Embed"),
              url: url,
              quality: "auto",
              headers: { Referer: pageUrl, "User-Agent": UA },
              subtitles: []
            });
          } else if (/\.vtt(?:\?|$)/.test(url)) {
            var tag = first(/_([A-Za-z]{2,})\.vtt/, url) || "";
            var lang = /turk/i.test(tag) ? "tr" : /engl/i.test(tag) ? "en" : tag.toLowerCase().slice(0, 2) || "tr";
            subs.push({ url: url, language: lang, name: (tag || lang.toUpperCase()) + " [lovefilm]" });
          }
        }
        if (!rows.length) {
          var loose = first(/https?:[^"'\s<>]+?\.m3u8[^"'\s<>]*/, html);
          if (loose) rows.push({
            name: "lovefilm",
            title: "LoveFilm - Embed",
            url: loose.replace(/\\\//g, "/"),
            quality: "auto",
            headers: { Referer: pageUrl, "User-Agent": UA },
            subtitles: []
          });
        }
        if (rows.length) rows[0].subtitles = subs.slice(0, 4);
        return rows;
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][lovefilm] " + (e && e.message));
      return [];
    });
}

module.exports = { getStreams };
