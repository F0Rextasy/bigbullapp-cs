// @provider {"id":"asyadizileriizle","name":"Asyadizileriizle","types":["tv"],"domain":"asyadizileriizle.com","formats":["mp4"],"sample":"tt27714020:tv:1:1","description":"Asyadizileriizle (DiziCaN) Asya dizileri; ok.ru MP4 oynatıcı."}
/**
 * asyadizileriizle — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /?s= search -> /dizi/ page -> /bolum/ episode -> ok.ru embed -> MP4 ladder.
 */
var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://asyadizileriizle.com";
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
  return pool[0] || null;
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
    name: "asyadizileriizle",
    title: "asyadizileriizle - " + (q ? q + "p" : String(label || "auto")) + " [ok.ru]",
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

function getStreams(tmdbId, mediaType, season, episode) {
  var wantSe = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var epUrl = null;
  var out = [];

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return null;
      // shortcut: titles are matched on the site's own listings, so a Cinemeta name the
      // site spells differently (localized) yields no source instead of a wrong one.
      return text(BASE + "/?s=" + encodeURIComponent(title)).then(function (html) {
        return pick(cards(html), title);
      });
    })
    .then(function (hit) {
      if (!hit) return null;
      return text(hit.url);
    })
    .then(function (html) {
      if (!html) return null;
      var ep = choose(episodes(html), wantSe, wantEp);
      if (!ep) return null;
      epUrl = ep.url;
      return text(epUrl);
    })
    .then(function (page) {
      if (!page) return [];
      return players(page, epUrl, out);
    })
    .then(function () {
      return out;
    })
    .catch(function (e) {
      console.log("[bb-nuvio][asyadizileriizle] " + (e && e.message));
      return [];
    });
}

module.exports = { getStreams };
