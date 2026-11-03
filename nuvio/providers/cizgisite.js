// @provider {"id":"cizgisite","name":"CizgiSite","types":["tv"],"domain":"cizgi.site","formats":["m3u8","mp4"],"sample":"tt0100366:tv:1:1","description":"CizgiSite çizgi film arşivi; vk HLS/MP4 ve ok.ru oynatıcıları."}
/**
 * cizgisite — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /tr catalogue filter -> series page (article.icerik) ->
 *       episode page -> vk video_ext (video.getEmbed ladder) + ok.ru embed.
 */
var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://www.cizgi.site";
var QMAP = { mobile: 240, lowest: 240, low: 360, sd: 480, high: 720, hd: 1080, full: 1080 };
var QARR = { 144: 144, 240: 240, 360: 360, 480: 480, 720: 720, 1080: 1080 };

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
    .replace(/ı/g, "i").replace(/ş/g, "s").replace(/ğ/g, "g")
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

/** The site ships no search endpoint; the /tr catalogue is the whole index. */
function cards(html) {
  var seen = {}, list = [], re = /<div class="ikon-dis">\s*<a href="([^"]+)"([^>]*)>/g, m;
  while ((m = re.exec(html)) !== null) {
    var url = abs(BASE, m[1]);
    if (!url || seen[url]) continue;
    seen[url] = 1;
    var title = clean(first(/title="([^"]*)"/, m[2])) || clean(first(/<h4[^>]*>([\s\S]*?)<\/h4>/, html.slice(m.index, m.index + 600)));
    list.push({ url: url, title: title });
  }
  return list;
}

function pick(list, query) {
  var fq = fold(query), best = null;
  for (var i = 0; i < list.length; i++) {
    var f = fold(list[i].title);
    if (!f) continue;
    if (f === fq) return list[i];
    if (!best && (f.indexOf(fq) !== -1 || fq.indexOf(f) !== -1)) best = list[i];
  }
  return best;
}

function episodes(html) {
  var parts = html.split(/<article[^>]*class="[^"]*icerik[^"]*"[^>]*>/), out = [], i;
  for (i = 1; i < parts.length; i++) {
    var p = parts[i];
    var href = first(/<a href="([^"]+)"/, p);
    if (!href) continue;
    var se = first(/class="sezon"[^>]*>\s*Sezon:\s*(\d+)/, p) || first(/Sezon:\s*(\d+)/, clean(first(/class="sezon"[^>]*>([\s\S]*?)<\/a>/, p)));
    var ep = first(/class="bolum"[^>]*>\s*Bölüm:\s*(\d+)/, p) || first(/Bölüm:\s*(\d+)/, clean(first(/class="bolum"[^>]*>([\s\S]*?)<\/a>/, p)));
    if (!ep) ep = first(/(\d+)-/, href.substring(href.lastIndexOf("/") + 1));
    out.push({ url: abs(BASE, href), season: se ? parseInt(se, 10) : 0, episode: ep ? parseInt(ep, 10) : 0 });
  }
  return out;
}

function choose(list, wantSe, wantEp) {
  var pool = [], i;
  for (i = 0; i < list.length; i++) {
    if (list[i].season && wantSe && list[i].season !== wantSe) continue;
    pool.push(list[i]);
  }
  for (i = 0; i < pool.length; i++) if (pool[i].episode === wantEp) return pool[i];
  return pool[0] || list[0] || null;
}

