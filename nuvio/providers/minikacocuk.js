// @provider {"id":"minikacocuk","name":"MinikaCocuk","types":["tv"],"domain":"minikacocuk.com.tr","formats":["mp4"],"sample":"tt0487849:tv:1:1","description":"MinikaÇOCUK Web TV çocuk bölümleri (doğrudan MP4 akışı).","verified":false}
/**
 * minikacocuk — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /webtv card -> bolum page -> contentUrl MP4.
 */
var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://www.minikacocuk.com.tr";

function text(url, referer) {
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (referer) headers.Referer = referer;
  return fetch(url, { headers: headers }).then(function (r) {
    if (!r.ok) throw new Error("HTTP " + r.status + " " + url);
    return r.text();
  });
}

function fold(value) {
  return String(value || "").toLowerCase()
    .replace(/ı/g, "i").replace(/ş/g, "s").replace(/ğ/g, "g")
    .replace(/ü/g, "u").replace(/ö/g, "o").replace(/ç/g, "c");
}

function abs(href) {
  if (!href) return null;
  if (/^https?:/i.test(href)) return href;
  return BASE + (href.charAt(0) === "/" ? href : "/" + href);
}

function titleOf(id, mediaType) {
  var kind = mediaType === "tv" ? "series" : "movie";
  return fetch("https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json")
    .then(function (r) { return r.json(); })
    .then(function (j) { return String((j.meta || {}).name || "").trim(); })
    .catch(function () { return ""; });
}

function episodeUrl(term) {
  return text(BASE + "/webtv").then(function (page) {
    var re = /<a href="([^"]+)" class="showcase-content"[\s\S]{0,4000}?class="showcase-(?:txt|lightbox)-title"[^>]*>([\s\S]*?)<\/h2>/g;
    var m;
    while ((m = re.exec(page)) !== null) {
      if (fold(m[2]).indexOf(term) !== -1 || fold(m[1]).indexOf(term) !== -1) return abs(m[1]);
    }
    return null;
  });
}

function getStreams(imdbId, mediaType, season, episode) {
  var pageUrl = null;
  var term = "";
  return titleOf(imdbId, mediaType)
    .then(function (title) {
      term = fold(title);
      if (!term) return null;
      return episodeUrl(term);
    })
    .then(function (url) {
      if (!url) return [];
      pageUrl = url;
      return text(url);
    })
    .then(function (page) {
      if (!page) return [];
      var raw = String(page).replace(/\\\//g, "/");
      var m = /contentUrl["']?\s*:\s*["']([^"']+)/.exec(raw);
      var url = m ? m[1] : null;
      if (!url || !/^https?:/.test(url) || !/(\.mp4|\.m3u8)/.test(url)) {
        m = /https?:\/\/[^"'\s<>\\]+?\.(?:mp4|m3u8)[^"'\s<>\\]*/.exec(raw);
        url = m ? m[0] : null;
      }
      if (!url) return [];
      var q = /(\d{3,4})p/.exec(url);
      return [{
        name: "minikacocuk",
        title: "MinikaCocuk - " + (q ? q[1] + "p" : "auto") + " [MP4]",
        url: url,
        quality: q ? q[1] + "p" : "auto",
        headers: { Referer: pageUrl, "User-Agent": UA },
        subtitles: []
      }];
    })
    .catch(function (e) {
      console.log("[bb-nuvio][minikacocuk] " + (e && e.message));
      return [];
    });
}

module.exports = { getStreams };
