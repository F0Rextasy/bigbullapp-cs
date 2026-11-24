// @provider {"id":"tv8","name":"TV8","types":["tv"],"domain":"tv8.com.tr","formats":["m3u8"],"sample":"tt12687036:tv:1:1","description":"TV8 resmi dizi, program ve yarışma arşivi; video-cdn HLS akışları.","verified":true,"version":"1.0.0"}
/**
 * tv8 — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta/alt title -> /programlar + /diziler + /yarismalar -> /<slug>/tum-bolumler -> -video.htm -> hls m3u8.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://www.tv8.com.tr";
var STATIC = { programlar: 1, "canli-yayin": 1, "yayin-akisi": 1 };

function text(url, referer) {
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (referer) headers.Referer = referer;
  return fetch(url, { method: "GET", headers: headers })
    .then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status + " " + url);
      return r.text();
    });
}

function first(re, body) {
  var m = re.exec(String(body || ""));
  return m ? m[1] : null;
}

function fold(v) {
  return String(v || "")
    .replace(/[\u0131\u0130I]/g, "i").replace(/[\u015f\u015e]/g, "s").replace(/[\u011f\u011e]/g, "g")
    .replace(/[\u00e7\u00c7]/g, "c").replace(/[\u00f6\u00d6]/g, "o").replace(/[\u00fc\u00dc]/g, "u")
    .toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
}

function sameTitle(have, want) {
  var h = fold(have), w = fold(want);
  return !!w && (h === w || h.indexOf(w + " ") === 0);
}

function titleOf(id, mediaType) {
  if (/^__alt__:/.test(String(id))) return Promise.resolve(decodeURIComponent(String(id).slice(8)));
  var kind = mediaType === "tv" ? "series" : "movie";
  return text("https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json")
    .then(function (body) { return String(((JSON.parse(body) || {}).meta || {}).name || "").trim(); })
    .catch(function () { return ""; });
}

function abs(base, href) {
  if (!href) return null;
  if (/^https?:/i.test(href)) return href;
  var origin = /^(https?:\/\/[^/]+)/.exec(base);
  if (href.charAt(0) === "/") return origin ? origin[1] + href : href;
  return base.replace(/[^/]*$/, "") + href;
}

function parseVariants(master, playlist) {
  if (/TYPE=AUDIO|AUDIO=/i.test(String(playlist || ""))) return [];
  var lines = String(playlist || "").split(/\r?\n/), out = [], seen = {}, q = "";
  for (var i = 0; i < lines.length; i++) {
    var line = lines[i].trim();
    if (!line) continue;
    if (line.indexOf("#EXT-X-STREAM-INF") === 0) {
      var m = /RESOLUTION=\d+x(\d+)/.exec(line);
      q = m ? m[1] + "p" : "auto";
    } else if (line.charAt(0) !== "#" && q) {
      var u = abs(master, line);
      if (u && !seen[u]) { seen[u] = 1; out.push({ quality: q, url: u }); }
      q = "";
    }
  }
  return out;
}

function findShowUrl(pages, wantTitle) {
  var artRe = /<article[^>]*class="[^"]*now-(?:program-)?thumb[^"]*"[\s\S]*?<a[^>]*href="([^"]+)"[^>]*>([\s\S]*?)<\/article>/gi;
  var m;
  for (var i = 0; i < pages.length; i++) {
    var html = pages[i] || "";
    artRe.lastIndex = 0;
    while ((m = artRe.exec(html)) !== null) {
      var u = abs(BASE + "/", m[1]);
      if (!u || u.indexOf(BASE) !== 0) continue;
      var path = u.replace(BASE, "").split("?")[0].replace(/^\/+|\/+$/g, "");
      if (!path || path.indexOf("/") !== -1 || STATIC[path]) continue;
      var t = first(/alt="([^"]+)"/, m[2]) || first(/class="now-thumb-prog"[^>]*>([^<]+)</, m[2]) || path.replace(/-/g, " ");
      if (sameTitle(t, wantTitle) || sameTitle(path.replace(/-/g, " "), wantTitle)) return u;
    }
  }
  return "";
}

function pickEpisodeInPage(html, slug, wantEp) {
  var re = /<article[^>]*class="now-thumb"[\s\S]*?<\/article>/gi, m;
  while ((m = re.exec(html)) !== null) {
    var href = first(/href="([^"]*-video\.htm)"/, m[0]);
    if (!href || href.indexOf("/" + slug + "/") === -1) continue;
    if (/tanitim|fragman/i.test(href)) continue;
    var num = parseInt(first(/(\d+)-bolum/i, href), 10);
    if (num === wantEp) return abs(BASE + "/", href);
  }
  return "";
}

function getStreams(id, mediaType, season, episode) {
  if (mediaType === "movie") return Promise.resolve([]);
  var wantEp = parseInt(episode, 10) || 1;
  var slug = "", epUrl = "";

  return titleOf(id, mediaType)
    .then(function (title) {
      if (!title) return "";
      return Promise.all([
        text(BASE + "/diziler").catch(function () { return ""; }),
        text(BASE + "/programlar").catch(function () { return ""; }),
        text(BASE + "/yarismalar").catch(function () { return ""; })
      ]).then(function (pages) { return findShowUrl(pages, title); });
    })
    .then(function (showUrl) {
      if (!showUrl) return "";
      slug = showUrl.replace(BASE, "").replace(/^\/+|\/+$/g, "");
      var listUrl = showUrl + "/tum-bolumler";
      return text(listUrl, BASE + "/").catch(function () { return text(showUrl, BASE + "/"); }).then(function (html) {
        var hit = pickEpisodeInPage(html, slug, wantEp);
        if (hit) return hit;
        var nums = [], re = /\?page=(\d+)/g, m;
        while ((m = re.exec(html)) !== null) nums.push(parseInt(m[1], 10));
        var lastPage = nums.length ? Math.max.apply(null, nums) : 1;
        if (lastPage <= 1) return "";
        var tries = wantEp <= 24 ? [lastPage, lastPage - 1, 2] : [2, 3, lastPage];
        var chain = Promise.resolve("");
        tries.forEach(function (p) {
          if (p <= 1 || p > lastPage) return;
          chain = chain.then(function (res) {
            return res || text(listUrl + "?page=" + p, listUrl).then(function (ph) { return pickEpisodeInPage(ph, slug, wantEp); }).catch(function () { return ""; });
          });
        });
        return chain;
      });
    })
    .then(function (foundEp) {
      if (!foundEp) return "";
      epUrl = foundEp;
      return text(epUrl, BASE + "/").catch(function () { return ""; });
    })
    .then(function (epHtml) {
      if (!epHtml) return [];
      var raw = first(/hls:\s*"([^"]+)"/, epHtml) || first(/(https?:\/\/[^"'\\ ]+\.m3u8[^"'\\ ]*)/, epHtml);
      if (!raw) return [];
      var master = raw.replace(/\\\//g, "/").replace(/&amp;/g, "&");
      if (!/^https?:/i.test(master)) return [];
      var headers = { "User-Agent": UA, Referer: epUrl };
      return text(master, epUrl).catch(function () { return ""; }).then(function (pl) {
        var vars = parseVariants(master, pl);
        if (!vars.length) {
          return [{ name: "TV8", title: "TV8 - HLS", url: master, quality: "auto", headers: headers, subtitles: [] }];
        }
        vars.sort(function (a, b) { return (parseInt(b.quality, 10) || 0) - (parseInt(a.quality, 10) || 0); });
        return vars.map(function (v) {
          return { name: "TV8", title: "TV8 - " + v.quality + " [HLS]", url: v.url, quality: v.quality, headers: headers, subtitles: [] };
        });
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][tv8] " + (e && e.message));
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
