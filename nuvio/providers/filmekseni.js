// @provider {"id":"filmekseni","name":"FilmEkseni","types":["movie"],"domain":"filmekseni.pro","formats":["m3u8","mp4"],"sample":"tt0133093:movie:1:1","description":"FilmEkseni - Turkce dublaj ve altyazili film arsivi (eksenload + vidmoly).","verified":true,"version":"1.0.0"}
/**
 * filmekseni — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta meta -> /api/search?q= JSON -> movie page ->
 *       videoPlayerData JSON -> base64 template -> eksenload / vidmoly HLS/MP4.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://filmekseni.pro";
var B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

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
  if (!m) return null;
  return m[1] === undefined ? m[0] : m[1];
}

function origin(url) {
  var m = /^(https?:\/\/[^/]+)/.exec(String(url || ""));
  return m ? m[1] : "";
}

function abs(base, url) {
  var u = String(url || "").trim();
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

function atobJs(value) {
  var s = String(value || "").replace(/[^A-Za-z0-9+/=]/g, "");
  var out = "", buf = 0, bits = 0;
  for (var i = 0; i < s.length; i++) {
    var v = B64.indexOf(s.charAt(i));
    if (v < 0) continue;
    buf = (buf << 6) | v;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out += String.fromCharCode((buf >> bits) & 255);
    }
  }
  return out;
}

function jsUnescape(s) {
  return String(s || "").replace(/\\(u[0-9a-fA-F]{4}|x[0-9a-fA-F]{2}|.)/g, function (_, esc) {
    var ch = esc.charAt(0);
    if (ch === "u" || ch === "x") return String.fromCharCode(parseInt(esc.slice(1), 16));
    if (ch === "n") return "\n";
    if (ch === "r") return "\r";
    if (ch === "t") return "\t";
    return esc;
  });
}

function norm(value) {
  return String(value || "")
    .replace(/[\u0131\u0130I]/g, "i").replace(/[\u015f\u015e]/g, "s").replace(/[\u011f\u011e]/g, "g")
    .replace(/[\u00e7\u00c7]/g, "c").replace(/[\u00f6\u00d6]/g, "o").replace(/[\u00fc\u00dc]/g, "u")
    .toLowerCase().replace(/&[a-z0-9#]+;/gi, " ").replace(/[^a-z0-9]+/g, " ").trim()
    .replace(/^(?:the|a|an)\s+/, "");
}

function titleOf(id, mediaType) {
  var raw = String(id || "").trim();
  if (/^__alt__:/.test(raw)) {
    return Promise.resolve({ name: decodeURIComponent(raw.slice(8)), year: "", imdbId: "" });
  }
  var kind = mediaType === "tv" ? "series" : "movie";
  return text("https://v3-cinemeta.strem.io/meta/" + kind + "/" + encodeURIComponent(raw) + ".json")
    .then(function (body) {
      var meta = JSON.parse(body).meta || {};
      var imdbId = /^tt\d+$/.test(raw) ? raw : String(meta.imdb_id || "");
      return {
        name: String(meta.name || "").trim(),
        year: String(meta.year || meta.releaseInfo || "").slice(0, 4),
        imdbId: /^tt\d+$/.test(imdbId) ? imdbId : ""
      };
    })
    .catch(function () {
      return { name: "", year: "", imdbId: /^tt\d+$/.test(raw) ? raw : "" };
    });
}

function qualityOf(url, referer) {
  if (!/\.m3u8/i.test(url)) return Promise.resolve("auto");
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

function langLabel(key) {
  var k = String(key || "").toLowerCase();
  if (k === "dual") return "T\u00fcrk\u00e7e Dublaj & Altyaz\u0131";
  if (k === "tr" || k === "dublaj" || k === "tr-dublaj") return "T\u00fcrk\u00e7e Dublaj";
  if (k === "en" || k === "sub" || k === "altyazi" || k === "tr-altyazi") return "T\u00fcrk\u00e7e Altyaz\u0131";
  return "";
}

function pageMatchesTarget(html, meta) {
  if (meta.imdbId) {
    var tts = String(html || "").match(/tt\d{6,9}/g) || [];
    if (tts.length) return tts.indexOf(meta.imdbId) >= 0;
  }
  var h1 = (first(/<h1[^>]*>([\s\S]*?)<\/h1>/i, html) || "").replace(/<[^>]+>/g, " ").replace(/\s+izle$/i, "").trim();
  return !!(meta.name && norm(h1) === norm(meta.name));
}

function pickCandidates(rows, meta) {
  var want = norm(meta.name);
  var year = String(meta.year || "").slice(0, 4);
  var exact = [], prefix = [], alias = [];
  for (var i = 0; i < (rows || []).length; i++) {
    var row = rows[i];
    if (!row || !row.slug) continue;
    if (row.contentableType && row.contentableType !== "App\\Models\\Movie") continue;
    var slug = String(row.slug).replace(/^\/+/, "");
    var rowTitle = norm(row.title);
    var rowYear = String(row.releaseYear || "").slice(0, 4);
    var yearOk = !year || !rowYear || rowYear === year;
    if (want && rowTitle === want && yearOk) exact.push(slug);
    else if (want && rowTitle.indexOf(want + " ") === 0 && yearOk) prefix.push(slug);
    else if (meta.imdbId && year && rowYear === year) alias.push(slug);
  }
  return exact.concat(prefix, alias).slice(0, 3);
}

function parseEmbed(embedUrl, pageUrl, baseQ, label, serviceName) {
  var isMoly = /vidmoly/i.test(embedUrl);
  var target = isMoly ? embedUrl.replace(/\/\/(?:www\.)?vidmoly\.(?:to|biz|me|sx)\//i, "//vidmoly.biz/") : embedUrl;
  var hdrs = isMoly ? { "Sec-Fetch-Dest": "iframe" } : {};
  return text(target, { referer: pageUrl, headers: hdrs })
    .then(function (html) {
      var clean = jsUnescape(html).replace(/\\/g, "");
      var stream = first(/https?:\/\/[^\s"'<>\\]+?\.(?:m3u8|mp4)[^\s"'<>\\]*/i, clean);
      if (!stream) return null;
      var isHls = /\.m3u8/i.test(stream);
      var orig = origin(target);
      var ref = orig ? orig + "/" : pageUrl;
      return qualityOf(stream, ref).then(function (q) {
        var finalQ = q !== "auto" ? q : (baseQ || "auto");
        var srv = serviceName || (isMoly ? "VidMoly" : "VIP");
        var tag = label ? " - " + label : "";
        return {
          name: "FilmEkseni",
          title: "FilmEkseni - " + srv + tag,
          url: isHls ? hlsNoDownload(stream) : stream,
          quality: finalQ,
          headers: orig ? { Referer: ref, Origin: orig, "User-Agent": UA } : { Referer: ref, "User-Agent": UA },
          subtitles: []
        };
      });
    })
    .catch(function () { return null; });
}

