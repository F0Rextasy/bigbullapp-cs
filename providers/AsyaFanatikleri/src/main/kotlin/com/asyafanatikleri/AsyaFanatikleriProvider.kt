package com.asyafanatikleri

import com.bigbull.common.browserHeaders
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
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
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Asya Fanatikleri — asyafanatikleri.vip
 *
 * Arama `/ara?q=` JSON uç noktasıdır (en fazla 12 karışık dizi/film sonucu, sayfalama yok).
 * Liste sayfaları (`/dizi-arsivi`, `/filmler`, ülke sayfaları) `/sayfa/N` ile ilerler;
 * sayfalayıcı `Sayfa 1 / 63` metnini basar. Bölümler `/dizi/<slug>/sezon-N/bolum-M`.
 * Oynatma playerdkorea.xyz üzerindeki FirePlayer'dır: `POST /player/index.php?data=<id>&do=getVideo`
 * imzalı HLS master döner. CDN ölü jetonlara 404 verdiği için akış ancak playlist
 * `#EXTM3U` yanıtladığında iletilir.
 */
class AsyaFanatikleriProvider : MainAPI() {
    override var mainUrl = "https://asyafanatikleri.vip"
    override var name = "Asya Fanatikleri"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage get() = ROWS.mapNotNull { row ->
        if (!Settings.rowEnabled(row)) return@mapNotNull null
        MainPageData(row, mainUrl + ROW_PATHS.getValue(row))
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(pageUrl(request.data, page), headers = browserHeaders).document
        val items = doc.select("a.poster-card[href]").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newHomePageResponse(request, items, hasNext = hasNextPage(doc))
    }

    override suspend fun search(query: String): List<SearchResponse> = searchPage(query)

    override suspend fun search(query: String, page: Int): SearchResponseList? =
        newSearchResponseList(if (page <= 1) searchPage(query) else emptyList(), false)

    override suspend fun quickSearch(query: String): List<SearchResponse>? =
        searchPage(query).ifEmpty { null }

