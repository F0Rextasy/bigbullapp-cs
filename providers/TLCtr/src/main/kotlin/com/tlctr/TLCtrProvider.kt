package com.tlctr

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * TLC Türkiye — tlctv.com.tr.
 *
 * Keşfet listeleri `POST /ajax/more` (type=discover, 16 kart) ve program bölümleri aynı uçtan
 * (type=episodes, 5 bölüm) dilim dilim gelir. Poster kartlarında görünür başlık yoktur; ad
 * yalnızca GA olayının son parametresinde yazılıdır.
 *
 * Oynatma: bölüm sayfasındaki `data-video-code` → dygvideo yönlendirmesi → imzalı HLS master
 * (360/480/720). Canlı yayın adresi sayfaya `daionUrl` olarak gömülüdür (720/480/360).
 */
class TLCtrProvider : MainAPI() {
    override var mainUrl = "https://www.tlctv.com.tr"
    override var name = "TLCtr"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)

    private val uaHeaders = mapOf(
        "User-Agent" to UA,
        "Accept-Language" to "tr-TR,tr;q=0.9",
    )
    private val ajaxHeaders = uaHeaders + mapOf("X-Requested-With" to "XMLHttpRequest")

    // Rows are the /kesfet categories; the empty slug is the featured grid.
    override val mainPage = mainPageOf(
        LIVE to "Canlı Yayın",
        "" to "Öne Çıkanlar",
        "sira-disi-hayatlar" to "Sıra Dışı Hayatlar",
        "ev-dekorasyon" to "Ev & Dekorasyon",
        "suc-arastirma" to "Suç & Araştırma",
        "yasam" to "Yaşam",
        "evlilik" to "Evlilik",
        "yemek" to "Yemek",
        "belgesel" to "Belgesel",
        "korelendik" to "Korelendik",
        "a-z" to "A-Z",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == LIVE) {
            val card = newLiveSearchResponse(LIVE_NAME, liveUrl(), TvType.Live)
            return newHomePageResponse(request.name, listOf(card), hasNext = false)
        }
        // The server-rendered grid is page 0 and carries slugs instead of names; the AJAX
        // fragment from page 0 on is the only variant with real titles, so never use the page.
        val fragment = app.post(
            "$mainUrl/ajax/more",
            data = mapOf("type" to "discover", "slug" to request.data, "page" to "${page - 1}"),
            headers = ajaxHeaders,
            referer = "$mainUrl/kesfet",
        ).text
        val items = parseCards(Jsoup.parse(fragment).select("div.poster > a[href]"))
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/arama",
            params = mapOf("sorgu" to query.trim()),
            headers = uaHeaders,
        ).document
        val programs = searchGroup(doc, "PROGRAMLAR")
            ?.let { parseCards(it.select("div.poster > a[href]")) }
            .orEmpty()
        val videos = searchGroup(doc, "VİDEOLAR")?.select("div.video").orEmpty().mapNotNull { video ->
            val href = absolute(video.selectFirst("a[href]")?.attr("href")) ?: return@mapNotNull null
            val title = listOfNotNull(
                video.selectFirst(".program-name")?.text()?.trim()?.ifBlank { null },
                video.selectFirst(".video-title")?.text()?.trim()?.ifBlank { null },
            ).joinToString(" - ").ifBlank { return@mapNotNull null }
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = cardImage(video)
            }
        }
        return (programs + videos).distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = runCatching { app.get(url, headers = uaHeaders) }.getOrNull()
            ?: throw ErrorLoadingException("$name: sayfa yüklenemedi. $REPORT_HINT")
        val doc = response.document

        if (url.trimEnd('/').endsWith("/canli-izle")) {
            return newLiveStreamLoadResponse(LIVE_NAME, url, url) {
                this.posterUrl = LIVE_POSTER.find(response.text)?.groupValues?.get(1)?.let { absolute(it) }
            }
        }

        val poster = absolute(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val heroTitle = doc.selectFirst(".slide-title h1")?.text()?.trim()

        // Episode and short-video pages have no program hero; they play one video.
        if (heroTitle == null) {
            val videoName = doc.selectFirst(".video-meta-title")?.text()?.trim()?.ifBlank { null }
                ?: throw ErrorLoadingException("$name: video adı okunamadı. $REPORT_HINT")
            val owner = doc.selectFirst("h1")?.text()?.trim() ?: videoName
            val plot = doc.selectFirst(".video-meta-description")?.text()?.trim()?.ifBlank { null }
            return newTvSeriesLoadResponse(owner, url, TvType.TvSeries, listOf(newEpisode(url) {
                this.name = videoName
                this.posterUrl = poster
            })) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        val plot = doc.selectFirst(".slide-description p.full")?.text()?.trim()?.ifBlank { null }
        val programId = doc.selectFirst(".dyn-content.program-episodes[data-program-id]")?.attr("data-program-id")
        val episodes = if (programId.isNullOrBlank()) inlineEpisodes(doc) else seasonEpisodes(programId, url, doc)
        if (episodes.isEmpty()) throw ErrorLoadingException("$name: bölüm bulunamadı. $REPORT_HINT")

        return newTvSeriesLoadResponse(heroTitle, url, TvType.TvSeries, episodes) {
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
        val page = runCatching { app.get(data, headers = uaHeaders) }.getOrNull()
            ?: throw ErrorLoadingException("$name: sayfa yüklenemedi. $REPORT_HINT")

        val master = if (data.trimEnd('/').endsWith("/canli-izle")) {
            LIVE_URL.find(page.text)?.groupValues?.get(1)?.takeIf { it.startsWith("http") }
                ?: throw ErrorLoadingException("$name: canlı yayın adresi bulunamadı. $REPORT_HINT")
        } else {
            val code = page.document.selectFirst("[data-video-code]")?.attr("data-video-code")
                ?: throw ErrorLoadingException("$name: oynatıcı kodu bulunamadı. $REPORT_HINT")
            // The site player uses this redirect too; it answers 302 with the signed HLS master.
            runCatching {
                app.get(
                    "https://dygvideo.dygdigital.com/api/redirect?PublisherId=20&ReferenceId=$code" +
                        "&SecretKey=$PLAYER_SECRET",
                    headers = uaHeaders,
                    referer = data,
                ).url
            }.getOrNull()?.takeIf { it.contains(".m3u8") }
                ?: throw ErrorLoadingException("$name: video kaynağı alınamadı. $REPORT_HINT")
        }

        val playlist = runCatching {
            app.get(master, headers = uaHeaders, referer = "$mainUrl/").text
        }.getOrNull() ?: throw ErrorLoadingException("$name: oynatma listesi alınamadı. $REPORT_HINT")

        val variants = parseVariants(playlist, master)
        if (variants.isEmpty()) {
            // Single-rendition playlist: unknown quality, so the cap must not drop the only link.
            emit(master, Qualities.Unknown.value, callback)
        } else {
            variants.filter { Settings.qualityAllowed(it.first) }
                .ifEmpty { listOf(variants.minBy { it.first }) }
                .forEach { (height, link) -> emit(link, height, callback) }
        }
        return true
    }

    private fun parseCards(cards: List<Element>): List<SearchResponse> = cards.mapNotNull { card ->
        val href = absolute(card.attr("href")) ?: return@mapNotNull null
        val title = cardTitle(card) ?: return@mapNotNull null
        newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = cardImage(card)
        }
    }.distinctBy { it.url }

    /** Card anchors carry no text; the name is the last quote slice of the GA event. */
    private fun cardTitle(card: Element): String? {
        val onclick = card.attr("onclick")
        val cut = onclick.lastIndexOf(", '")
        if (cut < 0) return null
        return onclick.substring(cut + 3)
            .removePrefix("'")
            .removeSuffix(");")
            .removeSuffix("'")
            .trim()
            .ifBlank { null }
    }

    private fun cardImage(card: Element): String? {
        val img = card.selectFirst("img") ?: return null
        return absolute(img.attr("data-src").ifBlank { img.attr("src") })
    }

    private fun searchGroup(doc: Document, name: String): Element? =
        doc.select(".page-search-result-group").firstOrNull {
            it.selectFirst("h2")?.text()?.trim() == name
        }

    /** Seasons come from the filter dropdown; each season pages 5 episodes at a time. */
    private suspend fun seasonEpisodes(
        programId: String,
        url: String,
        doc: Document,
    ): List<Episode> {
        val seasons = doc.select("#video-filter-changer option")
            .map { it.attr("value") }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf("") }
        val episodes = mutableListOf<Episode>()
        for (season in seasons) {
            var page = 0
            while (page < MAX_SEASON_PAGES) {
                val fragment = app.post(
                    "$mainUrl/ajax/more",
                    data = mapOf(
                        "type" to "episodes",
                        "program_id" to programId,
                        "page" to page.toString(),
                        "season" to season,
                    ),
                    headers = ajaxHeaders,
                    referer = url,
                ).text
                val found = Jsoup.parse(fragment).select(".item > a[href]").mapNotNull { it.toEpisode() }
                if (found.isEmpty()) break
                episodes += found
                page++
            }
        }
        return episodes.distinctBy { it.data }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
    }

    /** Landing pages without an episode grid (e.g. /film-kusagi) only list inline videos. */
    private fun inlineEpisodes(doc: Document): List<Episode> =
        doc.select("section.videos .items .item > a[href]").mapNotNull { it.toEpisode() }

    private fun Element.toEpisode(): Episode? {
        val href = absolute(attr("href")) ?: return null
        val label = selectFirst(".item-meta-title")?.text()?.trim()?.ifBlank { null }
        val marker = SEASON_EPISODE.find(href)
        return newEpisode(href) {
            this.name = label ?: "Video"
            this.season = marker?.groupValues?.get(1)?.toIntOrNull()
            this.episode = marker?.groupValues?.get(2)?.toIntOrNull()
            this.posterUrl = selectFirst("img")?.let { img ->
                absolute(img.attr("src").ifBlank { img.attr("data-src") })
            }
        }
    }

    /** Master playlist pairs: (height, variant URL). Relative URIs resolve against the master path. */
    private fun parseVariants(playlist: String, master: String): List<Pair<Int, String>> {
        val base = master.substringBefore('?').substringBeforeLast('/') + "/"
        val variants = mutableListOf<Pair<Int, String>>()
        var height = 0
        for (line in playlist.lineSequence()) {
            val text = line.trim()
            when {
                text.startsWith("#EXT-X-STREAM-INF") ->
                    height = RESOLUTION.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0

                text.isEmpty() || text.startsWith("#") -> Unit

                height > 0 -> {
                    variants += height to if (text.startsWith("http")) text else base + text
                    height = 0
                }
            }
        }
        return variants
    }

    private suspend fun emit(link: String, quality: Int, callback: (ExtractorLink) -> Unit) {
        val label = if (quality > 0) "$name ${quality}p" else name
        callback(newExtractorLink(name, label, link, ExtractorLinkType.M3U8) {
            this.referer = "$mainUrl/"
            this.quality = quality
            this.headers = mapOf("User-Agent" to UA)
        })
    }

    private fun liveUrl() = "$mainUrl/canli-izle"

    private fun absolute(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        return when {
            value.startsWith("http") -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "$mainUrl$value"
            else -> null
        }
    }

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
        private const val LIVE = "live"
        private const val LIVE_NAME = "TLC Canlı"

        // Published by the site's own player (media-player/js/bundle.js): TLC is publisher 20.
        private const val PLAYER_SECRET = "NtvApiSecret2014*"

        // shortcut: season pages stop at 40 (200 episodes); the endpoint has no larger page size.
        private const val MAX_SEASON_PAGES = 40

        private val SEASON_EPISODE = Regex("""(\d+)-sezon-(\d+)-bolum""")
        private val RESOLUTION = Regex("""RESOLUTION=\d+x(\d+)""")
        private val LIVE_URL = Regex("""daionUrl\s*:\s*'([^']+)'""")
        private val LIVE_POSTER = Regex("""poster\s*:\s*'([^']+)'""")
    }
}
