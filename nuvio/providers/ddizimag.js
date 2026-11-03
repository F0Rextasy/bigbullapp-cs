// @provider {"id":"ddizimag","name":"DDizimag","types":["tv"],"domain":"ddizimag.com","formats":["m3u8"],"sample":"tt0903747:tv:1:1","description":"DDizimag yabancı dizi arşivi; HLS akışları pilavyer oynatıcısından gelir."}
/**
 * ddizimag — Nuvio provider (bb-nuvio).
 * Flow: TMDB id -> Wikidata -> IMDb -> Cinemeta title -> /ara?q= -> /dizi/<slug>/sezon-S/bolum-E
 *       -> data-pv slug -> pilavyer s.php -> signed /api/stream.php playlist.
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://ddizimag.com";
var PLAYER = "https://pilavyerplay.top";

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

/* Nuvio sends TMDB ids from its own rows; Wikidata bridges them to the IMDb id Cinemeta keys on. */
function imdbOf(id, mediaType) {
  if (!/^\d+$/.test(String(id || ""))) return Promise.resolve(String(id || ""));
  var prop = mediaType === "movie" ? "P4947" : "P4983";
  var api = "https://www.wikidata.org/w/api.php?format=json";
  return text(api + "&action=query&list=search&srlimit=1&srsearch=" +
      encodeURIComponent("haswbstatement:" + prop + "=" + id))
    .then(function (body) {
      var hits = (JSON.parse(body).query || {}).search || [];
      if (!hits.length) return "";
      return text(api + "&action=wbgetclaims&entity=" + hits[0].title + "&property=P345");
    })
    .then(function (body) {
      if (!body) return "";
      var claims = (JSON.parse(body).claims || {}).P345 || [];
      for (var i = 0; i < claims.length; i++) {
        var snak = claims[i].mainsnak || {};
        if (snak.datavalue) return String(snak.datavalue.value || "");
      }
      return "";
    })
    .catch(function () {
      return "";
    });
}

function titleOf(id, mediaType) {
  var kind = mediaType === "tv" ? "series" : "movie";
  return imdbOf(id, mediaType)
    .then(function (imdb) {
      if (!imdb) return "";
      return text("https://v3-cinemeta.strem.io/meta/" + kind + "/" + imdb + ".json");
    })
    .then(function (body) {
      if (!body) return "";
      var meta = JSON.parse(body).meta || {};
      return String(meta.name || "").trim();
    })
    .catch(function () {
      return "";
    });
}

function fold(value) {
  return String(value || "")
    .toLowerCase()
    .replace(/ı/g, "i").replace(/ş/g, "s").replace(/ğ/g, "g")
    .replace(/ü/g, "u").replace(/ö/g, "o").replace(/ç/g, "c")
    .replace(/[^a-z0-9]+/g, " ");
}

/* Best card for the Cinemeta title: exact slug, then series over film, then most shared words. */
function pickCard(html, title) {
  var re = /<a href="https:\/\/ddizimag\.com\/(dizi|film)\/([^"?#]+)"/g;
  var want = fold(title);
  var wanted = want.split(" ").filter(function (w) {
    return w.length > 2;
  });
  var best = null;
  var bestScore = -1;
  var m;
  while ((m = re.exec(html)) !== null) {
    var path = "/" + m[1] + "/" + m[2];
    var hay = fold(m[2]);
    /* Same-name films outrank the series otherwise ("Stranger Things" exists as both). */
    var score = hay === want ? 1000 : m[1] === "dizi" ? 100 : 0;
    for (var i = 0; i < wanted.length; i++) if (hay.indexOf(wanted[i]) !== -1) score++;
    if (score > bestScore) {
      bestScore = score;
      best = BASE + path;
    }
  }
  return best;
}

function playerSlug(html) {
  return first(/data-pv="([^"]+)"/, html);
}

function playlist(html, referer) {
  var raw = String(html || "").replace(/\\\//g, "/").replace(/\\u0026/g, "&").replace(/&amp;/g, "&");
  var m = /(https?:\/\/[^"'\s]+?\/api\/stream\.php\?v=[0-9a-f]+[^"'\s]*?token=[^"'\s&]+)/.exec(raw);
  if (!m) return [];
  return [
    {
      name: "ddizimag",
      title: "ddizimag - HLS",
      url: m[1],
      quality: "auto",
      headers: { Referer: (referer || PLAYER) + "/" },
      subtitles: []
    }
  ];
}

function getStreams(tmdbId, mediaType, season, episode) {
  var want = parseInt(season, 10) || 1;
  var wantEp = parseInt(episode, 10) || 1;
  var wanted = "";
  var card = null;

  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) {
        console.log("[bb-nuvio][ddizimag] başlık çözülemedi: " + tmdbId);
        return null;
      }
      wanted = title;
      return text(BASE + "/ara?q=" + encodeURIComponent(title));
    })
    .then(function (search) {
      if (!search || !wanted) return null;
      card = pickCard(search, wanted);
      if (!card) return null;
      /* Series episodes are addressable; film pages carry no signed player on ddizimag. */
      if (card.indexOf("/dizi/") === -1) return text(card);
      return text(card + "/sezon-" + want + "/bolum-" + wantEp).catch(function () {
        return "";
      });
    })
    .then(function (page) {
      if (!page) return [];
      if (playerSlug(page)) return page;
      return text(card).then(function (series) {
        var re = /href="(https:\/\/ddizimag\.com\/dizi\/[^"]*\/sezon-(\d+)\/bolum-(\d+))"/g;
        var exact = null;
        var any = null;
        var m;
        while ((m = re.exec(series)) !== null) {
          if (!any) any = m[1];
          if (parseInt(m[2], 10) === want && parseInt(m[3], 10) === wantEp) {
            exact = m[1];
            break;
          }
        }
        var target = exact || any;
        return target ? text(target) : "";
      });
    })
    .then(function (page) {
      var slug = playerSlug(page);
      if (!slug) return [];
      /* Player hosts rotate (pilavyerplay.top, play2.pilavyerplay.top, ...): read it off core.js. */
      var host = first(/<script[^>]+src="(https:\/\/[^"]+)\/assets\/js\/core\.js/, page) || PLAYER;
      return text(host + "/assets/js/s.php?s=" + encodeURIComponent(slug), { referer: BASE + "/" }).then(
        function (player) {
          return playlist(player, host);
        }
      );
    })
    .catch(function (e) {
      console.log("[bb-nuvio][ddizimag] " + (e && e.message));
      return [];
    });
}

module.exports = { getStreams };
