package com.minikago

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
import org.jsoup.nodes.Document

/**
 * MinikaGO — minikago.com.tr çocuk VOD'u.
 *
 * `/webtv` ve `/arama` bölüm sayfalarını listeler; bölüm sayfası doğrudan `..._1080p_800k.mp4`
 * biçiminde CDN adresi taşır. Ayrı bir oynatıcı çözümlemesi gerekmez.
 */
class MinikaGOProvider : MainAPI() {
    override var mainUrl = "https://www.minikago.com.tr"
    override var name = "MinikaGO"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage get() = ROWS.mapNotNull { row ->
        if (row == "Videolar" && Settings.rowEnabled(row)) {
            MainPageData(row, "$mainUrl/webtv")
        } else null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val doc = app.get(request.data).document
        val items = doc.select("a[href*='/bolum/']").mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/arama", params = mapOf("q" to query.trim())).document
        val needle = normalize(query)
        return doc.select("div.search-item").mapNotNull { item ->
            val title = item.selectFirst("h2.item-content-title")?.text()?.trim() ?: return@mapNotNull null
            if (needle.isNotBlank() && !normalize(title).contains(needle)) return@mapNotNull null
            if (SKIP.containsMatchIn(title)) return@mapNotNull null
            val url = item.selectFirst("a[href*='/bolum/']")?.attr("href")
                ?.let { fixUrlNull(it) } ?: return@mapNotNull null
            newMovieSearchResponse(title, url, TvType.Movie)
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: bölüm adı okunamadı. $REPORT_HINT")
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrlNull(it) }
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
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
        val html = app.get(data, referer = "$mainUrl/").text.replace("\\/", "/")
        val links = MP4.findAll(html).map { it.value }.distinct().toList()
        if (links.isEmpty()) throw ErrorLoadingException("$name: bölüm videosu bulunamadı. $REPORT_HINT")
        var emitted = 0
        for (link in links) {
            val quality = QUALITY.find(link)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            if (!Settings.qualityAllowed(quality)) continue
            callback(newExtractorLink(name, if (quality > 0) "$name ${quality}p" else name, link, ExtractorLinkType.VIDEO) {
                this.referer = "$mainUrl/"
                this.quality = quality
                this.headers = mapOf("User-Agent" to UA)
            })
            emitted++
        }
        if (emitted == 0) throw ErrorLoadingException("$name: kalite ayarına uygun video yok. $REPORT_HINT")
        return true
    }

    private fun org.jsoup.nodes.Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val title = text().trim().takeIf { it.isNotBlank() } ?: return null
        if (SKIP.containsMatchIn(title)) return null
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = selectFirst("img")?.attr("src")?.let { fixUrlNull(it) }
        }
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
        .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')

    companion object {
        val ROWS = listOf("Videolar")
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private val MP4 = Regex("""https?://[^\s"'<>\\]+?\.mp4[^\s"'<>\\]*""")
        private val QUALITY = Regex("""[_-](\d{3,4})p[_-]""")
        private val SKIP = Regex("(?i)(tanıtım|tanitim|yakında|yakinda|fragman)")
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}
