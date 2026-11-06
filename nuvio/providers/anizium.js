// @provider {"id":"anizium", "name":"Anizium", "types":["tv"], "domain":"anizium.co", "formats":["mp4"], "sample":"tt0988824:tv:1:1", "description":"Anizium anime arsivi (Turkce altyazili MP4 akislari).", "version":"1.0.6"}
/**
 * anizium — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> api.anizium.co/page/search -> /anime/source -> group item mp4 links.
 * Every api call needs `Cf-Control`: hex(xor(JSON {nonce: ts}, TOKEN_KEY + "_" + istanbul-weekday)).
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://anizium.co";
var API = "https://api.anizium.co";
var TOKEN_KEY = "hlxjl1c2w281ax473rt1ofgrvhyjvi";
var ALPHANUM = "abcdefghijklmnopqrstuvwxyz0123456789";
var DAYS = ["sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday"];

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

function cfControl() {
  var weekday = DAYS[new Date(Date.now() + 3 * 3600000).getUTCDay()];
  var key = TOKEN_KEY + "_" + weekday;
  var nonce = "";
  for (var i = 0; i < 6; i++) nonce += ALPHANUM.charAt(Math.floor(Math.random() * ALPHANUM.length));
  // Pure-ASCII JSON keeps the xor byte-for-byte with the site's own client.
  var payload = '{"' + nonce + '":' + String(Date.now()) + "}";
  var out = "";
  for (var j = 0; j < payload.length; j++) {
    var value = payload.charCodeAt(j) ^ key.charCodeAt(j % key.length);
    out += ("0" + value.toString(16)).slice(-2);
  }
  return out;
}

function api(url) {
  return text(url, {
    referer: BASE + "/",
    headers: { "Cf-Control": cfControl(), "language": "tr", "site": "main", "Accept": "application/json" }
  }).then(function (body) {
    return JSON.parse(body);
  });
}

function pick(items, title) {
  var want = fold(title);
  var best = null;
  var bestScore = -1;
  for (var i = 0; i < (items || []).length; i++) {
    var item = items[i] || {};
    if (!item.ID) continue;
    var have = fold(item.name);
    var score = have === want ? 100 : 0;
    if (!score && have.indexOf(want) === 0) score = 60;
    if (!score) {
      var a = want.split(" ");
      var b = have.split(" ");
      for (var j = 0; j < a.length; j++) if (b.indexOf(a[j]) !== -1) score += 10;
    }
    if (score > bestScore) {
      bestScore = score;
      best = item;
    }
  }
  return bestScore > 0 ? best : null;
}

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var title = "";

  return titleOf(tmdbId, mediaType)
    .then(function (name) {
      title = name;
      if (!title) return null;
      return api(API + "/page/search?value=" + encodeURIComponent(title) + "&page=1");
    })
    .then(function (body) {
      if (!body) return null;
      var hit = pick(((body.page || {}).data) || [], title);
      if (!hit) return null;
      return api(
        API + "/anime/source?id=" + encodeURIComponent(hit.ID) + "&site=main" +
          "&season=" + want + "&episode=" + wantEp
      );
    })
    .then(function (source) {
      if (!source || !source.success) return [];
      var out = [];
      var subtitles = [];
      var subs = source.subtitles || [];
      for (var s = 0; s < subs.length; s++) {
        var link = String((subs[s] || {}).link || "");
        if (!link) continue;
        subtitles.push({
          url: link,
          language: String(subs[s].group || "tr"),
          name: (subs[s].name || "Altyazı") + " [anizium]"
        });
      }
      var groups = source.groups || [];
      for (var g = 0; g < groups.length; g++) {
        var group = groups[g] || {};
        var label = group.name ? "Anizium (" + group.name + ")" : "Anizium";
        var items = group.items || [];
        for (var i = 0; i < items.length; i++) {
          var item = items[i] || {};
          var url = String(item.link || "");
          if (!/^https?:/.test(url)) continue;
          var quality = parseInt(item.quality, 10) || 0;
          out.push({
            name: "Anizium",
            title: label + " - " + (quality ? quality + "p" : "Kaynak") + (url.indexOf(".m3u8") !== -1 ? " [HLS]" : " [MP4]"),
            url: url,
            quality: quality ? quality + "p" : "auto",
            headers: { "User-Agent": UA, "Referer": "https://x.anizium.co/" },
            subtitles: subtitles.slice(0, 4)
          });
        }
      }
      return out;
    })
    .catch(function (e) {
      console.log("[bb-nuvio][anizium] " + (e && e.message));
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

/* Dil etiketi: akis basliginin kalitesinin yanina sunucunun destekledigi dili yazar.
   Kanit yoksa etiket yazilmaz (uydurma yasak): baslikta dublaj/altyazi varsa dokunulmaz,
   altyazi dosyasi eklenmis akisa "Altyazili" eklenir. */
var __langLabeled = module.exports.getStreams;
module.exports.getStreams = function (id, mediaType, season, episode) {
  return __langLabeled.apply(this, arguments).then(function (streams) {
    return (streams || []).map(function (s) {
      if (!s || !s.title || /dublaj|altyaz/i.test(String(s.title))) return s;
      if (s.subtitles && s.subtitles.length) s.title = String(s.title) + " \u2022 Altyaz\u0131l\u0131";
      return s;
    });
  });
};
