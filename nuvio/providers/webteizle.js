// @provider {"id":"webteizle", "name":"WebteIzle", "types":["movie", "tv"], "domain":"webteizle.info", "formats":["m3u8"], "description":"WebteIzle film/dizi kaynaklari (vidmoly, streamruby HLS; ok.ru/pixel yedek).", "sample":"tt13611778:movie:1:1", "verified":false}
/**
 * webteizle — Nuvio provider (bb-nuvio).
 * Flow: Cinemeta title -> /hakkinda/<slug> (yoksa /filtre?a=) -> #wip data-id ->
 *       POST /ajax/dataAlternatif3.asp (dil 0 dublaj, 1 altyazi) ->
 *       POST /ajax/dataEmbed.asp -> vidmoly / streamruby / ok.ru / pixel ->
 *       player sayfasindaki sources.file (gerekirse hex) -> master m3u8 + VTT.
 * Site Cloudflare arkasinda; bu egress 403 aliyor (tarayici/cihaz gecer).
 */

var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
var BASE = "https://webteizle.info";
// Desteklenen oynaticilar; filemoon/netu/dzen kapsam disi.
var HOSTS = ["vidmoly", "streamruby", "ok.ru", "pixel"];

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

function form(url, body, referer) {
  return text(url, {
    method: "POST",
    referer: referer,
    body: body,
    headers: {
      "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
      "X-Requested-With": "XMLHttpRequest",
      "Accept": "application/json, text/javascript, */*; q=0.01"
    }
  });
}

function first(pattern, body) {
  var m = pattern.exec(String(body || ""));
  return m ? m[1] : null;
}

function abs(base, href) {
  if (!href) return null;
  if (/^https?:/i.test(href)) return href;
  if (String(href).indexOf("//") === 0) return "https:" + href;
  var origin = /^(https?:\/\/[^/]+)/.exec(base);
  if (href.charAt(0) === "/") return origin ? origin[1] + href : href;
  return base.replace(/[^/]*$/, "") + href;
}

function norm(s) {
  return String(s || "").toLowerCase().replace(/[^a-z0-9]+/g, "");
}

function slugify(s) {
  var map = { "\u00e7": "c", "\u011f": "g", "\u0131": "i", "\u00f6": "o", "\u015f": "s", "\u00fc": "u" };
  return String(s || "")
    .toLowerCase()
    .replace(/[\u00e7\u011f\u0131\u00f6\u015f\u00fc]/g, function (c) { return map[c] || c; })
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
}

function langOf(label) {
  var s = String(label || "").toLowerCase();
  if (/t[üu]rk|^tr$/.test(s)) return "tr";
  if (/eng|^en$/.test(s)) return "en";
  return "";
}

function titleOf(id, mediaType) {
  var kinds = mediaType === "movie" ? ["movie", "series"] : ["series", "movie"];
  function step(i) {
    if (i >= kinds.length) return Promise.resolve("");
    return text("https://v3-cinemeta.strem.io/meta/" + kinds[i] + "/" + id + ".json")
      .then(function (body) {
        var meta = JSON.parse(body).meta || {};
        var name = String(meta.name || "").trim();
        return name ? name : step(i + 1);
      })
      .catch(function () {
        return step(i + 1);
      });
  }
  return step(0);
}

function filmOf(page, slug) {
  var id = first(/id="wip"[^>]*data-id="(\d+)"/, page) || first(/data-id="(\d+)"[^>]*id="wip"/, page);
  return id ? { id: id, slug: slug } : null;
}

// Slugs are the slugified original title, so the detail page is usually one hop.
function detail(title) {
  var slug = slugify(title);
  return text(BASE + "/hakkinda/" + slug)
    .then(function (page) {
      var hit = filmOf(page, slug);
      if (hit) return hit;
      return text(BASE + "/filtre?a=" + encodeURIComponent(title)).then(function (list) {
        var links = [];
        var re = /href="\/hakkinda\/([^"?]+)"/g;
        var m;
        while ((m = re.exec(String(list || ""))) !== null) {
          if (links.indexOf(m[1]) === -1) links.push(m[1]);
        }
        if (!links.length) return null;
        var want = norm(title);
        var pick = links[0];
        for (var i = 0; i < links.length; i++) {
          if (norm(links[i]) === want) {
            pick = links[i];
            break;
          }
        }
        return text(BASE + "/hakkinda/" + pick).then(function (page2) {
          return filmOf(page2, pick);
        });
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][webteizle] " + (e && e.message));
      return null;
    });
}

