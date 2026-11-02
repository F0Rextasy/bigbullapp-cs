// @provider {"id":"asyadizi","name":"AsyaDizi","types":["tv"],"domain":"asyadizi.com","formats":["mp4","m3u8"],"sample":"tt11612120:tv:3:1","description":"AsyaDizi (asyadizi.com) Asya dizileri; bolum sayfasindaki ok.ru ve dogrudan akislar."}
/**
 * asyadizi — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /?s= -> series -> bolum (episode) -> page scan:
 *       direct .m3u8/.mp4, <iframe> embeds -> ok.ru metadata API / nested media scan.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://asyadizi.com";
var SITE = "asyadizi";
var MAX_ROWS = 6;

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
  if (o.headers) for (var k in o.headers) headers[k] = o.headers[k];
  return fetch(url, { method: o.method || "GET", headers: headers, body: o.body }).then(function (r) {
    if (!r.ok) throw new Error("HTTP " + r.status + " " + url);
    return r.text();
  });
}

function first(pattern, body) {
  var m = pattern.exec(String(body || ""));
  return m ? m[1] : null;
}

function all(pattern, body) {
  var flags = pattern.flags.indexOf("g") >= 0 ? pattern.flags : pattern.flags + "g";
  var re = new RegExp(pattern.source, flags);
  var out = [];
  var m;
  var src = String(body || "");
  while ((m = re.exec(src)) !== null) {
    out.push(m);
    if (m.index === re.lastIndex) re.lastIndex++;
  }
  return out;
}

function abs(base, href) {
  if (!href) return null;
  if (href.indexOf("//") === 0) return "https:" + href;
  if (/^https?:/i.test(href)) return href;
  var origin = first(/^(https?:\/\/[^/]+)/, base);
  if (href.charAt(0) === "/") return origin ? origin[1] + href : href;
  return base.replace(/[^/]*$/, "") + href;
}

function norm(value) {
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
      return String((JSON.parse(body).meta || {}).name || "").trim();
    })
    .catch(function () {
      return "";
    });
}

/** Rank series links by how many title words appear in their slug; the site search order breaks ties. */
function pickSeries(search, want) {
  var hrefs = [];
  var seen = {};
  var links = all(/href="(https?:\/\/[^"]+\/dizi\/[^"?#]+)"/, search);
  for (var i = 0; i < links.length; i++) {
    var href = links[i][1];
    if (seen[href]) continue;
    seen[href] = true;
    hrefs.push(href);
  }
  if (!hrefs.length) return null;
  var words = norm(want).split(" ").filter(function (w) {
    return w.length > 2 || /^\d+$/.test(w);
  });
  var best = null;
  var bestScore = 0;
  for (var j = 0; j < hrefs.length; j++) {
    var hay = norm(hrefs[j]);
    var score = 0;
    for (var k = 0; k < words.length; k++) if (hay.indexOf(words[k]) !== -1) score++;
    if (score > bestScore) {
      bestScore = score;
      best = hrefs[j];
    }
  }
  return best || hrefs[0];
}

/** Episode ranges ("01-06. Bölüm", "-1-sezon-2-bolum-", "-3-bolum-") reduced to [season, first, last]. */
function episodeRange(href) {
  var path = href.toLowerCase();
  if (path.indexOf("bolum") === -1 && path.indexOf("episode") === -1) return null;
  var m = /(\d+)[-_]sezon[-_/]*(\d+)[-_]bolum/.exec(path) || /sezon[-_/]?(\d+)[-_/]+.*?(\d+)[-_]bolum/.exec(path);
  if (m) return { season: parseInt(m[1], 10), at: parseInt(m[2], 10), to: parseInt(m[2], 10) };
  m = /(\d+)[-_](\d+)[-_]bolum/.exec(path);
  if (m) return { season: 0, at: parseInt(m[1], 10), to: parseInt(m[2], 10) };
  m = /bolum[-_/]?(\d+)/.exec(path) || /(\d+)[-_]bolum/.exec(path);
  if (m) return { season: 0, at: parseInt(m[1], 10), to: parseInt(m[1], 10) };
  return { season: 0, at: 0, to: 0 };
}

