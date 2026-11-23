// @provider {"id":"dizipod", "name":"DiziPod", "types":["tv"], "domain":"dizipod.com", "formats":["m3u8"], "sample":"tt0903747:tv:1:1", "description":"DiziPod Türkçe altyazılı yabancı dizi arşivi (HLS).", "version":"1.1.3", "verified":true}
/**
 * dizipod — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /?s= -> strict ul.list/ul.premium-listing card match ->
 *       /diziler/<slug>/ (exact season/episode) or /film/<slug>/ ->
 *       admin-ajax get_episode_player -> player iframe -> Dean Edwards unpack -> m3u8.
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
  if (String(href).indexOf("//") === 0) return "https:" + href;
  var origin = /^(https?:\/\/[^/]+)/.exec(base);
  if (String(href).charAt(0) === "/") return origin ? origin[1] + href : href;
  return base.replace(/[^/]*$/, "") + href;
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

function spaced(value) {
  return String(value || "")
    .replace(/\s*\([^)]*\)/g, " ")
    .replace(/\b(?:izle|dizi|film)\b/gi, " ")
    .split(/[\s:\-]+/)
    .map(norm)
    .filter(Boolean)
    .join(" ")
    .replace(/^(?:the|an|a) /, "");
}

function pick(cards, wanted) {
  var w = spaced(wanted);
  if (!w) return null;
  var subset = null;
  for (var i = 0; i < cards.length; i++) {
    var n = spaced(cards[i].title);
    if (!n) continue;
    if (n === w) return cards[i];
    if (!subset && n.indexOf(w + " ") === 0) subset = cards[i];
  }
  return subset;
}

function titleOf(id, mediaType) {
  if (/^__alt__:/.test(String(id))) return Promise.resolve(decodeURIComponent(String(id).slice(8)));
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

function searchCards(html, isTv) {
  var cards = [];
  var marker = isTv ? "/diziler/" : "/film/";
  var re = /<li\b[^>]*>([\s\S]*?)<\/li>/g;
  var m;
  while ((m = re.exec(html)) !== null) {
    var href = first(/<a\b[^>]*href="([^"]+)"/, m[1]);
    if (!href || href.indexOf(marker) === -1) continue;
    var title = first(/<div class="title"[^>]*>([\s\S]*?)<\/div>/, m[1]) ||
      first(/<a\b[^>]*title="([^"]+)"/, m[1]);
    if (!title) continue;
    cards.push({ url: abs(BASE, href), title: title.replace(/<[^>]+>/g, "").trim() });
  }
  return cards;
}

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
  var m = /eval\(function\(p,a,c,k,e,[rd]\)\{[\s\S]*?\}\('((?:[^'\\]+|\\.)*)',(\d+),(\d+),'((?:[^'\\]+|\\.)*)'\.split\('\|'\)/.exec(
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
  return m[1].replace(/\\'/g, "'").replace(/\b\w+\b/g, function (token) {
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

function episodeLink(html, season, episode) {
  var re = /href="([^"]*-(\d+)-sezon-(\d+)-bolum\/)"/g;
  var m;
  while ((m = re.exec(html)) !== null) {
    if (parseInt(m[2], 10) === season && parseInt(m[3], 10) === episode) {
      return Promise.resolve(abs(BASE, m[1]));
    }
  }
  var tabs = [];
  var rt = /class="[^"]*session-tab[^"]*"[^>]*href="([^"]+)"/g;
  while ((m = rt.exec(html)) !== null) tabs.push(abs(BASE, m[1]));
  var rt2 = /href="([^"]+)"[^>]*class="[^"]*session-tab/g;
  while ((m = rt2.exec(html)) !== null) tabs.push(abs(BASE, m[1]));
  var targetTab = null;
  for (var i = 0; i < tabs.length; i++) {
    if (new RegExp("-" + season + "-sezon(?:/|$)").test(tabs[i])) {
      targetTab = tabs[i];
      break;
    }
  }
  if (!targetTab) return Promise.resolve(null);
  return text(targetTab)
    .then(function (tabHtml) {
      var epRe = /href="([^"]*-(\d+)-sezon-(\d+)-bolum\/)"/g;
      var em;
      while ((em = epRe.exec(tabHtml)) !== null) {
        if (parseInt(em[2], 10) === season && parseInt(em[3], 10) === episode) {
          return abs(targetTab, em[1]);
        }
      }
      return null;
    })
    .catch(function () {
      return null;
    });
}

function getStreams(tmdbId, mediaType, season, episode) {
  if (mediaType === "movie") return Promise.resolve([]);
  var isTv = true;
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var pageUrl = null;
  var playerUrl = null;

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return null;
      return text(BASE + "/?s=" + encodeURIComponent(title)).then(function (searchHtml) {
        var hit = pick(searchCards(searchHtml, isTv), title);
        return hit ? hit.url : null;
      });
    })
    .then(function (targetUrl) {
      if (!targetUrl) return null;
      if (!isTv) return targetUrl;
      return text(targetUrl).then(function (seriesHtml) {
        return episodeLink(seriesHtml, want, wantEp);
      });
    })
    .then(function (resolvedPage) {
      if (!resolvedPage) return [];
      pageUrl = resolvedPage;
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
      var raw = first(/<iframe[^>]+src=\\?"((?:[^"\\]|\\\/)*)/, payload) ||
        first(/"(https?:[^"]+?(?:embed|player|\/e\/)[^"]*)"/, payload);
      if (!raw) return [];
      playerUrl = abs(pageUrl, raw.replace(/\\\//g, "/"));
      return text(playerUrl, { referer: pageUrl });
    })
    .then(function (player) {
      if (!player) return [];
      var list = sourcesOf(player);
      return list.map(function (item, index) {
        var qMatch = /(\d{3,4}p)/i.exec(item.label || "") || /-(1080|720|480|360)\.mp4/i.exec(item.url || "");
        var q = qMatch ? (qMatch[1].toLowerCase().indexOf("p") > -1 ? qMatch[1].toLowerCase() : qMatch[1] + "p") : "auto";
        return {
          name: "DiziPod",
          title: "DiziPod - " + (item.label || "Kaynak " + (index + 1)) + " [HLS]",
          url: item.url,
          quality: q,
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
  var mt = mediaType === "series" ? "tv" : mediaType;
  if (!/^\d+$/.test(raw)) {
    try {
      return Promise.resolve(__origGetStreams(raw, mt, season, episode)).catch(function () { return []; });
    } catch (e) {
      return Promise.resolve([]);
    }
  }
  var tv = mt !== "movie";
  var headers = { "User-Agent": "BigBullNuvio/1.0 (https://github.com/F0Rextasy/bigbullapp-cs)", "Accept": "application/json" };
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
    try {
      return Promise.resolve(__origGetStreams(imdb || raw, mt, season, episode)).catch(function () { return []; });
    } catch (e) {
      return [];
    }
  }).catch(function () { return []; });
};

/* Cinemeta basligi yerellestirir ("Gibi" -> "As If"); sonuc bossa Wikidata TR/EN basliklariyla yeniden dene. */
function __altTitles(raw, mediaType) {
  var wdHeaders = { "User-Agent": "BigBullNuvio/1.0 (https://github.com/F0Rextasy/bigbullapp-cs)", "Accept": "application/json" };
  var h = { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36", "Accept": "application/json" };
  var api = "https://www.wikidata.org/w/api.php?format=json";
  var stmt = /^tt\d+$/.test(raw) ? "P345=" + raw : (mediaType === "movie" ? "P4947=" : "P4983=") + raw;
  var wd = fetch(api + "&action=query&list=search&srlimit=1&srsearch=" + encodeURIComponent("haswbstatement:" + stmt), { headers: wdHeaders })
    .then(function (r) { return r.ok ? r.json() : null; })
    .then(function (j) {
      var hits = (j && j.query && j.query.search) || [];
      if (!hits.length) return null;
      return fetch(api + "&action=wbgetentities&props=labels|aliases&languages=tr|en&ids=" + hits[0].title, { headers: wdHeaders }).then(function (r) { return r.ok ? r.json() : null; });
    })
    .then(function (j) {
      var res = [], ents = (j && j.entities) || {}, k, l, a, i;
      for (k in ents) {
        l = ents[k].labels || {}; a = ents[k].aliases || {};
        if (l.tr && l.tr.value) res.push(l.tr.value);
        if (l.en && l.en.value) res.push(l.en.value);
        for (i = 0; i < (a.tr || []).length && i < 2; i++) res.push(a.tr[i].value);
      }
      return res;
    }).catch(function () { return []; });
  var imdb = /^tt\d+$/.test(raw)
    ? fetch("https://v3.sg.media-imdb.com/suggestion/t/" + raw + ".json", { headers: h })
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (j) { return (j && j.d && j.d[0] && j.d[0].l) ? [j.d[0].l] : []; })
        .catch(function () { return []; })
    : Promise.resolve([]);
  var tvm = /^tt\d+$/.test(raw) && mediaType !== "movie"
    ? fetch("https://api.tvmaze.com/lookup/shows?imdb=" + raw, { headers: h })
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (j) { return (j && j.name) ? [j.name] : []; })
        .catch(function () { return []; })
    : Promise.resolve([]);
  return Promise.all([wd, imdb, tvm]).then(function (lists) {
    var out = [], seen = {};
    for (var i = 0; i < lists.length; i++) {
      for (var j = 0; j < lists[i].length; j++) {
        var v = String(lists[i][j] || "").replace(/\s*\([^)]*\)\s*$/, "").trim();
        if (v && !seen[v.toLowerCase()]) { seen[v.toLowerCase()] = 1; out.push(v); }
      }
    }
    return out;
  });
}
var __cinemetaGetStreams = module.exports.getStreams;
module.exports.getStreams = function (id, mediaType, season, episode) {
  var raw = String(id || "");
  try {
    return Promise.resolve(__cinemetaGetStreams(raw, mediaType, season, episode)).then(function (streams) {
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
  } catch (e) {
    return Promise.resolve([]);
  }
};

/* Dil etiketi (AGENTS.md): Nuvio karti "quality \u2022 ..." satirini gosterir; dil etiketi kalitenin yanina yazilir. */
var __subOnly = true;
var __langLabeled = module.exports.getStreams;
module.exports.getStreams = function (id, mediaType, season, episode) {
  try {
    return Promise.resolve(__langLabeled.apply(this, arguments)).then(function (streams) {
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
    }).catch(function () { return []; });
  } catch (e) {
    return Promise.resolve([]);
  }
};
