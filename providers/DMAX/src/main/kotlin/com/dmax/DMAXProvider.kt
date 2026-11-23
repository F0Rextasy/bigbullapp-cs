package com.dmax

import com.bigbull.common.jsonLdDescription
import com.bigbull.common.posterSrcLazy
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * DMAX — dmax.com.tr belgesel/program arşivi.
 *
 * Arama, ana sayfadaki program köklerinde yerel filtredir. Program sayfası
 * `/sezonN-bolumM` kartlarını; bölüm sayfası `data-video-code` değerini basar.
 * `/player/info?referenceId=<code>` JSON'undaki `video.data.flavors.hls` HLS'tir.
 */
class DMAXProvider : MainAPI() {
    override var mainUrl = "https://www.dmax.com.tr"
    override var name = "DMAX"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Documentary, TvType.TvSeries)

    private val headers = mapOf(
        "user-agent" to UA,
        "Accept-Language" to "tr-TR,tr;q=0.9",
    )

    override val mainPage get() = mainPageOf(
        "$mainUrl/" to "Programlar",
        "$mainUrl/kesfet/belgesel" to "Belgesel",
        "$mainUrl/kesfet/zorlu-isler" to "Zorlu İşler",
        "$mainUrl/kesfet/nasil-yapiliyor" to "Nasıl Yapılıyor?",
        "$mainUrl/kesfet/dogayla-ic-ice" to "Doğayla İç İçe",
        "$mainUrl/kesfet/turbo" to "Turbo",
        "$mainUrl/kesfet/spor" to "Spor",
        "$mainUrl/kesfet/filmler" to "Filmler",
        "$mainUrl/kesfet/a-z" to "Tüm Programlar (A-Z)",
    ).filter { Settings.rowEnabled(it.name) }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data, headers = headers).document
        return newHomePageResponse(request.name, doc.cards(), hasNext = false)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (page > 1) return newSearchResponseList(emptyList(), false)
        val doc = app.get("$mainUrl/arama", params = mapOf("sorgu" to query.trim()), headers = headers).document
        return newSearchResponseList(
            doc.cards().filter { fold(it.name).contains(fold(query.trim())) },
            false,
        )
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = fixUrl(url)
        val doc = runCatching { app.get(pageUrl, headers = headers).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: içerik sayfası yüklenemedi. $REPORT_HINT")
        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("$name: başlık okunamadı. $REPORT_HINT")
        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = doc.jsonLdDescription()
            ?: doc.selectFirst("div.slide-description")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }

        if (EPISODE.containsMatchIn(pageUrl)) {
            return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        val programId = doc.selectFirst("[data-program-id]")?.attr("data-program-id")?.trim()
        val episodes = if (!programId.isNullOrBlank()) {
            seasonEpisodes(programId, pageUrl, doc).ifEmpty { inlineEpisodes(doc, pageUrl) }
        } else {
            inlineEpisodes(doc, pageUrl)
        }
        if (episodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
        return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    private suspend fun seasonEpisodes(
        programId: String,
        pageUrl: String,
        doc: Document,
    ): List<Episode> {
        val seasons = doc.select("#video-filter-changer option")
            .map { it.attr("value").trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .ifEmpty {
                listOfNotNull(doc.selectFirst(".load-more[data-season]")?.attr("data-season")?.trim()?.ifBlank { null })
                    .ifEmpty { listOf("") }
            }
        val ajaxHeaders = headers + mapOf("X-Requested-With" to "XMLHttpRequest")
        val episodes = mutableListOf<Episode>()
        for (season in seasons) {
            var page = 0
            while (page < MAX_SEASON_PAGES) {
                val fragment = runCatching {
                    app.post(
                        "$mainUrl/ajax/more",
                        data = mapOf(
                            "type" to "episodes",
                            "program_id" to programId,
                            "page" to page.toString(),
                            "season" to season,
                        ),
                        headers = ajaxHeaders,
                        referer = pageUrl,
                    ).text
                }.getOrNull().orEmpty()
                if (fragment.isBlank()) break
                val found = Jsoup.parse(fragment).select(".item > a[href], a[href]").mapNotNull { card ->
                    val href = fixUrlNull(card.attr("href")) ?: return@mapNotNull null
                    val match = EPISODE.find(href) ?: return@mapNotNull null
                    val label = card.selectFirst(".item-meta-title")?.text()?.trim()?.ifBlank { null }
                        ?: card.text().trim().ifBlank { "Bölüm ${match.groupValues[2]}" }
                    newEpisode(href) {
                        this.name = label
                        this.season = match.groupValues[1].toIntOrNull()
                        this.episode = match.groupValues[2].toIntOrNull()
                    }
                }
                if (found.isEmpty()) break
                episodes += found
                page++
            }
        }
        return episodes.distinctBy { it.data }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
    }

    private fun inlineEpisodes(doc: Document, pageUrl: String): List<Episode> =
        doc.select("a[href]").mapNotNull { card ->
            val href = fixUrlNull(card.attr("href")) ?: return@mapNotNull null
            if (!href.startsWith(pageUrl.trimEnd('/') + "/")) return@mapNotNull null
            val match = EPISODE.find(href) ?: return@mapNotNull null
            newEpisode(href) {
                this.name = card.text().trim().ifBlank { "Bölüm ${match.groupValues[2]}" }
                this.season = match.groupValues[1].toIntOrNull()
                this.episode = match.groupValues[2].toIntOrNull()
            }
        }.distinctBy { it.data }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageUrl = fixUrl(data)
        val html = runCatching { app.get(pageUrl, headers = headers).text }.getOrNull()
            ?: throw ErrorLoadingException("$name: bölüm sayfası yüklenemedi. $REPORT_HINT")
        val code = VIDEO_CODE.find(html)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("$name: video kodu bulunamadı. $REPORT_HINT")
        val info = runCatching {
            JSONObject(app.get("$mainUrl/player/info", params = mapOf("referenceId" to code), headers = headers).text)
        }.getOrNull() ?: throw ErrorLoadingException("$name: player bilgisi okunamadı. $REPORT_HINT")
        val hls = info.optJSONObject("video")?.optJSONObject("data")?.optJSONObject("flavors")
            ?.optString("hls")?.trim()
            ?.takeIf { it.startsWith("http") }
            ?: throw ErrorLoadingException("$name: HLS adresi bulunamadı. $REPORT_HINT")
        callback(newExtractorLink(name, name, hls, ExtractorLinkType.M3U8) {
            this.referer = pageUrl
            this.headers = headers
            this.quality = Qualities.Unknown.value
        })
        return true
    }

    private fun Document.cards(): List<SearchResponse> =
        select("a[href]").mapNotNull { card ->
            val href = fixUrlNull(card.attr("href")) ?: return@mapNotNull null
            if (!href.startsWith(mainUrl)) return@mapNotNull null
            val path = href.removePrefix(mainUrl).substringBefore('?').trim('/')
            if (path.isBlank() || path.contains('/') || path in STATIC) return@mapNotNull null
            if (card.attr("onclick").contains("HEADER_ELEMENTS")) return@mapNotNull null
            val img = card.selectFirst("img") ?: return@mapNotNull null
            val poster = fixUrlNull(img.posterSrcLazy()) ?: return@mapNotNull null
            val title = img.attr("alt").trim()
                .takeIf { it.isNotEmpty() }
                ?: card.attr("title").trim().takeIf { it.isNotEmpty() }
                ?: Regex("""GAEventTracker\([^)]*,\s*'([^']+)'""")
                    .find(card.attr("onclick"))?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
                ?: path.replace('-', ' ').replaceFirstChar { it.uppercase() }
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }

    private fun fold(value: String): String = value.lowercase()
        .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
        .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')
    companion object {
        val ROWS = listOf(
            "Programlar",
            "Belgesel",
            "Zorlu İşler",
            "Nasıl Yapılıyor?",
            "Doğayla İç İçe",
            "Turbo",
            "Spor",
            "Filmler",
            "Tüm Programlar (A-Z)",
        )
        private val UA = com.bigbull.common.browserHeaders.getValue("user-agent")
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
        private const val MAX_SEASON_PAGES = 40
        private val EPISODE = Regex("""(\d+)-sezon-(\d+)-bolum""")
        private val VIDEO_CODE = Regex("""data-video-code="([^"]+)"""")
        private val STATIC = setOf("arama", "yayin-akisi", "canli-yayin", "blog", "kesfet")
    }
}
