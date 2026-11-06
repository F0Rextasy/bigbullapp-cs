// @provider {"id":"tlctr", "name":"TLC Türkiye", "types":["tv"], "domain":"tlctv.com.tr", "formats":["m3u8"], "sample":"tt15334452:tv:1:1", "description":"TLC Türkiye program arşivi; bölümler DYG imzalı HLS olarak açılır.", "version":"1.0.2"}
/**
 * tlctr — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /arama -> program -> ajax episodes -> data-video-code -> DYG redirect -> HLS.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
var BASE = "https://www.tlctv.com.tr";
var DYG_SECRET = "NtvApiSecret2014*";
var MAX_PAGES = 10;

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
  if (o.headers) for (var k in o.headers) headers[k] = o.headers[k];
  return fetch(url, { method: o.method || "GET", headers: headers, body: o.body, redirect: "follow" })
    .then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status + " " + url);
      return r.text().then(function (body) { return { body: body, url: r.url }; });
    });
}

function first(re, body) {
  var m = re.exec(String(body || ""));
  return m ? m[1] : null;
}

function titleOf(id, mediaType) {
  var kind = mediaType === "tv" ? "series" : "movie";
  return text("https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json")
    .then(function (r) {
      var meta = JSON.parse(r.body).meta || {};
      return String(meta.name || "").trim();
    })
    .catch(function () {
      return "";
    });
}

// Turkish letters folded so "A\u011f\u0131r Ya\u015famlar" compares like "agir-yasamlar".
function norm(value) {
  return String(value || "").toLowerCase()
    .replace(/[\u0131\u0130]/g, "i").replace(/[\u015f\u015e]/g, "s").replace(/[\u011f\u011e]/g, "g")
    .replace(/[\u00fc\u00dc]/g, "u").replace(/[\u00f6\u00d6]/g, "o").replace(/[\u00e7\u00c7]/g, "c")
    .replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "");
}

function variants(master, playlist) {
  var origin = /^(https?:\/\/[^/]+)/.exec(master)[1];
  var base = master.replace(/[?#].*$/, "").replace(/[^/]*$/, "");
  var out = [], height = 0, lines = String(playlist || "").split("\n");
  for (var i = 0; i < lines.length; i++) {
    var line = lines[i].trim();
    if (line.indexOf("#EXT-X-STREAM-INF") === 0) {
      height = parseInt(first(/RESOLUTION=\d+x(\d+)/, line), 10) || 0;
    } else if (line && line.charAt(0) !== "#" && height > 0) {
      out.push({ quality: height + "p", url: /^https?:/i.test(line) ? line : line.charAt(0) === "/" ? origin + line : base + line });
      height = 0;
    }
  }
  return out;
}

// The fragment pairs one anchor with one title; the thumbnail block sits between them, so a
// single windowed regex is fragile. Collect both lists in order and zip them.
function episodeLinks(fragment) {
  var html = String(fragment || "");
  var urls = [], titles = [], re = /href="(https?:\/\/[^"]+)"|item-meta-title[^>]*>\s*(?:<strong>)?([^<]*)/g, m;
  while ((m = re.exec(html)) !== null) {
    if (m[1]) urls.push(m[1]);
    else titles.push(String(m[2] || "").trim());
  }
  var out = [];
  for (var i = 0; i < urls.length && i < titles.length; i++) out.push({ url: urls[i], label: titles[i] });
  return out;
}

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var wanted = "";
  var videoUrl = null;

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      wanted = norm(title);
      if (!wanted) return null;
      return text(BASE + "/arama?sorgu=" + encodeURIComponent(title));
    })
    .then(function (res) {
      if (!res) return null;
      var best = null, bestScore = 0;
      var re = /GAEventTracker\('SEARCH_[A-Z_]+', '[A-Z_]+', '([^']+)'\);?" href="([^"]+)"/g, m;
      while ((m = re.exec(res.body)) !== null) {
        var name = norm(m[1]);
        var score = 0;
        if (name === wanted) score = 3;
        else if (name.indexOf(wanted) === 0) score = 2;
        else if (name.indexOf(wanted) > -1 || wanted.indexOf(name) > -1) score = 1;
        if (score > bestScore) {
          bestScore = score;
          best = m[2];
        }
      }
      if (!best) return null;
      return text(best);
    })
    .then(function (res) {
      if (!res) return [];
      var program = res.url;
      var programId = first(/data-program-id="(\d+)"/, res.body);

      if (!programId) {
        videoUrl = program;
        return null;
      }

      var seasons = [], re = /<option[^>]*value="([^"]+)"/g, m;
      while ((m = re.exec(res.body)) !== null) if (m[1]) seasons.push(m[1]);
      if (!seasons.length) seasons = [""];

      function page(seasonIndex, pageIndex) {
        return text(BASE + "/ajax/more", {
          method: "POST",
          body: "type=episodes&program_id=" + programId + "&page=" + pageIndex + "&season=" + encodeURIComponent(seasons[seasonIndex]),
          referer: program,
          headers: { "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8", "X-Requested-With": "XMLHttpRequest" }
        }).then(function (frag) {
          var found = episodeLinks(frag.body);
          if (!found.length) return null;
          for (var i = 0; i < found.length; i++) {
            var marker = /(\d+)-sezon-(\d+)-bolum/.exec(found[i].url);
            if (marker && parseInt(marker[1], 10) === want && parseInt(marker[2], 10) === wantEp) {
              return found[i].url;
            }
          }
          if (pageIndex + 1 >= MAX_PAGES) return null;
          return page(seasonIndex, pageIndex + 1);
        });
      }

      function seasonScan(index) {
        if (index >= seasons.length) return Promise.resolve(null);
        return page(index, 0).then(function (hit) {
          return hit || seasonScan(index + 1);
        });
      }

      return seasonScan(0).then(function (hit) {
        videoUrl = hit;
        return null;
      });
    })
    .then(function () {
      if (!videoUrl) return [];
      return text(videoUrl).then(function (page) {
        var code = first(/data-video-code="([^"]+)"/, page.body);
        if (!code) return [];
        var api = "https://dygvideo.dygdigital.com/api/redirect?PublisherId=20&ReferenceId=" +
          encodeURIComponent(code) + "&SecretKey=" + encodeURIComponent(DYG_SECRET);
        return text(api, { referer: videoUrl }).then(function (redirect) {
          var master = /\.m3u8/i.test(redirect.url) ? redirect.url : first(/https?:\/\/[^"'\s]+\.m3u8[^"'\s]*/, redirect.body);
          if (!master) return [];
          return text(master, { referer: BASE + "/" }).then(function (playlist) {
            var list = variants(master, playlist.body);
            if (!list.length) list = [{ quality: "", url: master }];
            return list.map(function (v) {
              return {
                name: "tlctr",
                title: "TLC Tr" + (v.quality ? " " + v.quality : "") + " [HLS]",
                url: v.url,
                quality: v.quality,
                headers: { Referer: BASE + "/" },
                subtitles: []
              };
            });
          });
        });
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][tlctr] " + (e && e.message));
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
