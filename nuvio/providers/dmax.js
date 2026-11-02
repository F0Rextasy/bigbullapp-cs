// @provider {"id":"dmax","name":"DMAX","types":["tv"],"domain":"dmax.com.tr","formats":["m3u8"],"sample":"tt1800864:tv:15:1","description":"DMAX Türkiye program arşivi; bölümler player/info HLS adresiyle açılır."}
/**
 * dmax — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> program list -> program page -> /<slug>/<S>-sezon-<E>-bolum
 *       -> data-video-code -> /player/info?referenceId= -> video.data.flavors.hls
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
var BASE = "https://www.dmax.com.tr";

/* Discovery shows are listed under their Turkish broadcast names, so English
   metadata needs a bridge; token matching alone cannot cross the language. */
var ALIASES = {
  "gold rush": "altin-pesinde",
  "gold rush parkers trail": "altin-pesinde-parkerin-yolu",
  "gold rush white water": "altin-pesinde-kopuklu-sular",
  "bering sea gold": "bering-bogazinda-altin",
  "deadliest catch": "olumcul-av",
  "the last alaskans": "son-alaskalilar",
  "alaska the last frontier": "alaska-son-sinir",
  "undercover billionaire": "gizli-milyarder",
  "bear grylls breaking point": "bear-grylls-kirilma-noktasi",
  "megalodon the monster shark lives": "megalodon-dev-kopekbaligi-yasiyor",
  "homestead rescue": "kirsalda-yasamak-zor",
  "100 days wild": "yabanda-100-gun",
  "building wild": "yabani-insa-etmek",
  "treasure quest snake island": "hazine-arayisi-yilan-adasi",
  "the last woodsman": "son-ormanci",
  "alaskan mega machines": "alaskanin-mega-makineleri",
  "border control sweden": "gumruk-kontrol-isvec",
  "dirty jobs": "tamirat-tadilat"
};

function text(url, opts) {
  var o = opts || {};
  var headers = { "User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9" };
  if (o.referer) headers.Referer = o.referer;
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

function fold(value) {
  return String(value || "")
    .toLowerCase()
    .replace(/ı/g, "i").replace(/ş/g, "s").replace(/ğ/g, "g")
    .replace(/ü/g, "u").replace(/ö/g, "o").replace(/ç/g, "c")
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
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

/* Program cards carry data-type="program"; the slug is the whole path. */
function programs(html) {
  var parts = String(html || "").split('data-type="program"');
  var out = [];
  var seen = {};
  for (var i = 1; i < parts.length; i++) {
    var seg = parts[i].slice(0, 900);
    var href = /href="(https:\/\/www\.dmax\.com\.tr\/[^"?#]+)"/.exec(seg);
    if (!href || seen[href[1]]) continue;
    seen[href[1]] = 1;
    var alt = /alt="([^"]*)"/.exec(seg);
    out.push({ url: href[1], name: alt ? alt[1] : "" });
  }
  return out;
}

function pickProgram(list, title) {
  var key = fold(title);
  var slug = ALIASES[key];
  if (slug) return BASE + "/" + slug;
  var wanted = key.split(" ").filter(function (w) {
    return w.length > 2;
  });
  var best = null;
  var bestScore = 0;
  for (var i = 0; i < list.length; i++) {
    var hay = fold(list[i].name);
    var score = 0;
    for (var k = 0; k < wanted.length; k++) if (hay.indexOf(wanted[k]) !== -1) score++;
    if (score > bestScore) {
      bestScore = score;
      best = list[i].url;
    }
  }
  return best;
}

function streamsFromInfo(body, referer) {
  var data = JSON.parse(body);
  var flavors = (((data || {}).video || {}).data || {}).flavors || {};
  var url = String(flavors.hls || "").trim();
  if (!/^https?:/i.test(url)) return [];
  return [
    {
      name: "dmax",
      title: "dmax - HLS",
      url: url,
      quality: "auto",
      headers: { Referer: referer },
      subtitles: []
    }
  ];
}

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var pageUrl = null;

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return null;
      return text(BASE + "/").then(function (home) {
        return pickProgram(programs(home), title);
      });
    })
    .then(function (programUrl) {
      if (!programUrl) return [];
      pageUrl = programUrl;
      return text(programUrl);
    })
    .then(function (program) {
      if (!program) return [];
      var exact = null;
      var any = null;
      var re = /href="([^"]*\/(\d+)-sezon-(\d+)-bolum[^"]*)"/g;
      var m;
      while ((m = re.exec(program)) !== null) {
        var href = /^https?:/i.test(m[1]) ? m[1] : BASE + m[1];
        if (!any) any = href;
        if (parseInt(m[2], 10) === want && parseInt(m[3], 10) === wantEp) {
          exact = href;
          break;
        }
      }
      var target = exact || any || pageUrl;
      pageUrl = target;
      return text(target);
    })
    .then(function (page) {
      if (!page) return [];
      var code = first(/data-video-code="([^"]+)"/, page);
      /* Programs without episodes keep their video code on the program page. */
      if (!code && pageUrl === BASE + "/") return [];
      if (!code) return [];
      return text(BASE + "/player/info?referenceId=" + encodeURIComponent(code), { referer: pageUrl }).then(
        function (info) {
          return streamsFromInfo(info, pageUrl);
        }
      );
    })
    .catch(function (e) {
      console.log("[bb-nuvio][dmax] " + (e && e.message));
      return [];
    });
}

module.exports = { getStreams };
