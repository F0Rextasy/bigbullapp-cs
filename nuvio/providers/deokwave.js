// @provider {"id":"deokwave","name":"Deokwave","types":["tv","movie"],"domain":"deokwave.com","formats":["mp4"],"sample":"tt0409591:tv:1:1","description":"Deokwave anime arşivi; Sw2 CDN MP4 akışları imzalı video token ile açılır."}
/**
 * deokwave — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> search_api.php -> /watch/... -> appConfig (videoId, session, csrf)
 *       -> POST /api/v1/video/token/ -> https://sw2.deokwave.com/v/<videoId>/<q>/?vt=<token>
 * The CDN wants the watch-session cookie *and* a Referer, so both ride along in `headers`.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://deokwave.com";
var CDN = "https://sw2.deokwave.com";

function call(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
  if (o.headers) for (var k in o.headers) headers[k] = o.headers[k];
  return fetch(url, { method: o.method || "GET", headers: headers, body: o.body }).then(function (r) {
    /* Sandboxes without Headers.getSetCookie fold every Set-Cookie into one header. */
    var set = r.headers.getSetCookie ? r.headers.getSetCookie() : [];
    var raw = set && set.length ? set.join(",") : String(r.headers.get("set-cookie") || "");
    var cookie = raw
      .split(/,(?=\s*[^;=,\s]+=)/)
      .map(function (c) {
        return String(c || "").split(";")[0].trim();
      })
      .filter(Boolean)
      .join("; ");
    return r.text().then(function (body) {
      return { ok: r.ok, status: r.status, cookie: cookie, body: body };
    });
  });
}

function first(pattern, body) {
  var m = pattern.exec(String(body || ""));
  return m ? m[1] : null;
}

function fold(value) {
  return String(value || "")
    .toLowerCase()
    .replace(/ı/g, "i").replace(/ş/g, "s").replace(/ğ/g, "g")
    .replace(/ü/g, "u").replace(/ö/g, "o").replace(/ç/g, "c")
    .replace(/[^a-z0-9]+/g, " ");
}

function titleOf(id, mediaType) {
  var kind = mediaType === "tv" ? "series" : "movie";
  return call("https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json")
    .then(function (r) {
      var meta = JSON.parse(r.body).meta || {};
      return String(meta.name || "").trim();
    })
    .catch(function () {
      return "";
    });
}

function pick(animes, title, mediaType) {
  var wanted = fold(title).split(" ").filter(function (w) {
    return w.length > 2;
  });
  var best = null;
  var bestScore = 0;
  for (var i = 0; i < animes.length; i++) {
    var item = animes[i] || {};
    var hay = fold(item.name_english);
    var score = 0;
    for (var k = 0; k < wanted.length; k++) if (hay.indexOf(wanted[k]) !== -1) score++;
    if (mediaType === "movie" ? item.type === "movie" : item.type !== "movie") score += 0.5;
    if (score > bestScore) {
      bestScore = score;
      best = item;
    }
  }
  return best;
}

function config(html) {
  var h = String(html || "").replace(/&quot;/g, '"').replace(/&amp;/g, "&");
  var qualities = [];
  var block = first(/"qualities"\s*:\s*\[([^\]]*)\]/, h) || "";
  var re = /"(\d{3,4}p)"/g;
  var m;
  while ((m = re.exec(block)) !== null) qualities.push(m[1]);
  return {
    videoId: first(/"videoId"\s*:\s*"([^"]+)"/, h),
    session: first(/"playbackSessionId"\s*:\s*"([^"]+)"/, h),
    csrf: first(/"csrfToken"\s*:\s*"([^"]+)"/, h),
    qualities: qualities
  };
}

function mint(cfg, quality, cookie, referer) {
  var body = JSON.stringify({ videoid: cfg.videoId, quality: quality, sid: cfg.session });
  return call(BASE + "/api/v1/video/token/", {
    method: "POST",
    body: body,
    referer: referer,
    headers: {
      "Content-Type": "application/json",
      "X-CSRF-Token": cfg.csrf || "",
      Origin: BASE,
      Cookie: cookie
    }
  }).then(function (r) {
    var token = "";
    try {
      token = JSON.parse(r.body).token || "";
    } catch (e) {
      token = "";
    }
    if (!token) throw new Error("token rejected for " + quality);
    return CDN + "/v/" + encodeURIComponent(cfg.videoId) + "/" + quality + "/?vt=" + encodeURIComponent(token);
  });
}

/* Qualities that the CDN refuses (dead renditions exist) are dropped, not shown. */
function usable(url, cookie) {
  return fetch(url, {
    headers: { "User-Agent": UA, Referer: BASE + "/", Cookie: cookie, Range: "bytes=0-1023" }
  }).then(function (r) {
    var type = String(r.headers.get("content-type") || "");
    return r.status >= 200 && r.status < 300 && /video\/|mpegurl/i.test(type);
  });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var cookie = "";

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return null;
      return call(BASE + "/search_api.php?q=" + encodeURIComponent(title)).then(function (r) {
        var list = [];
        try {
          list = JSON.parse(r.body).animes || [];
        } catch (e) {
          list = [];
        }
        return pick(list, title, mediaType);
      });
    })
    .then(function (hit) {
      if (!hit || !hit.animeid) return null;
      var watch =
        mediaType === "movie"
          ? BASE + "/watch/" + hit.animeid + "/"
          : BASE + "/watch/" + hit.animeid + "/season/" + want + "/episode/" + wantEp;
      return call(watch, { referer: BASE + "/" }).then(function (r) {
        if (r.ok && first(/"videoId"\s*:\s*"([^"]+)"/, r.body.replace(/&quot;/g, '"'))) {
          cookie = r.cookie;
          return { page: r.body, referer: watch };
        }
        return call(BASE + "/watch/" + hit.animeid + "/season/1/episode/1", { referer: BASE + "/" }).then(
          function (alt) {
            cookie = alt.cookie;
            return alt.ok ? { page: alt.body, referer: BASE + "/watch/" + hit.animeid + "/season/1/episode/1" } : null;
          }
        );
      });
    })
    .then(function (found) {
      if (!found) return [];
      var cfg = config(found.page);
      if (!cfg.videoId || !cfg.session) return [];
      var list = cfg.qualities.length ? cfg.qualities : ["720p", "480p"];
      var rows = [];
      function step(i) {
        if (i >= list.length) return Promise.resolve(rows);
        var label = list[i];
        var q = String(label).replace(/p$/i, "");
        return mint(cfg, q, cookie, found.referer)
          .then(function (url) {
            return usable(url, cookie).then(function (ok) {
              if (ok) {
                rows.push({
                  name: "deokwave",
                  title: "deokwave - " + label + " [MP4]",
                  url: url,
                  quality: label,
                  headers: { Referer: BASE + "/", Cookie: cookie },
                  subtitles: []
                });
              }
              return step(i + 1);
            });
          })
          .catch(function () {
            return step(i + 1);
          });
      }
      return step(0);
    })
    .catch(function (e) {
      console.log("[bb-nuvio][deokwave] " + (e && e.message));
      return [];
    });
}

module.exports = { getStreams };
