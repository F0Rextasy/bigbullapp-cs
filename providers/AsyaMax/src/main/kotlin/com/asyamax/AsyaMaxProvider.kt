package com.asyamax

import com.bigbull.common.browserHeaders
import com.bigbull.common.posterSrcLazy
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
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
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import java.net.URLEncoder

/** Bölüm listesindeki bağlantı metni: "3. BÖLÜM", "2. Sezon 7. Bölüm", "12. BÖLÜM (Final)". */
private val EPISODE_TEXT = Regex(
    """^[\s\u00A0]*(\d+)[\s\u00A0]*\.[\s\u00A0]*(?:Sezon[\s\u00A0]*(\d+)[\s\u00A0]*\.[\s\u00A0]*)?B[ÖO]L[ÜU]M""",
    RegexOption.IGNORE_CASE,
)
private val EPISODE_IN_TITLE = Regex("""\bB[ÖO]L[ÜU]M\b""", RegexOption.IGNORE_CASE)
private val YEAR = Regex("""\b(?:19|20)\d{2}\b""")
private val MEDIA_RX = Regex("""https?://[^"'\s<>\\]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>\\]*)?""")


private fun sameUrl(a: String, b: String) = a.trimEnd('/') == b.trimEnd('/')

class AsyaMaxProvider : MainAPI() {
    override var mainUrl = "https://asyamax.com"
    override var name = "AsyaMax"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.AsianDrama)

    private fun absolute(raw: String): String = when {
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("http") -> raw
        else -> fixUrl(raw)
    }

    override val hasMainPage = true
    override val mainPage get() = ROWS.mapNotNull { (label, slug) ->
        if (!Settings.rowEnabled(label)) null else MainPageData(label, "$mainUrl/category/$slug/")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val doc = app.get(url, headers = browserHeaders).document
        val movieRow = request.data.endsWith("/filmler/")
        val hasNext = doc.selectFirst("a[href*='/page/${page + 1}/']") != null
        return newHomePageResponse(request.name, cards(doc, movieRow), hasNext = hasNext)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val term = URLEncoder.encode(query.trim(), "UTF-8")
        val url = if (page <= 1) "$mainUrl/?s=$term" else "$mainUrl/page/$page/?s=$term"
        val doc = runCatching { app.get(url, headers = browserHeaders).document }.getOrNull()
            ?: return newSearchResponseList(emptyList(), false)
        val items = cards(doc, null)
        val hasNext = doc.selectFirst("a[href*='/page/${page + 1}/']") != null
        return newSearchResponseList(items, hasNext)
    }

    /** Liste/sayfalama kartları. [movieRow] null ise tür başlıktan tahmin edilir; gerçek tür load() sırasında belirlenir. */
    private fun cards(doc: Document, movieRow: Boolean?): List<SearchResponse> {
        val list = mutableListOf<SearchResponse>()
        for (card in doc.select(".post-container")) {
            val anchor = card.selectFirst(".detail .title a") ?: continue
            val href = fixUrlNull(anchor.attr("href"))?.takeIf { it.startsWith(mainUrl) } ?: continue
            val title = anchor.text().trim().takeIf { it.isNotBlank() } ?: continue
            val posterUrl = fixUrlNull(card.selectFirst("img")?.posterSrcLazy())
            if (movieRow ?: !EPISODE_IN_TITLE.containsMatchIn(title)) {
                list.add(newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl })
            } else {
                list.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl })
            }
        }
        return list.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = browserHeaders).document
        val rootUrl = doc.select("a[href]").firstOrNull { it.text().trim() == "Dizi Sayfası" }
            ?.let { fixUrlNull(it.attr("href")) }
        val rootDoc = rootUrl?.takeIf { !sameUrl(it, url) }
            ?.let { runCatching { app.get(it, headers = browserHeaders).document }.getOrNull() }
        val fromEpisode = rootDoc != null
        val info = rootDoc ?: doc
        val pageUrl = if (fromEpisode) rootUrl!! else url
        val title = (info.selectFirst("h1.title")?.text() ?: info.selectFirst("meta[property=og:title]")?.attr("content"))
            ?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: başlık okunamadı")
        val posterUrl = fixUrlNull(info.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = info.selectFirst("meta[property=og:description]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
        val year = YEAR.find(title)?.value?.toIntOrNull()

        var episodes = episodesOf(info)
        if (fromEpisode && episodes.none { sameUrl(it.data, url) }) {
            val episodeTitle = doc.selectFirst("h1.title")?.text()?.trim().orEmpty()
            val parsed = EPISODE_TEXT.find(episodeTitle)
            episodes = episodes + newEpisode(url) {
                this.name = episodeTitle
                this.episode = parsed?.groupValues?.get(1)?.toIntOrNull()
                this.season = parsed?.groupValues?.get(2)?.toIntOrNull()
            }
        }
        if (episodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes.sortedWith(
                compareBy({ it.season ?: 0 }, { it.episode ?: 0 }),
            )) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
            }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.year = year
        }
    }

    /** Dizi sayfasındaki "1. BÖLÜM" bağlantıları; ilgili bölüm widget'ı kendi metniyle ayırt edilir. */
    private fun episodesOf(doc: Document) = doc.select("a[href]").mapNotNull { anchor ->
        val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
        if (!href.contains("bolum")) return@mapNotNull null
        val text = anchor.text().trim()
        val parsed = EPISODE_TEXT.find(text) ?: return@mapNotNull null
        newEpisode(href) {
            this.name = text
            this.episode = parsed.groupValues[1].toIntOrNull()
            this.season = parsed.groupValues[2].toIntOrNull()
        }
    }.distinctBy { it.data }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val doc = app.get(data, headers = browserHeaders).document
        val scope = doc.selectFirst("#singleContent") ?: doc
        val seen = mutableSetOf<String>()
        var found = false
        var emitted = false
        val subCallback: (SubtitleFile) -> Unit = { if (Settings.subtitleAllowed(it.lang)) subtitleCallback(it) }

        suspend fun emitDirect(stream: String, label: String) {
            if (!stream.startsWith("http") || !seen.add(stream)) return
            val isM3u8 = stream.contains(".m3u8")
            val quality = if (isM3u8) Qualities.Unknown.value else getQualityFromName(label)
            found = true
            if (!Settings.qualityAllowed(quality)) return
            callback(newExtractorLink(name, label, stream, if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.referer = data
                this.quality = quality
            })
            emitted = true
        }

        for (match in MEDIA_RX.findAll(scope.html().replace("&amp;", "&"))) emitDirect(match.value, "Direct")

        val frames = scope.select("iframe").mapNotNull { frame ->
            absolute(frame.attr("data-src").ifBlank { frame.attr("src") }.trim())
        }.filter { it.startsWith("http") && !it.startsWith(mainUrl) }.distinct()

        for (frame in frames) {
            val host = frame.substringAfter("://").substringBefore('/')
            if (frame.contains(".m3u8") || frame.contains(".mp4")) {
                emitDirect(frame, host)
                continue
            }
            var frameLinks = 0
            loadExtractor(frame, "$mainUrl/", subCallback) { link ->
                frameLinks++
                found = true
                if (seen.add(link.url) && Settings.qualityAllowed(link.quality)) {
                    callback(link)
                    emitted = true
                }
            }
            if (frameLinks > 0) continue
            // vidoza/mixdrop gibi hostlar doğrudan medyayı yalnız embed sayfasında bırakır.
            val html = runCatching { app.get(frame, headers = browserHeaders, referer = data).text }.getOrNull()
            for (match in MEDIA_RX.findAll(html.orEmpty().replace("&amp;", "&"))) emitDirect(match.value, host)
        }
        if (!found || !emitted) throw ErrorLoadingException("$name: oynatılabilir kaynak yok")
        return true
    }

    companion object {
        val ROWS = listOf(
            "Son Eklenen Bölümler" to "yeni-eklenen-bolumler",
            "Kore Dizileri" to "kore-dizileri-2",
            "Çin Dizileri" to "cin-dizileri-3",
            "Japon Dizileri" to "japon-dizileri-2",
            "Tayland Dizileri" to "tayland-dizileri-2",
            "Tayvan Dizileri" to "tayvan-dizileri-2",
            "Filmler" to "filmler",
        )
    }
}