function alts(filmId, dil, s, b, referer) {
  var body = "filmid=" + filmId + "&dil=" + dil + "&s=" + (s || "") + "&b=" + (b || "") + "&bot=0";
  return form(BASE + "/ajax/dataAlternatif3.asp", body, referer).then(function (raw) {
    var json;
    try {
      json = JSON.parse(raw);
    } catch (e) {
      return [];
    }
    return (json && json.data) || [];
  });
}

function rank(item) {
  var name = String((item || {}).baslik || "").toLowerCase();
  for (var i = 0; i < HOSTS.length; i++) if (name.indexOf(HOSTS[i]) !== -1) return i;
  return HOSTS.length;
}

function unhex(s) {
  if (!/^(?:[0-9a-f]{2})+$/i.test(s)) return "";
  var out = "";
  for (var i = 0; i < s.length; i += 2) out += String.fromCharCode(parseInt(s.substr(i, 2), 16));
  return out;
}

function linkOf(raw) {
  var value = String(raw || "").replace(/\\\//g, "/");
  if (/^https?:/i.test(value)) return value;
  var decoded = unhex(value);
  return /^https?:/i.test(decoded) ? decoded : "";
}

function vtts(page) {
  var out = [];
  var re = /\{[^{}]*file\s*:\s*["']([^"']+\.vtt[^"']*)["'][^{}]*\}/g;
  var m;
  while ((m = re.exec(String(page || ""))) !== null) {
    var chunk = m[0];
    if (/thumbnail/i.test(chunk)) continue;
    var kind = first(/kind\s*:\s*["']([^"']+)["']/, chunk);
    if (kind && !/caption|subtitle/i.test(kind)) continue;
    var label = first(/label\s*:\s*["']([^"']+)["']/, chunk);
    var url = linkOf(m[1]);
    if (!url) continue;
    out.push({
      url: url,
      language: langOf(label),
      name: String(label || "Altyazi") + " [webteizle]"
    });
  }
  return out;
}

function playable(page) {
  var raw =
    first(/sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+)["']/, page) ||
    first(/file\s*:\s*["']([^"']+)["']/, page);
  if (!raw) return null;
  var url = linkOf(raw);
  if (!url) return null;
  return { url: url, subs: vtts(page), label: first(/label\s*:\s*["']([^"']+)["']/, page) };
}

function heightOf(playlist) {
  var best = 0;
  var re = /RESOLUTION=\d+x(\d+)/g;
  var m;
  while ((m = re.exec(String(playlist || ""))) !== null) {
    var height = parseInt(m[1], 10) || 0;
    if (height > best) best = height;
  }
  return best;
}

function okru(id) {
  return text("https://ok.ru/dk?cmd=videoPlayerMetadata&mid=" + encodeURIComponent(id), {
    method: "POST",
    headers: { "User-Agent": "Mozilla/5.0 (Linux; Android 13)", "Referer": "https://ok.ru/", "Accept": "application/json" }
  }).then(function (body) {
    var list = JSON.parse(body).videos || [];
    var out = [];
    for (var i = 0; i < list.length; i++) {
      var v = list[i];
      if (!v || v.disallowed) continue;
      if (/^mobile$/i.test(String(v.name || ""))) continue;
      var url = String(v.url || "").replace(/\\\//g, "/");
      if (!/^https?:/.test(url)) continue;
      out.push({ url: url, quality: String(v.name || "auto"), hls: /\.m3u8/i.test(url) });
    }
    return out;
  });
}

// dil 0 = sitenin "Türkçe Dublaj" secimi; altyazi etiketi yalniz vtt dosyasi ekliyse.
function row(url, quality, subs, dubbing, hls) {
  var label = (dubbing ? " \u2022 TR Dublaj" : "") + (subs.length ? " \u2022 Altyaz\u0131l\u0131" : "");
  return {
    name: "WebteIzle",
    title: "WebteIzle - " + quality + (hls ? " [HLS]" : " [MP4]") + label,
    url: url,
    quality: quality,
    headers: {},
    subtitles: subs
  };
}

function jobOf(html, referer) {
  if (!html || /reCAPTCHADATA/i.test(html)) return null;
  var call = /<script[^>]*>\s*(\w+)\('([^']+)'/.exec(html);
  if (call) {
    var fn = call[1];
    var vid = call[2];
    if (fn === "vidmoly") return { kind: "page", url: "https://vidmoly.net/embed-" + vid + ".html", referer: referer };
    if (fn === "sruby") return { kind: "page", url: "https://rubyvidhub.com/embed-" + vid + ".html", referer: referer };
    if (fn === "okru") return { kind: "okru", id: vid };
    if (fn === "pixel") return { kind: "mp4", url: "https://pixeldrain.com/api/file/" + String(vid).split("|")[0] };
    return null;
  }
  var frame = first(/<iframe[^>]+src="([^"]+)"/, html);
  if (!frame) return null;
  var url = abs(BASE + "/", frame);
  if (!url || /\/ajax\/reCAPTCHADATA/i.test(url)) return null;
  // dataEmbed kimi zaman /player/<host>.asp iframe'i dondurur; host'a gore coz.
  var vp = first(/[?&]v=([^&"']+)/, url);
  if (vp && /\/player\/vidmoly\.asp/i.test(url)) return { kind: "page", url: "https://vidmoly.net/embed-" + vp + ".html", referer: url };
  if (vp && /\/player\/ok\.ru\.asp/i.test(url)) return { kind: "okru", id: vp };
  if (vp && /\/player\/pixel\.asp/i.test(url)) return { kind: "mp4", url: "https://pixeldrain.com/api/file/" + vp.split("|")[0] };
  return { kind: "page", url: url, referer: BASE + "/" };
}

function play(job, dubbing) {
  if (job.kind === "okru") {
    return okru(job.id).then(function (videos) {
      var out = [];
      for (var i = 0; i < videos.length; i++) out.push(row(videos[i].url, videos[i].quality, [], dubbing, videos[i].hls));
      return out;
    });
  }
  if (job.kind === "mp4") return Promise.resolve([row(job.url, "auto", [], dubbing, false)]);
  return text(job.url, { referer: job.referer }).then(function (page) {
    var hit = playable(page);
    if (!hit) return [];
    var tagged = /(\d{3,4})p/.exec(String(hit.label || ""));
    var fallback = tagged ? tagged[1] + "p" : "auto";
    return text(hit.url)
      .then(function (master) {
        var height = heightOf(master);
        return [row(hit.url, height ? height + "p" : fallback, hit.subs, dubbing, true)];
      })
      .catch(function () {
        return [row(hit.url, fallback, hit.subs, dubbing, true)];
      });
  });
}

function tryHosts(list, i, dubbing, referer) {
  if (i >= list.length || rank(list[i]) >= HOSTS.length) return Promise.resolve([]);
  return form(BASE + "/ajax/dataEmbed.asp", "id=" + list[i].id, referer)
    .then(function (html) {
      var job = jobOf(html, referer);
      return job ? play(job, dubbing) : [];
    })
    .then(function (rows) {
      return rows.length ? rows : tryHosts(list, i + 1, dubbing, referer);
    })
    .catch(function () {
      return tryHosts(list, i + 1, dubbing, referer);
    });
}

// dil 0 (dublaj) bos ya da oynaticilari kiriksa dil 1 (altyazi) denenir.
function attempt(info, dil, s, b) {
  if (dil > 1) return Promise.resolve([]);
  var watch = BASE + "/izle/" + (dil ? "altyazi" : "dublaj") + "/" + info.slug + (s ? "/" + s + "-sezon-" + b + "-bolum" : "");
  return alts(info.id, dil, s, b, watch)
    .then(function (list) {
      list.sort(function (a, b2) { return rank(a) - rank(b2); });
      return tryHosts(list, 0, dil === 0, watch);
    })
    .then(function (rows) {
      return rows.length ? rows : attempt(info, dil + 1, s, b);
    })
    .catch(function () {
      return attempt(info, dil + 1, s, b);
    });
}

function getStreams(tmdbId, mediaType, season, episode) {
  var tv = mediaType === "tv" || mediaType === "series";
  var s = tv ? String(parseInt(season, 10) || 1) : "";
  var b = tv ? String(parseInt(episode, 10) || 1) : "";
  return titleOf(tmdbId, mediaType)
    .then(function (title) {
      if (!title) return null;
      return detail(title).then(function (info) {
        return info ? attempt(info, 0, s, b) : [];
      });
    })
    .catch(function (e) {
      console.log("[bb-nuvio][webteizle] " + (e && e.message));
      return [];
    });
}

module.exports = { getStreams };


/* NuvioMobile always calls getStreams with args.tmdbId (JsBindings.kt); the
   value is a numeric TMDB id after TmdbService.ensureTmdbId. Resolve it to
   IMDb so the existing Cinemeta/title flow keeps working. */
var __origGetStreams = module.exports.getStreams;
module.exports.getStreams = function (id, mediaType, season, episode) {
  var raw = String(id || "");
  if (!/^\d+$/.test(raw)) return __origGetStreams(raw, mediaType, season, episode);
  var tv = mediaType !== "movie";
  var headers = { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36", "Accept": "application/json" };
  var key = typeof TMDB_API_KEY !== "undefined" ? TMDB_API_KEY : "";
  var api = "https://www.wikidata.org/w/api.php?format=json";
  var prop = tv ? "P4983" : "P4947";
  function fromTmdb() {
    if (!key) return Promise.resolve("");
    return fetch("https://api.themoviedb.org/3/" + (tv ? "tv" : "movie") + "/" + raw + "/external_ids?api_key=" + encodeURIComponent(key), { headers: headers })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (json) { return String((json && json.imdb_id) || ""); })
      .catch(function () { return ""; });
  }
  function fromWikidata() {
    return fetch(api + "&action=query&list=search&srlimit=1&srsearch=" + encodeURIComponent("haswbstatement:" + prop + "=" + raw), { headers: headers })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (json) {
        var hits = (json && json.query && json.query.search) || [];
        if (!hits.length) return "";
        return fetch(api + "&action=wbgetentities&props=claims&ids=" + hits[0].title, { headers: headers });
      })
      .then(function (r) { return r && r.ok ? r.json() : null; })
      .then(function (json) {
        var entities = (json && json.entities) || {};
        for (var entity in entities) {
          var rows = ((entities[entity] || {}).claims || {}).P345 || [];
          for (var i = 0; i < rows.length; i++) {
            var value = rows[i] && rows[i].mainsnak && rows[i].mainsnak.datavalue && rows[i].mainsnak.datavalue.value;
            if (/^tt\d+$/.test(String(value || ""))) return value;
          }
        }
        return "";
      })
      .catch(function () { return ""; });
  }
  return fromTmdb().then(function (imdb) { return imdb || fromWikidata(); }).then(function (imdb) {
    return __origGetStreams(imdb || raw, mediaType, season, episode);
  }).catch(function () { return []; });
};

/* Dil etiketi: akis basligina sunucunun destekledigi dili yazar; kanit yoksa etiket yok. */
var __langLabeled = module.exports.getStreams;
module.exports.getStreams = function (id, mediaType, season, episode) {
  return __langLabeled.apply(this, arguments).then(function (streams) {
    return (streams || []).map(function (s) {
      if (!s || !s.title || /dublaj|altyaz/i.test(String(s.title))) return s;
      if (s.subtitles && s.subtitles.length) s.title = String(s.title) + " \u2022 Altyaz\u0131l\u0131";
      return s;
    });
  });
};
