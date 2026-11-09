// @provider {"id":"rectv", "name":"RecTV", "types":["movie", "tv"], "domain":"rectv.org.tr", "formats":["m3u8"], "sample":"tt0903747:tv:1:1", "description":"RecTV imzalı resmî API film ve dizi kataloğu (attest/nonce + HMAC-SHA256; arama ve sezon/bölüm uçları, token'lı m3u8).", "verified":true, "version":"1.0.3"}
var CFG = {
  id: "rectv",
  name: "RecTV",
  base: "https://a.psrectv80.xyz",
  altBase: "https://m.prectv72.lol",
  ua: "app okhttp/4.12.0",
  clientId: "rectv-android",
  appVersion: "22.0.6",
  hmacKey: "3508611138826751fdf77beaa6f93eb93fd27e6a5acb910e7aad22665513dd6e",
  k1: "4F5A9C3D9A86FA54EACEDDD635185",
  k2: "c3c5bd17-e37b-4b94-a944-8a3688a30452",
  f5: "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  maxScanPages: 4
};

function utf8Bytes(str) {
  var out = [];
  for (var i = 0; i < str.length; i++) {
    var c = str.charCodeAt(i);
    if (c < 0x80) out.push(c);
    else if (c < 0x800) out.push(0xc0 | (c >> 6), 0x80 | (c & 0x3f));
    else out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f));
  }
  return out;
}
function b2hex(b) {
  var h = "", t, i;
  for (i = 0; i < b.length; i++) {
    t = b[i].toString(16);
    h += (t.length < 2 ? "0" : "") + t;
  }
  return h;
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
function sha256b(bytes) {
  var h = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
  var m = bytes.slice(), i, off, t1, t2, s0, s1;
  var ml = m.length * 8;
  m.push(0x80);
  while (m.length % 64 !== 56) m.push(0);
  for (i = 0; i < 8; i++) m.push(0);
  m[m.length - 1] = ml & 0xff;
  m[m.length - 2] = (ml >>> 8) & 0xff;
  m[m.length - 3] = (ml >>> 16) & 0xff;
  m[m.length - 4] = (ml >>> 24) & 0xff;
  var w = new Array(64);
  for (off = 0; off < m.length; off += 64) {
    for (i = 0; i < 16; i++) {
      w[i] = (m[off + i * 4] << 24) | (m[off + i * 4 + 1] << 16) | (m[off + i * 4 + 2] << 8) | m[off + i * 4 + 3];
    }
    for (i = 16; i < 64; i++) {
      s0 = ((w[i - 15] >>> 7) | (w[i - 15] << 25)) ^ ((w[i - 15] >>> 18) | (w[i - 15] << 14)) ^ (w[i - 15] >>> 3);
      s1 = ((w[i - 2] >>> 17) | (w[i - 2] << 15)) ^ ((w[i - 2] >>> 19) | (w[i - 2] << 13)) ^ (w[i - 2] >>> 10);
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) | 0;
    }
    var a = h[0], bb = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], hh = h[7];
    for (i = 0; i < 64; i++) {
      s1 = ((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7));
      var ch = (e & f) ^ (~e & g);
      t1 = (hh + s1 + ch + K256[i] + w[i]) | 0;
      s0 = ((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10));
      var maj = (a & bb) ^ (a & c) ^ (bb & c);
      t2 = (s0 + maj) | 0;
      hh = g; g = f; f = e; e = (d + t1) | 0; d = c; c = bb; bb = a; a = (t1 + t2) | 0;
    }
    h[0] = (h[0] + a) | 0; h[1] = (h[1] + bb) | 0; h[2] = (h[2] + c) | 0; h[3] = (h[3] + d) | 0;
    h[4] = (h[4] + e) | 0; h[5] = (h[5] + f) | 0; h[6] = (h[6] + g) | 0; h[7] = (h[7] + hh) | 0;
  }
  var out = [];
  for (i = 0; i < 8; i++) {
    out.push((h[i] >>> 24) & 0xff, (h[i] >>> 16) & 0xff, (h[i] >>> 8) & 0xff, h[i] & 0xff);
  }
  return out;
}
function hmacSha256Hex(keyStr, msg) {
  var k = utf8Bytes(keyStr), ip = [], op = [], i;
  if (k.length > 64) k = sha256b(k);
  while (k.length < 64) k.push(0);
  for (i = 0; i < 64; i++) {
    ip.push(k[i] ^ 0x36);
    op.push(k[i] ^ 0x5c);
  }
  var inner = sha256b(ip.concat(utf8Bytes(msg)));
  return b2hex(sha256b(op.concat(inner)));
}

