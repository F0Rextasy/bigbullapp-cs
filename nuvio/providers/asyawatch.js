// @provider {"id":"asyawatch","name":"AsyaWatch","types":["tv","movie"],"domain":"asyawatch.com","formats":["m3u8"],"sample":"tt15792808:tv:1:1","description":"AsyaWatch (asyawatch.com) Kore/Asya dizileri ve filmleri; pichive oynatcisindan HLS master + altyazi.","verified":false}
/**
 * asyawatch — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> POST /api/bg/searchContent -> base64 "response" envelope
 *       -> content page "#__NEXT_DATA__" secureData (same envelope) -> episode/part sources
 *       -> player iframe -> window.NP_CONFIG sources[] or openPlayer token -> source2.php
 *       -> m.php master (#EXTM3U) + VTT tracks.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
var BASE = "https://asyawatch.com";
var MAX_EMBEDS = 3;

var B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
  if (o.headers) for (var k in o.headers) headers[k] = o.headers[k];
  return fetch(url, { method: o.method || "GET", headers: headers }).then(function (r) {
    if (!r.ok) throw new Error("HTTP " + r.status);
    return r.text();
  });
}

function first(pattern, body) {
  var m = pattern.exec(String(body || ""));
  return m ? m[1] : null;
}

function abs(href) {
  if (!href) return null;
  if (href.indexOf("//") === 0) return "https:" + href;
  if (/^https?:/i.test(href)) return href;
  return href.charAt(0) === "/" ? BASE + href : BASE + "/" + href;
}

/** base64 -> byte string, then the byte string interpreted as UTF-8. */
function unseal(sealed) {
  var raw = String(sealed || "").replace(/[^A-Za-z0-9+/]/g, "");
  var bytes = "";
  for (var i = 0; i + 1 < raw.length; i += 4) {
    var n =
      (B64.indexOf(raw.charAt(i)) << 18) |
      (B64.indexOf(raw.charAt(i + 1)) << 12) |
      ((B64.indexOf(raw.charAt(i + 2)) & 63) << 6) |
      (B64.indexOf(raw.charAt(i + 3)) & 63);
    bytes += String.fromCharCode((n >> 16) & 255);
    if (i + 2 < raw.length) bytes += String.fromCharCode((n >> 8) & 255);
    if (i + 3 < raw.length) bytes += String.fromCharCode(n & 255);
  }
  var out = "";
  for (var j = 0; j < bytes.length; j++) {
    var c = bytes.charCodeAt(j);
    if (c < 128) out += String.fromCharCode(c);
    else if (c > 191 && c < 224) out += String.fromCharCode(((c & 31) << 6) | (bytes.charCodeAt(++j) & 63));
    else if (c > 223 && c < 240)
      out += String.fromCharCode(((c & 15) << 12) | ((bytes.charCodeAt(++j) & 63) << 6) | (bytes.charCodeAt(++j) & 63));
    else {
      c = ((c & 7) << 18) | ((bytes.charCodeAt(++j) & 63) << 12) | ((bytes.charCodeAt(++j) & 63) << 6) | (bytes.charCodeAt(++j) & 63);
      c -= 0x10000;
      out += String.fromCharCode(0xd800 + (c >> 10), 0xdc00 + (c & 1023));
    }
  }
  return out;
}

function jsonOf(sealed) {
  try {
    return JSON.parse(unseal(sealed));
  } catch (e) {
    return null;
  }
}

function norm(value) {
  return String(value || "")
    .toLowerCase()
    .replace(/ı/g, "i").replace(/ş/g, "s").replace(/ğ/g, "g")
    .replace(/ü/g, "u").replace(/ö/g, "o").replace(/ç/g, "c")
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
}

function titleOf(id, mediaType) {
  var kind = mediaType === "tv" ? "series" : "movie";
  return text("https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json")
    .then(function (body) {
      return String((JSON.parse(body).meta || {}).name || "").trim();
    })
    .catch(function () {
      return "";
    });
}

