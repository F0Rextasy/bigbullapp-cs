// @provider {"id":"ddizi","name":"Ddizi","types":["tv"],"domain":"www.ddizi.tel","formats":["mp4","m3u8"],"sample":"tt21764074:tv:1:145","description":"Ddizi yerli dizi arşivi; bölümler jwplayer HLS (tabii) olarak açılır."}
/**
 * ddizi — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> POST /arama/ -> series -> /izle/ episode -> /player/oynat/<hash> -> jwplayer sources.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
var BASE = "https://www.ddizi.tel";

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
  if (o.form) headers["Content-Type"] = "application/x-www-form-urlencoded";
  return fetch(url, { method: o.method || "GET", headers: headers, body: o.body })
    .then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status + " " + url);
      return r.text();
    });
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
  return text("https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json")
    .then(function (body) {
      var meta = JSON.parse(body).meta || {};
      return String(meta.name || "").trim();
    })
    .catch(function () {
      return "";
    });
}

function pickSeries(html, title) {
  var re = /<a href="(https:\/\/www\.ddizi\.tel\/diziler\/[^"]+)"[^>]*title="([^"]*)"/g;
  var wanted = fold(title).split(" ").filter(function (w) {
    return w.length > 2;
  });
  var best = "";
  var bestScore = -1;
  var m;
  while ((m = re.exec(html)) !== null) {
    var hay = fold(m[2]);
    var score = 0;
    for (var i = 0; i < wanted.length; i++) if (hay.indexOf(wanted[i]) !== -1) score++;
    if (score > bestScore) {
      bestScore = score;
      best = m[1];
    }
  }
  return best;
}

/* Episode links live at /izle/<id>/<slug>-<n>-bolum...; carry the same series slug. */
function episodes(html, slug) {
  var re = /<a href="(https:\/\/www\.ddizi\.tel\/izle\/[^"]+)"[^>]*title="([^"]*)"/g;
  var out = [];
  var m;
  while ((m = re.exec(html)) !== null) {
    if (slug && m[1].indexOf(slug) === -1) continue;
    var label = m[2].replace(/\s+/g, " ").trim();
    var season = /(\d+)\.\s*Sezon/.exec(label);
    var number = /(\d+)\.\s*Bölüm/.exec(label) || /-(\d+)-bolum/i.exec(m[1]);
    out.push({
      url: m[1],
      season: season ? parseInt(season[1], 10) : 1,
      episode: number ? parseInt(number[1], 10) : null
    });
  }
  return out;
}

function sources(player) {
  var block = /sources\s*:\s*\[([\s\S]*?)\]/i.exec(player);
  if (!block) return [];
  var out = [];
  var re = /file\s*:\s*"([^"]+)"(?:\s*,\s*label\s*:\s*"([^"]*)")?/g;
  var m;
  while ((m = re.exec(block[1])) !== null) {
    var url = m[1].replace(/\\\//g, "/");
    if (!/^https?:/i.test(url)) continue;
    var q = /(\d{3,4})/.exec(String(m[2] || ""));
    out.push({
      name: "ddizi",
      title: "ddizi - " + (m[2] || "Auto") + (/\.m3u8/i.test(url) ? " [HLS]" : " [MP4]"),
      url: url,
      quality: q ? q[1] + "p" : "auto",
      headers: {},
      subtitles: []
    });
  }
  return out;
}

/* Larger series split episodes into /sayfa-N pages: walk them until the pair shows up. */
function findEpisode(seriesUrl, slug, want, wantEp) {
  function pageUrls(html) {
    var out = [];
    var re = /href="([^"]*\/sayfa-\d+)"/g;
    var m;
    while ((m = re.exec(html)) !== null) {
      var url = /^https?:/i.test(m[1]) ? m[1] : BASE + m[1];
      if (out.indexOf(url) === -1) out.push(url);
    }
    return out.slice(0, 20);
  }

  function walk(list, i, fallback) {
    if (i >= list.length) return Promise.resolve(fallback);
    return text(list[i])
      .catch(function () {
        return "";
      })
      .then(function (html) {
        var eps = episodes(html, slug);
        for (var k = 0; k < eps.length; k++) {
          if (eps[k].season === want && eps[k].episode === wantEp) return eps[k].url;
          if (!fallback) fallback = eps[k].url;
        }
        return walk(list, i + 1, fallback);
      });
  }

  return text(seriesUrl).then(function (html) {
    var eps = episodes(html, slug);
    var fallback = "";
    for (var i = 0; i < eps.length; i++) {
      if (eps[i].season === want && eps[i].episode === wantEp) return eps[i].url;
      if (!fallback) fallback = eps[i].url;
    }
    var pages = pageUrls(html);
    return pages.length ? walk(pages, 0, fallback) : fallback;
  });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var wanted = "";

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return null;
      wanted = title;
      return text(BASE + "/arama/", {
        method: "POST",
        form: true,
        body: "arama=" + encodeURIComponent(title)
      });
    })
    .then(function (search) {
      var series = search && wanted ? pickSeries(search, wanted) : "";
      if (!series) return null;
      var slug = series.replace(/\/$/, "").split("/").pop().replace(/-\d+-son-bolum-izle$/, "");
      return findEpisode(series, slug, want, wantEp);
    })
    .then(function (episodeUrl) {
      if (!episodeUrl) return [];
      return text(episodeUrl, { referer: BASE + "/" }).then(function (html) {
        var frames = [];
        var re = /<iframe[^>]+(?:src|data-src)="([^"]+)"/g;
        var m;
        while ((m = re.exec(html)) !== null) {
          if (m[1].indexOf("/player/") === -1 || frames.indexOf(m[1]) !== -1) continue;
          frames.push(m[1]);
        }
        var rows = [];
        function step(i) {
          if (i >= frames.length) return Promise.resolve(rows);
          var frame = frames[i].charAt(0) === "/" ? BASE + frames[i] : frames[i];
          return text(frame, { referer: episodeUrl })
            .catch(function () {
              return "";
            })
            .then(function (player) {
              rows = rows.concat(sources(player));
              return step(i + 1);
            });
        }
        return step(0);
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][ddizi] " + (e && e.message));
      return [];
    });
}

module.exports = { getStreams };
