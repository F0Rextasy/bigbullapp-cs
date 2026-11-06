// @provider {"id":"belgeselx", "name":"BelgeselX", "types":["tv"], "domain":"belgeselx.com", "formats":["mp4"], "sample":"tt8481358:tv:2:1", "description":"BelgeselX Türkçe belgesel dizileri; ok.ru ve CDN MP4 kaynakları.", "version":"1.0.4"}
/**
 * belgeselx — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> sitemap slug -> /belgesel/<slug> episode rows ->
 *       /video/data/<slot>.php -> ok.ru embed | belgeselx.php cid redirect | m3u8.
 */
var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
var BASE = "https://belgeselx.com";
var QMAP = { mobile: 240, lowest: 240, low: 360, sd: 480, high: 720, hd: 1080, full: 1080 };
var SRC = { "0": "new5", "2": "new1", "5": "new4", "3": "new2", "4": "new3" };
var ITAG = { "18": 360, "22": 720, "37": 1080 };
var slugs = null;

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
  if (o.headers) for (var k in o.headers) headers[k] = o.headers[k];
  return fetch(url, { method: o.method || "GET", headers: headers, body: o.body, redirect: "follow" }).then(function (r) {
    if (!r.ok) throw new Error("HTTP " + r.status + " " + url);
    return r.text();
  });
}

function first(re, body) {
  var m = re.exec(String(body || ""));
  return m ? m[1] : null;
}

function fold(v) {
  return String(v || "").toLowerCase()
    .replace(/ı/g, "i").replace(/ş/g, "s").replace(/ğ/g, "g")
    .replace(/ü/g, "u").replace(/ö/g, "o").replace(/ç/g, "c")
    .replace(/â/g, "a").replace(/î/g, "i").replace(/û/g, "u");
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

/** The site's own search is a Google CSE widget, so the sitemap is the only complete index. */
function all(bust) {
  if (slugs && !bust) return Promise.resolve(slugs);
  return text(BASE + "/sitemap.xml").then(function (body) {
    var out = [], re = /<loc>https:\/\/belgeselx\.com\/belgesel\/([^<]+)<\/loc>/g, m;
    while ((m = re.exec(body)) !== null) out.push(m[1]);
    slugs = out;
    return out;
  });
}

var ROMAN = { i: "1", ii: "2", iii: "3", iv: "4", v: "5", vi: "6", vii: "7", viii: "8", ix: "9", x: "10" };

/** Turkish title -> site slug candidates ("Planet Earth II" -> planet-earth-2). */
function candidates(list, title) {
  var fq = fold(title);
  var toks = fq.split(/[^a-z0-9]+/).filter(function (t) { return t.length > 1; });
  if (!toks.length) return [];
  var tail = toks[toks.length - 1];
  var want = ROMAN[tail] || (/^\d+$/.test(tail) ? tail : "");
  var base = want ? toks.slice(0, -1) : toks;
  if (!base.length) base = toks;
  var exact = [], loose = [];
  for (var i = 0; i < list.length; i++) {
    var slug = list[i], ok = true;
    for (var k = 0; k < base.length; k++) if (slug.indexOf(base[k]) === -1) { ok = false; break; }
    if (!ok) continue;
    var number = want ? new RegExp("[-_/ ]" + want + "$").test(slug) : false;
    if (number || fold(slug.replace(/-/g, " ")) === fq) exact.push(slug);
    else loose.push(slug);
  }
  return exact.concat(loose).slice(0, 4);
}

function rows(html) {
  var out = [], re = /<a[^>]*px-ep-row[^>]*>/g, m;
  while ((m = re.exec(html)) !== null) {
    var on = first(/onclick="([^"]*)"/, m[0]);
    if (!on) continue;
    var q = [], r2 = /'([^']*)'/g, m2;
    while ((m2 = r2.exec(on)) !== null) q.push(m2[1]);
    if (q.length >= 9) out.push(q);
  }
  return out;
}

function choose(list, wantSe, wantEp) {
  var i, same = [];
  for (i = 0; i < list.length; i++) {
    if ((parseInt(list[i][7], 10) || 0) === wantSe) same.push(list[i]);
  }
  if (!same.length) same = list.length === 1 ? list : [];
  for (i = 0; i < same.length; i++) if ((parseInt(same[i][8], 10) || 0) === wantEp) return same[i];
  return same.length === 1 ? same[0] : null;
}

