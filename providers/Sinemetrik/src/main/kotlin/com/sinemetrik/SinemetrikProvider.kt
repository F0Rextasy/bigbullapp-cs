package com.sinemetrik

import com.bigbull.common.http11Get
import com.bigbull.common.browserHeaders
import com.bigbull.common.jsonLdDescription
import com.bigbull.common.posterSrcLazy
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import java.net.URLDecoder
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Sinemetrik — sinemetrik.com (TMDB tabanlı Türkçe film/dizi platformu).
 *
 * HTML sayfaları Cloudflare Turnstile kapısının arkasında (`/guvenlik-kontrol`); yalnız bot
 * UA'larına (Googlebot) açık. `/api/...`, `/test-player.php`, `/s1.php` ve `media-relay.php`
 * kapısız çalışır.
 *
 * - Arama `GET /api/search?q=` → `{results:[{name,slug,type:"movie"|"tv",poster,date}]}` (10 sonuç).
 * - Liste `GET /filmler?page=N`, `GET /diziler?page=N` → `a.movie-card`; son sayfa `.pagination`.
 * - Detay sayfasında JSON-LD `Movie`/`TVSeries` (künye, tür, puan, süre, oyuncu);
 *   dizi bölümleri `GET /api/dizi/<seriesId>/sezon/<N>/bolumler`.
 * - Akış: filmde `_s1url` (pv) + `_s1dpUrl` (dp ultra); bölümde `server1_player_url` (pv).
 *   `GET /s1.php?t=main&id=<pv>` (Referer zorunlu) → `stream` = media-relay HLS + TR altyazı.
 */
class SinemetrikProvider : MainAPI() {
    override var mainUrl = "https://www.sinemetrik.com"
    override var name = "Sinemetrik"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    /** HTML ve çözümleme uçları yalnız bot UA'sında kapıyı geçer. */
    private val botHeaders get() = browserHeaders + mapOf("user-agent" to BOT_UA)

