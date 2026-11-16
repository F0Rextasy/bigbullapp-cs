// @provider {"id":"ddizi", "name":"Ddizi", "types":["tv"], "domain":"www.ddizi.tel", "formats":["mp4", "m3u8"], "sample":"tt21764074:tv:1:123", "description":"Ddizi yerli dizi arşivi; bölümler jwplayer HLS (tabii) olarak açılır.", "version":"1.0.9"}
/**
 * ddizi — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title (+ videos cumulative episode map) -> POST /arama/ -> series
 *       -> smart /sayfa-N jump -> /izle/ episode -> /player/oynat/<hash> -> jwplayer sources.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
var BASE = "https://www.ddizi.tel";

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
  if (o.form) headers["Content-Type"] = "application/x-www-form-urlencoded";
  return fetch(url, { method: o.method || "GET", headers: headers, body: o.body })
    .then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status + " " + url);
      return r.text();
    });
}

function fold(value) {
  return String(value || "")
    .replace(/[\u0131\u0130I]/g, "i").replace(/[\u015f\u015e]/g, "s").replace(/[\u011f\u011e]/g, "g")
    .replace(/[\u00fc\u00dc]/g, "u").replace(/[\u00f6\u00d6]/g, "o").replace(/[\u00e7\u00c7]/g, "c")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
}

function hlsNoDownload(url) {
  var u = String(url || "");
  if (/\.m3u8(\?|$)/i.test(u) || u.indexOf("#") !== -1) return u;
  return u + "#.m3u8";
}

function metaOf(id, mediaType, want, wantEp) {
  if (/^__alt__:/.test(String(id))) {
    var parts = String(id).slice(8).split("|");
    return Promise.resolve({
      title: decodeURIComponent(parts[0] || ""),
      absEp: parts[1] ? parseInt(parts[1], 10) : (want === 1 ? wantEp : 0)
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

function pickSeries(html, title) {
  var blocks = String(html || "").split(/<div class="dizi-boxpost-cat">/i).slice(1);
  var want = fold(title);
  var best = "";
  var bestScore = 0;
  var re = /<a href="(https:\/\/www\.ddizi\.tel\/diziler\/[^"]+)"[^>]*title="([^"]*)"/i;
  for (var i = 0; i < blocks.length; i++) {
    var m = re.exec(blocks[i]);
    if (!m) continue;
    var cleanLabel = m[2].replace(/\s*son\s+b[oö]l[uü]m\s+izle\s*$/i, "").replace(/\s*izle\s*$/i, "");
    var hay = fold(cleanLabel);
    var score = hay === want ? 3 : (hay + " ").indexOf(want + " ") === 0 ? 2 : 0;
    if (score > bestScore) {
      bestScore = score;
      best = m[1];
    }
  }
  return best;
}

/* Episode links live inside div.dizi-boxpost-cat at /izle/<id>/<slug>-<n>-bolum... */
function episodes(html) {
  var blocks = String(html || "").split(/<div class="dizi-boxpost-cat">/i).slice(1);
  var out = [];
  var re = /<a href="(https:\/\/www\.ddizi\.tel\/izle\/[^"]+)"[^>]*title="([^"]*)"/i;
  for (var i = 0; i < blocks.length; i++) {
    var m = re.exec(blocks[i]);
    if (!m) continue;
    var label = m[2].replace(/\s+/g, " ").trim();
    var season = /(\d+)\.\s*Sezon/i.exec(label);
    var number = /(\d+)\.\s*B[oö]l[uü]m/i.exec(label) || /-(\d+)-bolum/i.exec(m[1]);
    out.push({
      url: m[1],
      season: season ? parseInt(season[1], 10) : 1,
      episode: number ? parseInt(number[1], 10) : null
    });
  }
  return out;
}

function matchEpisode(eps, want, wantEp, absEp) {
  for (var i = 0; i < eps.length; i++) {
    if (eps[i].season === want && eps[i].episode === wantEp) return eps[i].url;
  }
  if (absEp > 0) {
    for (var j = 0; j < eps.length; j++) {
      if (eps[j].season === 1 && eps[j].episode === absEp) return eps[j].url;
    }
  }
  return "";
}

function sources(player) {
  var block = /sources\s*:\s*\[([\s\S]*?)\]/i.exec(player);
  if (!block) return [];
  var out = [];
  var re = /file\s*:\s*"([^"]+)"(?:\s*,\s*label\s*:\s*"([^"]*)")?/g;
  var m;
  while ((m = re.exec(block[1])) !== null) {
    var url = m[1].replace(/\\\//g, "/");
    if (!/^https?:/i.test(url)) continue;
    var isHls = /\.m3u8/i.test(url);
    var q = /(\d{3,4})/.exec(String(m[2] || ""));
    out.push({
      name: "ddizi",
      title: "ddizi - " + (m[2] || "Auto") + (isHls ? " [HLS]" : " [MP4]"),
      url: isHls ? hlsNoDownload(url) : url,
      quality: (q ? q[1] + "p" : "auto") + (isHls ? "" : " [MP4]"),
      headers: {},
      subtitles: []
    });
  }
  return out;
}

/* Larger series split episodes into /sayfa-N pages (10/page, newest first); jump directly near absEp when possible. */
function findEpisode(seriesUrl, want, wantEp, absEp) {
  var base = seriesUrl.replace(/\/sayfa-\d+.*$/, "").replace(/\/+$/, "");

  function candidatePages(html, newestEp) {
    var seen = {};
    var out = [];
    function add(idx) {
      if (idx < 1 || idx > 40) return;
      var u = base + "/sayfa-" + idx;
      if (!seen[u]) { seen[u] = 1; out.push(u); }
    }
    var target = absEp > 0 ? absEp : wantEp;
    if (newestEp > 0 && target > 0 && newestEp >= target) {
      var est = Math.floor((newestEp - target) / 10);
      add(est);
      add(est - 1);
      add(est + 1);
      add(est + 2);
    }
    var re = /href="[^"]*\/sayfa-(\d+)"/g;
    var m;
    while ((m = re.exec(html)) !== null) {
      add(parseInt(m[1], 10));
    }
    return out.slice(0, 12);
  }

  function walk(list, i) {
    if (i >= list.length) return Promise.resolve("");
    return text(list[i])
      .catch(function () { return ""; })
      .then(function (html) {
        var hit = matchEpisode(episodes(html), want, wantEp, absEp);
        return hit || walk(list, i + 1);
      });
  }

  return text(seriesUrl).then(function (html) {
    var eps = episodes(html);
    var hit = matchEpisode(eps, want, wantEp, absEp);
    if (hit) return hit;
    var newest = eps.length && eps[0].episode ? eps[0].episode : 0;
    var pages = candidatePages(html, newest);
    return pages.length ? walk(pages, 0) : "";
  });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var wanted = "";
  var absEp = want === 1 ? wantEp : 0;

  return metaOf(tmdbId, mediaType, want, wantEp)
    .then(function (info) {
      if (!info.title) return null;
      wanted = info.title;
      absEp = info.absEp || absEp;
      return text(BASE + "/arama/", {
        method: "POST",
        form: true,
        body: "arama=" + encodeURIComponent(wanted)
      });
    })
    .then(function (search) {
      var series = search && wanted ? pickSeries(search, wanted) : "";
      if (!series) return null;
      return findEpisode(series, want, wantEp, absEp);
    })
    .then(function (episodeUrl) {
      if (!episodeUrl) return [];
      return text(episodeUrl, { referer: BASE + "/" }).then(function (html) {
        var frames = [];
        var re = /<iframe[^>]+(?:src|data-src)="([^"]+)"/g;
        var m;
        while ((m = re.exec(html)) !== null) {
          if (m[1].indexOf("/player/") === -1 || frames.indexOf(m[1]) !== -1) continue;
          frames.push(m[1]);
        }
        var rows = [];
        function step(i) {
          if (i >= frames.length) return Promise.resolve(rows);
          var frame = frames[i].charAt(0) === "/" ? BASE + frames[i] : frames[i];
          return text(frame, { referer: episodeUrl })
            .catch(function () { return ""; })
            .then(function (player) {
              rows = rows.concat(sources(player));
              return step(i + 1);
            });
        }
        return step(0);
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][ddizi] " + (e && e.message));
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
  if (!/^\d+$/.test(raw)) return __origGetStreams(raw, mediaType, season, episode);
  var tv = mediaType !== "movie";
  var headers = { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36", "Accept": "application/json" };
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
  return fromTmdb().then(function (imdb) { return imdb || fromWikidata(); }).then(function (imdb) {
    return __origGetStreams(imdb || raw, mediaType, season, episode);
  }).catch(function () { return []; });
};

/* Cinemeta basligi yerellestirir ("Gibi" -> "As If"); sonuc bossa Wikidata TR, IMDb ve TVMaze basliklariyla yeniden dene. */
function __altTitles(raw, mediaType) {
  var h = { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36", "Accept": "application/json" };
  var api = "https://www.wikidata.org/w/api.php?format=json";
  var stmt = /^tt\d+$/.test(raw) ? "P345=" + raw : (mediaType === "movie" ? "P4947=" : "P4983=") + raw;
  var wd = fetch(api + "&action=query&list=search&srlimit=1&srsearch=" + encodeURIComponent("haswbstatement:" + stmt), { headers: h })
    .then(function (r) { return r.ok ? r.json() : null; })
    .then(function (j) {
      var hits = (j && j.query && j.query.search) || [];
      if (!hits.length) return null;
      return fetch(api + "&action=wbgetentities&props=labels|aliases&languages=tr|en&ids=" + hits[0].title, { headers: h }).then(function (r) { return r.ok ? r.json() : null; });
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
  if (/^__alt__:/.test(raw)) return __cinemetaGetStreams(raw, mediaType, season, episode);
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  return __cinemetaGetStreams(raw, mediaType, season, episode).then(function (streams) {
    if (streams && streams.length) return streams;
    return metaOf(raw, mediaType, want, wantEp).then(function (info) {
      return __altTitles(raw, mediaType).then(function (titles) {
        var chain = Promise.resolve([]);
        titles.forEach(function (t) {
          chain = chain.then(function (out) {
            return out.length ? out : __cinemetaGetStreams("__alt__:" + encodeURIComponent(t) + "|" + (info.absEp || 0), mediaType, season, episode);
          });
        });
        return chain;
      });
    });
  }).catch(function () { return []; });
};

/* Dil etiketi (AGENTS.md): Nuvio karti "quality \u2022 ..." satirini gosterir; dil etiketi kalitenin yanina yazilir.
   Kanit: baslikta dublaj/altyazi, akisa eklenmis altyazi dosyasi ya da sitenin yalniz altyazili olmasi. Kanit yoksa etiket yok. */
var __subOnly = false;
var __langLabeled = module.exports.getStreams;
module.exports.getStreams = function (id, mediaType, season, episode) {
  return __langLabeled.apply(this, arguments).then(function (streams) {
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
  });
};
