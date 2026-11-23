package com.cizgivedizi

import android.util.Base64
import com.bigbull.common.browserHeaders
import com.bigbull.common.posterSrcLazy
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
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newAnimeSearchResponse
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
import java.io.BufferedReader
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class CizgiVeDiziProvider : MainAPI() {
    override var mainUrl = BASE_URL
    override var name = "CizgiVeDizi"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    override val mainPage
        get() = ROWS.mapNotNull { rowName ->
            if (!Settings.rowEnabled(rowName)) return@mapNotNull null
            when (rowName) {
                "Çizgi Diziler" -> MainPageData(rowName, "cizgi")
                "Animeler" -> MainPageData(rowName, "anime")
                "Diziler" -> MainPageData(rowName, "dizi")
                "Filmler" -> MainPageData(rowName, "film")
                else -> null
            }
        }

    internal data class VideoSourceItem(
        val label: String,
        val bridgeKey: String,
    )

    private fun CatalogEntry.toTvType(): TvType = when (type) {
        "film" -> TvType.Movie
        "anime" -> TvType.Anime
        else -> TvType.TvSeries
    }

    private fun CatalogEntry.toSearchResponse(): SearchResponse {
        val fullUrl = fixUrl(href)
        // shortcut: site yalnız .avif poster veriyor; Android 14+ native açar, Android <=13 c2.android.av1.decoder reddedebilir.
        val fullPoster = poster.takeIf { it.isNotBlank() }?.let { fixUrlNull(it) }
        return when (toTvType()) {
            TvType.Movie -> newMovieSearchResponse(title, fullUrl, TvType.Movie) { this.posterUrl = fullPoster }
            TvType.Anime -> newAnimeSearchResponse(title, fullUrl, TvType.Anime) { this.posterUrl = fullPoster }
            else -> newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries) { this.posterUrl = fullPoster }
        }
    }

    private fun loadCatalog(): List<CatalogEntry> {
        val now = System.currentTimeMillis()
        cachedCatalog?.let { if ((now - cachedAtMs) < CATALOG_TTL_MS) return it }
        synchronized(catalogLock) {
            val recheckNow = System.currentTimeMillis()
            cachedCatalog?.let { if ((recheckNow - cachedAtMs) < CATALOG_TTL_MS) return it }
            val req = Request.Builder()
                .url("$mainUrl/")
                .header("User-Agent", CvdVideoProxy.USER_AGENT)
                .header("Accept-Language", browserHeaders.getValue("accept-language"))
                .build()
            val entries = CvdVideoProxy.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw ErrorLoadingException("$name: HTTP ${resp.code}")
                BufferedReader(resp.body.charStream(), 32 * 1024).use { reader ->
                    if (!CvdParser.advancePastMarker(reader, POOL_MARKER)) {
                        throw ErrorLoadingException("$name: katalog verisi bulunamadı")
                    }
                    CvdParser.parsePoolData(reader)
                }
            }
            if (entries.isEmpty()) throw ErrorLoadingException("$name: katalog listesi boş")
            cachedCatalog = entries
            cachedById = entries.associateBy { it.id.lowercase(Locale.ROOT) }
            cachedAtMs = System.currentTimeMillis()
            return entries
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val catalog = loadCatalog()
        val filtered = catalog.filter { it.type == request.data }
        val from = (page - 1) * PAGE_SIZE
        if (from >= filtered.size) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val to = minOf(from + PAGE_SIZE, filtered.size)
        val items = filtered.subList(from, to).map { it.toSearchResponse() }
        return newHomePageResponse(request.name, items, hasNext = to < filtered.size)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        search(query, 1)?.items ?: emptyList()

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val normQuery = CvdParser.normalizeSearch(query)
        if (normQuery.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)
        val tokens = normQuery.split(' ').filter { it.isNotBlank() }
        val catalog = loadCatalog()
        val matched = catalog.mapNotNull { entry ->
            val normTitle = CvdParser.normalizeSearch(entry.title)
            val normEn = CvdParser.normalizeSearch(entry.englishTitle)
            val normHay = CvdParser.normalizeSearch(entry.hay)
            val score = when {
                normTitle == normQuery || normEn == normQuery -> 0
                normTitle.startsWith(normQuery) || normEn.startsWith(normQuery) -> 1
                normTitle.contains(normQuery) || normEn.contains(normQuery) -> 2
                tokens.all { tok -> normHay.contains(tok) } -> 3
                else -> return@mapNotNull null
            }
            score to entry
        }.sortedBy { it.first }.map { it.second }

        val from = (page - 1) * PAGE_SIZE
        if (from >= matched.size) return newSearchResponseList(emptyList(), hasNext = false)
        val to = minOf(from + PAGE_SIZE, matched.size)
        return newSearchResponseList(matched.subList(from, to).map { it.toSearchResponse() }, hasNext = to < matched.size)
    }

    private fun Element.toRecoCard(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val title = selectFirst(".r-tit")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: attr("title").trim().takeIf { it.isNotBlank() }
            ?: return null
        val poster = fixUrlNull(selectFirst("img")?.posterSrcLazy())
        return if (href.contains("/film/")) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
        }
    }

    private fun fetchRecommendations(endpoint: String): List<SearchResponse> = runCatching {
        val html = fetchText("$mainUrl$endpoint")
        Jsoup.parse(html, mainUrl).select("a.r-item[href]")
            .mapNotNull { it.toRecoCard() }
            .distinctBy { it.url }
            .take(20)
    }.getOrDefault(emptyList())

    override suspend fun load(url: String): LoadResponse? {
        val absUrl = fixUrl(url)
        val html = runCatching { fetchText(absUrl) }
            .getOrElse { throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT") }
        val doc = Jsoup.parse(html, mainUrl)
        val isFilm = absUrl.contains("/film/")

        if (isFilm) {
            val filmId = CvdParser.extractPathId(absUrl, "/film/")
            val catalogItem = filmId?.let { cachedById[it.lowercase(Locale.ROOT)] }
            val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("#videoDataContainer")?.attr("data-film-title")?.trim()?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" İzle")?.trim()
                ?: catalogItem?.title
                ?: throw ErrorLoadingException("$name: başlık okunamadı")
            val poster = fixUrlNull(
                doc.selectFirst("meta[property=og:image]")?.attr("content")
                    ?: catalogItem?.poster,
            )
            val plot = doc.selectFirst("div.meta .summary-content, .ep-summary-box .summary-content")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
            val tags = catalogItem?.let {
                (it.genres + listOfNotNull(it.channel.takeIf { ch -> ch.isNotBlank() })).distinct()
            }
            val recos = filmId?.let {
                fetchRecommendations("/film_onerilerim.php?filmid=${URLEncoder.encode(it, "UTF-8")}")
            }.orEmpty()
            return newMovieLoadResponse(title, absUrl, TvType.Movie, "$absUrl#cvd-film") {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.recommendations = recos
            }
        }

        val showId = CvdParser.extractPathId(absUrl, "/dizi/")
            ?: throw ErrorLoadingException("$name: dizi kimliği bulunamadı")
        val catalogItem = cachedById[showId.lowercase(Locale.ROOT)]
        val title = doc.selectFirst("div.poster-over-name")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.removeSuffix(" Türkçe İzle")?.trim()
            ?: catalogItem?.title
            ?: throw ErrorLoadingException("$name: başlık okunamadı")
        val poster = fixUrlNull(
            doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("img.poster-img")?.posterSrcLazy()
                ?: catalogItem?.poster,
        )
        val plot = doc.selectFirst(".hero .desc")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        val seasonBlocks = doc.select("section.cvd-season-block")
        if (seasonBlocks.isNotEmpty()) {
            for (block in seasonBlocks) {
                val heading = block.selectFirst(".cvd-sh-text, h3, h2, summary")?.text()?.trim().orEmpty()
                val seasonNo = Regex("""^(\d+)""").find(heading)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                for (entry in block.select("tbody.cvd-episode-entry")) {
                    parseEpisodeEntry(entry, showId, seasonNo)?.let { episodes.add(it) }
                }
            }
        } else {
            for (entry in doc.select("tbody.cvd-episode-entry")) {
                parseEpisodeEntry(entry, showId, 1)?.let { episodes.add(it) }
            }
        }
        if (episodes.isEmpty()) throw ErrorLoadingException("$name: bölüm bulunamadı. $REPORT_HINT")

        val tvType = if (catalogItem?.type == "anime") TvType.Anime else TvType.TvSeries
        val tags = catalogItem?.let {
            (it.genres + listOfNotNull(it.channel.takeIf { ch -> ch.isNotBlank() })).distinct()
        }
        val recos = fetchRecommendations("/onerilerim.php?diziid=${URLEncoder.encode(showId, "UTF-8")}")

        return newTvSeriesLoadResponse(title, absUrl, tvType, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.recommendations = recos
        }
    }

    private fun parseEpisodeEntry(entry: Element, showId: String, seasonNo: Int): com.lagradost.cloudstream3.Episode? {
        val overallKey = entry.selectFirst("td.cvd-overall-no")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val seasonEp = entry.selectFirst("td.cvd-season-no")?.text()?.trim()?.toIntOrNull()
        val trTitle = entry.selectFirst("a.cvd-episode-title-tr")?.text()?.trim().orEmpty()
        val enTitle = entry.selectFirst("a.cvd-episode-title-eng")?.text()?.trim().orEmpty()
        val epDesc = entry.selectFirst("tr.cvd-summary-row td")?.text()?.trim()?.takeIf { it.isNotBlank() }
        val epTitle = trTitle.ifBlank { enTitle }.ifBlank {
            seasonEp?.let { "Bölüm $it" } ?: overallKey
        }
        return newEpisode(CvdParser.encodeSeriesData(mainUrl, showId, overallKey)) {
            this.name = epTitle
            this.season = seasonNo
            this.episode = seasonEp
            this.description = epDesc
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val linkCallback: (ExtractorLink) -> Unit = { if (Settings.qualityAllowed(it.quality)) callback(it) }
        var emitted = 0

        val seriesData = CvdParser.decodeSeriesData(data)
        if (seriesData != null) {
            val (showId, episodeKey) = seriesData
            val sources = fetchSeriesSources(showId, episodeKey)
            for ((idx, src) in sources.withIndex()) {
                val proxyUrl = CvdVideoProxy.register(
                    src.bridgeKey,
                    CvdVideoProxy.SourceRef.Series(showId, episodeKey, idx),
                ) ?: continue
                linkCallback(
                    newExtractorLink(name, "$name - ${src.label}", proxyUrl, ExtractorLinkType.VIDEO) {
                        this.quality = Qualities.Unknown.value
                    },
                )
                emitted++
            }
        } else {
            val filmUrl = fixUrl(data.substringBefore("#cvd-film"))
            val keys = fetchFilmBridgeKeys(filmUrl)
            for ((idx, key) in keys.withIndex()) {
                val proxyUrl = CvdVideoProxy.register(
                    key,
                    CvdVideoProxy.SourceRef.Film(filmUrl, idx),
                ) ?: continue
                val label = if (keys.size > 1) "$name - Kaynak ${idx + 1}" else name
                linkCallback(
                    newExtractorLink(name, label, proxyUrl, ExtractorLinkType.VIDEO) {
                        this.quality = Qualities.Unknown.value
                    },
                )
                emitted++
            }
        }

        if (emitted == 0) throw ErrorLoadingException("$name: oynatılabilir video kaynağı bulunamadı. $REPORT_HINT")
        return true
    }

    companion object {
        internal const val BASE_URL = "https://cizgivedizi.net"
        private const val PAGE_SIZE = 40
        private const val CATALOG_TTL_MS = 15 * 60 * 1000L
        private const val POOL_MARKER = "<script type=\"application/json\" id=\"poolData\">"
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"

        val ROWS = listOf("Çizgi Diziler", "Animeler", "Diziler", "Filmler")

        private val catalogLock = Any()
        @Volatile private var cachedCatalog: List<CatalogEntry>? = null
        @Volatile private var cachedById: Map<String, CatalogEntry> = emptyMap()
        @Volatile private var cachedAtMs: Long = 0L

        internal fun fetchText(url: String, referer: String? = "$BASE_URL/"): String {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", CvdVideoProxy.USER_AGENT)
                .header("Accept-Language", browserHeaders.getValue("accept-language"))
                .apply { if (referer != null) header("Referer", referer) }
                .build()
            CvdVideoProxy.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                return resp.body.string()
            }
        }

        internal fun fetchSeriesSources(showId: String, episodeKey: String): List<VideoSourceItem> {
            val url = "$BASE_URL/diziyeni/video_sources.php?s=${URLEncoder.encode(showId, "UTF-8")}" +
                "&b=${URLEncoder.encode(episodeKey, "UTF-8")}"
            val json = JSONObject(fetchText(url))
            val arr = json.optJSONArray("sources") ?: return emptyList()
            val out = mutableListOf<VideoSourceItem>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val key = CvdParser.extractBridgeKey(item.optString("embed")) ?: continue
                val baseLabel = item.optString("ad").ifBlank { item.optString("kanal") }.ifBlank { "Merkez" }
                val take = item.optInt("take", 1)
                val part = item.optInt("part", 0)
                val label = when {
                    take > 1 && part > 0 -> "$baseLabel (${take}. Çekim, Kısım $part)"
                    take > 1 -> "$baseLabel (${take}. Çekim)"
                    part > 0 -> "$baseLabel (Kısım $part)"
                    else -> baseLabel
                }
                out.add(VideoSourceItem(label, key))
            }
            return out
        }

        internal fun fetchFilmBridgeKeys(filmUrl: String): List<String> {
            val html = fetchText(filmUrl)
            val b64 = Regex("""data-embeds=["']([^"']+)["']""").find(html)?.groupValues?.get(1) ?: return emptyList()
            val decoded = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
            val arr = JSONArray(decoded)
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                CvdParser.extractBridgeKey(arr.optString(i))?.let { out.add(it) }
            }
            return out
        }
    }
}

internal data class CatalogEntry(
    val id: String,
    val type: String,
    val title: String,
    val englishTitle: String,
    val hay: String,
    val href: String,
    val poster: String,
    val genres: List<String>,
    val channel: String,
)
