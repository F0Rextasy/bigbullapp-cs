// @provider {"id":"aniarsiv","name":"AniArsiv","types":["tv"],"domain":"aniarsiv.com","formats":["mp4","m3u8"],"sample":"tt2560140:tv:1:1","description":"AniArsiv Turkce altyazili ve dublajli anime arsivi (CDN MP4, tau-video, anizmplayer).","verified":true,"version":"1.0.0"}
/**
 * aniarsiv — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title (+ videos absEp) -> /api/animes?q= -> /api/anime/<slug> -> /api/bolum/<epSlug>
 *       -> cdn_video.video_url / tau-video.xyz / anizmplayer.com / direct MP4.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://aniarsiv.com";
var ID = "AniArsiv";

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9", "Accept": "application/json, text/plain, */*" };
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

function pickAnime(items, title, wantSeason) {
  var want = fold(title);
  if (!want) return null;
  var best = null;
  var bestScore = -1;
  for (var i = 0; i < (items || []).length; i++) {
    var item = items[i] || {};
    if (!item.slug) continue;
    var cands = [item.baslik, item.ingilizce, item.romaji];
    var score = 0;
    for (var c = 0; c < cands.length; c++) {
      var have = fold(cands[c]);
      if (!have) continue;
      if (have === want) score = Math.max(score, 100);
      else if (have.indexOf(want + " ") === 0) score = Math.max(score, 60);
    }
    if (!score) continue;
    if (wantSeason > 1) {
      var joined = fold((item.baslik || "") + " " + (item.ingilizce || "") + " " + (item.slug || ""));
      if (joined.indexOf("season " + wantSeason) !== -1 || joined.indexOf(wantSeason + " sezon") !== -1) {
        score += 30;
      }
    }
    if (score > bestScore) {
      bestScore = score;
      best = item;
    }
  }
  return bestScore > 0 ? best : null;
}

function pickEpisode(bolumler, wantEp, absEp) {
  var list = bolumler || [];
  var targets = [];
  if (wantEp > 0) targets.push(wantEp);
  if (absEp > 0 && absEp !== wantEp) targets.push(absEp);
  for (var t = 0; t < targets.length; t++) {
    var target = targets[t];
    for (var i = 0; i < list.length; i++) {
      var b = list[i] || {};
      if (!b.slug) continue;
      var idx = Number(b.index) || 0;
      if (!idx) {
        var m = /-(\d+)-bolum/.exec(String(b.slug));
        if (m) idx = parseInt(m[1], 10);
      }
      if (idx === target) return b.slug;
    }
  }
  return null;
}

function resolveTau(embed, labelPrefix) {
  var key = first(/\/embed\/([^/?#]+)/, embed);
  if (!key) return Promise.resolve([]);
  return text("https://tau-video.xyz/api/video/" + encodeURIComponent(key), { referer: embed })
    .then(function (body) {
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
          title: ID + " - " + labelPrefix + " " + q + (isHls ? " [HLS]" : " [MP4]"),
          url: stream,
          quality: q + (isHls ? "" : " [MP4]"),
          headers: { Referer: embed },
          subtitles: []
        });
      }
      return out;
    })
    .catch(function () { return []; });
}

function resolveAnizmPlayer(embed, referer, labelPrefix) {
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
    var data = JSON.parse(body);
    var stream = String(data.videoSource || "").replace(/\\\//g, "/").trim();
    if (!/^https?:/.test(stream)) return [];
    return [{
      name: ID,
      title: ID + " - " + labelPrefix + " [HLS]",
      url: stream,
      quality: "auto",
      headers: { Referer: origin + "/", Accept: "*/*" },
      subtitles: []
    }];
  }).catch(function () { return []; });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var absEp = want === 1 ? wantEp : 0;
  var wanted = "";

  return metaOf(tmdbId, mediaType, want, wantEp)
    .then(function (info) {
      wanted = info.title;
      absEp = info.absEp || absEp;
      if (!wanted) return null;
      return text(BASE + "/api/animes?q=" + encodeURIComponent(wanted), { referer: BASE + "/" })
        .then(function (body) { return JSON.parse(body); });
    })
    .then(function (data) {
      if (!data) return null;
      var hit = pickAnime(data.items, wanted, want);
      if (!hit) return null;
      return text(BASE + "/api/anime/" + encodeURIComponent(hit.slug), { referer: BASE + "/" })
        .then(function (body) { return JSON.parse(body); });
    })
    .then(function (detail) {
      if (!detail) return null;
      var epSlug = pickEpisode(detail.bolumler, wantEp, absEp);
      if (!epSlug) return null;
      var epPage = BASE + "/bolum/" + epSlug;
      return text(BASE + "/api/bolum/" + encodeURIComponent(epSlug), { referer: epPage })
        .then(function (body) { return { epPage: epPage, root: JSON.parse(body) }; });
    })
    .then(function (res) {
      if (!res || !res.root) return [];
      var root = res.root;
      var out = [];
      var seen = {};
      function push(row) {
        if (row && row.url && !seen[row.url]) {
          seen[row.url] = 1;
          out.push(row);
        }
      }
      var cdn = root.cdn_video;
      if (cdn && !cdn.is_short && /^https?:/.test(String(cdn.video_url || ""))) {
        var h = cdn.height ? cdn.height + "p" : "1080p";
        push({
          name: ID,
          title: ID + " - CDN " + h + " [MP4]",
          url: String(cdn.video_url),
          quality: h + " [MP4]",
          headers: { Referer: BASE + "/" },
          subtitles: []
        });
      }
      var links = root.links || [];
      var jobs = [];
      for (var i = 0; i < links.length && jobs.length < 6; i++) {
        var link = links[i] || {};
        var embed = String(link.url || "").trim();
        if (!/^https?:/.test(embed)) continue;
        var fansub = String(link.fansub || "").trim();
        var player = String(link.player || "Kaynak").trim();
        var tag = fansub ? player + " (" + fansub + ")" : player;
        if (embed.indexOf("tau-video.xyz") !== -1) {
          jobs.push(resolveTau(embed, tag));
        } else if (embed.indexOf("anizmplayer.com") !== -1) {
          jobs.push(resolveAnizmPlayer(embed, res.epPage, tag));
        } else if (/\.(?:mp4|m3u8)(?:\?|$)/i.test(embed)) {
          var isHls = embed.indexOf(".m3u8") !== -1;
          push({
            name: ID,
            title: ID + " - " + tag + (isHls ? " [HLS]" : " [MP4]"),
            url: embed,
            quality: "auto" + (isHls ? "" : " [MP4]"),
            headers: { Referer: BASE + "/" },
            subtitles: []
          });
        }
      }
      return Promise.all(jobs).then(function (lists) {
        for (var j = 0; j < lists.length; j++) {
          for (var k = 0; k < lists[j].length; k++) push(lists[j][k]);
        }
        return out;
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][aniarsiv] " + (e && e.message));
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
