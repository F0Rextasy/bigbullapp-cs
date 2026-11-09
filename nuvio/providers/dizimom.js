// @provider {"id":"dizimom", "name":"DiziMom", "types":["tv"], "domain":"ddizimom.net", "formats":["m3u8"], "sample":"tt0903747:tv:1:1", "description":"DiziMom yabancı diziler Türkçe dublaj/altyazılı arşivi (pilavyer HLS).", "version":"1.1.1", "verified":true}
/**
 * dizimom — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /ara?q= -> strict card match -> /dizi/<slug> ->
 *       exact /sezon-S/bolum-E -> data-pv slug -> pilavyer s.php -> window.__PLAYER__.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://ddizimom.net";
var PLAYER = "https://pilavyerplay.top";

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
  if (o.headers) for (var k in o.headers) headers[k] = o.headers[k];
  return fetch(url, { method: o.method || "GET", headers: headers }).then(function (r) {
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
  if (String(href).charAt(0) === "/") return origin ? origin[1] + href : href;
  return base.replace(/[^/]*$/, "") + href;
}

function norm(value) {
  return String(value || "")
    .toLowerCase()
    .replace(/[\u0131]/g, "i")
    .replace(/[\u015f]/g, "s")
    .replace(/[\u011f]/g, "g")
    .replace(/[\u00fc]/g, "u")
    .replace(/[\u00f6]/g, "o")
    .replace(/[\u00e7]/g, "c")
    .replace(/[^a-z0-9]/g, "");
}

function spaced(value) {
  return String(value || "")
    .replace(/\s*\([^)]*\)/g, " ")
    .replace(/\b(?:dizi|izle)\b/gi, " ")
    .split(/[\s:\-]+/)
    .map(norm)
    .filter(Boolean)
    .join(" ")
    .replace(/^(?:the|an|a) /, "");
}

function pick(cards, wanted) {
  var w = spaced(wanted);
  if (!w) return null;
  var subset = null;
  for (var i = 0; i < cards.length; i++) {
    var n = spaced(cards[i].title);
    if (!n) continue;
    if (n === w) return cards[i];
    if (!subset && n.indexOf(w + " ") === 0) subset = cards[i];
  }
  return subset;
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

function searchSeries(title) {
  return text(BASE + "/ara?q=" + encodeURIComponent(title)).then(function (html) {
    var cards = [];
    var re = /<a\b[^>]*href="([^"]*\/dizi\/[^"?#/]+)"[^>]*>([\s\S]*?)<\/a>/g;
    var m;
    while ((m = re.exec(html)) !== null) {
      var h3 = first(/<h3[^>]*>([\s\S]*?)<\/h3>/, m[2]) || first(/alt="([^"]+)"/, m[2]);
      if (!h3) continue;
      cards.push({ url: abs(BASE, m[1]), title: h3.replace(/<[^>]+>/g, "").trim() });
    }
    var hit = pick(cards, title);
    return hit ? hit.url : null;
  });
}

function episodeUrlFor(seriesHtml, seriesUrl, season, episode) {
  var re = /href="([^"]*\/sezon-(\d+)\/bolum-(\d+))"/g;
  var m;
  while ((m = re.exec(seriesHtml)) !== null) {
    if (parseInt(m[2], 10) === season && parseInt(m[3], 10) === episode) {
      return Promise.resolve(abs(seriesUrl, m[1]));
    }
  }
  return text(seriesUrl + "?sezon=" + season + "#bolumler")
    .then(function (html) {
      var found = first(new RegExp('href="([^"]*/sezon-' + season + '/bolum-' + episode + ')"'), html);
      return found ? abs(seriesUrl, found) : null;
    })
    .catch(function () {
      return null;
    });
}

function jsonObjectAt(before, body) {
  var at = String(body || "").indexOf(before);
  if (at < 0) return null;
  at = body.indexOf("{", at);
  if (at < 0) return null;
  var depth = 0;
  var inString = false;
  var escaped = false;
  for (var i = at; i < body.length; i++) {
    var ch = body.charAt(i);
    if (inString) {
      if (escaped) escaped = false;
      else if (ch === "\\") escaped = true;
      else if (ch === '"') inString = false;
      continue;
    }
    if (ch === '"') inString = true;
    else if (ch === "{") depth++;
    else if (ch === "}") {
      depth--;
      if (depth === 0) return body.slice(at, i + 1);
    }
  }
  return null;
}

function qualityOf(masterUrl, referer) {
  return text(masterUrl, { referer: referer })
    .then(function (playlist) {
      var re = /RESOLUTION=\d+x(\d+)/g;
      var m;
      var best = 0;
      while ((m = re.exec(playlist)) !== null) best = Math.max(best, parseInt(m[1], 10) || 0);
      return best > 0 ? best + "p" : "auto";
    })
    .catch(function () {
      return "auto";
    });
}