function api(name, params) {
  var query = "";
  for (var k in params) query += (query ? "&" : "") + k + "=" + encodeURIComponent(params[k]);
  return text(BASE + "/api/bg/" + name + "?" + query, {
    method: "POST",
    referer: BASE + "/",
    headers: { Accept: "application/json, text/plain, */*" }
  }).then(function (raw) {
    return jsonOf(JSON.parse(raw).response || "");
  });
}

function pageJson(url) {
  return text(url).then(function (html) {
    var sealed = first(/"secureData"\s*:\s*"([^"]+)"/, html);
    return sealed ? jsonOf(sealed) : null;
  });
}

/** Score search rows by title overlap; the site's own order breaks ties. */
function pickItem(rows, want, mediaType) {
  var wantKey = norm(want);
  var words = wantKey.split(" ").filter(function (w) {
    return w.length > 2 || /^\d+$/.test(w);
  });
  var best = null;
  var bestScore = -1;
  for (var i = 0; i < rows.length; i++) {
    var row = rows[i];
    if (!row || !row.used_slug) continue;
    var key = norm(row.object_name || row.object_alternative_name);
    var score = 0;
    if (key === wantKey) score += 10;
    for (var j = 0; j < words.length; j++) if (key.indexOf(words[j]) !== -1) score++;
    if (mediaType === "tv" && row.used_type === "Series") score += 3;
    if (mediaType === "movie" && row.used_type === "Movies") score += 3;
    if (score > bestScore) {
      bestScore = score;
      best = row;
    }
  }
  return best;
}

function sourceEntries(data) {
  var out = [];
  var related = (data && data.RelatedResults) || {};
  for (var key in related) {
    if (!/^get(?:EpisodeSources|MoviePartSourcesById_)/.test(key)) continue;
    var rows = (related[key] && related[key].result) || [];
    for (var i = 0; i < rows.length; i++) out.push(rows[i]);
  }
  return out;
}

/** window.NP_CONFIG = {...}; scan to the matching brace so quoted HTML never ends it early. */
function npConfig(html) {
  var start = html.indexOf("NP_CONFIG");
  if (start < 0) return null;
  var open = html.indexOf("{", start);
  if (open < 0) return null;
  var depth = 0;
  var quote = "";
  for (var i = open; i < html.length; i++) {
    var ch = html.charAt(i);
    if (quote) {
      if (ch === "\\") i++;
      else if (ch === quote) quote = "";
      continue;
    }
    if (ch === '"' || ch === "'") quote = ch;
    else if (ch === "{") depth++;
    else if (ch === "}") {
      depth--;
      if (!depth) return jsonOf(html.slice(open, i + 1));
    }
  }
  return null;
}

function resolution(playlist) {
  var re = /RESOLUTION=\d+x(\d+)/g;
  var best = 0;
  var m;
  while ((m = re.exec(playlist)) !== null) {
    var h = parseInt(m[1], 10);
    if (h > best) best = h;
  }
  return best ? best + "p" : "auto";
}

function addMaster(rows, seen, master, referer, label) {
  if (!master || seen[master]) return Promise.resolve();
  return text(master, { referer: referer })
    .then(function (playlist) {
      if (playlist.indexOf("#EXTM3U") !== 0) return;
      seen[master] = true;
      var quality = resolution(playlist);
      rows.push({
        name: "asyawatch",
        title: "asyawatch - " + (label ? label + " " : "") + quality + " [HLS]",
        url: master,
        quality: quality,
        headers: { Referer: referer, "User-Agent": UA },
        subtitles: []
      });
    })
    .catch(function () {});
}

