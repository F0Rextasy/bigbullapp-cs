// @provider {"id":"atv","name":"ATV","types":["tv"],"domain":"atv.com.tr","formats":["mp4"],"sample":"tt1534360:tv:1:1","description":"ATV dizi bolumleri (atv.com.tr VOD MP4 akislari).","verified":true}
/**
 * atv — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> atv.com.tr/<slug>/<ep>-bolum/izle -> VOD mp4.
 * shortcut: atv has no season metadata and numbers bolumler continuously, so `season` is only
 * a hint; a later-season request maps onto the same flat episode number. Upgrade when atv adds
 * season data.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://www.atv.com.tr";
var VOD = /(atv-vod\.ercdn\.net|erbvr\.com|h-cdn\.com)/;

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

function fold(value) {
  return String(value || "")
    .replace(/ı/g, "i").replace(/İ/g, "i")
    .replace(/ş/g, "s").replace(/Ş/g, "s")
    .replace(/ğ/g, "g").replace(/Ğ/g, "g")
    .replace(/ü/g, "u").replace(/Ü/g, "u")
    .replace(/ö/g, "o").replace(/Ö/g, "o")
    .replace(/ç/g, "c").replace(/Ç/g, "c")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
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

/** Stream urls on the page; the site's JSON-LD also carries mangled teaser paths ("<host>video/…"). */
function mediaUrls(html) {
  var all = [];
  var re = /https?:\/\/[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?/g;
  var m;
  while ((m = re.exec(html)) !== null) {
    var url = m[0];
    if (/\.com\.trvideo\//.test(url)) continue;
    if (all.indexOf(url) === -1) all.push(url);
  }
  var vod = [];
  for (var i = 0; i < all.length; i++) if (VOD.test(all[i])) vod.push(all[i]);
  return vod.length ? vod : all;
}

function qualityOf(url) {
  var m = /(\d{3,4})p/.exec(url);
  return m ? m[1] + "p" : "auto";
}

/** Falls back to the site's own search when the slug guess does not carry the episode. */
function slugFromSearch(title) {
  return text(BASE + "/arama?query=" + encodeURIComponent(title)).then(function (html) {
    var want = fold(title);
    var best = null;
    var bestScore = -1;
    var seen = {};
    var re = /href="\/([a-z0-9-]+)\/\d+-bolum\/izle"/g;
    var m;
    while ((m = re.exec(html)) !== null) {
      var slug = m[1];
      if (seen[slug]) continue;
      seen[slug] = true;
      var have = fold(slug);
      var score = have === want ? 100 : have.indexOf(want) === 0 || want.indexOf(have) === 0 ? 60 : 0;
      if (!score) {
        var a = want.split(" ");
        var b = have.split(" ");
        for (var i = 0; i < a.length; i++) if (b.indexOf(a[i]) !== -1) score += 10;
      }
      if (score > bestScore) {
        bestScore = score;
        best = slug;
      }
    }
    return bestScore > 0 ? best : null;
  });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var wantEp = parseInt(episode, 10) || 1;
  var title = "";

  function pageFor(slug) {
    return text(BASE + "/" + slug + "/" + wantEp + "-bolum/izle");
  }

  return titleOf(tmdbId, mediaType)
    .then(function (name) {
      title = name;
      if (!title) return null;
      var slug = fold(title).split(" ").join("-");
      if (!slug) return null;
      return pageFor(slug).catch(function () {
        return null;
      });
    })
    .then(function (html) {
      if (html && mediaUrls(html).length) return html;
      if (!title) return null;
      return slugFromSearch(title).then(function (slug) {
        return slug ? pageFor(slug) : null;
      });
    })
    .then(function (html) {
      if (!html) return [];
      var urls = mediaUrls(html);
      var out = [];
      for (var i = 0; i < urls.length; i++) {
        out.push({
          name: "ATV",
          title: "ATV - " + qualityOf(urls[i]) + (urls[i].indexOf(".m3u8") !== -1 ? " [HLS]" : " [MP4]"),
          url: urls[i],
          quality: qualityOf(urls[i]),
          headers: { "User-Agent": UA, "Referer": BASE + "/" },
          subtitles: []
        });
      }
      return out;
    })
    .catch(function (e) {
      console.log("[bb-nuvio][atv] " + (e && e.message));
      return [];
    });
}

module.exports = { getStreams };


/* NuvioMobile always calls getStreams with args.tmdbId (JsBindings.kt); the
   value is a numeric TMDB id after TmdbService.ensureTmdbId. Resolve it to
   IMDb so the existing Cinemeta/title flow keeps working. */
var __origGetStreams = module.exports.getStreams;
module.exports.getStreams = function (id, mediaType, season, episode) {
  var raw = String(id || "");
  if (!/^\d+$/.test(raw)) return __origGetStreams(raw, mediaType, season, episode);
  var tv = mediaType !== "movie";
  var headers = { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36", "Accept": "application/json" };
  var key = typeof TMDB_API_KEY !== "undefined" ? TMDB_API_KEY : "";
  var api = "https://www.wikidata.org/w/api.php?format=json";
  var prop = tv ? "P4983" : "P4947";
  function fromTmdb() {
    if (!key) return Promise.resolve("");
    return fetch("https://api.themoviedb.org/3/" + (tv ? "tv" : "movie") + "/" + raw + "/external_ids?api_key=" + encodeURIComponent(key), { headers: headers })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (json) { return String((json && json.imdb_id) || ""); })
      .catch(function () { return ""; });
  }
  function fromWikidata() {
    return fetch(api + "&action=query&list=search&srlimit=1&srsearch=" + encodeURIComponent("haswbstatement:" + prop + "=" + raw), { headers: headers })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (json) {
        var hits = (json && json.query && json.query.search) || [];
        if (!hits.length) return "";
        return fetch(api + "&action=wbgetentities&props=claims&ids=" + hits[0].title, { headers: headers });
      })
      .then(function (r) { return r && r.ok ? r.json() : null; })
      .then(function (json) {
        var entities = (json && json.entities) || {};
        for (var entity in entities) {
          var rows = ((entities[entity] || {}).claims || {}).P345 || [];
          for (var i = 0; i < rows.length; i++) {
            var value = rows[i] && rows[i].mainsnak && rows[i].mainsnak.datavalue && rows[i].mainsnak.datavalue.value;
            if (/^tt\d+$/.test(String(value || ""))) return value;
          }
        }
        return "";
      })
      .catch(function () { return ""; });
  }
  return fromTmdb().then(function (imdb) { return imdb || fromWikidata(); }).then(function (imdb) {
    return __origGetStreams(imdb || raw, mediaType, season, episode);
  }).catch(function () { return []; });
};