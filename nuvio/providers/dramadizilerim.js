// @provider {"id":"dramadizilerim","name":"DramaDizilerim","types":["tv"],"domain":"dramadizilerim.com","formats":["mp4"],"sample":"tt32591678:tv:1:1","description":"DramaDizilerim dikey kisa drama arsivi (Turkce dublaj, MP4)."}
/**
 * dramadizilerim — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /diziler?q= cards -> /dizi/<slug> -> /izle/<slug>?s=&e=
 *       -> data-episode-id -> /api/playback -> /api/media?ticket= (MP4).
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
var BASE = "https://dramadizilerim.com";

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
  return fetch(url, { method: o.method || "GET", headers: headers })
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
  href = String(href).replace(/&amp;/g, "&").trim();
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

function norm(value) {
  return String(value || "").toLowerCase()
    .replace(/&#x27;/g, "'").replace(/&#0*39;/g, "'").replace(/&amp;/g, "&")
    .replace(/[^a-z0-9]+/g, " ").trim();
}

/** `<a class="card-link" aria-label="Title — Diziler" href="/dizi/<slug>">`. */
function cards(html) {
  var out = [];
  var re = /<a[^>]*class="[^"]*card-link[^"]*"[^>]*>/g;
  var tag;
  while ((tag = re.exec(html)) !== null) {
    var label = first(/aria-label="([^"]*)"/, tag[0]) || "";
    var href = first(/href="([^"]*)"/, tag[0]);
    if (!href || href.indexOf("/dizi/") === -1) continue;
    out.push({ url: abs(BASE, href), title: label.split("\u2014")[0].trim() });
  }
  return out;
}

function pick(list, title) {
  var want = norm(title);
  var best = null;
  var bestScore = 0;
  for (var i = 0; i < list.length; i++) {
    var have = norm(list[i].title);
    var score = have === want ? 100 : have.indexOf(want) === 0 || want.indexOf(have) === 0 ? 80 : have.indexOf(want) !== -1 ? 60 : 0;
    if (score > bestScore) {
      bestScore = score;
      best = list[i];
    }
    if (bestScore === 100) break;
  }
  return bestScore >= 60 ? best : null;
}

/** `/izle/<slug>?s=<season>&e=<episode>`; the bare `/izle/<slug>` is S1E1. */
function episodes(html) {
  var out = [];
  var seen = {};
  var re = /href="([^"]*\/izle\/[^"]+)"/g;
  var m;
  while ((m = re.exec(html)) !== null) {
    var url = abs(BASE, m[1]);
    if (!url || seen[url]) continue;
    seen[url] = 1;
    out.push({
      url: url,
      season: parseInt(first(/[?&]s=(\d+)/, url), 10) || 1,
      episode: parseInt(first(/[?&]e=(\d+)/, url), 10) || 1
    });
  }
  return out;
}

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var episodeUrl = null;

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return null;
      return text(BASE + "/diziler?q=" + encodeURIComponent(title) + "&page=1").then(function (html) {
        return pick(cards(html), title);
      });
    })
    .then(function (series) {
      if (!series) return null;
      return text(series.url).then(function (detail) {
        var list = episodes(detail);
        for (var i = 0; i < list.length; i++) {
          if (list[i].season === want && list[i].episode === wantEp) {
            episodeUrl = list[i].url;
            break;
          }
        }
        if (!episodeUrl) episodeUrl = list.length ? list[0].url : null;
        return episodeUrl;
      });
    })
    .then(function (url) {
      if (!url) return [];
      return text(url).then(function (page) {
        var id = first(/data-episode-id="([^"]+)"/, page);
        if (!id) return [];
        return text(BASE + "/api/playback?episodeId=" + encodeURIComponent(id), { referer: url }).then(function (body) {
          var json = JSON.parse(body);
          var stream = abs(BASE, json.url || json.compatibilityUrl || "");
          if (!stream) return [];
          var hls = String(json.kind || "").toLowerCase().indexOf("hls") !== -1 || stream.indexOf(".m3u8") !== -1;
          return [
            {
              name: "dramadizilerim",
              title: "dramadizilerim - " + (hls ? "HLS" : "MP4"),
              url: stream,
              quality: "auto",
              headers: { Referer: url },
              subtitles: []
            }
          ];
        });
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][dramadizilerim] " + (e && e.message));
      return [];
    });
}

module.exports = { getStreams };
