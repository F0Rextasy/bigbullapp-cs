// @provider {"id":"cizgimax","name":"ÇizgiMax","types":["tv"],"domain":"cizgimax.online","formats":["mp4","m3u8"],"sample":"tt12343534:tv:1:1","description":"ÇizgiMax anime arşivi; bölüm başına Dzen (MP4) ve dublaj kaynakları."}
/**
 * cizgimax — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /ara/ -> series -> episode -> base64 servers blob -> tau-video renditions.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://cizgimax.online";

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

/* atob is absent in the Nuvio sandbox: base64 -> bytes -> UTF-8 by hand. */
function b64(value) {
  var abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  var bytes = "", buf = 0, bits = 0, i, v;
  value = String(value || "").replace(/[^A-Za-z0-9+/=]/g, "");
  for (i = 0; i < value.length; i++) {
    v = abc.indexOf(value.charAt(i));
    if (v < 0) break;
    buf = (buf << 6) | v;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      bytes += String.fromCharCode((buf >> bits) & 255);
    }
  }
  return utf8(bytes);
}

function utf8(s) {
  var out = "", i = 0, c, b2, b3, b4, cp;
  while (i < s.length) {
    c = s.charCodeAt(i++);
    if (c < 128) out += String.fromCharCode(c);
    else if (c < 224) out += String.fromCharCode(((c & 31) << 6) | (s.charCodeAt(i++) & 63));
    else if (c < 240) {
      b2 = s.charCodeAt(i++) & 63;
      b3 = s.charCodeAt(i++) & 63;
      out += String.fromCharCode(((c & 15) << 12) | (b2 << 6) | b3);
    } else {
      b2 = s.charCodeAt(i++) & 63;
      b3 = s.charCodeAt(i++) & 63;
      b4 = s.charCodeAt(i++) & 63;
      cp = (((c & 7) << 18) | (b2 << 12) | (b3 << 6) | b4) - 65536;
      out += String.fromCharCode(55296 + (cp >> 10), 56320 + (cp & 1023));
    }
  }
  return out;
}

function quality(label) {
  var m = /(\d{3,4})\s*p/i.exec(String(label || ""));
  return m ? m[1] + "p" : "auto";
}

function row(label, url, headers) {
  var hls = /\.m3u8/i.test(url);
  return {
    name: "cizgimax",
    title: "cizgimax - " + label + " [" + (hls ? "HLS" : "MP4") + "]",
    url: url,
    quality: quality(label),
    headers: headers,
    subtitles: []
  };
}

/* Runs the servers one by one: a dead embed must not take the live ones down. */
function chain(list, fn) {
  var out = [];
  function step(i) {
    if (i >= list.length) return Promise.resolve(out);
    return Promise.resolve()
      .then(function () {
        return fn(list[i]);
      })
      .catch(function () {
        return [];
      })
      .then(function (rows) {
        if (rows && rows.length) out = out.concat(rows);
        return step(i + 1);
      });
  }
  return step(0);
}

function dzen(server, episodeUrl) {
  var resolve = /^https?:/i.test(server.resolveUrl) ? server.resolveUrl : BASE + server.resolveUrl;
  return text(resolve, { referer: episodeUrl })
    .then(function (body) {
      var key = JSON.parse(body).id;
      if (!key) return [];
      return text("https://tau-video.xyz/api/video/" + key, { referer: "https://tau-video.xyz/" });
    })
    .then(function (body) {
      var urls = JSON.parse(body).urls || [];
      return urls
        .filter(function (u) {
          return u && /^https?:/i.test(u.url || "");
        })
        .map(function (u) {
          return row((server.label || "Dzen") + " " + (u.label || ""), u.url, { Referer: episodeUrl });
        });
    });
}

function serverRows(server, episodeUrl) {
  var label = server.label || server.type || "Kaynak";
  if (server.resolveUrl) return dzen(server, episodeUrl);
  /* The site proxy signs /api/stream/... and 403s without the episode as Referer. */
  if (server.streamUrl) {
    var url = abs(episodeUrl, server.streamUrl);
    if (!url) return Promise.resolve([]);
    return Promise.resolve([row(label, url, { Referer: episodeUrl })]);
  }
  /* src rows leave the site for third-party embeds (sibnet, vidmoly, ...); no direct media. */
  return Promise.resolve([]);
}

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var seriesUrl = null;
  var episodeUrl = null;

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return null;
      return text(BASE + "/ara/?q=" + encodeURIComponent(title) + "&page=1");
    })
    .then(function (search) {
      if (!search) return null;
      var href =
        first(/<a href="([^"]*\/diziler\/[^"]*)"[^>]*class="[^"]*film-name/, search) ||
        first(/<a [^>]*class="[^"]*film-name[^"]*"[^>]*href="([^"]+)"/, search) ||
        first(/<a href="([^"]*\/diziler\/[^"]*)"/, search);
      if (!href) return null;
      seriesUrl = abs(BASE, href);
      return text(seriesUrl);
    })
    .then(function (detail) {
      if (!detail) return [];
      var links = [];
      var re = /href="([^"]*-sezon-\d+-bolum-izle\/)"/g;
      var m;
      while ((m = re.exec(detail)) !== null) links.push(m[1]);
      if (!links.length) return [];
      var exact = "-" + want + "-sezon-" + wantEp + "-bolum-izle/";
      for (var i = 0; i < links.length; i++) {
        if (links[i].indexOf(exact) !== -1) return abs(seriesUrl, links[i]);
      }
      return abs(seriesUrl, links[0]);
    })
    .then(function (target) {
      if (!target) return [];
      episodeUrl = target;
      return text(episodeUrl);
    })
    .then(function (page) {
      if (!page) return [];
      var encoded = first(/var servers\s*=\s*JSON\.parse\(atob\("([^"]+)"\)\)/, page);
      if (!encoded) return [];
      var servers = JSON.parse(b64(encoded.replace(/\\/g, ""))) || [];
      return chain(servers, function (server) {
        return serverRows(server || {}, episodeUrl);
      });
    })
    .then(function (rows) {
      var seen = {};
      return rows.filter(function (r) {
        if (!r.url || seen[r.url]) return false;
        seen[r.url] = 1;
        return true;
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][cizgimax] " + (e && e.message));
      return [];
    });
}

module.exports = { getStreams };
