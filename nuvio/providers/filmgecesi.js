// @provider {"id":"filmgecesi","name":"Filmgecesi","types":["movie","tv"],"domain":"filmgecesi.vip","formats":["m3u8","mp4"],"sample":"tt0133093:movie:1:1","description":"Filmgecesi - Turkce dublajli film arsivi ve film serileri (blurays.me HLS).","verified":true,"version":"1.0.0"}
/**
 * filmgecesi — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /arama/?s= -> film (or /seri-filmler/ -> episode) ->
 *       rvali() prefix + pdata['prt_...'] base64 iframe -> blurays.me var src (HLS).
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://www.filmgecesi.vip";
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

function atobJs(value) {
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
  var re = /<a[^>]+class="[^"]*\bimage\b[^"]*"[^>]+href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/gi;
  var re2 = /<a[^>]+href="([^"]+)"[^>]+title="([^"]*)"/gi;
  var blocks = String(html || "").split('<div class="move_k">');
  for (var i = 1; i < blocks.length; i++) {
    var b = blocks[i];
    var href = first(/<a[^>]+class="[^"]*\bimage\b[^"]*"[^>]+href="([^"]+)"/i, b) || first(/<a[^>]+href="([^"]+)"/i, b);
    var url = abs(BASE, href);
    if (!url || seen[url]) continue;
    var h2 = (first(/<h2[^>]*>([\s\S]*?)<\/h2>/i, b) || "").replace(/<[^>]+>/g, " ").trim();
    var ttl = h2 || first(/title="([^"]+)"/i, b) || first(/alt="([^"]+)"/i, b) || "";
    ttl = decodeHtml(ttl).trim();
    if (!ttl) continue;
    seen[url] = 1;
    out.push({ url: url, title: ttl });
  }
  return out;
}

function pick(cards, wantTitle, isTv) {
  var want = norm(wantTitle);
  if (!want) return null;
  var prefix = null;
  for (var i = 0; i < cards.length; i++) {
    var isSer = cards[i].url.indexOf("/seri-filmler/") !== -1;
    if (isTv && !isSer) continue;
    var have = norm(cards[i].title);
    if (!have) continue;
    if (have === want) return cards[i];
    if (!prefix && have.indexOf(want + " ") === 0) prefix = cards[i];
  }
  return prefix;
}

function qualityOf(url, referer) {
  if (!/\.m3u8/i.test(url)) return Promise.resolve("auto");
  return text(url, { referer: referer })
    .then(function (m3u8) {
      var best = 0, re = /RESOLUTION=(\d+)x\d+/gi, m;
      while ((m = re.exec(m3u8)) !== null) {
        var w = Number(m[1]);
        if (w > best) best = w;
      }
      if (best >= 3800) return "4K";
      if (best >= 1900) return "1080p";
      if (best >= 1250) return "720p";
      if (best >= 840) return "480p";
      return "auto";
    })
    .catch(function () { return "auto"; });
}

function resolveEmbed(embedUrl, pageUrl, label) {
  if (/youtu\.?be|youtube\.com/i.test(embedUrl)) return Promise.resolve([]);
  return text(embedUrl, { referer: pageUrl })
    .then(function (html) {
      var master = first(/var\s+src\s*=\s*["']([^"']+)["']/, html) ||
        first(/file\s*:\s*["'](https?:\/\/[^"']+\.m3u8[^"']*)["']/, html) ||
        first(/https?:\/\/[^\s"'<>]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>]*)?/i, html);
      if (!master) return [];
      var url = abs(embedUrl, master.replace(/\\\//g, "/"));
      var isHls = /\.m3u8/i.test(url) || url.indexOf("gov.php") !== -1 || url.indexOf("blurays") !== -1;
      return qualityOf(url, embedUrl).then(function (q) {
        return [{
          name: "Filmgecesi",
          title: "Filmgecesi - " + (label || "Bluray") + (isHls ? " [HLS]" : " [MP4]"),
          url: isHls ? hlsNoDownload(url) : url,
          quality: q,
          headers: { Referer: embedUrl, "User-Agent": UA },
          subtitles: []
        }];
      });
    })
    .catch(function () { return []; });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var isTv = mediaType === "tv";
  var wantEp = parseInt(episode, 10) || 1;
  var filmUrl = "";

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return null;
      return text(BASE + "/arama/?s=" + encodeURIComponent(title)).then(function (html) {
        var hit = pick(parseCards(html), title, isTv);
        return hit ? hit.url : null;
      });
    })
    .then(function (url) {
      if (!url) return null;
      filmUrl = url;
      return text(url, { referer: BASE + "/" });
    })
    .then(function (html) {
      if (!html) return null;
      if (filmUrl.indexOf("/seri-filmler/") === -1) return html;
      var eps = [], re = /<div class="seri_k">[\s\S]*?<a[^>]+href="([^"]+)"/gi, m;
      while ((m = re.exec(html)) !== null) eps.push(abs(filmUrl, m[1]));
      var epUrl = eps[wantEp - 1];
      if (!epUrl) return null;
      filmUrl = epUrl;
      return text(epUrl, { referer: BASE + "/" });
    })
    .then(function (html) {
      if (!html) return [];
      var rv = first(/rvali\('([^']+)'\)/, html);
      var prefix = rv ? rv.split("").reverse().join("") : "PGlmcmFtZSB";
      var labels = {};
      var tr = /<li data-fid="([^"]+)"[^>]*class="pgrup"><a[^>]*>([\s\S]*?)<\/a>/gi, tm;
      while ((tm = tr.exec(html)) !== null) {
        labels[tm[1]] = tm[2].replace(/<[^>]+>/g, "").trim();
      }
      var jobs = [], seen = {};
      var pr = /pdata\['(prt_[^']+)'\]\s*=\s*'([^']*)'/g, pm;
      while ((pm = pr.exec(html)) !== null) {
        var pid = pm[1];
        if (pid.indexOf("fragman") !== -1) continue;
        var dec = atobJs(prefix + pm[2]);
        var src = first(/<iframe[^>]+src=["']([^"']+)["']/i, dec);
        if (!src) {
          dec = atobJs(pm[2]);
          src = first(/<iframe[^>]+src=["']([^"']+)["']/i, dec);
        }
        var embed = abs(filmUrl, src);
        if (!embed || seen[embed]) continue;
        seen[embed] = 1;
        var lbl = labels[pid.replace(/^prt_/, "")] || "Bluray";
        jobs.push(resolveEmbed(embed, filmUrl, lbl));
        if (jobs.length >= 6) break;
      }
      return Promise.all(jobs).then(function (lists) {
        var out = [], uSeen = {};
        for (var i = 0; i < lists.length; i++) {
          for (var j = 0; j < (lists[i] || []).length; j++) {
            var item = lists[i][j];
            if (item && item.url && !uSeen[item.url]) { uSeen[item.url] = 1; out.push(item); }
          }
        }
        return out;
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][filmgecesi] " + (e && e.message));
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
