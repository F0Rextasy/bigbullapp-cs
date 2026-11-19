// @provider {"id": "cizgivedizi", "name": "CizgiVeDizi", "types": ["tv", "movie"], "domain": "cizgivedizi.net", "formats": ["mp4"], "description": "CizgiVeDizi Turkce cizgi dizi, anime, dizi ve film arsivi (sunucu 2 MB Range + 60 sn cerez siniri nedeniyle Nuvio'da varsayilan kapali).", "sample": "tt7678620:tv:1:1", "version": "1.0.1", "verified": true}
/**
 * cizgivedizi — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> homepage poolData -> show/film -> video_sources/data-embeds -> yetki.php -> MP4.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://cizgivedizi.net";
var VIDEO_ORIGIN = "https://video.cizgivedizi.net";

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

function b64decode(input) {
  var chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=";
  var str = String(input || "").replace(/[^A-Za-z0-9+/=]/g, "");
  var bytes = [];
  var i = 0;
  while (i < str.length) {
    var e1 = chars.indexOf(str.charAt(i++));
    var e2 = chars.indexOf(str.charAt(i++));
    var e3 = chars.indexOf(str.charAt(i++));
    var e4 = chars.indexOf(str.charAt(i++));
    if (e1 < 0 || e2 < 0) break;
    var c1 = (e1 << 2) | (e2 >> 4);
    var c2 = ((e2 & 15) << 4) | (e3 >> 2);
    var c3 = ((e3 & 3) << 6) | e4;
    bytes.push(c1);
    if (e3 !== 64 && e3 >= 0) bytes.push(c2);
    if (e4 !== 64 && e4 >= 0) bytes.push(c3);
  }
  var pct = "";
  for (var j = 0; j < bytes.length; j++) {
    pct += "%" + ("0" + bytes[j].toString(16)).slice(-2);
  }
  try {
    return decodeURIComponent(pct);
  } catch (e) {
    var out = "";
    for (var k = 0; k < bytes.length; k++) out += String.fromCharCode(bytes[k]);
    return out;
  }
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

function normTitle(v) {
  return String(v || "").replace(/[\u0131\u0130I]/g, "i").replace(/[\u015f\u015e]/g, "s").replace(/[\u011f\u011e]/g, "g")
    .replace(/[\u00e7\u00c7]/g, "c").replace(/[\u00f6\u00d6]/g, "o").replace(/[\u00fc\u00dc]/g, "u")
    .toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
}

function matchKind(have, want) {
  var h = normTitle(have);
  var w = normTitle(want);
  if (!w || !h) return 0;
  if (h === w) return 2;
  if (h.indexOf(w + " ") === 0) return 1;
  return 0;
}

function extractEn(id, hayEn) {
  var trimmed = String(hayEn || "").trim();
  var lid = String(id || "").trim().toLowerCase();
  if (!trimmed || !lid) return "";
  var lower = trimmed.toLowerCase();
  var prefix = lid + " ";
  if (lower.indexOf(prefix) !== 0) return "";
  var rest = trimmed.slice(prefix.length);
  var restLower = lower.slice(prefix.length);
  var asciiId = lid.replace(/\u0131/g, "i").replace(/\u011f/g, "g").replace(/\u00fc/g, "u")
    .replace(/\u015f/g, "s").replace(/\u00f6/g, "o").replace(/\u00e7/g, "c");
  var idx1 = restLower.indexOf(" " + lid + " ");
  var idx2 = asciiId !== lid ? restLower.indexOf(" " + asciiId + " ") : -1;
  var cut = -1;
  if (idx1 > 0 && idx2 > 0) cut = Math.min(idx1, idx2);
  else cut = idx1 > 0 ? idx1 : idx2;
  return cut > 0 ? rest.slice(0, cut).trim() : "";
}

function findInPool(html, wantTitle, isMovie) {
  var marker = '<script type="application/json" id="poolData">';
  var pos = html.indexOf(marker);
  if (pos < 0) return null;
  pos += marker.length;
  var endPool = html.indexOf("</script>", pos);
  if (endPool < 0) endPool = html.length;

  var best = null;
  var bestRank = 0;
  while (pos < endPool) {
    var start = html.indexOf('{"dataset":{', pos);
    if (start < 0 || start >= endPool) break;
    var htmlIdx = html.indexOf(',"html":', start);
    if (htmlIdx < 0 || htmlIdx >= endPool) break;
    try {
      var obj = JSON.parse(html.slice(start, htmlIdx) + "}");
      var ds = obj.dataset || {};
      var itemIsFilm = ds.type === "film";
      if (ds.type !== "other" && itemIsFilm === isMovie) {
        var r1 = matchKind(obj.label || ds.name, wantTitle);
        var r2 = matchKind(extractEn(ds.id, ds.hayEn), wantTitle);
        var rank = r1 > r2 ? r1 : r2;
        if (rank > bestRank) {
          bestRank = rank;
          best = { id: ds.id, href: obj.href, type: ds.type };
          if (rank === 2) break;
        }
      }
    } catch (e) {}
    pos = htmlIdx + 8;
  }
  return best;
}

function extractBridgeKey(embed) {
  var raw = first(/[?&]k=([^&#]+)/, String(embed || ""));
  if (!raw) return null;
  try {
    return decodeURIComponent(raw);
  } catch (e) {
    return raw;
  }
}

function resolveKey(k, label) {
  if (!k) return Promise.resolve(null);
  var url = VIDEO_ORIGIN + "/yetki.php?k=" + encodeURIComponent(k);
  return fetch(url, {
    method: "GET",
    headers: {
      "User-Agent": UA,
      "Referer": BASE + "/",
      "Origin": BASE,
      "Accept": "*/*",
      "Sec-Fetch-Dest": "empty",
      "Sec-Fetch-Mode": "cors",
      "Sec-Fetch-Site": "same-site"
    }
  }).then(function (r) {
    if (!r || !r.ok || !r.headers) return null;
    var opaque = typeof r.headers.get === "function"
      ? (r.headers.get("x-cvd-video-url") || r.headers.get("X-CVD-Video-URL") || "")
      : "";
    if (!/^\/[A-Za-z0-9_-]{43}$/.test(String(opaque).trim())) return null;
    var rawCookies = "";
    if (typeof r.headers.getSetCookie === "function") rawCookies = r.headers.getSetCookie().join("; ");
    else if (typeof r.headers.getAll === "function") rawCookies = r.headers.getAll("set-cookie").join("; ");
    else if (typeof r.headers.get === "function") rawCookies = r.headers.get("set-cookie") || "";
    if (!rawCookies && r.headers.map && r.headers.map["set-cookie"]) {
      rawCookies = [].concat(r.headers.map["set-cookie"]).join("; ");
    }
    var sid = first(/cvd_video_sid=([^;,\s]+)/, rawCookies);
    var auth = first(/cvd_video_auth=([^;,\s]+)/, rawCookies);
    if (!sid || !auth) return null;
    return {
      name: "CizgiVeDizi",
      title: "CizgiVeDizi - " + label + " [MP4]",
      url: VIDEO_ORIGIN + String(opaque).trim(),
      quality: "auto",
      headers: {
        "User-Agent": UA,
        "Referer": BASE + "/",
        "Cookie": "cvd_video_sid=" + sid + "; cvd_video_auth=" + auth,
        "Sec-Fetch-Dest": "video",
        "Sec-Fetch-Mode": "no-cors",
        "Sec-Fetch-Site": "same-site"
      },
      subtitles: []
    };
  }).catch(function () {
    return null;
  });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var wantSeason = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var isMovie = mediaType === "movie";
  var wantTitle = "";
  var foundItem = null;

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      wantTitle = title;
      if (!title) return null;
      return text(BASE + "/");
    })
    .then(function (homeHtml) {
      if (!homeHtml) return null;
      foundItem = findInPool(homeHtml, wantTitle, isMovie);
      if (!foundItem || !foundItem.href) return null;
      return text(abs(BASE, foundItem.href), { referer: BASE + "/" });
    })
    .then(function (detailHtml) {
      if (!detailHtml || !foundItem) return [];
      if (isMovie) {
        var b64 = first(/data-embeds=["']([^"']+)["']/, detailHtml);
        if (!b64) return [];
        var embeds = [];
        try {
          embeds = JSON.parse(b64decode(b64));
        } catch (e) {
          return [];
        }
        var tasks = [];
        for (var i = 0; i < embeds.length; i++) {
          var key = extractBridgeKey(embeds[i]);
          if (key) tasks.push(resolveKey(key, embeds.length > 1 ? "Kaynak " + (i + 1) : "Merkez"));
        }
        return Promise.all(tasks);
      }

      var blocks = detailHtml.split('<section class="cvd-season-block">');
      var overallKey = null;
      for (var bi = 1; bi < blocks.length; bi++) {
        var blk = blocks[bi];
        var sHead = first(/class="cvd-sh-text"[^>]*>([^<]+)</, blk) || "";
        var sNum = parseInt(first(/^(\d+)/, sHead.trim()), 10);
        if (sNum !== wantSeason) continue;
        var entries = blk.split('<tbody class="cvd-episode-entry"');
        for (var ei = 1; ei < entries.length; ei++) {
          var ent = entries[ei];
          var sn = parseInt(first(/class="cvd-no-cell cvd-season-no"[^>]*>\s*([^<\s]+)\s*</, ent), 10);
          if (sn === wantEp) {
            overallKey = first(/class="cvd-no-cell cvd-overall-no"[^>]*>\s*([^<]+?)\s*</, ent);
            break;
          }
        }
        if (overallKey) break;
      }
      if (!overallKey) return [];
      return text(BASE + "/diziyeni/video_sources.php?s=" + encodeURIComponent(foundItem.id) + "&b=" + encodeURIComponent(overallKey.trim()), {
        referer: abs(BASE, foundItem.href)
      }).then(function (vsBody) {
        var vs = {};
        try { vs = JSON.parse(vsBody); } catch (e) { return []; }
        var rows = vs.sources || [];
        var jobs = [];
        for (var ri = 0; ri < rows.length; ri++) {
          var row = rows[ri] || {};
          var k = extractBridgeKey(row.embed);
          if (!k) continue;
          var lbl = String(row.ad || row.kanal || "Merkez").trim();
          jobs.push(resolveKey(k, lbl));
        }
        return Promise.all(jobs);
      });
    })
    .then(function (list) {
      var out = [];
      for (var i = 0; i < (list || []).length; i++) {
        if (list[i] && list[i].url) out.push(list[i]);
      }
      return out;
    })
    .catch(function (e) {
      console.log("[bb-nuvio][cizgivedizi] " + (e && e.message));
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

/* Cinemeta basligi yerellestirir ("Gibi" -> "As If"); sonuc bossa Wikidata TR/EN basliklariyla yeniden dene. */
function __altTitles(raw, mediaType) {
  var h = { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36", "Accept": "application/json" };
  var api = "https://www.wikidata.org/w/api.php?format=json";
  var stmt = /^tt\d+$/.test(raw) ? "P345=" + raw : (mediaType === "movie" ? "P4947=" : "P4983=") + raw;
  var wd = fetch(api + "&action=query&list=search&srlimit=1&srsearch=" + encodeURIComponent("haswbstatement:" + stmt), { headers: h })
    .then(function (r) { return r.ok ? r.json() : null; })
    .then(function (j) {
      var hits = (j && j.query && j.query.search) || [];
      if (!hits.length) return null;
      return fetch(api + "&action=wbgetentities&props=labels|aliases&languages=tr|en&ids=" + hits[0].title, { headers: h }).then(function (r) { return r.ok ? r.json() : null; });
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
