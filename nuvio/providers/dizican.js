// @provider {"id":"dizican","name":"DiziCaN","types":["tv"],"domain":"dizican.cc","formats":["m3u8","mp4"],"sample":"tt39029630:tv:1:1","description":"DiziCaN asya dizileri; vidmoly HLS ve ok.ru mp4 kaynaklari."}
/**
 * dizican — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /?s= -> /dizi/<slug>/ -> /bolum/<slug>-E-bolum[-hd]/
 *       -> vidmoly embed (HLS) ve/veya ok.ru embed (mp4).
 */
var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://dizican.cc";
var ID = "dizican";

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
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
  if (!origin) return href;
  if (href.indexOf("//") === 0) return "https:" + href;
  if (href.charAt(0) === "/") return origin[1] + href;
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

function qualityOf(master, fallback) {
  var re = /RESOLUTION=\d+x(\d+)/g;
  var best = 0;
  var m;
  while ((m = re.exec(String(master || ""))) !== null) {
    var h = parseInt(m[1], 10);
    if (h > best) best = h;
  }
  return best ? best + "p" : fallback;
}

// vidmoly embed -> HLS master
function fromVidmoly(embedUrl, referer) {
  return text(embedUrl, { referer: referer }).then(function (page) {
    var stream = first(/(https?:\/\/[^"'\s<>]+?\.m3u8[^"'\s<>]*)/, String(page).replace(/\\\//g, "/"));
    if (!stream) return null;
    return text(stream, { referer: embedUrl }).then(function (master) {
      if (String(master).indexOf("#EXTM3U") !== 0) return null;
      var quality = qualityOf(master, "1080p");
      return {
        name: ID,
        title: ID + " - " + quality + " [HLS]",
        url: stream,
        quality: quality,
        headers: { Referer: embedUrl },
        subtitles: []
      };
    });
  });
}

// ok.ru embed -> mp4 (videos[] içindeki hd/sd)
function fromOkRu(embedUrl) {
  return text(embedUrl, { referer: "https://ok.ru/" }).then(function (page) {
    var json = String(page).replace(/&quot;/g, '"').replace(/\\u0026/g, "&");
    var raw = first(/"videos"\s*:\s*(\[[\s\S]*?\}\])/, json);
    if (!raw) return null;
    var videos = JSON.parse(raw);
    var pick = null;
    for (var i = 0; i < videos.length; i++) {
      if (videos[i] && videos[i].name === "hd") pick = videos[i];
    }
    if (!pick) {
      for (var j = 0; j < videos.length; j++) {
        if (videos[j] && videos[j].name === "sd") pick = videos[j];
      }
    }
    if (!pick || !pick.url) return null;
    var quality = pick.name === "hd" ? "720p" : "480p";
    return {
      name: ID + " (ok.ru)",
      title: ID + " - " + quality + " [MP4]",
      url: pick.url,
      quality: quality,
      headers: { Referer: "https://ok.ru/" },
      subtitles: []
    };
  });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var wantEp = parseInt(episode, 10) || 1;
  // shortcut: diziCaN sezon sekmesi basmaz, bölüm numarasi adresin içinde; sezon yok sayilir.
  var seriesUrl = null;

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return null;
      return text(BASE + "/?s=" + encodeURIComponent(title)).then(function (search) {
        var link = first(/href="([^"]*\/dizi\/[^"?#]+)"/, search);
        // Site aramasi "&" gibi isaretlerde bos doner; tek kelimeyle bir kez daha dene.
        if (link || title.indexOf(" ") < 0) return link;
        return text(BASE + "/?s=" + encodeURIComponent(title.split(/\s+/)[0])).then(function (retry) {
          return first(/href="([^"]*\/dizi\/[^"?#]+)"/, retry);
        });
      });
    })
    .then(function (link) {
      if (!link) return null;
      seriesUrl = abs(BASE, link).replace(/\/+$/, "");
      return text(seriesUrl);
    })
    .then(function (page) {
      if (!page) return null;
      // Sezon sekmesi yok: bölümler tek düz liste, numara ada gömülü (/bolum/<slug>-E-bolum/).
      var re = /href="([^"]*\/bolum\/[^"?#]*-(\d+)-bolum[^"?#]*\/?)"/g;
      var m;
      while ((m = re.exec(page)) !== null) {
        if (parseInt(m[2], 10) === wantEp) return text(abs(seriesUrl, m[1]), { referer: seriesUrl });
      }
      return null;
    })
    .then(function (episodePage) {
      if (!episodePage) return [];
      var frames = [];
      var re = /<iframe[^>]+src="([^"]+)"/g;
      var m;
      while ((m = re.exec(episodePage)) !== null) frames.push(abs(seriesUrl, m[1]));
      var jobs = [];
      for (var i = 0; i < frames.length; i++) {
        var frame = frames[i];
        if (/vidmoly\./i.test(frame)) jobs.push(fromVidmoly(frame, seriesUrl));
        else if (/ok\.ru\/videoembed/i.test(frame)) jobs.push(fromOkRu(frame.replace(/^https?:/, "https:")));
      }
      if (!jobs.length) return [];
      return Promise.all(
        jobs.map(function (job) {
          return job.catch(function () {
            return null;
          });
        })
      ).then(function (rows) {
        // HLS daha dayanikli; ok.ru mp4 imzali ve IP'ye bagli, o yuzden sona kalir.
        return rows
          .filter(function (row) {
            return row && row.url;
          })
          .sort(function (a, b) {
            return b.title.indexOf("[HLS]") - a.title.indexOf("[HLS]");
          });
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][" + ID + "] " + (e && e.message));
      return [];
    });
}

module.exports = { getStreams };
