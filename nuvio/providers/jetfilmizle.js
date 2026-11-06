// @provider {"id":"jetfilmizle", "name":"JetFilmizle", "types":["movie"], "domain":"jetfilmizle.app", "formats":["m3u8", "mp4"], "sample":"tt0133093:movie:1:1", "description":"JetFilmizle Türkçe dublaj/altyazılı film arşivi (godrive + videopark).", "version":"1.1.0", "verified":true}
/**
 * jetfilmizle — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /arama -> strict card match -> film page (film_id) ->
 *       POST /jetplayer -> iframe -> videopark apollo / godrive resolvers.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://jetfilmizle.app";

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
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
  if (!m) return null;
  return m[1] === undefined ? m[0] : m[1];
}

function decode(s) {
  return String(s || "")
    .replace(/&#x([0-9a-f]+);/gi, function (_, h) { return String.fromCharCode(parseInt(h, 16)); })
    .replace(/&#(\d+);/g, function (_, d) { return String.fromCharCode(parseInt(d, 10)); })
    .replace(/&amp;/g, "&").replace(/&quot;/g, '"').replace(/&#039;/g, "'");
}

function abs(base, href) {
  if (!href) return null;
  if (/^https?:/i.test(href)) return href;
  if (href.indexOf("//") === 0) return "https:" + href;
  var origin = /^(https?:\/\/[^/]+)/.exec(base);
  if (href.charAt(0) === "/") return origin ? origin[1] + href : href;
  return base.replace(/[^/]*$/, "") + href;
}

function norm(s) {
  return decode(s)
    .toLowerCase()
    .replace(/[\u0131]/g, "i").replace(/[\u015f]/g, "s").replace(/[\u011f]/g, "g")
    .replace(/[\u00fc]/g, "u").replace(/[\u00f6]/g, "o").replace(/[\u00e7]/g, "c").replace(/[\u00e2]/g, "a")
    .replace(/\([^)]*\)/g, " ")
    .replace(/\b(?:izle|film|filmi|full|hd|1080p|720p|turkce|dublaj|altyazili)\b/g, " ")
    .replace(/[^a-z0-9]+/g, " ")
    .trim()
    .replace(/^(?:the|a|an)\s+/, "");
}

function pick(items, title) {
  var want = norm(title);
  if (!want) return null;
  var subset = null;
  for (var i = 0; i < items.length; i++) {
    var have = norm(items[i].title);
    if (!have) continue;
    if (have === want) return items[i];
    if (!subset && have.indexOf(want + " ") === 0) subset = items[i];
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

function search(title) {
  var q = String(title).replace(/^(?:the|a|an)\s+/i, "");
  return text(BASE + "/arama?q=" + encodeURIComponent(q)).then(function (html) {
    var items = [];
    var re = /<a href="([^"]*\/film\/[^"]+)"[^>]*title="([^"]*)"/g;
    var m;
    while ((m = re.exec(html)) !== null) {
      var u = abs(BASE, m[1]);
      if (u && u.indexOf(BASE) === 0) items.push({ url: u, title: decode(m[2]) });
    }
    return items;
  });
}

function apollo(playerUrl) {
  var id = first(/[?&]id=([^&]+)/, playerUrl);
  if (!id) return Promise.resolve([]);
  var base = playerUrl.replace(/[?#].*$/, "");
  return text(base + "get_video.php", {
    method: "POST",
    headers: { "X-Requested-With": "XMLHttpRequest", "Content-Type": "application/x-www-form-urlencoded" },
    body: "id=" + id,
    referer: playerUrl
  }).then(function (body) {
    var hls = first(/"hlsUrl"\s*:\s*"([^"]+)"/, body);
    if (!hls) return [];
    return [{ url: abs(base, hls.replace(/\\\//g, "/")), referer: playerUrl, quality: "auto" }];
  });
}

function godrive(playerUrl, referer) {
  return text(playerUrl, { referer: referer }).then(function (html) {
    var base = first(/WORKER_BASE\s*=\s*"([^"]+)"/, html);
    var videoId = first(/VIDEO_ID\s*=\s*(\d+)/, html);
    if (!base || !videoId) return [];
    base = base.replace(/\\\//g, "/").replace(/\/+$/, "");
    return text(base + "/v/" + videoId + "/info", { referer: playerUrl }).then(function (info) {
      var qualities = [];
      var qs = first(/"qualities"\s*:\s*\[([^\]]*)\]/, info) || "";
      var parts = qs.split(",");
      for (var i = 0; i < parts.length; i++) {
        var n = parseInt(parts[i], 10);
        if (n) qualities.push(n);
      }
      if (!qualities.length) return [{ url: base + "/v/" + videoId, referer: base + "/", quality: "auto" }];
      var out = [];
      for (i = 0; i < qualities.length; i++) {
        out.push({ url: base + "/v/" + videoId + "?q=" + qualities[i], referer: base + "/", quality: qualities[i] + "p" });
      }
      return out;
    });
  });
}

function generic(playerUrl, referer) {
  return text(playerUrl, { referer: referer }).then(function (html) {
    var out = [];
    var m = first(/"file"\s*:\s*"([^"]+\.(?:m3u8|mp4)[^"]*)"/, html) ||
      first(/https?:\/\/[^"'\s<>]+?\.(?:m3u8|mp4)[^"'\s<>]*/, html);
    var url = m ? m.replace(/\\\//g, "/") : null;
    if (url) out.push({ url: abs(playerUrl, url), referer: referer, quality: "auto" });
    return out;
  });
}

