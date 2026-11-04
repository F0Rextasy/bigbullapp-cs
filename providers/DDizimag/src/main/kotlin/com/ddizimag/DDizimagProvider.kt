package com.ddizimag

import com.bigbull.common.browserHeaders
import com.bigbull.common.posterSrcLazy
import com.lagradost.cloudstream3.Episode
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
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * DDizimag — ddizimag.com. Arama `/ara?q=`, detay `/dizi|film/<slug>`.
 * Bölümler `?sezon=N` altında (sezon başına bir istek); oynatıcı sayfadaki
 * `data-pv` slug'ı → pilavyer `s.php` → `window.__PLAYER__` → HLS + altyazı.
 */
class DDizimagProvider : MainAPI() {
    private val uaHeaders get() = browserHeaders

    override var mainUrl = "https://ddizimag.com"
    override var name = "DDizimag"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val hasMainPage = true
    override val mainPage get() = ROWS.mapNotNull { rowName ->
        if (!Settings.rowEnabled(rowName)) return@mapNotNull null
        MainPageData(rowName, when (rowName) {
            "Aile" -> "$mainUrl/tur/aile"
            "Aksiyon" -> "$mainUrl/tur/aksiyon"
            "Aksiyon &amp; Macera" -> "$mainUrl/tur/aksiyon-macera"
            "Animasyon" -> "$mainUrl/tur/animasyon"
            "Belgesel" -> "$mainUrl/tur/belgesel"
            "Bilim Kurgu" -> "$mainUrl/tur/bilim-kurgu"
            "Dram" -> "$mainUrl/tur/dram"
            "Fantastik" -> "$mainUrl/tur/fantastik"
            "Gerilim" -> "$mainUrl/tur/gerilim"
            "Gizem" -> "$mainUrl/tur/gizem"
            "Komedi" -> "$mainUrl/tur/komedi"
            "Korku" -> "$mainUrl/tur/korku"
            "Romantik" -> "$mainUrl/tur/romantik"
            "Suç" -> "$mainUrl/tur/suc"
            else -> mainUrl
        })
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.name !in ROWS || page > 1) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
        val doc = app.get(request.data, headers = uaHeaders).document
        return newHomePageResponse(request.name, cards(doc), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        cards(app.get("$mainUrl/ara", params = mapOf("q" to query), headers = uaHeaders).document)

    private fun cards(doc: Document): List<SearchResponse> = doc.select("a[href]").mapNotNull { card ->
        val href = fixUrlNull(card.attr("href")) ?: return@mapNotNull null
        val isSeries = seriesLink.matches(href)
        if (!isSeries && !movieLink.matches(href)) return@mapNotNull null
        val title = card.selectFirst("h3")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: card.selectFirst("img[alt]")?.attr("alt")
                ?.removeSuffix(" Film izle")?.removeSuffix(" Dizi izle")?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        val poster = fixUrlNull(card.selectFirst("img")?.posterSrcLazy())
        val year = card.selectFirst("p")?.text()?.trim()?.toIntOrNull()
        if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }.distinctBy { it.url }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = uaHeaders).document
        val title = doc.selectFirst("main h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: sayfa okunamadı")
        val meta = doc.select("dl dt").associate { dt ->
            dt.text().trim() to dt.nextElementSibling()?.text()?.trim().orEmpty()
        }
        val poster = fixUrlNull(doc.selectFirst("img[src*='/storage/tmdb/posters/']")?.posterSrcLazy())
            ?: fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = doc.selectFirst("main p.max-w-2xl")?.text()?.trim()
            ?: doc.selectFirst("main p.max-w-3xl")?.text()?.trim()
        val year = meta["Yapım Yılı"]?.toIntOrNull()
            ?: Regex("""\((\d{4})\)""").find(doc.title())?.groupValues?.get(1)?.toIntOrNull()
        val score = Score.from10(meta["IMDB Puanı"]?.filter { it.isDigit() || it == '.' })
            ?: Score.from10(doc.selectFirst("main p.text-star")?.text()?.filter { it.isDigit() || it == '.' })
        val tags = doc.select("main a[href*='/tur/']").map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct()

        if (!url.contains("/dizi/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = score
                this.tags = tags
            }
        }

