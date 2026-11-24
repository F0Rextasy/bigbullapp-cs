// @provider {"id":"asyamax","name":"AsyaMax","types":["tv","movie"],"domain":"asyamax.com","formats":["m3u8","mp4"],"sample":"tt39333617:tv:1:1","description":"AsyaMax Kore/Cin/Japon/Tayland dizileri ve filmleri (Vidmoly + VK + OkRu).","verified":true,"version":"1.0.0"}
/**
 * asyamax — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta/alt title -> /?s= -> series/movie page -> episode -> #singleContent iframes (vidmoly/vk/ok.ru/direct).
 */
var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://asyamax.com";
var ID = "AsyaMax";

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
  if (o.origin) headers.Origin = o.origin;
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

function cleanSiteTitle(raw) {
  return String(raw || "")
    .replace(/<[^>]+>/g, " ")
    .replace(/\s+\d+\.\s*b[oö]l[uü]m.*$/i, "")
    .replace(/\s+(?:19|20)\d{2}\s*$/, "")
    .trim();
}

function titleOf(id, mediaType) {
  if (/^__alt__:/.test(String(id))) return Promise.resolve(decodeURIComponent(String(id).slice(8)));
  var kind = mediaType === "tv" ? "series" : "movie";
  return text("https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json")
    .then(function (body) { return String((JSON.parse(body).meta || {}).name || "").trim(); })
    .catch(function () { return ""; });
}

function searchCards(html) {
  var out = [], seen = {};
  var re = /<div class="title">[\s\S]*?<a[^>]+href=["']?([^"'\s>]+)["']?[^>]*>\s*([^<]+?)\s*<\/a>/gi, m;
  while ((m = re.exec(html)) !== null) {
    var href = abs(BASE, m[1]);
    var t = m[2];
    if (!href || !t || seen[href] || /\/category\//.test(href)) continue;
    seen[href] = 1;
    out.push({ url: href, title: t.trim(), isEp: /\bb[öo]l[üu]m\b/i.test(t) });
  }
  return out;
}

function pickCandidate(list, wanted) {
  var key = norm(wanted);
  if (!key) return null;
  var best = null, bestScore = 0;
  for (var i = 0; i < list.length; i++) {
    var clean = norm(cleanSiteTitle(list[i].title));
    var score = clean === key ? 100 : clean.indexOf(key + " ") === 0 ? 70 : 0;
    if (!score) continue;
    if (!list[i].isEp) score += 20;
    if (score > bestScore) { bestScore = score; best = list[i]; }
  }
  return best;
}

function findEpisode(html, pageUrl, wantSe, wantEp) {
  var re = /<a[^>]+href="([^"]*bolum[^"]*)"[^>]*>([\s\S]{0,120}?)<\/a>/gi, m, best = null, fallback = null;
  var epRx = /^\s*(\d+)\s*\.\s*(?:sezon\s*(\d+)\s*\.\s*)?b[öo]l[üu]m/i;
  while ((m = re.exec(html)) !== null) {
    var u = abs(pageUrl, m[1]);
    var label = m[2].replace(/<[^>]+>/g, " ").trim();
    var pm = epRx.exec(label);
    if (!u || !pm) continue;
    var ep = parseInt(pm[1], 10);
    var se = pm[2] ? parseInt(pm[2], 10) : 1;
    if (ep === wantEp && se === wantSe) { best = u; break; }
    if (ep === wantEp && !fallback) fallback = u;
  }
  return best || fallback;
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
    if (out.length) return out;
    var tok = first(/access_token"\s*:\s*"(anonym\.[^"]+)/, html);
    var oid = first(/[?&]oid=(-?\d+)/, target), vid = first(/[?&]id=(\d+)/, target), hash = first(/[?&]hash=([0-9a-fA-F]+)/, target) || "";
    if (!tok || !oid || !vid) return [];
    return text("https://api.vk.com/method/video.getEmbed?v=5.289&client_id=6287487", {
      method: "POST",
      origin: "https://vk.com",
      referer: "https://vk.com/",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: "owner_id=" + encodeURIComponent(oid) + "&video_id=" + encodeURIComponent(vid) + "&hash=" + encodeURIComponent(hash) + "&access_token=" + encodeURIComponent(tok)
    }).then(function (body) {
      var files = (((JSON.parse(body).response || {}).video || {}).files) || {}, rows = [];
      for (var k in files) {
        var u = files[k];
        if (!/^http/.test(String(u || ""))) continue;
        if (k === "hls") rows.push({ name: ID, title: ID + " - VK auto [HLS]", url: hlsNoDownload(u), quality: "auto", headers: { Referer: "https://vk.com/" }, subtitles: [] });
        else if (k.indexOf("mp4_") === 0) {
          var q = k.slice(4) + "p";
          rows.push({ name: ID, title: ID + " - VK " + q + " [MP4]", url: u, quality: q, headers: { Referer: "https://vk.com/" }, subtitles: [] });
        }
      }
      return rows;
    }).catch(function () { return []; });
  }).catch(function () { return []; });
}

function extractIframeUrl(tag) {
  return first(/\bdata-src="([^"]+)"/i, tag)
    || first(/\bdata-lazy-src="([^"]+)"/i, tag)
    || first(/\bvdo-src="([^"]+)"/i, tag)
    || first(/\bsrc="([^"]+)"/i, tag);
}

