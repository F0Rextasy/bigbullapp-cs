// @provider {"id":"asyafanatikleri","name":"AsyaFanatikleri","types":["tv","movie"],"domain":"asyafanatikleri.vip","formats":["m3u8"],"sample":"tt27668559:tv:1:1","description":"AsyaFanatikleri Kore/Cin/Japon/Tayland dizileri ve filmleri.","verified":true,"version":"1.0.0"}
/**
 * asyafanatikleri — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta/alt title -> /ara?q= JSON -> /dizi/<slug> -> /sezon-N/bolum-M -> player iframes (vidmoly / playerdkorea).
 */
var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://asyafanatikleri.vip";
var ID = "AsyaFanatikleri";

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

function first(pattern, body) {
  var m = pattern.exec(String(body || ""));
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

function titleOf(id, mediaType) {
  if (/^__alt__:/.test(String(id))) return Promise.resolve(decodeURIComponent(String(id).slice(8)));
  var kind = mediaType === "tv" ? "series" : "movie";
  return text("https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json")
    .then(function (body) { return String((JSON.parse(body).meta || {}).name || "").trim(); })
    .catch(function () { return ""; });
}

function norm(value) {
  return String(value || "").toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
}

function pick(list, title, isMovie) {
  var want = norm(title);
  var best = null, bestScore = 0;
  for (var i = 0; i < list.length; i++) {
    var row = list[i];
    if (isMovie && row.type && row.type !== "movie") continue;
    if (!isMovie && row.type && row.type === "movie") continue;
    var have = norm(row.title);
    var score = have === want ? 100 : have.indexOf(want + " ") === 0 ? 80 : 0;
    if (score > bestScore) { bestScore = score; best = row; }
    if (bestScore === 100) break;
  }
  return bestScore >= 80 ? best : null;
}

function episodes(html, base) {
  var out = [], seen = {};
  var re = /href="([^"]*\/sezon-(\d+)\/bolum-(\d+))"/g, m;
  while ((m = re.exec(html)) !== null) {
    var url = abs(base, m[1]);
    if (!url || seen[url]) continue;
    seen[url] = 1;
    out.push({ url: url, season: parseInt(m[2], 10), episode: parseInt(m[3], 10) });
  }
  return out;
}

