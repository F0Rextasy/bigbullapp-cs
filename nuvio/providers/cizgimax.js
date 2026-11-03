// @provider {"id":"cizgimax","name":"ÇizgiMax","types":["tv"],"domain":"cizgimax.online","formats":["mp4","m3u8"],"sample":"tt12343534:tv:1:1","description":"ÇizgiMax anime arşivi; bölüm başına Dzen (MP4) ve dublaj kaynakları."}
/**
 * cizgimax — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /api/search/suggest/ -> series -> episode -> base64 servers blob.
 * A suggestion miss retries the title Wikidata holds for that IMDb id (Cinemeta ships the
 * English one) before giving up.
 * Sources: tau-video renditions (resolveUrl), the site's signed /api/stream relay
 * (streamUrl), and third-party embeds behind /oynat/ (src), whose direct media URL is
 * read out of the embed page. ytId rows are skipped: YouTube needs a signature
 * extractor the sandbox cannot host.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://cizgimax.online";
var SIBNET = "https://video.sibnet.ru";

/* One fetch path; embed hops need the final URL as their own referer. */
function pageOf(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
  if (o.headers) for (var k in o.headers) headers[k] = o.headers[k];
  return fetch(url, { method: o.method || "GET", headers: headers, body: o.body })
    .then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status + " " + url);
      return r.text().then(function (html) {
        return { html: html, url: r.url || url };
      });
    });
}

