// @provider {"id":"puhutv", "name":"PuhuTV", "types":["movie","tv"], "domain":"puhutv.com", "formats":["m3u8"], "sample":"tt7920978:tv:1:1", "description":"PuhuTV yerli dizi, puhutv Orijinal yapimlar ve filmler; DYG HLS akislari.", "version":"1.0.0"}
/**
 * puhutv — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta + Wikidata TR title -> appservice.puhutv.com/search/search
 *       -> /api/slug/<slug_path> -> (/api/seasons/<id>/episodes) -> /api/slug/<watch_slug>
 *       -> dygvideo.dygdigital.com/api/video_info (PublisherId=29) -> HLS variants.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://puhutv.com";
var SEARCH_API = "https://appservice.puhutv.com/search/search";
var DYG_API = "https://dygvideo.dygdigital.com/api/video_info";
var DYG_SECRET = "NtvApiSecret2014*";

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9", "Accept": "application/json, text/plain, */*" };
  if (o.referer) headers.Referer = o.referer;
  return fetch(url, { method: "GET", headers: headers })
    .then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status + " " + url);
      return r.text();
    });
}

function getJson(url, referer) {
  return text(url, { referer: referer || (BASE + "/") }).then(function (body) {
    return JSON.parse(body);
  });
}

function abs(base, href) {
  if (!href) return null;
  if (/^https?:/i.test(href)) return href;
  if (href.indexOf("//") === 0) return "https:" + href;
  var cleanBase = String(base || "").split("?")[0];
  var origin = /^(https?:\/\/[^/]+)/.exec(cleanBase);
  if (href.charAt(0) === "/") return origin ? origin[1] + href : href;
  return cleanBase.replace(/[^/]*$/, "") + href;
}

function fold(v) {
  return String(v || "")
    .replace(/\u00ad/g, "")
    .replace(/[\u0131\u0130I]/g, "i").replace(/[\u015f\u015e]/g, "s").replace(/[\u011f\u011e]/g, "g")
    .replace(/[\u00e7\u00c7]/g, "c").replace(/[\u00f6\u00d6]/g, "o").replace(/[\u00fc\u00dc]/g, "u")
    .toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
}

function sameTitle(have, want) {
  var h = fold(have);
  var w = fold(want);
  return !!w && (h === w || h.indexOf(w + " ") === 0);
}

function cinemetaTitle(id, mediaType) {
  var kind = mediaType === "tv" ? "series" : "movie";
  return getJson("https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json", BASE + "/")
    .then(function (res) {
      return String(((res && res.meta) || {}).name || "").trim();
    })
    .catch(function () { return ""; });
}

function wikidataTitles(id) {
  if (!/^tt\d+$/.test(String(id || ""))) return Promise.resolve([]);
  var api = "https://www.wikidata.org/w/api.php?format=json";
  return getJson(api + "&action=query&list=search&srlimit=1&srsearch=" + encodeURIComponent("haswbstatement:P345=" + id), "https://www.wikidata.org/")
    .then(function (res) {
      var hits = (res && res.query && res.query.search) || [];
      if (!hits.length) return null;
      return getJson(api + "&action=wbgetentities&props=labels|aliases&languages=tr|en&ids=" + encodeURIComponent(hits[0].title), "https://www.wikidata.org/");
    })
    .then(function (res) {
      if (!res || !res.entities) return [];
      var out = [];
      for (var qid in res.entities) {
        var ent = res.entities[qid] || {};
        var labels = ent.labels || {};
        var aliases = ent.aliases || {};
        if (labels.tr && labels.tr.value) out.push(labels.tr.value);
        var trAliases = aliases.tr || [];
        for (var i = 0; i < trAliases.length; i++) {
          if (trAliases[i] && trAliases[i].value) out.push(trAliases[i].value);
        }
        if (labels.en && labels.en.value) out.push(labels.en.value);
      }
      return out;
    })
    .catch(function () { return []; });
}

