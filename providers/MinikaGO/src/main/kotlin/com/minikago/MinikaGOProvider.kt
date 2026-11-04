package com.minikago

import com.bigbull.common.browserHeaders
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
    private val uaHeaders = browserHeaders + mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "X-Requested-With" to "XMLHttpRequest",
    )

    override val mainPage get() = ROWS.mapNotNull { row ->
        if (row == "Videolar" && Settings.rowEnabled(row)) {
            MainPageData(row, "$mainUrl/webtv")
        } else null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val doc = app.get(request.data, headers = uaHeaders).document
        // Bölüm bağlantısı kartın görselinde; başlık ayrı showcase-text bağlantısında durur.
        val items = doc.select("div.showcase-item").mapNotNull { item ->
            val url = item.selectFirst("div.showcase-image a[href*='/bolum/']")?.attr("href")
                ?.let { fixUrlNull(it) } ?: return@mapNotNull null
            val title = item.selectFirst("a.showcase-text span")?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (SKIP.containsMatchIn(title)) return@mapNotNull null
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = item.selectFirst("div.showcase-image img")?.attr("src")
                    ?.let { fixUrlNull(it) }
            }
        }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim()
        if (term.isBlank()) return emptyList()
        val doc = searchPage(term) ?: return emptyList()
        val needle = normalize(term)
        return doc.select("div.search-item").mapNotNull { item ->
            val title = item.selectFirst("h2.item-content-title")?.text()?.trim() ?: return@mapNotNull null
            if (!normalize(title).contains(needle)) return@mapNotNull null
            if (SKIP.containsMatchIn(title)) return@mapNotNull null
            val url = item.selectFirst("a[href*='/bolum/']")?.attr("href")
                ?.let { fixUrlNull(it) } ?: return@mapNotNull null
            newMovieSearchResponse(title, url, TvType.Movie)
        }.distinctBy { it.url }
    }

    /** Site /arama 500 dönebildiği için query= birincil, q= yedek; ikisi de düşerse hata bildirilir. */
    private suspend fun searchPage(term: String): Document? {
        var reachable = false
        for (param in listOf("query", "q")) {
            val doc = try {
                app.get("$mainUrl/arama", params = mapOf(param to term), headers = uaHeaders)
                    .document.also { reachable = true }
            } catch (_: Exception) {
                continue
            }
            if (doc.selectFirst("div.search-item") != null) return doc
        }
        if (!reachable) throw ErrorLoadingException("$name: arama yanıt vermedi. $REPORT_HINT")
        return null
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = if (url.endsWith("/")) url else "$url/"
        val doc = app.get(pageUrl, headers = uaHeaders).document
        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: bölüm adı okunamadı. $REPORT_HINT")
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrlNull(it) }
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
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
        val pageUrl = if (data.endsWith("/")) data else "$data/"
        val html = app.get(pageUrl, headers = uaHeaders, referer = "$mainUrl/").text.replace("\\/", "/")
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


    private fun normalize(value: String): String = value.lowercase()
        .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
        .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')

    companion object {
        val ROWS = listOf("Videolar")
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        private val MP4 = Regex("""https?://[^\s"'<>\\]+?\.mp4[^\s"'<>\\]*""")
        private val QUALITY = Regex("""[_-](\d{3,4})p[_-]""")
        private val SKIP = Regex("(?i)(tanıtım|tanitim|yakında|yakinda|fragman)")
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}
