// @provider {"id": "filmmakinesi", "name": "FilmMakinesi", "types": ["movie", "tv"], "domain": "filmmakinesi.co", "formats": ["m3u8"], "description": "FilmMakinesi film kaynaklari (oynatloload secure-hls, vidmoly).", "sample": "tt13611778:movie:1:1", "verified": true, "version":"1.0.10"}
/**
 * filmmakinesi — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /?s= -> film sayfasi -> oynatloload embed -> __ULTRA_EAUTH ->
 *       /api/video-bilgi -> secure-hls m3u8 (Referer+Origin) + VTT altyazilar.
 * Site yalniz film yayinlar; dizi turunde eslesme yoksa [] doner.
 */

var UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36";
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
  return String(s || "")
    .replace(/[\u0131\u0130I]/g, "i").replace(/[\u015f\u015e]/g, "s").replace(/[\u011f\u011e]/g, "g")
    .replace(/[\u00fc\u00dc]/g, "u").replace(/[\u00f6\u00d6]/g, "o").replace(/[\u00e7\u00c7]/g, "c")
    .toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
}

// Cinemeta istege bagli turu bos doner (film/dizi ayrimi 307 -> {}); diger turu dene.
function titleOf(id, mediaType) {
  if (/^__alt__:/.test(String(id))) return Promise.resolve(decodeURIComponent(String(id).slice(8)));
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

function embedsOf(page, filmUrl) {
  var body = String(page || "");
  var block = body.indexOf("video-player-container-here") !== -1 ? body.split("video-player-container-here")[1] : body;
  var re = /<iframe[^>]+src="([^"]+)"/gi;
  var out = [];
  var seen = {};
  var m;
  while ((m = re.exec(block)) !== null) {
    var src = abs(filmUrl, m[1]);
    if (!src || seen[src] || /youtu/i.test(src)) continue;
    seen[src] = 1;
    out.push(src);
  }
  return out;
}

