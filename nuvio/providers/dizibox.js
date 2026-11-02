// @provider {"id":"dizibox","name":"Dizibox","types":["tv"],"domain":"dizibox.live","formats":["m3u8"],"description":"Dizibox dizi kaynaklari (king.php -> sheila HLS).","sample":"tt0903747:tv:2:5"}
/**
 * dizibox — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> site search -> series -> episode -> king.php player -> embed -> sheila HLS.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://www.dizibox.live";

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

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var candidates = [];
  var episodeUrl = null;
  var embedUrl = null;

  // The site answers a degraded (episode-less) series page now and then and throttles
  // bursts: walk at most two search hits and never re-request the same page.
  function links(detail) {
    var out = [];
    var re = /href=["']([^"']+)["'][^>]*class=["']season-episode[^"']*["']/g;
    var m;
    while ((m = re.exec(String(detail || ""))) !== null) out.push(abs(BASE, m[1]));
    return out;
  }

  function findEp(list) {
    var exact = new RegExp("-" + want + "-sezon-" + wantEp + "-bolum");
    for (var i = 0; i < list.length; i++) if (exact.test(list[i])) return list[i];
    return null;
  }

  // A series page only lists one season; its tabs give the requested one in a single hop.
  function seasonPage(detail) {
    var m = new RegExp("href=[\"']([^\"']*/" + want + "-sezon-[^/\"']+/)[\"']").exec(String(detail || ""));
    return m ? abs(BASE, m[1]) : null;
  }

  function fromSeries(i) {
    if (i >= candidates.length) return Promise.resolve(null);
    return text(candidates[i])
      .then(function (detail) {
        var list = links(detail);
        var hit = findEp(list);
        if (hit) return hit;
        var first = list[0] || null;
        var tab = seasonPage(detail);
        if (!tab) return first || fromSeries(i + 1);
        return text(tab)
          .then(function (season) {
            return findEp(links(season)) || first || fromSeries(i + 1);
          })
          .catch(function () {
            return first || fromSeries(i + 1);
          });
      })
      .catch(function () {
        return fromSeries(i + 1);
      });
  }

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return null;
      return text(BASE + "/search/" + encodeURIComponent(title).replace(/%20/g, "+") + "/");
    })
    .then(function (search) {
      if (!search) return null;
      var re = /<article class="detailed-article"[\s\S]*?<h3[^>]*><a href="([^"]+)"/g;
      var m;
      while ((m = re.exec(search)) !== null && candidates.length < 2) candidates.push(abs(BASE, m[1]));
      return fromSeries(0);
    })
    .then(function (ep) {
      if (!ep) return null;
      episodeUrl = ep;
      return text(ep);
    })
    .then(function (page) {
      if (!page) return null;
      var src = first(/id="video-area"[\s\S]*?<iframe[^>]+src="([^"]+)"/, page);
      if (!src) return null;
      var player = abs(episodeUrl, src);
      if (player.indexOf("king.php") === -1) return null;
      return text(player.replace("king.php?v=", "king.php?wmode=opaque&v="), { referer: episodeUrl });
    })
    .then(function (king) {
      if (!king) return null;
      var src = first(/id="Player"[\s\S]*?<iframe[^>]+src="([^"]+)"/, king);
      if (!src) return null;
      embedUrl = abs(episodeUrl, src);
      var stream = embedUrl.replace("/embed/", "/embed/sheila/");
      if (stream === embedUrl) return null;
      // The sheila master only answers with a Referer; without one it serves a "banned" page.
      return text(stream, { referer: embedUrl }).then(function (playlist) {
        if (playlist.indexOf("#EXTM3U") === -1) return null;
        var height = first(/RESOLUTION=\d+x(\d+)/, playlist);
        var quality = height ? height + "p" : "auto";
        return [
          {
            name: "Dizibox",
            title: "Dizibox - " + quality + " [HLS]",
            url: stream,
            quality: quality,
            headers: { Referer: embedUrl },
            subtitles: []
          }
        ];
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][dizibox] " + (e && e.message));
      return [];
    });
}

module.exports = { getStreams };
