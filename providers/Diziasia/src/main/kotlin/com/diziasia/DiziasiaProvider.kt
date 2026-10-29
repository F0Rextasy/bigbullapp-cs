package com.diziasia

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
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.json.JSONArray

/** DiziAsia — diziasia.com (Asya dizileri/filmleri, ok.ru + vidmoly player) */
class DiziasiaProvider : MainAPI() {
    override var mainUrl = "https://diziasia.com"
    override var name = "DiziAsia"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage get() = ROWS.mapNotNull { name ->
        if (!Settings.rowEnabled(name)) return@mapNotNull null
        when (name) {
            "Son Diziler" -> MainPageData(name, "$mainUrl/diziler?page=")
            "Son Filmler" -> MainPageData(name, "$mainUrl/filmler?page=")
            "Popüler" -> MainPageData(name, "$mainUrl/populer?page=")
            "Kore Dizileri" -> MainPageData(name, "$mainUrl/kore-dizileri?page=")
            "Çin Dizileri" -> MainPageData(name, "$mainUrl/cin-dizileri?page=")
            "Japon Dizileri" -> MainPageData(name, "$mainUrl/japon-dizileri?page=")
            "Tayland Dizileri" -> MainPageData(name, "$mainUrl/tayland-dizileri?page=")
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data + page).document
        val items = doc.select("a.card[href]").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        // The site serves 24 cards per page and always renders pagination, so the count is the reliable stop sign.
        return newHomePageResponse(request.name, items, items.size >= 24)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        if (href.contains("sezon")) return null
        val title = selectFirst("h3.title")?.text()?.trim().orEmpty()
        if (title.isBlank()) return null
        val poster = fixUrlNull(selectFirst("img[data-src]")?.attr("data-src"))
        val year = select("ul li").lastOrNull()?.text()?.trim()?.toIntOrNull()
        return if (href.contains("/film/")) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    /** The pretty route `/arama/<q>` is the real search; `?q=` is ignored and lists the latest titles. */
    override suspend fun search(query: String): List<SearchResponse> = search(query, 1)?.items ?: emptyList()

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/arama/$encoded?page=$page").document
        val items = doc.select("a.card[href]").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        // The results page prints 24 cards; a shorter page is the last one.
        return newSearchResponseList(items, items.size >= 24)
    }

