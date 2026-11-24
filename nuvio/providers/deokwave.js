// @provider {"id":"deokwave","name":"Deokwave","types":["tv"],"domain":"deokwave.com","formats":["mp4","m3u8"],"sample":"tt0409591:tv:1:1","description":"Deokwave Turkce altyazili anime arsivi (token MP4 + embedler).","verified":true,"version":"1.0.0"}
/**
 * deokwave — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /search_api.php?q= -> /anime/<id>/ -> /watch/<id>/season/<s>/episode/<e>
 *       -> POST /api/v1/video/token/ (with CSRF + Cookie) -> sw2.deokwave.com/v/<vid>/<q>/?vt=<token>.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0";
var BASE = "https://deokwave.com";
var ID = "Deokwave";

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
  if (o.origin) headers.Origin = o.origin;
  if (o.headers) for (var k in o.headers) if (o.headers[k]) headers[k] = o.headers[k];
  return fetch(url, { method: o.method || "GET", headers: headers, body: o.body })
    .then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status + " " + url);
      return r.text();
    });
}

function first(re, body) {
  var m = re.exec(String(body || ""));
  return m ? m[1] : null;
}

function abs(base, href) {
  if (!href) return null;
  href = String(href).replace(/&amp;/g, "&").trim();
  if (/^https?:/i.test(href)) return href;
  if (href.indexOf("//") === 0) return "https:" + href;
  var origin = /^(https?:\/\/[^/]+)/.exec(base);
  if (href.charAt(0) === "/") return origin ? origin[1] + href : href;
  return base.replace(/[^/]*$/, "") + href;
}

function cookiesFrom(headers) {
  if (!headers) return "";
  var list = headers.getSetCookie ? headers.getSetCookie() : [];
  if ((!list || !list.length) && headers.get) {
    var raw = headers.get("set-cookie");
    if (raw) list = String(raw).split(/,\s*(?=[A-Za-z0-9_\-]+=)/);
  }
  var out = [];
  for (var i = 0; i < (list || []).length; i++) {
    var kv = String(list[i]).split(";")[0].trim();
    if (kv) out.push(kv);
  }
  return out.join("; ");
}

function fold(value) {
  return String(value || "")
    .replace(/[\u016b\u016a]/g, "u").replace(/[\u014d\u014c]/g, "o").replace(/[\u0101\u0100]/g, "a")
    .replace(/[\u012b\u012a]/g, "i").replace(/[\u0113\u0112]/g, "e")
    .replace(/[\u0131\u0130I]/g, "i").replace(/[\u015f\u015e]/g, "s").replace(/[\u011f\u011e]/g, "g")
    .replace(/[\u00fc\u00dc]/g, "u").replace(/[\u00f6\u00d6]/g, "o").replace(/[\u00e7\u00c7]/g, "c")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
}

function titleOf(id, mediaType) {
  if (/^__alt__:/.test(String(id))) return Promise.resolve(decodeURIComponent(String(id).slice(8)));
  var kind = mediaType === "tv" ? "series" : "movie";
  return text("https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json")
    .then(function (body) { return String((JSON.parse(body).meta || {}).name || "").trim(); })
    .catch(function () { return ""; });
}

function pickAnime(list, title) {
  var want = fold(title);
  if (!want) return null;
  var best = null;
  var bestScore = -1;
  for (var i = 0; i < (list || []).length; i++) {
    var o = list[i] || {};
    var id = String(o.animeid || "").trim();
    if (!id) continue;
    var cands = [o.name_english, o.name];
    var score = 0;
    for (var c = 0; c < cands.length; c++) {
      var have = fold(cands[c]);
      if (!have) continue;
      if (have === want) score = Math.max(score, 100);
      else if (have.indexOf(want + " ") === 0) score = Math.max(score, 60);
    }
    if (score > bestScore) {
      bestScore = score;
      best = id.indexOf("http") === 0 ? id : BASE + "/anime/" + id.replace(/^\/+/, "") + "/";
    }
  }
  return bestScore > 0 ? best : null;
}

function findWatchUrl(detailHtml, animeUrl, wantSe, wantEp) {
  var animeId = first(/animeIdJs\s*=\s*['"]([A-Za-z0-9_]+)['"]/, detailHtml)
    || animeUrl.split("/anime/")[1].replace(/\/.*$/, "");
  var jsRaw = first(/var\s+allSezonlar\s*=\s*(\{[^\r\n;]+\})\s*;/, detailHtml);
  if (jsRaw) {
    try {
      var seasons = JSON.parse(jsRaw);
      if (seasons[String(wantSe)] && seasons[String(wantSe)][String(wantEp)]) {
        return BASE + "/watch/" + animeId + "/season/" + wantSe + "/episode/" + wantEp;
      }
    } catch (e) {}
  }
  var re = /href="([^"]*(?:bolum|episode)[^"]*)"/gi;
  var m;
  var fallback = null;
  while ((m = re.exec(detailHtml || "")) !== null) {
    var u = abs(BASE, m[1]);
    var sm = /(?:sezon|season)[-_\/]?(\d+).*?(?:bolum|episode)[-_\/]?(\d+)/i.exec(u);
    var se = sm ? parseInt(sm[1], 10) : 1;
    var ep = sm ? parseInt(sm[2], 10) : parseInt(first(/(?:bolum|episode)[-_\/]?(\d+)/i, u) || "0", 10);
    if (se === wantSe && ep === wantEp) return u;
    if (ep === wantEp && !fallback) fallback = u;
  }
  return fallback;
}

function tokenStreams(watchUrl, rawPage, cookies) {
  var page = String(rawPage || "").replace(/&quot;/g, '"').replace(/\\"/g, '"');
  var mainVideoId = first(/"videoId"\s*:\s*"([^"]+)"/, page);
  if (!mainVideoId) return Promise.resolve([]);
  var sid = first(/"playbackSessionId"\s*:\s*"([^"]+)"/, page) || "";
  var csrf = first(/"csrfToken"\s*:\s*"([^"]+)"/, page) || "";
  var cfgRaw = first(/data-config="(\{[\s\S]*?\})"\s*>/, rawPage);
  var cfg = null;
  if (cfgRaw) {
    try { cfg = JSON.parse(cfgRaw.replace(/&quot;/g, '"')); } catch (e) {}
  }
  var entries = [];
  var fansubs = (cfg && cfg.fansubs) || [];
  for (var i = 0; i < fansubs.length && entries.length < 3; i++) {
    var fs = fansubs[i] || {};
    if (fs.is4k || !fs.videoid) continue;
    var qs = (fs.qualities || []).map(function (q) { return String(q).replace(/p$/i, "").trim(); }).filter(Boolean);
    entries.push({ label: String(fs.name || ID).trim(), vid: String(fs.videoid).trim(), quals: qs.length ? qs : ["1080", "720", "480"] });
  }
  if (!entries.length) {
    var defQs = ((cfg && cfg.qualities) || ["1080", "720", "480"]).map(function (q) { return String(q).replace(/p$/i, "").trim(); }).filter(Boolean);
    entries.push({ label: ID, vid: mainVideoId, quals: defQs.length ? defQs : ["1080", "720", "480"] });
  }
  var reqHeaders = {
    "User-Agent": UA,
    "Accept-Language": "tr-TR,tr;q=0.9",
    "Content-Type": "application/json",
    "X-CSRF-Token": csrf,
    "Referer": watchUrl
  };
  if (cookies) reqHeaders.Cookie = cookies;
  var streamHeaders = { "User-Agent": UA, "Referer": watchUrl };
  if (cookies) streamHeaders.Cookie = cookies;

  var jobs = [];
  var seenVid = {};
  for (var e = 0; e < entries.length; e++) {
    var ent = entries[e];
    if (seenVid[ent.vid]) continue;
    seenVid[ent.vid] = 1;
    for (var qi = 0; qi < ent.quals.length && qi < 3; qi++) {
      (function (entry, q) {
        jobs.push(
          fetch(BASE + "/api/v1/video/token/", {
            method: "POST",
            headers: reqHeaders,
            body: JSON.stringify({ videoid: entry.vid, quality: q, sid: sid })
          }).then(function (r) { return r.ok ? r.json() : null; }).then(function (j) {
            if (!j || !j.token) return null;
            return {
              name: ID,
              title: ID + " - " + entry.label + " " + q + "p [MP4]",
              url: "https://sw2.deokwave.com/v/" + encodeURIComponent(entry.vid) + "/" + encodeURIComponent(q) + "/?vt=" + encodeURIComponent(j.token),
              quality: q + "p [MP4]",
              headers: streamHeaders,
              subtitles: []
            };
          }).catch(function () { return null; })
        );
      })(ent, ent.quals[qi]);
    }
  }
  return Promise.all(jobs).then(function (rows) {
    return rows.filter(Boolean);
  });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var wanted = "";
  var animeUrl = null;
  var watchUrl = null;

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      wanted = title;
      if (!title) return null;
      return text(BASE + "/search_api.php?q=" + encodeURIComponent(title));
    })
    .then(function (raw) {
      if (!raw) return null;
      var root = JSON.parse(raw);
      animeUrl = pickAnime(root.animes, wanted);
      if (!animeUrl) return null;
      return text(animeUrl);
    })
    .then(function (detail) {
      if (!detail) return null;
      watchUrl = findWatchUrl(detail, animeUrl, want, wantEp);
      if (!watchUrl) return null;
      return fetch(watchUrl, { headers: { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9", Referer: animeUrl } })
        .then(function (r) {
          if (!r.ok) throw new Error("HTTP " + r.status);
          var cookies = cookiesFrom(r.headers);
          return r.text().then(function (html) { return { html: html, cookies: cookies }; });
        });
    })
    .then(function (res) {
      if (!res) return [];
      return tokenStreams(watchUrl, res.html, res.cookies).then(function (rows) {
        if (rows.length) return rows;
        var clean = res.html.replace(/\\\//g, "/").replace(/\\"/g, '"');
        var out = [];
        var seen = {};
        var mRe = /https?:\/\/[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?/g;
        var m;
        while ((m = mRe.exec(clean)) !== null) {
          if (seen[m[0]]) continue;
          seen[m[0]] = 1;
          var isHls = m[0].indexOf(".m3u8") !== -1;
          out.push({
            name: ID,
            title: ID + " - Direct" + (isHls ? " [HLS]" : " [MP4]"),
            url: m[0],
            quality: "auto" + (isHls ? "" : " [MP4]"),
            headers: { Referer: watchUrl },
            subtitles: []
          });
        }
        return out;
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][deokwave] " + (e && e.message));
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
