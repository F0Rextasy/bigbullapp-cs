// @provider {"id":"toruko","name":"Toruko","types":["tv"],"domain":"turkceanime.com","formats":["m3u8","mp4"],"sample":"tt0409591:tv:1:1","description":"Toruko (turkceanime.com) Turkce altyazili ve dublajli anime arsivi (HLS + tau-video).","verified":true,"version":"1.0.0"}
/**
 * toruko — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /ara/?q= -> /anime/<slug>/ -> season-block (+ paginated season page)
 *       -> /api/episode/<id>/sources -> /player/<token> -> /hls?u= / /mp4/stream/ / tau-video.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://turkceanime.com";
var ID = "Toruko";

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

function titleOf(id, mediaType) {
  if (/^__alt__:/.test(String(id))) return Promise.resolve(decodeURIComponent(String(id).slice(8)));
  var kind = mediaType === "tv" ? "series" : "movie";
  return text("https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json")
    .then(function (body) { return String((JSON.parse(body).meta || {}).name || "").trim(); })
    .catch(function () { return ""; });
}

function pickAnime(html, title) {
  var want = fold(title);
  if (!want) return null;
  var re = /<article class="tile[\s\S]*?<\/article>/gi;
  var m;
  var best = null;
  var bestScore = -1;
  while ((m = re.exec(html || "")) !== null) {
    var block = m[0];
    var href = first(/<a[^>]+class="[^"]*tile-link[^"]*"[^>]+href="([^"]+)"/, block);
    var label = first(/<[^>]*class="[^"]*tile-title[^"]*"[^>]*>([^<]+)<\/[^>]+>/, block)
      || first(/alt="([^"]+)"/, block) || "";
    if (!href || !label) continue;
    var have = fold(label);
    var score = have === want ? 100 : (have.indexOf(want + " ") === 0 ? 60 : 0);
    if (score > bestScore) {
      bestScore = score;
      best = abs(BASE, href);
    }
  }
  return bestScore > 0 ? best : null;
}

function epIdFromCards(html, wantEp) {
  var re = /<a[^>]+class="[^"]*ep-card[^"]*"[^>]*>/gi;
  var m;
  while ((m = re.exec(html || "")) !== null) {
    var tag = m[0];
    var id = first(/data-episode="(\d+)"/, tag);
    var href = first(/href="([^"]+)"/, tag) || "";
    var num = parseInt(first(/(\d+)-bolum\/?/, href) || "1", 10);
    if (id && num === wantEp) return id;
  }
  return null;
}

function findEpisodeId(detailHtml, wantSe, wantEp) {
  var blockRe = /<section[^>]+class="[^"]*season-block[^"]*"[^>]+data-season-block="(\d+)"[^>]*>([\s\S]*?)<\/section>/gi;
  var m;
  var seasonHref = null;
  while ((m = blockRe.exec(detailHtml || "")) !== null) {
    var se = parseInt(m[1], 10);
    if (se !== wantSe) continue;
    var hit = epIdFromCards(m[2], wantEp);
    if (hit) return Promise.resolve(hit);
    seasonHref = first(/<p[^>]+class="[^"]*more[^"]*"[\s\S]*?<a[^>]+href="([^"]+)"/, m[2]);
    break;
  }
  if (!seasonHref) return Promise.resolve(null);
  var base = abs(BASE, seasonHref).replace(/\/+$/, "");
  var est = Math.max(1, Math.ceil(wantEp / 48));
  var pages = [est];
  if (est > 1) pages.push(est - 1);
  pages.push(est + 1);
  function tryPage(idx) {
    if (idx >= pages.length) return Promise.resolve(null);
    return text(base + "/?sayfa=" + pages[idx])
      .then(function (html) {
        var hit = epIdFromCards(html, wantEp);
        return hit || tryPage(idx + 1);
      })
      .catch(function () { return tryPage(idx + 1); });
  }
  return tryPage(0);
}

function resolveTau(embed, label, baseQuality) {
  var key = first(/\/embed\/([^/?#]+)/, embed);
  if (!key) return Promise.resolve([]);
  return text("https://tau-video.xyz/api/video/" + encodeURIComponent(key), { referer: "https://tau-video.xyz/" })
    .then(function (body) {
      var urls = (JSON.parse(body) || {}).urls || [];
      var out = [];
      for (var i = 0; i < urls.length; i++) {
        var row = urls[i] || {};
        var stream = String(row.url || "").trim();
        if (!/^https?:/.test(stream)) continue;
        var q = String(row.label || baseQuality || "auto");
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

function resolveSource(src, label) {
  var rawQ = String(src.quality || "auto").trim();
  var qMatch = /(\d{3,4}p\+?)/i.exec(rawQ);
  var q = qMatch ? qMatch[1] : "auto";
  return text(BASE + "/player/" + encodeURIComponent(src.token))
    .then(function (player) {
      var target = first(/id="stream-url"[^>]*>([\s\S]*?)<\/script>/, player);
      if (target) {
        target = target.trim().replace(/^"+|"+$/g, "");
        var stream = abs(BASE, target);
        var isMp4 = stream.indexOf(".mp4") !== -1 || stream.indexOf("/mp4/") !== -1;
        return [{
          name: ID,
          title: ID + " - " + label + " " + rawQ + (isMp4 ? " [MP4]" : " [HLS]"),
          url: isMp4 ? stream : hlsNoDownload(stream),
          quality: q + (isMp4 ? " [MP4]" : ""),
          headers: { Referer: BASE + "/" },
          subtitles: []
        }];
      }
      var frame = first(/<iframe[^>]+src="([^"]+)"/, player);
      if (frame && frame.indexOf("tau-video.xyz/embed/") !== -1) {
        return resolveTau(abs(BASE, frame), label, q);
      }
      return [];
    })
    .catch(function () { return []; });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var wanted = "";

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      wanted = title;
      if (!title) return null;
      return text(BASE + "/ara/?q=" + encodeURIComponent(title));
    })
    .then(function (search) {
      if (!search) return null;
      var animeUrl = pickAnime(search, wanted);
      if (!animeUrl) return null;
      return text(animeUrl);
    })
    .then(function (detail) {
      if (!detail) return null;
      return findEpisodeId(detail, want, wantEp);
    })
    .then(function (epId) {
      if (!epId) return null;
      return text(BASE + "/api/episode/" + epId + "/sources")
        .then(function (body) { return JSON.parse(body); });
    })
    .then(function (data) {
      if (!data || !data.translators) return [];
      var jobs = [];
      var trs = data.translators;
      for (var i = 0; i < trs.length && jobs.length < 3; i++) {
        var tr = trs[i] || {};
        var who = String(tr.name || "Cevirmen").trim();
        var langSuffix = tr.dub ? " TR Dublaj" : " Altyazili";
        var sources = tr.sources || [];
        for (var j = 0; j < sources.length && jobs.length < 3; j++) {
          var s = sources[j] || {};
          if (s.locked || !s.token) continue;
          jobs.push(resolveSource(s, who + langSuffix));
        }
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
      console.log("[bb-nuvio][toruko] " + (e && e.message));
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
