// @provider {"id":"weboo","name":"Weboo","types":["tv"],"domain":"weboo.com.tr","formats":["m3u8"],"sample":"tt6548864:tv:1:1","description":"Weboo dublajli/altyazili dizi arsivi (dramatr CDN, HLS)."}
/**
 * weboo — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> index.php?q= -> dizi page (?slug=...&b=<episode>) -> currentVideoUrl -> HLS.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://weboo.com.tr";
var ID = "weboo";

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

function abs(base, href) {
  if (!href) return null;
  if (/^https?:/i.test(href)) return href;
  var origin = /^(https?:\/\/[^/]+)/.exec(base);
  if (href.charAt(0) === "/") return origin ? origin[1] + href : href;
  return base.replace(/[^/]*$/, "") + href;
}

function norm(value) {
  return String(value || "")
    .replace(/ı/g, "i").replace(/ş/g, "s").replace(/ğ/g, "g")
    .replace(/ü/g, "u").replace(/ö/g, "o").replace(/ç/g, "c")
    .replace(/&#\d+;|&[a-z]+;/g, " ")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "");
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

/** Site search is substring-based and diacritic-sensitive, so cards are matched on folded titles. */
function findCard(query, key) {
  return text(BASE + "/index.php?q=" + encodeURIComponent(query)).then(function (body) {
    var start = body.indexOf("Arama Sonu");
    var scope = start === -1 ? body : body.slice(start);
    var re = /<a href="(\/dizi\/index\.php\?slug=[^"]+)"[\s\S]{0,3000}?<img[^>]+alt="([^"]+)"/g, m, best = null;
    while ((m = re.exec(scope)) !== null) {
      var name = norm(m[2]);
      if (!name) continue;
      var score = name === key ? 3 : name.indexOf(key) !== -1 ? 2 : key.indexOf(name) === 0 ? 1 : 0;
      if (score && (!best || score > best.score)) best = { url: abs(BASE, m[1]), score: score };
    }
    return best;
  });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var wantEpisode = parseInt(episode, 10) || 1;
  var wanted = "";
  var pageUrl = null;

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      wanted = title;
      if (!title) return null;
      var key = norm(title);
      return findCard(title, key).then(function (best) {
        if (best) return best;
        var short = title.split(/\s+/)[0];
        return short && norm(short) !== key ? findCard(short, key) : null;
      });
    })
    .then(function (card) {
      if (!card) return null;
      pageUrl = card.url + "&b=" + wantEpisode;
      return text(pageUrl);
    })
    .then(function (page) {
      if (!page) return [];
      var raw = first(/currentVideoUrl\s*=\s*"([^"]+)"/, page);
      if (!raw) return [];
      var stream = raw.indexOf("url=") !== -1 ? decodeURIComponent(first(/[?&]url=([^&]+)/, raw) || "") : raw;
      if (!/^https?:/.test(stream)) return [];
      var quality = first(/[?&]q=(\d+p)/, raw) || first(/[?&]q=(\d+p)/, stream) || "auto";
      return [{
        name: ID,
        title: ID + " - " + quality + " [HLS]",
        url: stream,
        quality: quality,
        headers: { Referer: pageUrl },
        subtitles: []
      }];
    })
    .catch(function (e) {
      console.log("[bb-nuvio][" + ID + "] " + (e && e.message));
      return [];
    });
}

module.exports = { getStreams };
