package com.cizgivedizi

import android.util.Base64
import com.bigbull.common.browserHeaders
import com.bigbull.common.posterSrcLazy
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
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
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.Reader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
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

internal object CvdParser {
    private val jsonFactory = JsonFactory()

    fun extractBridgeKey(embedUrl: String?): String? {
        if (embedUrl.isNullOrBlank()) return null
        val raw = Regex("""[?&]k=([^&#]+)""").find(embedUrl)?.groupValues?.get(1) ?: return null
        return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw).takeIf { it.isNotBlank() }
    }

    fun extractPathId(url: String, prefix: String): String? {
        val idx = url.indexOf(prefix)
        if (idx < 0) return null
        val raw = url.substring(idx + prefix.length).substringBefore('/').substringBefore('?')
        return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw).takeIf { it.isNotBlank() }
    }

    fun encodeSeriesData(baseUrl: String, showId: String, overallKey: String): String =
        "$baseUrl/#cvd-dizi/${URLEncoder.encode(showId, "UTF-8")}/${URLEncoder.encode(overallKey, "UTF-8")}"

    fun decodeSeriesData(data: String): Pair<String, String>? {
        val tail = data.substringAfter("#cvd-dizi/", "")
        if (tail.isEmpty()) return null
        val parts = tail.split('/', limit = 2)
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return null
        val showId = runCatching { URLDecoder.decode(parts[0], "UTF-8") }.getOrNull() ?: return null
        val key = runCatching { URLDecoder.decode(parts[1], "UTF-8") }.getOrNull() ?: return null
        return showId to key
    }

    fun advancePastMarker(reader: Reader, marker: String): Boolean {
        var matched = 0
        while (true) {
            val ch = reader.read()
            if (ch < 0) return false
            val c = ch.toChar()
            if (c == marker[matched]) {
                matched++
                if (matched == marker.length) return true
            } else if (matched > 0) {
                matched = if (c == marker[0]) 1 else 0
            }
        }
    }

    fun parsePoolData(reader: Reader): List<CatalogEntry> {
        val out = ArrayList<CatalogEntry>(2600)
        jsonFactory.createParser(reader).use { p ->
            if (p.nextToken() != JsonToken.START_ARRAY) return out
            while (p.nextToken() == JsonToken.START_OBJECT) {
                var id = ""
                var type = ""
                var name = ""
                var hay = ""
                var hayEn = ""
                var genresRaw = ""
                var kanalText = ""
                var href = ""
                var poster = ""
                var label = ""

                while (p.nextToken() != JsonToken.END_OBJECT) {
                    val field = p.currentName()
                    p.nextToken()
                    when (field) {
                        "dataset" -> {
                            if (p.currentToken == JsonToken.START_OBJECT) {
                                while (p.nextToken() != JsonToken.END_OBJECT) {
                                    val df = p.currentName()
                                    p.nextToken()
                                    when (df) {
                                        "id" -> id = p.valueAsString.orEmpty()
                                        "type" -> type = p.valueAsString.orEmpty()
                                        "name" -> name = p.valueAsString.orEmpty()
                                        "hay" -> hay = p.valueAsString.orEmpty()
                                        "hayEn" -> hayEn = p.valueAsString.orEmpty()
                                        "genresraw" -> genresRaw = p.valueAsString.orEmpty()
                                        "kanaltext" -> kanalText = p.valueAsString.orEmpty()
                                        else -> p.skipChildren()
                                    }
                                }
                            } else {
                                p.skipChildren()
                            }
                        }
                        "href" -> href = p.valueAsString.orEmpty()
                        "poster" -> poster = p.valueAsString.orEmpty()
                        "label" -> label = p.valueAsString.orEmpty()
                        else -> p.skipChildren()
                    }
                }

                if (id.isNotBlank() && href.isNotBlank() && type != "other") {
                    val displayTitle = label.ifBlank { name }.trim()
                    if (displayTitle.isNotBlank()) {
                        out.add(
                            CatalogEntry(
                                id = id,
                                type = type,
                                title = displayTitle,
                                englishTitle = extractEnglishTitle(id, hayEn),
                                hay = hay,
                                href = href,
                                poster = poster,
                                genres = genresRaw.split('|').map { it.trim() }.filter { it.isNotEmpty() },
                                channel = kanalText.trim(),
                            ),
                        )
                    }
                }
            }
        }
        return out
    }

    fun extractEnglishTitle(id: String, hayEn: String): String {
        val trimmed = hayEn.trim()
        if (trimmed.isEmpty() || id.isBlank()) return ""
        val lower = trimmed.lowercase(Locale.ROOT)
        val lowerId = id.lowercase(Locale.ROOT)
        val prefix = "$lowerId "
        if (!lower.startsWith(prefix)) return ""
        val rest = trimmed.substring(prefix.length)
        val restLower = lower.substring(prefix.length)
        val asciiId = toAsciiTr(lowerId)
        val idx1 = restLower.indexOf(" $lowerId ")
        val idx2 = if (asciiId != lowerId) restLower.indexOf(" $asciiId ") else -1
        val cut = listOf(idx1, idx2).filter { it > 0 }.minOrNull() ?: return ""
        return rest.substring(0, cut).trim()
    }

    fun toAsciiTr(value: String): String = value
        .replace('ı', 'i')
        .replace('ğ', 'g')
        .replace('ü', 'u')
        .replace('ş', 's')
        .replace('ö', 'o')
        .replace('ç', 'c')

    fun normalizeSearch(value: String): String =
        toAsciiTr(value.lowercase(Locale("tr")))
            .replace('İ', 'i')
            .replace('I', 'i')
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
}

