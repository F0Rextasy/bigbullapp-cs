package com.atv

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
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

private fun String.folded(): String = lowercase()
    .replace("\u0307", "")
    .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
    .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')

class ATVProvider : MainAPI() {
    override var mainUrl = "https://www.atv.com.tr"
    override var name = "ATV"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)

    private val headers = browserHeaders + mapOf("Referer" to "$mainUrl/")

    override val mainPage get() = ROWS.mapNotNull { row ->
        if (!Settings.rowEnabled(row)) return@mapNotNull null
        when (row) {
            "Diziler" -> MainPageData(row, "$mainUrl/diziler")
            "Klasik Diziler" -> MainPageData(row, "$mainUrl/klasik-diziler")
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data, headers = headers).document
        val items = doc.seriesCards()
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val needle = query.folded().trim()
        if (needle.isBlank()) return emptyList()
        val results = LinkedHashMap<String, SearchResponse>()

        val searchUrl = "$mainUrl/arama?query=${URLEncoder.encode(query, "UTF-8")}"
        runCatching { app.get(searchUrl, headers = headers).document }.getOrNull()?.let { doc ->
            doc.select("a[href]").forEach { a ->
                val rawHref = a.attr("href").trim()
                val match = SEARCH_SLUG_REGEX.find(rawHref) ?: return@forEach
                val slug = match.groupValues[1]
                if (slug in EXCLUDED_SLUGS) return@forEach
                val foldedSlug = slug.replace('-', ' ').folded()
                if (slug in HEADER_NAV_SLUGS && !foldedSlug.contains(needle)) return@forEach
                val seriesUrl = "$mainUrl/$slug"
                if (results.containsKey(seriesUrl)) return@forEach
                val img = a.selectFirst("img") ?: a.parent()?.selectFirst("img")
                val rawText = a.text().trim()
                val title = rawText.substringBefore("1.").substringBefore("2.")
                    .substringBefore("3.").substringBefore("4.").substringBefore("5.")
                    .trim().takeIf { it.length in 2..60 && !it.startsWith("\"") }
                    ?: slug.split('-').joinToString(" ") { part ->
                        part.replaceFirstChar { it.uppercase() }
                    }
                results[seriesUrl] = newTvSeriesSearchResponse(title, seriesUrl, TvType.TvSeries) {
                    this.posterUrl = fixUrlNull(img?.posterSrcLazy())
                }
            }
        }

        listOf("$mainUrl/diziler", "$mainUrl/klasik-diziler").forEach { pageUrl ->
            runCatching { app.get(pageUrl, headers = headers).document }.getOrNull()
                ?.seriesCards()
                ?.filter { card ->
                    card.name.folded().contains(needle) || card.url.folded().contains(needle)
                }
                ?.forEach { card ->
                    results.putIfAbsent(card.url, card)
                }
        }

        return results.values.toList()
    }

    override suspend fun load(url: String): LoadResponse {
        val cleanUrl = fixUrl(url).trimEnd('/')
        val slug = SLUG_FROM_URL.find(cleanUrl)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("$name: dizi adresi çözülemedi")
        val seriesUrl = "$mainUrl/$slug"
        val bolumlerUrl = "$seriesUrl/bolumler"
        val doc = runCatching { app.get(bolumlerUrl, headers = headers).document }.getOrNull()
            ?: app.get(seriesUrl, headers = headers).document

        val rawTitle = doc.selectFirst("h1, h2.title")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore("-")?.substringBefore("|")?.trim()
            ?: slug.split('-').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        val title = rawTitle.replace(Regex("""\s*Bölümleri.*$""", RegexOption.IGNORE_CASE), "").trim()
            .ifEmpty { slug }

        val epRegex = Regex("""^/$slug/(\d+)-bolum/izle$""", RegexOption.IGNORE_CASE)
        val episodesByNum = LinkedHashMap<Int, Episode>()
        doc.select("a[href]").forEach { a ->
            val href = a.attr("href").trim()
            val match = epRegex.find(href) ?: return@forEach
            val epNum = match.groupValues[1].toIntOrNull() ?: return@forEach
            if (episodesByNum.containsKey(epNum)) return@forEach
            val img = a.selectFirst("img") ?: a.parent()?.selectFirst("img")
            val epUrl = fixUrl(href)
            episodesByNum[epNum] = newEpisode(epUrl) {
                this.name = "$epNum. Bölüm"
                this.season = 1
                this.episode = epNum
                this.posterUrl = fixUrlNull(img?.posterSrcLazy())
            }
        }

        if (episodesByNum.isEmpty()) {
            val probeUrl = "$seriesUrl/1-bolum/izle"
            val probeHtml = runCatching { app.get(probeUrl, headers = headers).text }.getOrNull().orEmpty()
            if (STREAM_REGEX.containsMatchIn(probeHtml)) {
                episodesByNum[1] = newEpisode(probeUrl) {
                    this.name = "1. Bölüm"
                    this.season = 1
                    this.episode = 1
                }
            }
        }

        val episodes = episodesByNum.entries.sortedBy { it.key }.map { it.value }
        if (episodes.isEmpty()) {
            throw ErrorLoadingException("$name: oynatılabilir bölüm bulunamadı")
        }

        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
            ?: episodes.firstOrNull { it.posterUrl != null }?.posterUrl
        val plot = doc.selectFirst("meta[name=description], meta[property=og:description]")
            ?.attr("content")?.trim()

        return newTvSeriesLoadResponse(title, seriesUrl, TvType.TvSeries, episodes) {
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
        val html = app.get(pageUrl, headers = headers).text.replace("\\/", "/")
        val allUrls = STREAM_REGEX.findAll(html).map { it.value }
            .filterNot { it.contains(".com.trvideo/") }
            .distinct()
            .toList()
        val vodUrls = allUrls.filter { VOD_HOST_REGEX.containsMatchIn(it) }
        val chosen = vodUrls.ifEmpty { allUrls }
        var emitted = false
        chosen.forEach { streamUrl ->
            val quality = QUALITY_REGEX.find(streamUrl)?.groupValues?.get(1)?.toIntOrNull()
                ?: Qualities.Unknown.value
            if (!Settings.qualityAllowed(quality)) return@forEach
            val type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback(newExtractorLink(name, name, streamUrl, type) {
                this.referer = "$mainUrl/"
                this.headers = this@ATVProvider.headers
                this.quality = quality
            })
            emitted = true
        }
        if (!emitted) {
            throw ErrorLoadingException("$name: oynatılabilir akış bulunamadı")
        }
        return true
    }

    private fun Document.seriesCards(): List<SearchResponse> {
        val seen = LinkedHashSet<String>()
        val out = mutableListOf<SearchResponse>()
        select("a[href]").forEach { a: Element ->
            val rawHref = a.attr("href").trim().trimEnd('/')
            val match = CARD_SLUG_REGEX.matchEntire(rawHref) ?: return@forEach
            val slug = match.groupValues[1]
            if (slug in EXCLUDED_SLUGS) return@forEach
            val img = a.selectFirst("img") ?: return@forEach
            val title = img.attr("alt").trim().takeIf { it.isNotBlank() }
                ?: a.selectFirst("h2, h3, h4, span")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: a.text().replace("İZLE", "").trim().takeIf { it.isNotBlank() }
                ?: return@forEach
            val fullUrl = "$mainUrl/$slug"
            if (!seen.add(fullUrl)) return@forEach
            out += newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries) {
                this.posterUrl = fixUrlNull(img.posterSrcLazy())
            }
        }
        return out
    }

    companion object {
        val ROWS = listOf("Diziler", "Klasik Diziler")
        private val CARD_SLUG_REGEX = Regex("""^/([a-z0-9-]+)$""")
        private val SEARCH_SLUG_REGEX = Regex("""^/([a-z0-9-]+)/(?:\d+-bolum|[^/]+/ozelvideo)/izle$""")
        private val SLUG_FROM_URL = Regex("""atv\.com\.tr/([a-z0-9-]+)""")
        private val STREAM_REGEX = Regex("""https?://[^"'\s<>\\]+?\.(?:mp4|m3u8)(?:\?[^"'\s<>\\]*)?""")
        private val VOD_HOST_REGEX = Regex("""(atv-vod\.ercdn\.net|erbvr\.com|h-cdn\.com)""")
        private val QUALITY_REGEX = Regex("""(\d{3,4})p""")
        private val HEADER_NAV_SLUGS = setOf(
            "gunesin-dogdugu-yer",
            "atv-ana-haber",
            "esra-erolda",
            "gun-ortasi-bulteni",
            "muge-anli-ile-tatli-sert",
            "kahvalti-haberleri",
        )
        private val EXCLUDED_SLUGS = setOf(
            "diziler",
            "eski-diziler",
            "klasik-diziler",
            "programlar",
            "canli-yayin",
            "yayin-akisi",
            "haberler",
            "a2tv",
            "adblock",
            "rss-bilgi",
            "bize-ulasin",
            "gizlilik-bildirimi",
            "veri-politikasi",
            "uydu-frekanslari",
            "site-haritasi",
            "kunye",
            "reklam",
            "iletisim",
        )
    }
}
