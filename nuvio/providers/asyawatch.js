// @provider {"id":"asyawatch","name":"AsyaWatch","types":["tv","movie"],"domain":"asyawatch.com","formats":["m3u8"],"sample":"tt10919420:tv:1:1","description":"AsyaWatch - Kore, Cin ve Japon dizi/filmleri (pichive HLS).","verified":true,"version":"1.0.0"}
/**
 * asyawatch — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /ara?q= -> /dizi/<slug> or /film/<slug> ->
 *       JSON-LD containsSeason.episode -> pichive iframe (NP_CONFIG + pichiveSign or openPlayer) -> HLS.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://asyawatch.com";

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
  return m ? m[1] : null;
}

function abs(base, href) {
  if (!href) return null;
  href = String(href).replace(/&amp;/g, "&").trim();
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

var K256 = [
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
];
function sha256Words(str) {
  var msg = [];
  for (var i = 0; i < str.length; i++) msg.push(str.charCodeAt(i) & 0xff);
  var len = msg.length;
  msg.push(0x80);
  while (msg.length % 64 !== 56) msg.push(0);
  var bits = len * 8;
  msg.push(0, 0, 0, 0, (bits >>> 24) & 0xff, (bits >>> 16) & 0xff, (bits >>> 8) & 0xff, bits & 0xff);
  var h0 = 0x6a09e667, h1 = 0xbb67ae85, h2 = 0x3c6ef372, h3 = 0xa54ff53a;
  var h4 = 0x510e527f, h5 = 0x9b05688c, h6 = 0x1f83d9ab, h7 = 0x5be0cd19;
  var w = [];
  for (var off = 0; off < msg.length; off += 64) {
    for (var j = 0; j < 16; j++) w[j] = (msg[off + 4 * j] << 24) | (msg[off + 4 * j + 1] << 16) | (msg[off + 4 * j + 2] << 8) | msg[off + 4 * j + 3];
    for (var t = 16; t < 64; t++) {
      var x = w[t - 15], y = w[t - 2];
      var s0 = ((x >>> 7) | (x << 25)) ^ ((x >>> 18) | (x << 14)) ^ (x >>> 3);
      var s1 = ((y >>> 17) | (y << 15)) ^ ((y >>> 19) | (y << 13)) ^ (y >>> 10);
      w[t] = (w[t - 16] + s0 + w[t - 7] + s1) | 0;
    }
    var a = h0, b = h1, c = h2, d = h3, e = h4, f = h5, g = h6, h = h7;
    for (var r = 0; r < 64; r++) {
      var S1 = ((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7));
      var ch = (e & f) ^ (~e & g);
      var t1 = (h + S1 + ch + K256[r] + w[r]) | 0;
      var S0 = ((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10));
      var maj = (a & b) ^ (a & c) ^ (b & c);
      var t2 = (S0 + maj) | 0;
      h = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0;
    }
    h0 = (h0 + a) | 0; h1 = (h1 + b) | 0; h2 = (h2 + c) | 0; h3 = (h3 + d) | 0;
    h4 = (h4 + e) | 0; h5 = (h5 + f) | 0; h6 = (h6 + g) | 0; h7 = (h7 + h) | 0;
  }
  return [h0 >>> 0, h1 >>> 0, h2 >>> 0, h3 >>> 0, h4 >>> 0, h5 >>> 0, h6 >>> 0, h7 >>> 0];
}

function pichiveSign(html, url) {
  var u = String(url || "");
  if ((u.indexOf("/nm.php") === -1 && u.indexOf("/nsub.php") === -1) || u.indexOf("&p=") !== -1 || u.indexOf("?p=") !== -1) return u;
  var gm = /"gate"\s*:\s*\{\s*"n"\s*:\s*"([^"]+)"(?:\s*,\s*"b"\s*:\s*(\d+))?/.exec(String(html || ""));
  if (!gm) return u;
  var n = gm[1], bits = parseInt(gm[2], 10) || 12;
  var sep = u.indexOf("?") !== -1 ? "&p=" : "?p=";
  var fn = /window\.NP_GATE\s*=\s*function\(\s*(\w+)\s*,\s*(\w+)\s*\)\s*\{([\s\S]*?)\};/.exec(String(html || ""));
  if (!fn) {
    for (var i = 0; i < 5000000; i++) if ((sha256Words(n + ":" + i)[0] >>> (32 - bits)) === 0) return u + sep + i;
    return u;
  }
  var nVar = fn[1], body = fn[3];
  var cm = /var\s+(\w+)\s*=\s*String\.fromCharCode\(([\d,\s]+)\)/.exec(body);
  var nm = /var\s+(\w+)\s*=\s*(?:\w+\.toString\((\d*)\)|String\(\w+\))/.exec(body);
  var im = /\(\s*\w+\(([^)]+)\)\[(\d+)\]\s*(>>>|&)\s*(\d+)\s*\)\s*===?\s*0/.exec(body);
  if (!cm || !nm || !im) return u;
  var saltVar = cm[1], salt = cm[2].split(",").map(function (x) { return String.fromCharCode(parseInt(x.trim(), 10)); }).join("");
  var nonceVar = nm[1], radix = parseInt(nm[2], 10) || 10, parts = im[1].split("+").map(function (s) { return s.trim(); });
  var wordIdx = parseInt(im[2], 10) || 0, op = im[3], rhs = parseInt(im[4], 10) >>> 0;
  for (var k = 0; k < 5000000; k++) {
    var nonce = k.toString(radix), msg = "";
    for (var p = 0; p < parts.length; p++) {
      var tok = parts[p];
      if (tok === nVar) msg += n;
      else if (tok === saltVar) msg += salt;
      else if (tok === nonceVar) msg += nonce;
      else msg += tok.replace(/^["']|["']$/g, "");
    }
    var w = sha256Words(msg)[wordIdx] >>> 0;
    if ((op === ">>>" ? (w >>> rhs) : ((w & rhs) >>> 0)) === 0) return u + sep + nonce;
  }
  return u;
}

function titleOf(id, mediaType) {
  if (/^__alt__:/.test(String(id))) return Promise.resolve(decodeURIComponent(String(id).slice(8)));
  var kind = mediaType === "movie" ? "movie" : "series";
  return text("https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json")
    .then(function (body) {
      var meta = JSON.parse(body).meta || {};
      return String(meta.name || "").trim();
    })
    .catch(function () {
      return "";
    });
}

function norm(value) {
  return String(value || "")
    .replace(/[\u0131\u0130I]/g, "i").replace(/[\u015f\u015e]/g, "s").replace(/[\u011f\u011e]/g, "g")
    .replace(/[\u00fc\u00dc]/g, "u").replace(/[\u00f6\u00d6]/g, "o").replace(/[\u00e7\u00c7]/g, "c")
    .toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
}

function objectAt(html, from) {
  var depth = 0, inString = false, escaped = false;
  for (var i = from; i < html.length; i++) {
    var ch = html.charAt(i);
    if (inString) {
      if (escaped) escaped = false;
      else if (ch === "\\") escaped = true;
      else if (ch === '"') inString = false;
      continue;
    }
    if (ch === '"') inString = true;
    else if (ch === "{") depth++;
    else if (ch === "}") {
      depth--;
      if (!depth) return html.slice(from, i + 1);
    }
  }
  return null;
}

function searchCards(html, isTv) {
  var out = [], seen = {};
  var re = /<a\b[^>]*href="([^"]*\/(?:dizi|film)\/[^"]+)"[^>]*>([\s\S]*?)<\/a>/g;
  var m;
  while ((m = re.exec(String(html || ""))) !== null) {
    var href = abs(BASE, m[1]);
    if (!href || seen[href]) continue;
    if (href.indexOf("/sezon-") !== -1 || href.indexOf("/bolum-") !== -1) continue;
    var rowTv = href.indexOf("/dizi/") !== -1;
    if (rowTv !== isTv) continue;
    var aria = first(/aria-label="([^"]+)"/, m[0]) || "";
    var ariaTitle = aria.replace(/^\d+\.\s*sırada?\s*,?\s*/, "").split(",")[0];
    var inner = first(/<(?:b|h2|h3)[^>]*>([^<]+)<\/(?:b|h2|h3)>/, m[2]) || ariaTitle || first(/alt="([^"]+)"/, m[2]);
    if (!inner) continue;
    seen[href] = 1;
    out.push({ url: href, title: inner.trim() });
  }
  return out;
}