    override val mainPage get() = ROW_PATHS
        .filter { Settings.rowEnabled(it.first) }
        .map { MainPageData(it.first, "$mainUrl/${it.second}") }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.replace(PAGE, page.toString())
        val body = runCatching { http11Get(url, botHeaders) }.getOrNull()
            ?: throw ErrorLoadingException("$name: ana sayfa yüklenemedi. $REPORT_HINT")
        if (url.contains("/api/home/platforms")) {
            val html = runCatching { JSONObject(body).optString("html") }.getOrDefault("")
            val cards = Jsoup.parse(html).select("a.ctw-card").mapNotNull { it.toCard() }
            return newHomePageResponse(request.name, cards, false)
        }
        if (url.contains("/api/home/episodes")) {
            val html = runCatching { JSONObject(body).optString("html") }.getOrDefault("")
            val cards = Jsoup.parse(html).select("a.hep-card").mapNotNull { it.toEpisodeCard() }
            return newHomePageResponse(request.name, cards, false)
        }
        val doc = Jsoup.parse(body)
        val cards = doc.select("a.movie-card").mapNotNull { it.toCard() }
        val hasNext = doc.select("div.pagination a").any { it.text().contains("Sonraki") }
        return newHomePageResponse(request.name, cards, hasNext)
    }

    /** Kart: liste `a.movie-card` (`.movie-title`, `.movie-meta span`, `.movie-rating`), vitrin `a.ctw-card` (`.ctw-*`). */
    private fun Element.toCard(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val title = selectFirst(".ctw-title, .movie-title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: attr("title").trim().takeIf { it.isNotEmpty() }
            ?: return null
        val year = selectFirst(".ctw-year, .movie-meta span")?.text()?.trim()?.toIntOrNull()
        val rating = selectFirst(".ctw-rating, .movie-rating")?.text()?.filter { it.isDigit() || it == '.' }
        val poster = fixUrlNull(selectFirst("img")?.posterSrcLazy())
        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                this.score = Score.from10(rating)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                this.score = Score.from10(rating)
            }
        }
    }

    /** Bölüm kartı: `/dizi/<slug>/sezon/<s>/bolum/<b>`; dizi URL'sine indirgenir. */
    private fun Element.toEpisodeCard(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val seriesUrl = href.substringBefore("/sezon/").takeIf { it != href } ?: return null
        val title = attr("title").substringBefore(" — ").trim()
            .ifBlank { selectFirst(".hep-title")?.text()?.trim().orEmpty() }
            .takeIf { it.isNotEmpty() } ?: return null
        return newTvSeriesSearchResponse(title, seriesUrl, TvType.TvSeries) {
            this.posterUrl = fixUrlNull(selectFirst("img")?.posterSrcLazy())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> = search(query, 1)?.items ?: emptyList()

    /** Site araması 10 sonuçla sınırlı; sayfa parametresi yok. */
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val term = query.trim()
        if (term.isEmpty() || page > 1) return newSearchResponseList(emptyList(), false)
        val body = runCatching {
            http11Get("$mainUrl/api/search?q=${URLEncoder.encode(term, "UTF-8")}", botHeaders)
        }.getOrNull() ?: return newSearchResponseList(emptyList(), false)
        val results = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull() ?: JSONArray()
        val items = (0 until results.length()).mapNotNull { index ->
            val item = results.optJSONObject(index) ?: return@mapNotNull null
            val title = item.optString("name").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val isMovie = item.optString("type") != "tv"
            val url = "$mainUrl/${if (isMovie) "film" else "dizi"}/${item.optString("slug")}"
            val poster = item.optString("poster").takeIf { it.isNotBlank() }?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
            val year = item.optString("date").take(4).toIntOrNull()
            if (isMovie) {
                newMovieSearchResponse(title, url, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                }
            } else {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = year
                }
            }
        }
        return newSearchResponseList(items, false)
    }

    private fun Document.jsonLd(): JSONObject? =
        select("script[type=application/ld+json]").mapNotNull {
            runCatching { JSONObject(it.data()) }.getOrNull()
        }.firstOrNull { ld -> VIDEO_TYPES.any { ld.optString("@type").contains(it) } }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = fixUrl(url)
        val doc = Jsoup.parse(http11Get(pageUrl, botHeaders))
        val ld = doc.jsonLd()
        val title = ld?.optString("name")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" (")?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: başlık okunamadı. $REPORT_HINT")
        val poster = fixUrlNull(
            doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: ld?.optString("image")?.takeIf { it.startsWith("http") }
        )
        val plot = doc.jsonLdDescription()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
        val year = ld?.optString("datePublished")?.take(4)?.toIntOrNull()
        val tags = ld?.optJSONArray("genre")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).trim().takeIf(String::isNotEmpty) }
        }
        val score = Score.from10(ld?.optJSONObject("aggregateRating")?.optString("ratingValue"))
        val actors = ld?.optJSONArray("actor")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val name = array.optJSONObject(index)?.optString("name")?.trim().orEmpty()
                if (name.isEmpty()) return@mapNotNull null
                ActorData(Actor(name, null))
            }
        }
        val durationSec = ld?.optString("duration")?.let { DURATION.find(it)?.groupValues?.get(1)?.toIntOrNull()?.times(60) }

        if (pageUrl.contains("/dizi/")) {
            val seriesId = doc.selectFirst("[data-series-id]")?.attr("data-series-id")?.takeIf { it.isNotBlank() }
                ?: throw ErrorLoadingException("$name: dizi kimliği okunamadı. $REPORT_HINT")
            val seasons = doc.select("[data-season]").mapNotNull { it.attr("data-season").toIntOrNull() }
                .distinct().sorted()
            val episodes = seasons.flatMap { season ->
                val body = runCatching {
                    http11Get("$mainUrl/api/dizi/$seriesId/sezon/$season/bolumler", botHeaders)
                }.getOrNull() ?: return@flatMap emptyList()
                val array = runCatching { JSONObject(body).optJSONArray("episodes") }.getOrNull() ?: JSONArray()
                (0 until array.length()).mapNotNull { index ->
                    val episode = array.optJSONObject(index) ?: return@mapNotNull null
                    val number = episode.optInt("episode_number", index + 1)
                    // Bölümün oynatıcı uçları: sunucu 1 (pv), ULTRA (dp), ULTRA hash (s2), VIP embed.
                    // `id` her bölümü benzersiz tutar (ipucu olmayan bölümler de listelenir).
                    val hints = JSONObject().put("id", episode.optString("id"))
                    val s1Raw = episode.optString("server1_player_url").trim()
                    if (s1Raw.isNotEmpty()) {
                        hints.put("s1", s1Raw)
                        PV.find(s1Raw)?.groupValues?.get(1)?.let { hints.put("pv", it) }
                    }
                    episode.optString("server1_dp_id").takeIf { it.isNotBlank() }?.let { hints.put("dp", it) }
                    episode.optString("server2_ultra_hash").takeIf { it.isNotBlank() && it != "None" }?.let {
                        hints.put("s2", it)
                        hints.put("s2p", episode.optString("server2_ultra_provider"))
                    }
                    episode.optString("server1_vip1_embed_url").takeIf { it.startsWith("http") }
                        ?.let { hints.put("vip", it) }
                    newEpisode(hints.toString()) {
                        this.name = episode.optString("name").ifBlank { "$season. Sezon $number. Bölüm" }
                        this.season = episode.optInt("season_number", season)
                        this.episode = number
                        this.posterUrl = fixUrlNull(episode.optString("still_local_path").takeIf { it.isNotBlank() })
                        this.description = episode.optString("overview").takeIf { it.isNotBlank() }
                        this.score = Score.from10(episode.optString("vote_average"))
                        this.runTime = episode.optInt("runtime").takeIf { it > 0 }
                    }
                }
            }.distinctBy { it.data }
            return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = score
                this.actors = actors
            }
        }

        return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.score = score
            this.actors = actors
            this.duration = durationSec
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var found = false
        val hints = runCatching { JSONObject(data.trim()) }.getOrNull()
        if (hints != null) {
            // Bölüm: sırayla ana kaynak, ULTRA, ULTRA hash, VIP embed.
            val s1 = hints.optString("s1").takeIf { it.isNotBlank() }
                ?: hints.optString("pv").takeIf { it.isNotBlank() }
            if (s1 != null) {
                found = resolveMain(s1, "Sinemetrik", subtitleCallback, callback)
            }
            if (!found) hints.optString("dp").takeIf { it.isNotBlank() }?.let { dp ->
                found = resolveUltra(dp, subtitleCallback, callback)
            }
            if (!found) hints.optString("s2").takeIf { it.isNotBlank() }?.let { hash ->
                val provider = hints.optString("s2p").let { if (it == "hlszone") "hlszone" else "canliplayer" }
                found = resolve(
                    "$mainUrl/s2.php?t=ultra&id=$hash&cdp=$provider",
                    "Sinemetrik ULTRA",
                    subtitleCallback,
                    callback,
                )
            }
            if (!found) hints.optString("vip").takeIf { it.startsWith("http") }?.let { embed ->
                found = runCatching {
                    com.lagradost.cloudstream3.utils.loadExtractor(
                        embed,
                        "$mainUrl/",
                        Settings.filterSubtitles(subtitleCallback),
                        Settings.filterLinks(callback),
                    )
                }.getOrDefault(false)
            }
        } else {
            // Film: detay sayfasındaki server 1 (`rv=` veya eski `pv=`) ve ULTRA (dp).
            val html = http11Get(fixUrl(data), botHeaders)
            val s1 = S1_URL.find(html)?.groupValues?.get(1).orEmpty()
            val dp = DP.find(S1_DP.find(html)?.groupValues?.get(1).orEmpty())?.groupValues?.get(1)
            // ULTRA (dp) düz medya listesi; ana kaynak ayrı ses izli master. Önce ULTRA.
            if (dp != null) found = resolveUltra(dp, subtitleCallback, callback) || found
            if (s1.isNotBlank()) found = resolveMain(s1, "Sinemetrik", subtitleCallback, callback) || found
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir kaynak bulunamadı. $REPORT_HINT")
        return true
    }

    private suspend fun resolveMain(
        s1Ref: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val rv = RV.find(s1Ref)?.groupValues?.get(1)
        if (rv != null) {
            val decoded = runCatching { URLDecoder.decode(rv, "UTF-8") }.getOrDefault(rv)
            val s3 = "$mainUrl/s3.php?t=main&url=${URLEncoder.encode(decoded, "UTF-8")}"
            if (resolve(s3, label, subtitleCallback, callback)) return true
        }
        val pv = PV.find(s1Ref)?.groupValues?.get(1)
            ?: s1Ref.takeIf { !it.contains("?") && !it.contains("/") }
            ?: return false
        return resolve("$mainUrl/s1.php?t=main&id=$pv", label, subtitleCallback, callback)
    }

    private suspend fun resolveUltra(
        dp: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = resolve("$mainUrl/s1.php?t=ultra&id=$dp&k=jw", "Sinemetrik ULTRA", subtitleCallback, callback)

    /** `s1.php`/`s3.php` yanıtı: `{success, stream, subtitles[]}`. */
    private suspend fun resolve(
        endpoint: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val referer = "$mainUrl/test-player.php?embed=1"
        val body = runCatching { http11Get(endpoint, browserHeaders, referer = referer) }.getOrNull()
            ?: runCatching { http11Get(endpoint, botHeaders, referer = referer) }.getOrNull()
            ?: return false
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return false
        if (!json.optBoolean("success")) return false
        val stream = json.optString("stream").takeIf { it.startsWith("http") } ?: return false

        val relay = RELAY.find(stream)?.groupValues?.get(1)
        val candidates = ArrayList<Pair<String, String>>()
        if (relay != null) candidates.add("$mainUrl/media-relay.php?$relay" to "$label relay")
        candidates.add(stream to label)
        for ((url, linkLabel) in candidates) {
            callback.invoke(newExtractorLink(name, linkLabel, url, ExtractorLinkType.M3U8) {
                this.referer = "$mainUrl/"
                this.headers = browserHeaders
                this.quality = Qualities.Unknown.value
            })
        }

        json.optJSONArray("subtitles")?.let { subs ->
            for (index in 0 until subs.length()) {
                val sub = subs.optJSONObject(index) ?: continue
                val subUrl = sub.optString("url").takeIf { it.startsWith("http") } ?: continue
                val language = sub.optString("lang").ifBlank { "tr" }
                if (!Settings.subtitleAllowed(language)) continue
                val subRelay = RELAY.find(subUrl)?.groupValues?.get(1)
                val target = if (subRelay != null) "$mainUrl/media-relay.php?$subRelay" else subUrl
                subtitleCallback.invoke(newSubtitleFile(language, target) {
                    this.headers = browserHeaders
                })
            }
        }
        return true
    }

    companion object {
        private const val PAGE = "SAYFA"

        /** Ana sayfa satırları; ayarlardan kapatılabilir. */
        private val ROW_PATHS = listOf(
            "Netflix" to "api/home/platforms?platform=netflix-8",
            "Disney+" to "api/home/platforms?platform=disney-plus-337",
            "Exxen" to "api/home/platforms?platform=exxen-1791",
            "Tabii" to "api/home/platforms?platform=tabii-2235",
            "Amazon Prime" to "api/home/platforms?platform=amazon-prime-video-119",
            "Mubi" to "api/home/platforms?platform=mubi-11",
            "Yeni Bölümler" to "api/home/episodes?tab=latest",
            "Filmler" to "filmler?page=$PAGE",
            "Diziler" to "diziler?page=$PAGE",
        )
        val ROWS = ROW_PATHS.map { it.first }

        private const val BOT_UA = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"
        private val VIDEO_TYPES = listOf("Movie", "TVSeries")
        private val RV = Regex("""[?&]rv=([^&"]+)""")
        private val RELAY = Regex("""media-relay\.php\?(.+)$""")
        private val PV = Regex("""pv=([A-Za-z0-9_-]+)""")
        private val DP = Regex("""[?&]dp=(\d+)""")
        private val S1_URL = Regex("""_s1url\s*=\s*"([^"]*)"""")
        private val S1_DP = Regex("""_s1dpUrl\s*=\s*"([^"]*)"""")
        private val DURATION = Regex("""PT(\d+)M""")
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}
