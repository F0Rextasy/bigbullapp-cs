// @provider {"id":"hdfilmizlebest","name":"HDFilmizleBest","types":["movie"],"domain":"hdfilmizle.best","formats":["m3u8"],"sample":"tt0133093:movie:1:1","description":"HDFilmizle.best film arsivi; HDF nonce ajax'i uzerinden HLS akis ve altyazi izlekleri."}
/**
 * hdfilmizlebest — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> site search -> film/bolum sayfasi -> _hdfNonce_ + data-post
 * -> /ajax/videosrc/ JSON (m3u8 + VTT tracks).
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://www.hdfilmizle.best";

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
    .replace(/&#(\d+);/g, function (_, d) { return String.fromCharCode(parseInt(d, 10)); })
    .replace(/&quot;/g, '"').replace(/&#39;|&apos;/g, "'").replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<").replace(/&gt;/g, ">");
}

function norm(s) {
  return decode(s)
    .toLowerCase()
    .replace(/ı/g, "i").replace(/ş/g, "s").replace(/ğ/g, "g").replace(/ü/g, "u")
    .replace(/ö/g, "o").replace(/ç/g, "c").replace(/â/g, "a")
    .replace(/\([^)]*\)/g, " ")
    .replace(/\b(?:izle|full|hd|turkce|dublaj|altyazili)\b/g, " ")
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
  var q = String(title).replace(/^(?:the|a|an)\s+/i, "");
  return text(BASE + "/?s=" + encodeURIComponent(q)).then(function (html) {
    var items = [];
    var re = /<a\b[^>]*>/g;
    var m;
    while ((m = re.exec(html)) !== null) {
      var tag = m[0];
      var href = first(/href="([^"]+)"/, tag);
      if (!href || href.indexOf(BASE + "/film/") !== 0 && href.indexOf(BASE + "/seri-filmler/") !== 0) continue;
      var label = first(/data-title="([^"]*)"/, tag) || first(/title="([^"]*)"/, tag) || "";
      var path = href.slice(BASE.length).replace(/[?#].*$/, "").replace(/\/+$/, "");
      if (path.split("/").length < 3) continue;
      items.push({ url: href, title: decode(label) });
    }
    return items;
  });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var pageUrl = null;

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
      pageUrl = url;
      return text(url);
    })
    .then(function (page) {
      if (!page) return [];
      var nonce = first(/_hdfNonce_\s*=\s*"([^"]+)"/, page);
      var post = first(/data-post="(\d+)"/, page);
      if (!nonce || !post) return [];
      return text(BASE + "/ajax/videosrc/?id=" + post + "&lang=tr&mr=0", {
        headers: { "X-HDF-Nonce": nonce, "X-Requested-With": "XMLHttp" + "Request" },
        referer: pageUrl
      });
    })
    .then(function (body) {
      if (!body) return [];
      var json = JSON.parse(body);
      var src = String(json.src || "");
      if (!/^https?:/.test(src)) return [];
      var subs = [];
      var tracks = json.tracks || [];
      for (var i = 0; i < tracks.length; i++) {
        var track = tracks[i] || {};
        if (!/^https?:/.test(String(track.src || ""))) continue;
        var lang = String(track.srclang || "tr").toLowerCase();
        subs.push({ url: track.src, language: lang, name: (track.label || lang) + " [hdfilmizlebest]" });
      }
      return [
        {
          name: "hdfilmizlebest",
          title: "HDFilmizleBest - HLS",
          url: src,
          quality: "auto",
          headers: { Referer: pageUrl, "User-Agent": UA },
          subtitles: subs.slice(0, 5)
        }
      ];
    })
    .catch(function (e) {
      console.log("[bb-nuvio][hdfilmizlebest] " + (e && e.message));
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