// @provider {"id":"minikago","name":"MinikaGO","types":["tv"],"domain":"minikago.com.tr","formats":["mp4"],"sample":"tt11372936:tv:1:1","description":"MinikaGO Web TV bölümleri (doğrudan MP4 akışı)."}
/**
 * minikago — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /arama list -> bolum page -> MP4 URLs.
 */
var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://www.minikago.com.tr";
var SKIP = /(tanıtım|tanitim|yakında|yakinda|fragman)/i;

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
  return text(BASE + "/arama?q=" + encodeURIComponent(term).replace(/%20/g, "+")).then(function (page) {
    var re = /<div class="search-item">\s*<a href="([^"]+)"[\s\S]{0,600}?<h2 class="item-content-title"[^>]*>([\s\S]*?)<\/h2>/g;
    var m;
    var exact = null;
    var loose = null;
    while ((m = re.exec(page)) !== null) {
      var title = m[2].replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim();
      if (SKIP.test(title)) continue;
      if (fold(title).indexOf(term) === -1) continue;
      var url = abs(m[1]);
      if (!url) continue;
      if (fold(title) === term) { exact = url; break; }
      if (!loose) loose = url;
    }
    return exact || loose;
  });
}

function getStreams(imdbId, mediaType, season, episode) {
  var pageUrl = null;
  return titleOf(imdbId, mediaType)
    .then(function (title) {
      var term = fold(title);
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
      var re = /https?:\/\/[^"'\s<>\\]+?\.mp4[^"'\s<>\\]*/g;
      var m;
      var seen = {};
      var rows = [];
      while ((m = re.exec(raw)) !== null) {
        var url = m[0];
        if (seen[url]) continue;
        seen[url] = 1;
        var q = /[_-](\d{3,4})p[_-]/.exec(url);
        rows.push({
          name: "minikago",
          title: "MinikaGO - " + (q ? q[1] + "p" : "auto") + " [MP4]",
          url: url,
          quality: q ? q[1] + "p" : "auto",
          headers: { Referer: pageUrl, "User-Agent": UA },
          subtitles: []
        });
      }
      return rows;
    })
    .catch(function (e) {
      console.log("[bb-nuvio][minikago] " + (e && e.message));
      return [];
    });
}

module.exports = { getStreams };
