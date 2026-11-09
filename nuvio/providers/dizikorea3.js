// @provider {"id":"dizikorea3", "name":"Dizikorea3", "types":["tv"], "domain":"dizikorea3.com", "formats":["m3u8"], "sample":"tt43088191:tv:1:1", "description":"Dizikorea3 Kore dizileri arsivi (HLS + Turkce altyazi).", "version":"1.0.10"}
/**
 * dizikorea3 — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /sayfa/1?s= -> /dizi/<slug> -> /sezon-N/bolum-M -> player iframes.
 * The site ignores `?search=`; WordPress-style `?s=` is the working search, paged by path.
 * Player iframes are lazy-loaded, so the real URL lives in data-src.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://dizikorea3.com";

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
  if (o.origin) headers.Origin = o.origin;
  if (o.ajax) headers["X-Requested-With"] = "XMLHttpRequest";
  if (o.form) headers["Content-Type"] = "application/x-www-form-urlencoded";
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
  href = String(href).replace(/&amp;/g, "&").trim();
  if (/^https?:/i.test(href)) return href;
  var origin = /^(https?:\/\/[^/]+)/.exec(base);
  if (href.charAt(0) === "/") return origin ? origin[1] + href : href;
  return base.replace(/[^/]*$/, "") + href;
}

/** Nuvio's home rows pass a numeric TMDB id; Wikidata maps it to the English title the site indexes. */
function numericTitle(id, mediaType) {
  if (!/^\d+$/.test(String(id || ""))) return Promise.resolve("");
  var prop = mediaType === "movie" ? "P4947" : "P4983";
  var api = "https://www.wikidata.org/w/api.php?format=json";
  return text(api + "&action=query&list=search&srlimit=1&srsearch=" +
      encodeURIComponent("haswbstatement:" + prop + "=" + id))
    .then(function (body) {
      var hits = (JSON.parse(body).query || {}).search || [];
      if (!hits.length) return "";
      return text(api + "&action=wbgetentities&props=labels&languages=en|tr&ids=" + hits[0].title);
    })
    .then(function (body) {
      var entities = JSON.parse(body).entities || {};
      for (var key in entities) {
        var labels = entities[key].labels || {};
        var label = (labels.en || labels.tr || {}).value;
        if (label) return String(label).replace(/\s*\([^)]*\)\s*$/, "").trim();
      }
      return "";
    })
    .catch(function () {
      return "";
    });
}

function titleOf(id, mediaType) {
  if (/^__alt__:/.test(String(id))) return Promise.resolve(decodeURIComponent(String(id).slice(8)));
  if (/^\d+$/.test(String(id || ""))) return numericTitle(id, mediaType);
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

/** `a.poster-card` blocks; the title only lives in the poster's alt text. */
function cards(html) {
  var out = [];
  var re = /<a[^>]*class="[^"]*poster-card[^"]*"[^>]*>([\s\S]{0,900}?)<\/a>/g;
  var block;
  while ((block = re.exec(html)) !== null) {
    var href = first(/href="([^"]*)"/, block[0]);
    var alt = first(/<img[^>]+alt="([^"]*)"/, block[0]);
    if (!href || href.indexOf("/dizi/") === -1) continue;
    out.push({ url: abs(BASE, href), title: String(alt || "").trim() });
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

/** `/dizi/<slug>/sezon-<n>/bolum-<m>`. */
function episodes(html, base) {
  var out = [];
  var seen = {};
  var re = /href="([^"]*\/sezon-(\d+)\/bolum-(\d+))"/g;
  var m;
  while ((m = re.exec(html)) !== null) {
    var url = abs(base, m[1]);
    if (!url || seen[url]) continue;
    seen[url] = 1;
    out.push({ url: url, season: parseInt(m[2], 10), episode: parseInt(m[3], 10) });
  }
  return out;
}

