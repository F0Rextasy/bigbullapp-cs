// @provider {"id":"dizilla", "name":"Dizilla", "types":["tv"], "domain":"dizilla.la", "formats":["m3u8"], "sample":"tt27497448:tv:1:1", "description":"Dizilla yabanci dizi arsivi (HLS + Turkce altyazi).", "version":"1.0.12"}
/**
 * dizilla — Nuvio provider (bb-nuvio).
 * Next.js site: series metadata + episode list live in the JSON-LD blocks, the
 * episode page carries its player iframes in the RSC payload ("sources").
 * Flow: Cinemeta title -> /ara?q= -> JSON-LD episodes -> "sources" iframes ->
 *       player NP_CONFIG -> master m3u8 + VTT subtitles. The player only accepts a
 *       dizilla.now Referer (PLAYER_REFERER); the .la host is answered with 404 "7".
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://dizilla.la";
/** pichive's referer allowlist accepts only this origin; the site itself moved to .la. */
var PLAYER_REFERER = "https://dizilla.now/";

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
  return fetch(url, { method: o.method || "GET", headers: headers })
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
  var origin = /^(https?:\/\/[^/]+)/.exec(base);
  if (href.charAt(0) === "/") return origin ? origin[1] + href : href;
  return base.replace(/[^/]*$/, "") + href;
}

function titleOf(id, mediaType) {
  var kind = mediaType === "tv" ? "series" : "movie";
  return text("https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json")
    .then(function (body) {
      var meta = JSON.parse(body).meta || {};
      return String(meta.name || "").trim();
    })
    .catch(function () {
      return "";
    });
}

function norm(value) {
  return String(value || "").toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
}

/** JSON sits inside a JS string / script tag, so cut a balanced {...} by hand. */
function sliceBalanced(body, open) {
  var depth = 0;
  var inString = false;
  var escaped = false;
  for (var i = open; i < body.length; i++) {
    var c = body.charAt(i);
    if (inString) {
      if (escaped) escaped = false;
      else if (c === "\\") escaped = true;
      else if (c === '"') inString = false;
      continue;
    }
    if (c === '"') inString = true;
    else if (c === "{" || c === "[") depth++;
    else if (c === "}" || c === "]") {
      depth--;
      if (depth === 0) return body.slice(open, i + 1);
    }
  }
  return null;
}

function jsonAfter(body, marker, offset) {
  var at = body.indexOf(marker, offset || 0);
  if (at < 0) return null;
  var open = body.indexOf("{", at + marker.length - 1);
  var slice = open < 0 ? null : sliceBalanced(body, open);
  if (!slice) return null;
  try {
    return JSON.parse(slice);
  } catch (e) {
    return null;
  }
}

/** `<a href="/dizi/<slug>" class="pcard" aria-label="Title, 2010, IMDb 8.1">`. */
function cards(html, base) {
  var out = [];
  var re = /<a[^>]*class="[^"]*pcard[^"]*"[^>]*>/g;
  var tag;
  while ((tag = re.exec(html)) !== null) {
    var href = first(/href="([^"]*)"/, tag[0]);
    var label = first(/aria-label="([^"]*)"/, tag[0]);
    if (!href || !label) continue;
    out.push({ url: abs(base, href), title: label.split(",")[0].trim() });
  }
  return out;
}

function pick(list, title) {
  var want = norm(title);
  var best = null;
  var bestScore = 0;
  for (var i = 0; i < list.length; i++) {
    var have = norm(list[i].title);
    var score = have === want ? 100 : have.indexOf(want + " ") === 0 ? 80 : 0;
    if (score > bestScore) {
      bestScore = score;
      best = list[i];
    }
    if (bestScore === 100) break;
  }
  return bestScore >= 80 ? best : null;
}

/** Every TVSeries JSON-LD block; episode lists only live in the richest one. */
function seriesObjects(html) {
  var out = [];
  var re = /<script[^>]+type="application\/ld\+json"[^>]*>([\s\S]*?)<\/script>/g;
  var m;
  while ((m = re.exec(html)) !== null) {
    var parsed;
    try {
      parsed = JSON.parse(m[1]);
    } catch (e) {
      continue;
    }
    if (parsed && parsed["@type"] === "TVSeries") out.push(parsed);
  }
  return out;
}

