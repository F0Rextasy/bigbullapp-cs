// @provider {"id":"dizipod","name":"DiziPod","types":["movie","tv"],"domain":"dizipod.com","formats":["m3u8"],"sample":"tt0903747:tv:1:1","description":"DiziPod - Turkce altyazili yabanci dizi ve film arsivi (HLS)."}
/**
 * dizipod — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /?s= search -> /diziler/<slug>/ -> episode -> admin-ajax -> player
 *       iframe -> Dean Edwards unpack -> sources[].file (m3u8).
 */
var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://dizipod.com";
var ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

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
  return m ? m[1] : null;
}

function abs(base, href) {
  if (!href) return null;
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

// Dean Edwards packer: eval(function(p,a,c,k,e,d){…}('payload',base,count,'words'.split('|'),0,{}))
function encodeBase(value, base) {
  if (value === 0) return "0";
  var out = "";
  while (value > 0) {
    out = ALPHABET.charAt(value % base) + out;
    value = Math.floor(value / base);
  }
  return out;
}

function unpack(html) {
  var m = /eval\(function\(p,a,c,k,e,[rd]\)\{[\s\S]*?\}\('([\s\S]*?)',(\d+),(\d+),'([\s\S]*?)'\.split\('\|'\)/.exec(
    String(html || "")
  );
  if (!m) return String(html || "");
  var words = m[4].split("|");
  var base = parseInt(m[2], 10);
  if (!(base >= 2 && base <= ALPHABET.length)) return String(html || "");
  var dict = {};
  for (var i = 0; i < words.length; i++) {
    var key = encodeBase(i, base);
    dict[key] = words[i] || key;
  }
  return m[1].replace(/\b\w+\b/g, function (token) {
    return Object.prototype.hasOwnProperty.call(dict, token) ? dict[token] : token;
  });
}

function sourcesOf(html) {
  var script = unpack(html);
  var out = [];
  var seen = {};
  var re = /"file"\s*:\s*"([^"]+)"\s*,\s*"label"\s*:\s*"([^"]*)"/g;
  var m;
  while ((m = re.exec(script)) !== null) {
    var url = m[1].replace(/\\/g, "");
    if (!/^https?:/.test(url) || seen[url]) continue;
    seen[url] = 1;
    out.push({ url: url, label: String(m[2] || "").trim() });
  }
  if (out.length) return out;
  var any = /https?:(?:\\?\/){2}[^\s"']+?\.m3u8/g;
  while ((m = any.exec(script)) !== null) {
    var plain = m[0].replace(/\\/g, "");
    if (seen[plain]) continue;
    seen[plain] = 1;
    out.push({ url: plain, label: "" });
  }
  return out;
}

function seriesOf(slug) {
  return text(abs(BASE, slug));
}

function episodeLink(html, season, episode) {
  var links = [];
  var re = /href="(https?:\/\/[^"]*-(\d+)-sezon-(\d+)-bolum\/)"/g;
  var m;
  while ((m = re.exec(html)) !== null) links.push({ url: m[1], season: parseInt(m[2], 10), ep: parseInt(m[3], 10) });
  for (var i = 0; i < links.length; i++) {
    if (links[i].season === season && links[i].ep === episode) return links[i].url;
  }
  // Episode list may live on per-season pages (/dizi/<slug>/<slug>-N-sezon/).
  var tabs = [];
  var rt = /class="[^"]*session-tab[^"]*"[^>]*href="([^"]+)"/g;
  while ((m = rt.exec(html)) !== null) tabs.push(abs(BASE, m[1]));
  var rt2 = /href="([^"]+)"[^>]*class="[^"]*session-tab/g;
  while ((m = rt2.exec(html)) !== null) tabs.push(abs(BASE, m[1]));
  return { tabs: tabs.filter(function (t, i, a) { return t && a.indexOf(t) === i; }), season: season, episode: episode };
}

function findOnSeasonPages(state) {
  if (state.tabs && state.tabs.length) {
    var tab = state.tabs[0];
    for (var i = 0; i < state.tabs.length; i++) {
      if (new RegExp("-" + state.season + "-sezon").test(state.tabs[i])) {
        tab = state.tabs[i];
        break;
      }
    }
    return text(tab).then(function (html) {
      var re = /href="([^"]*-(\d+)-sezon-(\d+)-bolum\/)"/g;
      var m;
      while ((m = re.exec(html)) !== null) {
        if (parseInt(m[2], 10) === state.season && parseInt(m[3], 10) === state.episode) return abs(tab, m[1]);
      }
      var firstAny = /href="([^"]*-\d+-sezon-\d+-bolum\/)"/.exec(html);
      return firstAny ? abs(tab, firstAny[1]) : null;
    });
  }
  return Promise.resolve(null);
}

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var pageUrl = null;
  var playerUrl = null;

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return null;
      return text(BASE + "/?s=" + encodeURIComponent(title));
    })
    .then(function (search) {
      if (!search) return null;
      var link =
        first(/<a[^>]+href="(https?:\/\/[^"]*\/diziler\/[^"]+\/)"/, search) ||
        first(/<a[^>]+href="(\/diziler\/[^"]+\/)"/, search);
      if (!link) return null;
      return seriesOf(link);
    })
    .then(function (seriesHtml) {
      if (!seriesHtml) return null;
      var found = episodeLink(seriesHtml, want, wantEp);
      if (typeof found === "string") return found;
      return findOnSeasonPages(found);
    })
    .then(function (episodeUrl) {
      if (!episodeUrl) return [];
      pageUrl = episodeUrl;
      return text(pageUrl);
    })
    .then(function (page) {
      if (!page) return [];
      var postId = first(/data-post-id="(\d+)"/, page);
      if (!postId) return [];
      return text(BASE + "/wp/wp-admin/admin-ajax.php?action=get_episode_player&post_id=" + postId, { referer: pageUrl });
    })
    .then(function (payload) {
      if (!payload) return [];
      var raw = first(/<iframe[^>]+src=\\?"((?:[^"\\]|\\\/)*)/, payload);
      if (!raw) raw = first(/"(https?:[^"]+?(?:embed|player|\/e\/)[^"]*)"/, payload);
      if (!raw) return [];
      playerUrl = abs(pageUrl, raw.replace(/\\\//g, "/"));
      return text(playerUrl, { referer: pageUrl });
    })
    .then(function (player) {
      if (!player) return [];
      var list = sourcesOf(player);
      return list.map(function (item, index) {
        return {
          name: "dizipod",
          title: "dizipod - " + (item.label || "Kaynak " + (index + 1)) + " [HLS]",
          url: item.url,
          quality: "auto",
          headers: { Referer: playerUrl, "User-Agent": UA },
          subtitles: []
        };
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][dizipod] " + (e && e.message));
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