// @provider {"id":"onepacetr","name":"One Pace TR","types":["tv"],"domain":"onepacetr.net","formats":["mp4"],"sample":"tt31228002:tv:1:1","description":"One Pace Türkçe: sezonlar One Piece arc'ları, bölümler arc parçaları. Google Drive kaynakları doğrudan MP4 olarak verilir."}
/**
 * onepacetr — Nuvio provider (bb-nuvio).
 * Vite SPA; tüm veri Strapi API'sinden gelir. Sezon numarası = arc numarası,
 * bölüm numarası = arc parçası. Oynatıcılar arasından yalnızca doğrudan
 * indirilebilir olanlar (Google Drive / mp4 / m3u8) verilir.
 */
var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var API = "https://onepacetradmin-v3-4f0db9f5d700.herokuapp.com/api";
var AUTH = "Bearer 1fe5d72626ea6144e9d685128cfc40a54c303573432381c8943fc497bb2203f693922fa8afaa872168eef456bf3aa7802507fb2a526582a642241acc0665b30861c60ba344f5d8b925b7ebb75073d9419dfbf9be4b3420427bd22302889792973faaf01c8f1d5bd40105187d22aa9f5595b00a8e2700b94614678cc3303a7ac3";

function json(url) {
  return fetch(url, { headers: { "User-Agent": UA, Authorization: AUTH } }).then(function (r) {
    if (!r.ok) throw new Error("HTTP " + r.status + " " + url);
    return r.json();
  });
}

function fold(value) {
  return String(value || "").toLowerCase()
    .replace(/ı/g, "i").replace(/ş/g, "s").replace(/ğ/g, "g")
    .replace(/ü/g, "u").replace(/ö/g, "o").replace(/ç/g, "c");
}

function titleOf(id, mediaType) {
  var kind = mediaType === "tv" ? "series" : "movie";
  return fetch("https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json")
    .then(function (r) { return r.json(); })
    .then(function (j) { return String((j.meta || {}).name || "").trim(); })
    .catch(function () { return ""; });
}

function pickSeason(seasons, term, want) {
  var i, exact = null, loose = null;
  for (i = 0; i < seasons.length; i++) {
    var name = fold(seasons[i].name);
    if (term && name === term) { exact = seasons[i]; break; }
    if (!loose && term && (term.indexOf(name) !== -1 || name.indexOf(term) !== -1)) loose = seasons[i];
  }
  if (exact) return exact;
  if (loose) return loose;
  for (i = 0; i < seasons.length; i++) if (Number(seasons[i].number) === want) return seasons[i];
  return seasons[0] || null;
}

function pickEpisode(episodes, want) {
  var sorted = episodes.slice().sort(function (a, b) { return (a.number || 0) - (b.number || 0); });
  var i;
  for (i = 0; i < sorted.length; i++) if (Number(sorted[i].number) === want) return sorted[i];
  return sorted[want - 1] || sorted[0] || null;
}

function playerRows(players) {
  var rows = [];
  var seen = {};
  for (var i = 0; i < players.length; i++) {
    var player = players[i] || {};
    var url = String(player.url || "").trim();
    if (!url || seen[url]) continue;
    seen[url] = 1;
    var label = String(player.name || "").trim() || "kaynak";
    var drive = /drive\.google\.com\/file\/d\/([^/]+)/.exec(url);
    if (drive) {
      rows.push({
        name: "onepacetr",
        title: "One Pace TR - Google Drive [MP4]",
        url: "https://drive.usercontent.google.com/download?id=" + drive[1] + "&export=download&confirm=t",
        quality: "auto",
        headers: { "User-Agent": UA },
        subtitles: []
      });
      continue;
    }
    if (!/^https?:/.test(url) || !/(\.mp4|\.m3u8)(\?|$)/.test(url)) continue;
    rows.push({
      name: "onepacetr",
      title: "One Pace TR - " + label + " [MP4]",
      url: url,
      quality: "auto",
      headers: { "User-Agent": UA },
      subtitles: []
    });
  }
  return rows;
}

function getStreams(imdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var slug = null;
  return titleOf(imdbId, mediaType)
    .then(function (title) {
      return json(API + "/seasons?pagination[page]=1&pagination[pageSize]=100").then(function (body) {
        var seasons = (body.data || []).map(function (s) {
          return { name: s.name, number: s.number, slug: s.slug };
        });
        var picked = pickSeason(seasons, fold(title), want);
        if (!picked) return null;
        slug = picked.slug;
        return json(API + "/seasons?filters[slug][$eq]=" + encodeURIComponent(slug) + "&populate=*");
      });
    })
    .then(function (body) {
      if (!body) return null;
      var seasonObj = (body.data || [])[0];
      if (!seasonObj) return null;
      var picked = pickEpisode(seasonObj.episodes || [], wantEp);
      if (!picked) return null;
      return json(API + "/episodes/" + encodeURIComponent(picked.slug));
    })
    .then(function (body) {
      if (!body) return [];
      return playerRows(((body.data || {}).players) || []);
    })
    .catch(function (e) {
      console.log("[bb-nuvio][onepacetr] " + (e && e.message));
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