/**
 * Local loopback HTTP relay for `video.cizgivedizi.net`.
 *
 * The upstream CDN requires `Sec-Fetch-Dest: video`, rejects non-Range requests with HTTP 500,
 * caps each 206 response at 2 MB, and expires `cvd_video_auth` cookies every 60 seconds.
 * This relay runs exclusively on `127.0.0.1`, translates standard `GET`/`HEAD` (with or without
 * `Range`) into 2 MB upstream slices, and refreshes `yetki.php` auth before expiry.
 */
object CvdVideoProxy {
    private const val VIDEO_ORIGIN = "https://video.cizgivedizi.net"
    private const val SITE_ORIGIN = "https://cizgivedizi.net"
    private const val CHUNK_BYTES = 2_097_152L
    private const val AUTH_REFRESH_MS = 20_000L
    private const val MAX_SESSIONS = 32
    private const val IO_BUFFER_SIZE = 32 * 1024

    // Auth cookies bind an SHA/MD5 hash of User-Agent; every yetki.php and video request must match.
    internal val USER_AGENT: String = browserHeaders.getValue("user-agent")

    internal sealed class SourceRef {
        data class Series(val showId: String, val episodeKey: String, val sourceIndex: Int) : SourceRef()
        data class Film(val filmUrl: String, val sourceIndex: Int) : SourceRef()
    }

    internal data class RangeSpec(val start: Long?, val end: Long?, val suffix: Long?)
    internal data class ContentRangeInfo(val start: Long, val end: Long, val total: Long)

    private class StreamSession(
        val sourceRef: SourceRef,
        @Volatile var bridgeKey: String,
    ) {
        val lock = Any()
        var opaquePath: String? = null
        var sidCookie: String? = null
        var authCookie: String? = null
        var authAtMs: Long = 0L
        var totalSize: Long = -1L
    }

