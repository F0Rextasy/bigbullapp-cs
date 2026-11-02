// @provider {"id":"dizikorea3","name":"Dizikorea3","types":["tv"],"domain":"dizikorea3.com","formats":["m3u8"],"sample":"tt43088191:tv:1:1","description":"Dizikorea3 Kore dizileri arsivi (HLS + Turkce altyazi)."}
/**
 * dizikorea3 — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /sayfa/1?s= -> /dizi/<slug> -> /sezon-N/bolum-M -> player iframes.
 * The site ignores `?search=`; WordPress-style `?s=` is the working search, paged by path.
 * Player iframes are lazy-loaded, so the real URL lives in data-src.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://dizikorea3.com";

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
  if (o.origin) headers.Origin = o.origin;
  if (o.ajax) headers["X-Requested-With"] = "XMLHttpRequest";
  if (o.form) headers["Content-Type"] = "application/x-www-form-urlencoded";
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
  href = String(href).replace(/&amp;/g, "&").trim();
  if (/^https?:/i.test(href)) return href;
  var origin = /^(https?:\/\/[^/]+)/.exec(base);
  if (href.charAt(0) === "/") return origin ? origin[1] + href : href;
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

function norm(value) {
  return String(value || "").toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
}

/** `a.poster-card` blocks; the title only lives in the poster's alt text. */
function cards(html) {
  var out = [];
  var re = /<a[^>]*class="[^"]*poster-card[^"]*"[^>]*>([\s\S]{0,900}?)<\/a>/g;
  var block;
  while ((block = re.exec(html)) !== null) {
    var href = first(/href="([^"]*)"/, block[0]);
    var alt = first(/<img[^>]+alt="([^"]*)"/, block[0]);
    if (!href || href.indexOf("/dizi/") === -1) continue;
    out.push({ url: abs(BASE, href), title: String(alt || "").trim() });
  }
  return out;
}

function pick(list, title) {
  var want = norm(title);
  var best = null;
  var bestScore = 0;
  for (var i = 0; i < list.length; i++) {
    var have = norm(list[i].title);
    var score = have === want ? 100 : have.indexOf(want) === 0 || want.indexOf(have) === 0 ? 80 : have.indexOf(want) !== -1 ? 60 : 0;
    if (score > bestScore) {
      bestScore = score;
      best = list[i];
    }
    if (bestScore === 100) break;
  }
  return bestScore >= 60 ? best : null;
}

/** `/dizi/<slug>/sezon-<n>/bolum-<m>`. */
function episodes(html, base) {
  var out = [];
  var seen = {};
  var re = /href="([^"]*\/sezon-(\d+)\/bolum-(\d+))"/g;
  var m;
  while ((m = re.exec(html)) !== null) {
    var url = abs(base, m[1]);
    if (!url || seen[url]) continue;
    seen[url] = 1;
    out.push({ url: url, season: parseInt(m[2], 10), episode: parseInt(m[3], 10) });
  }
  return out;
}

/** playerdkorea/FirePlayer answers JSON with `securedLink`/`videoSource`. */
function firePlayer(embed, found) {
  var id = String(embed).split("/").pop();
  var endpoint = embed.indexOf("/player/index.php") !== -1
    ? embed + "&do=getVideo"
    : embed.replace("/video/" + id, "/player/index.php?data=" + id + "&do=getVideo");
  return text(endpoint, {
    method: "POST",
    form: true,
    ajax: true,
    referer: BASE + "/",
    origin: "https://playerdkorea.xyz",
    body: "hash=" + id + "&r=" + encodeURIComponent(BASE + "/")
  })
    .then(function (body) {
      var json = JSON.parse(body);
      var keys = ["securedLink", "videoSource"];
      for (var i = 0; i < keys.length; i++) {
        var stream = String(json[keys[i]] || "");
        if (!/^https?:/i.test(stream)) continue;
        if (found.some(function (item) { return item.url === stream; })) continue;
        found.push({ url: stream, frame: embed, hls: true });
      }
      return found;
    })
    .catch(function () {
      return found;
    });
}


