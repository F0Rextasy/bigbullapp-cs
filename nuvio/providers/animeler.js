// @provider {"id":"animeler","name":"Animeler","types":["tv"],"domain":"animeler.live","formats":["mp4"],"sample":"tt12343534:tv:1:1","description":"Animeler.live Turkce altyazili anime arsivi (ok.ru / mail.ru MP4 akislari)."}
/**
 * animeler — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /filtre?search= -> series slug -> /<slug>/bolum-<n> -> ok.ru|mail.ru video meta.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://animeler.live";
var SKIP = { "/": 1, "/filtre": 1, "/takvim": 1, "/birlikte": 1, "/giris": 1, "/kayit": 1, "/az": 1, "/liste": 1, "/top500": 1, "/iletisim": 1 };

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

/** Slug that best matches the title, preferring a later-season page when one is asked for. */
function pickSlug(html, title, want) {
  var wantFold = fold(title);
  var wantTokens = wantFold.split(" ");
  var seen = {};
  var best = null;
  var bestScore = -1;
  var re = /href="(\/[^"?#]+)"/g;
  var m;
  while ((m = re.exec(html)) !== null) {
    var path = m[1];
    if (SKIP[path] || seen[path]) continue;
    seen[path] = true;
    var slug = path.slice(1);
    if (!/^[a-z0-9-]+$/.test(slug)) continue;
    var have = fold(slug);
    var score = have === wantFold ? 100 : have.indexOf(wantFold) === 0 ? 60 : 0;
    if (!score) {
      var parts = have.split(" ");
      for (var i = 0; i < wantTokens.length; i++) if (parts.indexOf(wantTokens[i]) !== -1) score += 10;
    }
    if (score <= 0) continue;
    if (want > 1) {
      var season = String(want);
      var seasonRe = new RegExp("(?:season|sezon)[-_]?" + season + "|" + season + "(?:nd|rd|th)?[-_]season|" + season + "[-_]sezon");
      score += seasonRe.test(slug) ? 50 : 0;
    }
    if (score > bestScore) {
      bestScore = score;
      best = slug;
    }
  }
  return best;
}

/** Runs fn(id) down the list until one answers with rows. */
function firstHit(ids, fn) {
  var index = 0;
  function step() {
    if (index >= ids.length) return Promise.resolve([]);
    var id = ids[index++];
    return fn(id)
      .then(function (rows) {
        return rows && rows.length ? rows : step();
      })
      .catch(function () {
        return step();
      });
  }
  return step();
}

function okRu(id) {
  return fetch("https://ok.ru/dk?cmd=videoPlayerMetadata&mid=" + encodeURIComponent(id), {
    method: "POST",
    headers: {
      "User-Agent": "Mozilla/5.0 (Linux; Android 13)",
      "Referer": "https://ok.ru/",
      "Content-Type": "application/x-www-form-urlencoded"
    },
    body: ""
  })
    .then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status);
      return r.text();
    })
    .then(function (body) {
      var videos = JSON.parse(body).videos || [];
      var order = ["hd", "sd", "low", "lowest", "mobile"];
      var out = [];
      for (var i = 0; i < videos.length; i++) {
        var url = String((videos[i] || {}).url || "");
        if (!/^https?:/.test(url)) continue;
        var label = String(videos[i].name || "");
        var rank = order.indexOf(label);
        out.push({
          name: "Animeler",
          title: "Animeler - OkRu " + (label || "kaynak") + " [MP4]",
          url: url,
          quality: label === "hd" ? "720p" : label === "sd" ? "480p" : "360p",
          headers: { "User-Agent": "Mozilla/5.0 (Linux; Android 13)", "Referer": "https://ok.ru/" },
          subtitles: [],
          _rank: rank === -1 ? 9 : rank
        });
      }
      out.sort(function (a, b) { return a._rank - b._rank; });
      for (var j = 0; j < out.length; j++) delete out[j]._rank;
      return out;
    });
}

function mailRu(id) {
  return text("https://my.mail.ru/+/video/meta/" + encodeURIComponent(id), {
    referer: "https://my.mail.ru/video/embed/" + id,
    headers: { "User-Agent": "Mozilla/5.0" }
  }).then(function (body) {
    var videos = JSON.parse(body).videos || [];
    var out = [];
    for (var i = 0; i < videos.length; i++) {
      var raw = String((videos[i] || {}).url || "");
      var url = raw.indexOf("//") === 0 ? "https:" + raw : raw;
      if (!/^https?:/.test(url)) continue;
      var label = String(videos[i].key || "MailRu");
      out.push({
        name: "Animeler",
        title: "Animeler - MailRu " + label + " [MP4]",
        url: url,
        quality: label,
        headers: { "User-Agent": "Mozilla/5.0", "Referer": "https://my.mail.ru/" },
        subtitles: []
      });
    }
    return out;
  });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var title = "";

  return titleOf(tmdbId, mediaType)
    .then(function (name) {
      title = name;
      if (!title) return null;
      return text(BASE + "/filtre?search=" + encodeURIComponent(title));
    })
    .then(function (html) {
      if (!html) return null;
      var slug = pickSlug(html, title, want);
      if (!slug) return null;
      return text(BASE + "/" + slug + "/bolum-" + wantEp);
    })
    .then(function (page) {
      if (!page) return [];
      var ids = [];
      var seen = {};
      var okRe = /(?:ok\.ru|odnoklassniki\.ru)\/videoembed\/(\d+)/g;
      var m;
      while ((m = okRe.exec(page)) !== null) if (!seen[m[1]]) seen[m[1]] = ids.push(m[1]);
      // shortcut: ok.ru mirrors only; mail.ru is the fallback when every ok.ru id fails.
      return firstHit(ids.slice(0, 3), okRu).then(function (rows) {
        if (rows.length) return rows;
        var mailRe = /(?:my\.)?mail\.ru\/(?:[^"'\s]*?)video\/embed\/(\d+)/g;
        var mailIds = [];
        while ((m = mailRe.exec(page)) !== null) mailIds.push(m[1]);
        if (!mailIds.length) return rows;
        return mailRu(mailIds[0]).catch(function () { return []; });
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][animeler] " + (e && e.message));
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