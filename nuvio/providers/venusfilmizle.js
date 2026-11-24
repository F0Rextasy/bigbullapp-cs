// @provider {"id":"venusfilmizle","name":"VenusFilmizle","types":["movie"],"domain":"venusfilmizle.com","formats":["m3u8"],"sample":"tt0049513:movie:1:1","description":"VenusFilmizle - Turkce dublaj & altyazili film arsivi (hd.player.rest + vidmoly HLS).","verified":true,"version":"1.0.0"}
/**
 * venusfilmizle — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /ara?q= JSON -> /izle/<slug> -> window.__lythvm ->
 *       /go?m=<slug>&s=<lang>&i=<idx> -> hd.player.rest/hls?v=<tok> or vidmoly HLS.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://venusfilmizle.com";

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
  if (o.headers) for (var k in o.headers) headers[k] = o.headers[k];
  return fetch(url, { method: o.method || "GET", headers: headers, body: o.body }).then(function (r) {
    if (!r.ok) throw new Error("HTTP " + r.status + " " + url);
    return r.text();
  });
}

function first(pattern, body) {
  var m = pattern.exec(String(body || ""));
  return m ? m[1] : null;
}

function origin(url) {
  var m = /^(https?:\/\/[^/]+)/.exec(String(url || ""));
  return m ? m[1] : "";
}

function abs(base, href) {
  var u = String(href || "").trim();
  if (!u) return "";
  if (/^https?:\/\//i.test(u)) return u;
  if (u.indexOf("//") === 0) return "https:" + u;
  var root = origin(base || BASE) || BASE;
  if (u.charAt(0) === "/") return root + u;
  return root + "/" + u;
}

function hlsNoDownload(url) {
  var u = String(url || "").trim();
  if (!u) return u;
  if (/\.m3u8(\?|$)/i.test(u) || u.indexOf("#") !== -1) return u;
  return u + "#.m3u8";
}

function norm(value) {
  return String(value || "")
    .replace(/\([^)]*\)/g, " ")
    .replace(/[\u0131\u0130I]/g, "i").replace(/[\u015f\u015e]/g, "s").replace(/[\u011f\u011e]/g, "g")
    .replace(/[\u00e7\u00c7]/g, "c").replace(/[\u00f6\u00d6]/g, "o").replace(/[\u00fc\u00dc]/g, "u")
    .toLowerCase()
    .replace(/\b(?:izle|film|full|hd)\b/g, " ")
    .replace(/[^a-z0-9]+/g, " ").trim()
    .replace(/^(?:the|a|an)\s+/, "");
}

function titleOf(id, mediaType) {
  var raw = String(id || "").trim();
  if (/^__alt__:/.test(raw)) return Promise.resolve({ name: decodeURIComponent(raw.slice(8)), year: "" });
  var kind = mediaType === "tv" ? "series" : "movie";
  return text("https://v3-cinemeta.strem.io/meta/" + kind + "/" + encodeURIComponent(raw) + ".json")
    .then(function (body) {
      var meta = JSON.parse(body).meta || {};
      return { name: String(meta.name || "").trim(), year: String(meta.year || meta.releaseInfo || "").slice(0, 4) };
    })
    .catch(function () { return { name: "", year: "" }; });
}

function pickResult(items, meta) {
  var want = norm(meta.name);
  if (!want) return null;
  var year = String(meta.year || "");
  var prefix = null;
  for (var i = 0; i < (items || []).length; i++) {
    var it = items[i];
    if (!it || !it.u) continue;
    var iy = it.y ? String(it.y) : "";
    if (year && iy && Math.abs(Number(year) - Number(iy)) > 1) continue;
    var t1 = norm(it.t), t2 = norm(it.o);
    if (t1 === want || t2 === want) return it;
    if (!prefix && (t1.indexOf(want + " ") === 0 || t2.indexOf(want + " ") === 0)) prefix = it;
  }
  return prefix;
}

function qualityOf(url, referer) {
  return text(url, { referer: referer })
    .then(function (m3u8) {
      var best = 0, re = /RESOLUTION=\d+x(\d+)/g, m;
      while ((m = re.exec(m3u8)) !== null) {
        var h = Number(m[1]);
        if (h > best) best = h;
      }
      if (best >= 2160) return "4K";
      if (best >= 1080) return "1080p";
      if (best >= 720) return "720p";
      if (best >= 480) return "480p";
      return best > 0 ? best + "p" : "auto";
    })
    .catch(function () { return "auto"; });
}

function resolveTarget(url, label, playUrl) {
  if (/youtube\.com|youtu\.be/i.test(url)) return Promise.resolve([]);
  return fetch(url, { headers: { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9", Referer: playUrl } })
    .then(function (r) {
      var finalUrl = r.url || url;
      return r.text().then(function (html) { return { finalUrl: finalUrl, html: html }; });
    })
    .then(function (res) {
      var fUrl = res.finalUrl;
      var html = res.html;
      if (fUrl.indexOf("hd.player.rest/") !== -1 || html.indexOf("hd.player.rest") !== -1) {
        var tok = first(/[?&]v=([^&"'\s]+)/, fUrl) || first(/\/hls\?v=([^&"'\s]+)/, html) || first(/[?&]v=([^&"'\s]+)/, html);
        if (tok) {
          var master = "https://hd.player.rest/hls?v=" + tok;
          var ref = fUrl.indexOf("hd.player.rest") !== -1 ? fUrl : "https://hd.player.rest/oynat?v=" + tok;
          return qualityOf(master, ref).then(function (q) {
            return [{
              name: "VenusFilmizle",
              title: "VenusFilmizle - " + label + " [HLS]",
              url: hlsNoDownload(master),
              quality: q,
              headers: { Referer: ref, "User-Agent": UA },
              subtitles: []
            }];
          });
        }
      }
      var stream = first(/file\s*:\s*['"](https?:\/\/[^'"]+\.m3u8[^'"]*)['"]/i, html);
      if (!stream) return [];
      return qualityOf(stream, fUrl).then(function (q) {
        return [{
          name: "VenusFilmizle",
          title: "VenusFilmizle - " + label + " VidMoly [HLS]",
          url: hlsNoDownload(stream),
          quality: q,
          headers: { Referer: fUrl, "User-Agent": UA },
          subtitles: []
        }];
      });
    })
    .catch(function () { return []; });
}

function getStreams(tmdbId, mediaType, season, episode) {
  if (mediaType === "tv") return Promise.resolve([]);
  var slug = "", playUrl = "";

  return titleOf(tmdbId, mediaType)
    .then(function (meta) {
      if (!meta.name) return null;
      return text(BASE + "/ara?q=" + encodeURIComponent(meta.name), {
        referer: BASE + "/",
        headers: { Accept: "application/json", "X-Requested-With": "XMLHttpRequest" }
      }).then(function (body) {
        var hit = pickResult(JSON.parse(body), meta);
        return hit ? abs(BASE, hit.u) : null;
      });
    })
    .then(function (filmUrl) {
      if (!filmUrl) return null;
      slug = filmUrl.split("/film/").pop().split("?")[0].replace(/^\/+|\/+$/g, "");
      if (!slug) return null;
      playUrl = BASE + "/izle/" + slug;
      return text(playUrl, { referer: filmUrl });
    })
    .then(function (html) {
      if (!html) return [];
      var raw = first(/window\.__lythvm=(\{[\s\S]*?\});\s*<\/script>/, html);
      if (!raw) return [];
      var sources = JSON.parse(raw);
      var langs = [["dublaj", "T\u00fcrk\u00e7e Dublaj"], ["altyazi", "T\u00fcrk\u00e7e Altyaz\u0131"]];
      var jobs = [];
      langs.forEach(function (pair) {
        var entries = sources[pair[0]] || [];
        for (var i = 0; i < entries.length; i++) {
          var ent = entries[i] || {};
          var target = /^https?:/i.test(String(ent.u || "")) ? ent.u : (BASE + "/go?m=" + encodeURIComponent(slug) + "&s=" + pair[0] + "&i=" + i);
          jobs.push(resolveTarget(target, pair[1] + " " + (i + 1), playUrl));
          if (jobs.length >= 6) break;
        }
      });
      return Promise.all(jobs).then(function (lists) {
        var out = [], seenUrl = {};
        for (var i = 0; i < lists.length; i++) {
          for (var j = 0; j < (lists[i] || []).length; j++) {
            var it = lists[i][j];
            if (it && it.url && !seenUrl[it.url]) { seenUrl[it.url] = 1; out.push(it); }
          }
        }
        return out;
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][venusfilmizle] " + (e && e.message));
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
