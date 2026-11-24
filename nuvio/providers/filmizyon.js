// @provider {"id":"filmizyon","name":"Filmizyon","types":["movie","tv"],"domain":"filmizyon.net","formats":["m3u8","mp4"],"sample":"tt0133093:movie:1:1","description":"Filmizyon - Turkce dublaj ve altyazili film arsivi (vixolity + embed).","verified":true,"version":"1.0.0"}
/**
 * filmizyon — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /?s= -> film/episode page -> iframe / vixolity / direct HLS/MP4.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://www.filmizyon.net";
var B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
  if (o.headers) for (var k in o.headers) headers[k] = o.headers[k];
  return fetch(url, { method: o.method || "GET", headers: headers, body: o.body }).then(function (r) {
    if (!r.ok) throw new Error("HTTP " + r.status + " " + url);
    return r.text();
  });
}

function first(pattern, body) {
  var m = pattern.exec(String(body || ""));
  return m ? m[1] : null;
}

function origin(url) {
  var m = /^(https?:\/\/[^/]+)/.exec(String(url || ""));
  return m ? m[1] : "";
}

function abs(base, href) {
  var u = String(href || "").trim();
  if (!u) return "";
  if (/^https?:\/\//i.test(u)) return u;
  if (u.indexOf("//") === 0) return "https:" + u;
  var root = origin(base || BASE) || BASE;
  if (u.charAt(0) === "/") return root + u;
  return root + "/" + u;
}

function hlsNoDownload(url) {
  var u = String(url || "").trim();
  if (!u) return u;
  if (/\.m3u8(\?|$)/i.test(u) || u.indexOf("#") !== -1) return u;
  return u + "#.m3u8";
}

function atobStr(value) {
  var s = String(value || "").replace(/[^A-Za-z0-9+/=]/g, "");
  var out = "", buf = 0, bits = 0;
  for (var i = 0; i < s.length; i++) {
    var v = B64.indexOf(s.charAt(i));
    if (v < 0) continue;
    buf = (buf << 6) | v;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out += String.fromCharCode((buf >> bits) & 255);
    }
  }
  return out;
}

function decodeVixolity(html) {
  var hex = first(/\}\)\('([0-9a-fA-F]{16,})',\s*\d+\)/, html) || first(/"([0-9a-fA-F]{80,})"/, html);
  if (!hex || hex.length % 2 !== 0) return null;
  var exp = first(/\}\)\('[0-9a-fA-F]{16,}',\s*(\d+)\)/, html);
  var shifts = exp ? [parseInt(exp, 10), 3, 1, 2, 4, 5, 6, 7] : [3, 1, 2, 4, 5, 6, 7];
  var step1 = "";
  for (var i = 0; i < hex.length; i += 2) step1 += String.fromCharCode(parseInt(hex.substr(i, 2), 16));
  var step2 = atobStr(step1.split("").reverse().join(""));
  for (var s = 0; s < shifts.length; s++) {
    var sh = shifts[s], shifted = "";
    for (var j = 0; j < step2.length; j++) shifted += String.fromCharCode(step2.charCodeAt(j) - sh);
    var dec = atobStr(shifted);
    var u = first(/"(?:kaynak|file|src)"\s*:\s*"([^"]+)"/, dec);
    if (u) {
      u = u.replace(/\\\//g, "/");
      if (/^http/i.test(u) || u.indexOf(".m3u8") !== -1) return u;
    }
  }
  return null;
}

function decodeHtml(s) {
  return String(s || "")
    .replace(/&#x([0-9a-f]+);/gi, function (_, h) { return String.fromCharCode(parseInt(h, 16)); })
    .replace(/&#(\d+);/g, function (_, d) { return String.fromCharCode(parseInt(d, 10)); })
    .replace(/&amp;/g, "&").replace(/&quot;/g, '"').replace(/&#0?39;/g, "'");
}

function norm(value) {
  return decodeHtml(value)
    .replace(/\([^)]*\)/g, " ")
    .replace(/[\u0131\u0130I]/g, "i").replace(/[\u015f\u015e]/g, "s").replace(/[\u011f\u011e]/g, "g")
    .replace(/[\u00e7\u00c7]/g, "c").replace(/[\u00f6\u00d6]/g, "o").replace(/[\u00fc\u00dc]/g, "u")
    .toLowerCase()
    .replace(/\b(?:izle|film|full|hd|turkce|dublaj|altyazili)\b/g, " ")
    .replace(/[^a-z0-9]+/g, " ").trim()
    .replace(/^(?:the|a|an)\s+/, "");
}

function titleOf(id, mediaType) {
  var raw = String(id || "").trim();
  if (/^__alt__:/.test(raw)) return Promise.resolve(decodeURIComponent(raw.slice(8)));
  var kind = mediaType === "tv" ? "series" : "movie";
  return text("https://v3-cinemeta.strem.io/meta/" + kind + "/" + encodeURIComponent(raw) + ".json")
    .then(function (body) { return String(((JSON.parse(body).meta) || {}).name || "").trim(); })
    .catch(function () { return ""; });
}

function parseCards(html) {
  var out = [], seen = {};
  var re = /<a[^>]+href="([^"]*\/film\/[^"]+)"[^>]*>([\s\S]*?)<\/a>/gi, m;
  while ((m = re.exec(String(html || ""))) !== null) {
    var href = abs(BASE, m[1]);
    if (!href || seen[href]) continue;
    var block = m[2];
    if (block.indexOf("<img") === -1) continue;
    var title = decodeHtml(first(/alt="([^"]+)"/i, block) || first(/title="([^"]+)"/i, m[0]) || "").trim();
    if (!title) continue;
    seen[href] = 1;
    out.push({ url: href, title: title });
  }
  return out;
}

function pick(cards, wantTitle) {
  var want = norm(wantTitle);
  if (!want) return null;
  var prefix = null;
  for (var i = 0; i < cards.length; i++) {
    var have = norm(cards[i].title);
    if (!have) continue;
    if (have === want) return cards[i];
    if (!prefix && have.indexOf(want + " ") === 0) prefix = cards[i];
  }
  return prefix;
}

function getStreams(tmdbId, mediaType, season, episode) {
  var isTv = mediaType === "tv";
  var wantS = parseInt(season, 10) || 1;
  var wantE = parseInt(episode, 10) || 1;
  var pageUrl = "";

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return null;
      return text(BASE + "/?s=" + encodeURIComponent(title)).then(function (html) {
        var hit = pick(parseCards(html), title);
        return hit ? hit.url : null;
      });
    })
    .then(function (url) {
      if (!url) return null;
      pageUrl = url;
      return text(url, { referer: BASE + "/" });
    })
    .then(function (html) {
      if (!html) return null;
      if (!isTv) return html;
      var re = /<a[^>]+href="([^"]+)"/gi, m, epUrl = null;
      while ((m = re.exec(html)) !== null) {
        var u = abs(pageUrl, m[1]);
        if (!/(?:bolum|episode|sezon|season)/i.test(u)) continue;
        var sm = /(?:sezon|season)[-_\/]?(\d+)/i.exec(u);
        var em = /(?:bolum|episode)[-_\/]?(\d+)/i.exec(u) || /(\d+)[-_]bolum/i.exec(u);
        if (em && Number(em[1]) === wantE && (!sm || Number(sm[1]) === wantS)) { epUrl = u; break; }
      }
      if (!epUrl) return html;
      pageUrl = epUrl;
      return text(epUrl, { referer: BASE + "/" });
    })
    .then(function (html) {
      if (!html) return [];
      var clean = String(html).replace(/\\\//g, "/").replace(/\\"/g, '"');
      var subs = [], subSeen = {};
      var sr = /https?:\/\/[^\s"'<>]+?\.(?:vtt|srt)(?:\?[^\s"'<>]*)?/gi, sm;
      while ((sm = sr.exec(clean)) !== null && subs.length < 4) {
        if (!subSeen[sm[0]]) { subSeen[sm[0]] = 1; subs.push({ url: sm[0], language: "tr", name: "T\u00fcrk\u00e7e [Filmizyon]" }); }
      }
      var results = [], seenUrl = {};
      function addStream(u, label, ref) {
        if (!/^https?:\/\//i.test(u) || seenUrl[u]) return;
        seenUrl[u] = 1;
        var isHls = /\.m3u8/i.test(u) || u.indexOf("/vs/") !== -1 || /\.gif(?:\?|$)/i.test(u);
        results.push({
          name: "Filmizyon",
          title: "Filmizyon - " + label + (isHls ? " [HLS]" : " [MP4]"),
          url: isHls ? hlsNoDownload(u) : u,
          quality: "auto",
          headers: { Referer: ref, "User-Agent": UA },
          subtitles: subs
        });
      }
      var dm = /https?:\/\/[^\s"'<>]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>]*)?/gi, d;
      while ((d = dm.exec(clean)) !== null) addStream(d[0], "Direct", pageUrl);

      var frames = [], fSeen = {};
      var er = /"(?:embedUrl|contentUrl|embed_url|player_url)"\s*:\s*"([^"]+)"/gi, em;
      while ((em = er.exec(clean)) !== null) {
        var eu = abs(pageUrl, em[1]);
        if (eu && eu !== pageUrl && !fSeen[eu] && !/youtu/i.test(eu)) { fSeen[eu] = 1; frames.push(eu); }
      }
      var fr = /<iframe[^>]+(?:src|data-src|vdo-src)=["']([^"']+)["']/gi, fm;
      while ((fm = fr.exec(clean)) !== null) {
        var fu = abs(pageUrl, fm[1]);
        if (fu && fu !== pageUrl && !fSeen[fu] && !/youtu|google|facebook/i.test(fu)) { fSeen[fu] = 1; frames.push(fu); }
      }
      var jobs = frames.slice(0, 6).map(function (frame) {
        if (/\.(?:m3u8|mp4)(?:\?|$)/i.test(frame)) { addStream(frame, "Embed", pageUrl); return Promise.resolve(); }
        return text(frame, { referer: pageUrl }).then(function (inner) {
          var vix = decodeVixolity(inner);
          if (vix) addStream(vix, "Vixolity", (origin(frame) || "https://pl.vixolity.com") + "/");
          var ic = inner.replace(/\\\//g, "/");
          var im = /https?:\/\/[^\s"'<>]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>]*)?/gi, x;
          while ((x = im.exec(ic)) !== null) addStream(x[0], "Embed", frame);
        }).catch(function () {});
      });
      return Promise.all(jobs).then(function () { return results; });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][filmizyon] " + (e && e.message));
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
  function fromTmdbTitle() {
    if (!key) return Promise.resolve("");
    return fetch("https://api.themoviedb.org/3/" + (tv ? "tv" : "movie") + "/" + raw + "?api_key=" + encodeURIComponent(key) + "&language=tr-TR", { headers: headers })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (json) {
        var t = (json && (json.original_name || json.name || json.original_title || json.title)) || "";
        return t ? "__alt__:" + encodeURIComponent(t) : "";
      })
      .catch(function () { return ""; });
  }
  return fromTmdb().then(function (imdb) { return imdb || fromWikidata(); }).then(function (imdb) { return imdb || fromTmdbTitle(); }).then(function (imdb) {
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
    if (/^__alt__:/.test(raw)) return Promise.resolve(__cinemetaGetStreams(raw, mediaType, season, episode)).catch(function () { return []; });
    function tryPrimary() {
      if (!/^tt\d+$/.test(raw)) return Promise.resolve(__cinemetaGetStreams(raw, mediaType, season, episode)).catch(function () { return []; });
      var h = { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36", "Accept": "application/json" };
      return fetch("https://v3.sg.media-imdb.com/suggestion/t/" + raw + ".json", { headers: h })
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (j) {
          var t = j && j.d && j.d[0] && j.d[0].l ? String(j.d[0].l).replace(/\s*\([^)]*\)\s*$/, "").trim() : "";
          return t ? __cinemetaGetStreams("__alt__:" + encodeURIComponent(t), mediaType, season, episode) : [];
        })
        .catch(function () { return []; })
        .then(function (streams) {
          return (streams && streams.length) ? streams : __cinemetaGetStreams(raw, mediaType, season, episode);
        });
    }
    return tryPrimary().then(function (streams) {
      if (streams && streams.length) return streams;
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
var __subOnly = false;
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