function extractPlayerStreams(pageHtml, pageUrl) {
  var raw = first(/videoPlayerData\(JSON\.parse\('([^']+)'\)/, pageHtml) || first(/JSON\.parse\('([^']+)'\)/, pageHtml);
  if (!raw) return Promise.resolve([]);
  var data;
  try {
    data = JSON.parse(raw.replace(/\\u0022/g, '"'));
  } catch (e) {
    try { data = JSON.parse(jsUnescape(raw)); } catch (e2) { return Promise.resolve([]); }
  }
  var jobs = [], seenEmbed = {};
  for (var langKey in data) {
    var list = data[langKey];
    if (!Array.isArray(list)) continue;
    var label = langLabel(langKey);
    for (var i = 0; i < list.length; i++) {
      var it = list[i];
      if (!it || !it.template || !it.link) continue;
      var tpl = atobJs(it.template);
      var srcTpl = first(/(?:data-)?src="([^"]+)"/i, tpl);
      if (!srcTpl) continue;
      var embedUrl = abs(BASE, srcTpl.replace(/\{url\}/g, String(it.link)).replace(/\{slug\}/g, String(it.service_slug || it.slug || "")));
      if (!/^https?:\/\//i.test(embedUrl) || seenEmbed[embedUrl]) continue;
      seenEmbed[embedUrl] = 1;
      jobs.push(parseEmbed(embedUrl, pageUrl, String(it.quality || "").trim(), label, it.service_name || ""));
      if (jobs.length >= 6) break;
    }
    if (jobs.length >= 6) break;
  }
  if (!jobs.length) return Promise.resolve([]);
  return Promise.all(jobs).then(function (rows) {
    var out = [], seenUrl = {};
    (rows || []).forEach(function (item) {
      if (!item || !item.url || seenUrl[item.url]) return;
      seenUrl[item.url] = 1;
      out.push(item);
    });
    return out;
  });
}

function getStreams(tmdbId, mediaType, season, episode) {
  if (mediaType === "tv") return Promise.resolve([]);
  var curMeta = { name: "", year: "", imdbId: "" };

  return titleOf(tmdbId, mediaType)
    .then(function (meta) {
      curMeta = meta;
      if (!meta.name) return [];
      return text(BASE + "/api/search?q=" + encodeURIComponent(meta.name), {
        referer: BASE + "/",
        headers: { Accept: "application/json", "X-Requested-With": "XMLHttpRequest" }
      }).then(function (body) {
        var json;
        try { json = JSON.parse(body); } catch (e) { return []; }
        return pickCandidates(json && json.data, meta);
      });
    })
    .then(function (slugs) {
      if (!slugs || !slugs.length) return null;
      var chain = Promise.resolve(null);
      slugs.forEach(function (slug) {
        chain = chain.then(function (verified) {
          if (verified) return verified;
          var detailUrl = BASE + "/" + slug.replace(/^\/+|\/+$/g, "") + "/";
          return text(detailUrl, { referer: BASE + "/" })
            .then(function (html) {
              return pageMatchesTarget(html, curMeta) ? { url: detailUrl, html: html } : null;
            })
            .catch(function () { return null; });
        });
      });
      return chain;
    })
    .then(function (match) {
      if (!match || !match.html) return [];
      return extractPlayerStreams(match.html, match.url);
    })
    .catch(function (e) {
      console.log("[bb-nuvio][filmekseni] " + (e && e.message));
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