/** Master playlist names its variant; a playlist that is not #EXTM3U is a dead token. */
function quality(item) {
  if (!item.hls) return Promise.resolve("auto");
  return text(item.url, { referer: item.frame })
    .then(function (body) {
      if (body.indexOf("#EXTM3U") !== 0) return null;
      var best = 0;
      var re = /RESOLUTION=\d+x(\d+)/g;
      var m;
      while ((m = re.exec(body)) !== null) {
        var height = parseInt(m[1], 10) || 0;
        if (height > best) best = height;
      }
      return best ? best + "p" : "auto";
    })
    .catch(function () {
      return null;
    });
}

/** `/ara?q=` answers JSON (title/url); the archive page `?s=` is the fallback. */
function search(title) {
  return text(BASE + "/ara?q=" + encodeURIComponent(title))
    .then(function (body) {
      var items = JSON.parse(body).items || [];
      var list = [];
      for (var i = 0; i < items.length; i++) {
        var item = items[i] || {};
        var url = abs(BASE, item.url || (item.slug ? "/dizi/" + item.slug : ""));
        if (!url) continue;
        list.push({ url: url, title: String(item.title || item.slug || "") });
      }
      if (list.length) return list;
      return text(BASE + "/sayfa/1?s=" + encodeURIComponent(title)).then(cards);
    })
    .catch(function () {
      return text(BASE + "/sayfa/1?s=" + encodeURIComponent(title)).then(cards);
    });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var episodeUrl = null;
  var frames = [];

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return null;
      return search(title).then(function (list) {
        return pick(list, title);
      });
    })
    .then(function (series) {
      if (!series) return null;
      return text(series.url).then(function (detail) {
        var list = episodes(detail, series.url);
        for (var i = 0; i < list.length; i++) {
          if (list[i].season === want && list[i].episode === wantEp) {
            episodeUrl = list[i].url;
            break;
          }
        }
        if (!episodeUrl) episodeUrl = list.length ? list[0].url : null;
        return episodeUrl;
      });
    })
    .then(function (url) {
      if (!url) return [];
      return text(url).then(function (html) {
        var re = /<iframe[^>]+(?:data-src|src)="([^"]+)"/g;
        var m;
        while ((m = re.exec(html)) !== null) {
          var frame = abs(url, m[1]);
          if (frame && /^https?:/i.test(frame) && frames.indexOf(frame) === -1) frames.push(frame);
        }
        return url;
      });
    })
    .then(function (referer) {
      return frames.reduce(function (chain, frame) {
        return chain.then(function (found) {
          if (frame.indexOf("playerdkorea") !== -1 || frame.indexOf("playerkorea") !== -1) {
            return firePlayer(frame, found);
          }
          return text(frame, { referer: referer })
            .then(function (html) {
              var body = html.replace(/\\\//g, "/");
              var re = /https?:\/\/[^"'\s<>\\]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>\\]*)?/g;
              var m;
              while ((m = re.exec(body)) !== null) {
                if (found.some(function (item) { return item.url === m[0]; })) continue;
                found.push({ url: m[0], frame: frame, hls: m[0].indexOf(".m3u8") !== -1 });
              }
              return found;
            })
            .catch(function () {
              return found;
            });
        });
      }, Promise.resolve([]));
    })
    .then(function (found) {
      return found.reduce(function (chain, item) {
        return chain.then(function (rows) {
          return quality(item).then(function (label) {
            if (!label) return rows;
            rows.push({
              name: "dizikorea3",
              title: "dizikorea3 - " + label + (item.hls ? " [HLS]" : " [MP4]"),
              url: item.url,
              quality: label,
              headers: { Referer: item.frame },
              subtitles: []
            });
            return rows;
          });
        });
      }, Promise.resolve([]));
    })
    .catch(function (e) {
      console.log("[bb-nuvio][dizikorea3] " + (e && e.message));
      return [];
    });
}

module.exports = { getStreams };
