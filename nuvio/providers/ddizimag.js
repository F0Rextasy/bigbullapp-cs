// @provider {"id": "ddizimag", "name": "DDizimag", "types": ["tv", "movie"], "domain": "ddizimag2.icu", "formats": ["m3u8"], "sample": "tt13675832:tv:1:1", "description": "DDizimag yerli ve yabanc\u0131 dizi/film ar\u015fivi; pilavyer HLS ak\u0131\u015f\u0131 ve altyaz\u0131lar.", "version":"1.0.3"}
/**
 * ddizimag — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta (+ Wikidata TR / IMDb / TVMaze) -> /ara?q= -> /dizi/<slug>/sezon-S/bolum-E
 *       -> data-pv + core.js -> s.php?s= -> window.__PLAYER__ -> HLS + altyazi.
 */

var UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36";
var BASE = "https://ddizimag2.icu";
var ID = "ddizimag";

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
  return fetch(url, { method: "GET", headers: headers }).then(function (r) {
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
  if (!origin) return href;
  if (href.indexOf("//") === 0) return "https:" + href;
  if (href.charAt(0) === "/") return origin[1] + href;
  return base.replace(/[^/]*$/, "") + href;
}

function fold(v) {
  return String(v || "")
    .replace(/[\u0131\u0130I]/g, "i").replace(/[\u015f\u015e]/g, "s").replace(/[\u011f\u011e]/g, "g")
    .replace(/[\u00e7\u00c7]/g, "c").replace(/[\u00f6\u00d6]/g, "o").replace(/[\u00fc\u00dc]/g, "u")
    .toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
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

function titleOf(id, mediaType) {
  if (/^__alt__:/.test(String(id))) return Promise.resolve(decodeURIComponent(String(id).slice(8)));
  var kind = mediaType === "tv" ? "series" : "movie";
  return text("https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json")
    .then(function (body) {
      var meta = JSON.parse(body).meta || {};
      return String(meta.name || "").trim();
    })
    .catch(function () { return ""; });
}

function resolvePlayer(pageUrl) {
  return text(pageUrl).then(function (page) {
    var pv = first(/data-pv="([^"]+)"/, page);
    if (!pv) return null;
    var core = first(/<script[^>]+src="([^"]*core\.js[^"]*)"/, page);
    var playerBase = core ? abs(pageUrl, core).replace(/\/assets\/js\/core\.js.*$/, "") : "https://pilavyerplay.top";
    return text(playerBase + "/assets/js/s.php?s=" + encodeURIComponent(pv), { referer: pageUrl }).then(function (frame) {
      var m = /window\.__PLAYER__\s*=\s*(\{[\s\S]*?\});/.exec(String(frame || ""));
      if (!m) return null;
      try {
        var cfg = JSON.parse(m[1]);
        return cfg && cfg.stream ? { cfg: cfg, playerBase: playerBase } : null;
      } catch (e) {
        return null;
      }
    });
  });
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

function rowsOf(found) {
  return text(found.cfg.stream, { referer: found.playerBase + "/" })
    .catch(function () { return ""; })
    .then(function (master) {
      var quality = qualityOf(master, "1080p");
      var subs = [];
      var list = found.cfg.subs || [];
      for (var i = 0; i < list.length && subs.length < 4; i++) {
        var sub = list[i] || {};
        if (!sub.src) continue;
        var lang = String(sub.lang || "tr").toLowerCase();
        subs.push({ url: sub.src, language: lang, name: String(sub.label || lang.toUpperCase()) + " [DDizimag]" });
      }
      return [
        {
          name: "DDizimag",
          title: "DDizimag - " + quality + " [HLS]",
          url: hlsNoDownload(found.cfg.stream),
          quality: quality,
          headers: { Referer: found.playerBase + "/" },
          subtitles: subs
        }
      ];
    });
}

function pickCard(html, wantTitle, isMovie) {
  var main = String(html || "").split(/<\/main>/i)[0];
  var prefix = isMovie ? "https?:\\/\\/[^\"\\/]+\\/film\\/[^\"?#]+" : "https?:\\/\\/[^\"\\/]+\\/dizi\\/[^\"?#]+";
  var re = new RegExp("<a href=\"(" + prefix + ")\"[^>]*>([\\s\\S]*?)<\\/a>", "gi");
  var m;
  while ((m = re.exec(main)) !== null) {
    var titleMatch = /<h3[^>]*>([^<]+)<\/h3>/i.exec(m[2]) || /alt="([^"]*)"/i.exec(m[2]);
    if (!titleMatch) continue;
    var clean = titleMatch[1]
      .replace(/&#039;/g, "'").replace(/&amp;/g, "&")
      .replace(/\s+(?:Dizi|Film)\s+izle\s*$/i, "").trim();
    if (sameTitle(clean, wantTitle)) return m[1];
  }
  return null;
}

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var isMovie = mediaType !== "tv";
  var wantTitle = "";

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      wantTitle = title;
      if (!title) return null;
      return text(BASE + "/ara?q=" + encodeURIComponent(title));
    })
    .then(function (search) {
      if (!search) return null;
      var link = pickCard(search, wantTitle, isMovie);
      if (!link) return null;
      var base = abs(BASE, link).replace(/\/+$/, "");
      var target = isMovie ? base : base + "/sezon-" + want + "/bolum-" + wantEp;
      return resolvePlayer(target);
    })
    .then(function (found) {
      return found ? rowsOf(found) : [];
    })
    .catch(function (e) {
      console.log("[bb-nuvio][" + ID + "] " + (e && e.message));
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
    return Promise.resolve(__cinemetaGetStreams(raw, mediaType, season, episode)).then(function (streams) {
      if ((streams && streams.length) || /^__alt__:/.test(raw)) return streams || [];
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
