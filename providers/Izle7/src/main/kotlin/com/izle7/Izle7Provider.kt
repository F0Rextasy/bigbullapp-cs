package com.izle7

import com.bigbull.common.browserHeaders
import com.bigbull.common.jsonLdDescription
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
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URLEncoder
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * İzle7 — Kanal 7 resmi arşivi (izle7.com).
 * Dizi sayfası `/kanal7/kategori/<slug>` sayfa başına 32 bölüm listeler; bölüm/film sayfası
 * `/kanal7/izle-<id>-<slug>.html` Dailymotion kimliğini `play_video` değişkeninde taşır.
 */
class Izle7Provider : MainAPI() {
    override var mainUrl = "https://www.izle7.com"
    override var name = "İzle7"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val uaHeaders get() = browserHeaders

    override val mainPage get() = ROWS.mapNotNull { row ->
        if (!Settings.rowEnabled(row)) return@mapNotNull null
        when (row) {
            "Diziler" -> MainPageData(row, "$mainUrl/kanal7/diziler")
            "Hint Dizileri" -> MainPageData(row, "$mainUrl/kanal7/hint-dizileri")
            "TV Filmleri" -> MainPageData(row, "$mainUrl/kanal7/filmler?page=SAYFA")
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data.replace("SAYFA", page.toString()), headers = uaHeaders).document
        return newHomePageResponse(
            request.name,
            doc.select(CARD).mapNotNull { it.toSearchResult() }.distinctBy { it.url },
            hasNext = doc.selectFirst(".pagination a.next") != null,
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/arama?q=$encoded", headers = uaHeaders).document
        return doc.select(CARD).mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val title = titleText() ?: return null
        val poster = cardPoster()
        return if (href.contains("/kategori/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        }
    }

    /** Kartta gerçek görsel yoksa sitenin kullandığı Dailymotion önizlemesinden türetilir. */
    private fun Element.cardPoster(): String? {
        fixUrlNull(selectFirst("img")?.posterSrcLazy())?.let { return it }
        val id = (parent() ?: this).selectFirst("[data-type=dm-video-preview][data-id]")
            ?.attr("data-id")?.trim().orEmpty()
        return id.takeIf { it.isNotEmpty() }?.let { "$DM_THUMB$it" }
    }

    private fun Element.titleText(): String? =
        selectFirst(".category-item-title")?.text()?.trim()?.ifBlank { null }
            ?: attr("title").trim().ifBlank { null }

    private fun pageTitle(doc: Document): String? =
        doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?.trim()?.removeSuffix(" - izle7")?.ifBlank { null }

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url, headers = uaHeaders).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        if (!url.contains("/kategori/")) {
            val title = pageTitle(doc)
                ?: throw ErrorLoadingException("$name: başlık okunamadı. $REPORT_HINT")
            return movieLoad(url, title, doc)
        }
        // Kategori sayfası adı sitede bazen slug hâlinde ("asiye") duruyor.
        val title = doc.title().substringBefore(" Kategorisindeki").trim().ifBlank { null }
            ?.replaceFirstChar { it.uppercaseChar() }
            ?: pageTitle(doc) ?: throw ErrorLoadingException("$name: başlık okunamadı. $REPORT_HINT")

        val episodes = ArrayList<Episode>()
        var poster: String? = null
        var next: String? = url
        var pages = 0
        while (next != null && pages < EPISODE_PAGES) {
            val pageUrl = next
            val pageDoc = if (pages == 0) doc else runCatching {
                app.get(pageUrl, headers = uaHeaders).document
            }.getOrNull() ?: break
            for (card in pageDoc.select(CARD)) {
                val href = fixUrlNull(card.attr("href")) ?: continue
                val cardTitle = card.titleText() ?: continue
                // Numarasız program videoları ("Kalp Gözü - Kaçış") bölüm numarası olmadan eklenir.
                val number = EPISODE_NUMBER.find(cardTitle)?.groupValues?.get(1)?.toIntOrNull()
                val cardPoster = card.cardPoster()
                if (poster == null) poster = cardPoster
                episodes.add(newEpisode(href) {
                    this.name = cardTitle
                    this.season = 1
                    this.episode = number
                    this.posterUrl = cardPoster
                })
            }
            next = pageDoc.selectFirst(".pagination a.next")
                ?.let { fixUrlNull(it.attr("href")) }
                ?.takeIf { it.contains("page=") }
            pages++
        }
        if (episodes.isEmpty()) throw ErrorLoadingException("$name: bölüm bulunamadı. $REPORT_HINT")

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes.distinctBy { it.data }.let { list ->
                if (list.all { it.episode != null }) list.sortedBy { it.episode } else list
            },
        ) {
            val seriesPoster = doc.selectFirst("main a.content-box img")?.posterSrcLazy()
                ?.let { fixUrlNull(it) } ?: poster
            this.posterUrl = seriesPoster
            this.backgroundPosterUrl = seriesPoster
        }
    }

    private suspend fun movieLoad(url: String, title: String, doc: Document) = newMovieLoadResponse(
        title,
        url,
        TvType.Movie,
        url,
    ) {
        val schema = schemaText(doc)
        this.posterUrl = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        this.plot = doc.jsonLdDescription()
        this.year = YEAR.find(schema)?.groupValues?.get(1)?.toIntOrNull()
        this.duration = DURATION.find(schema)?.destructured?.let { (h, m) ->
            (h.toIntOrNull() ?: 0) * 60 + (m.toIntOrNull() ?: 0)
        }?.takeIf { it > 0 }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = runCatching { app.get(data, headers = uaHeaders).text }
            .getOrNull() ?: throw ErrorLoadingException("$name: oynatıcı sayfası yüklenemedi. $REPORT_HINT")
        val videoId = PLAY_VIDEO.find(html)?.groupValues?.get(1)
            ?: DM_URL.find(html)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("$name: Dailymotion kimliği bulunamadı. $REPORT_HINT")

        var found = false
        loadExtractor(
            "https://www.dailymotion.com/video/$videoId",
            data,
            { if (Settings.subtitleAllowed(it.lang)) subtitleCallback(it) },
            {
                if (Settings.qualityAllowed(it.quality)) {
                    callback(it)
                    found = true
                }
            },
        )
        if (!found) throw ErrorLoadingException("$name: oynatılabilir kaynak yok. $REPORT_HINT")
        return true
    }

    /** JSON-LD `VideoObject` bloğu; süre ve yayın yılı buradan okunur. */
    private fun schemaText(doc: Document): String =
        doc.select("script[type=application/ld+json]").joinToString(" ") { it.data() }

    companion object {
        val ROWS = listOf("Diziler", "Hint Dizileri", "TV Filmleri")

        /** İzle7 kategori sayfaları yavaş (2–20 sn) yanıt veriyor; tüm arşiv load'ı kilitler. */
        private const val EPISODE_PAGES = 3
        private const val CARD = "main a.category-item"
        private const val DM_THUMB = "https://www.dailymotion.com/thumbnail/video/"
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"

        private val EPISODE_NUMBER = Regex("""(\d+)\.\s*B[öo]l[üu]m""", RegexOption.IGNORE_CASE)
        private val PLAY_VIDEO = Regex("""play_video\s*=\s*["']([\w-]+)["']""")
        private val DM_URL = Regex("""dailymotion\.com/(?:crawler/)?video/([\w-]+)""")
        private val YEAR = Regex("\"(?:datePublished|uploadDate)\"\\s*:\\s*\"(\\d{4})")
        private val DURATION = Regex("\"duration\"\\s*:\\s*\"PT(?:(\\d+)H)?(?:(\\d+)M)?")
    }
}
