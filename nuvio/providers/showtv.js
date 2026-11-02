// @provider {"id":"showtv","name":"ShowTV","types":["tv"],"domain":"showtv.com.tr","formats":["m3u8","mp4"],"sample":"tt21764074:tv:1:1","description":"Show TV resmi dizi ve program arşivi (Ciner CDN, HLS)."}
/**
 * showtv — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /arama?ara= -> /dizi|programlar/tanitim/<slug>/<id> -> episode link
 *       -> /dizi/tum_bolumler/... -> embedded HLS (or MP4 ladder).
 * The tanitim page only lists the latest episodes, so an older episode falls back to the
 * per-series sitemap (sitemap_<slug>.xml) which carries every episode URL.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
var BASE = "https://www.showtv.com.tr";

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
  if (o.headers) for (var k in o.headers) headers[k] = o.headers[k];
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

function abs(href) {
  if (!href) return null;
  return /^https?:/i.test(href) ? href : BASE + href;
}

function norm(value) {
  return String(value || "")
    .toLowerCase()
    .replace(/[\u0131]/g, "i")
    .replace(/[\u015f]/g, "s")
    .replace(/[\u011f]/g, "g")
    .replace(/[\u00fc]/g, "u")
    .replace(/[\u00f6]/g, "o")
    .replace(/[\u00e7]/g, "c")
    .replace(/[^a-z0-9]/g, "");
}

function pick(cards, wanted) {
  var w = norm(wanted);
  if (!w) return null;
  var subset = null;
  for (var i = 0; i < cards.length; i++) {
    var n = norm(cards[i].title);
    if (!n) continue;
    if (n === w) return cards[i];
    if (!subset && (w.indexOf(n) !== -1 || n.indexOf(w) !== -1)) subset = cards[i];
  }
  return subset;
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

function searchCards(html) {
  var cards = [];
  var re = /<a[^>]*href="([^"]*\/(?:dizi|programlar)\/tanitim\/[^"]*)"[^>]*>/g;
  var m;
  while ((m = re.exec(html)) !== null) {
    var title = first(/title="([^"]*)"/, m[0]);
    if (!title) {
      var tail = html.slice(m.index, m.index + 900);
      title = first(/<span class="[^"]*text-base[^"]*">([^<]*)</, tail);
    }
    if (!title) continue;
    cards.push({ url: abs(m[1]), title: title.trim() });
  }
  return cards;
}

function qualityOf(master) {
  var heights = [];
  var m;
  var re = /RESOLUTION=\d+x(\d+)/gi;
  while ((m = re.exec(String(master || ""))) !== null) heights.push(parseInt(m[1], 10));
  var max = 0;
  for (var i = 0; i < heights.length; i++) if (heights[i] > max) max = heights[i];
  if (!max) return "auto";
  if (max >= 2160) return "2160p";
  if (max >= 1080) return "1080p";
  if (max >= 720) return "720p";
  if (max >= 480) return "480p";
  return "360p";
}

function episodeUrl(body, slug, want, wantEp) {
  var re = new RegExp("/dizi/tum_bolumler/" + slug + "-sezon-(\\d+)-bolum-(\\d+)-izle/\\d+", "g");
  var m;
  while ((m = re.exec(body)) !== null) {
    if (parseInt(m[1], 10) === want && parseInt(m[2], 10) === wantEp) {
      return abs(m[0]);
    }
  }
  return null;
}

function streamsFor(pageUrl) {
  return text(pageUrl).then(function (raw) {
    var html = raw.replace(/&quot;/g, '"').replace(/&amp;/g, "&");
    var hls = first(/(https?:\/\/[^"'\\ ]+\.m3u8[^"'\\ ]*)/, html);
    var headers = { Referer: pageUrl, "User-Agent": UA };
    if (hls) {
      return text(hls, { referer: pageUrl })
        .then(function (master) {
          var quality = qualityOf(master);
          return [{ name: "ShowTV", title: "ShowTV - " + quality + " [HLS]", url: hls, quality: quality, headers: headers, subtitles: [] }];
        })
        .catch(function () {
          return [{ name: "ShowTV", title: "ShowTV - auto [HLS]", url: hls, quality: "auto", headers: headers, subtitles: [] }];
        });
    }
    var rows = [];
    var seen = {};
    var re = /https?:\/\/[^"'\\ ]+\.mp4[^"'\\ ]*/g;
    var m;
    while ((m = re.exec(html)) !== null) {
      if (seen[m[0]]) continue;
      seen[m[0]] = true;
      var size = /_(\d+)x(\d+)\.mp4/.exec(m[0]);
      var height = size ? parseInt(size[2], 10) : 0;
      var label = height >= 1080 ? "1080p" : height >= 720 ? "720p" : height >= 480 ? "480p" : height ? "360p" : "auto";
      rows.push({ name: "ShowTV", title: "ShowTV - " + label + " [MP4]", url: m[0], quality: label, headers: headers, subtitles: [] });
    }
    return rows;
  });
}
function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var slug = null;

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return null;
      return text(BASE + "/arama?ara=" + encodeURIComponent(title)).then(function (html) {
        return pick(searchCards(html), title);
      });
    })
    .then(function (card) {
      if (!card) return null;
      slug = first(/\/tanitim\/([^/]+)\//, card.url);
      return text(card.url);
    })
    .then(function (seriesHtml) {
      if (!seriesHtml || !slug) return null;
      var found = episodeUrl(seriesHtml, slug, want, wantEp);
      if (found) return found;
      return text(BASE + "/sitemap_" + slug + ".xml").then(function (xml) {
        return episodeUrl(xml, slug, want, wantEp);
      });
    })
    .then(function (episodeLink) {
      if (!episodeLink) return [];
      return streamsFor(episodeLink);
    })
    .then(function (rows) {
      return rows || [];
    })
    .catch(function (e) {
      console.log("[bb-nuvio][showtv] " + (e && e.message));
      return [];
    });
}

module.exports = { getStreams };
