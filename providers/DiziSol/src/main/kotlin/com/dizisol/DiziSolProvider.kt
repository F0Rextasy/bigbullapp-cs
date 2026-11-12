package com.dizisol

import com.bigbull.common.browserHeaders
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
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
import org.json.JSONArray
import org.json.JSONObject

/**
 * DiziSol — dizisol.com (React SPA + kendi JSON API'si; içerik TMDB kimlikleriyle saklanır).
 *
 * Uçlar:
 *  - Arama    GET /api/movies/search?q=..&paged=1&page=N&limit=30 → {items:[{tmdbId,type,..}]}
 *  - Satırlar GET /api/library/browse?type=tv|movie&page=N      → {results:[{id,media_type,..}]}
 *             GET /api/tmdb/{tv|movie}/popular?language=tr-TR
 *  - Detay    GET /api/movies/by-tmdb/{id}?type=movie
 *             GET /api/movies/by-tmdb/{id}/episodes  (season=0/episode=0 kaydı dizi başlığıdır)
 *  - Bölüm    GET /api/movies/by-tmdb/{id}?season=S&episode=E&type=tv → m3u8Url + sources[]
 *
 * m3u8 origin'leri imzasız isteği 403'ler ("çelik kalkan"), bu yüzden player'ın bilet akışı taklit edilir:
 *   POST /api/playback/load-mixi/config → origin→provider tablosu + ipBindingOrigins
 *   GET  {origin}/viewer-proof          → networkProof (IP bağlı origin'lerde zorunlu)
 *   POST /api/playback/{provider}       → {playUrl: imzalı master m3u8, subtitleTr, subtitleEn}
 */
class DiziSolProvider : MainAPI() {
    override var mainUrl = "https://dizisol.com"
    override var name = "DiziSol"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val apiHeaders: Map<String, String> get() = browserHeaders + mapOf(
        "referer" to "$mainUrl/",
        "origin" to mainUrl,
        "accept" to "application/json, text/plain, */*",
    )

    @Volatile
    private var cachedConfig: JSONObject? = null
    private var lastTicketError: String = ""