function getStreams(tmdbId, mediaType, season, episode) {
  var wantSe = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var isMovie = mediaType === "movie";
  var epUrl = null;

  return titleOf(tmdbId, mediaType).then(function (title) {
    if (!title) return null;
    return Promise.all([
      text(BASE + "/category/kore-dizileri-2/").catch(function () { return ""; }),
      text(BASE + "/").catch(function () { return ""; })
    ]).then(function (pages) {
      var list = searchCards(pages[0]).concat(searchCards(pages[1]));
      var hit = pickCandidate(list, title);
      if (hit) return hit;
      return text(BASE + "/?s=" + encodeURIComponent(title)).then(function (html) {
        return pickCandidate(searchCards(html), title);
      });
    });
  }).then(function (hit) {
    if (!hit) return null;
    return text(hit.url).then(function (detail) {
      var rootUrl = first(/<a[^>]+href="([^"]+)"[^>]*>\s*Dizi Sayfası\s*<\/a>/i, detail);
      if (rootUrl && rootUrl.replace(/\/$/, "") !== hit.url.replace(/\/$/, "")) {
        return text(abs(hit.url, rootUrl)).then(function (rootHtml) {
          return { url: abs(hit.url, rootUrl), html: rootHtml };
        });
      }
      return { url: hit.url, html: detail };
    });
  }).then(function (page) {
    if (!page) return null;
    if (isMovie) { epUrl = page.url; return page.html; }
    epUrl = findEpisode(page.html, page.url, wantSe, wantEp);
    if (!epUrl) return null;
    return text(epUrl, { referer: page.url });
  }).then(function (html) {
    if (!html) return [];
    var jobs = [], seen = {}, m;
    var ifRe = /<iframe\b[^>]*>/gi;
    while ((m = ifRe.exec(html)) !== null) {
      var raw = extractIframeUrl(m[0]);
      if (!raw || /^(?:data:|javascript:)/i.test(raw) || /youtu/i.test(raw)) continue;
      var f = abs(epUrl, raw);
      if (!f || seen[f]) continue;
      seen[f] = 1;
      if (/ok\.ru|odnoklassniki/i.test(f)) jobs.push(okru(f));
      else if (/vidmoly\./i.test(f)) jobs.push(vidmoly(f, epUrl));
      else if (/vk\.com\/video_ext|vkvideo\.ru/i.test(f)) jobs.push(vk(f, epUrl));
      else if (/\.(?:m3u8|mp4)(?:\?|$)/i.test(f)) {
        var hls = f.indexOf(".m3u8") !== -1;
        jobs.push(Promise.resolve([{ name: ID, title: ID + " - Embed" + (hls ? " [HLS]" : " [MP4]"), url: hls ? hlsNoDownload(f) : f, quality: "auto", headers: { Referer: epUrl }, subtitles: [] }]));
      }
    }
    return Promise.all(jobs).then(function (lists) {
      var out = [], uSeen = {};
      for (var i = 0; i < lists.length; i++) {
        for (var j = 0; j < (lists[i] || []).length; j++) {
          var row = lists[i][j];
          if (row && row.url && !uSeen[row.url]) { uSeen[row.url] = 1; out.push(row); }
        }
      }
      return out;
    });
  }).catch(function (e) {
    console.log("[bb-nuvio][asyamax] " + (e && e.message));
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