function httpJson(url, headers) {
  var req = { method: "GET", headers: headers || { "user-agent": CFG.ua } };
  return fetch(url, req).then(function (r) {
    return r.text().then(function (t) { return { status: r.status, text: t }; });
  });
}

function nonceGet(base) {
  return httpJson(base + "/api/attest/nonce").then(function (r) {
    try {
      return JSON.parse(r.text).nonce || null;
    } catch (e) {
      return null;
    }
  });
}

function signedGet(base, path) {
  var attempt = function (nonce) {
    var ts = String(Math.floor(new Date().getTime() / 1000));
    var msg = "GET\n" + path + "\n" + ts + "\n" + nonce + "\n" + CFG.f5;
    var sig = hmacSha256Hex(CFG.hmacKey, msg);
    var h = {
      "user-agent": CFG.ua,
      "X-Signature": sig,
      "X-Timestamp": ts,
      "X-Nonce": nonce,
      "X-Client-Id": CFG.clientId,
      "X-App-Version": CFG.appVersion
    };
    return httpJson(base + path, h);
  };
  return nonceGet(base).then(function (n) {
    if (!n) return { status: 0, text: "[]", nonce: null };
    return attempt(n).then(function (r) {
      if (r.text.indexOf("nonce_replay") === -1) return r;
      return nonceGet(base).then(function (n2) {
        return n2 ? attempt(n2) : r;
      });
    });
  });
}

function signedJson(path) {
  var tryBase = function (base) {
    return signedGet(base, path).then(function (r) {
      if (r.status === 200) {
        try { return JSON.parse(r.text); } catch (e) { return null; }
      }
      return null;
    }).catch(function () { return null; });
  };
  return tryBase(CFG.base).then(function (res) {
    return res !== null ? res : tryBase(CFG.altBase);
  });
}

