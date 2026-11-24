// @provider {"id":"hdfilmizle","name":"HDFilmizle","types":["movie","tv"],"domain":"hdfilmizle.ink","formats":["m3u8"],"sample":"tt0903747:tv:1:1","description":"HDFilmizle - Full HD film ve yabanci dizi arsivi (SetPlay / fastplay HLS + altyazi).","verified":true,"version":"1.0.0"}
/**
 * hdfilmizle — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /?s= -> film/dizi -> episode -> admin-ajax get_video_url ->
 *       bridge page -> XOR base64 pair -> fastplay player -> stream path + VTT tracks (mobile UA).
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var MOBILE_UA = "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";
var BASE = "https://www.hdfilmizle.ink";
var B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

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

function origin(url) {
  var m = /^(https?:\/\/[^/]+)/.exec(String(url || ""));
  return m ? m[1] : null;
}

function abs(base, href) {
  if (!href) return null;
  href = String(href).trim();
  if (/^https?:/i.test(href)) return href;
  if (href.slice(0, 2) === "//") return "https:" + href;
  var root = origin(base);
  if (href.charAt(0) === "/") return root ? root + href : href;
  return String(base || BASE).replace(/[^/]*$/, "") + href;
}

function hlsNoDownload(url) {
  var u = String(url || "");
  if (/\.m3u8(\?|$)/i.test(u) || u.indexOf("#") !== -1) return u;
  return u + "#.m3u8";
}

function b64(value) {
  var raw = String(value || "").replace(/\\\//g, "/").replace(/[^A-Za-z0-9+/=]/g, "");
  var out = "", buf = 0, bits = 0;
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

function xorUrl(aStr, bStr) {
  var a = b64(aStr), b = b64(bStr);
  if (!a.length || !b.length) return null;
  var out = "";
  for (var i = 0; i < a.length; i++) out += String.fromCharCode(a.charCodeAt(i) ^ b.charCodeAt(i % b.length));
  var parts = out.split("|");
  for (var j = 0; j < parts.length; j++) if (/^https?:/i.test(parts[j])) return parts[j].trim();
  return null;
}

function titleOf(id, mediaType) {
  if (/^__alt__:/.test(String(id))) return Promise.resolve(decodeURIComponent(String(id).slice(8)));
  var kind = mediaType === "movie" ? "movie" : "series";
  return text("https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json")
    .then(function (body) { return String((JSON.parse(body).meta || {}).name || "").trim(); })
    .catch(function () { return ""; });
}

function norm(value) {
  return String(value || "")
    .replace(/[\u0131\u0130I]/g, "i").replace(/[\u015f\u015e]/g, "s").replace(/[\u011f\u011e]/g, "g")
    .replace(/[\u00fc\u00dc]/g, "u").replace(/[\u00f6\u00d6]/g, "o").replace(/[\u00e7\u00c7]/g, "c")
    .toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
}

function searchCards(html, isTv) {
  var out = [], seen = {};
  var re = /<article[^>]*class="[^"]*item[^"]*"[^>]*>([\s\S]*?)<\/article>/g;
  var m;
  while ((m = re.exec(String(html || ""))) !== null) {
    var block = m[1];
    var href = first(/<div class="poster">\s*<a[^>]*href="([^"]+)"/, block);
    if (!href) continue;
    var rowTv = href.indexOf("/dizi/") !== -1 || href.indexOf("/bolum/") !== -1;
    if (rowTv !== isTv) continue;
    var title = first(/<div class="data">\s*<h2[^>]*>([^<]+)<\/h2>/, block) ||
      first(/class="tooltip-title"[^>]*>([^<]+)</, block) ||
      first(/aria-label="([^"]+)"/, block);
    if (!title) continue;
    title = title.replace(/\s*-\s*Film detaylar.*$/i, "").replace(/\s*\(\d{4}\)\s*$/, "").trim();
    if (seen[href]) continue;
    seen[href] = 1;
    out.push({ url: abs(BASE, href), title: title });
  }
  return out;
}

function pickCard(cards, wantTitle) {
  function clean(s) { return norm(s).replace(/^(?:the|an|a) /, ""); }
  var want = clean(wantTitle);
  if (!want) return null;
  var best = null;
  for (var i = 0; i < cards.length; i++) {
    var have = clean(cards[i].title);
    if (have === want) return cards[i];
    if (!best && have.indexOf(want + " ") === 0) best = cards[i];
  }
  return best;
}

function episodeUrl(seriesHtml, want, wantEp) {
  var re = /href="([^"]*\/bolum\/[^"]*-(\d+)-sezon-(\d+)-bolum[^"]*)"/g;
  var m;
  while ((m = re.exec(String(seriesHtml || ""))) !== null) {
    if (parseInt(m[2], 10) === want && parseInt(m[3], 10) === wantEp) return abs(BASE, m[1]);
  }
  return null;
}

function qualityOf(master) {
  var best = 0, re = /RESOLUTION=\d+x(\d+)/gi, m;
  while ((m = re.exec(String(master || ""))) !== null) {
    var h = parseInt(m[1], 10) || 0;
    if (h > best) best = h;
  }
  if (best >= 2160) return "2160p";
  if (best >= 1080) return "1080p";
  if (best >= 720) return "720p";
  if (best >= 480) return "480p";
  return best > 0 ? best + "p" : "auto";
}

function parseTracks(rawJson) {
  if (!rawJson) return [];
  var list;
  try { list = JSON.parse(rawJson); } catch (e) { return []; }
  var subs = [];
  for (var i = 0; i < list.length; i++) {
    var it = list[i] || {};
    if (it.kind && it.kind !== "captions") continue;
    if (!/^https?:/i.test(it.file || "")) continue;
    var lbl = String(it.label || it.lang || "Altyazı").trim();
    subs.push({
      url: it.file,
      language: /eng|ing/i.test(String(it.lang || lbl)) ? "en" : "tr",
      name: lbl + " [HDFilmizle]"
    });
  }
  return subs;
}

function resolvePlayer(pageUrl, nonce, player) {
  return text(BASE + "/wp-admin/admin-ajax.php", {
    method: "POST",
    referer: pageUrl,
    headers: { "X-Requested-With": "XMLHttpRequest", "Content-Type": "application/x-www-form-urlencoded" },
    body: "action=get_video_url&nonce=" + encodeURIComponent(nonce) +
      "&post_id=" + encodeURIComponent(player.postId) +
      "&player_name=" + encodeURIComponent(player.playerName) +
      "&part_key=" + encodeURIComponent(player.partKey)
  }).then(function (raw) {
    var bridgeUrl = ((JSON.parse(raw) || {}).data || {}).url;
    if (!bridgeUrl || !/^https?:/i.test(bridgeUrl)) return null;
    var bridgeOrg = (origin(bridgeUrl) || "https://setplay.sbs") + "/";
    return text(bridgeUrl, { referer: pageUrl }).then(function (bHtml) {
      var m = /cerceve\(\s*"b2"\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\)/.exec(bHtml);
      if (!m) return null;
      var playerUrl = xorUrl(m[1], m[2]);
      if (!playerUrl) return null;
      return text(playerUrl, { referer: bridgeOrg }).then(function (pHtml) {
        var path = first(/stream:\s*"([^"]+)"/, pHtml);
        if (!path) return null;
        var master = abs(playerUrl, path.replace(/\\\//g, "/"));
        var subs = parseTracks(first(/tracks:\s*(\[[\s\S]*?\])\s*,/, pHtml));
        var lbl = (player.playerName || "SetPlay") + (player.partKey ? " " + player.partKey : "");
        return text(master, { referer: playerUrl, mobile: true })
          .then(function (m3u8) { return qualityOf(m3u8); })
          .catch(function () { return "auto"; })
          .then(function (q) {
            return {
              name: "HDFilmizle",
              title: "HDFilmizle - " + lbl + " [HLS]",
              url: hlsNoDownload(master),
              quality: q,
              headers: { Referer: playerUrl, "User-Agent": MOBILE_UA, Accept: "*/*" },
              subtitles: subs.slice(0, 6)
            };
          });
      });
    });
  }).catch(function () { return null; });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var isTv = mediaType === "tv" || mediaType === "series";
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var pageUrl = null;

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return null;
      return text(BASE + "/?s=" + encodeURIComponent(title)).then(function (html) {
        return pickCard(searchCards(html, isTv), title);
      });
    })
    .then(function (card) {
      if (!card) return null;
      pageUrl = card.url;
      return text(pageUrl, { referer: BASE + "/" });
    })
    .then(function (html) {
      if (!html) return null;
      if (!isTv) return html;
      var ep = episodeUrl(html, want, wantEp);
      if (!ep) return null;
      pageUrl = ep;
      return text(ep, { referer: BASE + "/" });
    })
    .then(function (html) {
      if (!html) return [];
      var nonce = first(/id="playex"[^>]*data-nonce="([^"]+)"/, html) || first(/"nonce"\s*:\s*"([^"]+)"/, html);
      if (!nonce) return [];
      var players = [], seen = {};
      var re = /<a[^>]+data-post-id="([^"]+)"[^>]*>/g, m;
      while ((m = re.exec(html)) !== null) {
        var pid = m[1].trim();
        var pname = first(/data-player-name="([^"]*)"/, m[0]) || "";
        var pkey = first(/data-part-key="([^"]*)"/, m[0]) || "";
        var sig = pid + "|" + pname + "|" + pkey;
        if (!pid || seen[sig]) continue;
        seen[sig] = 1;
        players.push({ postId: pid, playerName: pname, partKey: pkey });
      }
      if (!players.length) return [];
      return Promise.all(players.slice(0, 4).map(function (pl) {
        return resolvePlayer(pageUrl, nonce, pl);
      })).then(function (rows) {
        var out = [], seenUrl = {};
        for (var i = 0; i < rows.length; i++) {
          if (!rows[i] || seenUrl[rows[i].url]) continue;
          seenUrl[rows[i].url] = 1;
          out.push(rows[i]);
        }
        return out;
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][hdfilmizle] " + (e && e.message));
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
