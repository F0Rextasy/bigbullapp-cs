package com.startv

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
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * StarTV — startv.com.tr dizileri.
 *
 * Sayfa verisi `"name":"...","slug":"..."` çiftleri taşır. Bölüm oynatıcısı DYG'dir:
 * `dyg:tags` son parçası içerik kimliğidir; `PublisherId=1` ve `StarTv_<id>` ile
 * `video_info` çağrılır. Dönen `flavors.hls` anahtarsız HLS manifestidir.
 */
class StarTVProvider : MainAPI() {
    override var mainUrl = "https://www.startv.com.tr"
    override var name = "StarTV"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage get() = ROWS.mapNotNull { row ->
        if (row == "Diziler" && Settings.rowEnabled(row)) MainPageData(row, "$mainUrl/dizi") else null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val html = app.get(request.data).text
        val items = parseSeries(html).take(60).map { (title, slug) -> searchResult(title, slug) }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val html = app.get("$mainUrl/arama-sonuclari", params = mapOf("q" to query.trim())).text
        val needle = normalize(query)
        return parseSeries(html).mapNotNull { (title, slug) ->
            if (needle.isBlank() || normalize(title).contains(needle)) searchResult(title, slug) else null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val seriesUrl = url.substringBefore("/bolumler").trimEnd('/')
        val doc = app.get(seriesUrl).document
        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: dizi adı okunamadı. $REPORT_HINT")
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrlNull(it) }
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
        val episodesDoc = app.get("$seriesUrl/bolumler").document
        val episodes = episodesDoc.select("a[href*='/bolumler/']").mapNotNull { episode ->
            val href = fixUrlNull(episode.attr("href")) ?: return@mapNotNull null
            val number = EPISODE_NO.find(href)?.groupValues?.get(1)?.toIntOrNull()
            val label = episode.text().trim().takeIf { it.contains("Bölüm", true) }
                ?: number?.let { "$it. Bölüm" }
                ?: href.substringAfterLast('/').replace('-', ' ')
            newEpisode(href) {
                this.name = label
                this.episode = number
            }
        }.distinctBy { it.data }
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
        val page = app.get(data, referer = "$mainUrl/").text
        val contentId = TAGS.find(page)?.groupValues?.get(1)?.split(',')?.lastOrNull()?.trim()
            ?: REFERENCE.find(page)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("$name: içerik kimliği bulunamadı. $REPORT_HINT")
        val api = "$DYG_API?akamai=true&PublisherId=1&ReferenceId=StarTv_$contentId&SecretKey=$DYG_SECRET"
        val json = runCatching {
            JSONObject(app.get(api, headers = mapOf("User-Agent" to UA), referer = "$mainUrl/").text)
        }.getOrNull() ?: throw ErrorLoadingException("$name: video bilgisi alınamadı. $REPORT_HINT")
        val flavors = json.optJSONObject("data")?.optJSONObject("flavors")
            ?: throw ErrorLoadingException("$name: video kaynağı yok. $REPORT_HINT")
        val master = flavors.optString("hls").takeIf { it.startsWith("http") }
            ?: flavors.optJSONObject("0")?.optString("file_url_1")?.takeIf { it.startsWith("http") }
            ?: throw ErrorLoadingException("$name: HLS adresi yok. $REPORT_HINT")
        val playlist = runCatching {
            app.get(master, referer = "$mainUrl/", headers = mapOf("User-Agent" to UA)).text
        }.getOrNull().orEmpty()
        val variants = parseVariants(playlist, master)
        var emitted = 0
        suspend fun emit(url: String, quality: Int) {
            if (!Settings.qualityAllowed(quality)) return
            callback(newExtractorLink(name, if (quality > 0) "$name ${quality}p" else name, url, ExtractorLinkType.M3U8) {
                this.referer = "$mainUrl/"
                this.quality = quality
                this.headers = mapOf("User-Agent" to UA)
            })
            emitted++
        }
        if (variants.isEmpty()) emit(master, 0) else variants.forEach { (quality, url) -> emit(url, quality) }
        if (emitted == 0) throw ErrorLoadingException("$name: oynatılabilir kaynak yok. $REPORT_HINT")
        return true
    }

    private fun parseSeries(html: String): List<Pair<String, String>> {
        val text = html.replace("\\\"", "\"")
        val valid = Jsoup.parse(html).select("a[href^=/dizi/]")
            .mapNotNull { it.attr("href").substringAfter("/dizi/").substringBefore("/").takeIf(String::isNotBlank) }
            .toSet()
        if (valid.isEmpty()) return emptyList()
        return SERIES.findAll(text).mapNotNull { match ->
            val title = match.groupValues[1].trim()
            val slug = match.groupValues[2].trim()
            if (title.isBlank() || slug.isBlank() || slug !in valid) null else title to slug
        }.distinctBy { it.second }.toList()
    }

    private fun searchResult(title: String, slug: String): SearchResponse =
        newTvSeriesSearchResponse(title, "$mainUrl/dizi/$slug", TvType.TvSeries)

    private fun parseVariants(playlist: String, base: String): List<Pair<Int, String>> {
        val variants = mutableListOf<Pair<Int, String>>()
        var quality = 0
        for (line in playlist.lineSequence()) {
            val text = line.trim()
            when {
                text.startsWith("#EXT-X-STREAM-INF") ->
                    quality = RESOLUTION.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                text.isEmpty() || text.startsWith("#") -> Unit
                quality > 0 -> {
                    variants += quality to runCatching { URI(base).resolve(text).toString() }.getOrDefault(text)
                    quality = 0
                }
            }
        }
        return variants
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
        .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')

    companion object {
        val ROWS = listOf("Diziler")
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val DYG_API = "https://dygvideo.dygdigital.com/api/video_info"
        private const val DYG_SECRET = "NtvApiSecret2014*"
        private val SERIES = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"slug\"\\s*:\\s*\"([^\"]+)\"")
        private val EPISODE_NO = Regex("""/bolumler/(\d+)-bolum""")
        private val TAGS = Regex("""dyg:tags"\s+content="([^"]+)"""")
        private val REFERENCE = Regex(""""referenceId"\s*:\s*"([a-f0-9]{24})"""")
        private val RESOLUTION = Regex("""RESOLUTION=\d+x(\d+)""")
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}
