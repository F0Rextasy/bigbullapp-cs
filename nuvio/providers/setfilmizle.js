// @provider {"id":"setfilmizle", "name":"SetFilmIzle", "types":["movie", "tv"], "domain":"setfilmizle.ltd", "formats":["m3u8"], "sample":"tt0133093:movie:1:1", "description":"SetFilmIzle Türkçe dublaj/altyazılı film ve dizi arşivi (HLS + altyazı).", "version":"1.0.6"}
/**
 * setfilmizle — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /?s= -> film|dizi page -> admin-ajax get_video_url -> bridge page
 *       -> XOR'd base64 pair -> fastplay player -> master path + subtitles.
 * The manifest gate signs desktop browser requests, so the player is read with a mobile UA.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
var MOBILE_UA = "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";
var BASE = "https://www.setfilmizle.ltd";

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": o.mobile ? MOBILE_UA : UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
  if (o.headers) for (var k in o.headers) headers[k] = o.headers[k];
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
  href = String(href).trim();
  if (/^https?:/i.test(href)) return href;
  var origin = /^(https?:\/\/[^/]+)/.exec(base);
  if (href.charAt(0) === "/") return origin ? origin[1] + href : href;
  return base.replace(/[^/]*$/, "") + href;
}

var B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";


/** Sandbox has no atob; one byte per char is all the bridge needs. */
function b64(value) {
  var raw = String(value || "").replace(/[^A-Za-z0-9+/=]/g, "");
  var out = "";
  var buf = 0;
  var bits = 0;
  for (var i = 0; i < raw.length; i++) {
    var c = raw.charAt(i);
    if (c === "=") break;
    var v = B64.indexOf(c);
    if (v < 0) continue;
    buf = (buf << 6) | v;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out += String.fromCharCode((buf >> bits) & 0xff);
    }
  }
  return out;
}

function norm(value) {
  return String(value || "")
    .toLowerCase()
    .replace(/[\u0131]/g, "i")
    .replace(/[\u015f]/g, "s")
    .replace(/[\u011f]/g, "g")
    .replace(/[\u00fc]/g, "u")
    .replace(/[\u00f6]/g, "o")
    .replace(/[\u00e7]/g, "c")
    .replace(/[^a-z0-9]/g, "");
}

function pick(cards, wanted) {
  var w = norm(wanted);
  if (!w) return null;
  var subset = null;
  for (var i = 0; i < cards.length; i++) {
    var n = norm(cards[i].title);
    if (!n) continue;
    if (n === w) return cards[i];
    if (!subset && (w.indexOf(n) !== -1 || n.indexOf(w) !== -1)) subset = cards[i];
  }
  return subset;
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

function searchCards(html) {
  var cards = [];
  var re = /<a class="card-link" href="([^"]+)">([\s\S]*?)<\/a>/g;
  var m;
  while ((m = re.exec(html)) !== null) {
    var title = first(/class="card-ad"[^>]*>([^<]*)</, m[2]);
    if (!title) continue;
    cards.push({ url: m[1].trim(), title: title.trim() });
  }
  return cards;
}

function qualityOf(master) {
  var heights = [];
  var re = /RESOLUTION=\d+x(\d+)/gi;
  var m;
  while ((m = re.exec(String(master || ""))) !== null) heights.push(parseInt(m[1], 10));
  var max = 0;
  for (var i = 0; i < heights.length; i++) if (heights[i] > max) max = heights[i];
  if (!max) return "auto";
  if (max >= 2160) return "2160p";
  if (max >= 1080) return "1080p";
  if (max >= 720) return "720p";
  if (max >= 480) return "480p";
  return "360p";
}

function parseSubtitles(html) {
  var json = first(/subtitles:\s*(\[[\s\S]*?])\s*,/, html);
  if (!json) return [];
  var list;
  try {
    list = JSON.parse(json);
  } catch (e) {
    return [];
  }
  var subs = [];
  for (var i = 0; i < list.length; i++) {
    var item = list[i] || {};
    var url = String(item.file || "");
    if (!/^https?:/.test(url)) continue;
    var kind = String(item.kind || "");
    if (kind && kind !== "captions") continue;
    var label = String(item.label || item.lang || "Altyazi").trim();
    subs.push({ url: url, language: String(item.lang || "").trim() || label, name: label + " [SetFilmIzle]" });
  }
  return subs;
}

/** Bridge blobs are an XOR of two base64 strings; one part is the nested player URL. */
function xorUrl(first, second) {
  var a = b64(first);
  var b = b64(second);
  if (!a.length || !b.length) return null;
  var out = "";
  for (var i = 0; i < a.length; i++) out += String.fromCharCode(a.charCodeAt(i) ^ b.charCodeAt(i % b.length));
  var parts = out.split("|");
  for (var j = 0; j < parts.length; j++) if (/^https?:/.test(parts[j])) return parts[j].trim();
  return null;
}