function normTitle(s) {
  return String(s || "")
    .toLowerCase()
    .replace(/[^a-z0-9\u00e7\u011f\u0131\u00f6\u015f\u00fc ]+/gi, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function titleMatches(itemTitle, needle) {
  var raw = String(itemTitle || "");
  if (normTitle(raw) === needle) return true;
  var parts = raw.split(/\s+-\s+/);
  for (var i = 0; i < parts.length; i++) {
    if (normTitle(parts[i]) === needle) return true;
  }
  return false;
}

function languageTagOf(contextLabel, src) {
  var st = String(src.title || "").toLowerCase();
  var hay = (st.indexOf("dublaj") !== -1 || st.indexOf("altyaz") !== -1) ? st : (st + " " + String(contextLabel || "")).toLowerCase();
  var tags = [];
  if (hay.indexOf("dublaj") !== -1) tags.push("TR Dublaj");
  if (hay.indexOf("altyaz") !== -1) tags.push("Altyaz\u0131l\u0131");
  return tags.length ? " \u2022 " + tags.join(" \u2022 ") : "";
}

function qualityOf(raw) {
  var q4 = String(raw || "").toUpperCase();
  if (q4.indexOf("4K") !== -1 || q4.indexOf("UHD") !== -1 || q4.indexOf("2160") !== -1) return "2160p";
  if (q4.indexOf("FHD") !== -1 || q4.indexOf("1080") !== -1) return "1080p";
  if (q4.indexOf("720") !== -1) return "720p";
  return "720p";
}

function emitSources(srcs, titlePrefix, contextLabel, out, seen) {
  var list = srcs || [];
  for (var i = 0; i < list.length; i++) {
    var src = list[i];
    var url = src && src.url;
    if (!url || !/^https?:\/\//.test(url) || seen[url]) continue;
    seen[url] = 1;
    var tag = languageTagOf(contextLabel, src);
    out.push({
      name: "RecTV",
      title: titlePrefix + (src.title ? " - " + src.title : "") + tag,
      url: url,
      quality: qualityOf(src.quality) + tag,
      headers: { "user-agent": CFG.ua, "referer": CFG.base + "/" },
      subtitles: []
    });
  }
}

function getStreams(id, mediaType, season, episode) {
  var tv = mediaType === "tv" || mediaType === "series";
  var wantType = tv ? "serie" : "movie";
  var wantS = parseInt(season, 10) || 1;
  var wantE = parseInt(episode, 10) || 1;

  var altName = /^__alt__:/.test(String(id)) ? decodeURIComponent(String(id).slice(8)) : "";
  return (altName ? Promise.resolve(JSON.stringify({ meta: { name: altName } })) : fetch("https://v3-cinemeta.strem.io/meta/" + (tv ? "series" : "movie") + "/" + id + ".json")
    .then(function (r) { return r.text(); })
    .catch(function () { return "{}"; }))
    .then(function (metaText) {
      var name = "";
      try { name = (JSON.parse(metaText).meta || {}).name || ""; } catch (e) { name = ""; }
      var needle = normTitle(name);
      if (!needle) {
        console.log("[bb-nuvio][rectv] cinemeta basligi cozulemedi: " + id);
        return [];
      }
      return findItem(name, needle, wantType).then(function (hit) {
        if (!hit) {
          console.log("[bb-nuvio][rectv] eslesme yok: " + id + " (" + needle + ")");
          return [];
        }
        return tv ? serieStreams(hit, wantS, wantE) : movieStreams(hit);
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][rectv] " + (e && e.message));
      return [];
    });

  function findItem(rawName, needle, kind) {
    var searchPath = "/api/search/" + encodeURIComponent(rawName) + "/" + CFG.k1 + "/" + CFG.k2 + "/";
    return signedJson(searchPath).then(function (res) {
      var posters = (res && res.posters) || [];
      for (var i = 0; i < posters.length; i++) {
        var p = posters[i];
        if (p && p.type === kind && titleMatches(p.title, needle)) return p;
      }
      return scanKind(kind, 1, needle);
    });
  }

  function scanKind(kind, page, needle) {
    if (page > CFG.maxScanPages) return Promise.resolve(null);
    var path = "/api/" + kind + "/by/filtres/0/created/" + page + "/" + CFG.k1 + "/" + CFG.k2 + "/";
    return signedJson(path).then(function (rows) {
      if (!rows || !rows.length) return null;
      for (var i = 0; i < rows.length; i++) {
        if (titleMatches(rows[i].title, needle)) return rows[i];
      }
      return scanKind(kind, page + 1, needle);
    });
  }

  function movieStreams(item) {
    var out = [];
    emitSources(item.sources, item.title, item.label, out, {});
    return out;
  }

  function serieStreams(item, sNum, eNum) {
    var path = "/api/season/by/serie/" + item.id + "/" + CFG.k1 + "/" + CFG.k2 + "/";
    return signedJson(path).then(function (seasons) {
      var list = seasons || [];
      var out = [];
      var seen = {};
      for (var i = 0; i < list.length; i++) {
        var sObj = list[i] || {};
        var sTitle = String(sObj.title || "");
        var sm = /(\d+)/.exec(sTitle);
        var parsedS = sm ? parseInt(sm[1], 10) : i + 1;
        if (parsedS !== sNum) continue;
        var eps = sObj.episodes || [];
        for (var j = 0; j < eps.length; j++) {
          var ep = eps[j] || {};
          var em = /(\d+)/.exec(String(ep.title || ""));
          var parsedE = em ? parseInt(em[1], 10) : j + 1;
          if (parsedE !== eNum) continue;
          emitSources(ep.sources, item.title + " S" + sNum + "E" + eNum, sTitle, out, seen);
        }
      }
      return out;
    });
  }
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
  return fetch(api + "&action=query&list=search&srlimit=1&srsearch=" + encodeURIComponent("haswbstatement:" + stmt), { headers: h })
    .then(function (r) { return r.ok ? r.json() : null; })
    .then(function (j) {
      var hits = (j && j.query && j.query.search) || [];
      if (!hits.length) return null;
      return fetch(api + "&action=wbgetentities&props=labels|aliases&languages=tr|en&ids=" + hits[0].title, { headers: h }).then(function (r) { return r.ok ? r.json() : null; });
    })
    .then(function (j) {
      var out = [], seen = {}, ents = (j && j.entities) || {}, k, l, a, i;
      function add(v) {
        v = String(v || "").replace(/\s*\([^)]*\)\s*$/, "").trim();
        if (v && !seen[v.toLowerCase()]) { seen[v.toLowerCase()] = 1; out.push(v); }
      }
      for (k in ents) {
        l = ents[k].labels || {}; a = ents[k].aliases || {};
        add((l.tr || {}).value); add((l.en || {}).value);
        for (i = 0; i < (a.tr || []).length && i < 2; i++) add(a.tr[i].value);
      }
      return out;
    })
    .catch(function () { return []; });
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

/* Dil etiketi (AGENTS.md): Nuvio karti "quality \u2022 ..." satirini gosterir; dil etiketi kalitenin yanina yazilir. */
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
