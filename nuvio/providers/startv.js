// @provider {"id":"startv","name":"StarTV","types":["tv"],"domain":"startv.com.tr","formats":["m3u8"],"sample":"tt22719788:tv:1:6","description":"Star TV dizi ar\u015fivi; b\u00f6l\u00fcmler DYG altyap\u0131s\u0131ndan HLS olarak gelir."}
/**
 * startv — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /dizi list -> episode number -> DYG video_info -> HLS variants.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
var BASE = "https://www.startv.com.tr";
var DYG = "https://dygvideo.dygdigital.com/api/video_info";
var DYG_KEY = "NtvApiSecret2014*";

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

// Turkish letters folded so "\u00d6mer" matches the "omer" slug.
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
      height = parseInt(first(/RESOLUTION=\d+x(\d+)/, line), 10) || 0;
    } else if (line && line.charAt(0) !== "#" && height > 0) {
      out.push({ quality: height + "p", url: /^https?:/i.test(line) ? line : line.charAt(0) === "/" ? origin + line : base + line });
      height = 0;
    }
  }
  return out;
}

function getStreams(tmdbId, mediaType, season, episode) {
  var wantEp = parseInt(episode, 10) || 1;
  var wanted = "";
  var episodeUrl = null;
  var master = null;

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      wanted = norm(title);
      if (!wanted) return null;
      return text(BASE + "/dizi");
    })
    .then(function (html) {
      if (!html) return null;
      var best = null, bestScore = 0;
      var re = /href="\/dizi\/([a-z0-9-]+)"[\s\S]{0,600}?alt="([^"]+)"/g, m;
      while ((m = re.exec(html)) !== null) {
        var name = norm(m[2]), slug = norm(m[1]);
        var score = 0;
        if (name === wanted || slug === wanted) score = 3;
        else if ((name && name.indexOf(wanted) === 0) || (slug && slug.indexOf(wanted) === 0)) score = 2;
        else if ((name && name.indexOf(wanted) > -1) || (slug && slug.indexOf(wanted) > -1)) score = 1;
        if (score > bestScore) {
          bestScore = score;
          best = m[1];
        }
      }
      if (!best) return null;
      return text(BASE + "/dizi/" + best + "/bolumler");
    })
    .then(function (html) {
      if (!html) return [];
      var re = /href="([^"]*\/bolumler\/(\d+)-bolum)"/g, m, found = null;
      while ((m = re.exec(html)) !== null) {
        if (parseInt(m[2], 10) === wantEp) {
          found = m[1];
          break;
        }
      }
      if (!found) return [];
      episodeUrl = /^https?:/i.test(found) ? found : BASE + found;
      return text(episodeUrl, { referer: BASE + "/" });
    })
    .then(function (html) {
      if (!html) return [];
      var tags = first(/dyg:tags"\s+content="([^"]+)"/, html);
      var contentId = tags ? tags.split(",").pop().trim() : first(/"referenceId"\s*:\s*"([a-f0-9]{24})"/, html);
      if (!contentId) return [];
      var api = DYG + "?akamai=true&PublisherId=1&ReferenceId=StarTv_" + encodeURIComponent(contentId) +
        "&SecretKey=" + encodeURIComponent(DYG_KEY);
      return text(api, { referer: BASE + "/" }).then(function (body) {
        var data = JSON.parse(body).data || {};
        var flavors = data.flavors || {};
        master = typeof flavors.hls === "string" && /^https?:/i.test(flavors.hls) ? flavors.hls : null;
        if (!master && flavors["0"]) master = flavors["0"].file_url_1;
        if (!master || !/^https?:/i.test(master)) return [];
        return text(master, { referer: BASE + "/" }).then(function (playlist) {
          var list = variants(master, playlist);
          if (!list.length) list = [{ quality: "", url: master }];
          return list.map(function (v) {
            return {
              name: "startv",
              title: "StarTV" + (v.quality ? " " + v.quality : "") + " [HLS]",
              url: v.url,
              quality: v.quality,
              headers: { Referer: BASE + "/" },
              subtitles: []
            };
          });
        });
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][startv] " + (e && e.message));
      return [];
    });
}

module.exports = { getStreams };
