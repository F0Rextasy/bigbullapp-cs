package com.showtv

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
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Show TV — showtv.com.tr dizi ve program arşivi.
 *
 * Arama `/arama?ara=<sorgu>`; diziler `/dizi/tanitim/<slug>/<id>`, bölümler
 * `/dizi/tum_bolumler/...-izle/<id>`. Bölüm sayfasındaki gömülü oynatıcı
 * yapılandırmasında doğrudan HLS/MP4 adresi bulunur.
 */
private fun Element.posterSrcLazy(): String? =
    attr("data-src").takeIf { it.isNotBlank() } ?: attr("src")

class ShowTVProvider : MainAPI() {
    override var mainUrl = "https://www.showtv.com.tr"
    override var name = "ShowTV"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val headers = mapOf(
        "User-Agent" to UA,
        "Accept-Language" to "tr-TR,tr;q=0.9",
    )

    override val mainPage get() = mainPageOf(
        "$mainUrl/diziler" to "Diziler",
        "$mainUrl/programlar" to "Programlar",
    ).filter { Settings.rowEnabled(it.name) }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data, headers = headers).document
        return newHomePageResponse(request.name, doc.cards(), hasNext = false)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (page > 1) return newSearchResponseList(emptyList(), false)
        val doc = app.get("$mainUrl/arama", params = mapOf("ara" to query.trim()), headers = headers).document
        return newSearchResponseList(doc.cards(), false)
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = fixUrl(url)
        val doc = runCatching { app.get(pageUrl, headers = headers).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: içerik sayfası yüklenemedi. $REPORT_HINT")
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore("|")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("$name: başlık okunamadı. $REPORT_HINT")
        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?.takeIf { it.isNotEmpty() }
        val year = YEAR.find(doc.selectFirst("meta[property=article:published_time]")?.attr("content").orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()
        val episodes = doc.select("a[href*='/tum_bolumler/']").mapNotNull { card ->
            val href = fixUrlNull(card.attr("href")) ?: return@mapNotNull null
            val number = EPISODE.find(href)?.groupValues?.get(1)?.toIntOrNull()
            newEpisode(href) {
                this.name = card.text().trim().ifBlank { "Bölüm ${number ?: ""}" }
                this.episode = number
                this.posterUrl = fixUrlNull(card.selectFirst("img")?.posterSrcLazy())
            }
        }.distinctBy { it.data }.sortedBy { it.episode ?: 0 }
        if (episodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
        return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageUrl = fixUrl(data)
        val raw = runCatching { app.get(pageUrl, headers = headers).text }.getOrNull()
            ?: throw ErrorLoadingException("$name: bölüm sayfası yüklenemedi. $REPORT_HINT")
        val html = raw.replace("&quot;", "\"").replace("&amp;", "&")
        val hls = HLS.find(html)?.groupValues?.get(0)
        if (hls != null) {
            callback(newExtractorLink(name, name, hls, ExtractorLinkType.M3U8) {
                this.referer = pageUrl
                this.headers = headers
                this.quality = Qualities.Unknown.value
            })
            return true
        }
        val mp4 = MP4.find(html)?.groupValues?.get(0)
        if (mp4 != null) {
            callback(newExtractorLink(name, name, mp4, ExtractorLinkType.VIDEO) {
                this.referer = pageUrl
                this.headers = headers
                this.quality = Qualities.Unknown.value
            })
            return true
        }
        throw ErrorLoadingException("$name: oynatılabilir akış yok. $REPORT_HINT")
    }

    private fun Document.cards(): List<SearchResponse> =
        select("a[href*='/dizi/tanitim/'], a[href*='/programlar/tanitim/']").mapNotNull { card ->
            val href = fixUrlNull(card.attr("href")) ?: return@mapNotNull null
            val title = card.attr("title").trim().ifBlank {
                card.selectFirst("span.text-base")?.text()?.trim().orEmpty()
            }.ifBlank {
                card.selectFirst("h2, h3, h4")?.text()?.trim().orEmpty()
            }.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val isSeries = href.contains("/dizi/")
            val type = if (isSeries) TvType.TvSeries else TvType.TvSeries
            val poster = fixUrlNull(card.selectFirst("img")?.posterSrcLazy())
            if (isSeries) newTvSeriesSearchResponse(title, href, type) {
                this.posterUrl = poster
            } else newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }

    companion object {
        val ROWS = listOf("Diziler", "Programlar")
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
        private val YEAR = Regex("""\b((?:19|20)\d{2})\b""")
        private val EPISODE = Regex("""bolum-(\d+)""")
        private val HLS = Regex("""https?://[^"'\\ ]+\.m3u8[^"'\\ ]*""")
        private val MP4 = Regex("""https?://[^"'\\ ]+\.mp4[^"'\\ ]*""")
    }
}
