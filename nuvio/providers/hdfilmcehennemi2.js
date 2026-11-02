// @provider {"id":"hdfilmcehennemi2","name":"HDFilmCehennemi2","types":["movie"],"domain":"hdfilmcehennemi2.biz","formats":["m3u8"],"description":"HDFilmCehennemi2 - Turkce dublaj/altyazili filmler; Alpine tabanli oynataci (HLS + altyazi).","sample":"tt0133093:movie:0:0"}
/**
 * hdfilmcehennemi2 — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /api/search -> film page videoPlayerData (base64 iframe templates)
 *       -> vidload player -> jwplayer setup (escaped JSON) -> HLS master + VTT tracks.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://www.hdfilmcehennemi2.biz";
var B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

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

function atobJs(value) {
  var s = String(value || "").replace(/[^A-Za-z0-9+/=]/g, "");
  var out = "";
  var i, a, b, c, d;
  for (i = 0; i < s.length; i += 4) {
    a = B64.indexOf(s.charAt(i));
    b = B64.indexOf(s.charAt(i + 1));
    c = B64.indexOf(s.charAt(i + 2));
    d = B64.indexOf(s.charAt(i + 3));
    if (a < 0 || b < 0) break;
    out += String.fromCharCode((a << 2) | (b >> 4));
    if (c > -1 && c !== 64) out += String.fromCharCode(((b & 15) << 4) | (c >> 2));
    if (d > -1 && d !== 64) out += String.fromCharCode(((c & 3) << 6) | d);
  }
  return out;
}

function abs(base, href) {
  if (!href) return null;
  if (/^https?:/i.test(href)) return href;
  if (href.indexOf("//") === 0) return "https:" + href;
  var origin = /^(https?:\/\/[^/]+)/.exec(base);
  if (href.charAt(0) === "/") return origin ? origin[1] + href : href;
  return base.replace(/[^/]*$/, "") + href;
}

/** jwplayer setup JSON is double-escaped: \u0022 for quotes, \/ for slashes. */
function unescapeJson(value) {
  return String(value || "")
    .replace(/\\u0022/g, "\"")
    .replace(/\\u0027/g, "'")
    .replace(/\\\\\//g, "/")
    .replace(/\\\//g, "/");
}

function titleOf(id, mediaType) {
  var kind = mediaType === "tv" ? "series" : "movie";
  return text("https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json")
    .then(function (body) {
      var meta = JSON.parse(body).meta || {};
      return { name: String(meta.name || "").trim(), year: String(meta.releaseInfo || meta.year || "").slice(0, 4) };
    })
    .catch(function () {
      return { name: "", year: "" };
    });
}

function norm(value) {
  return String(value || "")
    .toLowerCase()
    .replace(/ı/g, "i").replace(/ş/g, "s").replace(/ğ/g, "g")
    .replace(/ü/g, "u").replace(/ö/g, "o").replace(/ç/g, "c")
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
}

function pick(list, title, year) {
  var want = norm(title);
  var exact = [];
  var partial = [];
  for (var i = 0; i < list.length; i++) {
    var name = norm(list[i].title);
    if (name === want) exact.push(list[i]);
    else if (name && (name.indexOf(want) === 0 || want.indexOf(name) === 0)) partial.push(list[i]);
  }
  var pool = exact.length ? exact : partial;
  if (pool.length) {
    if (pool.length > 1 && year) {
      for (var k = 0; k < pool.length; k++) if (String(pool[k].releaseYear) === year) return pool[k];
    }
    return pool[0];
  }
  // Site stores Turkish titles ("Başlangıç"); trust the release year when it names one film.
  var byYear = [];
  for (var j = 0; j < list.length; j++) if (year && String(list[j].releaseYear) === year) byYear.push(list[j]);
  return byYear.length === 1 ? byYear[0] : null;
}

/** Sources listed in videoPlayerData: one entry per player (dual/dublaj/altyazi). */
function sources(page) {
  var block = first(/videoPlayerData\(JSON\.parse\('([\s\S]*?)'\)/, page);
  if (!block) return [];
  var root = null;
  try {
    root = JSON.parse(unescapeJson(block));
  } catch (e) {
    return [];
  }
  var out = [];
  for (var key in root) {
    var list = root[key];
    if (!list || !list.length) continue;
    for (var i = 0; i < list.length; i++) {
      var item = list[i] || {};
      if (!item.link || !item.template) continue;
      var frame = first(/data-src="([^"]+)/, atobJs(item.template));
      if (!frame) continue;
      out.push({
        url: abs(BASE + "/", frame.replace("{url}", item.link)),
        quality: String(item.quality || ""),
        label: String(item.service_name || item.service_slug || "player")
      });
    }
  }
  return out;
}

function getStreams(tmdbId, mediaType, season, episode) {
  var title = "";
  var pageUrl = null;

  return titleOf(tmdbId, mediaType)
    .then(function (meta) {
      title = meta.name;
      if (!title) return null;
      return text(BASE + "/api/search?q=" + encodeURIComponent(title), { headers: { Accept: "application/json" } }).then(function (body) {
        var list = [];
        try {
          list = JSON.parse(body).data || [];
        } catch (e) {
          list = [];
        }
        return pick(list, title, meta.year);
      });
    })
    .then(function (hit) {
      if (!hit || !hit.slug) return null;
      pageUrl = BASE + "/" + String(hit.slug).replace(/^\/+/, "");
      return text(pageUrl);
    })
    .then(function (page) {
      if (!page) return [];
      var found = sources(page);
      if (!found.length) return [];
      return Promise.all(
        found.map(function (src) {
          return text(src.url, { referer: pageUrl })
            .then(function (player) {
              return parsePlayer(player, src, pageUrl);
            })
            .catch(function () {
              return null;
            });
        })
      );
    })
    .then(function (rows) {
      var out = [];
      var seen = {};
      var list = rows || [];
      for (var i = 0; i < list.length; i++) {
        var row = list[i];
        if (!row || seen[row.url]) continue;
        seen[row.url] = 1;
        out.push(row);
      }
      return out;
    })
    .catch(function (e) {
      console.log("[bb-nuvio][hdfilmcehennemi2] " + (e && e.message));
      return [];
    });
}

function parsePlayer(player, src, pageUrl) {
  var raw = unescapeJson(first(/jwplayer\(['"]player['"]\)\s*\.setup\(JSON\.parse\('([\s\S]*?)'\)/, player) || "");
  var url = raw ? first(/"file"\s*:\s*"(https?:\/\/[^"]+?\.m3u8[^"]*)"/, raw) : null;
  // Vidmoly (ikinci oynataci) master'i paketli script icinde duz metin olarak tasir.
  if (!url) url = first(/(https?:\/\/[^"'\s<>\\]+?\.m3u8(?:\?[^"'\s<>\\]*)?)/, player);
  if (!url) return null;
  var subs = [];
  var re = /"file"\s*:\s*"(https?:\/\/[^"]+?\.(?:vtt|srt)[^"]*)"[^}]*?"label"\s*:\s*"([^"]*)"/g;
  var m;
  while ((m = re.exec(raw)) !== null && subs.length < 4) {
    var lang = (first(/_([a-z]{3})\.(?:vtt|srt)/i, m[1]) || "tr").toLowerCase();
    subs.push({ url: m[1], language: lang, name: (m[2] || lang.toUpperCase()) + " [HDFilmCehennemi2]" });
  }
  return {
    name: "HDFilmCehennemi2",
    title: "HDFilmCehennemi2 - " + (src.quality || "HLS") + " [" + src.label + "]",
    url: url,
    quality: src.quality || "1080p",
    headers: { "User-Agent": UA, Referer: src.url },
    subtitles: subs
  };
}

module.exports = { getStreams };
