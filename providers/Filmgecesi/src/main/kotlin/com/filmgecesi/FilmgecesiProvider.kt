package com.filmgecesi

import com.bigbull.common.browserHeaders
import com.bigbull.common.posterSrcLazy
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
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
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Base64

/**
 * Filmgecesi — filmgecesi.vip (77+ sayfalık film arşivi, tür sayfaları ve film serileri).
 *
 * Oynatıcı: detaydaki `pdata['prt_<id>']` base64 gövdesinin başına `rvali()` ile ters çevrilmiş
 * önek eklenir; çözülen `<iframe src="https://blurays.me/gov.php?vod=...">` sarmalayıcısı
 * `var src="..."` içinde HLS master'ı döner. Ses ayrı rendition grubunda olduğu için varyant
 * yerine master verilir. `/seri-filmler/<slug>` sayfaları TvSeries; bölümleri sıradaki filmlerdir.
 */
class FilmgecesiProvider : MainAPI() {
    override var mainUrl = "https://www.filmgecesi.vip"
    override var name = "Filmgecesi"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage get() = ROW_BASES.keys.mapNotNull { row ->
        if (!Settings.rowEnabled(row)) return@mapNotNull null
        MainPageData(row, "$mainUrl${ROW_BASES.getValue(row)}")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.name == ARCHIVE_ROW) {
            val doc = app.post(
                "$mainUrl/film-arsivi",
                data = ARCHIVE_FILTERS + ("sayfano" to page.toString()),
                headers = browserHeaders,
            ).document
            val items = doc.select(CARD).mapNotNull { it.toMovieCard() }
            return newHomePageResponse(request.name, items, hasNext = hasNextPage(doc, page))
        }
        val path = request.data.removePrefix(mainUrl).trimEnd('/')
        val url = when {
            page > 1 -> "$mainUrl$path/page/$page"
            path.isEmpty() -> "$mainUrl/"
            else -> "$mainUrl$path"
        }
        val doc = app.get(url, headers = browserHeaders).document
        val series = doc.select("article.series-premium-card").mapNotNull { it.toSeriesCard() }
        val items: List<SearchResponse> = if (series.isNotEmpty()) series else {
            val cards = if (path.isEmpty()) doc.select(FEED_CARD) else doc.select(CARD)
            cards.mapNotNull { it.toMovieCard() }
        }
        return newHomePageResponse(request.name, items, hasNext = hasNextPage(doc, page))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/arama/", headers = browserHeaders, params = mapOf("s" to query.trim())).document
        return doc.select(CARD).mapNotNull { it.toMovieCard() }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = fixUrl(url)
        val doc = runCatching { app.get(pageUrl, headers = browserHeaders).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: sayfa yüklenemedi. $REPORT_HINT")
        if (pageUrl.contains("/seri-filmler/")) return loadSeries(pageUrl, doc)

        val info = doc.selectFirst("#video_infos")
        val title = (doc.selectFirst("h1 span[itemprop=name]") ?: doc.selectFirst("h1"))?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: başlık bulunamadı. $REPORT_HINT")
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
            ?: info?.selectFirst("img")?.posterSrcLazy()
        val plot = info?.selectFirst("[itemprop=description]")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val year = doc.selectFirst("span[itemprop=dateCreated] a")?.text()?.trim()?.toIntOrNull()
        val score = Score.from10(doc.selectFirst("span[itemprop=ratingValue]")?.text()?.trim())
        val duration = DURATION.find(info?.text().orEmpty())?.groupValues?.get(1)?.toIntOrNull()
        val tags = info?.select(".detail a[href]")?.map { it.text().trim() }?.filter { it.isNotBlank() }.orEmpty()
        val actors = info?.select(".oyn p")?.text()?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()

        return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = plot
            this.year = year
            this.score = score
            this.duration = duration
            this.tags = tags
            this.actors = actors.map { ActorData(Actor(it)) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageUrl = fixUrl(data.substringBefore("|"))
        val html = runCatching { app.get(pageUrl, headers = browserHeaders).text }.getOrNull()
            ?: throw ErrorLoadingException("$name: sayfa yüklenemedi. $REPORT_HINT")
        val prefix = RVALI.find(html)?.groupValues?.get(1)?.reversed() ?: IFRAME_PREFIX
        val labels = TAB.findAll(html).associate {
            it.groupValues[1] to it.groupValues[2].replace(TAG, "").trim()
        }
        val subCallback: (SubtitleFile) -> Unit = Settings.filterSubtitles(subtitleCallback)
        val linkCallback: (ExtractorLink) -> Unit = Settings.filterLinks(callback)
        var found = false
        for (part in PART.findAll(html)) {
            val partId = part.groupValues[1]
            if (partId.contains("fragman")) continue
            val iframe = decodeIframe(prefix, part.groupValues[2])
                ?.let { IFRAME_SRC.find(it)?.groupValues?.get(1) }
                ?.let(::fixUrl)
                ?: continue
            if (YOUTUBE.containsMatchIn(iframe)) continue
            val label = labels[partId.removePrefix("prt_")].orEmpty().ifBlank { "Bluray" }
            if (resolveSource(iframe, pageUrl, label, subCallback, linkCallback)) found = true
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir kaynak yok. $REPORT_HINT")
        return true
    }

    private suspend fun loadSeries(pageUrl: String, doc: Document): LoadResponse {
        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: seri başlığı bulunamadı. $REPORT_HINT")
        val episodes = doc.select("div.series-movie-grid div.seri_k").mapIndexedNotNull { index, node ->
            val href = fixUrlNull(node.selectFirst("a.image[href]")?.attr("href"))
                ?: fixUrlNull(node.selectFirst("a.info[href]")?.attr("href"))
                ?: return@mapIndexedNotNull null
            val epName = node.selectFirst("h2")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: "Film ${index + 1}"
            newEpisode(href) {
                this.season = 1
                this.episode = index + 1
                this.name = epName
            }
        }
        if (episodes.isEmpty()) throw ErrorLoadingException("$name: seri bölümleri bulunamadı. $REPORT_HINT")
        return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
            this.posterUrl = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
            this.plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        }
    }

    private suspend fun resolveSource(
        embed: String,
        referer: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val wrapper = runCatching { app.get(embed, headers = browserHeaders, referer = referer).text }.getOrNull()
        val master = wrapper?.let { HLS_SRC.find(it)?.groupValues?.get(1) }?.let(::fixUrl)
        if (master != null) {
            val playlist = runCatching { app.get(master, headers = browserHeaders, referer = embed).text }.getOrNull()
            if (playlist != null && playlist.trimStart().startsWith("#EXTM3U")) {
                val tiers = RESOLUTION.findAll(playlist)
                    .map { tierOf(it.groupValues[1].toInt()) }.distinct().sortedDescending().toList()
                // shortcut: master tek link; kalite tavanı yalnız etiketi belirler, ExoPlayer daha yüksek varyanta çıkabilir.
                val best = tiers.firstOrNull { Settings.qualityAllowed(it) } ?: tiers.firstOrNull()
                callback.invoke(newExtractorLink(name, label, master, ExtractorLinkType.M3U8) {
                    this.referer = embed
                    this.quality = best ?: Qualities.Unknown.value
                this.headers = browserHeaders
                })
                return true
            }
        }
        return loadExtractor(embed, referer, subtitleCallback, callback)
    }

    private fun decodeIframe(prefix: String, encoded: String): String? =
        runCatching { String(Base64.getDecoder().decode(prefix + encoded), Charsets.UTF_8) }.getOrNull()
            ?: runCatching { String(Base64.getDecoder().decode(encoded), Charsets.UTF_8) }.getOrNull()

    // Varyantlar letterbox'lı (3840x1608); kalite sınıfı genişlikten çıkarılır.
    private fun tierOf(width: Int): Int = when {
        width >= 3800 -> Qualities.P2160.value
        width >= 2500 -> Qualities.P1440.value
        width >= 1900 -> Qualities.P1080.value
        width >= 1250 -> Qualities.P720.value
        width >= 840 -> Qualities.P480.value
        width >= 620 -> Qualities.P360.value
        else -> Qualities.P240.value
    }

    private fun hasNextPage(doc: Document, page: Int): Boolean {
        val pagination = doc.selectFirst(".pagination") ?: return false
        val last = pagination.select("a[rel]").mapNotNull { it.attr("rel").toIntOrNull() }.maxOrNull() ?: page
        return last > page
    }

    private fun Element.toMovieCard(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val title = selectFirst("h2")?.text()?.trim().orEmpty().ifBlank { attr("title").trim() }
        if (title.isBlank()) return null
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = fixUrlNull(selectFirst("img")?.posterSrcLazy())
            this.year = selectFirst(".box.year")?.text()?.trim()?.toIntOrNull()
        }
    }

