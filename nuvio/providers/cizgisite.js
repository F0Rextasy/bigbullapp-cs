// @provider {"id":"cizgisite","name":"CizgiSite","types":["tv"],"domain":"www.cizgi.site","formats":["mp4","m3u8"],"sample":"tt0262153:tv:1:1","description":"CizgiSite Turkce dublajli cizgi film arsivi (VK Video MP4/HLS).","verified":true,"version":"1.0.0"}
/**
 * cizgisite — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta/alt title -> /tr listing -> show page -> episode -> vk.com video_ext / video.getEmbed.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://www.cizgi.site";
var ID = "CizgiSite";

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

function pickSeries(html, title) {
  var want = fold(title);
  if (!want) return null;
  var re = /<div class="ikon-dis"><a href="([^"]+)"[^>]*title="([^"]+)"/gi;
  var m;
  var best = null;
  var bestScore = -1;
  while ((m = re.exec(html || "")) !== null) {
    var have = fold(m[2].replace(/&#x27;/g, "'"));
    var score = have === want ? 100 : (have.indexOf(want + " ") === 0 ? 60 : 0);
    if (score > bestScore) {
      bestScore = score;
      best = abs(BASE, m[1]);
    }
  }
  return bestScore > 0 ? best : null;
}

function pickEpisode(html, wantSe, wantEp) {
  var blocks = String(html || "").split(/<article class="icerik">/i).slice(1);
  var exact = null;
  var byEp = null;
  var list = [];
  for (var i = 0; i < blocks.length; i++) {
    var b = blocks[i];
    var href = first(/<a[^>]+href="([^"]+)"/, b);
    if (!href) continue;
    var u = abs(BASE, href);
    list.push(u);
    var seStr = first(/class="sezon"[^>]*>([^<]+)/, b) || "";
    var epStr = first(/class="bolum"[^>]*>([^<]+)/, b) || "";
    var se = parseInt(seStr.replace(/\D+/g, ""), 10) || 1;
    var ep = parseInt(epStr.replace(/\D+/g, ""), 10) || parseInt(first(/\/(\d+)-/, u) || "0", 10);
    if (se === wantSe && ep === wantEp && !exact) exact = u;
    if (ep === wantEp && !byEp) byEp = u;
  }
  if (exact) return exact;
  if (byEp) return byEp;
  // Some series on cizgi.site only carry title slugs without episode numbers; match by index when S1.
  if (wantSe === 1 && wantEp >= 1 && wantEp <= list.length) {
    return list[wantEp - 1];
  }
  return null;
}

function vkExtract(frame, referer) {
  var target = frame.replace("vkvideo.ru", "vk.com");
  return text(target, { referer: referer }).then(function (html) {
    var out = [];
    var seen = {};
    var re = /"(mp4_\d+|hls|hls_fmp4)"\s*:\s*"(https?:[^"]+)"/g;
    var m;
    while ((m = re.exec(html)) !== null) {
      var u = m[2].replace(/\\\\\//g, "/").replace(/\\\//g, "/").replace(/\\u0026/g, "&");
      if (seen[u]) continue;
      seen[u] = 1;
      var isHls = m[1].indexOf("hls") === 0;
      var q = first(/mp4_(\d+)/, m[1]);
      out.push({
        name: ID,
        title: ID + " - TR Dublaj " + (q ? q + "p" : "VK") + (isHls ? " [HLS]" : " [MP4]"),
        url: isHls ? hlsNoDownload(u) : u,
        quality: (q ? q + "p" : "auto") + (isHls ? "" : " [MP4]"),
        headers: { Referer: target, "User-Agent": UA },
        subtitles: []
      });
    }
    if (out.length) return out;
    var oid = first(/[?&]oid=(-?\d+)/, target);
    var vid = first(/[?&]id=(\d+)/, target);
    var hash = first(/[?&]hash=([0-9a-fA-F]+)/, target) || "";
    var tok = first(/access_token"\s*:\s*"(anonym\.[^"]+)"/, html);
    if (!oid || !vid || !tok) return [];
    return text("https://api.vk.com/method/video.getEmbed?v=5.289&client_id=6287487", {
      method: "POST",
      origin: "https://vk.com",
      referer: "https://vk.com/",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: "owner_id=" + encodeURIComponent(oid) + "&video_id=" + encodeURIComponent(vid) +
        "&hash=" + encodeURIComponent(hash) + "&access_token=" + encodeURIComponent(tok)
    }).then(function (apiBody) {
      var files = (((JSON.parse(apiBody) || {}).response || {}).video || {}).files || {};
      var rows = [];
      for (var k in files) {
        var u = String(files[k] || "");
        if (!/^https?:/.test(u) || (!/^mp4_/.test(k) && !/^hls/.test(k))) continue;
        var isHls = k.indexOf("hls") === 0;
        var q = first(/mp4_(\d+)/, k);
        rows.push({
          name: ID,
          title: ID + " - TR Dublaj " + (q ? q + "p" : "VK") + (isHls ? " [HLS]" : " [MP4]"),
          url: isHls ? hlsNoDownload(u) : u,
          quality: (q ? q + "p" : "auto") + (isHls ? "" : " [MP4]"),
          headers: { Referer: target, "User-Agent": UA },
          subtitles: []
        });
      }
      return rows.reverse();
    });
  }).catch(function () { return []; });
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
      return text(BASE + "/tr");
    })
    .then(function (listing) {
      if (!listing) return null;
      var showUrl = pickSeries(listing, wanted);
      if (!showUrl) return null;
      return text(showUrl);
    })
    .then(function (detail) {
      if (!detail) return null;
      epUrl = pickEpisode(detail, want, wantEp);
      if (!epUrl) return null;
      return text(epUrl);
    })
    .then(function (page) {
      if (!page) return [];
      var jobs = [];
      var seen = {};
      var fRe = /(?:src|data-src)=["']([^"']*(?:vk\.com\/video_ext|video_ext\.php)[^"']*)["']/gi;
      var m;
      while ((m = fRe.exec(page)) !== null) {
        var frame = abs(epUrl, m[1]);
        if (!frame || seen[frame]) continue;
        seen[frame] = 1;
        jobs.push(vkExtract(frame, epUrl));
      }
      if (!jobs.length) {
        var dRe = /https?:\/\/[^"'\s<>]+?\.(?:mp4|m3u8)(?:\?[^"'\s<>]*)?/gi;
        while ((m = dRe.exec(page)) !== null) {
          if (seen[m[0]]) continue;
          seen[m[0]] = 1;
          var isHls = m[0].indexOf(".m3u8") !== -1;
          jobs.push(Promise.resolve([{
            name: ID,
            title: ID + " - TR Dublaj" + (isHls ? " [HLS]" : " [MP4]"),
            url: isHls ? hlsNoDownload(m[0]) : m[0],
            quality: "auto" + (isHls ? "" : " [MP4]"),
            headers: { Referer: epUrl },
            subtitles: []
          }]));
        }
      }
      return Promise.all(jobs).then(function (lists) {
        var out = [];
        var uSeen = {};
        for (var i = 0; i < lists.length; i++) {
          for (var j = 0; j < lists[i].length; j++) {
            var r = lists[i][j];
            if (r && r.url && !uSeen[r.url]) {
              uSeen[r.url] = 1;
              out.push(r);
            }
          }
        }
        return out;
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][cizgisite] " + (e && e.message));
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