/** playerdkorea/FirePlayer answers JSON with `securedLink`/`videoSource`. */
function firePlayer(embed, found) {
  var id = String(embed).split("/").pop();
  var endpoint = embed.indexOf("/player/index.php") !== -1
    ? embed + "&do=getVideo"
    : embed.replace("/video/" + id, "/player/index.php?data=" + id + "&do=getVideo");
  return text(endpoint, {
    method: "POST",
    form: true,
    ajax: true,
    referer: BASE + "/",
    origin: "https://playerdkorea.xyz",
    body: "hash=" + id + "&r=" + encodeURIComponent(BASE + "/")
  })
    .then(function (body) {
      var json = JSON.parse(body);
      // Both keys name the same playlist (`master.m3u8?md5=…` / `master.txt`), and the CDN
      // stalls ~10s before rejecting a dead token, so probe it once.
      var stream = String(json.securedLink || json.videoSource || "");
      if (!/^https?:/i.test(stream)) return found;
      if (!found.some(function (item) { return item.url === stream; })) {
        found.push({ url: stream, frame: embed, hls: true });
      }
      return found;
    })
    .catch(function () {
      return found;
    });
}


/** Master playlist names its variant; a playlist that is not #EXTM3U is a dead token. */
function quality(item) {
  if (!item.hls) return Promise.resolve("auto");
  return text(item.url, { referer: item.frame })
    .then(function (body) {
      if (body.indexOf("#EXTM3U") !== 0) return null;
      var best = 0;
      var re = /RESOLUTION=\d+x(\d+)/g;
      var m;
      while ((m = re.exec(body)) !== null) {
        var height = parseInt(m[1], 10) || 0;
        if (height > best) best = height;
      }
      return best ? best + "p" : "auto";
    })
    .catch(function () {
      return null;
    });
}

