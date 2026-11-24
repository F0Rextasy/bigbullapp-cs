// @provider {"id":"dizilab","name":"Dizilab","types":["tv"],"domain":"dizilab.cc","formats":["m3u8"],"sample":"tt36220283:tv:1:1","description":"Dizilab yabanci ve Asya dizileri (dzembed HLS + altyazi).","verified":true,"version":"1.0.0"}
/**
 * dizilab — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta/alt title -> /?s= -> article.card -> /bolum/<slug>-S-sezon-E-bolum/
 *       -> iframe (ksdpictures.site/dzembed.php NP_CONFIG or direct m3u8) -> HLS master + subtitles.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://dizilab.cc";
var ID = "Dizilab";

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

function sliceBalanced(body, open) {
  var depth = 0, inString = false, escaped = false;
  for (var i = open; i < body.length; i++) {
    var c = body.charAt(i);
    if (inString) {
      if (escaped) escaped = false;
      else if (c === "\\") escaped = true;
      else if (c === '"') inString = false;
      continue;
    }
    if (c === '"') inString = true;
    else if (c === "{" || c === "[") depth++;
    else if (c === "}" || c === "]") {
      depth--;
      if (depth === 0) return body.slice(open, i + 1);
    }
  }
  return null;
}

function jsonAfter(body, marker) {
  var at = body.indexOf(marker);
  if (at < 0) return null;
  var open = body.indexOf("{", at + marker.length);
  var slice = open < 0 ? null : sliceBalanced(body, open);
  if (!slice) return null;
  try { return JSON.parse(slice); } catch (e) { return null; }
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
      return text(BASE + "/?s=" + encodeURIComponent(title));
    })
    .then(function (html) {
      if (!html) return null;
      var re = /<article[^>]*class="[^"]*card[^"]*"[^>]*>([\s\S]*?)<\/article>/gi;
      var m, best = null, bestScore = 0;
      while ((m = re.exec(html)) !== null) {
        var u = first(/href="([^"]*\/dizi\/[^"]+)"/i, m[1]);
        var t = first(/<h3[^>]*>([^<]+)<\/h3>/i, m[1]) || first(/alt="([^"]+)"/i, m[1]) || "";
        u = abs(BASE, u);
        t = t.trim();
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
      var re = /href="([^"]*\/bolum\/[^"]+)"/gi, m;
      while ((m = re.exec(detail)) !== null) {
        var u = abs(BASE, m[1]);
        var sm = /-(\d+)-sezon-(\d+)-bolum/.exec(u);
        if (sm && parseInt(sm[1], 10) === want && parseInt(sm[2], 10) === wantEp) {
          epUrl = u;
          break;
        }
      }
      if (!epUrl) return null;
      return text(epUrl).catch(function () { return null; });
    })
    .then(function (page) {
      if (!page) return [];
      var frames = [], seenF = {}, m;
      var ifRe = /<iframe[^>]+src=["']([^"']+)["']/gi;
      while ((m = ifRe.exec(page)) !== null) {
        var f = abs(epUrl, m[1]);
        if (f && /^https?:/i.test(f) && !/youtu/i.test(f) && !seenF[f]) {
          seenF[f] = 1;
          frames.push(f);
        }
      }
      return Promise.all(frames.slice(0, 4).map(function (frame) {
        return text(frame, { referer: epUrl }).then(function (inner) {
          var out = [];
          var cfg = jsonAfter(inner, "NP_CONFIG");
          if (cfg && Array.isArray(cfg.sources)) {
            var subs = [];
            var tracks = cfg.subtitles || [];
            for (var j = 0; j < tracks.length && subs.length < 4; j++) {
              if (!tracks[j] || !/^https?:/i.test(String(tracks[j].url || ""))) continue;
              subs.push({
                url: tracks[j].url,
                language: String(tracks[j].lang || "tr").toLowerCase(),
                name: String(tracks[j].label || "Türkçe") + " [" + ID + "]"
              });
            }
            var refOrigin = (first(/^(https?:\/\/[^/]+)/, frame) || BASE) + "/";
            for (var i = 0; i < cfg.sources.length; i++) {
              var s = cfg.sources[i];
              var stream = s && (s.master || s.file);
              if (!stream || !/^https?:/i.test(stream)) continue;
              out.push({
                name: ID,
                title: ID + " - " + String(s.title || "HLS"),
                url: hlsNoDownload(stream),
                quality: "auto",
                headers: { Referer: refOrigin, "User-Agent": UA },
                subtitles: subs
              });
            }
          }
          if (!out.length) {
            var clean = inner.replace(/\\\//g, "/");
            var m3Re = /https?:\/\/[^"'\s<>]+?\.m3u8(?:\?[^"'\s<>]*)?/g, mm;
            while ((mm = m3Re.exec(clean)) !== null) {
              var hdrs = { "User-Agent": UA };
              if (mm[0].indexOf("video.twimg.com") === -1) hdrs.Referer = frame;
              out.push({
                name: ID,
                title: ID + " - HLS",
                url: hlsNoDownload(mm[0]),
                quality: "auto",
                headers: hdrs,
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
      console.log("[bb-nuvio][dizilab] " + (e && e.message));
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