function okru(id) {
  return text("https://ok.ru/dk?cmd=videoPlayerMetadata&mid=" + id, {
    method: "POST",
    headers: { "User-Agent": "Mozilla/5.0 (Linux; Android 13)", "Referer": "https://ok.ru/" }
  }).then(function (body) {
    var list = JSON.parse(body).videos || [], out = [];
    for (var i = 0; i < list.length; i++) {
      var v = list[i];
      if (!v || v.disallowed) continue;
      var name = String(v.name || "");
      if (/^mobile$/i.test(name)) continue; // same 240p tier as "lowest"
      var url = String(v.url || "").replace(/\\\//g, "/");
      if (!/^http/.test(url)) continue;
      out.push({ label: name || "auto", url: url });
    }
    return out;
  });
}

/**
 * `/video/belgeselx*.php?cid=` answers 302 to a Google CDN mp4; the `=mNN` tail is the itag.
 * Following the chain lands on googlevideo, which only plays without a Referer, so read the
 * first hop when the runtime exposes it and fall back to the final URL otherwise.
 */
function cid(url, referer) {
  return fetch(url, { method: "GET", headers: { "User-Agent": UA, "Referer": referer }, redirect: "manual" })
    .then(function (r) {
      var loc = (r.headers && r.headers.get && r.headers.get("location")) || "";
      if (/^http/.test(loc)) return loc;
      return fetch(url, { method: "GET", headers: { "User-Agent": UA }, redirect: "follow" })
        .then(function (r2) { return String(r2.url || ""); });
    })
    .catch(function () {
      return fetch(url, { method: "GET", headers: { "User-Agent": UA }, redirect: "follow" })
        .then(function (r2) { return String(r2.url || ""); })
        .catch(function () { return ""; });
    });
}

function push(out, url, quality, referer) {
  if (!url || !/^http/.test(url)) return;
  for (var i = 0; i < out.length; i++) if (out[i].url === url) return;
  out.push({
    name: "belgeselx",
    title: "belgeselx - " + (quality ? quality + "p" : "auto"),
    url: url,
    quality: quality ? quality + "p" : "",
    headers: referer ? { Referer: referer } : {},
    subtitles: []
  });
}

function scan(body, base, referer, out) {
  var chain = Promise.resolve();
  var re = /<iframe[^>]+src="([^"]*)"/g, m, jobs = [];
  while ((m = re.exec(body)) !== null) {
    var src = m[1].trim();
    if (!src) continue;
    if (/^\d+$/.test(src)) jobs.push({ ok: src });
    else if (/ok\.ru|odnoklassniki/.test(src)) jobs.push({ ok: first(/videoembed\/(\d+)/, src) });
    else if (/\.(m3u8|mp4)/.test(src)) jobs.push({ direct: /^https?:/.test(src) ? src : "https:" + src.replace(/^\/\//, "//") });
  }
  var rf = /file\s*:\s*"([^"]*)"/g;
  while ((m = rf.exec(body)) !== null) {
    var f = m[1].trim();
    if (!f) continue;
    if (f.indexOf("/video/belgeselx") !== -1) jobs.push({ cid: /^https?:/.test(f) ? f : BASE + (f.charAt(0) === "/" ? f : "/" + f) });
    else if (/\.(m3u8|mp4)/.test(f)) jobs.push({ direct: /^https?:/.test(f) ? f : BASE + "/" + f });
  }

  jobs.forEach(function (job) {
    chain = chain.then(function () {
      if (job.ok) {
        return okru(job.ok).then(function (vids) {
          for (var i = 0; i < vids.length; i++) push(out, vids[i].url, QMAP[vids[i].label.toLowerCase()] || 0, "https://ok.ru/");
        }).catch(function () {});
      }
      if (job.direct) {
        push(out, job.direct, 0, referer);
        return null;
      }
      // unfinished episodes answer the `ogw/default-user` placeholder instead of a /pw/ asset
      return cid(job.cid, referer).then(function (final) {
        if (!final || final.indexOf("/pw/") === -1 || final.indexOf("/ogw/") !== -1) return;
        var itag = first(/=m(\d+)$/, final) || first(/[?&]itag=(\d+)/, final);
        push(out, final, itag ? ITAG[itag] || 0 : 0, "");
      });
    });
  });
  return chain;
}

function pageFor(list, wantSe, wantEp, i) {
  if (i >= list.length) return Promise.resolve(null);
  return text(BASE + "/belgesel/" + list[i])
    .then(function (html) {
      var ep = choose(rows(html), wantSe, wantEp);
      if (ep) return { page: BASE + "/belgesel/" + list[i], ep: ep };
      return pageFor(list, wantSe, wantEp, i + 1);
    })
    .catch(function () {
      return pageFor(list, wantSe, wantEp, i + 1);
    });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var wantSe = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var out = [];

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return null;
      return all().then(function (list) {
        return pageFor(candidates(list, title), wantSe, wantEp, 0);
      });
    })
    .then(function (hit) {
      if (!hit) return null;
      var q = hit.ep, chain = Promise.resolve();
      for (var i = 1; i <= 3; i++) {
        (function (slot) {
          var code = q[slot];
          var file = SRC[code];
          if (!code || !file) return;
          var url = BASE + "/video/data/" + file + ".php?id=" + encodeURIComponent(q[0]) + "&sira=" + slot;
          chain = chain
            .then(function () {
              return text(url, { referer: hit.page }).then(function (body) { return scan(body, hit.page, hit.page, out); }).catch(function () {});
            });
        })(i);
      }
      return chain;
    })
    .then(function () {
      return out;
    })
    .catch(function (e) {
      console.log("[bb-nuvio][belgeselx] " + (e && e.message));
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
