// @provider {"id":"yerlidiziler", "name":"YerliDiziler", "types":["tv"], "domain":"yerlidiziler.com.tr", "formats":["m3u8"], "sample":"tt38647499:tv:2:7", "description":"YerliDiziler yerli dizi arşivi (tüm ulusal kanallar); Vidmoly HLS akışları.", "version":"1.0.1"}
/**
 * yerlidiziler — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta (+ videos cumulative episode map) / IMDb / TVMaze / Wikidata TR
 *       -> /wp-json/wp/v2/categories -> /wp-json/wp/v2/posts -> Vidmoly embed -> HLS.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
var BASE = "https://yerlidiziler.com.tr";

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
  return fetch(url, { method: "GET", headers: headers }).then(function (r) {
    if (!r.ok) throw new Error("HTTP " + r.status + " " + url);
    return r.text();
  });
}

function getJson(url) {
  return text(url, { referer: BASE + "/" }).then(function (b) { return JSON.parse(b); });
}

function fold(value) {
  return String(value || "")
    .replace(/[\u0131\u0130I]/g, "i").replace(/[\u015f\u015e]/g, "s").replace(/[\u011f\u011e]/g, "g")
    .replace(/[\u00fc\u00dc]/g, "u").replace(/[\u00f6\u00d6]/g, "o").replace(/[\u00e7\u00c7]/g, "c")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
}

function sameTitle(have, want) {
  var h = fold(have);
  var w = fold(want);
  return !!w && (h === w || h.indexOf(w + " ") === 0);
}

function hlsNoDownload(url) {
  var u = String(url || "");
  if (/\.m3u8(\?|$)/i.test(u) || u.indexOf("#") !== -1) return u;
  return u + "#.m3u8";
}

function metaOf(id, mediaType, want, wantEp) {
  if (/^__alt__:/.test(String(id))) {
    var parts = String(id).slice(8).split("|");
    return Promise.resolve({
      title: decodeURIComponent(parts[0] || ""),
      absEp: parts[1] ? parseInt(parts[1], 10) : (want === 1 ? wantEp : 0)
    });
  }
  var kind = mediaType === "tv" ? "series" : "movie";
  return text("https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json")
    .then(function (body) {
      var meta = JSON.parse(body).meta || {};
      var videos = (meta.videos || []).filter(function (v) { return v && Number(v.season) > 0; });
      videos.sort(function (a, b) {
        return (Number(a.season) - Number(b.season)) || (Number(a.episode || a.number) - Number(b.episode || b.number));
      });
      var absEp = want === 1 ? wantEp : 0;
      for (var i = 0; i < videos.length; i++) {
        var v = videos[i];
        if (Number(v.season) === want && Number(v.episode || v.number) === wantEp) {
          absEp = i + 1;
          break;
        }
      }
      return { title: String(meta.name || "").trim(), absEp: absEp };
    })
    .catch(function () {
      return { title: "", absEp: want === 1 ? wantEp : 0 };
    });
}

function extractVidmoly(embedUrl, pageUrl) {
  return text(embedUrl, { referer: pageUrl }).then(function (html) {
    var block = /sources\s*:\s*\[([\s\S]*?)\]/i.exec(html);
    var hay = block ? block[1] : html;
    var m = /file\s*:\s*["'](https?:[^"']+)["'](?:\s*,\s*label\s*:\s*["']([^"']*)["'])?/.exec(hay);
    if (!m) return [];
    var stream = m[1].replace(/\\\//g, "/");
    var isHls = /\.m3u8/i.test(stream);
    var q = /(\d{3,4})/.exec(String(m[2] || ""));
    return [
      {
        name: "YerliDiziler",
        title: "YerliDiziler - Vidmoly" + (isHls ? " [HLS]" : " [MP4]"),
        url: isHls ? hlsNoDownload(stream) : stream,
        quality: q ? q[1] + "p" : "1080p",
        headers: { Referer: embedUrl },
        subtitles: []
      }
    ];
  }).catch(function () { return []; });
}

function getStreams(tmdbId, mediaType, season, episode) {
  if (mediaType === "movie") return Promise.resolve([]);
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var absEp = want === 1 ? wantEp : 0;
  var wanted = "";

  return metaOf(tmdbId, mediaType, want, wantEp)
    .then(function (info) {
      if (!info.title) return null;
      wanted = info.title;
      absEp = info.absEp || absEp;
      return getJson(BASE + "/wp-json/wp/v2/categories?per_page=100&_fields=id,count,name,slug");
    })
    .then(function (cats) {
      if (!Array.isArray(cats)) return null;
      var best = null;
      for (var i = 0; i < cats.length; i++) {
        var c = cats[i];
        if (!c || !c.count || c.slug === "uncategorized") continue;
        if (sameTitle(c.name, wanted) || sameTitle(String(c.slug || "").replace(/-/g, " "), wanted)) {
          best = c;
          break;
        }
      }
      if (!best) return null;
      return getJson(BASE + "/wp-json/wp/v2/posts?categories=" + best.id + "&per_page=50&_fields=link,title");
    })
    .then(function (posts) {
      if (!Array.isArray(posts)) return null;
      var targets = [];
      if (absEp > 0) targets.push(absEp);
      if (want === 1 && targets.indexOf(wantEp) === -1) targets.push(wantEp);
      for (var t = 0; t < targets.length; t++) {
        for (var i = 0; i < posts.length; i++) {
          var p = posts[i] || {};
          var title = String((p.title && p.title.rendered) || "");
          var link = String(p.link || "");
          var m = /(\d+)\.\s*B[oö]l[uü]m/i.exec(title) || /-(\d+)-bolum/i.exec(link);
          if (m && parseInt(m[1], 10) === targets[t]) return link;
        }
      }
      return null;
    })
    .then(function (epUrl) {
      if (!epUrl) return [];
      return text(epUrl, { referer: BASE + "/" }).then(function (html) {
        var embeds = [];
        var seen = {};
        var re = /<iframe[^>]+(?:src|data-src)="([^"]+)"/gi;
        var m;
        while ((m = re.exec(html)) !== null) {
          var u = m[1].indexOf("//") === 0 ? "https:" + m[1] : m[1];
          if (/^https?:/i.test(u) && !seen[u]) { seen[u] = 1; embeds.push(u); }
        }
        var jobs = [];
        for (var i = 0; i < embeds.length; i++) {
          if (/vidmoly\./i.test(embeds[i])) jobs.push(extractVidmoly(embeds[i], epUrl));
        }
        return Promise.all(jobs).then(function (lists) {
          var out = [];
          for (var j = 0; j < lists.length; j++) out = out.concat(lists[j]);
          return out;
        });
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][yerlidiziler] " + (e && e.message));
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
  return fromTmdb().then(function (imdb) { return imdb || fromWikidata(); }).then(function (imdb) {
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
    var want = parseInt(season, 10) || 1;
    var wantEp = parseInt(episode, 10) || 1;
    return Promise.resolve(__cinemetaGetStreams(raw, mediaType, season, episode)).then(function (streams) {
      if (streams && streams.length) return streams;
      return metaOf(raw, mediaType, want, wantEp).then(function (info) {
        return __altTitles(raw, mediaType).then(function (titles) {
          var chain = Promise.resolve([]);
          titles.forEach(function (t) {
            chain = chain.then(function (out) {
              return out.length ? out : __cinemetaGetStreams("__alt__:" + encodeURIComponent(t) + "|" + (info.absEp || 0), mediaType, season, episode);
            });
          });
          return chain;
        });
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
