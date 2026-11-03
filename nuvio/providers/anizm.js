// @provider {"id":"anizm","name":"Anizm","types":["tv"],"domain":"turkanime.info","formats":["m3u8"],"sample":"tt12343534:tv:1:1","description":"Anizm (turkanime.info) Turkce altyazili anime; kendi oynatcisindan HLS master."}
/**
 * anizm — Nuvio provider (bb-nuvio).
 * Flow: title -> /arama -> anime detail -> episode -> cevirmen (translator) JSON
 *       -> /player/{id} redirect -> anizmplayer getVideo (videoSource) | puffytr native.m3u8.
 * Each /player probe costs a 302 plus a POST, so mirrors are probed in small parallel
 * batches and the walk stops as soon as MAX_LINKS answer (the site's own player is the
 * first mirror, usually the only request that has to finish).
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
var BASE = "https://turkanime.info";

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

function follow(url, referer) {
  return fetch(url, {
    method: "GET",
    headers: { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9", Referer: referer }
  }).then(function (r) {
    return r.url || url;
  });
}

function first(pattern, body) {
  var m = pattern.exec(String(body || ""));
  return m ? m[1] : null;
}

function all(pattern, body) {
  var out = [];
  var re = new RegExp(pattern.source, pattern.flags.indexOf("g") >= 0 ? pattern.flags : pattern.flags + "g");
  var m;
  while ((m = re.exec(String(body || ""))) !== null) out.push(m);
  return out;
}

function abs(base, href) {
  if (!href) return null;
  if (/^https?:/i.test(href)) return href;
  var origin = /^(https?:\/\/[^/]+)/.exec(base);
  if (href.indexOf("//") === 0) return "https:" + href;
  if (href.charAt(0) === "/") return origin ? origin[1] + href : href;
  return base.replace(/[^/]*$/, "") + href;
}

/* Nuvio's own rows send a numeric TMDB id; Wikidata links it to the site-searchable label. */
function wikidataTitle(id, mediaType) {
  var prop = mediaType === "movie" ? "P4947" : "P4983";
  var api = "https://www.wikidata.org/w/api.php?format=json";
  return text(api + "&action=query&list=search&srlimit=1&srsearch=" +
      encodeURIComponent("haswbstatement:" + prop + "=" + id))
    .then(function (body) {
      var hits = (JSON.parse(body).query || {}).search || [];
      if (!hits.length) return "";
      return text(api + "&action=wbgetentities&props=labels&languages=tr|en&ids=" + hits[0].title);
    })
    .then(function (body) {
      if (!body) return "";
      var entities = JSON.parse(body).entities || {};
      for (var key in entities) {
        var labels = entities[key].labels || {};
        var label = (labels.tr || labels.en || {}).value;
        if (label) return String(label).replace(/\s*\([^)]*\)\s*$/, "").trim();
      }
      return "";
    })
    .catch(function () {
      return "";
    });
}

function titleOf(id, mediaType) {
  if (/^\d+$/.test(String(id || ""))) return wikidataTitle(id, mediaType);
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

function norm(value) {
  return String(value || "")
    .toLowerCase()
    .replace(/ı/g, "i").replace(/ş/g, "s").replace(/ğ/g, "g")
    .replace(/ü/g, "u").replace(/ö/g, "o").replace(/ç/g, "c")
    .replace(/[^a-z0-9]+/g, "");
}

/** Pick the card whose title matches the wanted title best; fall back to the first card. */
function pickCard(body, want) {
  var cards = all(/<article class="v3-catalog-card">([\s\S]*?)<\/article>/, body);
  var wantKey = norm(want);
  var fallback = null;
  for (var i = 0; i < cards.length; i++) {
    var html = cards[i][1];
    var href = first(/<a class="v3-catalog-poster" href="([^"]+)"/, html);
    if (!href) href = first(/<a[^>]*href="([^"]+)"[^>]*class="[^"]*v3-catalog-poster/, html);
    if (!href) continue;
    var name = first(/<h[23][^>]*>([^<]+)<\/h[23]>/, html) || first(/aria-label="([^"]+) anime detay/, html) || "";
    if (!fallback) fallback = href;
    if (wantKey && norm(name) === wantKey) return href;
    if (wantKey && norm(name).indexOf(wantKey) !== -1) fallback = href;
  }
  return fallback;
}