/** `/ara?q=` answers JSON (title/url); the archive page `?s=` is the fallback. */
function search(title) {
  return text(BASE + "/ara?q=" + encodeURIComponent(title))
    .then(function (body) {
      var items = JSON.parse(body).items || [];
      var list = [];
      for (var i = 0; i < items.length; i++) {
        var item = items[i] || {};
        var url = abs(BASE, item.url || (item.slug ? "/dizi/" + item.slug : ""));
        if (!url) continue;
        list.push({ url: url, title: String(item.title || item.slug || "") });
      }
      if (list.length) return list;
      return text(BASE + "/sayfa/1?s=" + encodeURIComponent(title)).then(cards);
    })
    .catch(function () {
      return text(BASE + "/sayfa/1?s=" + encodeURIComponent(title)).then(cards);
    });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var episodeUrl = null;
  var frames = [];

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return null;
      return search(title).then(function (list) {
        return pick(list, title);
      });
    })
    .then(function (series) {
      if (!series) return null;
      return text(series.url).then(function (detail) {
        var list = episodes(detail, series.url);
        for (var i = 0; i < list.length; i++) {
          if (list[i].season === want && list[i].episode === wantEp) {
            episodeUrl = list[i].url;
            break;
          }
        }
        return episodeUrl;
      });
    })
    .then(function (url) {
      if (!url) return [];
      return text(url).then(function (html) {
        var re = /<iframe[^>]+(?:data-src|src)="([^"]+)"/g;
        var m;
        while ((m = re.exec(html)) !== null) {
          var frame = abs(url, m[1]);
          if (frame && /^https?:/i.test(frame) && frames.indexOf(frame) === -1) frames.push(frame);
        }
        return url;
      });
    })
    .then(function (referer) {
      return frames.reduce(function (chain, frame) {
        return chain.then(function (found) {
          if (frame.indexOf("playerdkorea") !== -1 || frame.indexOf("playerkorea") !== -1) {
            return firePlayer(frame, found);
          }
          return text(frame, { referer: referer })
            .then(function (html) {
              var body = html.replace(/\\\//g, "/");
              var re = /https?:\/\/[^"'\s<>\\]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>\\]*)?/g;
              var m;
              while ((m = re.exec(body)) !== null) {
                if (found.some(function (item) { return item.url === m[0]; })) continue;
                found.push({ url: m[0], frame: frame, hls: m[0].indexOf(".m3u8") !== -1 });
              }
              return found;
            })
            .catch(function () {
              return found;
            });
        });
      }, Promise.resolve([]));
    })
    .then(function (found) {
      return found.reduce(function (chain, item) {
        return chain.then(function (rows) {
          return quality(item).then(function (label) {
            if (!label) return rows;
            rows.push({
              name: "dizikorea3",
              title: "dizikorea3 - " + label + (item.hls ? " [HLS]" : " [MP4]"),
              url: item.url,
              quality: label,
              headers: { Referer: item.frame },
              subtitles: []
            });
            return rows;
          });
        });
      }, Promise.resolve([]));
    })
    .catch(function (e) {
      console.log("[bb-nuvio][dizikorea3] " + (e && e.message));
      return [];
    });
}

module.exports = { getStreams };

var __origGetStreams = module.exports.getStreams;
module.exports.getStreams = function (id, mediaType, season, episode) {
  var raw = String(id || "");
  if (!/^\d+$/.test(raw)) return __origGetStreams(raw, mediaType, season, episode);
  var tv = mediaType !== "movie";
  var headers = { "User-Agent": UA, "Accept": "application/json" };
  var api = "https://www.wikidata.org/w/api.php?format=json";
  var prop = tv ? "P4983" : "P4947";
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
    .catch(function () { return ""; })
    .then(function (imdb) {
      return __origGetStreams(imdb || raw, mediaType, season, episode);
    });
};


/* Cinemeta basligi yerellestirir ("Gibi" -> "As If"); sonuc bossa Wikidata TR/EN basliklariyla yeniden dene. */
function __altTitles(raw, mediaType) {
  var h = { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36", "Accept": "application/json" };
  var api = "https://www.wikidata.org/w/api.php?format=json";
  var stmt = /^tt\d+$/.test(raw) ? "P345=" + raw : (mediaType === "movie" ? "P4947=" : "P4983=") + raw;
  return fetch(api + "&action=query&list=search&srlimit=1&srsearch=" + encodeURIComponent("haswbstatement:" + stmt), { headers: h })
    .then(function (r) { return r.ok ? r.json() : null; })
    .then(function (j) {
      var hits = (j && j.query && j.query.search) || [];
      if (!hits.length) return null;
      return fetch(api + "&action=wbgetentities&props=labels|aliases&languages=tr|en&ids=" + hits[0].title, { headers: h }).then(function (r) { return r.ok ? r.json() : null; });
    })
    .then(function (j) {
      var out = [], seen = {}, ents = (j && j.entities) || {}, k, l, a, i;
      function add(v) {
        v = String(v || "").replace(/\s*\([^)]*\)\s*$/, "").trim();
        if (v && !seen[v.toLowerCase()]) { seen[v.toLowerCase()] = 1; out.push(v); }
      }
      for (k in ents) {
        l = ents[k].labels || {}; a = ents[k].aliases || {};
        add((l.tr || {}).value); add((l.en || {}).value);
        for (i = 0; i < (a.tr || []).length && i < 2; i++) add(a.tr[i].value);
      }
      return out;
    })
    .catch(function () { return []; });
}
var __cinemetaGetStreams = module.exports.getStreams;
module.exports.getStreams = function (id, mediaType, season, episode) {
  var raw = String(id || "");
  return __cinemetaGetStreams(raw, mediaType, season, episode).then(function (streams) {
    if ((streams && streams.length) || /^__alt__:/.test(raw)) return streams || [];
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
};

/* Dil etiketi (AGENTS.md): Nuvio karti "quality \u2022 ..." satirini gosterir; dil etiketi kalitenin yanina yazilir.
   Kanit: baslikta dublaj/altyazi, akisa eklenmis altyazi dosyasi ya da sitenin yalniz altyazili olmasi. Kanit yoksa etiket yok. */
var __subOnly = true;
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
