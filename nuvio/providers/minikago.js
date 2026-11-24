// @provider {"id":"minikago","name":"MinikaGO","types":["tv","movie"],"domain":"minikago.com.tr","formats":["mp4"],"sample":"tt11372936:tv:1:1","description":"MinikaGO çocuk VOD arşivi; doğrudan MP4 akışları.","verified":true,"version":"1.0.0"}
/**
 * minikago — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta/alt title -> /webtv + /programlar -> /webtv/<slug>/bolum/... -> MP4 links.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://www.minikago.com.tr";
var SKIP = /(tanıtım|tanitim|yakında|yakinda|fragman)/i;

function text(url, referer) {
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9", "X-Requested-With": "XMLHttpRequest" };
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

function decodeEntities(s) {
  return String(s || "")
    .replace(/&#x([0-9a-f]+);/gi, function (_, h) { return String.fromCharCode(parseInt(h, 16)); })
    .replace(/&#(\d+);/g, function (_, d) { return String.fromCharCode(parseInt(d, 10)); })
    .replace(/&ouml;/g, "\u00f6").replace(/&Ouml;/g, "\u00d6").replace(/&uuml;/g, "\u00fc").replace(/&Uuml;/g, "\u00dc")
    .replace(/&ccedil;/g, "\u00e7").replace(/&Ccedil;/g, "\u00c7").replace(/&amp;/g, "&").replace(/&quot;/g, '"');
}

function fold(v) {
  return decodeEntities(v)
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

function findEpisodeUrl(webtvHtml, progHtml, wantTitle, wantSeason, wantEp) {
  var re = /<div class="showcase-item">([\s\S]*?)<\/div>\s*<\/div>/gi, m;
  var fallback = "";
  while ((m = re.exec(webtvHtml)) !== null) {
    var b = m[1];
    var href = first(/href="([^"]*\/bolum\/[^"]+)"/, b);
    var rawTitle = decodeEntities(first(/<a[^>]*class="showcase-text"[^>]*>[\s\S]*?<span>([^<]+)<\/span>/i, b) || "");
    if (!href || !rawTitle || SKIP.test(rawTitle)) continue;
    var showPart = rawTitle.replace(/\s*(?:\||Her\s+G[üu]n\s+Minika).*$/i, "").trim();
    var slug = first(/\/webtv\/([^/]+)\/bolum\//, href) || "";
    if (!sameTitle(showPart, wantTitle) && !sameTitle(slug.replace(/-/g, " "), wantTitle)) continue;
    var se = /(\d+)\.\s*Sezon.*?(\d+)\.\s*B[oö]l[uü]m/i.exec(rawTitle);
    var bNum = parseInt(first(/(\d+)\.\s*B[oö]l[uü]m/i, rawTitle), 10);
    if ((se && parseInt(se[1], 10) === wantSeason && parseInt(se[2], 10) === wantEp) || (!se && bNum === wantEp)) {
      return Promise.resolve(abs(BASE + "/", href));
    }
    if (!fallback) fallback = abs(BASE + "/", href);
  }
  if (fallback && wantSeason === 1 && wantEp === 1) return Promise.resolve(fallback);

  var pRe = /<a[^>]*href="(\/programlar\/[^"]+)"[^>]*>[\s\S]{0,300}?<span>([^<]+)<\/span>/gi;
  while ((m = pRe.exec(progHtml)) !== null) {
    if (sameTitle(m[2], wantTitle) || sameTitle(m[1].split("/").pop().replace(/-/g, " "), wantTitle)) {
      return text(BASE + m[1], BASE + "/").then(function (pHtml) {
        var epRe = /href="([^"]*\/bolum\/[^"]+)"/gi, em, firstEp = "";
        while ((em = epRe.exec(pHtml)) !== null) {
          if (!firstEp && !SKIP.test(em[1])) firstEp = abs(BASE + "/", em[1]);
        }
        return wantSeason === 1 && wantEp === 1 ? firstEp : "";
      }).catch(function () { return ""; });
    }
  }
  return Promise.resolve("");
}

function getStreams(id, mediaType, season, episode) {
  var wantSeason = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;

  return titleOf(id, mediaType)
    .then(function (title) {
      if (!title) return "";
      return Promise.all([
        text(BASE + "/webtv").catch(function () { return ""; }),
        text(BASE + "/programlar").catch(function () { return ""; })
      ]).then(function (pages) {
        return findEpisodeUrl(pages[0], pages[1], title, wantSeason, wantEp);
      });
    })
    .then(function (epUrl) {
      if (!epUrl) return [];
      return text(epUrl, BASE + "/").then(function (raw) {
        var html = raw.replace(/\\\//g, "/");
        var re = /https?:\/\/[^\s"'<>\\]+?\.mp4[^\s"'<>\\]*/g, m, out = [], seen = {};
        while ((m = re.exec(html)) !== null) {
          var u = m[0];
          if (seen[u]) continue;
          seen[u] = 1;
          var q = first(/[_-](\d{3,4})p[_-]/, u);
          var quality = q ? q + "p" : "auto";
          out.push({
            name: "MinikaGO",
            title: "MinikaGO - " + quality + " [MP4] TR Dublaj",
            url: u,
            quality: quality,
            headers: { "User-Agent": UA, Referer: BASE + "/" },
            subtitles: []
          });
        }
        return out;
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][minikago] " + (e && e.message));
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
