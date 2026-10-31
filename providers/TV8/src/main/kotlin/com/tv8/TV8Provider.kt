package com.tv8

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document

/**
 * TV8 — tv8.com.tr program arşivi.
 *
 * Arama, `/programlar` sayfasındaki program köklerinde yerel filtredir.
 * Program sayfası `-video.htm` bölümlerini; bölüm sayfası `hls: "..."` alanını basar.
 */
class TV8Provider : MainAPI() {
    override var mainUrl = "https://www.tv8.com.tr"
    override var name = "TV8"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val headers = mapOf(
        "User-Agent" to UA,
        "Accept-Language" to "tr-TR,tr;q=0.9",
    )

    override val mainPage get() = mainPageOf(
        "$mainUrl/programlar" to "Programlar",
    ).filter { Settings.rowEnabled(it.name) }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data, headers = headers).document
        return newHomePageResponse(request.name, doc.cards(), hasNext = false)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (page > 1) return newSearchResponseList(emptyList(), false)
        val doc = app.get("$mainUrl/programlar", headers = headers).document
        val q = query.trim().lowercase()
        return newSearchResponseList(
            doc.cards().filter { it.name.lowercase().contains(q) || it.url.lowercase().contains(q) },
            false,
        )
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = fixUrl(url)
        val doc = runCatching { app.get(pageUrl, headers = headers).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: içerik sayfası yüklenemedi. $REPORT_HINT")
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("$name: başlık okunamadı. $REPORT_HINT")
        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?.takeIf { it.isNotEmpty() }

        if (pageUrl.contains("-video.htm")) {
            return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        val episodes = doc.select("a[href*='-video.htm']").mapNotNull { card ->
            val href = fixUrlNull(card.attr("href")) ?: return@mapNotNull null
            newEpisode(href) {
                this.name = card.text().trim().ifBlank { "Bölüm" }
                this.posterUrl = fixUrlNull(card.selectFirst("img")?.attr("src"))
            }
        }.distinctBy { it.data }
        if (episodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
        return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageUrl = fixUrl(data)
        val html = runCatching { app.get(pageUrl, headers = headers).text }.getOrNull()
            ?: throw ErrorLoadingException("$name: video sayfası yüklenemedi. $REPORT_HINT")
        val stream = HLS.find(html)?.groupValues?.get(1)
            ?: M3U8.find(html)?.groupValues?.get(0)
            ?: throw ErrorLoadingException("$name: HLS adresi bulunamadı. $REPORT_HINT")
        callback(newExtractorLink(name, name, stream, ExtractorLinkType.M3U8) {
            this.referer = pageUrl
            this.headers = headers
            this.quality = Qualities.Unknown.value
        })
        return true
    }

    private fun Document.cards(): List<SearchResponse> =
        select("a[href]").mapNotNull { card ->
            val href = fixUrlNull(card.attr("href")) ?: return@mapNotNull null
            if (!href.startsWith(mainUrl)) return@mapNotNull null
            val path = href.removePrefix(mainUrl).substringBefore('?').trim('/')
            if (path.isBlank() || path.contains('/') || path in STATIC) return@mapNotNull null
            val title = card.text().trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = fixUrlNull(card.selectFirst("img")?.attr("src"))
            }
        }.distinctBy { it.url }

    companion object {
        val ROWS = listOf("Programlar")
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
        private val HLS = Regex("""hls:\s*"([^"]+)"""")
        private val M3U8 = Regex("""https?://[^"'\\ ]+\.m3u8[^"'\\ ]*""")
        private val STATIC = setOf("programlar", "canli-yayin", "yayin-akisi")
    }
}
