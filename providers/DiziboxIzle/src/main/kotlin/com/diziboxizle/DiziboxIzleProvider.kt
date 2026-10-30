package com.diziboxizle

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject

/**
 * DiziboxIzle — diziboxizle.com
 *
 * Search hits are episode posts, so results are folded back to their series page
 * (`<slug>-<n>-sezon-<m>-bolum` -> `<slug>`). The player is a plain ok.ru iframe.
 */
private fun Element.posterSrcLazy(): String? =
    attr("data-src").takeIf { it.isNotBlank() } ?: attr("src")

class DiziboxIzleProvider : MainAPI() {
    override var mainUrl = "https://diziboxizle.com"
    override var name = "DiziboxIzle"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage get() = ROWS.mapNotNull { name ->
        if (!Settings.rowEnabled(name)) return@mapNotNull null
        when (name) {
            "Son Eklenenler" -> MainPageData(name, "$mainUrl/")
            "Aksiyon" -> MainPageData(name, "$mainUrl/dizi-arsivi/?tur=aksiyon")
            "Dram" -> MainPageData(name, "$mainUrl/dizi-arsivi/?tur=drama")
            "IMDb 7+" -> MainPageData(name, "$mainUrl/dizi-arsivi/?filtrele=imdb&sirala=DESC&imdb=7")
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.substringBefore("?").trimEnd('/')
        val query = request.data.substringAfter("?", "")
        val url = when {
            page <= 1 -> request.data
            query.isEmpty() -> "$path/page/$page/"
            else -> "$path/page/$page/?$query"
        }
        val doc = app.get(url).document
        val items = if (path.endsWith("/dizi-arsivi")) {
            doc.select("div.single-item").mapNotNull { it.toArchiveResult() }
        } else {
            doc.select("a[href*='-sezon'][href*='-bolum']")
                .mapNotNull { anchor ->
                    val episodeUrl = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
                    seriesUrl(episodeUrl)?.let { series ->
                        val label = anchor.attr("title").ifBlank { anchor.text() }.trim()
                        newTvSeriesSearchResponse(label, series, TvType.TvSeries)
                    }
                }
                .distinctBy { it.url }
        }
        return newHomePageResponse(request.name, items, hasNext = true)
    }

    /** Archive/genre cards: poster in `.cat-img`, title in `.categorytitle`. */
    private fun org.jsoup.nodes.Element.toArchiveResult(): SearchResponse? {
        val anchor = selectFirst(".categorytitle a") ?: selectFirst(".cat-img a") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        return newTvSeriesSearchResponse(anchor.text().trim(), href, TvType.TvSeries) {
            this.posterUrl = fixUrlNull(selectFirst(".cat-img img")?.posterSrcLazy())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> =
        search(query, 1)?.items ?: emptyList()

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val res = app.get(
            "$mainUrl/wp-json/wp/v2/search",
            params = mapOf("search" to query, "per_page" to "20", "page" to page.toString()),
        )
        if (!res.isSuccessful) return newSearchResponseList(emptyList(), false)
        val results = JSONArray(res.text)
        val items = (0 until results.length()).mapNotNull { index ->
            val item = results.optJSONObject(index) ?: return@mapNotNull null
            val episodeUrl = item.optString("url")
            if (episodeUrl.isBlank()) return@mapNotNull null
            val series = seriesUrl(episodeUrl) ?: return@mapNotNull null
            val title = item.optString("title")
                .replace(Regex("""\s*\d+\.\s*Sezon.*$"""), "")
                .replace(Regex("""\s+izle$"""), "")
                .trim()
            newTvSeriesSearchResponse(title.ifBlank { series.substringAfterLast('/') }, series, TvType.TvSeries)
        }.distinctBy { it.url }
        val totalPages = res.headers["X-WP-TotalPages"]?.toIntOrNull() ?: 0
        return newSearchResponseList(items, page < totalPages)
    }

    /** Header live search (`action=data_fetch`); its nonce is scraped once per process from any page. */
    override suspend fun quickSearch(query: String): List<SearchResponse>? = quickSearch(query, false)

    private suspend fun quickSearch(query: String, refreshedNonce: Boolean): List<SearchResponse>? {
        if (query.length < 3) return null
        val nonce = quickSearchNonce(refreshedNonce) ?: return null
        val res = app.post(
            "$mainUrl/wp-admin/admin-ajax.php",
            data = mapOf("action" to "data_fetch", "keyword" to query, "_wpnonce" to nonce),
        )
        if (res.text.trim() == "0") { // nonce rejected
            return if (refreshedNonce) null else quickSearch(query, true)
        }
        return res.document.select(".searchelement").mapNotNull { element ->
            val anchor = element.children().firstOrNull { it.tagName() == "a" } ?: return@mapNotNull null
            val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            val series = seriesUrl(href) ?: href
            val title = anchor.text().trim().ifBlank { series.trimEnd('/').substringAfterLast('/') }
            newTvSeriesSearchResponse(title, series, TvType.TvSeries) {
                this.posterUrl = fixUrlNull(element.selectFirst(".search-cat-img img")?.posterSrcLazy())
                this.year = element.selectFirst("#search-cat-year")?.text()?.trim()?.toIntOrNull()
            }
        }.distinctBy { it.url }
    }

    private suspend fun quickSearchNonce(force: Boolean): String? {
        if (!force) cachedQuickSearchNonce?.let { return it }
        val html = app.get("$mainUrl/").text
        return Regex("""live_search_ajax\s*=\s*\{[^}]*"admin_ajax_nonce"\s*:\s*"([a-f0-9]+)""")
            .find(html)?.groupValues?.get(1)
            .also { cachedQuickSearchNonce = it }
    }

    companion object {
        /** Homepage rows in display order; genre rows map to live `/dizi-arsivi/` filters. */
        val ROWS = listOf("Son Eklenenler", "Aksiyon", "Dram", "IMDb 7+")

        private var cachedQuickSearchNonce: String? = null
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }

    private fun seriesUrl(episodeUrl: String): String? {
        val slug = episodeUrl.trimEnd('/').substringAfterLast('/')
        val base = slug.replace(Regex("""-\d+-sezon-\d+-bolum.*$"""), "")
        return if (base == slug || base.isBlank()) null else "$mainUrl/$base/"
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?.replace(Regex("""\s*izle.*$"""), "")?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")

        val episodes = doc.select("a[href*='-sezon'][href*='-bolum']").mapNotNull { anchor ->
            val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            val label = anchor.text().trim().ifBlank { anchor.attr("title").trim() }
            newEpisode(href) {
                this.name = label
                this.season = Regex("""(\d+)\.\s*Sezon""").find(label)?.groupValues?.get(1)?.toIntOrNull()
                this.episode = Regex("""(\d+)\.\s*Bölüm""").find(label)?.groupValues?.get(1)?.toIntOrNull()
            }
        }.distinctBy { it.data }

        if (episodes.isEmpty()) throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")

        val trailerId = doc.selectFirst("iframe.trailer-video")?.attr("src")
            ?.let { Regex("""(?:embed/|v=)([\w-]{11})""").find(it)?.groupValues?.get(1) }
        val score = doc.selectFirst(".imdb-row .imdb-score")?.text()?.trim()?.toDoubleOrNull()
        val cast = doc.selectFirst("span.dizimeta:contains(Oyuncular)")?.parent()?.text()
            ?.let { Regex("""Oyuncular\s*:\s*(.+)""").find(it)?.groupValues?.get(1) }
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.map { ActorData(Actor(it)) }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
            this.plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
            this.score = score?.let { Score.from10(it) }
            this.actors = cast
            if (trailerId != null) addTrailer("https://www.youtube.com/embed/$trailerId")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val seenLinks = mutableSetOf<String>()
        val seenSubs = mutableSetOf<String>()
        val linkCallback: (ExtractorLink) -> Unit = {
            if (seenLinks.add(it.url) && Settings.qualityAllowed(it.quality)) callback(it)
        }
        val subtitleFilter: (SubtitleFile) -> Unit = {
            if (seenSubs.add(it.url) && Settings.subtitleAllowed(it.lang)) subtitleCallback(it)
        }
        val doc = runCatching { app.get(data).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: kaynak bulunamadı. $REPORT_HINT")
        val iframes = doc.select("iframe[src]").map { fixUrl(it.attr("src")) }.distinct()
        var found = false
        iframes.forEach { embed ->
            val ok = if (embed.contains("ok.ru") || embed.contains("odnoklassniki")) {
                loadOkRu(embed, linkCallback)
            } else {
                loadExtractor(embed, "$mainUrl/", subtitleFilter, linkCallback)
            }
            if (ok) found = true
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir akış yok. $REPORT_HINT")
        return found
    }

    /**
     * ok.ru serves its metadata HTML-escaped (`&quot;`), while the core extractor
     * only unescapes `\&quot;`. Unescape before reading the `videos` array.
     */
    private suspend fun loadOkRu(embed: String, callback: (ExtractorLink) -> Unit): Boolean {
        val id = Regex("""videoembed/(\d+)""").find(embed)?.groupValues?.get(1) ?: return false
        val body = runCatching {
            app.post(
                "https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$id",
                data = emptyMap(),
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 13)",
                    "Referer" to "https://ok.ru/",
                ),
            ).text
        }.getOrNull() ?: return false
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return false
        val videos = json.optJSONArray("videos") ?: return false
        var found = false
        for (i in 0 until videos.length()) {
            val item = videos.optJSONObject(i) ?: continue
            val stream = item.optString("url").takeIf { it.startsWith("http") } ?: continue
            val label = item.optString("name").ifBlank { "OkRu" }
            callback.invoke(
                newExtractorLink(name, "OkRu $label", stream, INFER_TYPE) {
                    this.referer = "https://ok.ru/"
                    this.quality = getQualityFromName(label.uppercase())
                }
            )
            found = true
        }
        return found
    }
}
