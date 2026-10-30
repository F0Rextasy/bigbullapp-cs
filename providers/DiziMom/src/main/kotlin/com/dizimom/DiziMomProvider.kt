package com.dizimom

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Document

/**
 * DiziMom — ddizimom.net (dizimom.plus adresi parked).
 * Bölümler `?sezon=N#bolumler` altında, sezon başına bir istek.
 * Bölüm sayfasındaki `data-pv` slug'ı → play2.pilavyerplay.top s.php →
 * `window.__PLAYER__` (master HLS + ses/altyazı) → stream.php.
 */
class DiziMomProvider : MainAPI() {
    private val uaHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9",
    )
    override var mainUrl = "https://ddizimom.net"
    override var name = "DiziMom"
    override var lang = "tr"
    override val hasMainPage = true
    override val mainPage get() = mainPageOf("$mainUrl/" to "Son Eklenenler")
        .filter { Settings.rowEnabled(it.name) }
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    private val playerBase = "https://pilavyerplay.top"
    private val seriesLink = Regex("""^https?://[^/]+/dizi/[^/?#]+$""")
    private val episodeLink = Regex("""/sezon-(\d+)/bolum-(\d+)$""")
    private val playerSlug = Regex("""data-pv="([A-Za-z0-9_\-]{8,})"""")
    private val playerConfig = Regex("""window\.__PLAYER__\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
    private val masterHeight = Regex("""RESOLUTION=\d+x(\d+)""")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data, headers = uaHeaders).document
        val items = doc.select("a[href*='/dizi/']").mapNotNull { card ->
            val href = fixUrlNull(card.attr("href")) ?: return@mapNotNull null
            if (!seriesLink.matches(href)) return@mapNotNull null
            val title = card.selectFirst("img[alt]")?.attr("alt")
                ?.removeSuffix(" Dizi izle")?.removeSuffix(" izle")?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: card.attr("title").trim().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = card.selectFirst("img")?.attr("src")
            }
        }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/ara", params = mapOf("q" to query)).document
        return doc.select("main a[href]").mapNotNull { card ->
            val href = card.attr("href")
            if (!seriesLink.matches(href)) return@mapNotNull null
            val title = card.selectFirst("h3")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = card.selectFirst("img")?.attr("src")
                this.year = card.selectFirst("p")?.text()?.trim()?.toIntOrNull()
            }
        }.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val title = doc.selectFirst("main h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: dizi sayfası okunamadı")

        val base = url.substringBefore("?")
        val seasons = doc.select("a[href*='?sezon=']")
            .map { it.attr("href").substringBefore("#") }
            .distinct()
        val episodes = seasons.ifEmpty { listOf(base) }.flatMap { page ->
            parseEpisodes(if (page == base) doc else app.get(page).document, base)
        }.distinctBy { it.data }
        if (episodes.isEmpty()) throw ErrorLoadingException("$name: bölüm bulunamadı")

        val meta = doc.select("dl dt").associate { dt ->
            dt.text().trim() to dt.nextElementSibling()?.text()?.trim().orEmpty()
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.year = meta["Yapım Yılı"]?.toIntOrNull()
            this.plot = doc.selectFirst("main p.max-w-2xl")?.text()?.trim()
            this.posterUrl = doc.selectFirst("img[src*='/storage/tmdb/posters/']")?.attr("src")
            this.score = Score.from10(meta["IMDB Puanı"]?.filter { it.isDigit() || it == '.' })
            this.tags = doc.select("main a[href*='/tur/']").map { it.text().trim() }.distinct()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val linkCallback: (ExtractorLink) -> Unit = { if (Settings.qualityAllowed(it.quality)) callback(it) }
        val subtitleFilter: (SubtitleFile) -> Unit = { if (Settings.subtitleAllowed(it.lang)) subtitleCallback(it) }

        val slugs = playerSlug.findAll(app.get(data, headers = uaHeaders).text).map { it.groupValues[1] }.distinct().toList()
        if (slugs.isEmpty()) throw ErrorLoadingException("$name: oynatıcı bulunamadı")

        slugs.forEach { slug ->
            val shell = app.get(
                "$playerBase/assets/js/s.php",
                params = mapOf("s" to slug),
                referer = data,
                headers = uaHeaders,
            ).text
            val config = playerConfig.find(shell)?.groupValues?.get(1)
                ?: throw ErrorLoadingException("$name: oynatıcı yapılandırması okunamadı")
            val player = JSONObject(config)
            val stream = player.optString("stream").takeIf { it.isNotBlank() }
                ?: throw ErrorLoadingException("$name: akış adresi bulunamadı")

            val dubs = player.optJSONArray("audios")?.let { audios ->
                (0 until audios.length()).mapNotNull { i ->
                    audios.optJSONObject(i)?.optString("label")?.trim()?.takeIf { it.isNotBlank() }
                }
            }.orEmpty()

            linkCallback(
                newExtractorLink(
                    name,
                    if (dubs.isEmpty()) "Pilavyer" else dubs.joinToString(" / "),
                    stream,
                    ExtractorLinkType.M3U8,
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = masterQuality(stream)
                }
            )

            player.optJSONArray("subs")?.let { subs ->
                (0 until subs.length()).mapNotNull { subs.optJSONObject(it) }.forEach { sub ->
                    val src = sub.optString("src").takeIf { it.isNotBlank() } ?: return@forEach
                    val language = sub.optString("lang").ifBlank { "tr" }
                    subtitleFilter(newSubtitleFile(language, src))
                }
            }
        }
        return true
    }

    private fun parseEpisodes(doc: Document, base: String): List<Episode> =
        doc.select("a[href]").mapNotNull { card ->
            val href = card.attr("href")
            if (!href.startsWith("$base/sezon-")) return@mapNotNull null
            val match = episodeLink.find(href) ?: return@mapNotNull null
            // "İlk Bölüm İzle" butonu da bu URL'e gider; kartlarda p veya img vardır.
            val label = card.selectFirst("p")?.text()?.trim()?.takeIf { it.isNotBlank() }
            val poster = card.selectFirst("img")?.attr("src")
            if (label == null && poster == null) return@mapNotNull null
            val season = match.groupValues[1].toInt()
            val number = match.groupValues[2].toInt()
            newEpisode(href) {
                this.name = label ?: "$season. Sezon $number. Bölüm"
                this.season = season
                this.episode = number
                this.posterUrl = poster
            }
        }.distinctBy { it.data }

    /** Master m3u8'deki en yüksek çözünürlük; oynatıcı yine adaptif seçer. */
    private suspend fun masterQuality(masterUrl: String): Int =
        masterHeight.findAll(runCatching { app.get(masterUrl, referer = "$mainUrl/").text }.getOrDefault(""))
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .maxOrNull() ?: Qualities.Unknown.value

    companion object {
        /** Ana sayfa satırları; ayarlardan kapatılabilir. */
        val ROWS = listOf("Son Eklenenler")
    }
}