function firePlayer(embed, found) {
  var origin = first(/^(https?:\/\/[^/]+)/, embed) || "https://playerdkorea.xyz";
  var id = first(/data=([0-9a-zA-Z]+)/, embed) || String(embed).split("/").pop();
  if (!id) return Promise.resolve(found);
  var endpoint = origin + "/player/index.php?data=" + id + "&do=getVideo";
  return text(endpoint, {
    method: "POST",
    form: true,
    ajax: true,
    referer: embed,
    origin: origin,
    body: "hash=" + encodeURIComponent(id) + "&r=" + encodeURIComponent(BASE + "/")
  }).then(function (body) {
    var json = JSON.parse(body);
    var stream = String(json.securedLink || json.videoSource || "").replace(/\\\//g, "/").trim();
    if (!/^https?:/i.test(stream)) return found;
    if (!found.some(function (item) { return item.url === stream; })) {
      found.push({ url: stream, frame: origin + "/", hls: true });
    }
    return found;
  }).catch(function () { return found; });
}

function quality(item) {
  if (!item.hls) return Promise.resolve("auto");
  return text(item.url, { referer: item.frame }).then(function (body) {
    if (body.indexOf("#EXTM3U") !== 0) return null;
    var best = 0, re = /RESOLUTION=\d+x(\d+)/g, m;
    while ((m = re.exec(body)) !== null) {
      var h = parseInt(m[1], 10) || 0;
      if (h > best) best = h;
    }
    return best ? best + "p" : "auto";
  }).catch(function () { return null; });
}

function search(title) {
  return text(BASE + "/ara?q=" + encodeURIComponent(title)).then(function (body) {
    var items = JSON.parse(body).items || [], list = [];
    for (var i = 0; i < items.length; i++) {
      var it = items[i] || {};
      var url = abs(BASE, it.url || (it.slug ? "/dizi/" + it.slug : ""));
      if (!url) continue;
      list.push({ url: url, title: String(it.title || it.slug || ""), type: String(it.type || "") });
    }
    return list;
  }).catch(function () { return []; });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var isMovie = mediaType === "movie";
  var episodeUrl = null;
  var frames = [];

  return titleOf(tmdbId, mediaType).then(function (title) {
    if (!title) return null;
    return search(title).then(function (list) { return pick(list, title, isMovie); });
  }).then(function (entry) {
    if (!entry) return null;
    if (isMovie || entry.url.indexOf("/film/") !== -1) {
      episodeUrl = entry.url;
      return text(entry.url);
    }
    return text(entry.url).then(function (detail) {
      var list = episodes(detail, entry.url);
      for (var i = 0; i < list.length; i++) {
        if (list[i].season === want && list[i].episode === wantEp) { episodeUrl = list[i].url; break; }
      }
      if (!episodeUrl) return null;
      return text(episodeUrl, { referer: entry.url });
    });
  }).then(function (html) {
    if (!html) return [];
    var re = /<iframe[^>]+(?:data-src|src)="([^"]+)"/g, m;
    while ((m = re.exec(html)) !== null) {
      var frame = abs(episodeUrl, m[1]);
      if (frame && /^https?:/i.test(frame) && !/youtu/i.test(frame) && frames.indexOf(frame) === -1) frames.push(frame);
    }
    function probeFrame(frame) {
      if (frame.indexOf("playerdkorea") !== -1 || frame.indexOf("playerkorea") !== -1) return firePlayer(frame, []);
      var target = frame.replace(/\/\/(?:www\.)?vidmoly\.(?:to|me|biz|sx|org)\//i, "//vidmoly.net/");
      var isVidmoly = /vidmoly\./i.test(target);
      return text(target, { referer: episodeUrl, headers: isVidmoly ? { "Sec-Fetch-Dest": "iframe" } : null })
        .then(function (body) {
          var clean = body.replace(/\\\//g, "/");
          var re2 = /https?:\/\/[^"'\s<>\\]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>\\]*)?/g;
          var found = [], mm;
          var streamRef = isVidmoly ? (first(/^(https?:\/\/[^/]+)/, target) || "https://vidmoly.net") + "/" : target;
          while ((mm = re2.exec(clean)) !== null) {
            if (!found.some(function (it) { return it.url === mm[0]; })) {
              found.push({ url: mm[0], frame: streamRef, hls: mm[0].indexOf(".m3u8") !== -1 });
            }
          }
          return found;
        }).catch(function () { return []; });
    }
    function resolveGroup(group) {
      if (!group.length) return Promise.resolve([]);
      return Promise.all(group.map(probeFrame)).then(function (lists) {
        var merged = [];
        for (var i = 0; i < lists.length; i++) {
          for (var j = 0; j < (lists[i] || []).length; j++) {
            var c = lists[i][j];
            if (!merged.some(function (x) { return x.url === c.url; })) merged.push(c);
          }
        }
        return Promise.all(merged.map(function (item) {
          return quality(item).then(function (label) {
            if (!label) return null;
            return {
              name: ID,
              title: ID + " - " + label + (item.hls ? " [HLS]" : " [MP4]"),
              url: item.hls ? hlsNoDownload(item.url) : item.url,
              quality: label,
              headers: { Referer: item.frame },
              subtitles: []
            };
          });
        })).then(function (rows) { return rows.filter(Boolean); });
      });
    }
    var fast = [], slow = [];
    for (var i = 0; i < frames.length; i++) {
      if (frames[i].indexOf("playerdkorea") !== -1 || frames[i].indexOf("playerkorea") !== -1) slow.push(frames[i]);
      else fast.push(frames[i]);
    }
    return resolveGroup(fast).then(function (rows) { return rows.length ? rows : resolveGroup(slow); });
  }).catch(function (e) {
    console.log("[bb-nuvio][asyafanatikleri] " + (e && e.message));
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
