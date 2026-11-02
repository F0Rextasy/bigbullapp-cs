// @provider {"id":"kultfilmler","name":"Kultfilmler","types":["movie"],"domain":"kultfilmler.net","formats":["m3u8"],"description":"Kultfilmler film kaynaklari (vidpapi getVideo -> HLS).","sample":"tt0133093:movie:1:1"}
/**
 * kultfilmler — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> site search -> film page -> vidpapi iframe -> getVideo -> securedLink HLS.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://kultfilmler.net";
var API = "https://vidpapi.xyz/player/index.php";

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

function streams(film, videoId) {
  var body = "hash=" + encodeURIComponent(videoId) + "&r=" + encodeURIComponent(film);
  return text(API + "?data=" + encodeURIComponent(videoId) + "&do=getVideo", {
    method: "POST",
    referer: film,
    body: body,
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
      "X-Requested-With": "XML" + "HttpRequest"
    }
  }).then(function (payload) {
    var link = first(/"securedLink"\s*:\s*"([^"]+)"/, payload);
    if (!link) return [];
    var url = link.replace(/\\\//g, "/");
    return text(url, { referer: film }).then(function (playlist) {
      if (playlist.indexOf("#EXTM3U") !== 0) return [];
      var height = 0;
      var re = /RESOLUTION=\d+x(\d+)/g;
      var m;
      while ((m = re.exec(playlist)) !== null) height = Math.max(height, parseInt(m[1], 10));
      var quality = height ? height + "p" : "auto";
      return [
        {
          name: "kultfilmler",
          title: "kultfilmler - " + quality + " [HLS]",
          url: url,
          quality: quality,
          headers: { Referer: film },
          subtitles: []
        }
      ];
    });
  });
}

function tryFilm(film) {
  return text(film)
    .then(function (detail) {
      var embed = first(/<iframe[^>]+src="(https:\/\/vidpapi\.xyz\/video\/[^"]+)"/, detail);
      if (!embed) return [];
      var videoId = embed.replace(/\/+$/, "").split("/").pop();
      if (!videoId) return [];
      return streams(film, videoId);
    })
    .catch(function (e) {
      console.log("[bb-nuvio][kultfilmler] " + (e && e.message));
      return [];
    });
}

function eachFilm(films, i) {
  if (i >= films.length) return Promise.resolve([]);
  return tryFilm(films[i]).then(function (rows) {
    return rows.length ? rows : eachFilm(films, i + 1);
  });
}

function getStreams(tmdbId, mediaType) {
  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return [];
      return text(BASE + "/?s=" + encodeURIComponent(title)).then(function (search) {
        var films = [];
        // site slugs are "-izle/" or "-izle-4/": match both, in result order
        var re = /href="([^"]*-izle(?:-\d+)?\/)"/g;
        var m;
        while ((m = re.exec(search)) !== null) {
          var href = abs(BASE, m[1]);
          if (href && films.indexOf(href) === -1) films.push(href);
        }
        return eachFilm(films.slice(0, 5), 0);
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][kultfilmler] " + (e && e.message));
      return [];
    });
}

module.exports = { getStreams };
