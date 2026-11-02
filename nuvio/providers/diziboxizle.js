// @provider {"id":"diziboxizle","name":"DiziboxIzle","types":["tv"],"domain":"diziboxizle.com","formats":["mp4"],"description":"DiziboxIzle dizi kaynaklari (wp-json arama + ok.ru MP4).","sample":"tt9204128:tv:1:1"}
/**
 * diziboxizle — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> wp-json search -> derived series slug -> episode -> iframe -> embed "videos" -> direct mp4s.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://diziboxizle.com";

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
  var origin = /^(https?:\/\/[^/]+)/.exec(base);
  if (href.charAt(0) === "/") return origin ? origin[1] + href : href;
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

// The embed carries its player config as JSON inside HTML, with \u00XX escapes in the URLs.
function unesc(s) {
  return String(s || "")
    .replace(/\\u([0-9a-fA-F]{4})/g, function (_, hex) {
      return String.fromCharCode(parseInt(hex, 16));
    })
    .replace(/\\\//g, "/");
}

function rowsFromEmbed(body) {
  var clean = String(body || "")
    .replace(/&quot;/g, '"')
    .replace(/&#0?39;/g, "'")
    .replace(/&amp;/g, "&");
  var slice = first(/"videos"\s*:\s*(\[[\s\S]*?])/, clean);
  if (!slice) return [];
  var rows = [];
  var seen = {};
  var re = /"name"\s*:\s*"([^"]*)"\s*,\s*"url"\s*:\s*"([^"]+)"/g;
  var m;
  while ((m = re.exec(slice)) !== null) push(rows, seen, m[1], m[2]);
  if (!rows.length) {
    var only = /"url"\s*:\s*"([^"]+)"/g;
    while ((m = only.exec(slice)) !== null) push(rows, seen, "", m[1]);
  }
  return rows;
}

function push(rows, seen, label, url) {
  url = unesc(url);
  if (!/^https?:/i.test(url) || seen[url]) return;
  seen[url] = 1;
  var tag = label || "auto";
  rows.push({
    name: "diziboxizle",
    title: "diziboxizle - " + tag + " [MP4]",
    url: url,
    quality: tag,
    headers: { "User-Agent": UA },
    subtitles: []
  });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var series = null;
  var episodeUrl = null;

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return null;
      return text(BASE + "/wp-json/wp/v2/search?search=" + encodeURIComponent(title) + "&per_page=5");
    })
    .then(function (search) {
      if (!search) return null;
      var link = first(/"url"\s*:\s*"(https?:\\?\/\\?\/[^"]+)"/, search);
      if (!link) return null;
      var slug = unesc(link).replace(/\/+$/, "").split("/").pop();
      var derived = slug.replace(/-\d+-sezon-\d+-bolum.*$/, "");
      if (!derived) return null;
      series = BASE + "/" + derived + "/";
      return text(series);
    })
    .then(function (detail) {
      if (!detail) return null;
      var links = [];
      var re = /href="([^"]*-sezon-[^"]*-bolum\/)"/g;
      var m;
      while ((m = re.exec(detail)) !== null) links.push(m[1]);
      if (!links.length) return null;
      var exact = "-" + want + "-sezon-" + wantEp + "-bolum/";
      for (var i = 0; i < links.length; i++) {
        if (links[i].indexOf(exact) !== -1) {
          episodeUrl = links[i];
          break;
        }
      }
      if (!episodeUrl) episodeUrl = links[0];
      return text(episodeUrl);
    })
    .then(function (page) {
      if (!page) return [];
      var iframe = first(/<iframe[^>]+src="([^"]+)"/, page);
      if (!iframe) return [];
      var embed = abs(episodeUrl, iframe);
      return text(embed, { referer: series }).then(rowsFromEmbed);
    })
    .catch(function (e) {
      console.log("[bb-nuvio][diziboxizle] " + (e && e.message));
      return [];
    });
}

module.exports = { getStreams };