    /** Header live search (`data-suggest` on the search form), capped server-side at 7 instant hits. */
    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/ajax/search?q=$encoded").document
        return doc.select("a.ss-item[href]").mapNotNull { item ->
            val href = fixUrlNull(item.attr("href")) ?: return@mapNotNull null
            val title = item.selectFirst(".ss-title")?.text()?.trim().orEmpty()
            if (title.isBlank()) return@mapNotNull null
            val poster = fixUrlNull(item.selectFirst("img")?.attr("src"))
            val isMovie = item.selectFirst(".ss-meta")?.text()?.contains("Film") == true
            if (isMovie) {
                newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
            } else {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val poster = fixUrlNull(doc.selectFirst("img[data-src]")?.attr("data-src"))
        val meta = doc.selectFirst("ul.list-inline.list-separator")?.select("li")?.map { it.text().trim() }.orEmpty()
        val year = meta.lastOrNull()?.toIntOrNull()
        val tags = doc.selectFirst(".card-tag p")?.text()?.split(",")
            ?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
        // The plot <p> sits right after the genre block on both detail layouts.
        val plot = doc.selectFirst(".card-tag")?.nextElementSibling()?.text()?.trim()

        if (url.contains("/film/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }

        val seasons = doc.select(".accordion-item")
        val multiSeason = seasons.size > 1
        val episodes = seasons.flatMap { block ->
            val season = Regex("""(\d+)""").find(block.selectFirst(".accordion-header")?.text().orEmpty())
                ?.groupValues?.get(1)?.toIntOrNull() ?: 1
            block.select("a.episode[href]").mapNotNull { anchor ->
                val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
                val label = anchor.text().trim()
                val number = Regex("""(\d+)""").find(label)?.groupValues?.get(1)?.toIntOrNull()
                newEpisode(href) {
                    this.name = if (multiSeason) "$season. Sezon $label" else label
                    this.season = season
                    this.episode = number
                }
            }
        }.distinctBy { it.data }
        // "Sizin için önerilenler" block; only the series layout renders it.
        val recommendations = doc.selectFirst("h3.layout-title:contains(Sizin için önerilenler)")
            ?.closest("div.layout-section")
            ?.select("a.card[href]")
            ?.mapNotNull { it.toSearchResult() }
            ?.distinctBy { it.url }
            .orEmpty()
        if (episodes.isEmpty()) throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.recommendations = recommendations
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
        val ids = doc.select(".btn-stream[data-id]").map { it.attr("data-id") }
            .filter { it.isNotBlank() }.distinct()
        var found = false
        for (id in ids) {
            // Player sources load over AJAX: POST /ajax/embed {id} -> <iframe data-src=...>
            val html = runCatching {
                app.post(
                    "$mainUrl/ajax/embed",
                    data = mapOf("id" to id),
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to data,
                        "Origin" to mainUrl,
                    ),
                ).text
            }.getOrNull() ?: continue

            val iframe = Jsoup.parse(html).selectFirst("iframe") ?: continue
            val src = iframe.attr("data-src").ifBlank { iframe.attr("src") }.trim()
            if (src.isBlank()) continue
            val embed = fixUrl(src)

            val ok = when {
                embed.contains("ok.ru") || embed.contains("odnoklassniki") -> loadOkRu(embed, linkCallback)
                embed.contains("vk.com/video_ext") || embed.contains("vkvideo.ru") -> loadVk(embed, linkCallback)
                else -> loadExtractor(embed, "$mainUrl/", subtitleFilter, linkCallback)
            }
            if (ok) found = true
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir akış yok. $REPORT_HINT")
        return found
    }

    /** ok.ru/odnoklassniki videoembed HTML-escapes its player JSON; unescape, then read the videos array. */
    private suspend fun loadOkRu(embed: String, callback: (ExtractorLink) -> Unit): Boolean {
        val body = app.get(embed, referer = "$mainUrl/").text.replace("&quot;", "\"")
        val array = Regex(""""videos"\s*:\s*(\[.*?])""", RegexOption.DOT_MATCHES_ALL)
            .find(body)?.groupValues?.get(1) ?: return false
        val videos = runCatching { JSONArray(array) }.getOrNull() ?: return false

        var found = false
        for (i in 0 until videos.length()) {
            val video = videos.optJSONObject(i) ?: continue
            val stream = video.optString("url")
            if (stream.isBlank()) continue
            callback.invoke(
                newExtractorLink(name, "ok.ru", stream, INFER_TYPE) {
                    this.referer = "https://ok.ru/"
                    this.quality = okRuQuality(video.optString("name"))
                }
            )
            found = true
        }
        return found
    }

    /** vkvideo.ru demands a cookie chain; the same embed on vk.com answers directly. */
    private suspend fun loadVk(embed: String, callback: (ExtractorLink) -> Unit): Boolean {
        val page = app.get(embed.replace("vkvideo.ru", "vk.com"), referer = "$mainUrl/").text
        var found = false
        Regex("\"(mp4_\\d+|hls)\":\"(https:[^\"]+)\"").findAll(page).forEach { match ->
            val label = match.groupValues[1]
            val stream = match.groupValues[2].replace("\\/", "/").replace("\\u0026", "&")
            callback.invoke(
                newExtractorLink(
                    name,
                    "VK",
                    stream,
                    if (label == "hls") ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    this.referer = "https://vk.com/"
                    this.quality = if (label == "hls") Qualities.Unknown.value
                    else vkQuality(label.removePrefix("mp4_"))
                }
            )
            found = true
        }
        return found
    }

    private fun vkQuality(height: String): Int = when (height) {
        "240" -> Qualities.P240.value
        "360" -> Qualities.P360.value
        "480" -> Qualities.P480.value
        "720" -> Qualities.P720.value
        "1080" -> Qualities.P1080.value
        "1440" -> Qualities.P1440.value
        "2160" -> Qualities.P2160.value
        else -> Qualities.P144.value
    }

    private fun okRuQuality(label: String): Int = when (label) {
        "mobile", "lowest" -> Qualities.P240.value
        "low" -> Qualities.P360.value
        "sd" -> Qualities.P480.value
        "hd" -> Qualities.P720.value
        "full" -> Qualities.P1080.value
        else -> Qualities.Unknown.value
    }

    companion object {
        /** Homepage row labels, in display order; each maps to a live listing page above. */
        val ROWS = listOf(
            "Son Diziler",
            "Son Filmler",
            "Popüler",
            "Kore Dizileri",
            "Çin Dizileri",
            "Japon Dizileri",
            "Tayland Dizileri",
        )
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}
