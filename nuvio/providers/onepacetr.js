// @provider {"id":"onepacetr","name":"Onepacetr","types":["tv"],"domain":"onepacetr.net","formats":["mp4","m3u8"],"sample":"tt0388629:tv:1:1","description":"One Pace TR (onepacetr.net) Turkce altyazili One Piece fan-edit arsivi (Google Drive, Vidmoly, OkRu).","verified":true,"version":"1.0.0"}
/**
 * onepacetr — Nuvio provider (bb-nuvio).
 * Flow: Verify One Piece (tt0388629 / "One Piece") -> Strapi /api/seasons -> match season/ep or anime absEp
 *       -> Strapi /api/episodes/<slug> -> Google Drive / Vidmoly / OkRu / direct MP4.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://onepacetr.net";
var API = "https://onepacetradmin-v3-4f0db9f5d700.herokuapp.com/api";
var AUTH = "Bearer 1fe5d72626ea6144e9d685128cfc40a54c303573432381c8943fc497bb2203f693922fa8afaa872168eef456bf3aa7802507fb2a526582a642241acc0665b30861c60ba344f5d8b925b7ebb75073d9419dfbf9be4b3420427bd22302889792973faaf01c8f1d5bd40105187d22aa9f5595b00a8e2700b94614678cc3303a7ac3";
var ID = "Onepacetr";

var JS_ONLY = ["abyssplayer", "short.icu", "short.ink", "gett.su", "listeamed.net", "upns.live"];
var HIDDEN = { gettsu: 1, vidguard: 1 };

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

function apiJson(url) {
  return text(url, { headers: { Authorization: AUTH } }).then(function (b) { return JSON.parse(b); });
}

function first(re, body) {
  var m = re.exec(String(body || ""));
  return m ? m[1] : null;
}

function hlsNoDownload(url) {
  var u = String(url || "");
  if (/\.m3u8(\?|$)/i.test(u) || u.indexOf("#") !== -1) return u;
  return u + "#.m3u8";
}

function fold(value) {
  return String(value || "").toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
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

function animeMatches(spec, target) {
  if (!spec || target <= 0) return false;
  var parts = String(spec).split(",");
  for (var i = 0; i < parts.length; i++) {
    var p = parts[i].trim();
    var range = /^(\d+)\s*-\s*(\d+)$/.exec(p);
    if (range) {
      var a = parseInt(range[1], 10);
      var b = parseInt(range[2], 10);
      if (target >= a && target <= b) return true;
    } else if (/^\d+$/.test(p) && parseInt(p, 10) === target) {
      return true;
    }
  }
  return false;
}

function gdrive(embed) {
  var id = first(/\/file\/d\/([^/]+)/, embed);
  if (!id) return Promise.resolve([]);
  var dl = "https://drive.usercontent.google.com/download?id=" + id + "&export=download&confirm=t";
  return fetch(dl, { headers: { "User-Agent": UA, Range: "bytes=0-0" } })
    .then(function (r) {
      if (!r || r.status < 200 || r.status >= 300) return [];
      var ct = (r.headers && r.headers.get && r.headers.get("content-type")) || "";
      if (ct.indexOf("text/") === 0) return [];
      return [{
        name: ID,
        title: ID + " - Google Drive 1080p [MP4]",
        url: dl,
        quality: "1080p [MP4]",
        headers: { Referer: embed },
        subtitles: []
      }];
    })
    .catch(function () { return []; });
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
    for (var i = vids.length - 1; i >= 0; i--) {
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
    return out;
  }).catch(function () { return []; });
}

function vidmoly(embed) {
  var target = embed.replace(/\/\/(?:www\.)?vidmoly\.(?:to|me|biz|sx|org)\//i, "//vidmoly.net/");
  return text(target, { referer: BASE + "/", headers: { "Sec-Fetch-Dest": "iframe" } }).then(function (html) {
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

  return metaOf(tmdbId, mediaType, want, wantEp)
    .then(function (info) {
      absEp = info.absEp || absEp;
      var isOnePiece = String(tmdbId) === "tt0388629" || fold(info.title) === "one piece";
      if (!isOnePiece) return null;
      return apiJson(
        API + "/seasons?pagination[page]=1&pagination[pageSize]=100&sort[0]=number:asc" +
          "&populate[episodes][fields][0]=number&populate[episodes][fields][1]=slug&populate[episodes][fields][2]=anime"
      );
    })
    .then(function (body) {
      if (!body || !body.data) return null;
      var seasons = body.data;
      var exactSlug = null;
      var animeSlug = null;
      for (var i = 0; i < seasons.length; i++) {
        var s = seasons[i] || {};
        var eps = s.episodes || [];
        for (var j = 0; j < eps.length; j++) {
          var ep = eps[j] || {};
          if (!ep.slug) continue;
          if (Number(s.number) === want && Number(ep.number) === wantEp && !exactSlug) exactSlug = ep.slug;
          if (absEp > 0 && animeMatches(ep.anime, absEp) && !animeSlug) animeSlug = ep.slug;
        }
      }
      var slug = exactSlug || animeSlug;
      if (!slug) return null;
      return apiJson(API + "/episodes/" + encodeURIComponent(slug));
    })
    .then(function (epData) {
      if (!epData || !epData.data) return [];
      var players = epData.data.players || [];
      var jobs = [];
      var seen = {};
      for (var i = 0; i < players.length; i++) {
        var p = players[i] || {};
        var embed = String(p.url || "").trim();
        if (!embed || seen[embed]) continue;
        seen[embed] = 1;
        if (HIDDEN[String(p.name || "").toLowerCase()]) continue;
        var skip = false;
        for (var j = 0; j < JS_ONLY.length; j++) {
          if (embed.indexOf(JS_ONLY[j]) !== -1) { skip = true; break; }
        }
        if (skip) continue;
        if (embed.indexOf("drive.google.com") !== -1) jobs.push(gdrive(embed));
        else if (/ok\.ru|odnoklassniki\.ru/i.test(embed)) jobs.push(okru(embed));
        else if (/vidmoly\./i.test(embed)) jobs.push(vidmoly(embed));
        else if (/\.(?:mp4|m3u8)(?:\?|$)/i.test(embed)) {
          var isHls = embed.indexOf(".m3u8") !== -1;
          jobs.push(Promise.resolve([{
            name: ID,
            title: ID + " - Direct" + (isHls ? " [HLS]" : " [MP4]"),
            url: isHls ? hlsNoDownload(embed) : embed,
            quality: "auto" + (isHls ? "" : " [MP4]"),
            headers: { Referer: BASE + "/" },
            subtitles: []
          }]));
        }
      }
      return Promise.all(jobs).then(function (lists) {
        var out = [];
        var uSeen = {};
        for (var a = 0; a < lists.length; a++) {
          for (var b = 0; b < lists[a].length; b++) {
            var r = lists[a][b];
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
      console.log("[bb-nuvio][onepacetr] " + (e && e.message));
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
