package com.dmax

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
import org.json.JSONObject
import org.jsoup.nodes.Document

/**
 * DMAX — dmax.com.tr belgesel/program arşivi.
 *
 * Arama, ana sayfadaki program köklerinde yerel filtredir. Program sayfası
 * `/sezonN-bolumM` kartlarını; bölüm sayfası `data-video-code` değerini basar.
 * `/player/info?referenceId=<code>` JSON'undaki `video.data.flavors.hls` HLS'tir.
 */
class DMAXProvider : MainAPI() {
    override var mainUrl = "https://www.dmax.com.tr"
    override var name = "DMAX"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val headers = mapOf(
        "User-Agent" to UA,
        "Accept-Language" to "tr-TR,tr;q=0.9",
    )

    override val mainPage get() = mainPageOf("$mainUrl/" to "Programlar")
        .filter { Settings.rowEnabled(it.name) }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data, headers = headers).document
        return newHomePageResponse(request.name, doc.cards(), hasNext = false)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (page > 1) return newSearchResponseList(emptyList(), false)
        val doc = app.get(mainUrl, headers = headers).document
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
        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("$name: başlık okunamadı. $REPORT_HINT")
        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?.takeIf { it.isNotEmpty() }

        if (EPISODE.containsMatchIn(pageUrl)) {
            return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        val episodes = doc.select("a[href*='-bolum']").mapNotNull { card ->
            val href = fixUrlNull(card.attr("href")) ?: return@mapNotNull null
            if (!href.startsWith(pageUrl.trimEnd('/') + "/")) return@mapNotNull null
            val match = EPISODE.find(href) ?: return@mapNotNull null
            newEpisode(href) {
                this.name = card.text().trim().ifBlank { "Bölüm ${match.groupValues[2]}" }
                this.season = match.groupValues[1].toIntOrNull()
                this.episode = match.groupValues[2].toIntOrNull()
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
            ?: throw ErrorLoadingException("$name: bölüm sayfası yüklenemedi. $REPORT_HINT")
        val code = VIDEO_CODE.find(html)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("$name: video kodu bulunamadı. $REPORT_HINT")
        val info = runCatching {
            JSONObject(app.get("$mainUrl/player/info", params = mapOf("referenceId" to code), headers = headers).text)
        }.getOrNull() ?: throw ErrorLoadingException("$name: player bilgisi okunamadı. $REPORT_HINT")
        val hls = info.optJSONObject("video")?.optJSONObject("data")?.optJSONObject("flavors")
            ?.optString("hls")?.trim()
            ?.takeIf { it.startsWith("http") }
            ?: throw ErrorLoadingException("$name: HLS adresi bulunamadı. $REPORT_HINT")
        callback(newExtractorLink(name, name, hls, ExtractorLinkType.M3U8) {
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
            val title = card.selectFirst("img")?.attr("alt")?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
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
        private val EPISODE = Regex("""(\d+)-sezon-(\d+)-bolum""")
        private val VIDEO_CODE = Regex("""data-video-code="([^"]+)"""")
        private val STATIC = setOf("arama", "yayin-akisi", "canli-yayin", "blog")
    }
}