    override val mainPage get() = mainPageOf(
        "browse:tv" to "Son Eklenen Diziler",
        "browse:movie" to "Son Eklenen Filmler",
        "popular:tv" to "Popüler Diziler",
        "popular:movie" to "Popüler Filmler",
    ).filter { Settings.rowEnabled(it.name) }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val parts = request.data.split(":", limit = 2)
        val kind = parts[0]
        val mediaType = parts.getOrElse(1) { "tv" }
        val url = if (kind == "browse") "$mainUrl/api/library/browse" else "$mainUrl/api/tmdb/$mediaType/popular"
        val params = if (kind == "browse") {
            mapOf("type" to mediaType, "page" to page.toString())
        } else {
            mapOf("language" to "tr-TR", "page" to page.toString())
        }
        val body = runCatching { app.get(url, params = params, headers = apiHeaders).text }
            .getOrElse { throw ErrorLoadingException("$name: satır yüklenemedi (${it.message})") }
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: throw ErrorLoadingException("$name: satır verisi okunamadı")
        val results = root.optJSONArray("results") ?: JSONArray()
        val items = (0 until results.length()).mapNotNull { results.optJSONObject(it)?.toSearchResponse() }
        if (items.isEmpty() && page == 1) throw ErrorLoadingException("$name: satır boş döndü")
        return newHomePageResponse(request.name, items, hasNext = page < root.optInt("total_pages", page))
    }

    override suspend fun search(query: String): List<SearchResponse> =
        search(query, 1)?.items ?: emptyList()

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (query.isBlank() || page < 1) return newSearchResponseList(emptyList(), false)
        val body = runCatching {
            app.get(
                "$mainUrl/api/movies/search",
                params = mapOf("q" to query.trim(), "paged" to "1", "page" to page.toString(), "limit" to "30"),
                headers = apiHeaders,
            ).text
        }.getOrElse { throw ErrorLoadingException("$name: arama başarısız (${it.message})") }
        if (body.isBlank()) return newSearchResponseList(emptyList(), false)
        val trimmed = body.trimStart()
        val root: JSONObject? = if (trimmed.startsWith("[")) null else runCatching { JSONObject(trimmed) }.getOrNull()
        val rows = if (root == null) runCatching { JSONArray(trimmed) }.getOrNull() else root.optJSONArray("items")
        if (rows == null) return newSearchResponseList(emptyList(), false)
        val items = (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.toSearchResponse() }
        return newSearchResponseList(items, root?.optBoolean("hasMore") == true)
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = TMDB_ID.find(url)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw ErrorLoadingException("$name: geçersiz içerik adresi")
        val mediaType = MEDIA_TYPE.find(url)?.groupValues?.get(1) ?: "movie"
        return if (mediaType == "tv") loadSeries(id, url) else loadMovie(id, url)
    }

    private suspend fun loadMovie(id: Int, url: String): LoadResponse {
        val item = JSONObject(
            runCatching { app.get("$mainUrl/api/movies/by-tmdb/$id?type=movie", headers = apiHeaders).text }
                .getOrElse { throw ErrorLoadingException("$name: film bulunamadı") }
        )
        val title = item.titleOrNull() ?: throw ErrorLoadingException("$name: film adı okunamadı")
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = item.posterUrl()
            this.year = item.yearOrNull()
            this.plot = item.optString("overview").ifBlank { null }
            this.tags = item.tagList()
            this.score = item.scoreOrNull()
            this.duration = item.optInt("duration", 0).takeIf { it > 0 }
        }
    }

    private suspend fun loadSeries(id: Int, url: String): LoadResponse {
        val raw = runCatching { app.get("$mainUrl/api/movies/by-tmdb/$id/episodes", headers = apiHeaders).text }
            .getOrElse { throw ErrorLoadingException("$name: dizi bulunamadı") }
        val array = runCatching { JSONArray(raw) }.getOrNull()
            ?: throw ErrorLoadingException("$name: bölüm listesi okunamadı")
        val metas = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        if (metas.isEmpty()) throw ErrorLoadingException("$name: dizi bulunamadı")
        val head = metas.firstOrNull { it.optInt("season") == 0 && it.optInt("episode") == 0 } ?: metas.first()
        val title = head.titleOrNull() ?: throw ErrorLoadingException("$name: dizi adı okunamadı")
        val episodes = metas
            .filter { it.optInt("episode") > 0 }
            .sortedWith(compareBy({ it.optInt("season") }, { it.optInt("episode") }))
            .map { ep ->
                val season = ep.optInt("season")
                val number = ep.optInt("episode")
                newEpisode("$mainUrl/api/movies/by-tmdb/$id?season=$season&episode=$number&type=tv") {
                    this.name = "S${season}B$number"
                    this.season = season
                    this.episode = number
                    this.posterUrl = ep.posterUrl() ?: head.posterUrl()
                    this.description = ep.optString("overview").ifBlank { null }
                    this.score = ep.scoreOrNull()
                }
            }
        if (episodes.isEmpty()) throw ErrorLoadingException("$name: bölüm bulunamadı")
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = head.posterUrl()
            this.year = head.yearOrNull()
            this.plot = head.optString("overview").ifBlank { null }
            this.tags = head.tagList()
            this.score = head.scoreOrNull()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val id = TMDB_ID.find(data)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw ErrorLoadingException("$name: geçersiz bölüm adresi")
        val mediaType = MEDIA_TYPE.find(data)?.groupValues?.get(1) ?: "movie"
        val query = if (mediaType == "tv") {
            val season = SEASON_PARAM.find(data)?.groupValues?.get(1) ?: "1"
            val episode = EPISODE_PARAM.find(data)?.groupValues?.get(1) ?: "1"
            "?season=$season&episode=$episode&type=tv"
        } else {
            "?type=movie"
        }
        val detail = JSONObject(
            runCatching { app.get("$mainUrl/api/movies/by-tmdb/$id$query", headers = apiHeaders).text }
                .getOrElse { throw ErrorLoadingException("$name: kaynak listesi alınamadı") }
        )
        val movieId = detail.optInt("id", 0)

        val candidates = mutableListOf<Pair<String, Int?>>()
        detail.optString("m3u8Url").takeIf { it.startsWith("http") }?.let { candidates.add(Pair(it, null)) }
        val sourceArray = detail.optJSONArray("sources")
        if (sourceArray != null) {
            for (i in 0 until sourceArray.length()) {
                val src = sourceArray.optJSONObject(i) ?: continue
                if (!src.optBoolean("isActive", true)) continue
                val sourceUrl = src.optString("m3u8Url")
                if (!sourceUrl.startsWith("http")) continue
                candidates.add(Pair(sourceUrl, src.optInt("id", 0).takeIf { it > 0 }))
            }
        }
        val unique = candidates.distinctBy { it.first }
        if (unique.isEmpty()) throw ErrorLoadingException("$name: bu içerik için kaynak yok")

        val config = playbackConfig()
        var emitted = 0
        for ((playUrl, sourceId) in unique) {
            val origin = originOf(playUrl) ?: continue
            // Bilet yalnız imzalı worker origin'leri için verilir; CDN m3u8'i bilet kabul etmez.
            if (!origin.contains("workers.dev")) continue
            // Worker origin'leri kendi sağlayıcı adını taşır (closeload.*, load-mixi.*, rapid.*);
            // config eşlemesi rotasyonlu alan adlarında boş kalabildiği için önce host'tan türet.
            val provider = providerFromHost(origin) ?: config?.providerFor(origin) ?: DEFAULT_PROVIDER
            val ticket = issueTicket(config, provider, playUrl, movieId, sourceId) ?: continue
            val signed = ticket.optString("playUrl")
            if (!signed.startsWith("http")) continue
            val master = runCatching { app.get(signed, headers = browserHeaders).text }.getOrNull().orEmpty()
            if (!master.contains("#EXTM3U")) continue

            val height = RESOLUTION.findAll(master)
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .maxOrNull() ?: Qualities.Unknown.value
            callback(
                newExtractorLink(name, "$name • ${providerLabel(provider)}", signed, ExtractorLinkType.M3U8) {
                    this.quality = height
                    this.referer = "$mainUrl/"
                    this.headers = browserHeaders
                }
            )
            emitted++
            // Master gömülü altyazı taşıyorsa aynı dili harici dosyayla tekrar ekleme.
            if (!master.contains("TYPE=SUBTITLES", ignoreCase = true)) {
                ticket.optString("subtitleTr")
                    .takeIf { it.startsWith("http") && Settings.subtitleAllowed("tr") }
                    ?.let { subtitleCallback(SubtitleFile("Türkçe", it)) }
                ticket.optString("subtitleEn")
                    .takeIf { it.startsWith("http") && Settings.subtitleAllowed("en") }
                    ?.let { subtitleCallback(SubtitleFile("English", it)) }
            }
        }
        if (emitted == 0) throw ErrorLoadingException("$name: imzalı akış alınamadı [$lastTicketError]")
        return true
    }

    /** Orijinal player'ın bilet akışı; imzasız m3u8 origin'leri 403 döndürür. */
    private suspend fun issueTicket(
        config: JSONObject?,
        provider: String,
        playUrl: String,
        movieId: Int,
        sourceId: Int?,
    ): JSONObject? {
        val origin = originOf(playUrl) ?: return null
        val body = JSONObject().put("movieId", movieId).put("playUrl", playUrl)
        if (sourceId != null) body.put("sourceId", sourceId)
        // Config alınamasa da proof ucu zararsız; IP-bağlı worker'lar proof olmadan bileti reddeder.
        if (config == null || config.isIpBound(origin)) {
            val proof = runCatching {
                JSONObject(app.get("$origin/viewer-proof", headers = apiHeaders).text).optString("proof")
            }.getOrNull()
            if (!proof.isNullOrBlank()) body.put("networkProof", proof)
        }
        val response = runCatching {
            app.post(
                "$mainUrl/api/playback/$provider",
                json = body,
                headers = apiHeaders + mapOf(
                    "content-type" to "application/json",
                    "x-playback-intent" to "$provider-v1",
                ),
            ).text
        }.onFailure { lastTicketError = "post:$provider:${it.message}" }.getOrNull()
            ?: return null
        lastTicketError = "body:$provider:${response.take(160)}"
        return runCatching { JSONObject(response) }.getOrNull()
    }

    private suspend fun playbackConfig(): JSONObject? {
        cachedConfig?.let { return it }
        val json = runCatching {
            JSONObject(app.post("$mainUrl/api/playback/load-mixi/config", data = emptyMap(), headers = apiHeaders).text)
        }.getOrNull() ?: return null
        cachedConfig = json
        return json
    }

    private fun originOf(url: String): String? = runCatching {
        val uri = java.net.URI(url)
        val host = uri.authority ?: return null
        "${uri.scheme ?: "https"}://$host"
    }.getOrNull()

    private fun providerLabel(provider: String): String = when (provider) {
        "load-mixi" -> "Sunucu 1"
        "cortina" -> "Sunucu 2"
        "dizipal3008" -> "Dizipal"
        "dizilla" -> "Dizilla"
        "videoplays" -> "VideoPlays"
        "closeload" -> "CloseLoad"
        "rapidrame" -> "RapidRame"
        "canlidizi14" -> "CanliDizi"
        "filmekseni" -> "FilmEkseni"
        else -> provider
    }

    /** `closeload.dizisol74.workers.dev` → `closeload`; bilinmeyen host → null. */
    private fun providerFromHost(origin: String): String? {
        val host = origin.substringAfter("://").substringBefore('/').lowercase()
        return when {
            host.startsWith("load-mixi") -> "load-mixi"
            host.startsWith("closeload") -> "closeload"
            host.startsWith("cortina") -> "cortina"
            host.startsWith("rapid") -> "rapidrame"
            host.startsWith("dizipal") -> "dizipal3008"
            host.startsWith("dizilla") -> "dizilla"
            host.startsWith("ydx") -> "ydx"
            host.startsWith("videoplays") -> "videoplays"
            host.startsWith("filmekseni") -> "filmekseni"
            else -> null
        }
    }

    private fun JSONObject.providerFor(origin: String): String? {
        val providers = optJSONObject("providers")
        if (providers != null) {
            val keys = providers.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (providers.optJSONArray(key).containsUrl(origin)) return key
            }
        }
        if (optJSONArray("origins").containsUrl(origin)) return "load-mixi"
        if (optJSONArray("cortinaOrigins").containsUrl(origin)) return "cortina"
        return null
    }

    private fun JSONObject.isIpBound(origin: String): Boolean = optJSONArray("ipBindingOrigins").containsUrl(origin)

    private fun JSONArray?.containsUrl(origin: String): Boolean {
        if (this == null || origin.isBlank()) return false
        for (i in 0 until length()) {
            if (optString(i).trimEnd('/').equals(origin.trimEnd('/'), ignoreCase = true)) return true
        }
        return false
    }

    private fun JSONObject.toSearchResponse(): SearchResponse? {
        val id = optInt("id", 0).takeIf { it > 0 } ?: optInt("tmdbId", 0).takeIf { it > 0 } ?: return null
        val title = titleOrNull() ?: return null
        val mediaType = optString("media_type").ifBlank { optString("type") }
            .ifBlank { if (optString("first_air_date").isNotBlank()) "tv" else "movie" }
        val entryUrl = "$mainUrl/api/movies/by-tmdb/$id?type=$mediaType"
        val poster = posterUrl()
        val year = yearOrNull()
        val score = scoreOrNull()
        return if (mediaType == "tv") {
            newTvSeriesSearchResponse(title, entryUrl, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                this.score = score
            }
        } else {
            newMovieSearchResponse(title, entryUrl, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                this.score = score
            }
        }
    }

    /** Arama `title/titleTr`, liste uçları `title/name` kullanır; Türkçe başlık önce gelir. */
    private fun JSONObject.titleOrNull(): String? = sequenceOf("titleTr", "title", "name")
        .map { optString(it).trim() }
        .firstOrNull { it.isNotBlank() }

    /** Arama `poster` (tam adres), liste uçları `poster_path` döndürür. */
    private fun JSONObject.posterUrl(): String? {
        optString("poster").takeIf { it.startsWith("http") }?.let { return it }
        val path = optString("poster_path").ifBlank { optString("backdrop_path") }
        return path.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" }
    }

    private fun JSONObject.yearOrNull(): Int? = optInt("year", 0).takeIf { it > 0 }
        ?: sequenceOf("release_date", "first_air_date")
            .map { optString(it) }
            .firstOrNull { it.length >= 4 && it[0].isDigit() }
            ?.substring(0, 4)?.toIntOrNull()

    private fun JSONObject.scoreOrNull(): Score? = optDouble("vote_average", 0.0)
        .takeIf { it > 0 }?.let { Score.from10(it) }

    private fun JSONObject.tagList(): List<String> = optString("genres")
        .split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }

    companion object {
        /** Ana sayfa satırları; ayarlardan kapatılabilir. */
        val ROWS = listOf(
            "Son Eklenen Diziler",
            "Son Eklenen Filmler",
            "Popüler Diziler",
            "Popüler Filmler",
        )

        private const val DEFAULT_PROVIDER = "load-mixi"
        private val TMDB_ID = Regex("""by-tmdb/(\d+)""")
        private val MEDIA_TYPE = Regex("""[?&]type=(movie|tv)""")
        private val SEASON_PARAM = Regex("""[?&]season=(\d+)""")
        private val EPISODE_PARAM = Regex("""[?&]episode=(\d+)""")
        private val RESOLUTION = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)
    }
}
