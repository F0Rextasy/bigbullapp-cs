package com.trt1

import com.bigbull.common.browserHeaders
import com.bigbull.common.parseHlsVariants
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.Episode
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
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * TRT1 — trt1.com.tr resmî dizi arşivi.
 *
 * Next.js sayfasının `__NEXT_DATA__` bloğu `pageComponents.componentData` içinde sayfanın
 * bütün verisini taşır: liste sayfalarında `GridGenerator` (kartlar + `pagination.totalPages`),
 * dizi sayfasında `Breadcrumbs`/`MediaDetailCard`, bölüm sayfasında `MediaDetailCard.media`.
 * Bölüm listesi 12'lik sayfalara bölünür (`/diziler/<slug>/bolum/<sayfa>`).
 *
 * Oynatıcı `media` alanında iki biçimde gelir: eski bölümlerde `platform=asset` + doğrudan
 * `master.m3u8`, güncel bölümlerde `platform=youtube` + video kimliği (loadExtractor).
 */
class TRT1Provider : MainAPI() {
    override var mainUrl = "https://www.trt1.com.tr"
    override var name = "TRT1"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage get() = ROWS.mapNotNull { row ->
        val archive = when (row) {
            "Güncel Diziler" -> "false"
            "Arşiv Diziler" -> "true"
            else -> return@mapNotNull null
        }
        if (!Settings.rowEnabled(row)) return@mapNotNull null
        MainPageData(row, "$mainUrl/diziler?archive=$archive")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else request.data.replace("$mainUrl/diziler", "$mainUrl/diziler/$page")
        val grid = grid(url) ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        return newHomePageResponse(request.name, cards(grid), hasNext = page < totalPages(grid))
    }

