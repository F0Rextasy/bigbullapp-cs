// @provider {"id":"birasyadizi","name":"BirAsyaDizi","types":["tv"],"domain":"birasyadizi.cx","formats":["mp4"],"sample":"tt27714020:tv:1:1","description":"BirAsyaDizi Asya dizileri; ok.ru MP4 oynatıcı."}
/**
 * birasyadizi — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /?s= search -> /dizi/ page -> /bolum/ episode -> ok.ru embed -> MP4 ladder.
 */
var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://www.birasyadizi.cx";
var QMAP = { mobile: 240, lowest: 240, low: 360, sd: 480, high: 720, hd: 1080, full: 1080 };

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
    .replace(/ı/g, "i").replace(/i̇/g, "i")
    .replace(/ş/g, "s").replace(/ğ/g, "g")
    .replace(/ü/g, "u").replace(/ö/g, "o").replace(/ç/g, "c")
    .replace(/â/g, "a").replace(/î/g, "i").replace(/û/g, "u");
}

function clean(v) {
  return String(v || "").replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim();
}

function abs(base, href) {
  if (!href) return null;
  if (/^https?:/i.test(href)) return href;
  if (href.charAt(0) === "/" && href.charAt(1) === "/") return "https:" + href;
  var origin = /^(https?:\/\/[^/]+)/.exec(base);
  if (href.charAt(0) === "/") return (origin ? origin[1] : BASE) + href;
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

function cards(html) {
  var seen = {}, list = [], re = /<a[^>]+href="(https?:\/\/[^"]+\/dizi\/[^"]+)"[^>]*>/g, m;
  while ((m = re.exec(html)) !== null) {
    var url = m[1].replace(/\/$/, "");
    if (/\/feed$|\/page\/|\/dizi-kategori\//.test(url)) continue;
    var t = clean(first(/title="([^"]*)"/, m[0]));
    if (!seen[url]) {
      seen[url] = { url: url, title: t };
      list.push(seen[url]);
    } else if (!seen[url].title && t) {
      seen[url].title = t;
    }
  }
  return list;
}

function pick(list, query) {
  var fq = fold(query), slug = fq.replace(/[^a-z0-9]+/g, "-"), best = null;
  for (var i = 0; i < list.length; i++) {
    var f = fold(list[i].title);
    if (f && f === fq) return list[i];
    if (!best && f && (f.indexOf(fq) !== -1 || fq.indexOf(f) !== -1)) best = list[i];
    if (!best && slug && list[i].url.indexOf(slug) !== -1) best = list[i];
  }
  return best;
}

function parseEps(html, season) {
  var seen = {}, list = [], re = /<a[^>]+href="(https?:\/\/[^"]+\/bolum\/[^"]+)"[^>]*>/g, m;
  while ((m = re.exec(html)) !== null) {
    var url = m[1];
    if (seen[url]) continue;
    seen[url] = 1;
    var num = first(/-(\d+)-bolum/, url) || first(/bolum-(\d+)/, url);
    var se = season || parseInt(first(/-sezon-(\d+)-/, url), 10) || 0;
    list.push({ url: url, season: se, episode: num ? parseInt(num, 10) : 0 });
  }
  return list;
}

/** The page keeps one `#s-N` block per season while the episode URLs carry no season. */
function episodes(html) {
  var chunks = html.split(/<div id="s-(\d+)"[^>]*>/), out = [], i;
  if (chunks.length < 3) return parseEps(html, 0);
  for (i = 1; i < chunks.length; i += 2) {
    out = out.concat(parseEps(chunks[i + 1], parseInt(chunks[i], 10) || 0));
  }
  return out;
}

function choose(list, wantSe, wantEp) {
  var pool = [], i;
  for (i = 0; i < list.length; i++) {
    var e = list[i];
    if (e.season && wantSe && e.season !== wantSe) continue;
    pool.push(e);
  }
  for (i = 0; i < pool.length; i++) if (pool[i].episode === wantEp) return pool[i];
  // only an unnumbered listing is a sane guess; a missing episode stays missing
  for (i = 0; i < pool.length; i++) if (!pool[i].episode) return pool[i];
  return null;
}

function frames(html) {
  var seen = {}, out = [], re = /<iframe[^>]*>/g, m;
  while ((m = re.exec(html)) !== null) {
    var src = first(/\b(?:vdo-src|data-src|src)="([^"]+)"/, m[0]);
    if (!src || /^about:/.test(src)) continue;
    var url = abs(BASE, src);
    if (!url || seen[url]) continue;
    seen[url] = 1;
    out.push(url);
  }
  return out;
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

function push(out, url, label, referer) {
  if (!url || !/^http/.test(url)) return;
  for (var i = 0; i < out.length; i++) if (out[i].url === url) return;
  var q = QMAP[String(label || "").toLowerCase()] || 0;
  out.push({
    name: "birasyadizi",
    title: "birasyadizi - " + (q ? q + "p" : String(label || "auto")) + " [ok.ru]",
    url: url,
    quality: q ? q + "p" : "",
    headers: { Referer: referer || "https://ok.ru/" },
    subtitles: []
  });
}

function scan(html, base, referer, out) {
  var re = /https?:\/\/[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?/g, m;
  while ((m = re.exec(html)) !== null) push(out, m[0], "auto", referer);
  var rf = /(?:embedUrl|contentUrl|embed_url|player_url)"\s*:\s*"([^"]+)/g;
  while ((m = rf.exec(html)) !== null) push(out, abs(base, m[1]), "auto", base);
}

function players(page, referer, out) {
  var list = frames(page), chain = Promise.resolve();
  list.forEach(function (url) {
    chain = chain
      .then(function () {
        var id = first(/videoembed\/(\d+)/, url);
        if (id && /ok\.ru|odnoklassniki/.test(url)) {
          return okru(id).then(function (vids) {
            for (var i = 0; i < vids.length; i++) push(out, vids[i].url, vids[i].label, "https://ok.ru/");
          }).catch(function () {});
        }
        if (/\.(m3u8|mp4)/.test(url)) {
          push(out, url, "auto", referer);
          return null;
        }
        return text(url, { referer: referer })
          .then(function (inner) { scan(inner, url, url, out); })
          .catch(function () {});
      });
  });

  return chain;
}

/* Nuvio's own rows hand over TMDB ids; Wikidata links them to a title this site lists. */
function wikidataTitle(id, mediaType) {
  if (!/^\d+$/.test(String(id || ""))) return Promise.resolve("");
  var prop = mediaType === "movie" ? "P4947" : "P4983";
  var api = "https://www.wikidata.org/w/api.php?format=json";
  return text(api + "&action=query&list=search&srlimit=1&srsearch=" +
      encodeURIComponent("haswbstatement:" + prop + "=" + id))
    .then(function (body) {
      var hits = (JSON.parse(body).query || {}).search || [];
      if (!hits.length) return "";
      return text(api + "&action=wbgetentities&props=labels&languages=en|tr&ids=" + hits[0].title);
    })
    .then(function (body) {
      if (!body) return "";
      var entities = JSON.parse(body).entities || {};
      for (var key in entities) {
        var labels = entities[key].labels || {};
        var label = (labels.en || labels.tr || {}).value;
        if (label) return String(label).replace(/\s*\([^)]*\)\s*$/, "").trim();
      }
      return "";
    })
    .catch(function () {
      return "";
    });
}

function titleFor(id, mediaType) {
  return /^\d+$/.test(String(id || "")) ? wikidataTitle(id, mediaType) : titleOf(id, mediaType);
}

/* The site's WordPress RSS search answers in ~10 KB and each <item> carries the
   episode's player iframe; /?s= and the pages behind it are ~370 KB and take 10-60 s. */
function feedItems(query) {
  return text(BASE + "/?s=" + encodeURIComponent(query) + "&feed=rss2").then(function (xml) {
    var out = [], re = /<item>([\s\S]*?)<\/item>/g, m;
    if (xml.indexOf("<rss") === -1) return out;
    while ((m = re.exec(xml)) !== null) {
      var link = String(first(/<link>([\s\S]*?)<\/link>/, m[1]) || "").trim();
      if (!link) continue;
      var body = first(/<content:encoded>([\s\S]*?)<\/content:encoded>/, m[1]) || "";
      out.push({
        url: link,
        title: clean(first(/<title>([\s\S]*?)<\/title>/, m[1])).replace(/<!\[CDATA\[|\]\]>/g, ""),
        html: body.replace(/^\s*<!\[CDATA\[/, "").replace(/\]\]>\s*$/, "")
      });
    }
    return out;
  });
}

function feedEpisode(items, wantSe, wantEp) {
  for (var i = 0; i < items.length; i++) {
    if (items[i].url.indexOf("/bolum/") === -1) continue;
    var num = first(/-(\d+)-bolum/, items[i].url) || first(/(\d+)\s*\.\s*b[oö]l[uü]m/i, items[i].title);
    var se = parseInt(first(/-sezon-(\d+)-/, items[i].url), 10) || 0;
    if ((num ? parseInt(num, 10) : 0) === wantEp && (!se || se === wantSe)) return items[i];
  }
  return null;
}

/* Two light queries: the title itself, then the episode title the site gives it. */
function feedLookup(title, wantSe, wantEp) {
  var found = { series: null, ep: null };
  var queries = [title, title + " " + wantEp + ". Bölüm"];
  function step(q) {
    if (q >= queries.length || found.ep) return Promise.resolve(found);
    return feedItems(queries[q]).then(function (items) {
      for (var i = 0; i < items.length; i++) {
        if (!found.series && /\/dizi\//.test(items[i].url)) found.series = items[i].url;
      }
      found.ep = feedEpisode(items, wantSe, wantEp);
      return step(q + 1);
    });
  }
  return step(0).catch(function () {
    return found;
  });
}

function okId(html) {
  var re = /<iframe[^>]+(?:src|vdo-src|data-src)="([^"]*videoembed\/\d+[^"]*)"/g, m;
  while ((m = re.exec(String(html || ""))) !== null) {
    if (/ok\.ru|odnoklassniki/.test(m[1])) return first(/videoembed\/(\d+)/, m[1]);
  }
  return null;
}

/* A feed item already holds the player iframe, so no page has to be fetched. */
function playFeed(html, out) {
  scan(html, BASE, BASE, out);
  var id = okId(html);
  if (!id) return Promise.resolve();
  return okru(id)
    .then(function (vids) {
      for (var i = 0; i < vids.length; i++) push(out, vids[i].url, vids[i].label, "https://ok.ru/");
    })
    .catch(function () {});
}

function playPage(url, out) {
  return text(url).then(function (page) {
    return players(page, url, out);
  });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var packed = String(tmdbId || "").split(":"); // sample form "tt27714020:tv:1:1"
  if (packed.length > 1) {
    tmdbId = packed[0];
    mediaType = packed[1] || mediaType;
    season = packed[2] || season;
    episode = packed[3] || episode;
  }
  var wantSe = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var out = [], series = null, epUrl = null;

  return titleFor(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return null;
      // shortcut: titles are matched on the site's own listings, so a name the site
      // spells differently (localized) yields no source instead of a wrong one.
      return feedLookup(title, wantSe, wantEp).then(function (found) {
        series = found.series;
        if (found.ep) {
          epUrl = found.ep.url;
          if (okId(found.ep.html)) return playFeed(found.ep.html, out);
          return null;
        }
        if (series) return null;
        return text(BASE + "/?s=" + encodeURIComponent(title)).then(function (html) {
          var hit = pick(cards(html), title);
          series = hit ? hit.url : null;
        });
      });
    })
    .then(function () {
      if (out.length || !epUrl) return null;
      return playPage(epUrl, out);
    })
    .then(function () {
      if (out.length || epUrl || !series) return null;
      return text(series).then(function (html) {
        var ep = choose(episodes(html), wantSe, wantEp);
        return ep ? playPage(ep.url, out) : null;
      });
    })
    .then(function () {
      return out;
    })
    .catch(function (e) {
      console.log("[bb-nuvio][birasyadizi] " + (e && e.message));
      return out;
    });
}

module.exports = { getStreams };
