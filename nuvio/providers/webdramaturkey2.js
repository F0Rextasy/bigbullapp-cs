// @provider {"id":"webdramaturkey2", "name":"WebdramaTurkey2", "types":["movie", "tv"], "domain":"webdramaturkey2.com", "formats":["m3u8"], "sample":"tt29569035:tv:1:1", "description":"WebdramaTurkey2 Kore/Cin/Japon dizileri, filmleri ve animeleri (Turkce altyazili).", "version":"1.0.5"}
/**
 * webdramaturkey2 — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /arama/<q> -> dizi|film page -> episode -> /ajax/embed -> video.php
 *       -> #main-iframe (FirePlayer) -> getVideo -> HLS variants.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://webdramaturkey2.com";
var ID = "webdramaturkey2";

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
  if (o.method === "POST") headers["Content-Type"] = "application/x-www-form-urlencoded; charset=UTF-8";
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
  if (/^https?:/i.test(href)) return href;
  var origin = /^(https?:\/\/[^/]+)/.exec(base);
  if (href.charAt(0) === "/") return origin ? origin[1] + href : href;
  return base.replace(/[^/]*$/, "") + href;
}

function norm(value) {
  return String(value || "")
    .replace(/ı/g, "i").replace(/ş/g, "s").replace(/ğ/g, "g")
    .replace(/ü/g, "u").replace(/ö/g, "o").replace(/ç/g, "c")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "");
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

function rowsFromPlaylist(masterUrl, playlist, referer) {
  var list = [], lines = String(playlist || "").split(/\r?\n/);
  for (var i = 0; i < lines.length; i++) {
    if (lines[i].indexOf("#EXT-X-STREAM-INF") !== 0) continue;
    var height = first(/RESOLUTION=\d+x(\d+)/, lines[i]);
    var j = i + 1;
    while (j < lines.length && (!lines[j].trim() || lines[j].charAt(0) === "#")) j++;
    if (j >= lines.length) continue;
    list.push({ url: abs(masterUrl, lines[j].trim()), quality: height ? height + "p" : "" });
  }
  if (!list.length) list = [{ url: masterUrl, quality: "" }];
  return list.map(function (variant) {
    return {
      name: ID,
      title: ID + " - " + (variant.quality || "auto") + " [HLS]",
      url: variant.url,
      quality: variant.quality || "auto",
      headers: { Referer: referer },
      subtitles: []
    };
  });
}

/** video.php wrapper -> the real player iframe (#main-iframe). */
function resolveTarget(wrapper, referer) {
  return text(wrapper, { referer: referer }).then(function (body) {
    return first(/id=["']main-iframe["'][^>]*\ssrc=["']([^"']+)["']/, body);
  });
}

/** FirePlayer: POST /player/index.php?data=<id>&do=getVideo -> signed HLS link. */
function firePlayer(target, referer) {
  var m = /^(https?:\/\/[^/]+)\/(?:video|embed|e)\/([0-9a-zA-Z]{16,})/.exec(target);
  if (!m) return Promise.resolve([]);
  var origin = m[1], videoId = m[2];
  return text(origin + "/player/index.php?data=" + videoId + "&do=getVideo", {
    method: "POST",
    referer: target,
    headers: { "X-Requested-With": "XMLHttpRequest", Origin: origin },
    body: "hash=" + encodeURIComponent(videoId) + "&r=" + encodeURIComponent(referer)
  }).then(function (body) {
    var panel = JSON.parse(body);
    var stream = String(panel.securedLink || panel.videoSource || "").trim();
    if (!/^https?:/.test(stream)) return [];
    if (!panel.hls && stream.indexOf(".m3u8") === -1) {
      return [{ name: ID, title: ID + " - auto [MP4]", url: stream, quality: "auto", headers: { Referer: origin + "/" }, subtitles: [] }];
    }
    return text(stream, { referer: target }).then(function (playlist) {
      return rowsFromPlaylist(stream, playlist, target);
    });
  });
}

function attempt(source, referer) {
  return text(BASE + "/ajax/embed", {
    method: "POST",
    referer: referer,
    headers: { "X-Requested-With": "XMLHttpRequest" },
    body: "id=" + encodeURIComponent(source.id)
  }).then(function (body) {
    var wrapper = first(/<iframe[^>]*\ssrc=["']([^"']+)["']/, body);
    if (!wrapper) return [];
    wrapper = abs(BASE, wrapper);
    var target = wrapper.indexOf("/video.php") !== -1 ? resolveTarget(wrapper, referer) : Promise.resolve(wrapper);
    return target.then(function (player) {
      if (!player) return [];
      if (/\.(?:mp4|m3u8)(?:[?#]|$)/i.test(player)) return rowsFromPlaylist(player, "", player);
      return firePlayer(player, referer);
    });
  });
}

/** Sources are tried in order; Cloudflare rate-limits /ajax/embed, so stop at the first hit. */
function trySources(sources, index, referer) {
  if (index >= sources.length) return Promise.resolve([]);
  return attempt(sources[index], referer)
    .catch(function () {
      return [];
    })
    .then(function (rows) {
      return rows.length ? rows : trySources(sources, index + 1, referer);
    });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var wantSeason = parseInt(season, 10) || 1;
  var wantEpisode = parseInt(episode, 10) || 1;
  var wanted = "";
  var entry = null;
  var pageUrl = null;

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      wanted = title;
      if (!title) return null;
      return text(BASE + "/arama/" + encodeURIComponent(title));
    })
    .then(function (search) {
      if (!search) return null;
      var re = /<a[^>]+href="([^"]+)"[^>]*class="list-title"[^>]*>([\s\S]{0,200}?)<\/a>/g, m;
      var key = norm(wanted), best = null;
      while ((m = re.exec(search)) !== null) {
        var href = abs(BASE, m[1]);
        var name = norm(m[2].replace(/<[^>]+>/g, " "));
        if (!href || !name || !/\/(?:dizi|film|anime)\//.test(href)) continue;
        var score = name === key ? 3 : name.indexOf(key) === 0 || key.indexOf(name) === 0 ? 2 : name.indexOf(key) !== -1 ? 1 : 0;
        if (score && (!best || score > best.score)) best = { url: href, score: score };
      }
      if (!best) return null;
      entry = best.url;
      pageUrl = entry;
      return text(entry);
    })
    .then(function (page) {
      if (!page) return null;
      if (entry.indexOf("/film/") !== -1) return page;
      var re = /href="([^"]*\/(\d+)-sezon\/(\d+)-bolum)"/g, m, target = null;
      while ((m = re.exec(page)) !== null) {
        if (parseInt(m[2], 10) === wantSeason && parseInt(m[3], 10) === wantEpisode) target = abs(BASE, m[1]);
      }
      var episodeUrl = target || entry.replace(/\/$/, "") + "/" + wantSeason + "-sezon/" + wantEpisode + "-bolum";
      return text(episodeUrl, { referer: entry }).then(function (episodePage) {
        pageUrl = episodeUrl;
        return episodePage;
      });
    })
    .then(function (page) {
      if (!page) return [];
      var sources = [], re = /data-embed="([^"]+)"/g, m;
      while ((m = re.exec(page)) !== null) {
        var id = m[1].trim();
        if (id && sources.length < 6) {
          var seen = false;
          for (var i = 0; i < sources.length; i++) if (sources[i].id === id) seen = true;
          if (!seen) sources.push({ id: id });
        }
      }
      return trySources(sources, 0, pageUrl);
    })
    .catch(function (e) {
      console.log("[bb-nuvio][" + ID + "] " + (e && e.message));
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