    internal val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val serverLock = Any()
    private var serverSocket: ServerSocket? = null
    private var port: Int = -1
    private val sessionsLock = Any()
    private val sessions = object : LinkedHashMap<String, StreamSession>(MAX_SESSIONS + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StreamSession>?): Boolean =
            size > MAX_SESSIONS
    }

    private val workerPool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "cvd-proxy-worker").apply { isDaemon = true }
    }

    private fun ensureStarted(): Int {
        synchronized(serverLock) {
            val existing = serverSocket
            if (existing != null && !existing.isClosed) return port
            val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
            val socket = ServerSocket(0, 50, loopback)
            serverSocket = socket
            port = socket.localPort
            Thread({
                while (!socket.isClosed) {
                    val clientSocket = try {
                        socket.accept()
                    } catch (_: IOException) {
                        break
                    }
                    workerPool.execute { handleClient(clientSocket) }
                }
            }, "cvd-proxy-accept").apply {
                isDaemon = true
                start()
            }
            return port
        }
    }

    internal fun register(bridgeKey: String, sourceRef: SourceRef): String? {
        if (bridgeKey.isBlank()) return null
        val listenPort = runCatching { ensureStarted() }.getOrNull() ?: return null
        val session = StreamSession(sourceRef, bridgeKey)
        if (!ensureAuth(session, forceNewTicket = true)) return null
        val token = UUID.randomUUID().toString().replace("-", "")
        synchronized(sessionsLock) {
            sessions[token] = session
        }
        return "http://127.0.0.1:$listenPort/v/$token.mp4"
    }

    private fun refreshBridgeKey(sourceRef: SourceRef): String? = runCatching {
        when (sourceRef) {
            is SourceRef.Series -> CizgiVeDiziProvider.fetchSeriesSources(sourceRef.showId, sourceRef.episodeKey)
                .getOrNull(sourceRef.sourceIndex)?.bridgeKey
            is SourceRef.Film -> CizgiVeDiziProvider.fetchFilmBridgeKeys(sourceRef.filmUrl)
                .getOrNull(sourceRef.sourceIndex)
        }
    }.getOrNull()

    private fun ensureAuth(
        session: StreamSession,
        forceNewTicket: Boolean = false,
        forceRefreshKey: Boolean = false,
    ): Boolean {
        synchronized(session.lock) {
            if (forceRefreshKey) {
                val fresh = refreshBridgeKey(session.sourceRef) ?: return false
                session.bridgeKey = fresh
                session.opaquePath = null
                session.authAtMs = 0L
            }
            val now = System.currentTimeMillis()
            if (!forceNewTicket && session.opaquePath != null && (now - session.authAtMs) < AUTH_REFRESH_MS) {
                return true
            }

            var renewOnly = !forceNewTicket && session.opaquePath != null && session.sidCookie != null
            var retriedWithFreshKey = forceRefreshKey
            for (attempt in 0 until 3) {
                val key = session.bridgeKey
                val url = buildString {
                    append(VIDEO_ORIGIN)
                    append("/yetki.php?k=")
                    append(URLEncoder.encode(key, "UTF-8"))
                    if (renewOnly) append("&renew=1")
                }
                val cookieHeader = buildCookieHeader(session.sidCookie, session.authCookie)
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "$SITE_ORIGIN/")
                    .header("Origin", SITE_ORIGIN)
                    .header("Accept", "*/*")
                    .header("Sec-Fetch-Dest", "empty")
                    .header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Site", "same-site")
                    .apply { if (cookieHeader.isNotEmpty()) header("Cookie", cookieHeader) }
                    .build()

                val ok = runCatching {
                    client.newCall(req).execute().use { resp ->
                        val setCookies = resp.headers("Set-Cookie")
                        parseCookie(setCookies, "cvd_video_sid")?.let { session.sidCookie = it }
                        parseCookie(setCookies, "cvd_video_auth")?.let { session.authCookie = it }
                        if (!resp.isSuccessful) return@use resp.code

                        if (!renewOnly) {
                            val opaque = resp.header("X-CVD-Video-URL")?.trim().orEmpty()
                            if (!OPAQUE_PATH_RE.matches(opaque)) return@use -1
                            session.opaquePath = opaque
                        }
                        session.authAtMs = System.currentTimeMillis()
                        200
                    }
                }.getOrDefault(-1)

                if (ok == 200) return true
                if (renewOnly) {
                    renewOnly = false
                    continue
                }
                if (!retriedWithFreshKey && (ok == 403 || ok == 410)) {
                    retriedWithFreshKey = true
                    val fresh = refreshBridgeKey(session.sourceRef) ?: return false
                    session.bridgeKey = fresh
                    session.opaquePath = null
                    continue
                }
                return false
            }
            return false
        }
    }

    private fun openUpstreamChunk(session: StreamSession, start: Long, endInclusive: Long): Response? {
        for (attempt in 0 until 3) {
            val forceTicket = attempt == 1
            val forceKey = attempt == 2
            if (!ensureAuth(session, forceNewTicket = forceTicket, forceRefreshKey = forceKey)) {
                return null
            }
            val opaque: String
            val cookieHeader: String
            synchronized(session.lock) {
                opaque = session.opaquePath ?: return null
                cookieHeader = buildCookieHeader(session.sidCookie, session.authCookie)
            }
            val req = Request.Builder()
                .url(VIDEO_ORIGIN + opaque)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "$SITE_ORIGIN/")
                .header("Sec-Fetch-Dest", "video")
                .header("Sec-Fetch-Mode", "no-cors")
                .header("Sec-Fetch-Site", "same-site")
                .header("Range", "bytes=$start-$endInclusive")
                .apply { if (cookieHeader.isNotEmpty()) header("Cookie", cookieHeader) }
                .build()

            val resp = runCatching { client.newCall(req).execute() }.getOrNull() ?: continue
            if (resp.isSuccessful) {
                parseContentRange(resp.header("Content-Range"))?.let { cr ->
                    synchronized(session.lock) { session.totalSize = cr.total }
                }
                return resp
            }
            val code = resp.code
            resp.close()
            if (code == 403 || code == 410) {
                synchronized(session.lock) {
                    session.authAtMs = 0L
                    if (code == 410) session.opaquePath = null
                }
                continue
            }
            return null
        }
        return null
    }

    private fun resolveTotalSize(session: StreamSession): Long {
        synchronized(session.lock) {
            if (session.totalSize > 0L) return session.totalSize
        }
        openUpstreamChunk(session, 0L, 0L)?.use { resp ->
            val info = parseContentRange(resp.header("Content-Range"))
            if (info != null) {
                synchronized(session.lock) { session.totalSize = info.total }
                return info.total
            }
        }
        return -1L
    }

    private fun handleClient(socket: Socket) {
        socket.use { sock ->
            sock.soTimeout = 30_000
            val input = BufferedInputStream(sock.getInputStream())
            val output = sock.getOutputStream()

            val reqLine = readAsciiLine(input) ?: return
            val parts = reqLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore('?')

            var rangeHeader: String? = null
            while (true) {
                val line = readAsciiLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val headerName = line.substring(0, colon).trim()
                    if (headerName.equals("Range", ignoreCase = true)) {
                        rangeHeader = line.substring(colon + 1).trim()
                    }
                }
            }

            if (method != "GET" && method != "HEAD") {
                writeSimpleResponse(output, 405, "Method Not Allowed")
                return
            }

            val token = PATH_TOKEN_RE.matchEntire(path)?.groupValues?.get(1)
            val session = token?.let { synchronized(sessionsLock) { sessions[it] } }
            if (session == null) {
                writeSimpleResponse(output, 404, "Not Found")
                return
            }

            val rangeSpec = if (rangeHeader != null) parseRange(rangeHeader) else null
            if (rangeHeader != null && rangeSpec == null) {
                writeSimpleResponse(output, 416, "Range Not Satisfiable")
                return
            }

            var reqStart: Long
            var reqEnd: Long
            var firstResp: Response? = null

            if (rangeSpec?.suffix != null) {
                val total = resolveTotalSize(session)
                if (total <= 0L) {
                    writeSimpleResponse(output, 502, "Bad Gateway")
                    return
                }
                reqStart = max(0L, total - rangeSpec.suffix)
                reqEnd = total - 1L
            } else {
                reqStart = rangeSpec?.start ?: 0L
                reqEnd = rangeSpec?.end ?: -1L
            }

            val probeEnd = if (reqEnd >= reqStart) {
                min(reqStart + CHUNK_BYTES - 1L, reqEnd)
            } else {
                reqStart + CHUNK_BYTES - 1L
            }

            firstResp = openUpstreamChunk(session, reqStart, probeEnd)
            if (firstResp == null) {
                writeSimpleResponse(output, 502, "Bad Gateway")
                return
            }

            val totalSize = parseContentRange(firstResp.header("Content-Range"))?.total
                ?: synchronized(session.lock) { session.totalSize }
            if (totalSize <= 0L) {
                firstResp.close()
                writeSimpleResponse(output, 502, "Bad Gateway")
                return
            }
            if (reqStart >= totalSize) {
                firstResp.close()
                writeSimpleResponse(output, 416, "Range Not Satisfiable", "Content-Range: bytes */$totalSize\r\n")
                return
            }

            val finalEnd = if (reqEnd >= 0L) min(reqEnd, totalSize - 1L) else totalSize - 1L
            val contentLength = finalEnd - reqStart + 1L
            val isPartial = rangeSpec != null

            val head = buildString {
                append(if (isPartial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                append("Content-Type: video/mp4\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Length: $contentLength\r\n")
                if (isPartial) append("Content-Range: bytes $reqStart-$finalEnd/$totalSize\r\n")
                append("Connection: close\r\n\r\n")
            }

            try {
                output.write(head.toByteArray(Charsets.US_ASCII))
                output.flush()
                if (method == "HEAD") {
                    firstResp.close()
                    return
                }

                var pos = reqStart
                var currentResp: Response? = firstResp
                var retriesOnUpstreamGap = 0
                val buffer = ByteArray(IO_BUFFER_SIZE)

                while (pos <= finalEnd) {
                    val chunkEnd = min(pos + CHUNK_BYTES - 1L, finalEnd)
                    val resp = currentResp ?: openUpstreamChunk(session, pos, chunkEnd)
                    currentResp = null
                    if (resp == null) break

                    val expectedInChunk = (chunkEnd - pos + 1L)
                    var copied = 0L
                    var upstreamFailed = false
                    resp.use { r ->
                        val bodyStream = r.body?.byteStream()
                        if (bodyStream == null) {
                            upstreamFailed = true
                            return@use
                        }
                        while (copied < expectedInChunk) {
                            val toRead = min(buffer.size.toLong(), expectedInChunk - copied).toInt()
                            val n = try {
                                bodyStream.read(buffer, 0, toRead)
                            } catch (_: IOException) {
                                upstreamFailed = true
                                break
                            }
                            if (n <= 0) break
                            // Client disconnect stops streaming immediately without error.
                            output.write(buffer, 0, n)
                            copied += n
                        }
                        output.flush()
                    }
                    pos += copied
                    if (copied == 0L) {
                        if (upstreamFailed && retriesOnUpstreamGap < 2) {
                            retriesOnUpstreamGap++
                            continue
                        }
                        break
                    }
                    retriesOnUpstreamGap = 0
                }
            } catch (_: IOException) {
                firstResp.close()
            }
        }
    }

    private fun readAsciiLine(input: InputStream): String? {
        val out = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("US-ASCII")
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
        }
        return out.toString("US-ASCII")
    }

    private fun writeSimpleResponse(output: OutputStream, code: Int, message: String, extraHeaders: String = "") {
        val body = message.toByteArray(Charsets.US_ASCII)
        val header = "HTTP/1.1 $code $message\r\nContent-Length: ${body.size}\r\n${extraHeaders}Connection: close\r\n\r\n"
        runCatching {
            output.write(header.toByteArray(Charsets.US_ASCII))
            output.write(body)
            output.flush()
        }
    }

    internal fun buildCookieHeader(sid: String?, auth: String?): String =
        listOfNotNull(
            sid?.takeIf { it.isNotBlank() }?.let { "cvd_video_sid=$it" },
            auth?.takeIf { it.isNotBlank() }?.let { "cvd_video_auth=$it" },
        ).joinToString("; ")

    internal fun parseCookie(setCookieHeaders: List<String>, cookieName: String): String? {
        val prefix = "$cookieName="
        for (raw in setCookieHeaders) {
            val pair = raw.substringBefore(';').trim()
            if (pair.startsWith(prefix)) {
                return pair.substring(prefix.length).trim().takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    internal fun parseRange(header: String?): RangeSpec? {
        val raw = header?.trim() ?: return null
        if (!raw.startsWith("bytes=", ignoreCase = true)) return null
        val spec = raw.substring(6).substringBefore(',').trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val left = spec.substring(0, dash).trim()
        val right = spec.substring(dash + 1).trim()
        return when {
            left.isEmpty() && right.isNotEmpty() -> {
                val suffix = right.toLongOrNull()?.takeIf { it > 0L } ?: return null
                RangeSpec(null, null, suffix)
            }
            left.isNotEmpty() && right.isEmpty() -> {
                val start = left.toLongOrNull()?.takeIf { it >= 0L } ?: return null
                RangeSpec(start, null, null)
            }
            left.isNotEmpty() && right.isNotEmpty() -> {
                val start = left.toLongOrNull()?.takeIf { it >= 0L } ?: return null
                val end = right.toLongOrNull()?.takeIf { it >= start } ?: return null
                RangeSpec(start, end, null)
            }
            else -> null
        }
    }

    internal fun parseContentRange(header: String?): ContentRangeInfo? {
        val match = CONTENT_RANGE_RE.find(header ?: return null) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        if (total <= 0L || end < start) return null
        return ContentRangeInfo(start, end, total)
    }

    private val OPAQUE_PATH_RE = Regex("""^/[A-Za-z0-9_-]{43}$""")
    private val PATH_TOKEN_RE = Regex("""^/v/([A-Za-z0-9]+)\.mp4$""")
    private val CONTENT_RANGE_RE = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)
}
