// @provider {"id":"dizifon","name":"Dizifon","types":["tv"],"domain":"dizifon.cc","formats":["mp4"],"sample":"tt37439410:tv:1:1","description":"Dizifon (dizifon.cc) Asya dizileri; ok.ru oynaticisi."}
/**
 * dizifon — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> ?s=<q> -> /dizi/<slug> -> /bolum/<slug>-N-bolum -> ok.ru videos JSON.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://www.dizifon.cc";

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

function abs(base, href) {
  if (!href) return null;
  if (/^https?:/i.test(href)) return href;
  var origin = /^(https?:\/\/[^/]+)/.exec(base);
  if (href.indexOf("//") === 0) return "https:" + href;
  if (href.charAt(0) === "/") return origin ? origin[1] + href : href;
  return base.replace(/[^/]*$/, "") + href;
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

/** Search cards: `<a class="baslik uzn" href="/dizi/<slug>/" title="Final Table 2026 Kore">`. */
function pickSeries(html, title) {
  var re = /href="(https:\/\/www\.dizifon\.cc\/dizi\/[^"?#]+)"[^>]*title="([^"]*)"/g;
  var m;
  var fallback = null;
  var want = String(title).toLowerCase();
  while ((m = re.exec(html)) !== null) {
    if (!fallback) fallback = m[1];
    if (String(m[2] || "").toLowerCase().indexOf(want) === 0) return m[1];
  }
  return fallback;
}

var Q = { mobile: "240p", lowest: "240p", low: "360p", sd: "480p", hd: "720p", full: "1080p" };

function okRuStreams(embed, referer) {
  return text(embed, { referer: referer })
    .then(function (body) {
      var arr = first(/"videos"\s*:\s*(\[[\s\S]*?])/, String(body).replace(/&quot;/g, "\""));
      if (!arr) return [];
      var videos = JSON.parse(arr);
      var out = [];
      for (var i = 0; i < videos.length; i++) {
        var v = videos[i] || {};
        if (!/^https?:/.test(v.url || "")) continue;
        var q = Q[v.name];
        if (!q) continue;
        out.push({
          name: "dizifon",
          title: "dizifon - " + q + " [ok.ru]",
          url: v.url,
          quality: q,
          headers: { Referer: "https://ok.ru/" },
          subtitles: []
        });
      }
      return out;
    })
    .catch(function () {
      return [];
    });
}

function directStreams(page, pageUrl) {
  var re = /https?:\/\/[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?/g;
  var m;
  var out = [];
  var seen = {};
  while ((m = re.exec(page)) !== null) {
    if (seen[m[0]]) continue;
    seen[m[0]] = 1;
    var hls = /\.m3u8/.test(m[0]);
    out.push({
      name: "dizifon",
      title: "dizifon - " + (hls ? "HLS" : "video"),
      url: m[0],
      quality: "auto",
      headers: { Referer: pageUrl },
      subtitles: []
    });
  }
  return out;
}

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var title = "";
  var series = null;
  var episodeUrl = null;

  return titleOf(tmdbId, mediaType)
    .then(function (name) {
      if (!name) return null;
      title = name;
      return text(BASE + "/?s=" + encodeURIComponent(name));
    })
    .then(function (search) {
      if (!search) return null;
      series = pickSeries(search, title);
      if (!series) return null;
      return text(series);
    })
    .then(function (detail) {
      if (!detail) return null;
      var re = /href="(https:\/\/www\.dizifon\.cc\/bolum\/[^"?#]+)"/g;
      var m;
      var list = [];
      while ((m = re.exec(detail)) !== null) {
        var path = m[1];
        var se = /-(\d+)-sezon-(\d+)-bolum/.exec(path);
        var ep = /(?:^|\/)([^/]*?)-(\d+)-bolum\/?$/.exec(path);
        if (!se && !ep) continue;
        list.push({
          url: path,
          season: se ? parseInt(se[1], 10) : 1,
          ep: se ? parseInt(se[2], 10) : parseInt(ep[2], 10)
        });
      }
      if (!list.length) return null;
      episodeUrl = list[0].url;
      for (var i = 0; i < list.length; i++) {
        if (list[i].season === want && list[i].ep === wantEp) {
          episodeUrl = list[i].url;
          break;
        }
      }
      return text(episodeUrl, { referer: series });
    })
    .then(function (page) {
      if (!page) return [];
      var embed = first(/<iframe[^>]+(?:vdo-src|data-src|src)="([^"]*(?:ok\.ru|odnoklassniki\.ru)[^"]*)"/, page);
      if (embed) {
        return okRuStreams(abs(episodeUrl, embed), episodeUrl).then(function (rows) {
          return rows.length ? rows : directStreams(page, episodeUrl);
        });
      }
      return directStreams(page, episodeUrl);
    })
    .catch(function (e) {
      console.log("[bb-nuvio][dizifon] " + (e && e.message));
      return [];
    });
}

module.exports = { getStreams };
