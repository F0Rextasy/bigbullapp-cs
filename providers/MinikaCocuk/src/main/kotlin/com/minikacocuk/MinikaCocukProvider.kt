package com.minikacocuk

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
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

private fun Element.posterSrcLazy(): String? =
    attr("data-src").takeIf { it.isNotBlank() } ?: attr("src")

class MinikaCocukProvider : MainAPI() {
    override var mainUrl = "https://www.minikacocuk.com.tr"
    override var name = "MinikaCocuk"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val uaHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9",
    )

    override val mainPage get() = ROWS.mapNotNull { name ->
        if (!Settings.rowEnabled(name)) return@mapNotNull null
        when (name) {
            "Web TV" -> MainPageData(name, "$mainUrl/webtv")
            "Canlı Yayın" -> MainPageData(name, "$mainUrl/webtv/canli-yayin")
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data, headers = uaHeaders).document
        val items = doc.select("div.showcase").mapNotNull { card ->
            val link = card.selectFirst("a.showcase-content[href]") ?: return@mapNotNull null
            val href = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
            val title = card.selectFirst(".showcase-lightbox-title")?.text()?.trim()
                ?: link.attr("title").trim().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = fixUrlNull(card.selectFirst("img")?.posterSrcLazy())
            }
        }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim().lowercase()
        if (term.isBlank()) return emptyList()
        val doc = app.get("$mainUrl/webtv", headers = uaHeaders).document
        return doc.select("div.showcase").mapNotNull { card ->
            val link = card.selectFirst("a.showcase-content[href]") ?: return@mapNotNull null
            val href = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
            val title = card.selectFirst(".showcase-lightbox-title")?.text()?.trim()
                ?: return@mapNotNull null
            if (!title.lowercase().contains(term) && !href.lowercase().contains(term)) return@mapNotNull null
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = fixUrlNull(card.selectFirst("img")?.posterSrcLazy())
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val page = app.get(url, headers = uaHeaders)
        val doc = page.document
        val title = doc.selectFirst("h2.title")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: throw ErrorLoadingException("$name: başlık okunamadı")
        val poster = fixUrlNull(
            doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("img[data-src]")?.posterSrcLazy()
        )
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
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
        val html = app.get(data, headers = uaHeaders).text
            .replace("\\/", "/")
            .replace("&quot;", "\"")
        val content = Regex("""contentUrl["']?\s*:\s*["']([^"']+)""")
            .findAll(html).map { it.groupValues[1].replace("\\/", "/").replace("\\u002F", "/") }
            .firstOrNull { it.startsWith("http") && (it.contains(".mp4") || it.contains(".m3u8")) }
        val streams = mutableSetOf<String>()
        if (!content.isNullOrBlank() && content.startsWith("http")) streams.add(content)
        var found = false
        for (stream in streams) {
            callback.invoke(newExtractorLink(name, "Minika", stream, ExtractorLinkType.VIDEO) {
                this.referer = data
            })
            found = true
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir akış yok")
        return true
    }

    companion object {
        val ROWS = listOf("Web TV", "Canlı Yayın")
    }
}