    private suspend fun searchPage(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val body = app.get("$mainUrl/ara?q=${URLEncoder.encode(query, "UTF-8")}", headers = browserHeaders).text
        val items = runCatching { JSONObject(body).optJSONArray("items") }.getOrNull() ?: return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val href = item.optString("url").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val title = item.optString("title").trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val poster = item.optString("poster").takeIf { it.startsWith("http") }
            if (item.optString("type") == "movie") {
                newMovieSearchResponse(title, href) { this.posterUrl = poster }
            } else {
                newTvSeriesSearchResponse(title, href) { this.posterUrl = poster }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url, headers = browserHeaders).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi")
        val ld = jsonLd(doc)
        val title = doc.selectFirst("h1.yd-series-title, h1.yd-movie-title")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: ld?.optString("name")?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi")
        val poster = fixUrlNull(ld?.optString("image")?.takeIf { it.isNotBlank() })
            ?: fixUrlNull(doc.selectFirst("meta[property='og:image']")?.attr("content"))
        val plot = ld?.optString("description")?.trim()?.takeIf { it.isNotBlank() }
        val year = Regex("""(\d{4})""").find(ld?.optString("datePublished").orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()
        val tags = ld?.stringList("genre").orEmpty()
        val score = Score.from10(ld?.optJSONObject("aggregateRating")?.optString("ratingValue"))
        val duration = Regex("""(\d+)\s*(?:dk|min)""")
            .find(ld?.optString("duration").orEmpty())?.groupValues?.get(1)?.toIntOrNull()
        val actors = doc.select("a.yd-aside-cast-item, a.yd-movie-cast-item").mapNotNull { card ->
            val person = card.selectFirst("em")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ActorData(Actor(person, fixUrlNull(card.selectFirst("img")?.attr("src"))))
        }
        val trailer = doc.selectFirst("[data-trailer]")?.attr("data-trailer")?.trim()?.takeIf { it.isNotBlank() }
        val related = doc.select("h2.section-header-title, h3.yd-movie-section-title")
            .firstOrNull { it.text().startsWith(BENZER) }
            ?.parents()?.firstOrNull { it.tagName() == "section" }
            ?.select("a.poster-card[href]")?.mapNotNull { it.toSearchResult() }?.distinctBy { it.url }.orEmpty()

        if (url.contains("/film/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = score
                this.duration = duration
                this.actors = actors
                this.recommendations = related
                if (trailer != null) addTrailer(trailer)
            }
        }

        val episodes = doc.select("div.yd-ep-grid[data-season]").flatMap { grid ->
            val season = grid.attr("data-season").toIntOrNull()
            grid.select("a.yd-ep-link[href]").mapNotNull { anchor ->
                val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
                val label = anchor.selectFirst(".yd-ep-sub")?.text()?.trim()?.takeIf { it.isNotBlank() }
                newEpisode(href) {
                    this.name = label ?: "Bölüm"
                    this.season = season
                    this.episode = label?.let { EPISODE_NO.find(it)?.groupValues?.get(1)?.toIntOrNull() }
                        ?: BOLUM_NO.find(href)?.groupValues?.get(1)?.toIntOrNull()
                }
            }
        }.distinctBy { it.data }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.score = score
            this.actors = actors
            this.recommendations = related
            if (trailer != null) addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val doc = runCatching { app.get(data, headers = browserHeaders).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: oynatılabilir kaynak yok")
        // Fragman iframe'i de sayfada durur; yalnızca FirePlayer kaynakları çözülür.
        val embeds = doc.select("iframe[data-src], iframe[src]")
            .mapNotNull { fixUrlNull(it.attr("data-src").ifBlank { it.attr("src") }.trim()) }
            .filter { it.startsWith("http") && (it.contains("playerdkorea") || it.contains("playerkorea")) }
            .distinct()
        var found = false
        for (embed in embeds) {
            if (loadFirePlayer(embed, data, callback)) found = true
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir kaynak yok")
        return true
    }

    /** FirePlayer (playerdkorea/playerkorea*) — çekirdekte hazır extractor yok. */
    private suspend fun loadFirePlayer(embed: String, pageUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        val origin = embed.substringBefore("/video/").takeIf { it.startsWith("http") } ?: return false
        val id = embed.substringAfter("/video/").substringBefore('?').substringBefore('/')
        if (id.isBlank()) return false
        val body = runCatching {
            app.post(
                "$origin/player/index.php?data=$id&do=getVideo",
                data = mapOf("hash" to id, "r" to pageUrl),
                headers = browserHeaders + mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to pageUrl,
                    "Origin" to origin,
                ),
            ).text
        }.getOrNull() ?: return false
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return false
        // Oynatıcı `videoSource`u (master.txt) kullanır; `securedLink` aynı playlist'in imzalı hâli.
        val stream = json.optString("videoSource").ifBlank { json.optString("securedLink") }
        if (!stream.startsWith("http")) return false
        val live = runCatching {
            app.get(stream, referer = "$origin/", headers = browserHeaders).text.startsWith("#EXTM3U")
        }.getOrDefault(false)
        if (!live) return false
        val link = newExtractorLink(name, "playerDKorea", stream, ExtractorLinkType.M3U8) {
            this.referer = "$origin/"
            this.quality = Qualities.Unknown.value
        }
        if (!Settings.qualityAllowed(link.quality)) return false
        callback.invoke(link)
        return true
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val isMovie = href.contains("/film/")
        if (!isMovie && !href.contains("/dizi/")) return null
        val title = selectFirst(".poster-card-title")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val poster = fixUrlNull(selectFirst("img")?.attr("src"))
        return if (isMovie) {
            newMovieSearchResponse(title, href) { this.posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, href) { this.posterUrl = poster }
        }
    }

    private fun pageUrl(base: String, page: Int): String = if (page <= 1) base else "$base/sayfa/$page"

    /** Sayfalayıcı `Sayfa 2 / 63` basar; tek sayfalık listelerde hiç görünmez. */
    private fun hasNextPage(doc: Document): Boolean {
        val parts = Regex("""(\d+)\s*/\s*(\d+)""")
            .find(doc.selectFirst(".page-info")?.text() ?: return false)?.groupValues ?: return false
        return parts[1].toInt() < parts[2].toInt()
    }

    private fun JSONObject.stringList(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optString(it)?.trim()?.takeIf(String::isNotBlank) }
    }

    private fun jsonLd(doc: Document): JSONObject? = doc.select("script[type='application/ld+json']")
        .firstNotNullOfOrNull { script ->
            runCatching { JSONObject(script.data()) }.getOrNull()
                ?.takeIf { it.optString("@type") in LD_TYPES }
        }

    companion object {
        private const val BENZER = "Benzer"
        private val LD_TYPES = setOf("TVSeries", "Movie")
        private val EPISODE_NO = Regex("""(\d+)""")
        private val BOLUM_NO = Regex("""bolum-(\d+)""")

        /** Ana sayfa satırları, görünen sırayla. */
        val ROWS = listOf(
            "Kore Dizileri",
            "Çin Dizileri",
            "Japon Dizileri",
            "Tayland Dizileri",
            "Tüm Diziler",
            "Filmler",
            "Yakında",
        )

        private val ROW_PATHS = mapOf(
            "Kore Dizileri" to "/kore-dizileri-izle-dq",
            "Çin Dizileri" to "/cin-dizileri",
            "Japon Dizileri" to "/japon-dizileri",
            "Tayland Dizileri" to "/tayland-dizileri",
            "Tüm Diziler" to "/dizi-arsivi",
            "Filmler" to "/filmler",
            "Yakında" to "/yakinda",
        )
    }
}
