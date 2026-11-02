// @provider {"id":"diziboxtv","name":"DiziBoxTv","types":["tv","movie"],"domain":"diziboxtv.com","formats":["m3u8"],"sample":"tt0903747:tv:1:1","description":"DiziBoxTv dizi/film arsivi; oynatici kimliginden dogrudan HLS master akisi."}
/**
 * diziboxtv — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /ara?q= -> /dizi/<slug>?sezon=S&bolum=E (film: /film/<slug>)
 *       -> iframe /player/<id> -> https://one.makesomthingup.click/<id>/master.m3u8
 * Not: oynatıcı sayfası AES ile şifreli bir kabuk basar; şifreli HTML çözülmeden de
 * aynı <id> ile CDN master listesi doğrudan açılır.
 */
var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://diziboxtv.com";
// shortcut: CDN alan adi sabit; degisirse iframe kimliginden baska bir host denenmeli.
var CDN = "https://one.makesomthingup.click";
var ID = "diziboxtv";

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

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var isMovie = mediaType !== "tv";
  var pageUrl = null;
  var playerId = null;

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return null;
      return text(BASE + "/ara?q=" + encodeURIComponent(title));
    })
    .then(function (search) {
      if (!search) return null;
      var pattern = isMovie
        ? /href="([^"]*\/film\/[^"?#]+-izle\/?)"/
        : /href="([^"]*\/dizi\/[^"?#]+-izle\/?)"/;
      var link = first(pattern, search) || first(/href="([^"]*\/(?:dizi|film)\/[^"?#]+-izle\/?)"/, search);
      if (!link) return null;
      var contentUrl = abs(BASE, link).replace(/\/+$/, "");
      // Filmlerde oynatıcı film sayfasındadır; dizilerde sezon/bölüm sorguyla gelir.
      pageUrl = isMovie ? contentUrl : contentUrl + "?sezon=" + want + "&bolum=" + wantEp;
      return text(pageUrl, { referer: BASE + "/" });
    })
    .then(function (page) {
      if (!page) return null;
      playerId = first(/<iframe[^>]+src="[^"]*\/player\/([A-Za-z0-9_-]+)/, page);
      if (!playerId) return [];
      var url = CDN + "/" + playerId + "/master.m3u8";
      return text(url, { referer: pageUrl }).then(function (master) {
        if (String(master).indexOf("#EXTM3U") !== 0) return [];
        var quality = qualityOf(master, "1080p");
        return [
          {
            name: ID,
            title: ID + " - " + quality + " [HLS]",
            url: url,
            quality: quality,
            headers: { Referer: pageUrl },
            subtitles: []
          }
        ];
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][" + ID + "] " + (e && e.message));
      return [];
    });
}

module.exports = { getStreams };