function episodesOf(obj) {
  var seasons = obj.containsSeason;
  if (!seasons) return [];
  if (!Array.isArray(seasons)) seasons = [seasons];
  var out = [];
  for (var i = 0; i < seasons.length; i++) {
    var list = seasons[i].episode || [];
    var season = parseInt(seasons[i].seasonNumber, 10) || i + 1;
    for (var j = 0; j < list.length; j++) {
      if (!list[j] || !list[j].url) continue;
      out.push({
        url: list[j].url,
        season: season,
        episode: parseInt(list[j].episodeNumber, 10) || j + 1
      });
    }
  }
  return out;
}

var LANGS = { "türkçe": "tr", "turkce": "tr", "i̇ngilizce": "en", "ingilizce": "en", "english": "en", "arapça": "ar", "almanca": "de", "ispanyolca": "es" };

function langOf(label) {
  var key = String(label || "").toLowerCase().trim();
  return LANGS[key] || (key.indexOf("ingiliz") !== -1 ? "en" : "tr");
}

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var episodeUrl = null;
  var players = [];

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return null;
      return text(BASE + "/ara?q=" + encodeURIComponent(title) + "&page=1").then(function (html) {
        return pick(cards(html, BASE), title);
      });
    })
    .then(function (series) {
      if (!series) return null;
      return text(series.url).then(function (detail) {
        var objects = seriesObjects(detail);
        if (!objects.length) return null;
        var list = [];
        for (var i = 0; i < objects.length; i++) {
          var found = episodesOf(objects[i]);
          if (found.length > list.length) list = found;
        }
        for (var j = 0; j < list.length; j++) {
          if (list[j].season === want && list[j].episode === wantEp) {
            episodeUrl = list[j].url;
            break;
          }
        }
        return episodeUrl;
      });
    })
    .then(function (url) {
      if (!url) return [];
      return text(url).then(function (html) {
        var body = html.replace(/\\"/g, '"');
        var at = body.indexOf('"sources":[');
        if (at < 0) return [];
        var open = body.indexOf("[", at);
        var slice = open < 0 ? null : sliceBalanced(body, open);
        var seen = {};
        if (!slice) return [];
        var list;
        try {
          list = JSON.parse(slice);
        } catch (e) {
          return [];
        }
        for (var i = 0; i < list.length; i++) {
          var item = list[i];
          if (!item || item.trailer || !/^https?:/i.test(String(item.src || ""))) continue;
          var key = item.src;
          if (seen[key]) {
            seen[key].lang = seen[key].lang && seen[key].lang !== (item.langLabel || item.lang) ? seen[key].lang + " / " + (item.langLabel || item.lang) : seen[key].lang;
            continue;
          }
          seen[key] = {
            url: item.src,
            label: String(item.name || "").trim(),
            quality: String(item.quality || "").trim(),
            lang: String(item.langLabel || item.lang || "").trim()
          };
          players.push(seen[key]);
        }
        return url;
      });
    })
    .then(function () {
      return players.reduce(function (chain, player) {
        return chain.then(function (rows) {
          return text(player.url, { referer: PLAYER_REFERER })
            .then(function (html) {
              var config = jsonAfter(html, "NP_CONFIG");
              if (!config) return rows;
              var masters = config.sources || [];
              var height = parseInt(first(/(\d{3,4})/, player.quality) || "", 10) || 0;
              var name = ["dizilla", player.lang, player.label].filter(function (part) { return part; }).join(" ");
              for (var i = 0; i < masters.length; i++) {
                var stream = masters[i] && masters[i].master;
                if (!stream || !/^https?:/i.test(stream)) continue;
                if (rows.some(function (row) { return row.url === stream; })) continue;
                var subs = [];
                var tracks = config.subtitles || [];
                for (var j = 0; j < tracks.length; j++) {
                  if (!tracks[j] || !/^https?:/i.test(String(tracks[j].url || ""))) continue;
                  subs.push({
                    url: tracks[j].url,
                    language: langOf(tracks[j].label),
                    name: String(tracks[j].label || "Türkçe") + " [dizilla]"
                  });
                }
                rows.push({
                  name: "dizilla",
                  title: name + (height ? " - " + height + "p" : " - HLS"),
                  url: stream,
                  quality: height ? height + "p" : "auto",
                  headers: { Referer: player.url },
                  subtitles: subs.slice(0, 6)
                });
              }
              return rows;
            })
            .catch(function () {
              return rows;
            });
        });
      }, Promise.resolve([]));
    })
    .catch(function (e) {
      console.log("[bb-nuvio][dizilla] " + (e && e.message));
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
