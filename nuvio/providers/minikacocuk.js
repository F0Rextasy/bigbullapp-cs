// @provider {"id":"minikacocuk","name":"MinikaCocuk","types":["tv"],"domain":"minikacocuk.com.tr","formats":["mp4"],"sample":"tt0487849:tv:1:1","description":"MinikaÇOCUK Web TV çocuk bölümleri (doğrudan MP4 akışı).","verified":true}
/**
 * minikacocuk — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /programlar/<slug> show page -> istenen sezon/bölüm -> contentUrl MP4.
 */
var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://www.minikacocuk.com.tr";

function text(url, referer) {
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (referer) headers.Referer = referer;
  return fetch(url, { headers: headers }).then(function (r) {
    if (!r.ok) throw new Error("HTTP " + r.status + " " + url);
    return r.text();
  });
}

// Kart başlıkları HTML entity taşır (&#252;); çözülmezse Türkçe adlar eşleşmez.
function decode(value) {
  return String(value == null ? "" : value)
    .replace(/<[^>]*>/g, " ")
    .replace(/&#x([0-9a-f]+);/gi, function (_, hex) { return String.fromCharCode(parseInt(hex, 16)); })
    .replace(/&#(\d+);/g, function (_, dec) { return String.fromCharCode(parseInt(dec, 10)); })
    .replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&#39;/g, "'")
    .replace(/&nbsp;/g, " ").replace(/&amp;/g, "&")
    .replace(/\s+/g, " ").trim();
}

function fold(value) {
  return decode(value).toLowerCase()
    .replace(/\u0307/g, "")
    .replace(/ı/g, "i").replace(/ş/g, "s").replace(/ğ/g, "g")
    .replace(/ü/g, "u").replace(/ö/g, "o").replace(/ç/g, "c");
}

function abs(href) {
  if (!href) return null;
  if (/^https?:/i.test(href)) return href;
  return BASE + (href.charAt(0) === "/" ? href : "/" + href);
}

function titleOf(id, mediaType) {
  var kind = mediaType === "tv" ? "series" : "movie";
  return fetch("https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json")
    .then(function (r) { return r.json(); })
    .then(function (j) { return String((j.meta || {}).name || "").trim(); })
    .catch(function () { return ""; });
}

// /webtv ve /programlar kartı: <div class="showcase"> içinde href + başlık (lightbox ya da kart içi).
function cards(html) {
  var parts = String(html).split(/<div class="showcase(?: [^"]*)?"/);
  var hrefRe = /href="([^"]+)"/;
  var titleRe = /class="[^"]*showcase-(?:txt|lightbox)-title[^"]*"[^>]*>([\s\S]*?)<\/h2>/;
  var out = [];
  for (var i = 1; i < parts.length; i++) {
    var href = hrefRe.exec(parts[i]);
    var title = titleRe.exec(parts[i]);
    if (!href || !title) continue;
    var url = abs(href[1]);
    if (!url) continue;
    out.push({ url: url, title: decode(title[1]) });
  }
  return out;
}

function tokens(query) {
  return fold(query).split(/[^a-z0-9]+/).filter(function (w) { return w.length >= 4; });
}

function hit(title, url, query) {
  var hay = fold(title + " " + url);
  var full = fold(query);
  if (!full) return false;
  if (hay.indexOf(full) !== -1) return true;
  var head = full.split(/ [-:|(]/)[0].trim();
  if (head.length >= 3 && hay.indexOf(head) !== -1) return true;
  var words = tokens(query);
  for (var j = 0; j < words.length; j++) if (hay.indexOf(words[j]) !== -1) return true;
  return false;
}

// Slider bağlantılarından (character-slider-item) eşleşen dizi sayfası.
function pickShow(html, query) {
  var re = /href="(\/programlar\/[^"]+)"/g;
  var m;
  while ((m = re.exec(html)) !== null) {
    if (hit("", m[1], query)) return abs(m[1]);
  }
  return null;
}

// Dizi sayfasındaki bölüm kartları; istenen sezon/bölüm yoksa en yeni bölüm.
function pickEpisode(html, season, episode) {
  var list = cards(html).filter(function (c) { return c.url.indexOf("/bolum/") !== -1; });
  if (!list.length) return null;
  var wantedSeason = parseInt(season, 10);
  var wantedEpisode = parseInt(episode, 10);
  var numbered = [];
  if (!(wantedSeason > 0)) wantedSeason = 1;
  for (var i = 0; i < list.length; i++) {
    var m = /(\d+)\.\s*sezon\s*(\d+)\.\s*bolum/.exec(fold(list[i].title));
    if (!m) continue;
    numbered.push(list[i]);
    if (wantedEpisode > 0 && +m[1] === wantedSeason && +m[2] === wantedEpisode) return list[i];
  }
  if (numbered.length) return numbered[0];
  return list[0];
}

function pickCard(html, query) {
  var list = cards(html);
  for (var i = 0; i < list.length; i++) if (hit(list[i].title, list[i].url, query)) return list[i];
  return null;
}

function getStreams(imdbId, mediaType, season, episode) {
  var pageUrl = null;
  var streamTitle = "";
  return titleOf(imdbId, mediaType)
    .then(function (title) {
      if (!title) return null;
      return text(BASE + "/webtv").then(function (home) {
        var show = pickShow(home, title);
        if (!show) {
          var card = pickCard(home, title);
          if (card) streamTitle = card.title;
          return card ? card.url : null;
        }
        return text(show).then(function (detail) {
          var picked = pickEpisode(detail, season, episode);
          if (picked) {
            streamTitle = picked.title;
            return picked.url;
          }
          var card = pickCard(home, title);
          if (card) streamTitle = card.title;
          return card ? card.url : null;
        });
      });
    })
    .then(function (url) {
      if (!url) return [];
      pageUrl = url;
      return text(url);
    })
    .then(function (page) {
      if (!page) return [];
      var raw = String(page).replace(/\\\//g, "/");
      var m = /contentUrl["']?\s*:\s*["']([^"']+)/.exec(raw);
      var url = m ? m[1] : null;
      if (!url || !/^https?:/.test(url) || !/(\.mp4|\.m3u8)/.test(url)) {
        m = /https?:\/\/[^"'\s<>\\]+?\.(?:mp4|m3u8)[^"'\s<>\\]*/.exec(raw);
        url = m ? m[0] : null;
      }
      if (!url) return [];
      var q = /(\d{3,4})p/.exec(url);
      return [{
        name: "minikacocuk",
        title: "MinikaCocuk - " + (streamTitle || "bölüm") + " " + (q ? q[1] + "p" : "[MP4]"),
        url: url,
        quality: q ? q[1] + "p" : "auto",
        headers: { Referer: pageUrl, "User-Agent": UA },
        subtitles: []
      }];
    })
    .catch(function (e) {
      console.log("[bb-nuvio][minikacocuk] " + (e && e.message));
      return [];
    });
}

module.exports = { getStreams };