function pickCard(cards, wantTitle) {
  var want = norm(wantTitle);
  if (!want) return null;
  var best = null;
  for (var i = 0; i < cards.length; i++) {
    var have = norm(cards[i].title);
    if (have === want) return cards[i];
    if (!best && have.indexOf(want + " ") === 0) best = cards[i];
  }
  return best;
}

function episodeUrl(html, want, wantEp) {
  var re = /<script[^>]+type="application\/ld\+json"[^>]*>([\s\S]*?)<\/script>/g;
  var m;
  while ((m = re.exec(String(html || ""))) !== null) {
    var ld;
    try { ld = JSON.parse(m[1]); } catch (e) { continue; }
    if (!ld || !ld.containsSeason) continue;
    var seasons = Array.isArray(ld.containsSeason) ? ld.containsSeason : [ld.containsSeason];
    for (var s = 0; s < seasons.length; s++) {
      var eps = (seasons[s] && seasons[s].episode) || [];
      for (var e = 0; e < eps.length; e++) {
        var u = eps[e] && eps[e].url;
        if (!u) continue;
        var seMatch = /\/sezon-(\d+)\/bolum-(\d+)/.exec(u);
        if (seMatch) {
          if (parseInt(seMatch[1], 10) === want && parseInt(seMatch[2], 10) === wantEp) return u;
        } else if (want === 1 && e + 1 === wantEp) {
          return u;
        }
      }
    }
  }
  var fallback = new RegExp('href="([^"]*/sezon-' + want + '/bolum-' + wantEp + '[^"]*)"', "i");
  var hit = first(fallback, String(html || "").replace(/\\"/g, '"'));
  return hit ? abs(BASE, hit) : null;
}

function resolveCandidate(candidate) {
  if (/\.(m3u8|mp4)/i.test(candidate)) {
    var isHls = /\.m3u8/i.test(candidate);
    return Promise.resolve([{
      name: "AsyaWatch",
      title: "AsyaWatch" + (isHls ? " [HLS]" : " [MP4]"),
      url: isHls ? hlsNoDownload(candidate) : candidate,
      quality: "auto",
      headers: { Referer: BASE + "/", "User-Agent": UA },
      subtitles: []
    }]);
  }
  return text(candidate, { referer: BASE + "/" }).then(function (embed) {
    var org = origin(candidate) || "";
    var out = [], seen = {};
    var at = embed.indexOf("NP_CONFIG");
    if (at >= 0) {
      var open = embed.indexOf("{", at);
      var rawCfg = open >= 0 ? objectAt(embed, open) : null;
      if (rawCfg) {
        try {
          var cfg = JSON.parse(rawCfg);
          var subs = [];
          var tracks = cfg.subtitles || [];
          for (var j = 0; j < tracks.length; j++) {
            var tr = tracks[j] || {};
            var rawSub = /^https?:/i.test(tr.url || "") ? tr.url : (tr.tok ? org + "/nsub.php?v=" + tr.tok : "");
            if (!rawSub) continue;
            subs.push({
              url: pichiveSign(embed, rawSub),
              language: /ing|eng/i.test(tr.lang || tr.label || "") ? "en" : "tr",
              name: String(tr.label || tr.lang || "Türkçe").trim() + " [AsyaWatch]"
            });
          }
          var srcs = cfg.sources || [];
          for (var i = 0; i < srcs.length; i++) {
            var u = srcs[i] && srcs[i].master;
            if (!/^https?:/i.test(u || "")) continue;
            var signed = pichiveSign(embed, u);
            if (seen[signed]) continue;
            seen[signed] = 1;
            out.push({
              name: "AsyaWatch",
              title: "AsyaWatch [HLS]",
              url: hlsNoDownload(signed),
              quality: "auto",
              headers: { Referer: candidate, "User-Agent": UA },
              subtitles: subs.slice(0, 6)
            });
          }
          if (out.length) return out;
        } catch (e) {}
      }
    }
    var token = first(/openPlayer\s*\(\s*['"]([^'"]+)/, embed);
    if (org && token) {
      return text(org + "/source2.php?v=" + encodeURIComponent(token), {
        referer: candidate,
        headers: { Accept: "application/json" }
      }).then(function (body) {
        var json;
        try { json = JSON.parse(body); } catch (e) { return []; }
        var list = json.playlist || [];
        for (var i = 0; i < list.length; i++) {
          var srcs = list[i].sources || [];
          for (var j = 0; j < srcs.length; j++) {
            var f = String((srcs[j] || {}).file || "");
            if (!/^https?:/i.test(f)) continue;
            var master = f.indexOf("m.php") !== -1 ? f.replace("m.php", "master.m3u8") : f;
            if (seen[master]) continue;
            seen[master] = 1;
            out.push({
              name: "AsyaWatch",
              title: "AsyaWatch [HLS]",
              url: hlsNoDownload(master),
              quality: "auto",
              headers: { Referer: candidate, "User-Agent": UA },
              subtitles: []
            });
          }
        }
        return out;
      });
    }
    var reM = /"(?:master|file)"\s*:\s*"(https?:\/\/[^"]+)"/g, mm;
    while ((mm = reM.exec(embed)) !== null) {
      var su = pichiveSign(embed, mm[1]);
      if (seen[su]) continue;
      seen[su] = 1;
      out.push({
        name: "AsyaWatch",
        title: "AsyaWatch [HLS]",
        url: hlsNoDownload(su),
        quality: "auto",
        headers: { Referer: candidate, "User-Agent": UA },
        subtitles: []
      });
    }
    return out;
  }).catch(function () {
    return [];
  });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var isTv = mediaType !== "movie";
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return null;
      return text(BASE + "/ara?q=" + encodeURIComponent(title)).then(function (html) {
        return pickCard(searchCards(html, isTv), title);
      });
    })
    .then(function (card) {
      if (!card) return null;
      return text(card.url, { referer: BASE + "/" });
    })
    .then(function (html) {
      if (!html) return null;
      if (!isTv) return html;
      var ep = episodeUrl(html, want, wantEp);
      if (!ep) return null;
      return text(ep, { referer: BASE + "/" });
    })
    .then(function (html) {
      if (!html) return [];
      var clean = String(html).replace(/\\"/g, '"').replace(/\\\//g, "/");
      var re = /"(?:src|master)"\s*:\s*"(https?:\/\/[^"]+)"/g;
      var m, cands = [], seen = {};
      while ((m = re.exec(clean)) !== null) {
        var u = m[1];
        if (!/(pichive|hotlinger|playru|\.m3u8|\.mp4)/i.test(u) || seen[u]) continue;
        seen[u] = 1;
        cands.push(u);
      }
      if (!cands.length) return [];
      return Promise.all(cands.slice(0, 3).map(resolveCandidate)).then(function (lists) {
        var out = [], seenOut = {};
        for (var i = 0; i < lists.length; i++) {
          for (var j = 0; j < lists[i].length; j++) {
            if (!seenOut[lists[i][j].url]) {
              seenOut[lists[i][j].url] = 1;
              out.push(lists[i][j]);
            }
          }
        }
        return out;
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][asyawatch] " + (e && e.message));
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
