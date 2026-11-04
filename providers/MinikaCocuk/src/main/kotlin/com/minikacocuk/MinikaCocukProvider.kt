package com.minikacocuk

import com.bigbull.common.browserHeaders
import com.bigbull.common.posterSrcLazy
import com.lagradost.cloudstream3.Episode
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
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

private fun String.folded(): String = lowercase()
    .replace("\u0307", "")
    .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
    .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')

private val CONTENT_URL = Regex("""contentUrl["']?\s*:\s*["']([^"']+)""")
private val SEASON_EPISODE = Regex("""(\d+)\.\s*Sezon\s*(\d+)\.\s*Bölüm""", RegexOption.IGNORE_CASE)

private fun contentUrls(html: String): List<String> =
    CONTENT_URL.findAll(html).map { it.groupValues[1].replace("\\u002F", "/") }.toList()

private fun String.isStreamUrl(): Boolean = contains(".mp4") || contains(".m3u8")

class MinikaCocukProvider : MainAPI() {
    override var mainUrl = "https://www.minikacocuk.com.tr"
    override var name = "MinikaCocuk"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val uaHeaders = browserHeaders + mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "X-Requested-With" to "XMLHttpRequest",
    )

    override val mainPage get() = ROWS.mapNotNull { name ->
        if (!Settings.rowEnabled(name)) return@mapNotNull null
        when (name) {
            "Web TV" -> MainPageData(name, "$mainUrl/webtv")
            "Programlar" -> MainPageData(name, "$mainUrl/programlar")
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val type = if (request.name == "Programlar") TvType.TvSeries else TvType.Movie
        val items = app.get(request.data, headers = uaHeaders).document
            .select("div.showcase").mapNotNull { it.toSearchResult(type) }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val needle = query.folded().trim()
        if (needle.isBlank()) return emptyList()
        return listOf("$mainUrl/webtv" to TvType.Movie, "$mainUrl/programlar" to TvType.TvSeries)
            .flatMap { (pageUrl, type) ->
                app.get(pageUrl, headers = uaHeaders).document.select("div.showcase").mapNotNull { card ->
                    card.toSearchResult(type)?.takeIf {
                        it.name.folded().contains(needle) || it.url.folded().contains(needle)
                    }
                }
            }.distinctBy { it.url }
    }

    private fun Element.toSearchResult(type: TvType): SearchResponse? {
        val link = selectFirst("a.showcase-content[href]") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        val title = selectFirst(".showcase-txt-title, .showcase-lightbox-title")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: link.attr("title").trim().takeIf { it.isNotBlank() }
            ?: return null
        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = fixUrlNull(selectFirst("img")?.posterSrcLazy())
        }
    }

    private fun Element.toEpisode(): Episode? {
        val link = selectFirst("a.showcase-content[href]") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        if (!href.contains("/bolum/")) return null
        val raw = selectFirst(".showcase-txt-title")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val match = SEASON_EPISODE.find(raw)
        return newEpisode(href) {
            this.name = raw.substringAfter("|").trim().ifBlank { raw }
            this.season = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
            this.episode = match?.groupValues?.get(2)?.toIntOrNull()
            this.posterUrl = fixUrlNull(selectFirst("img")?.posterSrcLazy())
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val page = app.get(url, headers = uaHeaders)
        val doc = page.document
        val html = page.text.replace("\\/", "/")
        val urls = contentUrls(html)
        val title = doc.selectFirst("h2.title, h1.category-title")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: başlık okunamadı")
        if (urls.none { it.isStreamUrl() }) {
            val episodes = doc.select("div.showcase").mapNotNull { it.toEpisode() }.distinctBy { it.data }
            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = episodes.firstOrNull { it.posterUrl != null }?.posterUrl
                }
            }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = fixUrlNull(urls.firstOrNull { !it.isStreamUrl() })
                ?: fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
            this.plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = app.get(data, headers = uaHeaders).text
            .replace("\\/", "/")
            .replace("&quot;", "\"")
        val stream = contentUrls(html).firstOrNull { it.startsWith("http") && it.isStreamUrl() }
            ?: throw ErrorLoadingException("$name: oynatılabilir akış yok")
        callback(newExtractorLink(name, "Minika", stream, ExtractorLinkType.VIDEO) {
            this.referer = data
        })
        return true
    }

    companion object {
        val ROWS = listOf("Web TV", "Programlar")
    }
}
