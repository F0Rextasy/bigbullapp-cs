// @provider {"id":"izle7","name":"Izle7","types":["tv","movie"],"domain":"izle7.com","formats":["m3u8"],"sample":"tt4745726:tv:1:1","description":"Kanal 7 resmi dizi ve TV filmi arşivi (izle7.com); Dailymotion HLS akışları.","verified":true,"version":"1.0.0"}
/**
 * izle7 — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta/alt title -> /kanal7/diziler + /hint-dizileri + /arama -> play_video -> Dailymotion player metadata (?embedder=) -> m3u8.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://www.izle7.com";

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

function mainCards(html) {
  var main = first(/<main[\s\S]*?>([\s\S]*?)<\/main>/i, html) || html;
  var out = [], re = /<a[^>]*class="[^"]*category-item[^"]*"[^>]*href="([^"]+)"[^>]*>([\s\S]*?)<\/a>|<a[^>]*href="([^"]+)"[^>]*class="[^"]*category-item[^"]*"[^>]*>([\s\S]*?)<\/a>/gi;
  var m;
  while ((m = re.exec(main)) !== null) {
    var href = abs(BASE + "/", m[1] || m[3]);
    var inner = m[2] || m[4] || "";
    var t = first(/class="category-item-title"[^>]*>([\s\S]*?)<\/[^>]+>/, inner) || first(/alt="([^"]+)"/, inner) || "";
    t = t.replace(/<[^>]+>/g, " ").trim();
    if (href && t) out.push({ url: href, title: t });
  }
  return out;
}

function pickEpCard(cards, wantTitle, wantEp) {
  for (var i = 0; i < cards.length; i++) {
    var c = cards[i];
    if (c.url.indexOf("/kategori/") !== -1) continue;
    if (/fragman|tanitim/i.test(c.url) || /fragman|tan\u0131t\u0131m/i.test(c.title)) continue;
    if (wantTitle && !sameTitle(c.title, wantTitle) && fold(c.title).indexOf(fold(wantTitle)) === -1) continue;
    var num = parseInt(first(/(\d+)\.?\s*b[oö]l[uü]m/i, decodeEntities(c.title)) || first(/-(\d+)-?bolum/i, c.url), 10);
    if (num === wantEp) return c.url;
  }
  return "";
}

function findTargetUrl(title, wantMovie, wantEp) {
  if (wantMovie) {
    return text(BASE + "/arama?q=" + encodeURIComponent(title)).then(function (html) {
      var cards = mainCards(html);
      for (var i = 0; i < cards.length; i++) {
        if (cards[i].url.indexOf("/kategori/") === -1 && sameTitle(cards[i].title, title)) return cards[i].url;
      }
      return "";
    });
  }
  return Promise.all([
    text(BASE + "/kanal7/diziler").catch(function () { return ""; }),
    text(BASE + "/kanal7/hint-dizileri").catch(function () { return ""; }),
    text(BASE + "/arama?q=" + encodeURIComponent(title + " " + wantEp + " bolum")).catch(function () { return ""; })
  ]).then(function (pages) {
    var direct = pickEpCard(mainCards(pages[2]), title, wantEp);
    if (direct) return direct;
    var catUrl = "";
    var all = mainCards(pages[0]).concat(mainCards(pages[1]));
    for (var i = 0; i < all.length; i++) {
      if (all[i].url.indexOf("/kategori/") !== -1 && sameTitle(all[i].title, title)) { catUrl = all[i].url; break; }
    }
    if (!catUrl) return "";
    return text(catUrl).then(function (cHtml) {
      var hit = pickEpCard(mainCards(cHtml), "", wantEp);
      if (hit) return hit;
      var nums = [], re = /page=(\d+)/g, m;
      while ((m = re.exec(cHtml)) !== null) nums.push(parseInt(m[1], 10));
      var last = nums.length ? Math.max.apply(null, nums) : 1;
      var tries = wantEp <= 32 ? [last, 2] : [2, 3];
      var chain = Promise.resolve("");
      tries.forEach(function (p) {
        if (p <= 1 || p > last) return;
        chain = chain.then(function (res) {
          return res || text(catUrl + "?page=" + p).then(function (ph) { return pickEpCard(mainCards(ph), "", wantEp); }).catch(function () { return ""; });
        });
      });
      return chain;
    });
  });
}

function getStreams(id, mediaType, season, episode) {
  var wantMovie = mediaType === "movie";
  var wantEp = parseInt(episode, 10) || 1;
  var dmRef = "https://www.dailymotion.com/";

  return titleOf(id, mediaType)
    .then(function (title) {
      return title ? findTargetUrl(title, wantMovie, wantEp) : "";
    })
    .then(function (epUrl) {
      if (!epUrl) return "";
      return text(epUrl, BASE + "/").catch(function () { return ""; });
    })
    .then(function (epHtml) {
      if (!epHtml) return [];
      var vid = first(/play_video\s*=\s*["']([\w-]+)["']/, epHtml) || first(/dailymotion\.com\/(?:crawler\/)?video\/([\w-]+)/, epHtml);
      if (!vid) return [];
      var metaUrl = "https://www.dailymotion.com/player/metadata/video/" + encodeURIComponent(vid) + "?embedder=" + encodeURIComponent(BASE + "/");
      return fetch(metaUrl, { headers: { "User-Agent": UA, Referer: BASE + "/" } })
        .then(function (r) { return r.ok ? r.text() : ""; })
        .then(function (body) {
          if (!body) return [];
          var meta = JSON.parse(body);
          var auto = ((meta || {}).qualities || {}).auto || [];
          var master = (auto[0] && auto[0].url) || "";
          if (!/^https?:/i.test(master)) return [];
          var headers = { "User-Agent": UA, Referer: dmRef, Origin: "https://www.dailymotion.com" };
          return text(master, dmRef).catch(function () { return ""; }).then(function (pl) {
            var vars = parseVariants(master, pl);
            if (!vars.length) {
              return [{ name: "Izle7", title: "Izle7 - HLS", url: master, quality: "auto", headers: headers, subtitles: [] }];
            }
            vars.sort(function (a, b) { return (parseInt(b.quality, 10) || 0) - (parseInt(a.quality, 10) || 0); });
            return vars.map(function (v) {
              return { name: "Izle7", title: "Izle7 - " + v.quality + " [HLS]", url: v.url, quality: v.quality, headers: headers, subtitles: [] };
            });
          });
        });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][izle7] " + (e && e.message));
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