function pickEpisode(detail, season, episode) {
  var want = parseInt(episode, 10) || 1;
  var wantSeason = parseInt(season, 10) || 1;
  var links = all(/href="(https?:\/\/[^"?#]+)"/, detail);
  var seen = {};
  var list = [];
  for (var i = 0; i < links.length; i++) {
    var href = links[i][1];
    if (seen[href]) continue;
    var range = episodeRange(href);
    if (!range) continue;
    seen[href] = true;
    list.push({ url: href, range: range });
  }
  if (!list.length) return null;
  var j;
  for (j = 0; j < list.length; j++) {
    var r = list[j].range;
    if (r.season === wantSeason && want >= r.at && want <= r.to) return list[j].url;
  }
  for (j = 0; j < list.length; j++) {
    var r2 = list[j].range;
    if (r2.season === 0 && want >= r2.at && want <= r2.to) return list[j].url;
  }
  return list[0].url;
}

/** ok.ru names its tiers instead of pixels; "mobile" is the same file the "hd" entry already covers. */
var OK_QUALITY = { hd: "720p", sd: "480p", low: "360p", lowest: "240p", mobile: "" };

function qualityOf(label) {
  var key = String(label || "").trim().toLowerCase();
  if (OK_QUALITY[key] !== undefined) return OK_QUALITY[key];
  var m = /(\d{3,4})/.exec(key);
  if (!m) return "";
  var h = parseInt(m[1], 10);
  return h >= 240 && h <= 2160 ? h + "p" : "";
}

function okRu(embed, rows, seen) {
  var m = /videoembed\/(\d+)/.exec(embed);
  if (!m) return Promise.resolve();
  return fetch("https://ok.ru/dk?cmd=videoPlayerMetadata&mid=" + m[1], {
    method: "POST",
    headers: {
      "User-Agent": "Mozilla/5.0 (Linux; Android 13)",
      Referer: "https://ok.ru/",
      "Content-Type": "application/x-www-form-urlencoded"
    },
    body: ""
  })
    .then(function (r) {
      return r.text();
    })
    .then(function (raw) {
      var videos = JSON.parse(raw).videos || [];
      var first = null;
      for (var i = 0; i < videos.length && rows.length < MAX_ROWS; i++) {
        var url = String(videos[i].url || "");
        if (!/^https?:/i.test(url)) continue;
        if (!first) first = url;
        var quality = qualityOf(videos[i].name);
        if (!quality || seen[url]) continue;
        seen[url] = true;
        rows.push({
          name: SITE,
          title: SITE + " - " + quality + " [MP4]",
          url: url,
          quality: quality,
          headers: { Referer: "https://ok.ru/", "User-Agent": UA },
          subtitles: []
        });
      }
      // ok.ru sometimes labels nothing recognisable; one playable row beats none.
      if (!rows.length && first) {
        seen[first] = true;
        rows.push({
          name: SITE,
          title: SITE + " - auto [MP4]",
          url: first,
          quality: "auto",
          headers: { Referer: "https://ok.ru/", "User-Agent": UA },
          subtitles: []
        });
      }
    })
    .catch(function () {});
}

function scanMedia(body, referer, rows, seen) {
  var text = String(body || "").replace(/\\\//g, "/");
  var hits = all(/(https?:\/\/[^"'\s<>\\]+?\.(?:m3u8|mp4)[^"'\s<>\\]*)/i, text);
  for (var i = 0; i < hits.length; i++) {
    var url = hits[i][1];
    if (seen[url]) continue;
    seen[url] = true;
    var hls = /\.m3u8/i.test(url);
    rows.push({
      name: SITE,
      title: SITE + " - " + (hls ? "HLS" : "MP4"),
      url: url,
      quality: "auto",
      headers: { Referer: referer, "User-Agent": UA },
      subtitles: []
    });
  }
  return hits.length > 0;
}

function embedList(body) {
  var out = [];
  var seen = {};
  var frames = all(/<iframe[^>]*(?:data-src|src)="([^"]+)"/i, body);
  for (var i = 0; i < frames.length; i++) {
    var href = frames[i][1];
    if (!href || /^javascript/i.test(href)) continue;
    if (href.indexOf("'+") !== -1 || href.indexOf("' +") !== -1) continue;
    if (/youtube|vimeo|vzaar|dailymotion/i.test(href)) continue;
    if (seen[href]) continue;
    seen[href] = true;
    out.push(href);
  }
  return out;
}

function getStreams(tmdbId, mediaType, season, episode) {
  var rows = [];
  var seen = {};
  var pageUrl = null;
  var wantTitle = "";

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      wantTitle = title;
      if (!title) return null;
      return text(BASE + "/?s=" + encodeURIComponent(title));
    })
    .then(function (search) {
      if (!search) return null;
      var series = pickSeries(search, wantTitle);
      if (!series) return null;
      pageUrl = series;
      return text(series);
    })
    .then(function (detail) {
      if (!detail) return [];
      var epUrl = pickEpisode(detail, season, episode);
      if (!epUrl) return [[detail, pageUrl]];
      pageUrl = epUrl;
      return text(epUrl).then(function (epPage) {
        return [[epPage, epUrl]];
      });
    })
    .then(function (loaded) {
      if (!loaded || !loaded.length) return;
      var page = loaded[0][0];
      var referer = loaded[0][1];
      if (!page) return;
      scanMedia(page, referer, rows, seen);
      var frames = embedList(page);
      return frames.reduce(function (chain, frame) {
        return chain.then(function () {
          if (rows.length) return;
          var url = abs(referer, frame);
          if (/ok\.ru|odnoklassniki/.test(url)) return okRu(url, rows, seen);
          return text(url, { referer: referer })
            .then(function (inner) {
              scanMedia(inner, url, rows, seen);
              var nested = embedList(inner);
              var okFrames = nested.filter(function (f) {
                return /ok\.ru|odnoklassniki/.test(f);
              });
              return okFrames.reduce(function (c, f) {
                return c.then(function () {
                  return okRu(abs(url, f), rows, seen);
                });
              }, Promise.resolve());
            })
            .catch(function () {});
        });
      }, Promise.resolve());
    })
    .then(function () {
      return rows;
    })
    .catch(function (e) {
      console.log("[bb-nuvio][" + SITE + "] " + (e && e.message));
      return [];
    });
}


module.exports = { getStreams };