function resolvePlayer(playerUrl, referer) {
  var run = playerUrl.indexOf("videopark.top/apollo/") !== -1 ? apollo
    : playerUrl.indexOf("videopark.top/go/") !== -1 ? godrive
    : generic;
  return run(playerUrl, referer).catch(function () { return []; });
}

function getStreams(tmdbId, mediaType, season, episode) {
  if (mediaType === "tv") return Promise.resolve([]);
  var film = null;
  var referer = null;
  var rows = [];

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return null;
      return search(title).then(function (items) {
        var hit = pick(items, title);
        return hit ? hit.url : null;
      });
    })
    .then(function (url) {
      if (!url) return null;
      referer = url;
      return text(url);
    })
    .then(function (page) {
      if (!page) return null;
      var filmId = first(/input[^>]*name=["']?film_id["']?[^>]*value=["']([^"']+)/i, page) ||
        first(/input[^>]*value=["']([^"']+)["'][^>]*name=["']?film_id/i, page);
      if (!filmId) return null;
      var slots = [];
      var groups = [];
      var re = /data-player-group="([^"]*)"/g;
      var m;
      while ((m = re.exec(page)) !== null) groups.push({ type: m[1], at: m.index });
      for (var g = 0; g < groups.length; g++) {
        var chunk = page.slice(groups[g].at, g < groups.length - 1 ? groups[g + 1].at : page.length);
        var br = /data-source-index="(\d+)"[^>]*>\s*([^<]*)/g;
        var b;
        while ((b = br.exec(chunk)) !== null) {
          slots.push({ type: groups[g].type, index: b[1], label: b[2].trim() });
        }
      }
      film = { id: filmId, slots: slots.slice(0, 6) };
      return film.slots.length ? text(BASE + "/jetplayer", {
        method: "POST",
        headers: { "X-Requested-With": "XMLHttpRequest", "Content-Type": "application/x-www-form-urlencoded" },
        body: "film_id=" + film.id + "&source_index=" + film.slots[0].index + "&player_type=" + encodeURIComponent(film.slots[0].type),
        referer: referer
      }) : null;
    })
    .then(function (html) {
      if (!film || !film.slots.length) return [];
      var seen = {};
      var chain = function (slotAt, iframe) {
        if (slotAt >= film.slots.length) return Promise.resolve();
        var slot = film.slots[slotAt];
        var step = iframe
          ? Promise.resolve(iframe)
          : text(BASE + "/jetplayer", {
              method: "POST",
              headers: { "X-Requested-With": "XMLHttpRequest", "Content-Type": "application/x-www-form-urlencoded" },
              body: "film_id=" + film.id + "&source_index=" + slot.index + "&player_type=" + encodeURIComponent(slot.type),
              referer: referer
            }).then(function (body) {
              return first(/<iframe[^>]+src=['"]([^'"]+)/, body);
            });
        return step.then(function (playerUrl) {
          if (!playerUrl) return chain(slotAt + 1, null);
          return resolvePlayer(abs(BASE, playerUrl), referer).then(function (found) {
            var desc = [slot.type, slot.label].filter(Boolean).join(" · ");
            for (var i = 0; i < found.length; i++) {
              if (seen[found[i].url]) continue;
              seen[found[i].url] = 1;
              rows.push({
                name: "JetFilmizle",
                title: "JetFilmizle - " + (desc || "Kaynak"),
                url: found[i].url,
                quality: found[i].quality,
                headers: { Referer: found[i].referer, "User-Agent": UA },
                subtitles: []
              });
            }
            return chain(slotAt + 1, null);
          });
        });
      };
      return chain(0, html ? first(/<iframe[^>]+src=['"]([^'"]+)/, html) : null);
    })
    .then(function () {
      return rows;
    })
    .catch(function (e) {
      console.log("[bb-nuvio][jetfilmizle] " + (e && e.message));
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

/* Dil etiketi (AGENTS.md): Nuvio karti "quality \u2022 ..." satirini gosterir; dil etiketi kalitenin yanina yazilir. */
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