function candidateTitles(id, mediaType) {
  return Promise.all([wikidataTitles(id), cinemetaTitle(id, mediaType)]).then(function (pair) {
    var raw = (pair[0] || []).slice();
    if (pair[1]) raw.push(pair[1]);
    var expanded = [];
    for (var i = 0; i < raw.length; i++) {
      var t = String(raw[i] || "").trim();
      if (!t) continue;
      expanded.push(t);
      var colon = t.indexOf(":");
      if (colon > 0) {
        var prefix = t.slice(0, colon).trim();
        if (fold(prefix).length >= 3) expanded.push(prefix);
      }
    }
    var seen = {};
    var out = [];
    for (var k = 0; k < expanded.length; k++) {
      var key = fold(expanded[k]);
      if (!key || seen[key]) continue;
      seen[key] = 1;
      out.push(expanded[k]);
    }
    return out;
  });
}

function flattenSearch(res) {
  var top = (res && res.data) || [];
  var out = [];
  for (var i = 0; i < top.length; i++) {
    var entry = top[i] || {};
    if (Array.isArray(entry.data)) {
      for (var k = 0; k < entry.data.length; k++) out.push(entry.data[k]);
    } else {
      out.push(entry);
    }
  }
  return out;
}

function slugFromItem(item) {
  if (item.slug_path) return String(item.slug_path).trim();
  var m = /https?:\/\/[^/]+\/([^/?#]+)/i.exec(String(item.web_url || ""));
  return m ? m[1] : "";
}

function findMatchingSlug(candidates, wantMovie) {
  var queries = candidates.slice(0, 5);
  function step(idx) {
    if (idx >= queries.length) return Promise.resolve("");
    var url = SEARCH_API + "?query=" + encodeURIComponent(queries[idx]) + "&v=2&source=web";
    return getJson(url, BASE + "/")
      .catch(function () { return null; })
      .then(function (res) {
        var items = flattenSearch(res);
        for (var i = 0; i < items.length; i++) {
          var item = items[i] || {};
          var t = String(item.type || "");
          if (t && t.indexOf("title_") !== 0) continue;
          var isMovie = Boolean(item.is_movie) || t === "title_movie";
          if (isMovie !== wantMovie) continue;
          var slug = slugFromItem(item);
          if (!slug) continue;
          for (var c = 0; c < candidates.length; c++) {
            if (sameTitle(item.name, candidates[c])) return slug;
          }
        }
        return step(idx + 1);
      });
  }
  return step(0);
}

function findEpisodeWatchSlug(seasonId, wantEp) {
  function walk(page) {
    if (page > 10) return Promise.resolve("");
    return getJson(BASE + "/api/seasons/" + seasonId + "/episodes?e_page=" + page + "&e_per=100", BASE + "/")
      .then(function (res) {
        var sData = (res && res.data) || {};
        var eps = sData.episodes || [];
        for (var i = 0; i < eps.length; i++) {
          var ep = eps[i] || {};
          var asset = (ep.assets && ep.assets[0]) || {};
          var num = parseInt(ep.position, 10) || parseInt(asset.episode_number, 10) || 0;
          if (num === wantEp) {
            return String(asset.slug_path || ep.slug_path || "");
          }
        }
        return sData.has_more ? walk(page + 1) : "";
      });
  }
  return walk(1);
}

function parseVariants(playlist, masterUrl) {
  var lines = String(playlist || "").split(/\r?\n/);
  var out = [];
  var seen = {};
  var quality = "";
  for (var i = 0; i < lines.length; i++) {
    var line = lines[i].trim();
    if (!line) continue;
    if (line.indexOf("#EXT-X-STREAM-INF") === 0) {
      var m = /RESOLUTION=\d+x(\d+)/.exec(line);
      quality = m ? (m[1] + "p") : "auto";
    } else if (line.charAt(0) !== "#" && quality) {
      var u = abs(masterUrl, line);
      if (u && !seen[u]) {
        seen[u] = 1;
        out.push({ quality: quality, url: u });
      }
      quality = "";
    }
  }
  return out;
}

function resolveWatchStreams(watchSlug) {
  if (!watchSlug) return Promise.resolve([]);
  var pageUrl = BASE + "/" + watchSlug;
  return getJson(BASE + "/api/slug/" + watchSlug, pageUrl)
    .then(function (res) {
      var wData = (res && res.data) || {};
      var firstAsset = (wData.assets && wData.assets[0]) || {};
      var videoId = String(wData.video_id || firstAsset.video_id || "").trim();
      if (!videoId) return [];
      var dygUrl = DYG_API + "?akamai=true&PublisherId=29&ReferenceId=" + encodeURIComponent(videoId) + "&SecretKey=" + encodeURIComponent(DYG_SECRET);
      return getJson(dygUrl, pageUrl).then(function (vRes) {
        var vData = (vRes && vRes.data) || {};
        var flavors = vData.flavors || {};
        var master = String(flavors.hls || (flavors["0"] && flavors["0"].file_url_1) || "").trim();
        if (!/^https?:/i.test(master)) return [];

        var subs = [];
        var tracks = vData.tracks || [];
        for (var t = 0; t < tracks.length; t++) {
          var tr = tracks[t] || {};
          if (tr.kind && tr.kind !== "subtitles" && tr.kind !== "captions") continue;
          var subUrl = abs(BASE + "/", tr.file || tr.src || "");
          if (subUrl) subs.push({ url: subUrl, lang: String(tr.label || tr.language || "tr") });
        }

        return text(master, { referer: BASE + "/" })
          .catch(function () { return ""; })
          .then(function (playlist) {
            var variants = parseVariants(playlist, master);
            var headers = { "User-Agent": UA, "Referer": BASE + "/" };
            if (!variants.length) {
              return [{
                name: "PuhuTV",
                title: "PuhuTV - HLS",
                url: master,
                quality: "auto",
                headers: headers,
                subtitles: subs
              }];
            }
            variants.sort(function (a, b) {
              return (parseInt(b.quality, 10) || 0) - (parseInt(a.quality, 10) || 0);
            });
            var streams = [];
            for (var i = 0; i < variants.length; i++) {
              streams.push({
                name: "PuhuTV",
                title: "PuhuTV - " + variants[i].quality + " [HLS]",
                url: variants[i].url,
                quality: variants[i].quality,
                headers: headers,
                subtitles: subs
              });
            }
            return streams;
          });
      });
    });
}

function getStreams(id, mediaType, season, episode) {
  var wantMovie = mediaType === "movie";
  var wantSeason = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;

  return candidateTitles(id, mediaType)
    .then(function (candidates) {
      if (!candidates.length) return "";
      return findMatchingSlug(candidates, wantMovie);
    })
    .then(function (slug) {
      if (!slug) return "";
      return getJson(BASE + "/api/slug/" + slug, BASE + "/").then(function (res) {
        var det = (res && res.data) || {};
        if (wantMovie) {
          if (!det.is_movie) return "";
          var firstAsset = (det.assets && det.assets[0]) || {};
          return String(det.to_watch_asset_slug_path || firstAsset.slug_path || "");
        }
        if (det.is_movie) return "";
        var seasons = det.seasons || [];
        var targetSeason = null;
        for (var i = 0; i < seasons.length; i++) {
          if (parseInt(seasons[i].position, 10) === wantSeason) {
            targetSeason = seasons[i];
            break;
          }
        }
        if (!targetSeason || !targetSeason.id) return "";
        return findEpisodeWatchSlug(targetSeason.id, wantEp);
      });
    })
    .then(function (watchSlug) {
      return resolveWatchStreams(watchSlug);
    })
    .catch(function (e) {
      console.log("[bb-nuvio][puhutv] " + (e && e.message));
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
