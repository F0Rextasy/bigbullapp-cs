// @provider {"id":"diziasia","name":"Diziasia","types":["tv","movie"],"domain":"diziasia.com","formats":["m3u8","mp4"],"sample":"tt34440854:tv:1:1","description":"DiziAsia Asya dizileri ve filmleri (OkRu + VK + Vidmoly).","verified":true,"version":"1.0.0"}
/**
 * diziasia — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta/alt title -> /ajax/search?q= (fallback /arama/<q>) -> series/movie -> .accordion-item episodes -> POST /ajax/embed {id} -> ok.ru / vk / vidmoly.
 */
var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://diziasia.com";
var ID = "Diziasia";

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
  if (o.origin) headers.Origin = o.origin;
  if (o.ajax) headers["X-Requested-With"] = "XMLHttpRequest";
  if (o.form) headers["Content-Type"] = "application/x-www-form-urlencoded";
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

function norm(value) {
  return String(value || "")
    .replace(/[\u0131\u0130I]/g, "i").replace(/[\u015f\u015e]/g, "s").replace(/[\u011f\u011e]/g, "g")
    .replace(/[\u00e7\u00c7]/g, "c").replace(/[\u00f6\u00d6]/g, "o").replace(/[\u00fc\u00dc]/g, "u")
    .toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
}

function titleOf(id, mediaType) {
  if (/^__alt__:/.test(String(id))) return Promise.resolve(decodeURIComponent(String(id).slice(8)));
  var kind = mediaType === "tv" ? "series" : "movie";
  return text("https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json")
    .then(function (body) { return String((JSON.parse(body).meta || {}).name || "").trim(); })
    .catch(function () { return ""; });
}

function search(title, isMovie) {
  function parseAjax(html) {
    var out = [], re = /<a[^>]+class="[^"]*ss-item[^"]*"[^>]+href="([^"]+)"[\s\S]*?<(?:div|span)[^>]*class="[^"]*ss-title[^"]*"[^>]*>([^<]+)<\/(?:div|span)>/gi, m;
    while ((m = re.exec(html)) !== null) {
      var u = abs(BASE, m[1]);
      if (u) out.push({ url: u, title: m[2].trim() });
    }
    return out;
  }
  function parseArama(html) {
    var out = [], re = /<a[^>]+href="([^"]+)"[^>]*class="card[^"]*"[^>]*>([\s\S]{0,900}?)<\/a>/gi, m;
    while ((m = re.exec(html)) !== null) {
      var u = abs(BASE, m[1]);
      var t = first(/<h3[^>]*class="[^"]*title[^"]*"[^>]*>([^<]+)<\/h3>/, m[2]) || first(/alt="([^"]+)"/, m[2]);
      if (u && t) out.push({ url: u, title: t.trim() });
    }
    return out;
  }
  return text(BASE + "/ajax/search?q=" + encodeURIComponent(title)).then(parseAjax).catch(function () { return []; })
    .then(function (list) {
      if (list.length) return list;
      return text(BASE + "/arama/" + encodeURIComponent(title)).then(parseArama).catch(function () { return []; });
    }).then(function (list) {
      var key = norm(title), best = null, bestScore = 0;
      for (var i = 0; i < list.length; i++) {
        var rowIsMovie = list[i].url.indexOf("/film/") !== -1;
        if (isMovie !== rowIsMovie) continue;
        var have = norm(list[i].title);
        var score = have === key ? 100 : have.indexOf(key + " ") === 0 ? 75 : 0;
        if (score > bestScore) { bestScore = score; best = list[i]; }
      }
      return best;
    });
}

function findEpisode(html, pageUrl, wantSe, wantEp) {
  var blocks = html.split(/<div[^>]+class="[^"]*accordion-item[^"]*"/i);
  for (var i = 1; i < blocks.length; i++) {
    var head = first(/class="[^"]*accordion-header[^"]*"[^>]*>([\s\S]*?)<\/div>/i, blocks[i]) || "";
    var se = parseInt(first(/(\d+)/, head.replace(/<[^>]+>/g, " ")) || "1", 10);
    if (se !== wantSe) continue;
    var re = /<a[^>]+class="[^"]*episode[^"]*"[^>]+href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/gi, m;
    while ((m = re.exec(blocks[i])) !== null) {
      var ep = parseInt(first(/(\d+)/, m[2].replace(/<[^>]+>/g, " ")) || "0", 10);
      if (ep === wantEp) return abs(pageUrl, m[1]);
    }
  }
  var fallback = first(new RegExp('href="([^"]*sezon-' + wantSe + '[^"]*bolum-' + wantEp + '[^"]*)"', "i"), html)
    || first(new RegExp('href="([^"]*-' + wantSe + '-sezon-' + wantEp + '-bolum[^"]*)"', "i"), html);
  return fallback ? abs(pageUrl, fallback) : null;
}

