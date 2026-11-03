// @provider {"id":"koreanturk","name":"KoreanTurk","types":["tv"],"domain":"koreanturk.tv","formats":["m3u8"],"sample":"tt13433812:tv:1:1","description":"KoreanTurk.tv Kore dizileri arsivi; bolumler hintdizi player uzerinden HLS akis."}
/**
 * koreanturk — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /?s= -> dizi sayfasi -> /bolum/<slug>-N-bolum/ -> player iframe
 * -> player.html?video=%2Fstorage%2F....m3u8 -> HLS.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://koreanturk.tv";

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
  if (!m) return null;
  return m[1] === undefined ? m[0] : m[1];
}

function decode(s) {
  return String(s || "")
    .replace(/&#x([0-9a-f]+);/gi, function (_, h) { return String.fromCharCode(parseInt(h, 16)); })
    .replace(/&amp;/g, "&").replace(/&quot;/g, '"').replace(/&#39;|&apos;/g, "'");
}

function norm(s) {
  return decode(s)
    .toLowerCase()
    .replace(/ı/g, "i").replace(/ş/g, "s").replace(/ğ/g, "g").replace(/ü/g, "u")
    .replace(/ö/g, "o").replace(/ç/g, "c").replace(/â/g, "a")
    .replace(/\([^)]*\)/g, " ")
    .replace(/\b(?:izle|full|hd)\b/g, " ")
    .replace(/[^a-z0-9]+/g, " ")
    .trim()
    .replace(/^(?:the|a|an)\s+/, "");
}

function pick(items, title) {
  var want = norm(title);
  var best = null;
  var score = 0;
  for (var i = 0; i < items.length; i++) {
    var have = norm(items[i].title);
    var s = have === want ? 3 : have.indexOf(want) === 0 ? 2 : have.indexOf(want) !== -1 ? 1 : 0;
    if (s > score) { score = s; best = items[i]; }
  }
  return best || items[0] || null;
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

function search(title) {
  var q = String(title).replace(/^(?:the|a|an)\s+/i, "").replace(/\s+/g, "+");
  return text(BASE + "/?s=" + encodeURIComponent(q)).then(function (html) {
    var items = [];
    var re = /<div class="name">\s*<a[^>]*href="([^"]+)"[^>]*(?:title="([^"]*)")?[^>]*>/g;
    var m;
    while ((m = re.exec(html)) !== null) {
      if (m[1].indexOf(BASE) !== 0) continue;
      items.push({ url: m[1], title: decode(m[2] || "").trim() });
    }
    return items;
  });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var wantS = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var episodeUrl = null;

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return [];
      return search(title).then(function (items) {
        var hit = pick(items, title);
        return hit ? hit.url : null;
      });
    })
    .then(function (url) {
      if (!url) return [];
      return text(url);
    })
    .then(function (page) {
      if (!page) return [];
      var eps = [];
      var re = /<a href="(https?:\/\/koreanturk\.tv\/bolum\/[^"]*?-(\d+)-bolum\/?)"[^>]*(?:title="([^"]*)")?/g;
      var m;
      while ((m = re.exec(page)) !== null) {
        var label = decode(m[3] || "");
        var seasonNo = parseInt(first(/(\d+)\.\s*Sezon/, label) || "", 10) || 1;
        eps.push({ url: m[1], episode: +m[2], season: seasonNo });
      }
      if (!eps.length) return [];
      var chosen = null;
      var i;
      for (i = 0; i < eps.length; i++) if (eps[i].season === wantS && eps[i].episode === wantEp) { chosen = eps[i]; break; }
      for (i = 0; i < eps.length && !chosen; i++) if (eps[i].season === wantS) chosen = eps[i];
      if (!chosen) return [];
      episodeUrl = chosen.url;
      return text(episodeUrl);
    })
    .then(function (page) {
      if (!page) return [];
      var frame = first(/<iframe[^>]+src="([^"]+)"/, page);
      if (!frame) return [];
      var playerUrl = decode(frame);
      var videoParam = first(/[?&]video=([^&]+)/, playerUrl);
      if (!videoParam) return [];
      var videoPath = decodeURIComponent(videoParam);
      if (videoPath.indexOf(".m3u8") === -1) return [];
      var origin = playerUrl.replace(/\/player\/.*$/, "");
      return [
        {
          name: "koreanturk",
          title: "KoreanTurk - HLS",
          url: origin + videoPath,
          quality: "auto",
          headers: { Referer: playerUrl, "User-Agent": UA },
          subtitles: []
        }
      ];
    })
    .catch(function (e) {
      console.log("[bb-nuvio][koreanturk] " + (e && e.message));
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