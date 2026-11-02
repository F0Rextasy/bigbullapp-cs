// @provider {"id":"animecix","name":"AnimeciX","types":["tv"],"domain":"animecix.tv","formats":["mp4"],"sample":"tt12343534:tv:1:1","description":"AnimeciX Turkce altyazili anime arsivi (MP4 akislari)."}
/**
 * animecix — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /secure/search/<q> -> /secure/related-videos -> secure/best-video
 * (302 to tau-video.xyz/embed/<key>) -> tau /api/video/<key> -> mp4 renditions.
 * shortcut: the site's X-E-H AES token is only needed by /secure/titles, which this flow skips;
 * season+episode resolution uses related-videos, upgrade if that route starts requiring the token.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
var BASE = "https://animecix.tv";
var TAU = "https://tau-video.xyz";

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

function fetchJson(url) {
  return text(url, { referer: BASE + "/" }).then(function (body) {
    return JSON.parse(body);
  });
}

function fold(value) {
  return String(value || "")
    .replace(/ı/g, "i").replace(/İ/g, "i")
    .replace(/ş/g, "s").replace(/Ş/g, "s")
    .replace(/ğ/g, "g").replace(/Ğ/g, "g")
    .replace(/ü/g, "u").replace(/Ü/g, "u")
    .replace(/ö/g, "o").replace(/Ö/g, "o")
    .replace(/ç/g, "c").replace(/Ç/g, "c")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
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

function pick(items, title) {
  var want = fold(title);
  var best = null;
  var bestScore = -1;
  for (var i = 0; i < (items || []).length; i++) {
    var item = items[i] || {};
    if (!item.id) continue;
    var have = fold(item.name);
    var score = have === want ? 100 : 0;
    if (!score && have.indexOf(want) === 0) score = 60;
    if (!score) {
      var a = want.split(" ");
      var b = have.split(" ");
      for (var j = 0; j < a.length; j++) if (b.indexOf(a[j]) !== -1) score += 10;
    }
    if (score > bestScore) {
      bestScore = score;
      best = item;
    }
  }
  return bestScore > 0 ? best : null;
}

/** best-video answers 302 to tau-video.xyz/embed/<key>; the key is only in the redirect. */
function embedKey(url) {
  function fromUrl(target) {
    var m = /\/embed\/([A-Za-z0-9]+)/.exec(String(target || ""));
    return m ? m[1] : null;
  }
  return fetch(url, { headers: { "User-Agent": UA, "Referer": BASE + "/" }, redirect: "manual" })
    .then(function (r) {
      return fromUrl(r && r.headers && r.headers.get ? r.headers.get("location") : null);
    })
    .catch(function () {
      return null;
    })
    .then(function (key) {
      if (key) return key;
      // Some sandbox fetch shims ignore `redirect: manual`; retry following it and read r.url.
      return fetch(url, { headers: { "User-Agent": UA, "Referer": BASE + "/" } })
        .then(function (r) {
          return fromUrl(r.url);
        })
        .catch(function () {
          return null;
        });
    });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var title = "";

  return titleOf(tmdbId, mediaType)
    .then(function (name) {
      title = name;
      if (!title) return null;
      return fetchJson(BASE + "/secure/search/" + encodeURIComponent(title) + "?limit=20");
    })
    .then(function (body) {
      if (!body) return null;
      var hit = pick(body.results, title);
      if (!hit) return null;
      return fetchJson(
        BASE + "/secure/related-videos?episode=1&season=" + want + "&videoId=0&titleId=" + encodeURIComponent(hit.id)
      );
    })
    .then(function (body) {
      if (!body) return null;
      var videos = body.videos || [];
      var inSeason = [];
      for (var i = 0; i < videos.length; i++) {
        if (Number((videos[i] || {}).season_num) !== want) continue;
        inSeason.push(videos[i]);
      }
      if (!inSeason.length) return null;
      var chosen = inSeason[0];
      for (var j = 0; j < inSeason.length; j++) {
        if (Number(inSeason[j].episode_num) === wantEp) {
          chosen = inSeason[j];
          break;
        }
      }
      var path = String(chosen.url || "");
      if (!path) return null;
      return embedKey(/^https?:/.test(path) ? path : BASE + "/" + path);
    })
    .then(function (key) {
      if (!key) return [];
      return fetchJson(TAU + "/api/video/" + encodeURIComponent(key));
    })
    .then(function (video) {
      if (!video) return [];
      var out = [];
      var urls = video.urls || [];
      for (var i = 0; i < urls.length; i++) {
        var url = String((urls[i] || {}).url || "");
        if (!/^https?:/.test(url)) continue;
        var label = String(urls[i].label || "Kaynak");
        out.push({
          name: "AnimeciX",
          title: "AnimeciX - " + label + (url.indexOf(".m3u8") !== -1 ? " [HLS]" : " [MP4]"),
          url: url,
          quality: label,
          headers: { "User-Agent": UA, "Referer": TAU + "/" },
          subtitles: []
        });
      }
      return out;
    })
    .catch(function (e) {
      console.log("[bb-nuvio][animecix] " + (e && e.message));
      return [];
    });
}

module.exports = { getStreams };
