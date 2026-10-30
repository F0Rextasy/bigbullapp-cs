package com.dizibox

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.extractors.helper.CryptoJS
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

/** DiziBox — dizibox.live (mirrors: dizibox.com/.net/.vip) */
class DiziboxProvider : MainAPI() {
    override var mainUrl = "https://www.dizibox.live"
    private val uaHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9",
    )
    override var name = "DiziBox"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage get() = ROWS.mapNotNull { name ->
        if (!Settings.rowEnabled(name)) return@mapNotNull null
        when (name) {
            "Son Bölümler" -> MainPageData(name, "$mainUrl/tum-bolumler/")
            "Popüler" -> MainPageData(name, "$mainUrl/tum-bolumler/?tip=populer")
            "Dram" -> MainPageData(name, "$mainUrl/tur/dram/")
            "Komedi" -> MainPageData(name, "$mainUrl/tur/komedi/")
            "Korku" -> MainPageData(name, "$mainUrl/tur/korku/")
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.substringBefore("?").trimEnd('/')
        val query = request.data.substringAfter("?", "")
        val url = when {
            page <= 1 -> request.data
            query.isEmpty() -> "$path/page/$page/"
            else -> "$path/page/$page/?$query"
        }
        val doc = app.get(url, headers = uaHeaders).document
        if (path.contains("/tur/")) {
            val items = doc.select("article.article-series-poster").mapNotNull { it.toPosterSeries() }
            return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
        }
        val items = doc.select("article.article-episode-card").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items, hasNext = true)
    }

    /** Genre pages use the poster grid, unlike the episode feed. */
    private fun Element.toPosterSeries(): SearchResponse? {
        val anchor = selectFirst("a.poster-title") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        val img = selectFirst("img")
        return newTvSeriesSearchResponse(
            anchor.text().trim().ifBlank { anchor.attr("title") },
            href,
            TvType.TvSeries,
        ) {
            this.posterUrl = fixUrlNull(img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src"))
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a.episode-card-title") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        val series = selectFirst("b.series-name")?.text()?.trim().orEmpty()
        val season = selectFirst("span.season")?.text()?.trim().orEmpty()
        val episode = selectFirst("b.episode")?.text()?.trim().orEmpty()
        val title = listOf(series, "$season $episode".trim())
            .filter { it.isNotBlank() }.joinToString(" - ")
            .ifBlank { anchor.attr("title") }
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            val img = selectFirst("img")
            this.posterUrl = fixUrlNull(
                img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")
            )
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search/${query.trim().replace(" ", "+")}/", headers = uaHeaders).document
        return doc.select("article.detailed-article").mapNotNull { it.toSeriesSearchResult() }
    }

    private fun Element.toSeriesSearchResult(): SearchResponse? {
        val anchor = selectFirst("h3 a") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        return newTvSeriesSearchResponse(anchor.text().trim(), href, TvType.TvSeries) {
            val img = selectFirst("figure img")
            this.posterUrl = fixUrlNull(
                img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")
            )
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url, headers = uaHeaders).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val title = doc.selectFirst("div.tv-overview h1 a")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")

        val episodes = LinkedHashMap<String, Episode>()
        fun collect(page: org.jsoup.nodes.Document) {
            page.select("a.season-episode").forEach { anchor ->
                val href = fixUrlNull(anchor.attr("href")) ?: return@forEach
                val label = anchor.text().trim()
                val season = Regex("""(\d+)\.\s*Sezon""").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                episodes.getOrPut(href) {
                    newEpisode(href) {
                        this.name = label
                        this.season = season
                        this.episode = Regex("""(\d+)\.\s*Bölüm""").find(label)?.groupValues?.get(1)?.toIntOrNull()
                    }
                }
            }
        }
        collect(doc)
        doc.select("div#seasons-list a").forEach { seasonAnchor ->
            val seasonUrl = fixUrlNull(seasonAnchor.attr("href")) ?: return@forEach
            runCatching { collect(app.get(seasonUrl, headers = uaHeaders).document) }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.values.toList()) {
            this.posterUrl = fixUrlNull(doc.selectFirst("div.tv-overview figure img")?.attr("src"))
            this.plot = doc.selectFirst("div.tv-story p")?.text()?.trim()
            this.year = doc.selectFirst("a[href*='/yil/']")?.text()?.trim()?.toIntOrNull()
            this.tags = doc.select("a[href*='/tur/']").map { it.text() }
            this.score = Score.from10(doc.selectFirst("span.label-imdb b")?.text()?.trim())
            this.actors = doc.select("div.tv-overview a[href*='/oyuncu/']").map { ActorData(Actor(it.text())) }
            doc.selectFirst("div#trailer-box iframe")?.attr("src")
                ?.let { Regex("""youtube\.com/embed/([A-Za-z0-9_-]+)""").find(it)?.groupValues?.get(1) }
                ?.let { addTrailer("https://www.youtube.com/embed/$it") }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val seenLinks = mutableSetOf<String>()
        val seenSubs = mutableSetOf<String>()
        val linkCallback: (ExtractorLink) -> Unit = {
            if (seenLinks.add(it.url) && Settings.qualityAllowed(it.quality)) callback(it)
        }
        val subtitleFilter: (com.lagradost.cloudstream3.SubtitleFile) -> Unit = {
            if (seenSubs.add(it.url) && Settings.subtitleAllowed(it.lang)) subtitleCallback(it)
        }
        val doc = runCatching { app.get(data, headers = uaHeaders).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: kaynak bulunamadı. $REPORT_HINT")
        val players = mutableListOf<String>()
        fixUrlNull(doc.selectFirst("div#video-area iframe")?.attr("src"))?.let { players.add(it) }
        doc.select("div.video-toolbar option[value]").forEach { source ->
            runCatching {
                val alt = app.get(fixUrl(source.attr("value")), headers = uaHeaders).document
                fixUrlNull(alt.selectFirst("div#video-area iframe")?.attr("src"))?.let { players.add(it) }
            }
        }

        var found = false
        players.filter { it.contains("/player/") }.distinct().forEach { player ->
            runCatching {
                when {
                    player.contains("king.php") ->
                        if (resolvePlayer(data, player, linkCallback)) found = true
                    player.contains("moly.php") || player.contains("haydi.php") ->
                        unwrapWrapper(data, player)?.forEach { inner ->
                            if (loadExtractor(inner, data, subtitleFilter, linkCallback)) found = true
                        }
                    loadExtractor(player, data, subtitleFilter, linkCallback) -> found = true
                }
            }
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir akış yok. $REPORT_HINT")
        return found
    }

    /**
     * `moly.php` answers an `atob(unescape(...))`-obfuscated page and `haydi.php` a plain
     * one; both only wrap a third-party iframe (vidmoly / ok.ru) that core can extract.
     */
    private suspend fun unwrapWrapper(episodeUrl: String, playerUrl: String): List<String>? {
        val raw = runCatching {
            app.get(playerUrl, referer = episodeUrl, headers = uaHeaders).text
        }.getOrNull() ?: return null
        val html = Regex("""atob\(unescape\("([^"]+)"\)\)""").find(raw)?.groupValues?.get(1)
            ?.let { base64Decode(java.net.URLDecoder.decode(it, "UTF-8")) }
            ?: raw
        return Regex("""<iframe[^>]+src="([^"]+)"""").findAll(html)
            .mapNotNull { fixUrlNull(it.groupValues[1]) }
            .toList()
    }

    /** king.php -> molystream embed -> CryptoJS AES -> m3u8 master */
    private suspend fun resolvePlayer(
        episodeUrl: String,
        playerUrl: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val king = playerUrl.replace("king.php?v=", "king.php?wmode=opaque&v=")
        val inner = app.get(king, referer = episodeUrl, headers = uaHeaders).document
            .selectFirst("div#Player iframe")?.attr("src") ?: return false

        val embed = app.get(fixUrl(inner), referer = "$mainUrl/", headers = uaHeaders).text
        val cipher = Regex("""CryptoJS\.AES\.decrypt\("(.*?)","""").find(embed)?.groupValues?.get(1)
            ?: return false
        val pass = Regex("""","(.*?)"\);""").find(embed)?.groupValues?.get(1) ?: return false
        val plain = CryptoJS.decrypt(pass, cipher)
        val stream = Regex("""file:\s*'(.*?)'""").find(plain)?.groupValues?.get(1)
            ?: return false

        callback.invoke(
            newExtractorLink(name, name, stream, ExtractorLinkType.M3U8) {
                this.referer = fixUrl(inner)
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    companion object {
        /** Homepage rows in display order; genre rows map to live `/tur/<tur>/` listings. */
        val ROWS = listOf("Son Bölümler", "Popüler", "Dram", "Komedi", "Korku")

        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}
