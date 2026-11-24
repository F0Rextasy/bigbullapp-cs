// @provider {"id":"animeler","name":"Animeler","types":["tv"],"domain":"animeler.live","formats":["m3u8","mp4"],"sample":"tt0409591:tv:1:1","description":"Animeler Turkce altyazili anime arsivi (makisekurisu HLS, ok.ru, mail.ru, vidmoly).","verified":true,"version":"1.0.0"}
/**
 * animeler — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title (+ videos absEp) -> /filtre?search= -> /<slug> -> /<slug>/bolum-<n>
 *       -> makisekurisu.site / ok.ru / my.mail.ru / vidmoly / sibnet.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://animeler.live";
var ID = "Animeler";

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
  var artRe = /<article[^>]*class="mac"([^>]*)>([\s\S]*?)<\/article>/gi;
  var m;
  var best = null;
  var bestScore = -1;
  while ((m = artRe.exec(html || "")) !== null) {
    var attrs = m[1];
    var body = m[2];
    var href = first(/href="([^"]+)"/, body);
    if (!href) continue;
    var path = href.split("?")[0];
    if (!path || path.indexOf("/filtre") === 0 || path.indexOf("/genre/") === 0) continue;
    var cands = [
      first(/data-title-main="([^"]*)"/, attrs),
      first(/data-title-en="([^"]*)"/, attrs),
      first(/data-title-jp="([^"]*)"/, attrs),
      first(/title="([^"]*)"/, body)
    ];
    var score = 0;
    for (var i = 0; i < cands.length; i++) {
      var raw = String(cands[i] || "").replace(/\s+\d+\.\s*B[oö]l[uü]m.*$/i, "").trim();
      var have = fold(raw);
      if (!have) continue;
      if (have === want) score = Math.max(score, 100);
      else if (have.indexOf(want + " ") === 0) score = Math.max(score, 60);
    }
    if (!score) continue;
    if (wantSeason > 1 && fold(cands[0] || "").indexOf("season " + wantSeason) !== -1) score += 25;
    if (score > bestScore) {
      bestScore = score;
      best = abs(BASE, href);
    }
  }
  return bestScore > 0 ? best : null;
}

function findEpisode(detailHtml, seriesUrl, wantEp, absEp) {
  var seriesPath = seriesUrl.replace(BASE, "").split("?")[0].replace(/\/+$/, "");
  var re = /href="([^"]*\/bolum-(\d+)[^"]*)"/g;
  var m;
  var map = {};
  while ((m = re.exec(detailHtml || "")) !== null) {
    if (m[1].indexOf(seriesPath + "/bolum-") === -1) continue;
    var n = parseInt(m[2], 10);
    if (!map[n]) map[n] = abs(BASE, m[1]);
  }
  if (map[wantEp]) return map[wantEp];
  if (absEp > 0 && map[absEp]) return map[absEp];
  return null;
}

function makise(embed) {
  var id = String(embed || "").split("/").pop().split("?")[0];
  if (!id) return Promise.resolve([]);
  return text("https://makisekurisu.site/api/player/v1/videos/" + encodeURIComponent(id), {
    referer: embed,
    headers: { Accept: "application/json" }
  }).then(function (body) {
    var master = (JSON.parse(body) || {}).master;
    if (!master || !/^https?:/.test(master)) return [];
    return [{
      name: ID,
      title: ID + " - Makise [HLS]",
      url: hlsNoDownload(master),
      quality: "auto",
      headers: { Referer: embed },
      subtitles: []
    }];
  }).catch(function () { return []; });
}

function okru(embed) {
  var id = first(/(?:videoembed|video)\/(\d+)/, embed);
  if (!id) return Promise.resolve([]);
  return text("https://ok.ru/dk?cmd=videoPlayerMetadata&mid=" + id, {
    method: "POST",
    headers: { "User-Agent": "Mozilla/5.0 (Linux; Android 13)", Referer: "https://ok.ru/" }
  }).then(function (body) {
    var qmap = { lowest: "240p", low: "360p", sd: "480p", hd: "720p", full: "1080p" };
    var vids = JSON.parse(body).videos || [];
    var out = [];
    for (var i = 0; i < vids.length; i++) {
      var v = vids[i];
      if (!v || v.disallowed || /^mobile$/i.test(v.name || "")) continue;
      var u = String(v.url || "").replace(/\\\//g, "/");
      if (!/^http/.test(u)) continue;
      var q = qmap[String(v.name || "").toLowerCase()] || "auto";
      out.push({
        name: ID,
        title: ID + " - Ok.Ru " + q + " [MP4]",
        url: u,
        quality: q + " [MP4]",
        headers: { Referer: "https://ok.ru/", "User-Agent": "Mozilla/5.0 (Linux; Android 13)" },
        subtitles: []
      });
    }
    return out.reverse();
  }).catch(function () { return []; });
}

function mailru(embed) {
  var id = first(/\/video\/embed\/(\d+)/, embed);
  if (!id) return Promise.resolve([]);
  return text("https://my.mail.ru/+/video/meta/" + id, {
    referer: "https://my.mail.ru/video/embed/" + id
  }).then(function (body) {
    var vids = JSON.parse(body).videos || [];
    var out = [];
    for (var i = 0; i < vids.length; i++) {
      var v = vids[i] || {};
      var u = String(v.url || "");
      if (u.indexOf("//") === 0) u = "https:" + u;
      if (!/^https?:/.test(u)) continue;
      var q = String(v.key || "auto");
      out.push({
        name: ID,
        title: ID + " - Mail.Ru " + q + " [MP4]",
        url: u,
        quality: q + " [MP4]",
        headers: { Referer: "https://my.mail.ru/" },
        subtitles: []
      });
    }
    return out.reverse();
  }).catch(function () { return []; });
}

function vidmoly(embed, referer) {
  var target = embed.replace(/\/\/(?:www\.)?vidmoly\.(?:to|me|biz|sx|org)\//i, "//vidmoly.net/");
  return text(target, { referer: referer, headers: { "Sec-Fetch-Dest": "iframe" } }).then(function (html) {
    var m3u8 = first(/sources:\s*\[\s*\{[^}]*?file:\s*["']([^"']+\.m3u8[^"']*)["']/, html)
      || first(/file:\s*["'](https?:\/\/[^"']+\.m3u8[^"']*)["']/, html);
    if (!m3u8) return [];
    var ref = (first(/^(https?:\/\/[^/]+)/, target) || "https://vidmoly.net") + "/";
    return [{
      name: ID,
      title: ID + " - Vidmoly [HLS]",
      url: hlsNoDownload(m3u8.replace(/\\\//g, "/")),
      quality: "auto",
      headers: { Referer: ref },
      subtitles: []
    }];
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
      return text(BASE + "/filtre?search=" + encodeURIComponent(wanted));
    })
    .then(function (search) {
      if (!search) return null;
      seriesUrl = pickSeries(search, wanted, want);
      if (!seriesUrl) return null;
      return text(seriesUrl);
    })
    .then(function (detail) {
      if (!detail) return null;
      epUrl = findEpisode(detail, seriesUrl, wantEp, absEp);
      if (!epUrl) return null;
      return text(epUrl, { referer: seriesUrl });
    })
    .then(function (page) {
      if (!page) return [];
      var raw = String(page).replace(/\\\//g, "/").replace(/\\"/g, '"');
      var jobs = [];
      var seen = {};
      function add(key, fn) {
        if (seen[key]) return;
        seen[key] = 1;
        jobs.push(fn());
      }
      var m;
      var mkRe = /https?:\/\/makisekurisu\.site\/embed\/[^"'\s<>\\]+/g;
      while ((m = mkRe.exec(raw)) !== null && jobs.length < 4) {
        (function (u) { add(u, function () { return makise(u); }); })(m[0]);
      }
      var okRe = /(?:ok\.ru|odnoklassniki\.ru)\/videoembed\/(\d+)/g;
      while ((m = okRe.exec(raw)) !== null && jobs.length < 6) {
        (function (id) { add("ok:" + id, function () { return okru("https://ok.ru/videoembed/" + id); }); })(m[1]);
      }
      var mrRe = /my\.mail\.ru\/video\/embed\/(\d+)/g;
      while ((m = mrRe.exec(raw)) !== null && jobs.length < 8) {
        (function (id) { add("mr:" + id, function () { return mailru("https://my.mail.ru/video/embed/" + id); }); })(m[1]);
      }
      var vmRe = /https?:\/\/(?:www\.)?vidmoly\.[a-z]+\/embed-[^"'\s<>\\]+/g;
      while ((m = vmRe.exec(raw)) !== null && jobs.length < 10) {
        (function (u) { add(u, function () { return vidmoly(u, epUrl); }); })(m[0]);
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
      console.log("[bb-nuvio][animeler] " + (e && e.message));
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