    private fun Element.toSeriesCard(): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        val title = selectFirst("h2")?.text()?.trim().orEmpty().ifBlank { anchor.attr("title").trim() }
        if (title.isBlank()) return null
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = fixUrlNull(selectFirst("img")?.posterSrcLazy())
        }
    }

    companion object {
        private const val ARCHIVE_ROW = "Film Arşivi"
        private const val REPORT_HINT = "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
        private const val CARD = "div.move_k a.image[href]"
        // Ana sayfada tür bölümleri de move_k taşır; besleme yalnızca home-feed listesinden alınır.
        private const val FEED_CARD = "main.home-feed .content > .lists div.move_k a.image[href]"
        private const val IFRAME_PREFIX = "PGlmcmFtZSB"

        private val ROW_BASES = linkedMapOf(
            "Yeni Eklenenler" to "",
            "Seri Filmler" to "/seri-filmler",
            "Aksiyon" to "/aksiyon",
            "Animasyon" to "/animasyon",
            "Komedi" to "/komedi",
            "Korku" to "/korku",
            "Dram" to "/dram",
            "Bilim Kurgu" to "/bilim-kurgu",
            "Gerilim" to "/gerilim",
            "Macera" to "/macera",
            "Suç" to "/suc",
            "Yerli" to "/yerli",
            "Hint" to "/hint",
            "Kore" to "/kore",
            "Netflix" to "/netflix",
            "Yeşilçam" to "/yesilcam",
        )
        val ROWS: List<String> = ROW_BASES.keys.toList()

        private val ARCHIVE_FILTERS = mapOf(
            "action" to "ajax_archive",
            "rdil" to "",
            "rdate" to "",
            "rtip" to "",
            "rcat" to "",
            "rimdb" to "",
        )
        private val RVALI = Regex("""rvali\('([^']+)'\)""")
        private val PART = Regex("""pdata\['(prt_[^']+)'\]\s*=\s*'([^']*)'""")
        private val TAB = Regex(
            """<li data-fid="([^"]+)"[^>]*class="pgrup"><a[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val TAG = Regex("""<[^>]+>""")
        private val IFRAME_SRC = Regex("""<iframe[^>]+src=["']([^"']+)""", RegexOption.IGNORE_CASE)
        private val HLS_SRC = Regex("""var\s+src\s*=\s*["']([^"']+)""")
        private val RESOLUTION = Regex("""RESOLUTION=(\d+)x\d+""", RegexOption.IGNORE_CASE)
        private val YOUTUBE = Regex("""youtu\.?be|youtube\.com""", RegexOption.IGNORE_CASE)
        private val DURATION = Regex("""(\d+)\s*Dakika""", RegexOption.IGNORE_CASE)
    }
}
