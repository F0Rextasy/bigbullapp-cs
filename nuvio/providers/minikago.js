// @provider {"id":"minikago","name":"MinikaGO","types":["tv"],"domain":"minikago.com.tr","formats":["mp4"],"sample":"tt11372936:tv:1:1","description":"MinikaGO Web TV bölümleri (doğrudan MP4 akışı)."}
/**
 * minikago — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /arama list -> bolum page -> MP4 URLs.
 */
var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://www.minikago.com.tr";
var SKIP = /(tanıtım|tanitim|yakında|yakinda|fragman)/i;

function text(url, referer) {
  var headers = {
    "User-Agent": UA,
    "Accept-Language": "tr-TR,tr;q=0.9",
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "X-Requested-With": "XMLHttpRequest"
  };
  if (referer) headers.Referer = referer;
  return fetch(url, { headers: headers }).then(function (r) {
    if (!r.ok) throw new Error("HTTP " + r.status + " " + url);
    return r.text();
  });
}

function decode(value) {
  return String(value || "")
    .replace(/&#(\d+);/g, function (_, n) { return String.fromCharCode(Number(n)); })
    .replace(/&#x([0-9a-f]+);/gi, function (_, n) { return String.fromCharCode(parseInt(n, 16)); })
    .replace(/&quot;/g, "\"").replace(/&#39;/g, "'").replace(/&amp;/g, "&");
}

function fold(value) {
  return decode(value).toLowerCase()
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

function search(term) {
  var q = encodeURIComponent(term).replace(/%20/g, "+");
  function fallback() {
    return text(BASE + "/arama?q=" + q).catch(function () { return ""; });
  }
  return text(BASE + "/arama?query=" + q)
    .catch(function () { return ""; })
    .then(function (page) {
      return page && page.indexOf("search-item") !== -1 ? page : fallback();
    });
}

/** S1E3 ya da "3. Bölüm" başlığını tercih eder; site sezon numarası vermediği için yalnız 1. sezon. */
function episodeRank(title, season, episode) {
  var n = parseInt(episode, 10);
  if (!n || String(season) !== "1") return 0;
  var t = fold(title);
  if (new RegExp("s0*1e0*" + n + "(\\b|$)").test(t)) return 2;
  return new RegExp("\\b0*" + n + "\\.?\\s*bolum\\b").test(t) ? 1 : 0;
}

function candidates(term, season, episode) {
  function pick(page) {
    var re = /<div class="search-item">\s*<a href="([^"]+)"[\s\S]{0,600}?<h2 class="item-content-title"[^>]*>([\s\S]*?)<\/h2>/g;
    var m;
    var rows = [];
    var seen = {};
    while ((m = re.exec(page)) !== null) {
      var url = abs(m[1]);
      // /video/ ve /programlar/ sayfaları MP4 taşımaz, yalnız bölüm sayfaları oynatılabilir.
      if (!url || url.indexOf("/bolum/") === -1 || seen[url]) continue;
      var title = m[2].replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim();
      if (SKIP.test(title)) continue;
      if (fold(title).indexOf(term) === -1) continue;
      seen[url] = 1;
      rows.push({ url: url, rank: episodeRank(title, season, episode) });
    }
    rows.sort(function (a, b) { return b.rank - a.rank; });
    return rows.slice(0, 5).map(function (row) { return row.url; });
  }
  return search(term).then(pick);
}

function mp4Rows(page, pageUrl) {
  var raw = String(page || "").replace(/\\\//g, "/");
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
}

/** Aday sayfaları sırayla dener; ilk MP4'süz sayfa sonucu boşa çıkarmaz. */
function tryPages(urls, index) {
  var i = index || 0;
  if (i >= urls.length) return Promise.resolve([]);
  return text(urls[i])
    .then(function (page) {
      var rows = mp4Rows(page, urls[i]);
      return rows.length ? rows : tryPages(urls, i + 1);
    })
    .catch(function () { return tryPages(urls, i + 1); });
}

function getStreams(imdbId, mediaType, season, episode) {
  return titleOf(imdbId, mediaType)
    .then(function (title) {
      var term = fold(title);
      if (!term) return [];
      return candidates(term, season, episode);
    })
    .then(function (urls) {
      return tryPages(urls || []);
    })
    .catch(function (e) {
      console.log("[bb-nuvio][minikago] " + (e && e.message));
      return [];
    });
}

module.exports = { getStreams };