        val base = url.substringBefore("?")
        val seasons = doc.select("a[href]").map { it.attr("href") }
            .filter { it.contains("?sezon=") }
            .mapNotNull { fixUrlNull(it.substringBefore("#")) }
            .distinct()
        val episodes = (seasons.ifEmpty { listOf(base) }).flatMap { page ->
            val pageDoc = if (page == base) doc else app.get(page, headers = uaHeaders).document
            parseEpisodes(pageDoc, base)
        }.distinctBy { it.data }
        if (episodes.isEmpty()) throw ErrorLoadingException("$name: bölüm bulunamadı")

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.score = score
            this.tags = tags
        }
    }

    private fun parseEpisodes(doc: Document, base: String): List<Episode> =
        doc.select("a[href]").mapNotNull { card ->
            val href = fixUrlNull(card.attr("href")) ?: return@mapNotNull null
            if (!href.startsWith("$base/sezon-")) return@mapNotNull null
            val match = episodeLink.find(href) ?: return@mapNotNull null
            val label = card.selectFirst("p")?.text()?.trim()?.takeIf { it.isNotBlank() }
            // "Sonraki Bölüm" butonu aynı URL'e gider ama kart değildir (p/span yok).
            if (label == null && card.selectFirst("span") == null) return@mapNotNull null
            val season = match.groupValues[1].toInt()
            val number = match.groupValues[2].toInt()
            newEpisode(href) {
                this.name = label ?: "$season. Sezon $number. Bölüm"
                this.season = season
                this.episode = number
            }
        }.distinctBy { it.data }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val page = app.get(data, headers = uaHeaders).text
        val slugs = playerSlug.findAll(page).map { it.groupValues[1] }.distinct().toList()
        if (slugs.isEmpty()) throw ErrorLoadingException("$name: oynatıcı bulunamadı")
        val host = playerHost.find(page)?.groupValues?.get(1) ?: playerBase

        slugs.forEach { slug ->
            val shell = app.get(
                "$host/assets/js/s.php",
                params = mapOf("s" to slug),
                referer = data,
                headers = uaHeaders,
            ).text
            val config = playerConfig.find(shell)?.groupValues?.get(1)
                ?: throw ErrorLoadingException("$name: oynatıcı yapılandırması okunamadı")
            val player = JSONObject(config)
            val stream = player.optString("stream").takeIf { it.isNotBlank() }
                ?: throw ErrorLoadingException("$name: akış adresi bulunamadı")
            val quality = masterQuality(stream, "$host/")
            if (Settings.qualityAllowed(quality)) {
                callback.invoke(newExtractorLink(name, "Pilavyer", stream, ExtractorLinkType.M3U8) {
                    this.referer = "$host/"
                    this.quality = quality
                })
            }
            player.optJSONArray("subs")?.let { subs ->
                (0 until subs.length()).mapNotNull { subs.optJSONObject(it) }.forEach { sub ->
                    val src = sub.optString("src").takeIf { it.isNotBlank() } ?: return@forEach
                    val language = sub.optString("lang").ifBlank { "tr" }
                    if (Settings.subtitleAllowed(language)) {
                        subtitleCallback.invoke(newSubtitleFile(language, src) {
                            this.headers = mapOf("Referer" to "$host/")
                        })
                    }
                }
            }
        }
        return true
    }

    /** Master m3u8'deki en yüksek çözünürlük; oynatıcı yine adaptif seçer. */
    private suspend fun masterQuality(masterUrl: String, referer: String): Int =
        masterHeight.findAll(runCatching { app.get(masterUrl, referer = referer).text }.getOrDefault(""))
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .maxOrNull() ?: Qualities.Unknown.value

    companion object {
        val ROWS = listOf("Aile", "Aksiyon", "Aksiyon &amp; Macera", "Animasyon", "Belgesel",
            "Bilim Kurgu", "Dram", "Fantastik", "Gerilim", "Gizem", "Komedi", "Korku", "Romantik", "Suç")
    }

    private val playerBase = "https://pilavyerplay.top"
    private val playerHost = Regex("""<script[^>]+src="(https://[^"]+)/assets/js/core\.js""")
    private val seriesLink = Regex("""^https?://[^/]+/dizi/[^/?#]+$""")
    private val movieLink = Regex("""^https?://[^/]+/film/[^/?#]+$""")
    private val episodeLink = Regex("""/sezon-(\d+)/bolum-(\d+)$""")
    private val playerSlug = Regex("""data-pv="([A-Za-z0-9_\-]{8,})"""")
    private val playerConfig = Regex("""window\.__PLAYER__\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
    private val masterHeight = Regex("""RESOLUTION=\d+x(\d+)""")
}