/** Episode anchors carry data-episode-number; pick the requested one, else nearest by index. */
function pickEpisode(body, want) {
  var links = all(/<a href="([^"#]+)"[^>]*data-episode-number="(\d+)"/, body);
  if (!links.length) links = all(/data-episode-number="(\d+)"[^>]*href="([^"#]+)"/, body);
  if (!links.length) return null;
  var i;
  for (i = 0; i < links.length; i++) {
    if (parseInt(links[i][2] || links[i][1], 10) === want) return links[i][1] || links[i][2];
  }
  var idx = Math.min(Math.max(want - 1, 0), links.length - 1);
  return links[idx][1] || links[idx][2];
}

function playerOf(embed, referer) {
  if (embed.indexOf("anizmplayer.com") !== -1) return anizmPlayer(embed, referer);
  if (embed.indexOf("puffytr") !== -1) return puffytr(embed);
  return Promise.resolve(null);
}

function anizmPlayer(embed, referer) {
  var hash = embed.substring(embed.lastIndexOf("/") + 1);
  // getVideo answers HTML unless the request looks like the site's own ajax call.
  var origin = first(/^(https?:\/\/[^/]+)/, embed);
  if (!hash || !origin) return Promise.resolve(null);
  var body = "hash=" + encodeURIComponent(hash) + "&r=" + encodeURIComponent(referer);
  return text(origin + "/player/index.php?data=" + encodeURIComponent(hash) + "&do=getVideo", {
    method: "POST",
    referer: embed,
    headers: {
      "X-Requested-With": "XMLHttpRequest",
      Origin: origin,
      "Content-Type": "application/x-www-form-urlencoded"
    },
    body: body
  })
    .then(function (raw) {
      var src = String(JSON.parse(raw).videoSource || "").trim();
      return /^https?:/i.test(src) ? { url: src, referer: origin + "/" } : null;
    })
    .catch(function () {
      return null;
    });
}

function puffytr(embed) {
  var origin = embed.substring(0, embed.indexOf("/watch/"));
  var slug = embed.substring(embed.lastIndexOf("/") + 1);
  if (!slug || !origin || origin === embed) return Promise.resolve(null);
  return Promise.resolve({ url: origin + "/stream/" + slug + "/native.m3u8", referer: embed });
}

/** One translator's mirror list; a dead translator must not stop the next one. */
function videoList(url, episodeUrl) {
  return text(url, { referer: episodeUrl })
    .then(function (raw) {
      return (JSON.parse(raw).videos || []).filter(function (video) {
        return video && video.id;
      }).map(function (video) {
        video.label = video.name || "Video";
        return video;
      });
    })
    .catch(function () {
      return [];
    });
}

/** Round-robin by mirror rank so every translator's first player is tried before any second. */
function interleave(lists) {
  var out = [];
  var deepest = 0;
  var i;
  for (i = 0; i < lists.length; i++) deepest = Math.max(deepest, lists[i].length);
  for (var rank = 0; rank < deepest && out.length < MAX_MIRRORS; rank++) {
    for (i = 0; i < lists.length && out.length < MAX_MIRRORS; i++) {
      if (lists[i][rank]) out.push(lists[i][rank]);
    }
  }
  return out;
}

/** /player/{id} is a same-host 302 to the embed; the embed page decides the stream. */
function embedOf(video, episodeUrl) {
  var playerUrl = BASE + "/player/" + video.id;
  return follow(playerUrl, episodeUrl)
    .then(function (target) {
      if (!target || target === playerUrl) return null;
      return playerOf(target, episodeUrl);
    })
    .catch(function () {
      return null;
    });
}

function probe(videos, episodeUrl, state, from) {
  if (from >= videos.length || state.rows.length >= MAX_LINKS) return Promise.resolve();
  var batch = videos.slice(from, from + BATCH);
  return Promise.all(batch.map(function (video) {
    return embedOf(video, episodeUrl).then(function (play) {
      return play ? { play: play, label: video.label } : null;
    });
  })).then(function (hits) {
    for (var i = 0; i < hits.length; i++) {
      var hit = hits[i];
      if (!hit || state.seen[hit.play.url] || state.rows.length >= MAX_LINKS) continue;
      state.seen[hit.play.url] = true;
      state.rows.push({
        name: "anizm",
        title: "anizm - " + hit.label + " [HLS]",
        url: hit.play.url,
        quality: "auto",
        headers: { Referer: hit.play.referer, Accept: "*/*" },
        subtitles: []
      });
    }
    return probe(videos, episodeUrl, state, from + BATCH);
  });
}

/** Two translators per round; later rounds run only while nothing playable was found. */
function round(sources, offset, episodeUrl, state) {
  if (offset >= sources.length || state.rows.length >= MAX_LINKS) return Promise.resolve();
  var slice = sources.slice(offset, offset + MAX_TRANSLATORS);
  return Promise.all(slice.map(function (url) {
    return videoList(url, episodeUrl);
  }))
    .then(function (lists) {
      return probe(interleave(lists), episodeUrl, state, 0);
    })
    .then(function () {
      return round(sources, offset + MAX_TRANSLATORS, episodeUrl, state);
    });
}

function getStreams(tmdbId, mediaType, season, episode) {
  // The repo harness may be fed the header sample ("tt12343534:tv:1:1") as the id.
  var sample = String(tmdbId || "").split(":");
  if (sample.length > 1) {
    tmdbId = sample[0];
    mediaType = sample[1] || mediaType;
    season = sample[2] || season;
    episode = sample[3] || episode;
  }
  var want = parseInt(episode, 10) || 1;
  var seasonNo = parseInt(season, 10) || 1;
  var episodeUrl = null;
  var state = { rows: [], seen: {} };
  var wantTitle = "";

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      wantTitle = title;
      if (!title) return [];
      return text(BASE + "/arama?q=" + encodeURIComponent(title));
    })
    .then(function (search) {
      if (!search) return [];
      var href = pickCard(search, wantTitle);
      if (!href) return [];
      return text(abs(BASE, href));
    })
    .then(function (detail) {
      if (!detail) return [];
      // turkanime lists one continuous episode run (no season tabs); later seasons fall back to #1.
      episodeUrl = pickEpisode(detail, seasonNo > 1 ? 1 : want);
      if (!episodeUrl) return [];
      episodeUrl = abs(BASE, episodeUrl);
      return text(episodeUrl);
    })
    .then(function (page) {
      if (!page) return [];
      var sources = all(/data-v3-translator[^>]*data-sources="([^"]+)"/, page).map(function (m) {
        return m[1];
      });
      if (!sources.length) {
        var epId = first(/data-episode-id="(\d+)"/, page);
        var trs = all(/data-translator-id="(\d+)"/, page).map(function (m) {
          return m[1];
        });
        if (epId) {
          sources = trs.map(function (id) {
            return BASE + "/bolum/" + epId + "/cevirmen/" + id;
          });
        }
      }
      if (!sources.length) return [];
      return round(sources.slice(0, MAX_SOURCES), 0, episodeUrl, state);
    })
    .then(function () {
      return state.rows;
    })
    .catch(function (e) {
      console.log("[bb-nuvio][anizm] " + (e && e.message));
      return state.rows;
    });
}

var MAX_LINKS = 3;
var MAX_MIRRORS = 6;
var MAX_TRANSLATORS = 2;
var MAX_SOURCES = 4;
var BATCH = 3;

module.exports = { getStreams };
