// @provider {"id":"dizigom","name":"Dizigom","types":["tv","movie"],"domain":"www.dizigom.news","formats":["m3u8"],"sample":"tt0903747:tv:1:1","description":"Dizigom dizi ve film arsivi (Pilavyer HLS + altyazi).","verified":true,"version":"1.0.0"}
/**
 * dizigom — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta/alt title -> /ara?q= -> /diziler/<slug>/ or /film/<slug> -> episode/movie page
 *       -> [data-pv] + core.js -> s.php -> window.__PLAYER__ -> HLS master + subtitles.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://www.dizigom.news";
var ID = "Dizigom";

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
    .then(function (body) {
      var meta = JSON.parse(body).meta || {};
      return String(meta.name || "").trim();
    })
    .catch(function () {
      return "";
    });
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

function qualityOf(master, fallback) {
  var re = /RESOLUTION=\d+x(\d+)/g;
  var best = 0;
  var m;
  while ((m = re.exec(String(master || ""))) !== null) {
    var h = parseInt(m[1], 10);
    if (h > best) best = h;
  }
  return best ? best + "p" : fallback;
}

function playerConfig(frame) {
  var m = /window\.__PLAYER__\s*=\s*(\{[\s\S]*?\});/.exec(String(frame || ""));
  if (!m) return null;
  try {
    return JSON.parse(m[1]);
  } catch (e) {
    return null;
  }
}

function resolvePilavyer(pageUrl, page) {
  var pvs = [], seenPv = {}, m;
  var pvRe = /data-pv=["']([^"']+)["']|s\.php\?s=([\w-]+)/g;
  while ((m = pvRe.exec(page)) !== null) {
    var pv = m[1] || m[2];
    if (pv && !seenPv[pv]) { seenPv[pv] = 1; pvs.push(pv); }
  }
  if (!pvs.length) return Promise.resolve([]);
  var core = first(/<script[^>]+src=["']([^"']*core\.js[^"']*)["']/, page);
  var hostMatch = core && !/kankaplayer/i.test(core) ? /^(https?:\/\/[^/]+)/.exec(abs(pageUrl, core)) : null;
  var playerBase = hostMatch ? hostMatch[1] : "https://pilavyerplay.top";
  var refUrl = playerBase + "/";

  return Promise.all(pvs.slice(0, 3).map(function (pv, idx) {
    return text(playerBase + "/assets/js/s.php?s=" + encodeURIComponent(pv), { referer: pageUrl })
      .then(function (frame) {
        var cfg = playerConfig(frame);
        if (!cfg || !cfg.stream || !/^https?:/i.test(cfg.stream)) return null;
        return text(cfg.stream, { referer: refUrl }).catch(function () { return ""; }).then(function (master) {
          var q = qualityOf(master, "auto");
          var subs = [];
          var list = cfg.subs || [];
          for (var i = 0; i < list.length && subs.length < 4; i++) {
            var sub = list[i] || {};
            if (!sub.src || !/^https?:/i.test(sub.src)) continue;
            var lang = String(sub.lang || "tr").toLowerCase();
            subs.push({ url: sub.src, language: lang, name: String(sub.label || lang.toUpperCase()) + " [" + ID + "]" });
          }
          var label = pvs.length > 1 ? "Pilavyer " + (idx + 1) : "Pilavyer";
          return {
            name: ID,
            title: ID + " - " + label + " " + q + " [HLS]",
            url: hlsNoDownload(cfg.stream),
            quality: q,
            headers: { Referer: refUrl, "User-Agent": UA },
            subtitles: subs
          };
        });
      })
      .catch(function () { return null; });
  })).then(function (rows) {
    return rows.filter(Boolean);
  });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var isMovie = mediaType === "movie";
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var wantTitle = "";
  var contentUrl = null;
  var playUrl = null;

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      wantTitle = title;
      if (!title) return null;
      return text(BASE + "/ara?q=" + encodeURIComponent(title));
    })
    .then(function (html) {
      if (!html) return null;
      var re = isMovie
        ? /<a\b[^>]*href="([^"]*\/film\/[^"?#]+)"[^>]*>([\s\S]{0,4000}?)<\/a>/gi
        : /<a\b[^>]*href="([^"]*\/(?:diziler|dizi)\/[^"?#]+)"[^>]*>([\s\S]{0,4000}?)<\/a>/gi;
      var m, best = null, bestScore = 0;
      while ((m = re.exec(html)) !== null) {
        var u = abs(BASE, m[1]);
        if (!u) continue;
        var t = first(/<h[23][^>]*>([^<]+)<\/h[23]>/i, m[2]) || first(/alt="([^"]+)"/i, m[2]) || first(/title="([^"]+)"/i, m[0]) || "";
        t = t.replace(/\s+(?:dizi|film)?\s*izle\s*$/i, "").trim();
        if (!t) continue;
        if (sameTitle(t, wantTitle)) {
          var score = t.toLowerCase() === wantTitle.toLowerCase() ? 100 : 80;
          if (score > bestScore) { bestScore = score; best = u; }
          if (bestScore === 100) break;
        }
      }
      if (!best) return null;
      contentUrl = best.replace(/\/+$/, "");
      return text(contentUrl + "/");
    })
    .then(function (detail) {
      if (!detail) return null;
      if (isMovie) {
        playUrl = contentUrl;
        if (detail.indexOf("data-pv") !== -1) return detail;
        return text(contentUrl + "/izle").catch(function () { return detail; });
      }
      var re = /href="([^"]*(?:-(\d+)-sezon-(\d+)-bolum|(?:sezon|season)[-_\/]?(\d+)[^"]*(?:bolum|episode)[-_\/]?(\d+))[^"]*)"/gi;
      var m;
      while ((m = re.exec(detail)) !== null) {
        var se = parseInt(m[2] || m[4], 10);
        var ep = parseInt(m[3] || m[5], 10);
        if (se === want && ep === wantEp) {
          playUrl = abs(contentUrl, m[1]);
          break;
        }
      }
      if (!playUrl && detail.indexOf("?sezon=" + want) !== -1) {
        return text(contentUrl + "/?sezon=" + want).then(function (sHtml) {
          var m2;
          re.lastIndex = 0;
          while ((m2 = re.exec(sHtml)) !== null) {
            var se2 = parseInt(m2[2] || m2[4], 10);
            var ep2 = parseInt(m2[3] || m2[5], 10);
            if (se2 === want && ep2 === wantEp) {
              playUrl = abs(contentUrl, m2[1]);
              break;
            }
          }
          if (!playUrl) return null;
          return text(playUrl).catch(function () { return null; });
        });
      }
      if (!playUrl) return null;
      return text(playUrl).catch(function () { return null; });
    })
    .then(function (page) {
      if (!page) return [];
      return resolvePilavyer(playUrl, page).then(function (streams) {
        if (streams.length) return streams;
        var clean = page.replace(/\\\//g, "/").replace(/\\"/g, '"');
        var direct = first(/(https?:\/\/[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?)/i, clean);
        if (!direct) return [];
        var isHls = direct.indexOf(".m3u8") !== -1;
        return [{
          name: ID,
          title: ID + " - Direct" + (isHls ? " [HLS]" : " [MP4]"),
          url: isHls ? hlsNoDownload(direct) : direct,
          quality: "auto",
          headers: { Referer: playUrl, "User-Agent": UA },
          subtitles: []
        }];
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][dizigom] " + (e && e.message));
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
