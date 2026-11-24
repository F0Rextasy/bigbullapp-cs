// @provider {"id":"cizgimax","name":"Cizgimax","types":["tv"],"domain":"cizgimax.online","formats":["m3u8","mp4"],"sample":"tt2293002:tv:1:1","description":"Cizgimax cizgi film, anime ve dizi arsivi (tau-video, ok.ru, vidmoly, mail.ru).","verified":true,"version":"1.0.0"}
/**
 * cizgimax — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /api/search/suggest/?q= -> /diziler/<slug>/ -> episode
 *       -> base64 servers[] -> streamUrl / src (/oynat/ 302) / resolveUrl (tau-video).
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://cizgimax.online";
var ID = "Cizgimax";
var B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

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

function first(re, body) {
  var m = re.exec(String(body || ""));
  return m ? m[1] : null;
}

function abs(base, href) {
  if (!href) return null;
  href = String(href).replace(/&amp;/g, "&").trim();
  if (/^https?:/i.test(href)) return href;
  if (href.indexOf("//") === 0) return "https:" + href;
  var origin = /^(https?:\/\/[^/]+)/.exec(base);
  if (href.charAt(0) === "/") return origin ? origin[1] + href : href;
  return base.replace(/[^/]*$/, "") + href;
}

function hlsNoDownload(url) {
  var u = String(url || "");
  if (/\.m3u8(\?|$)/i.test(u) || u.indexOf("#") !== -1) return u;
  return u + "#.m3u8";
}

function unbase64(s) {
  var src = String(s || "").replace(/\\/g, "").replace(/[^A-Za-z0-9+/]/g, "");
  var out = "";
  var buf = 0;
  var bits = 0;
  for (var i = 0; i < src.length; i++) {
    var c = B64.indexOf(src.charAt(i));
    if (c < 0) continue;
    buf = (buf << 6) | c;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out += String.fromCharCode((buf >> bits) & 0xff);
      buf &= (1 << bits) - 1;
    }
  }
  return out;
}

function fold(value) {
  return String(value || "")
    .replace(/[\u0131\u0130I]/g, "i").replace(/[\u015f\u015e]/g, "s").replace(/[\u011f\u011e]/g, "g")
    .replace(/[\u00fc\u00dc]/g, "u").replace(/[\u00f6\u00d6]/g, "o").replace(/[\u00e7\u00c7]/g, "c")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
}

function titleOf(id, mediaType) {
  if (/^__alt__:/.test(String(id))) return Promise.resolve(decodeURIComponent(String(id).slice(8)));
  var kind = mediaType === "tv" ? "series" : "movie";
  return text("https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json")
    .then(function (body) { return String((JSON.parse(body).meta || {}).name || "").trim(); })
    .catch(function () { return ""; });
}

function pickSuggest(items, title) {
  var want = fold(title);
  if (!want) return null;
  var best = null;
  var bestScore = -1;
  for (var i = 0; i < (items || []).length; i++) {
    var item = items[i] || {};
    var u = String(item.url || "").replace(/\\\//g, "/");
    if (!u) continue;
    var have = fold(item.name);
    var score = have === want ? 100 : (have.indexOf(want + " ") === 0 ? 60 : 0);
    if (score > bestScore) {
      bestScore = score;
      best = abs(BASE, u);
    }
  }
  return bestScore > 0 ? best : null;
}

function findEpisode(detailHtml, wantSe, wantEp) {
  var re = /href="([^"]*-(\d+)-sezon-(\d+)-bolum-izle\/?)"/g;
  var m;
  var fallback = null;
  while ((m = re.exec(detailHtml || "")) !== null) {
    var s = parseInt(m[2], 10);
    var e = parseInt(m[3], 10);
    if (s === wantSe && e === wantEp) return abs(BASE, m[1]);
    if (e === wantEp && !fallback) fallback = abs(BASE, m[1]);
  }
  return fallback;
}

function langTag(srv) {
  var l = String((srv && srv.lang) || "").toLowerCase();
  if (l.indexOf("dub") !== -1 || l === "tr") return "TR Dublaj";
  if (l.indexOf("alt") !== -1 || l.indexOf("sub") !== -1) return "Altyazili";
  return "";
}

function okru(embed, label) {
  var id = first(/(?:videoembed|video)\/(\d+)/, embed);
  if (!id) return Promise.resolve([]);
  return text("https://ok.ru/dk?cmd=videoPlayerMetadata&mid=" + id, {
    method: "POST",
    headers: { "User-Agent": "Mozilla/5.0 (Linux; Android 13)", Referer: "https://ok.ru/" }
  }).then(function (body) {
    var qmap = { lowest: "240p", low: "360p", sd: "480p", hd: "720p", full: "1080p" };
    var vids = JSON.parse(body).videos || [];
    var out = [];
    for (var i = vids.length - 1; i >= 0; i--) {
      var v = vids[i];
      if (!v || v.disallowed || /^mobile$/i.test(v.name || "")) continue;
      var u = String(v.url || "").replace(/\\\//g, "/");
      if (!/^http/.test(u)) continue;
      var q = qmap[String(v.name || "").toLowerCase()] || "auto";
      out.push({
        name: ID,
        title: ID + " - " + label + " " + q + " [MP4]",
        url: u,
        quality: q + " [MP4]",
        headers: { Referer: "https://ok.ru/", "User-Agent": "Mozilla/5.0 (Linux; Android 13)" },
        subtitles: []
      });
    }
    return out;
  }).catch(function () { return []; });
}

function vidmoly(embed, referer, label) {
  var target = embed.replace(/\/\/(?:www\.)?vidmoly\.(?:to|me|biz|sx|org)\//i, "//vidmoly.net/");
  return text(target, { referer: referer, headers: { "Sec-Fetch-Dest": "iframe" } }).then(function (html) {
    var m3u8 = first(/sources:\s*\[\s*\{[^}]*?file:\s*["']([^"']+\.m3u8[^"']*)["']/, html)
      || first(/file:\s*["'](https?:\/\/[^"']+\.m3u8[^"']*)["']/, html);
    if (!m3u8) return [];
    var ref = (first(/^(https?:\/\/[^/]+)/, target) || "https://vidmoly.net") + "/";
    return [{
      name: ID,
      title: ID + " - " + label + " [HLS]",
      url: hlsNoDownload(m3u8.replace(/\\\//g, "/")),
      quality: "auto",
      headers: { Referer: ref, "User-Agent": UA },
      subtitles: []
    }];
  }).catch(function () { return []; });
}

function resolveTau(resolveUrl, epUrl, label) {
  return text(abs(BASE, resolveUrl), { referer: epUrl })
    .then(function (body) {
      var key = (JSON.parse(body) || {}).id;
      if (!key) return [];
      return text("https://tau-video.xyz/api/video/" + encodeURIComponent(key), { referer: "https://tau-video.xyz/" });
    })
    .then(function (body) {
      if (typeof body !== "string") return [];
      var urls = (JSON.parse(body) || {}).urls || [];
      var out = [];
      for (var i = 0; i < urls.length; i++) {
        var row = urls[i] || {};
        var stream = String(row.url || "").trim();
        if (!/^https?:/.test(stream)) continue;
        var q = String(row.label || "auto");
        var isHls = stream.indexOf(".m3u8") !== -1;
        out.push({
          name: ID,
          title: ID + " - " + label + " " + q + (isHls ? " [HLS]" : " [MP4]"),
          url: stream,
          quality: q + (isHls ? "" : " [MP4]"),
          headers: { Referer: "https://tau-video.xyz/" },
          subtitles: []
        });
      }
      return out;
    })
    .catch(function () { return []; });
}

function resolveSrc(src, epUrl, label) {
  var p = (src.indexOf("http") === 0 || src.indexOf("//") === 0)
    ? Promise.resolve(abs(BASE, src))
    : fetch(abs(BASE, src), { headers: { "User-Agent": UA, Referer: epUrl }, redirect: "manual" })
        .then(function (r) {
          var loc = r && r.headers && r.headers.get ? r.headers.get("location") : null;
          return loc ? abs(BASE, loc) : (r && r.url && r.url !== abs(BASE, src) ? r.url : null);
        })
        .catch(function () { return null; });
  return p.then(function (embed) {
    if (!embed) return [];
    if (/ok\.ru|odnoklassniki\.ru/i.test(embed)) return okru(embed, label);
    if (/vidmoly\./i.test(embed)) return vidmoly(embed, epUrl, label);
    if (/\.(?:m3u8|mp4)(?:\?|$)/i.test(embed)) {
      var isHls = embed.indexOf(".m3u8") !== -1;
      return [{
        name: ID,
        title: ID + " - " + label + (isHls ? " [HLS]" : " [MP4]"),
        url: isHls ? hlsNoDownload(embed) : embed,
        quality: "auto" + (isHls ? "" : " [MP4]"),
        headers: { Referer: epUrl },
        subtitles: []
      }];
    }
    return [];
  }).catch(function () { return []; });
}

function resolveStreamUrl(srv, epUrl, label) {
  var isMp4 = srv.type === "mailru" || String(srv.streamUrl || "").indexOf("mp4=1") !== -1;
  if (isMp4 && srv.qualitiesUrl) {
    return text(abs(BASE, srv.qualitiesUrl), { referer: epUrl })
      .then(function (body) {
        var qs = (JSON.parse(body) || {}).qualities || [];
        var out = [];
        for (var i = 0; i < qs.length; i++) {
          var item = qs[i] || {};
          var u = String(item.url || "").replace(/\\\//g, "/");
          if (!u) continue;
          var q = String(item.label || item.q || "auto");
          out.push({
            name: ID,
            title: ID + " - " + label + " " + q + " [MP4]",
            url: abs(BASE, u),
            quality: q + " [MP4]",
            headers: { Referer: epUrl },
            subtitles: []
          });
        }
        return out;
      })
      .catch(function () { return []; });
  }
  var u = abs(BASE, srv.streamUrl);
  return fetch(u, { headers: { "User-Agent": UA, Referer: epUrl } })
    .then(function (r) {
      if (!r || !r.ok) return [];
      return [{
        name: ID,
        title: ID + " - " + label + (isMp4 ? " [MP4]" : " [HLS]"),
        url: isMp4 ? u : hlsNoDownload(u),
        quality: "auto" + (isMp4 ? " [MP4]" : ""),
        headers: { Referer: epUrl },
        subtitles: []
      }];
    })
    .catch(function () { return []; });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var wanted = "";
  var epUrl = null;

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      wanted = title;
      if (!title) return null;
      return text(BASE + "/api/search/suggest/?q=" + encodeURIComponent(title), { referer: BASE + "/" })
        .then(function (body) {
          var data = JSON.parse(body);
          var hit = pickSuggest(data.animes, wanted);
          if (hit) return hit;
          var cleaned = wanted.replace(/[:\-–—]/g, " ").replace(/\s+/g, " ").trim();
          if (!cleaned || cleaned === wanted) return null;
          return text(BASE + "/api/search/suggest/?q=" + encodeURIComponent(cleaned), { referer: BASE + "/" })
            .then(function (b2) { return pickSuggest((JSON.parse(b2) || {}).animes, wanted); });
        });
    })
    .then(function (hit) {
      if (!hit) return null;
      return text(hit);
    })
    .then(function (detail) {
      if (!detail) return null;
      epUrl = findEpisode(detail, want, wantEp);
      if (!epUrl) return null;
      return text(epUrl);
    })
    .then(function (epHtml) {
      if (!epHtml) return [];
      var blob = first(/var servers\s*=\s*JSON\.parse\(atob\("([^"]+)"\)\)/, epHtml);
      if (!blob) return [];
      var servers = JSON.parse(unbase64(blob));
      var jobs = [];
      for (var i = 0; i < (servers || []).length && jobs.length < 8; i++) {
        var srv = servers[i] || {};
        var tag = langTag(srv);
        var baseLabel = String(srv.label || srv.type || "Kaynak");
        var label = tag ? baseLabel + " " + tag : baseLabel;
        if (srv.resolveUrl) jobs.push(resolveTau(srv.resolveUrl, epUrl, label));
        else if (srv.streamUrl) jobs.push(resolveStreamUrl(srv, epUrl, label));
        else if (srv.src) jobs.push(resolveSrc(srv.src, epUrl, label));
      }
      return Promise.all(jobs).then(function (lists) {
        var out = [];
        var seen = {};
        for (var a = 0; a < lists.length; a++) {
          for (var b = 0; b < lists[a].length; b++) {
            var r = lists[a][b];
            if (r && r.url && !seen[r.url]) {
              seen[r.url] = 1;
              out.push(r);
            }
          }
        }
        return out;
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][cizgimax] " + (e && e.message));
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