function readEmbed(embed, rows, seen, subs) {
  var origin = first(/^(https?:\/\/[^/]+)/, embed);
  var configPromise = text(embed, { referer: BASE + "/" })
    .then(function (html) {
      var config = npConfig(html);
      if (config) {
        var sources = config.sources || [];
        var chain = Promise.resolve();
        for (var i = 0; i < sources.length; i++) {
          (function (master) {
            if (!/^https?:/i.test(String(master || ""))) return;
            chain = chain.then(function () {
              return addMaster(rows, seen, master, origin + "/");
            });
          })(sources[i].master);
        }
        for (var j = 0; j < (config.subtitles || []).length; j++) {
          var sub = config.subtitles[j];
          if (sub && /^https?:/i.test(String(sub.url || ""))) {
            subs.push({
              url: sub.url,
              language: String(sub.language || sub.label || "tr").slice(0, 2).toLowerCase(),
              name: sub.label || "TR"
            });
          }
        }
        return chain;
      }
      var token = first(/openPlayer\(\s*'([^']+)'/, html);
      if (!token) return null;
      return text(origin + "/source2.php?v=" + encodeURIComponent(token), { referer: embed })
        .then(function (raw) {
          var playlist = (JSON.parse(raw).playlist || []);
          var chain2 = Promise.resolve();
          for (var k = 0; k < playlist.length; k++) {
            var files = playlist[k].sources || [];
            for (var f = 0; f < files.length; f++) {
              (function (file, label) {
                if (!/^https?:/i.test(String(file || ""))) return;
                chain2 = chain2.then(function () {
                  return addMaster(rows, seen, file, origin + "/", label);
                });
              })(files[f].file, files[f].title);
            }
          }
          return chain2;
        })
        .catch(function () {
          return null;
        });
    })
    .catch(function () {
      return null;
    });
  return configPromise;
}

function getStreams(tmdbId, mediaType, season, episode) {
  var rows = [];
  var seen = {};
  var wantTitle = "";
  var wantSeason = parseInt(season, 10) || 1;
  var wantEpisode = parseInt(episode, 10) || 1;
  var subs = [];

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      wantTitle = title;
      if (!title) return null;
      return api("searchContent", { searchterm: title });
    })
    .then(function (result) {
      if (!result) return null;
      var item = pickItem((result.result || []), wantTitle, mediaType);
      if (!item) return null;
      return pageJson(BASE + "/" + item.used_slug);
    })
    .then(function (data) {
      if (!data) return [];
      var entries = sourceEntries(data);
      if (!entries.length && mediaType === "tv") return episodeSources(data, wantSeason, wantEpisode);
      return entries;
    })
    .then(function (entries) {
      var embeds = [];
      var seenEmbeds = {};
      for (var i = 0; i < entries.length && embeds.length < MAX_EMBEDS; i++) {
        var html = String((entries[i] && entries[i].source_content) || "");
        var src = first(/<iframe[^>]*\bsrc\s*=\s*["']([^"']+)["']/i, html);
        var url = abs(src);
        if (!url || seenEmbeds[url]) continue;
        seenEmbeds[url] = true;
        embeds.push(url);
      }
      return embeds.reduce(function (chain, embed) {
        return chain.then(function () {
          if (rows.length >= MAX_EMBEDS) return null;
          return readEmbed(embed, rows, seen, subs);
        });
      }, Promise.resolve());
    })
    .then(function () {
      var picked = subs.slice(0, 4);
      for (var i = 0; i < rows.length; i++) rows[i].subtitles = picked;
      return rows;
    })
    .catch(function (e) {
      console.log("[bb-nuvio][asyawatch] " + (e && e.message));
      return [];
    });
}

/** Series page carries only the season list; the episode page carries the sources. */
function episodeSources(data, wantSeason, wantEpisode) {
  var seasons = ((data.RelatedResults || {}).getSerieSeasonAndEpisodes || {}).result || [];
  var slug = null;
  var fallback = null;
  for (var i = 0; i < seasons.length; i++) {
    var eps = seasons[i].episodes || [];
    for (var j = 0; j < eps.length; j++) {
      if (!fallback) fallback = eps[j].used_slug;
      if (seasons[i].season_no === wantSeason && eps[j].episode_no === wantEpisode) {
        slug = eps[j].used_slug;
        break;
      }
    }
    if (slug) break;
  }
  slug = slug || fallback;
  if (!slug) return [];
  return pageJson(BASE + "/" + slug).then(function (epData) {
    return sourceEntries(epData);
  });
}

module.exports = { getStreams };
