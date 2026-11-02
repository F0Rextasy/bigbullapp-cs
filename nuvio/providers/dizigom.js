// @provider {"id":"dizigom","name":"Dizigom","types":["movie"],"domain":"dizigom.news","formats":["m3u8"],"sample":"tt1375666:movie:1:1","description":"Dizigom (dizigom.news) Turkce film arsivi; imzali HLS akisi."}
/**
 * dizigom — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /ara?q= -> /film/<slug> -> data-pv -> pilavyerplay s.php -> window.__PLAYER__.stream (HLS).
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://www.dizigom.news";

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

function getStreams(tmdbId, mediaType, season, episode) {
  var filmUrl = null;
  var playerUrl = null;

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return null;
      return text(BASE + "/ara?q=" + encodeURIComponent(title));
    })
    .then(function (search) {
      if (!search) return null;
      var href = first(/<a href="(https:\/\/www\.dizigom\.news\/film\/[^"?#]+)"/, search);
      if (!href) href = first(/href="(\/film\/[^"?#]+)"/, search);
      if (!href) return null;
      filmUrl = href.indexOf("http") === 0 ? href : BASE + href;
      return text(filmUrl);
    })
    .then(function (page) {
      if (!page) return null;
      if (/data-pv=/.test(page)) return page;
      return text(filmUrl.replace(/\/+$/, "") + "/izle");
    })
    .then(function (playerPage) {
      if (!playerPage) return null;
      var slug = first(/data-pv=["']([^"']+)/, playerPage);
      if (!slug) return null;
      var base = first(/src=["'](https?:\/\/[^"']+\/assets\/js\/core\.js)["']/, playerPage);
      base = base ? base.replace("/assets/js/core.js", "") : "https://pilavyerplay.top";
      playerUrl = base + "/assets/js/s.php?s=" + encodeURIComponent(slug);
      return text(playerUrl, { referer: filmUrl });
    })
    .then(function (body) {
      if (!body) return [];
      var json = first(/window\.__PLAYER__\s*=\s*(\{[\s\S]*?\});/, body);
      if (!json) return [];
      var stream = String(JSON.parse(json).stream || "").replace(/\\\//g, "/").trim();
      if (!/^https?:/.test(stream)) return [];
      var base = playerUrl.replace(/\/assets\/js\/s\.php.*$/, "");
      return text(stream, { referer: base + "/" }).then(function (playlist) {
        if (String(playlist).replace(/^\s+/, "").indexOf("#EXTM3U") !== 0) return [];
        return [
          {
            name: "dizigom",
            title: "dizigom - HLS [pilavyerplay]",
            url: stream,
            quality: "auto",
            headers: { Referer: base + "/" },
            subtitles: []
          }
        ];
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][dizigom] " + (e && e.message));
      return [];
    });
}

module.exports = { getStreams };
