// @provider {"id":"anizm","name":"Anizm","types":["tv"],"domain":"turkanime.info","formats":["m3u8","mp4"],"sample":"tt0409591:tv:1:1","description":"Anizm (turkanime.info) Turkce altyazili anime arsivi (AnizmPlayer, PuffyTR, OkRu, Vidmoly).","verified":true,"version":"1.0.0"}
/**
 * anizm — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title (+ videos absEp) -> /arama?q= -> series -> episode
 *       -> /bolum/<epId>/cevirmen/<trId> -> /player/<videoId> (302) -> anizmplayer / puffytr / ok.ru / vidmoly.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://turkanime.info";
var ID = "Anizm";

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

function metaOf(id, mediaType, want, wantEp) {
  if (/^__alt__:/.test(String(id))) {
    return Promise.resolve({
      title: decodeURIComponent(String(id).slice(8)),
      absEp: want === 1 ? wantEp : 0
    });
  }
  var kind = mediaType === "tv" ? "series" : "movie";
  return text("https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json")
    .then(function (body) {
      var meta = JSON.parse(body).meta || {};
      var videos = (meta.videos || []).filter(function (v) { return v && Number(v.season) > 0; });
      videos.sort(function (a, b) {
        return (Number(a.season) - Number(b.season)) || (Number(a.episode || a.number) - Number(b.episode || b.number));
      });
      var absEp = want === 1 ? wantEp : 0;
      for (var i = 0; i < videos.length; i++) {
        var v = videos[i];
        if (Number(v.season) === want && Number(v.episode || v.number) === wantEp) {
          absEp = i + 1;
          break;
        }
      }
      return { title: String(meta.name || "").trim(), absEp: absEp };
    })
    .catch(function () {
      return { title: "", absEp: want === 1 ? wantEp : 0 };
    });
}

function pickSeries(html, title, wantSeason) {
  var want = fold(title);
  if (!want) return null;
  var re = /<article class="v3-catalog-card">([\s\S]*?)<\/article>/gi;
  var m;
  var best = null;
  var bestScore = -1;
  while ((m = re.exec(html || "")) !== null) {
    var block = m[1];
    var href = first(/<a[^>]+class="[^"]*v3-catalog-poster[^"]*"[^>]+href="([^"]+)"/, block);
    var label = first(/<h2[^>]*>\s*<a[^>]+>([^<]+)<\/a>/, block) || "";
    if (!href || !label) continue;
    var have = fold(label);
    var score = 0;
    if (have === want) score = 100;
    else if (have.indexOf(want + " ") === 0) score = 60;
    if (!score) continue;
    if (wantSeason > 1 && have.indexOf("season " + wantSeason) !== -1) score += 25;
    if (score > bestScore) {
      bestScore = score;
      best = abs(BASE, href);
    }
  }
  return bestScore > 0 ? best : null;
}

function findEpisode(detailHtml, wantEp, absEp) {
  var grid = first(/class="v3-anime-episode-grid"([\s\S]*?)<\/div>\s*<\/section>/, detailHtml) || detailHtml;
  var re = /<a[^>]+href="([^"]+)"[^>]*>/gi;
  var m;
  var map = {};
  while ((m = re.exec(grid || "")) !== null) {
    var tag = m[0];
    var num = first(/data-episode-number="(\d+)"/, tag) || first(/-(\d+)-bolum/, m[1]);
    if (!num) continue;
    var n = parseInt(num, 10);
    if (n > 0 && !map[n]) map[n] = abs(BASE, m[1]);
  }
  if (map[wantEp]) return map[wantEp];
  if (absEp > 0 && map[absEp]) return map[absEp];
  return null;
}

function redirectTarget(playerUrl, referer) {
  return fetch(playerUrl, { headers: { "User-Agent": UA, Referer: referer }, redirect: "manual" })
    .then(function (r) {
      var loc = r && r.headers && r.headers.get ? r.headers.get("location") : null;
      if (loc && loc !== playerUrl) return abs(playerUrl, loc);
      if (r && r.url && r.url !== playerUrl) return r.url;
      return null;
    })
    .catch(function () { return null; })
    .then(function (target) {
      if (target) return target;
      return fetch(playerUrl, { headers: { "User-Agent": UA, Referer: referer } })
        .then(function (r) { return r && r.url && r.url !== playerUrl ? r.url : null; })
        .catch(function () { return null; });
    });
}

function anizmPlayer(embed, referer, label) {
  var hash = String(embed || "").split("/").pop().split("?")[0];
  var origin = first(/^(https?:\/\/[^/]+)/, embed);
  if (!hash || !origin) return Promise.resolve([]);
  return text(origin + "/player/index.php?data=" + encodeURIComponent(hash) + "&do=getVideo", {
    method: "POST",
    referer: embed,
    origin: origin,
    headers: { "X-Requested-With": "XMLHttpRequest", "Content-Type": "application/x-www-form-urlencoded" },
    body: "hash=" + encodeURIComponent(hash) + "&r=" + encodeURIComponent(referer)
  }).then(function (body) {
    var stream = String((JSON.parse(body) || {}).videoSource || "").replace(/\\\//g, "/").trim();
    if (!/^https?:/.test(stream)) return [];
    return [{
      name: ID,
      title: ID + " - " + label + " [HLS]",
      url: hlsNoDownload(stream),
      quality: "auto",
      headers: { Referer: origin + "/", Accept: "*/*" },
      subtitles: []
    }];
  }).catch(function () { return []; });
}

