// @provider {"id":"hdfilmizle","name":"HDFilmizle","types":["movie"],"domain":"hdfilmizle.ink","formats":["m3u8"],"description":"HDFilmizle - Full HD film arsivi; SetPlay/FastPlay oynataci zinciri (HLS + Turkce altyazi).","sample":"tt0133093:movie:0:0"}
/**
 * hdfilmizle — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /?s= search -> film page nonce + players -> admin-ajax get_video_url
 *       -> bridge page SPG.cerceve XOR(base64) -> fastplay player (stream path + tracks)
 *       -> HLS master read with the mobile UA (desktop identity gets 404).
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
var MOBILE_UA = "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";
var BASE = "https://www.hdfilmizle.ink";
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

function all(pattern, body) {
  var out = [];
  var re = new RegExp(pattern.source, pattern.flags.indexOf("g") < 0 ? pattern.flags + "g" : pattern.flags);
  var m;
  while ((m = re.exec(String(body || ""))) !== null) {
    out.push(m);
    if (m[0] === "") re.lastIndex++;
  }
  return out;
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

function origin(url) {
  var m = /^(https?:\/\/[^/]+)/.exec(String(url || ""));
  return m ? m[1] : BASE;
}

function form(fields) {
  var out = [];
  for (var k in fields) out.push(encodeURIComponent(k) + "=" + encodeURIComponent(fields[k]));
  return out.join("&");
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
    .replace(/^the\s+/, "")
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
}

/** Each `<article class="item …">` card: href + <h2> title + span.anayil year. */
function cards(page, want, year) {
  var blocks = all(/<article[\s\S]*?<\/article>/i, page);
  var out = [];
  for (var i = 0; i < blocks.length; i++) {
    var block = blocks[i][0];
    var href = first(/href="(https?:\/\/(?:www\.)?hdfilmizle\.[a-z]+\/(?:film|dizi)\/[^"]+)"/, block);
    if (!href) continue;
    var title = first(/<h2>([^<]+)<\/h2>/, block) || first(/alt="([^"(]+)/, block);
    out.push({ url: href, title: String(title || "").trim(), year: first(/class="anayil">(\d{4})</, block) });
  }
  if (!want) return out;
  var exact = [];
  var partial = [];
  for (var j = 0; j < out.length; j++) {
    var name = norm(out[j].title);
    if (name === want) exact.push(out[j]);
    else if (name && (name.indexOf(want) === 0 || want.indexOf(name) === 0)) partial.push(out[j]);
  }
  var pool = exact.length ? exact : partial;
  if (pool.length) {
    if (pool.length > 1 && year) {
      for (var k = 0; k < pool.length; k++) if (pool[k].year === year) return [pool[k]];
    }
    return [pool[0]];
  }
  // Site stores Turkish titles ("Başlangıç"); trust the release year when it names one card.
  var byYear = [];
  for (var m = 0; m < out.length; m++) if (year && out[m].year === year) byYear.push(out[m]);
  return byYear.length === 1 ? [byYear[0]] : [];
}