function frames(html) {
  var seen = {}, out = [], re = /(?:src|data-src)=["']([^"']*(?:vk\.com\/video_ext|video_ext\.php|ok\.ru\/videoembed|odnoklassniki\.ru\/videoembed)[^"']*)["']/gi, m;
  while ((m = re.exec(html)) !== null) {
    var url = abs(BASE, m[1]);
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

function push(out, url, quality, label, referer) {
  if (!url || !/^http/.test(url)) return;
  for (var i = 0; i < out.length; i++) if (out[i].url === url) return;
  out.push({
    name: "cizgisite",
    title: "cizgisite - " + (quality ? quality + "p" : "auto") + " [" + label + "]",
    url: url,
    quality: quality ? quality + "p" : "",
    headers: referer ? { Referer: referer } : {},
    subtitles: []
  });
}

/**
 * video_ext used to inline the mp4/hls ladder. It now ships just a webToken, so the ladder
 * comes from video.getEmbed with that anonym token; the inline regex stays for older pages.
 */
function vk(html, out) {
  var before = out.length, re = /"(mp4_\d+|hls|hls_fmp4)"\s*:\s*"(https?:[^"]+)"/g, m;
  while ((m = re.exec(html)) !== null) {
    var kind = m[1];
    var url = m[2].replace(/\\\//g, "/").replace(/&amp;/g, "&");
    var q = QARR[parseInt(first(/mp4_(\d+)/, kind) || "0", 10)] || 0;
    // the CDN rejects the embed page as Referer
    push(out, url, q, "vk", "");
  }
  return out.length - before;
}

function vkApi(frame, embed, out) {
  var oid = first(/[?&]oid=(-?\d+)/, frame);
  var id = first(/[?&]id=(\d+)/, frame);
  var token = first(/access_token"\s*:\s*"(anonym\.[^"]+)"/, embed);
  if (!oid || !id || !token) return Promise.resolve(0);
  var body = "owner_id=" + encodeURIComponent(oid) + "&video_id=" + encodeURIComponent(id) +
    "&hash=" + encodeURIComponent(first(/[?&]hash=([0-9a-fA-F]+)/, frame) || "") +
    "&access_token=" + encodeURIComponent(token);
  var before = out.length;
  return text("https://api.vk.com/method/video.getEmbed?v=5.289&client_id=6287487", {
    method: "POST",
    body: body,
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
      "Origin": "https://vk.com",
      "Referer": "https://vk.com/"
    }
  }).then(function (raw) {
    var files = ((JSON.parse(raw).response || {}).video || {}).files || {};
    for (var kind in files) {
      // hls_fmp4 is the same ladder in fMP4; one master keeps the list free of twin rows
      if (kind === "hls_fmp4" && files.hls) continue;
      if (!/^(mp4_\d+|hls)/.test(kind)) continue;
      push(out, String(files[kind] || ""), QARR[parseInt(first(/mp4_(\d+)/, kind) || "0", 10)] || 0, "vk", "");
    }
    return out.length - before;
  }).catch(function () {
    return 0;
  });
}

function loadVk(url, referer, out, tries) {
  return text(url, { referer: referer })
    .then(function (inner) {
      var n = vk(inner.replace(/\\\//g, "/"), out);
      return n > 0 ? n : vkApi(url, inner, out);
    })
    .then(function (n) {
      return n > 0 || tries >= 2 ? null : loadVk(url, referer, out, tries + 1);
    })
    .catch(function () {
      return tries < 2 ? loadVk(url, referer, out, tries + 1) : null;
    });
}

function players(page, referer, out) {
  var list = frames(page), chain = Promise.resolve();
  list.forEach(function (url) {
    chain = chain.then(function () {
      var id = first(/videoembed\/(\d+)/, url);
      if (id && /ok\.ru|odnoklassniki/.test(url)) {
        return okru(id).then(function (vids) {
          for (var i = 0; i < vids.length; i++) push(out, vids[i].url, QMAP[vids[i].label.toLowerCase()] || 0, "ok.ru", "https://ok.ru/");
        }).catch(function () {});
      }
      return loadVk(url, referer, out, 1);
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
      return text(BASE + "/tr").then(function (html) {
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
      return out.sort(function (a, b) { return (parseInt(b.quality, 10) || 0) - (parseInt(a.quality, 10) || 0); });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][cizgisite] " + (e && e.message));
      return [];
    });
}

module.exports = { getStreams };