function playerOf(slug, pageUrl, playerBase) {
  var shellUrl = playerBase + "/assets/js/s.php?s=" + encodeURIComponent(slug);
  return text(shellUrl, { referer: pageUrl }).then(function (shell) {
    var raw = jsonObjectAt("window.__PLAYER__", shell);
    if (!raw) return null;
    var cfg = JSON.parse(raw);
    var stream = String(cfg.stream || "").trim();
    if (!/^https?:/.test(stream)) return null;
    var dubs = [];
    (cfg.audios || []).forEach(function (a) {
      var label = String((a && a.label) || "").trim();
      if (label) dubs.push(label);
    });
    var subs = [];
    (cfg.subs || []).forEach(function (s) {
      var src = String((s && s.src) || "").trim();
      if (!/^https?:/.test(src)) return;
      subs.push({ url: src, language: String(s.lang || "tr"), name: String(s.label || s.lang || "tr").trim() + " [DiziMom]" });
    });
    return qualityOf(stream, BASE + "/").then(function (quality) {
      return {
        name: "DiziMom",
        title: "DiziMom - " + (dubs.length ? dubs.join(" / ") : "HLS"),
        url: stream,
        quality: quality,
        headers: { Referer: BASE + "/", "User-Agent": UA },
        subtitles: subs.slice(0, 6)
      };
    });
  });
}

function getStreams(tmdbId, mediaType, season, episode) {
  if (mediaType === "movie") return Promise.resolve([]);
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var seriesUrl = null;
  var pageUrl = null;

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return null;
      return searchSeries(title);
    })
    .then(function (url) {
      if (!url) return null;
      seriesUrl = url;
      return text(seriesUrl);
    })
    .then(function (seriesHtml) {
      if (!seriesHtml) return null;
      return episodeUrlFor(seriesHtml, seriesUrl, want, wantEp);
    })
    .then(function (episodeUrl) {
      if (!episodeUrl) return [];
      pageUrl = episodeUrl;
      return text(pageUrl);
    })
    .then(function (page) {
      if (!page) return [];
      var core = first(/<script[^>]+src="([^"]*\/assets\/js\/core\.js[^"]*)"/, page);
      var playerBase = core && /^https?:/i.test(core) ? core.replace(/\/assets\/js\/core\.js.*$/, "") : PLAYER;
      var slugs = [];
      var re = /data-pv="([A-Za-z0-9_\-]{8,})"/g;
      var m;
      while ((m = re.exec(page)) !== null) {
        if (slugs.indexOf(m[1]) === -1) slugs.push(m[1]);
      }
      if (!slugs.length) return [];
      return Promise.all(slugs.slice(0, 3).map(function (slug) {
        return playerOf(slug, pageUrl, playerBase).catch(function () { return null; });
      }));
    })
    .then(function (players) {
      var out = [];
      var seen = {};
      (players || []).forEach(function (item) {
        if (!item || !item.url || seen[item.url]) return;
        seen[item.url] = 1;
        out.push(item);
      });
      return out;
    })
    .catch(function (e) {
      console.log("[bb-nuvio][dizimom] " + (e && e.message));
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

/* Cinemeta basligi yerellestirir ("Gibi" -> "As If"); sonuc bossa Wikidata TR/EN basliklariyla yeniden dene. */
function __altTitles(raw, mediaType) {
  var h = { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36", "Accept": "application/json" };
  var api = "https://www.wikidata.org/w/api.php?format=json";
  var stmt = /^tt\d+$/.test(raw) ? "P345=" + raw : (mediaType === "movie" ? "P4947=" : "P4983=") + raw;
  return fetch(api + "&action=query&list=search&srlimit=1&srsearch=" + encodeURIComponent("haswbstatement:" + stmt), { headers: h })
    .then(function (r) { return r.ok ? r.json() : null; })
    .then(function (j) {
      var hits = (j && j.query && j.query.search) || [];
      if (!hits.length) return null;
      return fetch(api + "&action=wbgetentities&props=labels|aliases&languages=tr|en&ids=" + hits[0].title, { headers: h }).then(function (r) { return r.ok ? r.json() : null; });
    })
    .then(function (j) {
      var out = [], seen = {}, ents = (j && j.entities) || {}, k, l, a, i;
      function add(v) {
        v = String(v || "").replace(/\s*\([^)]*\)\s*$/, "").trim();
        if (v && !seen[v.toLowerCase()]) { seen[v.toLowerCase()] = 1; out.push(v); }
      }
      for (k in ents) {
        l = ents[k].labels || {}; a = ents[k].aliases || {};
        add((l.tr || {}).value); add((l.en || {}).value);
        for (i = 0; i < (a.tr || []).length && i < 2; i++) add(a.tr[i].value);
      }
      return out;
    })
    .catch(function () { return []; });
}
var __cinemetaGetStreams = module.exports.getStreams;
module.exports.getStreams = function (id, mediaType, season, episode) {
  var raw = String(id || "");
  return __cinemetaGetStreams(raw, mediaType, season, episode).then(function (streams) {
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
};

/* Dil etiketi (AGENTS.md): Nuvio karti "quality \u2022 ..." satirini gosterir; dil etiketi kalitenin yanina yazilir. */
var __subOnly = false;
var __langLabeled = module.exports.getStreams;
module.exports.getStreams = function (id, mediaType, season, episode) {
  return __langLabeled.apply(this, arguments).then(function (streams) {
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
  });
};
