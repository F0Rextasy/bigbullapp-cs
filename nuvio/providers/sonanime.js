// @provider {"id":"sonanime","name":"SonAnime","types":["movie","tv"],"domain":"sonanime.com","formats":["mp4"],"sample":"tt0409591:tv:1:1","description":"SonAnime T\u00fcrk\u00e7e altyaz\u0131l\u0131 anime ar\u015fivi; API'den do\u011frudan MP4 kademeleri."}
/**
 * sonanime — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> api.sonanime.com search -> anime_link -> link API -> per-quality MP4.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://sonanime.com";
var API = "https://api.sonanime.com/api/anime";

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
  if (o.headers) for (var k in o.headers) headers[k] = o.headers[k];
  return fetch(url, { method: o.method || "GET", headers: headers, redirect: "follow" })
    .then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status + " " + url);
      return r.text();
    });
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

// Turkish letters folded so "Kaguya-sama" and "kaguya sama" compare equal.
function norm(value) {
  return String(value || "").toLowerCase()
    .replace(/[\u0131\u0130]/g, "i").replace(/[\u015f\u015e]/g, "s").replace(/[\u011f\u011e]/g, "g")
    .replace(/[\u00fc\u00dc]/g, "u").replace(/[\u00f6\u00d6]/g, "o").replace(/[\u00e7\u00c7]/g, "c")
    .replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "");
}

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var wanted = "";
  var slug = null;

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      wanted = norm(title);
      if (!wanted) return null;
      return text(API + "/search?q=" + encodeURIComponent(title), { referer: BASE + "/" });
    })
    .then(function (body) {
      if (!body) return null;
      var list = JSON.parse(body);
      var best = null, bestScore = 0;
      for (var i = 0; i < list.length; i++) {
        var a = norm(list[i].anime_name), b = norm(list[i].anime_name_en);
        var score = 0;
        if (a === wanted || b === wanted) score = 3;
        else if ((a && a.indexOf(wanted) === 0) || (b && b.indexOf(wanted) === 0)) score = 2;
        else if ((a && a.indexOf(wanted) > -1) || (b && b.indexOf(wanted) > -1)) score = 1;
        if (score > bestScore && list[i].anime_link) {
          bestScore = score;
          best = list[i].anime_link;
        }
      }
      if (!best) return null;
      slug = String(best).replace(/^\/+/, "");
      return text(API + "/link/" + encodeURIComponent(slug), { referer: BASE + "/anime/" + slug });
    })
    .then(function (body) {
      if (!body) return [];
      var anime = JSON.parse(body);
      var episodes = anime.episodes || [];
      var hit = null;
      for (var i = 0; i < episodes.length; i++) {
        if (Number(episodes[i].anime_season) === want && Number(episodes[i].episode_number) === wantEp) {
          hit = episodes[i];
          break;
        }
      }
      if (!hit) return [];
      var rows = [];
      [["1080", "1080p"], ["720", "720p"], ["480", "480p"], ["360", "360p"]].forEach(function (pair) {
        var url = hit["episode_link_" + pair[0]];
        if (typeof url !== "string" || !/^https?:/i.test(url)) return;
        rows.push({
          name: "sonanime",
          title: "sonanime " + pair[1] + " [MP4]",
          url: url,
          quality: pair[1],
          headers: { Referer: BASE + "/" },
          subtitles: []
        });
      });
      return rows;
    })
    .catch(function (e) {
      console.log("[bb-nuvio][sonanime] " + (e && e.message));
      return [];
    });
}

module.exports = { getStreams };
