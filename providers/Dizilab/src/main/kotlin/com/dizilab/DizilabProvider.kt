package com.dizilab

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

/**
 * Dizilab — dizilab.cc.
 *
 * Arama `/?s=<sorgu>`; detay bölüm linkleri `/bolum/<slug>-<sezon>-sezon-<bolum>-bolum/`.
 * Bölüm sayfasındaki `ksdpictures.site/dzembed.php` iframe'i doğrudan master.m3u8 taşır.
 */
class DizilabProvider : MainAPI() {
    override var mainUrl = "https://dizilab.cc"
    override var name = "Dizilab"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9",
    )

    override val mainPage get() = listOf(
        MainPageData("Diziler", "$mainUrl/diziler"),
    )

    private fun Element.toCard(): SearchResponse? {
        val href = fixUrlNull(selectFirst("a[href*=/dizi/]")?.attr("href")) ?: return null
        val title = selectFirst("h3")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val poster = fixUrlNull(selectFirst("img")?.attr("src"))
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
        val doc = app.get(request.data, headers = headers).document
        return newHomePageResponse(request.name, doc.select("article.card").mapNotNull { it.toCard() }, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=${query.trim()}", headers = headers).document
        return doc.select("article.card").mapNotNull { it.toCard() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = fixUrl(url)
        val doc = app.get(pageUrl, headers = headers).document
        val title = doc.selectFirst("h1")?.text()?.trim()?.removeSuffix(" izle")?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("$name: başlık okunamadı. $REPORT_HINT")
        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?.takeIf { it.isNotEmpty() }

        val episodes = doc.select("a[href*=/bolum/]").mapNotNull { anchor ->
            val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            val match = EPISODE.find(href) ?: return@mapNotNull null
            val season = match.groupValues[1].toIntOrNull()
            val number = match.groupValues[2].toIntOrNull()
            newEpisode(href) {
                this.season = season
                this.episode = number
                this.name = "S${season ?: "?"} B${number ?: "?"}"
            }
        }.distinctBy { it.data }

        if (episodes.isEmpty()) {
            throw ErrorLoadingException("$name: bölüm bulunamadı. $REPORT_HINT")
        }
        return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
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
        val doc = app.get(pageUrl, headers = headers).document
        val frames = doc.select("iframe[src]").mapNotNull { fixUrlNull(it.attr("src")) }
            .filter { it.startsWith("http") }
            .distinct()

        var found = false
        for (frame in frames) {
            val inner = runCatching { app.get(frame, headers = headers, referer = pageUrl).text }
                .getOrNull() ?: continue
            val streams = Regex("""https?://[^"'\s<>]+?\.m3u8(?:\?[^"'\s<>]*)?""")
                .findAll(inner.replace("\\/", "/"))
                .map { it.value }
                .distinct()
            for (stream in streams) {
                callback.invoke(
                    newExtractorLink(name, name, stream, ExtractorLinkType.M3U8) {
                        this.referer = frame
                        this.headers = headers
                        this.quality = Qualities.Unknown.value
                    },
                )
                found = true
            }
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir akış yok. $REPORT_HINT")
        return true
    }

    companion object {
        val ROWS = listOf("Diziler")
        private val EPISODE = Regex("""-(\d+)-sezon-(\d+)-bolum""")
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}
