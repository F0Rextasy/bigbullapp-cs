// @provider {"id":"dmax", "name":"DMAX", "types":["tv"], "domain":"dmax.com.tr", "formats":["m3u8"], "sample":"tt1800864:tv:15:1", "description":"DMAX Türkiye program arşivi; bölümler player/info HLS adresiyle açılır.", "version":"1.0.4"}
/**
 * dmax — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> program list -> program page -> /<slug>/<S>-sezon-<E>-bolum
 *       -> data-video-code -> /player/info?referenceId= -> video.data.flavors.hls
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
var BASE = "https://www.dmax.com.tr";

/* Discovery shows are listed under their Turkish broadcast names, so English
   metadata needs a bridge; token matching alone cannot cross the language. */
var ALIASES = {
  "gold rush": "altin-pesinde",
  "gold rush parkers trail": "altin-pesinde-parkerin-yolu",
  "gold rush white water": "altin-pesinde-kopuklu-sular",
  "bering sea gold": "bering-bogazinda-altin",
  "deadliest catch": "olumcul-av",
  "the last alaskans": "son-alaskalilar",
  "alaska the last frontier": "alaska-son-sinir",
  "undercover billionaire": "gizli-milyarder",
  "bear grylls breaking point": "bear-grylls-kirilma-noktasi",
  "megalodon the monster shark lives": "megalodon-dev-kopekbaligi-yasiyor",
  "homestead rescue": "kirsalda-yasamak-zor",
  "100 days wild": "yabanda-100-gun",
  "building wild": "yabani-insa-etmek",
  "treasure quest snake island": "hazine-arayisi-yilan-adasi",
  "the last woodsman": "son-ormanci",
  "alaskan mega machines": "alaskanin-mega-makineleri",
  "border control sweden": "gumruk-kontrol-isvec",
  "dirty jobs": "tamirat-tadilat"
};

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
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

function fold(value) {
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
      var meta = JSON.parse(body).meta || {};
      return String(meta.name || "").trim();
    })
    .catch(function () {
      return "";
    });
}

/* Program cards carry data-type="program"; the slug is the whole path. */
function programs(html) {
  var parts = String(html || "").split('data-type="program"');
  var out = [];
  var seen = {};
  for (var i = 1; i < parts.length; i++) {
    var seg = parts[i].slice(0, 900);
    var href = /href="(https:\/\/www\.dmax\.com\.tr\/[^"?#]+)"/.exec(seg);
    if (!href || seen[href[1]]) continue;
    seen[href[1]] = 1;
    var alt = /alt="([^"]*)"/.exec(seg);
    out.push({ url: href[1], name: alt ? alt[1] : "" });
  }
  return out;
}

function pickProgram(list, title) {
  var key = fold(title);
  var slug = ALIASES[key];
  if (slug) return BASE + "/" + slug;
  var wanted = key.split(" ").filter(function (w) {
    return w.length > 2;
  });
  var best = null;
  var bestScore = 0;
  for (var i = 0; i < list.length; i++) {
    var hay = fold(list[i].name);
    var score = 0;
    for (var k = 0; k < wanted.length; k++) if (hay.indexOf(wanted[k]) !== -1) score++;
    if (score > bestScore) {
      bestScore = score;
      best = list[i].url;
    }
  }
  return wanted.length && bestScore >= wanted.length ? best : null;
}

function streamsFromInfo(body, referer) {
  var data = JSON.parse(body);
  var flavors = (((data || {}).video || {}).data || {}).flavors || {};
  var url = String(flavors.hls || "").trim();
  if (!/^https?:/i.test(url)) return [];
  return [
    {
      name: "dmax",
      title: "dmax - HLS",
      url: url,
      quality: "auto",
      headers: { Referer: referer },
      subtitles: []
    }
  ];
}

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var pageUrl = null;

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return null;
      return text(BASE + "/").then(function (home) {
        return pickProgram(programs(home), title);
      });
    })
    .then(function (programUrl) {
      if (!programUrl) return [];
      pageUrl = programUrl;
      return text(programUrl);
    })
    .then(function (program) {
      if (!program) return [];
      var exact = null;
      var any = null;
      var re = /href="([^"]*\/(\d+)-sezon-(\d+)-bolum[^"]*)"/g;
      var m;
      while ((m = re.exec(program)) !== null) {
        var href = /^https?:/i.test(m[1]) ? m[1] : BASE + m[1];
        if (!any) any = href;
        if (parseInt(m[2], 10) === want && parseInt(m[3], 10) === wantEp) {
          exact = href;
          break;
        }
      }
      var target = exact || (any ? null : pageUrl);
      if (!target) return null;
      pageUrl = target;
      return text(target);
    })
    .then(function (page) {
      if (!page) return [];
      var code = first(/data-video-code="([^"]+)"/, page);
      /* Programs without episodes keep their video code on the program page. */
      if (!code && pageUrl === BASE + "/") return [];
      if (!code) return [];
      return text(BASE + "/player/info?referenceId=" + encodeURIComponent(code), { referer: pageUrl }).then(
        function (info) {
          return streamsFromInfo(info, pageUrl);
        }
      );
    })
    .catch(function (e) {
      console.log("[bb-nuvio][dmax] " + (e && e.message));
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
