// @provider {"id":"yesilcamtv","name":"YesilCamTv","types":["movie"],"domain":"yesilcamtv.com.tr","formats":["m3u8","mp4"],"sample":"tt0297138:movie:1:1","description":"Yeşilçam TV: klasik Türk filmleri; Rumble HLS kaliteleri ve doğrudan MP4 akışları."}
/**
 * yesilcamtv — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /?s= card -> film page -> rumble embed -> HLS qualities.
 */
var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://yesilcamtv.com.tr";

function text(url, referer) {
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (referer) headers.Referer = referer;
  return fetch(url, { headers: headers }).then(function (r) {
    if (!r.ok) throw new Error("HTTP " + r.status + " " + url);
    return r.text();
  });
}

function fold(value) {
  return String(value || "").toLowerCase()
    .replace(/ı/g, "i").replace(/ş/g, "s").replace(/ğ/g, "g")
    .replace(/ü/g, "u").replace(/ö/g, "o").replace(/ç/g, "c")
    .replace(/[^a-z0-9]+/g, " ").trim();
}

function titleOf(id, mediaType) {
  var kind = mediaType === "tv" ? "series" : "movie";
  return fetch("https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json")
    .then(function (r) { return r.json(); })
    .then(function (j) { return String((j.meta || {}).name || "").trim(); })
    .catch(function () { return ""; });
}

function parseCards(page) {
  var re = /<a href="(https:\/\/yesilcamtv\.com\.tr\/[^"]+-izle\/?)"[^>]*>[\s\S]{0,700}?alt="([^"]*)"/g;
  var m, cards = [];
  while ((m = re.exec(page)) !== null) cards.push({ url: m[1], label: fold(m[2]) });
  return cards;
}

/* Site araması Türkçe harflere duyarlı ("Gelinlik Kızlar" ≠ "Gelinlik Kizlar");
   bu yüzden sorgu sırayla tam başlık, en uzun kelime ve katlanmış haliyle denenir. */
function bestCard(cards, term) {
  var want = term.split(" ").filter(function (w) { return w.length > 2; });
  var best = null, bestScore = 0, i, j, hit;
  for (i = 0; i < cards.length; i++) {
    var label = cards[i].label.split(" ");
    hit = 0;
    for (j = 0; j < want.length; j++) if (label.indexOf(want[j]) !== -1) hit++;
    if (hit > bestScore) { bestScore = hit; best = cards[i]; }
  }
  return best ? best.url : null;
}

function tryQuery(query, term) {
  return text(BASE + "/?s=" + encodeURIComponent(query)).then(function (page) {
    return bestCard(parseCards(page), term);
  });
}

function filmUrl(title) {
  var term = fold(title);
  if (!term) return Promise.resolve(null);
  var words = String(title).split(/\s+/).filter(Boolean);
  var longest = words.slice().sort(function (a, b) { return b.length - a.length; })[0] || "";
  var queries = [title];
  if (longest && longest !== title) queries.push(longest);
  if (term !== title) queries.push(term);
  var i = 0;
  function next() {
    if (i >= queries.length) return null;
    return tryQuery(queries[i++], term).then(function (hit) {
      return hit || next();
    });
  }
  return Promise.resolve().then(next);
}

/* Rumble embed HTML carries "<key>":{"url":...,"meta":{...w,h}} per quality. */
function rumbleRows(page, add) {
  var ids = [];
  var re = /rumble\.com\/embed\/([^\/"'?#]+)/g;
  var m;
  while ((m = re.exec(page)) !== null) if (ids.indexOf(m[1]) === -1) ids.push(m[1]);
  if (!ids.length) return Promise.resolve();
  /* shortcut: yalnızca ilk rumble gömülüsü okunur; çok parçalı sayfa çıkarsa tüm id'ler dolaşılmalı */
  return text("https://rumble.com/embed/" + ids[0] + "/", "https://rumble.com/").then(function (embed) {
    var media = /"(\d+|tar|mp4)":\{"url":"(https:[^"]+?)","meta":\{"bitrate":\d+,"size":\d+,"w":\d+,"h":(\d+)/g;
    var hit;
    while ((hit = media.exec(embed)) !== null) {
      /* yalnızca gerçek video varyantları: mp4 girdisi 180p önizleme şeridi, ses .aac */
      if (hit[2].indexOf("r_file=chunklist.m3u8") === -1) continue;
      var height = parseInt(hit[3], 10) || 0;
      add(hit[2].replace(/\\\//g, "/"), height ? height + "p" : "auto", "https://rumble.com/");
    }
  });
}

function getStreams(imdbId, mediaType, season, episode) {
  var pageUrl = null;
  return titleOf(imdbId, mediaType)
    .then(function (title) {
      return filmUrl(title);
    })
    .then(function (url) {
      if (!url) return [];
      pageUrl = url;
      return text(url);
    })
    .then(function (page) {
      if (!page) return [];
      var rows = [];
      var seen = {};
      var seenQ = {};
      function add(url, quality, referer) {
        if (!/^https?:/.test(url) || seen[url] || seenQ[quality]) return;
        seen[url] = 1;
        seenQ[quality] = 1;
        rows.push({
          name: "yesilcamtv",
          title: "YesilCamTv - " + quality + (/(\.m3u8|chunklist)/.test(url) ? " [HLS]" : " [MP4]"),
          url: url,
          quality: quality,
          headers: { Referer: referer, "User-Agent": UA },
          subtitles: []
        });
      }
      return rumbleRows(page, add).then(function () {
        var direct = /https?:\/\/[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?/g;
        var m;
        while ((m = direct.exec(page)) !== null) add(m[0], "auto", pageUrl);
        rows.sort(function (a, b) {
          return (parseInt(b.quality, 10) || 0) - (parseInt(a.quality, 10) || 0);
        });
        return rows.slice(0, 6);
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][yesilcamtv] " + (e && e.message));
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