function episodeUrl(seriesHtml, want, wantEp) {
  var re = /\/bolum\/([^"]*?)-(\d+)-sezon-(\d+)-bolum\//g;
  var m;
  var fallback = null;
  while ((m = re.exec(seriesHtml)) !== null) {
    var href = abs(BASE, m[0]);
    if (parseInt(m[2], 10) === want && parseInt(m[3], 10) === wantEp) return href;
    if (!fallback) fallback = href;
  }
  return fallback;
}

/**
 * Two bridge flavours share this tail: a nested player URL behind cerceve("b2",…),
 * or an inline STF_KOPRU.src manifest path. Both end at a page naming its HLS src.
 */
function playerRows(html, pageUrl) {
  var nested = /cerceve\(\s*"b2"\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\)/.exec(html);
  if (nested) {
    var next = xorUrl(nested[1], nested[2]);
    if (!next) return Promise.resolve([]);
    return text(next, { referer: "https://setplay.sbs/" }).then(function (body) {
      return playerRows(body, next);
    });
  }
  var src = first(/src:\s*"([^"]+)"/, html);
  if (!src) return Promise.resolve([]);
  var url = abs(pageUrl, src.replace(/\\\//g, "/"));
  var subs = parseSubtitles(html);
  return text(url, { referer: pageUrl, mobile: true })
    .then(function (master) {
      return [{ url: url, quality: qualityOf(master), subs: subs, referer: pageUrl }];
    })
    .catch(function () {
      return [{ url: url, quality: "auto", subs: subs, referer: pageUrl }];
    });
}

/** pageUrl -> admin-ajax stream bridge -> player URL -> HLS rows. */
function streamsFor(pageUrl) {
  return text(pageUrl)
    .then(function (html) {
      var postId = first(/id="stfPlayer"[^>]*data-post-id="(\d+)"/, html);
      var nonce = first(/video:\s*"([^"]+)"/, html) || "";
      var players = [];
      var re = /data-player-name="([^"]*)"\s+data-part-key="([^"]*)"/g;
      var m;
      while ((m = re.exec(html)) !== null) players.push({ name: m[1] || "SetPlay", key: m[2] || "" });
      if (!postId) return [];
      if (!players.length) players.push({ name: "SetPlay", key: "" });

      return Promise.all(
        players.map(function (player) {
          return text(BASE + "/wp-admin/admin-ajax.php", {
            method: "POST",
            headers: { "X-Requested-With": "XMLHttpRequest", "Content-Type": "application/x-www-form-urlencoded" },
            referer: pageUrl,
            body:
              "action=get_video_url&nonce=" +
              encodeURIComponent(nonce) +
              "&post_id=" +
              encodeURIComponent(postId) +
              "&player_name=" +
              encodeURIComponent(player.name) +
              "&part_key=" +
              encodeURIComponent(player.key) +
              "&taze=1"
          })
            .then(function (body) {
              var data = JSON.parse(body).data || {};
              var stream = data.stream || {};
              if (stream.type !== "bridge") return null;
              var bridge = String(stream.url || "");
              if (!/^http/.test(bridge)) return null;
              return bridge;
            })
            .then(function (bridgeUrl) {
              if (!bridgeUrl) return null;
              return text(bridgeUrl, { referer: pageUrl }).then(function (bridgeHtml) {
                return playerRows(bridgeHtml, bridgeUrl);
              });
            })
            .catch(function () {
              return null;
            });
        })
      ).then(function (groups) {
        var rows = [];
        var seen = {};
        var subs = [];
        for (var i = 0; i < groups.length; i++) {
          if (!groups[i]) continue;
          for (var j = 0; j < groups[i].length; j++) {
            var item = groups[i][j];
            if (seen[item.url]) continue;
            seen[item.url] = true;
            if (!subs.length) subs = item.subs;
            rows.push({
              name: "SetFilmIzle",
              title: "SetFilmIzle - " + item.quality + " [HLS]",
              url: item.url,
              quality: item.quality,
              headers: { Referer: item.referer, "User-Agent": MOBILE_UA, Accept: "*/*" },
              subtitles: subs
            });
          }
        }
        return rows;
      });
    });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var isTv = mediaType === "tv";

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return null;
      return text(BASE + "/?s=" + encodeURIComponent(title)).then(function (html) {
        var cards = searchCards(html);
        var wanted = isTv ? cards.filter(function (c) { return c.url.indexOf("/dizi/") !== -1; }) : cards;
        return pick(wanted.length ? wanted : cards, title);
      });
    })
    .then(function (card) {
      if (!card) return null;
      if (!isTv) return streamsFor(card.url);
      return text(card.url).then(function (seriesHtml) {
        var url = episodeUrl(seriesHtml, want, wantEp);
        return url ? streamsFor(url) : [];
      });
    })
    .then(function (rows) {
      return rows || [];
    })
    .catch(function (e) {
      console.log("[bb-nuvio][setfilmizle] " + (e && e.message));
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

/* Dil etiketi: akis basliginin kalitesinin yanina sunucunun destekledigi dili yazar.
   Kanit yoksa etiket yazilmaz (uydurma yasak): baslikta dublaj/altyazi varsa dokunulmaz,
   altyazi dosyasi eklenmis akisa "Altyazili" eklenir. */
var __langLabeled = module.exports.getStreams;
module.exports.getStreams = function (id, mediaType, season, episode) {
  return __langLabeled.apply(this, arguments).then(function (streams) {
    return (streams || []).map(function (s) {
      if (!s || !s.title || /dublaj|altyaz/i.test(String(s.title))) return s;
      if (s.subtitles && s.subtitles.length) s.title = String(s.title) + " \u2022 Altyaz\u0131l\u0131";
      return s;
    });
  });
};