    override suspend fun search(query: String): List<SearchResponse> = search(query, 1)?.items ?: emptyList()

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val term = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val url = if (page <= 1) "$mainUrl/arama/$term?contenttype=series"
        else "$mainUrl/arama/$term/$page?contenttype=series"
        val grid = grid(url) ?: return newSearchResponseList(emptyList(), hasNext = false)
        return newSearchResponseList(cards(grid), hasNext = page < totalPages(grid))
    }

    override suspend fun load(url: String): LoadResponse? {
        val seriesUrl = url.substringBefore('?').trimEnd('/')
        val json = nextData(app.get(seriesUrl, headers = browserHeaders).text)
            ?: throw ErrorLoadingException("$name: dizi verisi okunamadı. $REPORT_HINT")
        val title = breadcrumbTitle(json)
            ?: seoTitle(json)
            ?: throw ErrorLoadingException("$name: dizi adı okunamadı. $REPORT_HINT")
        val detail = component(json, "MediaDetailCard")?.optJSONObject("data")
        val media = detail?.optJSONObject("media")
        val poster = media?.optString("src")?.takeIf { media.optString("type") == "image" && it.startsWith("http") }
        val plot = detail?.optString("detail")?.let { Jsoup.parse(it).text().trim() }?.takeIf { it.isNotBlank() }
        return newTvSeriesLoadResponse(title, seriesUrl, TvType.TvSeries, episodes(seriesUrl)) {
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
        val json = nextData(app.get(data, headers = browserHeaders).text)
            ?: throw ErrorLoadingException("$name: bölüm verisi okunamadı. $REPORT_HINT")
        val media = component(json, "MediaDetailCard")?.optJSONObject("data")?.optJSONObject("media")
            ?: throw ErrorLoadingException("$name: oynatıcı bulunamadı. $REPORT_HINT")
        when (media.optString("platform")) {
            "asset" -> {
                val master = media.optString("src").takeIf { it.startsWith("http") }
                    ?: throw ErrorLoadingException("$name: akış adresi yok. $REPORT_HINT")
                val playlist = app.get(master, headers = browserHeaders, referer = "$mainUrl/").text
                val variants = parseHlsVariants(master, playlist)
                var emitted = 0
                suspend fun emit(url: String, quality: Int) {
                    if (!Settings.qualityAllowed(quality)) return
                    val type = if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback(newExtractorLink(name, if (quality > 0) "$name ${quality}p" else name, url, type) {
                        this.referer = "$mainUrl/"
                        this.quality = quality
                        this.headers = browserHeaders
                    })
                    emitted++
                }
                if (variants.isEmpty()) emit(master, 0) else variants.forEach { (quality, url) -> emit(url, quality) }
                if (emitted == 0) throw ErrorLoadingException("$name: oynatılabilir kaynak yok. $REPORT_HINT")
            }
            "youtube" -> {
                val id = media.optString("id").takeIf { it.isNotBlank() }
                    ?: throw ErrorLoadingException("$name: YouTube kimliği yok. $REPORT_HINT")
                if (!loadExtractor("https://www.youtube.com/embed/$id", "$mainUrl/", subtitleCallback, callback))
                    throw ErrorLoadingException("$name: YouTube akışı çözülemedi. $REPORT_HINT")
            }
            else -> throw ErrorLoadingException(
                "$name: desteklenmeyen oynatıcı (${media.optString("platform")}). $REPORT_HINT",
            )
        }
        return true
    }

    /**
     * Bölüm listesini 12'lik sayfalar hâlinde toplar; sezon kavramı sitede yok.
     * Site toplu liste ucu sunmadığı için uzun dizilerde (Seksenler 31 sayfa) sayfa sayfa çekilir.
     */
    private suspend fun episodes(seriesUrl: String): List<Episode> {
        val listUrl = "$seriesUrl/bolum"
        val first = grid(listUrl) ?: return emptyList()
        val found = LinkedHashMap<String, Episode>()
        fun collect(grid: JSONObject) {
            val items = grid.optJSONArray("data") ?: return
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                if (item.optString("type") != "series-episode") continue
                val path = item.optString("path").takeIf { it.startsWith("/diziler/") } ?: continue
                val href = "$mainUrl$path"
                if (found.containsKey(href)) continue
                val number = EPISODE_NO.find(path)?.groupValues?.get(1)?.toIntOrNull()
                val label = item.optString("title").trim().ifBlank { number?.let { "$it. Bölüm" } ?: "Bölüm" }
                found[href] = newEpisode(href) {
                    this.name = label
                    this.episode = number
                    this.posterUrl = item.optJSONObject("images")?.optString("coverImage")?.takeIf { it.startsWith("http") }
                }
            }
        }
        collect(first)
        for (page in 2..totalPages(first)) {
            val next = grid("$listUrl/$page") ?: break
            collect(next)
        }
        return found.values.sortedWith(compareBy({ it.episode ?: Int.MAX_VALUE }, { it.name }))
    }

    private suspend fun grid(url: String): JSONObject? =
        component(nextData(app.get(url, headers = browserHeaders).text), "GridGenerator")

    /** `componentData` listesinden istenen bileşenin `data` gövdesini döndürür. */
    private fun component(json: JSONObject?, name: String): JSONObject? {
        val list = json?.optJSONObject("props")?.optJSONObject("pageProps")
            ?.optJSONObject("pageComponents")?.optJSONArray("componentData") ?: return null
        for (i in 0 until list.length()) {
            val data = list.optJSONObject(i)?.optJSONObject("data") ?: continue
            if (data.optString("component") == name) return data
        }
        return null
    }

    private fun nextData(html: String): JSONObject? {
        val raw = Jsoup.parse(html).selectFirst("script#__NEXT_DATA__")?.data() ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    private fun cards(grid: JSONObject): List<SearchResponse> {
        val items = grid.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<SearchResponse>(items.length())
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            if (item.optString("type") != "series") continue
            val path = item.optString("path").takeIf { it.startsWith("/diziler/") } ?: continue
            val title = item.optString("title").trim().takeIf { it.isNotBlank() } ?: continue
            out.add(newTvSeriesSearchResponse(title, "$mainUrl$path", TvType.TvSeries) {
                this.posterUrl = poster(item)
            })
        }
        return out.distinctBy { it.url }
    }

    private fun poster(item: JSONObject): String? {
        val images = item.optJSONObject("images") ?: return null
        return images.optString("verticalImage").takeIf { it.startsWith("http") }
            ?: images.optString("horizontalImage").takeIf { it.startsWith("http") }
    }

    private fun totalPages(grid: JSONObject): Int =
        grid.optJSONObject("pagination")?.optInt("totalPages", 1)?.coerceAtLeast(1) ?: 1

    private fun breadcrumbTitle(json: JSONObject): String? {
        val items = component(json, "Breadcrumbs")?.optJSONArray("data") ?: return null
        return (items.length() - 1 downTo 0)
            .mapNotNull { items.optJSONObject(it)?.optString("title")?.trim() }
            .firstOrNull { it.isNotBlank() }
    }

    private fun seoTitle(json: JSONObject): String? {
        val seo = json.optJSONObject("props")?.optJSONObject("pageProps")
            ?.optJSONObject("data")?.optJSONArray("pageSeo") ?: return null
        for (i in 0 until seo.length()) {
            val entry = seo.optJSONObject(i) ?: continue
            if (entry.optString("seoKey") == "title") return entry.optString("seoValue").trim().takeIf { it.isNotBlank() }
        }
        return null
    }

    companion object {
        val ROWS = listOf("Güncel Diziler", "Arşiv Diziler")
        private val EPISODE_NO = Regex("""/bolum/(\d+)""")
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}
