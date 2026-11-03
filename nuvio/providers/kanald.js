// @provider {"id":"kanald","name":"KanalD","types":["tv"],"domain":"kanald.com.tr","formats":["m3u8"],"sample":"tt8264358:tv:1:1939","description":"Kanal D resmi arsivi; diziler ve programlar, bolum sayfasindaki kanaldvod HLS akisi."}
/**
 * kanald — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /diziler + /programlar kartlari -> dizi sayfasi -> bolum
 * -> bolum sayfasindaki kanaldvod .m3u8.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
var BASE = "https://www.kanald.com.tr";

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
    .replace(/&quot;/g, '"').replace(/&#39;|&apos;/g, "'").replace(/&amp;/g, "&");
}

function norm(s) {
  return decode(s)
    .toLowerCase()
    .replace(/ı/g, "i").replace(/ş/g, "s").replace(/ğ/g, "g").replace(/ü/g, "u")
    .replace(/ö/g, "o").replace(/ç/g, "c").replace(/â/g, "a")
    .replace(/[^a-z0-9]+/g, " ")
    .trim()
    .replace(/^(?:the|a|an)\s+/, "");
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

function cards(html) {
  var items = [];
  var re = /<a href="(\/[^"\/#?]+)"[^>]*>\s*<figure>[\s\S]{0,1500}?<figcaption>\s*<p[^>]*>([^<]*)<\/p>/g;
  var m;
  while ((m = re.exec(html)) !== null) {
    items.push({ url: BASE + m[1], title: decode(m[2]).trim() });
  }
  return items;
}

function findSeries(title) {
  var want = norm(title);
  return Promise.all([text(BASE + "/diziler"), text(BASE + "/programlar")])
    .then(function (pages) {
      var items = cards(pages[0]).concat(cards(pages[1]));
      for (var i = 0; i < items.length; i++) {
        if (norm(items[i].title) === want) return items[i];
      }
      for (i = 0; i < items.length; i++) {
        if (norm(items[i].title).indexOf(want) !== -1) return items[i];
      }
      return null;
    });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var wantEp = parseInt(episode, 10) || 1;
  var pageUrl = null;

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return [];
      return findSeries(title);
    })
    .then(function (hit) {
      if (!hit) return [];
      pageUrl = hit.url;
      return text(hit.url);
    })
    .then(function (page) {
      if (!page) return [];
      if (page.indexOf("/bolumler/") !== -1 && pageUrl.indexOf("/bolumler/") !== -1) return page;
      var eps = [];
      var re = /href="([^"]*\/bolumler\/[^"]*?-(\d+)-bolum[^"]*)"/g;
      var m;
      while ((m = re.exec(page)) !== null) eps.push({ url: m[1], episode: +m[2] });
      if (!eps.length) return [];
      var chosen = null;
      for (var i = 0; i < eps.length; i++) if (eps[i].episode === wantEp) { chosen = eps[i]; break; }
      if (!chosen) return [];
      pageUrl = chosen.url.indexOf("http") === 0 ? chosen.url : BASE + chosen.url;
      return text(pageUrl);
    })
    .then(function (page) {
      if (!page) return [];
      var stream = first(/https?:\/\/[^"'\s<>\\]+?\.m3u8[^"'\s<>\\]*/, page);
      if (!stream) return [];
      return [
        {
          name: "kanald",
          title: "KanalD - HLS",
          url: stream,
          quality: "auto",
          headers: { Referer: pageUrl, "User-Agent": UA },
          subtitles: []
        }
      ];
    })
    .catch(function (e) {
      console.log("[bb-nuvio][kanald] " + (e && e.message));
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