function okru(embed) {
  var id = first(/(?:videoembed|video)\/(\d+)/, embed);
  if (!id) return Promise.resolve([]);
  return text("https://ok.ru/dk?cmd=videoPlayerMetadata&mid=" + id, {
    method: "POST",
    headers: { "User-Agent": "Mozilla/5.0 (Linux; Android 13)", Referer: "https://ok.ru/" }
  }).then(function (body) {
    var qmap = { lowest: "240p", low: "360p", sd: "480p", hd: "720p", full: "1080p" };
    var vids = JSON.parse(body).videos || [], out = [];
    for (var i = 0; i < vids.length; i++) {
      var v = vids[i];
      if (!v || v.disallowed || /^mobile$/i.test(v.name || "")) continue;
      var u = String(v.url || "").replace(/\\\//g, "/");
      if (!/^http/.test(u)) continue;
      var q = qmap[String(v.name || "").toLowerCase()] || "auto";
      out.push({ name: ID, title: ID + " - Ok.Ru " + q + " [MP4]", url: u, quality: q, headers: { Referer: "https://ok.ru/" }, subtitles: [] });
    }
    return out.reverse();
  }).catch(function () { return []; });
}

function vk(embed, referer) {
  var target = embed.replace("vkvideo.ru", "vk.com");
  return text(target, { referer: referer }).then(function (html) {
    var out = [], re = /"(mp4_\d+|hls)":"(https:[^"]+)"/g, m;
    while ((m = re.exec(html)) !== null) {
      var u = m[2].replace(/\\\//g, "/").replace(/\\u0026/g, "&");
      var isHls = m[1] === "hls";
      var q = isHls ? "auto" : m[1].replace("mp4_", "") + "p";
      out.push({ name: ID, title: ID + " - VK " + q + (isHls ? " [HLS]" : " [MP4]"), url: isHls ? hlsNoDownload(u) : u, quality: q, headers: { Referer: "https://vk.com/" }, subtitles: [] });
    }
    return out;
  }).catch(function () { return []; });
}

function vidmoly(embed, referer) {
  var target = embed.replace(/\/\/(?:www\.)?vidmoly\.(?:to|me|biz|sx|org)\//i, "//vidmoly.net/");
  return text(target, { referer: referer, headers: { "Sec-Fetch-Dest": "iframe" } }).then(function (html) {
    var m3u8 = first(/sources:\s*\[\s*\{\s*file:\s*["']([^"']+\.m3u8[^"']*)["']/, html)
      || first(/file:\s*["'](https?:\/\/[^"']+\.m3u8[^"']*)["']/, html);
    if (!m3u8) return [];
    var ref = (first(/^(https?:\/\/[^/]+)/, target) || "https://vidmoly.net") + "/";
    return text(m3u8, { referer: ref }).then(function (pl) {
      if (pl.indexOf("#EXTM3U") !== 0) return [];
      var best = 0, re = /RESOLUTION=\d+x(\d+)/g, m;
      while ((m = re.exec(pl)) !== null) { var h = parseInt(m[1], 10) || 0; if (h > best) best = h; }
      var q = best ? best + "p" : "auto";
      return [{ name: ID, title: ID + " - Vidmoly " + q + " [HLS]", url: hlsNoDownload(m3u8), quality: q, headers: { Referer: ref }, subtitles: [] }];
    });
  }).catch(function () { return []; });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var wantSe = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var isMovie = mediaType === "movie";
  var epUrl = null;

  return titleOf(tmdbId, mediaType).then(function (title) {
    if (!title) return null;
    return search(title, isMovie);
  }).then(function (hit) {
    if (!hit) return null;
    if (isMovie || hit.url.indexOf("/film/") !== -1) {
      epUrl = hit.url;
      return text(hit.url);
    }
    return text(hit.url).then(function (detail) {
      epUrl = findEpisode(detail, hit.url, wantSe, wantEp);
      if (!epUrl) return null;
      return text(epUrl, { referer: hit.url });
    });
  }).then(function (html) {
    if (!html) return [];
    var ids = [], re = /btn-stream[^>]+data-id="([^"]+)"/gi, m;
    while ((m = re.exec(html)) !== null) {
      var id = m[1].trim();
      if (id && ids.indexOf(id) === -1) ids.push(id);
    }
    return Promise.all(ids.slice(0, 6).map(function (id) {
      return text(BASE + "/ajax/embed", {
        method: "POST",
        ajax: true,
        form: true,
        referer: epUrl,
        origin: BASE,
        body: "id=" + encodeURIComponent(id)
      }).then(function (resHtml) {
        var src = first(/<iframe[^>]+(?:data-src|src)="([^"]+)"/i, resHtml);
        if (!src) return [];
        var embed = abs(BASE, src);
        if (/ok\.ru|odnoklassniki/i.test(embed)) return okru(embed);
        if (/vk\.com\/video_ext|vkvideo\.ru/i.test(embed)) return vk(embed, epUrl);
        if (/vidmoly\./i.test(embed)) return vidmoly(embed, epUrl);
        return [];
      }).catch(function () { return []; });
    })).then(function (lists) {
      var out = [], seen = {};
      for (var i = 0; i < lists.length; i++) {
        for (var j = 0; j < lists[i].length; j++) {
          if (!seen[lists[i][j].url]) { seen[lists[i][j].url] = 1; out.push(lists[i][j]); }
        }
      }
      return out;
    });
  }).catch(function (e) {
    console.log("[bb-nuvio][diziasia] " + (e && e.message));
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