function puffytr(target, label) {
  var origin = target.split("/watch/")[0];
  var slug = target.split("/").pop().split("?")[0];
  if (!slug || origin === target) return Promise.resolve([]);
  return Promise.resolve([{
    name: ID,
    title: ID + " - " + label + " [HLS]",
    url: hlsNoDownload(origin + "/stream/" + slug + "/master.txt"),
    quality: "auto",
    headers: { Referer: target, Accept: "*/*" },
    subtitles: []
  }]);
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
      headers: { Referer: ref },
      subtitles: []
    }];
  }).catch(function () { return []; });
}

function resolveMirror(videoId, label, epUrl) {
  var playerUrl = BASE + "/player/" + videoId;
  return redirectTarget(playerUrl, epUrl).then(function (target) {
    if (!target) return [];
    if (target.indexOf("anizmplayer.com") !== -1) return anizmPlayer(target, epUrl, label);
    if (target.indexOf("puffytr") !== -1) return puffytr(target, label);
    if (/ok\.ru|odnoklassniki\.ru/i.test(target)) return okru(target, label);
    if (/vidmoly\./i.test(target)) return vidmoly(target, epUrl, label);
    if (/\.(?:m3u8|mp4)(?:\?|$)/i.test(target)) {
      var isHls = target.indexOf(".m3u8") !== -1;
      return [{
        name: ID,
        title: ID + " - " + label + (isHls ? " [HLS]" : " [MP4]"),
        url: isHls ? hlsNoDownload(target) : target,
        quality: "auto" + (isHls ? "" : " [MP4]"),
        headers: { Referer: epUrl },
        subtitles: []
      }];
    }
    return [];
  }).catch(function () { return []; });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var absEp = want === 1 ? wantEp : 0;
  var wanted = "";
  var seriesUrl = null;
  var epUrl = null;

  return metaOf(tmdbId, mediaType, want, wantEp)
    .then(function (info) {
      wanted = info.title;
      absEp = info.absEp || absEp;
      if (!wanted) return null;
      return text(BASE + "/arama?q=" + encodeURIComponent(wanted));
    })
    .then(function (search) {
      if (!search) return null;
      seriesUrl = pickSeries(search, wanted, want);
      if (!seriesUrl) return null;
      return text(seriesUrl);
    })
    .then(function (detail) {
      if (!detail) return null;
      epUrl = findEpisode(detail, wantEp, absEp);
      if (!epUrl) return null;
      return text(epUrl, { referer: seriesUrl });
    })
    .then(function (epHtml) {
      if (!epHtml) return [];
      var episodeId = first(/data-episode-id="(\d+)"/, epHtml);
      if (!episodeId) return [];
      var trs = [];
      var seenTr = {};
      var re = /<button[^>]+data-v3-translator[^>]*>/gi;
      var m;
      while ((m = re.exec(epHtml)) !== null && trs.length < 2) {
        var tid = first(/data-translator-id="(\d+)"/, m[0]);
        if (!tid || seenTr[tid]) continue;
        seenTr[tid] = 1;
        var tname = first(/title="([^"]*)"/, m[0]) || "";
        trs.push({ id: tid, name: tname === "-" ? "" : tname.trim() });
      }
      if (!trs.length) return [];
      return Promise.all(trs.map(function (tr) {
        return text(BASE + "/bolum/" + episodeId + "/cevirmen/" + tr.id, {
          referer: epUrl,
          headers: { "X-Requested-With": "XMLHttpRequest" }
        }).then(function (body) {
          var videos = (JSON.parse(body) || {}).videos || [];
          var list = [];
          for (var i = 0; i < videos.length && i < 4; i++) {
            var v = videos[i] || {};
            if (!v.id) continue;
            var sname = String(v.name || "Video").trim();
            list.push({ id: v.id, label: tr.name ? sname + " · " + tr.name : sname });
          }
          return list;
        }).catch(function () { return []; });
      })).then(function (perTr) {
        var mirrors = [];
        for (var rank = 0; rank < 4 && mirrors.length < 6; rank++) {
          for (var t = 0; t < perTr.length && mirrors.length < 6; t++) {
            if (perTr[t][rank]) mirrors.push(perTr[t][rank]);
          }
        }
        return Promise.all(mirrors.map(function (mir) {
          return resolveMirror(mir.id, mir.label, epUrl);
        })).then(function (lists) {
          var out = [];
          var seen = {};
          for (var i = 0; i < lists.length; i++) {
            for (var j = 0; j < lists[i].length; j++) {
              var r = lists[i][j];
              if (r && r.url && !seen[r.url]) {
                seen[r.url] = 1;
                out.push(r);
              }
            }
          }
          return out;
        });
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][anizm] " + (e && e.message));
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
var __subOnly = true;
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
