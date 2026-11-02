// @provider {"id":"tv8","name":"TV8","types":["tv"],"formats":["m3u8"],"domain":"tv8.com.tr","sample":"tt21766660:tv:1:60","description":"TV8 program ar\u015fivi; numaral\u0131 b\u00f6l\u00fcmler e\u015fle\u015fir, g\u00fcnl\u00fck programlarda tarih s\u0131ras\u0131; ak\u0131\u015f HLS."}
/**
 * tv8 — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /programlar -> program -> -video.htm list -> hls: field -> HLS.
 * Drama pages expose "<n>-bolum" slugs and match the episode number exactly; daily shows only
 * carry dates, so there the Nth listed date stands in for episode N.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
var BASE = "https://www.tv8.com.tr";
var STATIC = ["rss", "canli-yayin", "programlar", "yayin-akisi"];

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
  if (o.headers) for (var k in o.headers) headers[k] = o.headers[k];
  return fetch(url, { method: o.method || "GET", headers: headers, redirect: "follow" })
    .then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status + " " + url);
      return r.text();
    });
}

function first(re, body) {
  var m = re.exec(String(body || ""));
  return m ? m[1] : null;
}

// Cinemeta meta.name is often the English title ("MasterChef Turkey") while the catalog
// endpoint returns the IMDb primary title ("MasterChef Türkiye"); try both.
function titlesOf(id, mediaType) {
  var kind = mediaType === "tv" ? "series" : "movie";
  return text("https://v3-cinemeta.strem.io/meta/" + kind + "/" + id + ".json")
    .then(function (body) {
      var meta = JSON.parse(body).meta || {};
      var name = String(meta.name || "").trim();
      var names = name ? [name] : [];
      if (!name) return names;
      return text("https://v3-cinemeta.strem.io/catalog/" + kind + "/top/search=" + encodeURIComponent(name) + ".json")
        .then(function (list) {
          (JSON.parse(list).metas || []).forEach(function (hit) {
            if (hit.imdb_id === id && hit.name && names.indexOf(hit.name) < 0) names.push(String(hit.name).trim());
          });
          return names;
        })
        .catch(function () { return names; });
    })
    .catch(function () {
      return [];
    });
}

// Turkish letters folded so "MasterChef T\u00fcrkiye" matches the "masterchef-turkiye" path.
function norm(value) {
  return String(value || "").toLowerCase()
    .replace(/[\u0131\u0130]/g, "i").replace(/[\u015f\u015e]/g, "s").replace(/[\u011f\u011e]/g, "g")
    .replace(/[\u00fc\u00dc]/g, "u").replace(/[\u00f6\u00d6]/g, "o").replace(/[\u00e7\u00c7]/g, "c")
    .replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "");
}

function variants(master, playlist) {
  var origin = /^(https?:\/\/[^/]+)/.exec(master)[1];
  var base = master.replace(/[?#].*$/, "").replace(/[^/]*$/, "");
  var out = [], height = 0, lines = String(playlist || "").split("\n");
  for (var i = 0; i < lines.length; i++) {
    var line = lines[i].trim();
    if (line.indexOf("#EXT-X-STREAM-INF") === 0) {
      height = parseInt(first(/RESOLUTION=\d+x(\d+)/, line), 10) || parseInt(first(/NAME=(\d+)p/, line), 10) || 0;
    } else if (line && line.charAt(0) !== "#" && height > 0) {
      out.push({ quality: height + "p", url: /^https?:/i.test(line) ? line : line.charAt(0) === "/" ? origin + line : base + line });
      height = 0;
    }
  }
  return out;
}

function getStreams(tmdbId, mediaType, season, episode) {
  var wantEp = parseInt(episode, 10) || 1;
  var wanted = [];
  var show = null;

  return titlesOf(tmdbId, mediaType)
    .then(function (names) {
      for (var i = 0; i < names.length; i++) {
        var value = norm(names[i]);
        if (value) wanted.push(value);
      }
      if (!wanted.length) return null;
      return text(BASE + "/programlar");
    })
    .then(function (html) {
      if (!html) return null;
      var best = null, bestScore = 0;
      var re = /href="(?:\/|https:\/\/www\.tv8\.com\.tr\/)([a-z0-9-]+)"/g, m;
      while ((m = re.exec(html)) !== null) {
        var slug = m[1];
        if (!slug || slug.indexOf("/") > -1 || STATIC.indexOf(slug) > -1 || slug.indexOf("sayfa-") === 0) continue;
        var value = norm(slug);
        var score = 0;
        for (var i = 0; i < wanted.length && score < 3; i++) {
          var hit = 0;
          if (value === wanted[i]) hit = 3;
          else if (value.indexOf(wanted[i]) === 0 || wanted[i].indexOf(value) === 0) hit = 2;
          else if (value.indexOf(wanted[i]) > -1 || wanted[i].indexOf(value) > -1) hit = 1;
          if (hit > score) score = hit;
        }
        if (score > bestScore) {
          bestScore = score;
          best = slug;
        }
      }
      if (!best) return null;
      show = best;
      return text(BASE + "/" + best);
    })
    .then(function (html) {
      if (!html) return [];
      var links = [], re = /href="([^"]*-video\.htm)"/g, m;
      while ((m = re.exec(html)) !== null) {
        if (/tanitim/i.test(m[1])) continue;
        if (links.indexOf(m[1]) < 0) links.push(m[1]);
      }
      if (!links.length) return [];
      var numbered = [], dated = [];
      for (var i = 0; i < links.length; i++) {
        var marker = /-(\d+)-bolum(?:-|$)/.exec(links[i]);
        if (marker) numbered.push({ url: links[i], number: parseInt(marker[1], 10), date: "" });
      }
      var hit = null;
      for (var j = 0; j < numbered.length; j++) {
        if (numbered[j].number === wantEp) { hit = numbered[j]; break; }
      }
      if (!hit && numbered.length) return [];
      if (!hit) {
        // Daily shows carry only dates, so the Nth listed date stands in for episode N.
        dated = links.map(function (url, index) {
          var d = /-(\d{2})(\d{2})(\d{4})-/.exec(url);
          return { url: url, key: d ? Number(d[3] + d[2] + d[1]) : index, date: d ? d[1] + "." + d[2] + "." + d[3] : "" };
        }).sort(function (a, b) { return a.key - b.key; });
        hit = dated[wantEp - 1];
      }
      if (!hit) return [];
      return text(hit.url, { referer: BASE + "/" + show }).then(function (page) {
        var stream = first(/hls:\s*"([^"]+)"/, page) || first(/(https?:\/\/[^"'\\ ]+\.m3u8[^"'\\ ]*)/, page);
        if (!stream) return [];
        return text(stream, { referer: hit.url }).then(function (playlist) {
          var list = variants(stream, playlist);
          if (!list.length) list = [{ quality: "", url: stream }];
          return list.map(function (v) {
            return {
              name: "tv8",
              title: "TV8" + (hit.number ? " " + hit.number + ". b\u00f6l\u00fcm" : hit.date ? " " + hit.date : "") + (v.quality ? " " + v.quality : "") + " [HLS]",
              url: v.url,
              quality: v.quality,
              headers: { Referer: hit.url },
              subtitles: []
            };
          });
        });
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][tv8] " + (e && e.message));
      return [];
    });
}

module.exports = { getStreams };
