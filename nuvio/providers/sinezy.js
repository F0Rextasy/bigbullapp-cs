// @provider {"id":"sinezy","name":"Sinezy","types":["movie"],"domain":"sinezy.si","formats":["m3u8"],"sample":"tt1577061:movie:1:1","description":"Sinezy film arsivi; oynat\u0131c\u0131dan HLS master + kalite kademeleri."}
/**
 * sinezy — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> ajax search -> film page -> base64 iframe -> playlist JSON -> HLS.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://sinezy.si";
var ABC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
  if (o.origin) headers.Origin = o.origin;
  if (o.headers) for (var k in o.headers) headers[k] = o.headers[k];
  return fetch(url, { method: o.method || "GET", headers: headers, body: o.body, redirect: "follow" })
    .then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status + " " + url);
      return r.text().then(function (body) { return { body: body, url: r.url }; });
    });
}

function first(re, body) {
  var m = re.exec(String(body || ""));
  return m ? m[1] : null;
}

function abs(base, href) {
  if (!href) return null;
  if (/^https?:/i.test(href)) return href;
  if (href.slice(0, 2) === "//") return "https:" + href;
  var origin = /^(https?:\/\/[^/]+)/.exec(base);
  if (href.charAt(0) === "/") return origin ? origin[1] + href : href;
  return base.replace(/[^/]*$/, "") + href;
}

function titleOf(id, mediaType) {
  var kind = mediaType === "tv" ? "series" : "movie";
  return text("https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json")
    .then(function (r) {
      var meta = JSON.parse(r.body).meta || {};
      return String(meta.name || "").trim();
    })
    .catch(function () {
      return "";
    });
}

// Turkish letters folded so "Recep \u0130vedik" and "recep-ivedik" compare equal.
function norm(value) {
  return String(value || "").toLowerCase()
    .replace(/[\u0131\u0130]/g, "i").replace(/[\u015f\u015e]/g, "s").replace(/[\u011f\u011e]/g, "g")
    .replace(/[\u00fc\u00dc]/g, "u").replace(/[\u00f6\u00d6]/g, "o").replace(/[\u00e7\u00c7]/g, "c")
    .replace(/[\u00e2]/g, "a").replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "");
}

// pairs are [name, url]; search hits that contain the wanted title still count, exact wins.
function bestMatch(pairs, title) {
  var want = norm(title), best = null, bestScore = 0;
  for (var i = 0; i < pairs.length; i++) {
    var value = norm(pairs[i][0]);
    var score = 0;
    if (value && value === want) score = 3;
    else if (value && want && (value.indexOf(want) === 0 || want.indexOf(value) === 0)) score = 2;
    else if (value && want && (value.indexOf(want) > -1 || want.indexOf(value) > -1)) score = 1;
    if (score > bestScore) {
      bestScore = score;
      best = pairs[i][1];
    }
  }
  return best;
}

function b64(value) {
  var s = String(value || "").replace(/[^A-Za-z0-9+/=]/g, "");
  var out = "", acc = 0, bits = 0;
  for (var i = 0; i < s.length; i++) {
    var ch = s.charAt(i);
    if (ch === "=") break;
    var v = ABC.indexOf(ch);
    if (v < 0) continue;
    acc = (acc << 6) | v;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out += String.fromCharCode((acc >> bits) & 0xff);
    }
  }
  return out;
}

function variants(master, playlist) {
  var origin = /^(https?:\/\/[^/]+)/.exec(master)[1];
  var base = master.replace(/[?#].*$/, "").replace(/[^/]*$/, "");
  var out = [], height = 0, lines = String(playlist || "").split("\n");
  for (var i = 0; i < lines.length; i++) {
    var line = lines[i].trim();
    if (line.indexOf("#EXT-X-STREAM-INF") === 0) {
      height = parseInt(first(/RESOLUTION=\d+x(\d+)/, line), 10) || 0;
    } else if (line && line.charAt(0) !== "#" && height > 0) {
      out.push({ quality: height + "p", url: /^https?:/i.test(line) ? line : line.charAt(0) === "/" ? origin + line : base + line });
      height = 0;
    }
  }
  return out;
}

function getStreams(tmdbId, mediaType, season, episode) {
  var page = null;
  var embed = null;
  var wanted = "";
  var ref = "";

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      wanted = title;
      if (!title) return null;
      return text(BASE + "/arama/", {
        method: "POST",
        body: "action=ajax_search&arama_kelime=" + encodeURIComponent(title),
        referer: BASE + "/arama/",
        origin: BASE,
        headers: { "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8", "X-Requested-With": "XMLHttpRequest" }
      });
    })
    .then(function (res) {
      if (!res) return null;
      var pairs = [], re = /<a title="([^"]*)" href="([^"]+)"/g, m;
      while ((m = re.exec(res.body)) !== null) pairs.push([m[1], m[2]]);
      var hit = bestMatch(pairs, wanted);
      if (!hit) return null;
      page = abs(BASE, hit);
      return text(page);
    })
    .then(function (res) {
      if (!res) return [];
      var encoded = first(/var\s+ilkpartkod\s*=\s*'([^']+)'/, res.body);
      if (!encoded) return [];
      var iframe = first(/src=["']([^"']+)["']/, b64(encoded));
      if (!iframe) return [];
      embed = abs(page, iframe);
      return text(embed, { referer: page });
    })
    .then(function (res) {
      if (!res) return [];
      var id = embed.replace(/[?#].*$/, "").split("/").pop();
      // The filmizle host is case-sensitive on its vhost: "Player." answers 404.
      var origin = /^(https?:\/\/[^/]+)/.exec(embed)[1].toLowerCase();
      // The host check is case-sensitive too: a "Player." referer is rejected with 404.
      ref = origin + embed.replace(/^https?:\/\/[^/]+/, "");
      var declared = first(/playlistUrl\s*=\s*'([^']+)'/, res.body);
      // Two player families: JW/playlist JSON (videoseyred) and FirePlayer's getVideo POST.
      function firePlayer() {
        var api = origin + "/player/index.php?data=" + encodeURIComponent(id) + "&do=getVideo";
        return text(api, {
          method: "POST",
          body: "hash=" + encodeURIComponent(id) + "&r=" + encodeURIComponent(BASE + "/"),
          referer: ref,
          origin: origin,
          headers: { "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8", "X-Requested-With": "XMLHttpRequest" }
        }).then(function (r) {
          var json = JSON.parse(r.body), out = [];
          ["securedLink", "videoSource"].forEach(function (key) {
            if (typeof json[key] === "string" && /^https?:/i.test(json[key])) out.push(json[key]);
          });
          return out;
        });
      }
      if (!declared && !/\/embed\//.test(embed)) return firePlayer();
      var listUrl = declared ? abs(embed, declared) : origin + "/playlist/" + id + ".json";
      return text(listUrl, { referer: ref }).then(function (r) {
        var list = JSON.parse(r.body) || [];
        var streams = [];
        ((list[0] || {}).sources || []).forEach(function (s) {
          if (s && typeof s.file === "string" && /^https?:/i.test(s.file)) streams.push(s.file);
        });
        return streams.length ? streams : firePlayer();
      }).catch(firePlayer);
    })
    .then(function (streams) {
      var playable = streams.filter(function (u) { return /\.m3u8/i.test(u); });
      var target = playable.length ? playable[0] : streams[0];
      return text(target, { referer: ref }).then(function (r) {
        var list = variants(target, r.body);
        if (!list.length) list = [{ quality: "", url: target }];
        return list.map(function (v) {
          return {
            name: "sinezy",
            title: "sinezy" + (v.quality ? " " + v.quality : "") + " [HLS]",
            url: v.url,
            quality: v.quality,
            headers: { Referer: ref },
            subtitles: []
          };
        });
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][sinezy] " + (e && e.message));
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