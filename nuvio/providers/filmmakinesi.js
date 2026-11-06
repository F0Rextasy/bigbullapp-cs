// @provider {"id":"filmmakinesi", "name":"FilmMakinesi", "types":["movie", "tv"], "domain":"filmmakinesi.co", "formats":["m3u8"], "description":"FilmMakinesi film kaynaklari (oynatloload secure-hls).", "sample":"tt13611778:movie:1:1", "verified":true}
/**
 * filmmakinesi — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /?s= -> film sayfasi -> oynatloload embed -> __ULTRA_EAUTH ->
 *       /api/video-bilgi -> secure-hls m3u8 (Referer+Origin) + VTT altyazilar.
 * Site yalniz film yayinlar; dizi turunde eslesme yoksa [] doner.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://filmmakinesi.co";
var PLAYER = "https://oynatloload.top";

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

function norm(s) {
  return String(s || "").toLowerCase().replace(/[^a-z0-9]+/g, "");
}

// Cinemeta istege bagli turu bos doner (film/dizi ayrimi 307 -> {}); diger turu dene.
function titleOf(id, mediaType) {
  var kinds = mediaType === "movie" ? ["movie", "series"] : ["series", "movie"];
  function step(i) {
    if (i >= kinds.length) return Promise.resolve("");
    return text("https://v3-cinemeta.strem.io/meta/" + kinds[i] + "/" + id + ".json")
      .then(function (body) {
        var meta = JSON.parse(body).meta || {};
        var name = String(meta.name || "").trim();
        return name ? name : step(i + 1);
      })
      .catch(function () {
        return step(i + 1);
      });
  }
  return step(0);
}

function posters(page) {
  var out = [];
  var re = /<a href="([^"]+)" title="([^"]*)" class="poster"/g;
  var m;
  while ((m = re.exec(String(page || ""))) !== null) out.push({ url: abs(BASE, m[1]), name: m[2] });
  return out;
}

function embedOf(page, filmUrl) {
  var block = String(page || "").split("video-player-container-here")[1] || "";
  var src = first(/<iframe[^>]+src="([^"]+)"/, block);
  if (!src) src = first(/<iframe[^>]+src="([^"]*oynatloload[^"]*)"/, page);
  return src ? abs(filmUrl, src) : null;
}

function langOf(label) {
  var s = String(label || "").toLowerCase();
  if (/t[üu]rk|^tr$/.test(s)) return "tr";
  if (/eng|^en$/.test(s)) return "en";
  return "";
}

function row(url, quality, subs, label) {
  return {
    name: "FilmMakinesi",
    title: "FilmMakinesi - " + quality + " [HLS]" + label,
    url: url,
    quality: quality,
    headers: { Referer: PLAYER + "/", Origin: PLAYER },
    subtitles: subs
  };
}

// audio_tracks "tr" ise dublaj, altyazi dosyasi varsa "Altyazili"; ikisi de kanitli.
function rows(oj) {
  var tracks = oj.subtitles || oj.subtitle_tracks || oj.tracks || [];
  var subs = [];
  for (var i = 0; i < tracks.length; i++) {
    var file = String((tracks[i] || {}).file || "");
    if (!/\.(vtt|srt)(\?|$)/i.test(file)) continue;
    subs.push({
      url: abs(PLAYER + "/", file),
      language: langOf(tracks[i].label),
      name: String(tracks[i].label || "Altyazi") + " [filmmakinesi]"
    });
  }
  var audio = oj.audio_tracks || [];
  var dubbing = false;
  for (var a = 0; a < audio.length; a++) {
    if (langOf(audio[a].lang_code || audio[a].label) === "tr") dubbing = true;
  }
  var label = (dubbing ? " \u2022 TR Dublaj" : "") + (subs.length ? " \u2022 Altyaz\u0131l\u0131" : "");

  var out = [];
  var seen = {};
  var sources = oj.sources || [];
  for (var s = 0; s < sources.length; s++) {
    var url = String((sources[s] || {}).file || "");
    if (!/^https?:/i.test(url) || seen[url]) continue;
    seen[url] = 1;
    out.push(row(url, String(sources[s].label || "auto"), subs, label));
  }
  if (!out.length) {
    var master = String(oj.src || oj.hls_m3u8_path || "");
    if (/^https?:/i.test(master)) out.push(row(master, "auto", subs, label));
  }
  return out;
}

function resolve(filmUrl) {
  var embedUrl = null;
  return text(filmUrl)
    .then(function (page) {
      embedUrl = embedOf(page, filmUrl);
      if (!embedUrl || embedUrl.indexOf("oynatloload.") === -1) return null;
      return text(embedUrl, { referer: filmUrl });
    })
    .then(function (embed) {
      if (!embed) return [];
      var token = first(/__ULTRA_EAUTH\s*=\s*"([^"]+)"/, embed);
      if (!token) return [];
      var id = embedUrl.replace(/[?#].*$/, "").split("/").pop();
      return text(PLAYER + "/api/video-bilgi/" + id + "?eauth=" + encodeURIComponent(token), {
        referer: embedUrl,
        headers: { Origin: PLAYER, Accept: "application/json" }
      });
    })
    .then(function (body) {
      if (!body) return [];
      return rows(JSON.parse(body));
    });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var title = "";
  return titleOf(tmdbId, mediaType)
    .then(function (t) {
      title = t;
      if (!title) return null;
      return text(BASE + "/?s=" + encodeURIComponent(title));
    })
    .then(function (page) {
      if (!page) return [];
      var list = posters(page);
      if (!list.length) return [];
      var want = norm(title);
      var hit = null;
      var i;
      for (i = 0; i < list.length; i++) {
        if (norm(list[i].name) === want) {
          hit = list[i];
          break;
        }
      }
      for (i = 0; !hit && i < list.length; i++) {
        var n = norm(list[i].name);
        if (n && (n.indexOf(want) === 0 || want.indexOf(n) === 0)) hit = list[i];
      }
      return resolve((hit || list[0]).url);
    })
    .catch(function (e) {
      console.log("[bb-nuvio][filmmakinesi] " + (e && e.message));
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

/* Dil etiketi: akis basligina sunucunun destekledigi dili yazar; kanit yoksa etiket yok. */
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
