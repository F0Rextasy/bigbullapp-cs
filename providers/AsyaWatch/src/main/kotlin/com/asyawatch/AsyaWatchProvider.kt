package com.asyawatch

import com.bigbull.common.browserHeaders
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
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.json.JSONArray
import org.jsoup.nodes.Element

/**
 * AsyaWatch — asyawatch.com.
 *
 * Site 2026-11-02'de Next.js istemci API'sinden sunucu-render HTML'e geçti:
 * arama `/ara?q=`, liste `/dizi-izle` ve `/film-izle`, bölümler JSON-LD
 * `containsSeason.episode`, oynatıcı pichive iframe → HLS master.
 */
class AsyaWatchProvider : MainAPI() {
    override var mainUrl = "https://asyawatch.com"
    override var name = "AsyaWatch"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val uaHeaders get() = browserHeaders

    override val mainPage get() = ROWS.mapNotNull { row ->
        if (!Settings.rowEnabled(row)) return@mapNotNull null
        when (row) {
            "Son Bölümler" -> MainPageData(row, "$mainUrl/dizi-izle")
            "Son Filmler" -> MainPageData(row, "$mainUrl/film-izle")
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val doc = app.get(request.data, headers = uaHeaders).document
        val items = doc.select("a.sc.pcard[href], a.pcard[href]")
            .mapNotNull { it.toHtmlCard() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (page > 1) return newSearchResponseList(emptyList(), false)
        val doc = app.get(
            "$mainUrl/ara",
            params = mapOf("q" to query.trim()),
            headers = uaHeaders,
        ).document
        val items = doc.select("a.sc.pcard[href], a.pcard[href]")
            .mapNotNull { it.toHtmlCard() }
            .distinctBy { it.url }
        return newSearchResponseList(items, false)
    }

    private fun Element.toHtmlCard(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        if (!href.contains("/dizi/") && !href.contains("/film/")) return null
        val title = attr("aria-label").substringBefore(',').trim()
            .ifBlank { selectFirst("b")?.text()?.trim().orEmpty() }
            .ifBlank { selectFirst("img")?.attr("alt")?.trim().orEmpty() }
            .takeIf { it.isNotBlank() } ?: return null
        val poster = fixUrlNull(selectFirst("img")?.attr("src"))
        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = uaHeaders).document
        val ld = doc.select("script[type=application/ld+json]").mapNotNull {
            runCatching { JSONObject(it.data()) }.getOrNull()
        }.firstOrNull {
            it.optString("@type").contains("TVSeries") || it.optString("@type").contains("Movie")
        }
        val title = ld?.optString("name")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: başlık okunamadı. $REPORT_HINT")
        val poster = fixUrlNull(
            doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: ld?.optString("image")?.takeIf { it.startsWith("http") }
        )
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
        if (url.contains("/dizi/")) {
            val episodeArray = ld?.optJSONObject("containsSeason")?.optJSONArray("episode") ?: JSONArray()
            val episodes = (0 until episodeArray.length()).mapNotNull { index ->
                val episode = episodeArray.optJSONObject(index) ?: return@mapNotNull null
                val href = episode.optString("url").takeIf { it.startsWith("http") }
                    ?: return@mapNotNull null
                val number = Regex("""(\d+)""").find(episode.optString("name"))
                    ?.groupValues?.get(1)?.toIntOrNull()
                newEpisode(href) {
                    this.name = episode.optString("name").ifBlank { "Bölüm" }
                    this.season = 1
                    this.episode = number
                }
            }.distinctBy { it.data }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
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
        val page = runCatching { app.get(data, headers = uaHeaders, referer = "$mainUrl/").text }
            .getOrNull() ?: throw ErrorLoadingException("$name: bölüm sayfası yüklenemedi. $REPORT_HINT")
        val normalized = page.replace("\\\"", "\"").replace("\\/", "/")
        val candidates = Regex(""""(?:src|master)"\s*:\s*"(https?://[^"]+)"""")
            .findAll(normalized).map { it.groupValues[1] }
            .filter {
                it.contains("pichive") || it.contains("hotlinger") || it.contains("playru") ||
                    it.endsWith(".m3u8") || it.contains(".mp4")
            }
            .distinct().toList()
        var found = false
        for (candidate in candidates) {
            if (candidate.contains(".m3u8") || candidate.contains(".mp4")) {
                callback(newExtractorLink(name, "AsyaWatch", candidate, ExtractorLinkType.M3U8) {
                    this.referer = "$mainUrl/"
                    this.quality = 0
                })
                found = true
                continue
            }
            val embed = runCatching { app.get(candidate, headers = uaHeaders, referer = "$mainUrl/").text }
                .getOrNull() ?: continue
            for (master in Regex(""""(?:master|file)"\s*:\s*"(https?://[^"]+)"""")
                .findAll(embed).map { it.groupValues[1] }.distinct()) {
                val playlist = runCatching { app.get(master, headers = uaHeaders, referer = candidate).text }
                    .getOrNull() ?: continue
                if (!playlist.startsWith("#EXTM3U")) continue
                callback(newExtractorLink(name, "AsyaWatch", master, ExtractorLinkType.M3U8) {
                    this.referer = candidate
                    this.quality = 0
                })
                found = true
            }
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir kaynak bulunamadı. $REPORT_HINT")
        return true
    }

    companion object {
        val ROWS = listOf("Son Bölümler", "Son Filmler")
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}
