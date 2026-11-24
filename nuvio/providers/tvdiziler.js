// @provider {"id":"tvdiziler","name":"TvDiziler","types":["tv"],"domain":"tvdiziler.tv","formats":["m3u8","mp4"],"sample":"tt10516352:tv:1:1","description":"TvDiziler yerli dizi arsivi (Streambox HLS + MP4).","verified":true,"version":"1.0.0"}
/**
 * tvdiziler — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta/alt title -> POST /search?qr= (XMLHttpRequest) -> dizi/<slug>
 *       -> data-hhs -> /vid/ply/<hash> -> jwplayer sources (streambox/yandex/mp4/hls).
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://tvdiziler.tv";
var ID = "TvDiziler";

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
  if (o.headers) for (var k in o.headers) if (o.headers[k]) headers[k] = o.headers[k];
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
  href = String(href).replace(/&amp;/g, "&").trim();
  if (/^https?:/i.test(href)) return href;
  var origin = /^(https?:\/\/[^/]+)/.exec(base);
  if (!origin) return href;
  if (href.indexOf("//") === 0) return "https:" + href;
  if (href.charAt(0) === "/") return origin[1] + href;
  return base.replace(/[^/]*$/, "") + href;
}

function titleOf(id, mediaType) {
  if (/^__alt__:/.test(String(id))) return Promise.resolve(decodeURIComponent(String(id).slice(8)));
  var kind = mediaType === "tv" ? "series" : "movie";
  return text("https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json")
    .then(function (body) { return String(((JSON.parse(body).meta) || {}).name || "").trim(); })
    .catch(function () { return ""; });
}

function sameTitle(have, want) {
  function f(v) {
    return String(v || "").replace(/[\u0131\u0130I]/g, "i").replace(/[\u015f\u015e]/g, "s").replace(/[\u011f\u011e]/g, "g")
      .replace(/[\u00e7\u00c7]/g, "c").replace(/[\u00f6\u00d6]/g, "o").replace(/[\u00fc\u00dc]/g, "u")
      .toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
  }
  have = f(have);
  want = f(want);
  return !!want && (have === want || have.indexOf(want + " ") === 0);
}

function hlsNoDownload(url) {
  var u = String(url || "");
  if (/\.m3u8(\?|$)/i.test(u) || u.indexOf("#") !== -1) return u;
  return u + "#.m3u8";
}

function cleanTitle(raw) {
  return String(raw || "").replace(/\s+izle\b.*$/i, "").replace(/\s*\((?:19|20)\d{2}\)\s*$/, "").trim().replace(/^[-|]+|[-|]+$/g, "");
}

function getStreams(tmdbId, mediaType, season, episode) {
  if (mediaType === "movie") return Promise.resolve([]);
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var wantTitle = "";
  var epUrl = null;

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      wantTitle = title;
      if (!title) return null;
      return text(BASE + "/search?qr=" + encodeURIComponent(title), {
        method: "POST",
        referer: BASE + "/",
        headers: { "X-Requested-With": "XMLHttpRequest" }
      });
    })
    .then(function (raw) {
      if (!raw) return null;
      var html = "";
      try { html = String((JSON.parse(raw) || {}).data || ""); } catch (e) { return null; }
      var re = /<a[^>]+href="([^"]*dizi\/[^"]+)"[^>]*>([\s\S]*?)<\/a>/gi, m;
      var best = null, bestScore = 0;
      while ((m = re.exec(html)) !== null) {
        var u = abs(BASE + "/", m[1]);
        var t = cleanTitle(first(/<h3[^>]*>([^<]+)<\/h3>/i, m[2]) || first(/alt="([^"]+)"/i, m[2]) || "");
        if (!u || !t) continue;
        if (sameTitle(t, wantTitle)) {
          var score = t.toLowerCase() === wantTitle.toLowerCase() ? 100 : 80;
          if (score > bestScore) { bestScore = score; best = u; }
          if (bestScore === 100) break;
        }
      }
      if (!best) return null;
      return text(best);
    })
    .then(function (detail) {
      if (!detail) return null;
      var re = /href="([^"]*bolum[^"]*)"/gi, m, fallback = null;
      while ((m = re.exec(detail)) !== null) {
        var u = abs(BASE + "/", m[1]);
        if (!u) continue;
        var sm = /(\d+)-sezon-(\d+)-bolum/.exec(u);
        if (sm) {
          if (parseInt(sm[1], 10) === want && parseInt(sm[2], 10) === wantEp) { epUrl = u; break; }
        } else if (want === 1) {
          var bm = /(\d+)-bolum/.exec(u);
          if (bm && parseInt(bm[1], 10) === wantEp && !fallback) fallback = u;
        }
      }
      epUrl = epUrl || fallback;
      if (!epUrl) return null;
      return text(epUrl).catch(function () { return null; });
    })
    .then(function (page) {
      if (!page) return [];
      var players = [], seenP = {}, m;
      var hhsRe = /data-hhs="([^"]+)"/gi;
      while ((m = hhsRe.exec(page)) !== null) {
        var parts = m[1].split(",");
        for (var i = 0; i < parts.length; i++) {
          var cand = parts[i].trim();
          if (!cand || cand.indexOf("/vid/kapat/") !== -1) continue;
          var pu = abs(BASE + "/", cand);
          if (pu && !seenP[pu]) { seenP[pu] = 1; players.push(pu); }
        }
      }
      return Promise.all(players.slice(0, 4).map(function (playerUrl) {
        return text(playerUrl, { referer: epUrl }).then(function (html) {
          var out = [], seenU = {};
          var srcRe = /file\s*:\s*["']([^"']+)["'][^{}]*?label\s*:\s*["']([^"']*)["']/g, sm;
          while ((sm = srcRe.exec(html)) !== null) {
            var stream = sm[1].replace(/\\\//g, "/");
            if (!/^https?:/i.test(stream) || seenU[stream]) continue;
            seenU[stream] = 1;
            var isHls = /\.(?:m3u8)|\/hls\/|master\.txt/i.test(stream);
            var isSb = /streambox\.xyz/i.test(stream);
            var hdrs = { "User-Agent": UA };
            if (/yandex/i.test(stream)) hdrs.Referer = "https://disk.yandex.ru/";
            else if (isSb) { hdrs.Referer = "https://streambox.xyz/"; hdrs.Accept = "*/*"; }
            else hdrs.Referer = epUrl;
            var q = first(/(\d{3,4})/, sm[2]);
            out.push({
              name: ID,
              title: ID + " - " + (sm[2] || (isHls ? "HLS" : "MP4")),
              url: isHls ? hlsNoDownload(stream) : stream,
              quality: q ? q + "p" : "auto",
              headers: hdrs,
              subtitles: []
            });
          }
          if (!out.length) {
            var direct = first(/(https?:\/\/[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?)/i, html.replace(/\\\//g, "/"));
            if (direct) {
              var isH = direct.indexOf(".m3u8") !== -1;
              out.push({
                name: ID,
                title: ID + " - " + (isH ? "HLS" : "MP4"),
                url: isH ? hlsNoDownload(direct) : direct,
                quality: "auto",
                headers: { Referer: playerUrl, "User-Agent": UA },
                subtitles: []
              });
            }
          }
          return out;
        }).catch(function () { return []; });
      })).then(function (lists) {
        var out = [], seenU = {};
        for (var i = 0; i < lists.length; i++) {
          for (var j = 0; j < lists[i].length; j++) {
            if (!seenU[lists[i][j].url]) { seenU[lists[i][j].url] = 1; out.push(lists[i][j]); }
          }
        }
        return out;
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][tvdiziler] " + (e && e.message));
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