function altLinksOf(page, filmUrl) {
  var re = /<a[^>]+href="([^"]+)"[^>]*class="[^"]*alternative-link|<a[^>]+class="[^"]*alternative-link[^"]*"[^>]*href="([^"]+)"/gi;
  var out = [];
  var seen = {};
  var m;
  while ((m = re.exec(String(page || ""))) !== null) {
    var u = abs(filmUrl, m[1] || m[2]);
    if (!u || u.replace(/\/+$/, "") === filmUrl.replace(/\/+$/, "") || seen[u]) continue;
    seen[u] = 1;
    out.push(u);
    if (out.length >= 3) break;
  }
  return out;
}

function vidmolyRows(embedUrl, referer) {
  var normUrl = String(embedUrl).replace(/\/\/(?:www\.)?vidmoly\.(?:to|biz|me|sx)\//i, "//vidmoly.biz/");
  return text(normUrl, { referer: referer, headers: { "Sec-Fetch-Dest": "iframe" } })
    .then(function (body) {
      var m3u8 = first(/file\s*:\s*["'](https?:\/\/[^"']+\.m3u8[^"']*)["']/, body);
      if (!m3u8) return [];
      var subs = [];
      var seenSub = {};
      var re = /file\s*:\s*["'](https?:\/\/[^"']+\.(?:vtt|srt)[^"']*)["']/gi;
      var m;
      while ((m = re.exec(body)) !== null) {
        if (seenSub[m[1]] || /slides|thumb/i.test(m[1])) continue;
        seenSub[m[1]] = 1;
        subs.push({ url: m[1], language: "tr", name: "Altyazi [filmmakinesi]" });
      }
      return [{
        name: "FilmMakinesi",
        title: "FilmMakinesi - VidMoly [HLS]" + (subs.length ? " \u2022 Altyaz\u0131l\u0131" : ""),
        url: hlsNoDownload(m3u8),
        quality: "auto",
        headers: { "User-Agent": UA, Referer: "https://vidmoly.biz/" },
        subtitles: subs
      }];
    })
    .catch(function () { return []; });
}

function resolveEmbed(embedUrl, referer) {
  if (/oynatloload\./i.test(embedUrl)) {
    return text(embedUrl, { referer: referer })
      .then(function (embed) {
        var token = first(/__ULTRA_EAUTH\s*=\s*"([^"]+)"/, embed);
        if (!token) return [];
        var id = embedUrl.replace(/[?#].*$/, "").split("/").pop();
        return text(PLAYER + "/api/video-bilgi/" + id + "?eauth=" + encodeURIComponent(token), {
          referer: embedUrl,
          headers: { Origin: PLAYER, Accept: "application/json" }
        }).then(function (body) {
          return body ? rows(JSON.parse(body)) : [];
        });
      })
      .catch(function () { return []; });
  }
  if (/vidmoly\./i.test(embedUrl)) return vidmolyRows(embedUrl, referer);
  return Promise.resolve([]);
}

function resolvePage(page, pageUrl) {
  var embeds = embedsOf(page, pageUrl);
  var chain = Promise.resolve([]);
  embeds.forEach(function (em) {
    chain = chain.then(function (acc) {
      if (acc.length) return acc;
      return resolveEmbed(em, pageUrl);
    });
  });
  return chain;
}

function langOf(label) {
  var s = String(label || "").toLowerCase();
  if (/t[üu]rk|^tr$/.test(s)) return "tr";
  if (/eng|^en$/.test(s)) return "en";
  return "";
}

function hlsNoDownload(url) {
  var u = String(url || "");
  if (/\.m3u8(\?|$)/i.test(u) || u.indexOf("#") !== -1) return u;
  return u + "#.m3u8";
}

function row(url, quality, subs, label) {
  return {
    name: "FilmMakinesi",
    title: "FilmMakinesi - " + quality + " [HLS]" + label,
    url: hlsNoDownload(url),
    quality: quality,
    headers: { "User-Agent": UA, Referer: PLAYER + "/", Origin: PLAYER },
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
  var master = String(oj.src || oj.hls_m3u8_path || "");
  var sources = oj.sources || [];
  if (audio.length && /^https?:/i.test(master)) {
    out.push(row(master, "auto", subs, label));
    return out;
  }
  for (var s = 0; s < sources.length; s++) {
    var url = String((sources[s] || {}).file || "");
    if (!/^https?:/i.test(url) || seen[url]) continue;
    seen[url] = 1;
    out.push(row(url, String(sources[s].label || "auto"), subs, label));
  }
  if (!out.length && /^https?:/i.test(master)) {
    out.push(row(master, "auto", subs, label));
  }
  return out;
}

function resolve(filmUrl) {
  return text(filmUrl).then(function (page) {
    return resolvePage(page, filmUrl).then(function (out) {
      if (out.length) return out;
      var alts = altLinksOf(page, filmUrl);
      var chain = Promise.resolve([]);
      alts.forEach(function (alt) {
        chain = chain.then(function (acc) {
          if (acc.length) return acc;
          return text(alt).then(function (ap) { return resolvePage(ap, alt); }).catch(function () { return []; });
        });
      });
      return chain;
    });
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
        if (n && n.indexOf(want + " ") === 0) hit = list[i];
      }
      return hit ? resolve(hit.url) : [];
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
  var mt = mediaType === "series" ? "tv" : mediaType;
  if (!/^\d+$/.test(raw)) {
    try {
      return Promise.resolve(__origGetStreams(raw, mt, season, episode)).catch(function () { return []; });
    } catch (e) {
      return Promise.resolve([]);
    }
  }
  var tv = mt !== "movie";
  var headers = { "User-Agent": "BigBullNuvio/1.0 (https://github.com/F0Rextasy/bigbullapp-cs)", "Accept": "application/json" };
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
  function fromTmdbTitle() {
    if (!key) return Promise.resolve("");
    return fetch("https://api.themoviedb.org/3/" + (tv ? "tv" : "movie") + "/" + raw + "?api_key=" + encodeURIComponent(key) + "&language=tr-TR", { headers: headers })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (json) {
        var t = (json && (json.original_name || json.name || json.original_title || json.title)) || "";
        return t ? "__alt__:" + encodeURIComponent(t) : "";
      })
      .catch(function () { return ""; });
  }
  return fromTmdb().then(function (imdb) { return imdb || fromWikidata(); }).then(function (imdb) { return imdb || fromTmdbTitle(); }).then(function (imdb) {
    try {
      return Promise.resolve(__origGetStreams(imdb || raw, mt, season, episode)).catch(function () { return []; });
    } catch (e) {
      return [];
    }
  }).catch(function () { return []; });
};

/* Cinemeta basligi yerellestirir ("Gibi" -> "As If"); sonuc bossa Wikidata TR/EN basliklariyla yeniden dene. */
function __altTitles(raw, mediaType) {
  var wdHeaders = { "User-Agent": "BigBullNuvio/1.0 (https://github.com/F0Rextasy/bigbullapp-cs)", "Accept": "application/json" };
  var h = { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36", "Accept": "application/json" };
  var api = "https://www.wikidata.org/w/api.php?format=json";
  var stmt = /^tt\d+$/.test(raw) ? "P345=" + raw : (mediaType === "movie" ? "P4947=" : "P4983=") + raw;
  var wd = fetch(api + "&action=query&list=search&srlimit=1&srsearch=" + encodeURIComponent("haswbstatement:" + stmt), { headers: wdHeaders })
    .then(function (r) { return r.ok ? r.json() : null; })
    .then(function (j) {
      var hits = (j && j.query && j.query.search) || [];
      if (!hits.length) return null;
      return fetch(api + "&action=wbgetentities&props=labels|aliases&languages=tr|en&ids=" + hits[0].title, { headers: wdHeaders }).then(function (r) { return r.ok ? r.json() : null; });
    })
    .then(function (j) {
      var res = [], ents = (j && j.entities) || {}, k, l, a, i;
      for (k in ents) {
        l = ents[k].labels || {}; a = ents[k].aliases || {};
        if (l.tr && l.tr.value) res.push(l.tr.value);
        if (l.en && l.en.value) res.push(l.en.value);
        for (i = 0; i < (a.tr || []).length && i < 2; i++) res.push(a.tr[i].value);
      }
      return res;
    }).catch(function () { return []; });
  var imdb = /^tt\d+$/.test(raw)
    ? fetch("https://v3.sg.media-imdb.com/suggestion/t/" + raw + ".json", { headers: h })
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (j) { return (j && j.d && j.d[0] && j.d[0].l) ? [j.d[0].l] : []; })
        .catch(function () { return []; })
    : Promise.resolve([]);
  var tvm = /^tt\d+$/.test(raw) && mediaType !== "movie"
    ? fetch("https://api.tvmaze.com/lookup/shows?imdb=" + raw, { headers: h })
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (j) { return (j && j.name) ? [j.name] : []; })
        .catch(function () { return []; })
    : Promise.resolve([]);
  return Promise.all([wd, imdb, tvm]).then(function (lists) {
    var out = [], seen = {};
    for (var i = 0; i < lists.length; i++) {
      for (var j = 0; j < lists[i].length; j++) {
        var v = String(lists[i][j] || "").replace(/\s*\([^)]*\)\s*$/, "").trim();
        if (v && !seen[v.toLowerCase()]) { seen[v.toLowerCase()] = 1; out.push(v); }
      }
    }
    return out;
  });
}
var __cinemetaGetStreams = module.exports.getStreams;
module.exports.getStreams = function (id, mediaType, season, episode) {
  var raw = String(id || "");
  try {
    if (/^__alt__:/.test(raw)) return Promise.resolve(__cinemetaGetStreams(raw, mediaType, season, episode)).catch(function () { return []; });
    function tryPrimary() {
      if (!/^tt\d+$/.test(raw)) return Promise.resolve(__cinemetaGetStreams(raw, mediaType, season, episode)).catch(function () { return []; });
      var h = { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36", "Accept": "application/json" };
      return fetch("https://v3.sg.media-imdb.com/suggestion/t/" + raw + ".json", { headers: h })
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (j) {
          var t = j && j.d && j.d[0] && j.d[0].l ? String(j.d[0].l).replace(/\s*\([^)]*\)\s*$/, "").trim() : "";
          return t ? __cinemetaGetStreams("__alt__:" + encodeURIComponent(t), mediaType, season, episode) : [];
        })
        .catch(function () { return []; })
        .then(function (streams) {
          return (streams && streams.length) ? streams : __cinemetaGetStreams(raw, mediaType, season, episode);
        });
    }
    return tryPrimary().then(function (streams) {
      if (streams && streams.length) return streams;
      return __altTitles(raw, mediaType).then(function (titles) {
        var chain = Promise.resolve([]);
        titles.forEach(function (t) {
          chain = chain.then(function (out) {
            return out.length ? out : __cinemetaGetStreams("__alt__:" + encodeURIComponent(t), mediaType, season, episode);
          });
        });
        return chain;
      });
    }).catch(function () { return []; });
  } catch (e) {
    return Promise.resolve([]);
  }
};

/* Dil etiketi (AGENTS.md): Nuvio karti "quality \u2022 ..." satirini gosterir; dil etiketi kalitenin yanina yazilir. */
var __subOnly = false;
var __langLabeled = module.exports.getStreams;
module.exports.getStreams = function (id, mediaType, season, episode) {
  try {
    return Promise.resolve(__langLabeled.apply(this, arguments)).then(function (streams) {
      return (streams || []).map(function (s) {
        if (!s) return s;
        var hay = String(s.title || "") + " " + String(s.name || "");
        var tags = [];
        if (/dublaj/i.test(hay)) tags.push("TR Dublaj");
        if (/altyaz/i.test(hay) || (s.subtitles && s.subtitles.length) || (!tags.length && __subOnly)) tags.push("Altyaz\u0131l\u0131");
        if (!tags.length) return s;
        var q = String(s.quality || "").replace(/\s*\u2022.*$/, "") || "auto";
        s.quality = q + " \u2022 " + tags.join(" \u2022 ");
        return s;
      });
    }).catch(function () { return []; });
  } catch (e) {
    return Promise.resolve([]);
  }
};