function getStreams(tmdbId, mediaType, season, episode) {
  var title = "";
  var filmUrl = null;

  return titleOf(tmdbId, mediaType)
    .then(function (meta) {
      title = meta.name;
      if (!title) return null;
      return text(BASE + "/?s=" + encodeURIComponent(title)).then(function (search) {
        return cards(search, norm(title), meta.year)[0] || null;
      });
    })
    .then(function (hit) {
      if (!hit) return null;
      filmUrl = hit.url;
      return text(filmUrl);
    })
    .then(function (page) {
      if (!page) return [];
      var nonce = first(/id="playex"[^>]*data-nonce="([^"]+)"/, page) || first(/"nonce"\s*:\s*"([^"]+)"/, page);
      if (!nonce) return [];
      var players = [];
      var tags = all(/<a[^>]*class="[^"]*\boptions\b[^"]*"[^>]*>/g, page);
      for (var i = 0; i < tags.length; i++) {
        var tag = tags[i][0];
        var postId = first(/data-post-id="([^"]+)"/, tag);
        if (!postId) continue;
        players.push({
          postId: postId,
          name: first(/data-player-name="([^"]*)"/, tag) || "SetPlay",
          partKey: first(/data-part-key="([^"]*)"/, tag) || ""
        });
      }
      return Promise.all(
        players.map(function (p) {
          return requestBridge(filmUrl, nonce, p)
            .then(function (bridge) {
              return bridge ? resolveBridge(bridge, filmUrl).then(function (player) {
                return player ? readPlayer(player, p) : null;
              }) : null;
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
        if (!list[i] || seen[list[i].url]) continue;
        seen[list[i].url] = 1;
        out.push(list[i]);
      }
      return out;
    })
    .catch(function (e) {
      console.log("[bb-nuvio][hdfilmizle] " + (e && e.message));
      return [];
    });
}

function requestBridge(filmUrl, nonce, player) {
  return text(BASE + "/wp-admin/admin-ajax.php", {
    method: "POST",
    referer: filmUrl,
    headers: { "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8", "X-Requested-With": "XMLHttpRequest" },
    body: form({ action: "get_video_url", nonce: nonce, post_id: player.postId, player_name: player.name, part_key: player.partKey })
  }).then(function (body) {
    var url = null;
    try {
      url = ((JSON.parse(body) || {}).data || {}).url;
    } catch (e) {}
    return url && /^https?:/i.test(url) ? url : null;
  });
}

/** Bridge hides the real player in two base64 strings XOR-ed byte by byte. */
function resolveBridge(bridgeUrl, referer) {
  return text(bridgeUrl, { referer: referer }).then(function (html) {
    var m = /cerceve\(\s*"b2"\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\)/.exec(html);
    if (!m) return null;
    var one = atobJs(m[1]);
    var two = atobJs(m[2]);
    if (!one.length || !two.length) return null;
    var decoded = "";
    for (var i = 0; i < one.length; i++) {
      decoded += String.fromCharCode(one.charCodeAt(i) ^ two.charCodeAt(i % two.length));
    }
    var parts = decoded.split("|");
    for (var k = 0; k < parts.length; k++) {
      if (/^https?:/i.test(parts[k])) return parts[k];
    }
    return null;
  });
}

function readPlayer(playerUrl, player) {
  return text(playerUrl, { referer: origin(playerUrl) + "/" }).then(function (html) {
    var path = first(/stream:\s*"([^"]+)"/, html);
    if (!path) return null;
    var master = origin(playerUrl) + path.replace(/\\\//g, "/");
    var subs = [];
    var tracks = first(/tracks:\s*(\[[\s\S]*?])\s*,/, html);
    if (tracks) {
      var re = /"file"\s*:\s*"([^"]+\.(?:vtt|srt)[^"]*)"[^}]*?"label"\s*:\s*"([^"]*)"[^}]*?"lang"\s*:\s*"([^"]*)"/g;
      var m;
      while ((m = re.exec(tracks)) !== null && subs.length < 4) {
        subs.push({ url: m[1].replace(/\\\//g, "/"), language: (m[3] || "tr").toLowerCase(), name: (m[2] || "altyazi") + " [HDFilmizle]" });
      }
    }
    return readQuality(master, playerUrl).then(function (quality) {
      return {
        name: "HDFilmizle",
        title: "HDFilmizle - " + quality + " [" + (player.name || "SetPlay") + "]",
        url: master,
        quality: quality,
        headers: { "User-Agent": MOBILE_UA, Accept: "*/*", Referer: playerUrl },
        subtitles: subs
      };
    });
  });
}

function readQuality(masterUrl, playerUrl) {
  return fetch(masterUrl, { headers: { "User-Agent": MOBILE_UA, Accept: "*/*", Referer: playerUrl } })
    .then(function (r) {
      return r.ok ? r.text() : "";
    })
    .then(function (body) {
      var best = 0;
      var matches = all(/RESOLUTION=\d+x(\d+)/, body);
      for (var i = 0; i < matches.length; i++) {
        var h = parseInt(matches[i][1], 10);
        if (h > best) best = h;
      }
      return best ? best + "p" : "1080p";
    })
    .catch(function () {
      return "1080p";
    });
}

module.exports = { getStreams };