function text(url, opts) {
  return pageOf(url, opts).then(function (page) {
    return page.html;
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

/* Cinemeta carries the English name; its imdb_id is the one the flow keeps. */
function metaOf(imdb, mediaType) {
  var kind = mediaType === "tv" ? "series" : "movie";
  return text("https://v3-cinemeta.strem.io/meta/" + kind + "/" + imdb + ".json")
    .then(function (body) {
      var meta = JSON.parse(body).meta || {};
      return { name: String(meta.name || "").trim(), imdb: String(meta.imdb_id || imdb) };
    })
    .catch(function () {
      return { name: "", imdb: imdb };
    });
}

function titleOf(id, mediaType) {
  var raw = String(id || "");
  /* Nuvio's home rows send TMDB ids; numericMeta bridges them to a title + IMDb id. */
  if (!/^tt\d+$/.test(raw)) {
    return numericMeta(raw, mediaType).then(function (found) {
      return { name: found.name || "", imdb: found.imdb || "" };
    });
  }
  return metaOf(raw, mediaType);
}

/* Cinemeta names are English: "Miraculous: Tales of Ladybug & Cat Noir" only finds
   the site's card once the subtitle is dropped. */
function variants(title) {
  var name = String(title || "").trim();
  var out = [];
  if (name) out.push(name);
  var head = name.split(/\s*[:\u2013\u2014]\s*|\s+-\s+/)[0].trim();
  if (head && head !== name) out.push(head);
  return out;
}

/* Turkish + ASCII fold so "Kral Şakir" and "kral sakir" compare equal. */
function norm(value) {
  return String(value || "").toLowerCase()
    .replace(/[ıİ]/g, "i").replace(/[şŞ]/g, "s").replace(/[ğĞ]/g, "g")
    .replace(/[üÜ]/g, "u").replace(/[öÖ]/g, "o").replace(/[çÇ]/g, "c")
    .replace(/[^a-z0-9]+/g, " ")
    .replace(/^\s+|\s+$/g, "");
}

/* The card's URL slug is the English title even when the site renames the display name
   (Miraculous -> "Mucize Uğur Böceği ile Kara Kedi"), so both form the match key. */
function searchSite(query) {
  if (!query) return Promise.resolve(null);
  return text(BASE + "/api/search/suggest/?q=" + encodeURIComponent(query))
    .then(function (body) {
      var animes = (JSON.parse(body) || {}).animes || [];
      var want = norm(query);
      var firstUrl = null, exact = null, prefix = null;
      for (var i = 0; i < animes.length; i++) {
        var url = animes[i] && animes[i].url;
        if (!url) continue;
        if (!firstUrl) firstUrl = url;
        var keys = [norm(animes[i].name), norm(url.replace(/^.*\/diziler\//, "").replace(/-izle\/?$/, ""))];
        if (keys.indexOf(want) >= 0) exact = exact || url;
        else if (!prefix && keys.some(function (k) { return k && k.indexOf(want) === 0; })) prefix = url;
        else if (!prefix && keys.some(function (k) { return k && k.split(/\s+/).indexOf(want) >= 0; })) prefix = url;
      }
      /* A lone suggestion is the site's own answer; a list is only picked by name. */
      return exact || prefix || (animes.length === 1 ? firstUrl : null);
    });
}

/* Wikidata links the IMDb id to the title the site indexes (tr label first). */
function nativeTitle(imdb) {
  if (!/^tt\d+$/.test(String(imdb || ""))) return Promise.resolve("");
  var api = "https://www.wikidata.org/w/api.php?format=json";
  return text(api + "&action=query&list=search&srlimit=1&srsearch=" +
      encodeURIComponent("haswbstatement:P345=" + imdb))
    .then(function (body) {
      var hits = (JSON.parse(body).query || {}).search || [];
      if (!hits.length) return "";
      return text(api + "&action=wbgetentities&props=labels&languages=tr|en&ids=" + hits[0].title);
    })
    .then(function (body) {
      if (!body) return "";
      var entities = JSON.parse(body).entities || {};
      for (var id in entities) {
        var labels = entities[id].labels || {};
        var label = (labels.tr || labels.en || {}).value;
        if (label) return String(label).replace(/\s*\([^)]*\)\s*$/, "").trim();
      }
      return "";
    });
}
/* Nuvio's home rows send TMDB ids, not IMDb ids. Wikidata links the two: one entity
   fetch yields the localized title the site indexes plus the IMDb id the native-title
   fallback needs, so the numeric path costs the same two requests as before. */
function numericMeta(id, mediaType) {
  if (!/^\d+$/.test(String(id || ""))) return Promise.resolve({});
  var prop = mediaType === "movie" ? "P4947" : "P4983";
  var api = "https://www.wikidata.org/w/api.php?format=json";
  return text(api + "&action=query&list=search&srlimit=1&srsearch=" +
      encodeURIComponent("haswbstatement:" + prop + "=" + id))
    .then(function (body) {
      var hits = (JSON.parse(body).query || {}).search || [];
      if (!hits.length) return "";
      return text(api + "&action=wbgetentities&props=claims%7Clabels&languages=tr%7Cen&ids=" + hits[0].title);
    })
    .then(function (body) {
      if (!body) return {};
      var entities = JSON.parse(body).entities || {};
      for (var key in entities) {
        var entity = entities[key] || {};
        var label = ((entity.labels || {}).tr || (entity.labels || {}).en || {}).value;
        var claims = (entity.claims || {}).P345 || [];
        var imdb = "";
        for (var i = 0; i < claims.length; i++) {
          var value = ((claims[i] || {}).mainsnak || {}).datavalue;
          if (value && /^tt\d+$/.test(String(value.value || ""))) {
            imdb = String(value.value);
            break;
          }
        }
        return {
          name: label ? String(label).replace(/\s*\([^)]*\)\s*$/, "").trim() : "",
          imdb: imdb
        };
      }
      return {};
    })
    .catch(function () {
      return {};
    });
}

/* First non-empty answer wins; a dead candidate must not stop the next one. */
function firstHit(list, fn) {
  function step(i) {
    if (i >= list.length) return Promise.resolve(null);
    return Promise.resolve()
      .then(function () {
        return fn(list[i]);
      })
      .catch(function () {
        return null;
      })
      .then(function (value) {
        return value || step(i + 1);
      });
  }
  return step(0);
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

function row(label, url, headers, hls) {
  if (hls === undefined) hls = /\.m3u8/i.test(url);
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

/* Embed pages carry the direct media in one of three shapes: sibnet's player.src
   call, a jwplayer `sources:[{file:…}]` block, or ok.ru's "videos" array. */
function media(embedUrl, episodeUrl, label) {
  return pageOf(embedUrl, { referer: episodeUrl }).then(function (page) {
    var rows = [];
    var seen = {};

    function add(url, referer, name) {
      if (!url || seen[url] || !/^https?:/i.test(url)) return;
      seen[url] = 1;
      rows.push(row(String(name || "").trim() || label, url, { Referer: referer }, /\.m3u8/i.test(url)));
    }

    var sib = first(/player\.src\(\[\{src:\s*"([^"]+)"/, page.html);
    if (sib) add(/^https?:/i.test(sib) ? sib : SIBNET + sib, page.url, "");

    var files = /(?:file|src)\s*:\s*['"]([^'"]*\.(?:m3u8|mp4)[^'"]*)['"]/g;
    var m;
    while ((m = files.exec(page.html)) !== null) add(m[1], page.url, "");

    var vids = first(/"videos"\s*:\s*(\[[^\]]*\])/, page.html);
    if (vids) {
      try {
        JSON.parse(vids).forEach(function (v) {
          var url = String((v && v.url) || "");
          add(url.indexOf("//") === 0 ? "https:" + url : url, "https://ok.ru/", label + " " + ((v && v.name) || ""));
        });
      } catch (e) {
        /* an unreadable videos array just yields no rows */
      }
    }
    return rows;
  });
}

function serverRows(server, episodeUrl) {
  var label = server.label || server.type || "Kaynak";
  if (server.resolveUrl) return dzen(server, episodeUrl);
  /* The site proxy signs /api/stream/... and 403s without the episode as Referer. */
  if (server.streamUrl) {
    var url = abs(episodeUrl, server.streamUrl);
    if (!url) return Promise.resolve([]);
    /* Everything but mailru is the site's HLS relay; mailru (and mp4=1) are MP4. */
    var mp4 = server.type === "mailru" || /mp4=1/.test(server.streamUrl);
    return Promise.resolve([row(label, url, { Referer: episodeUrl }, !mp4)]);
  }
  /* src rows leave the site for third-party embeds (sibnet, vidmoly, ok.ru, ...). */
  if (server.src) {
    var embed = abs(episodeUrl, server.src);
    return embed ? media(embed, episodeUrl, label) : Promise.resolve([]);
  }
  /* ytId rows would need YouTube signature extraction; emit nothing rather than a
     watch URL no player here can open. */
  return Promise.resolve([]);
}

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var seriesUrl = null;
  var episodeUrl = null;

  return titleOf(tmdbId, mediaType)
    .then(function (meta) {
      return firstHit(variants(meta.name), searchSite).then(function (href) {
        if (href || !meta.imdb) return href;
        return nativeTitle(meta.imdb).then(function (title) {
          return firstHit(variants(title), searchSite);
        });
      });
    })
    .then(function (href) {
      if (!href) return null;
      seriesUrl = abs(BASE, href);
      return seriesUrl ? text(seriesUrl) : null;
    })
    .then(function (detail) {
      if (!detail) return null;
      var links = [];
      var re = /href="([^"]*-sezon-\d+-bolum-izle\/)"/g;
      var m;
      while ((m = re.exec(detail)) !== null) links.push(m[1]);
      var exact = "-" + want + "-sezon-" + wantEp + "-bolum-izle/";
      var pick = null;
      for (var i = 0; i < links.length; i++) {
        if (links[i].indexOf(exact) !== -1) {
          pick = links[i];
          break;
        }
      }
      /* A different season/episode is a miss, not a reason to play a neighbour. */
      return pick ? abs(seriesUrl, pick) : null;
    })
    .then(function (target) {
      if (!target) return null;
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
