package com.anizium

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TrailerData
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random

/**
 * Anizium — anizium.co.
 *
 * The site is a SPA; everything comes from api.anizium.co (JSON) and the embed player
 * (x.anizium.co) is only a wrapper around `GET /anime/source`. Every API call needs a
 * `Cf-Control` header: hex(xor(JSON {nonce: timestamp}, TOKEN_KEY + "_" + istanbul-weekday)).
 */
class AniziumProvider : MainAPI() {
    override var mainUrl = "https://anizium.co"
    override var name = "Anizium"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)

    private val apiUrl = "https://api.anizium.co"

    override val mainPage get() = ROWS.mapNotNull { name ->
        if (!Settings.rowEnabled(name)) return@mapNotNull null
        when (name) {
            "Son Eklenen Bölümler" -> MainPageData(name, "last")
            "Popüler" -> MainPageData(name, "top")
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when (request.data) {
            "last" -> "$apiUrl/page/last-added-episodes?page=$page"
            else -> "$apiUrl/page/top?platform=all&page=$page"
        }
        val pageData = apiObject(url).optJSONObject("page")
            ?: return newHomePageResponse(request.name, emptyList())
        val items = pageData.optJSONArray("data").objects().mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items, pageData.optInt("next_page", 0) > 0)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        search(query, 1)?.items ?: emptyList()

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val body = apiObject("$apiUrl/page/search?value=$encoded&page=$page")
        val resultPage = body.optJSONObject("page") ?: return newSearchResponseList(emptyList(), false)
        val items = resultPage.optJSONArray("data").objects().mapNotNull { it.toSearchResult() }
        return newSearchResponseList(items, resultPage.optInt("next_page", 0) > 0)
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = Regex("""/anime/([^/?#]+)""").find(url)?.groupValues?.get(1) ?: return null
        val data = runCatching { apiObject("$apiUrl/anime/get?id=$id").optJSONObject("data") }
            .getOrNull() ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        if (data.optString("name").isBlank()) throw ErrorLoadingException("$name: içerik verisi boş. $REPORT_HINT")
        val recommendations = apiObject("$apiUrl/anime/similar?id=$id")
            .optJSONArray("data").objects().mapNotNull { it.toSearchResult() }
        val trailers = mutableListOf<TrailerData>()
        data.optString("youtube_video").takeIf { it.isNotBlank() }
            ?.let { trailers += TrailerData(it, null, false) }

        val title = data.optString("name")
        val poster = data.optString("poster").takeIf { it.isNotBlank() }
        val plot = data.optString("overview").takeIf { it.isNotBlank() }
        val year = data.optString("release_year").toIntOrNull()
        val tags = data.optJSONArray("genre").objects().mapNotNull { it.optString("name").takeIf { it.isNotBlank() } }
        val score = Score.from(data.optString("imdb_point"), 10)
        val status = when (data.optString("status")) {
            "" -> null
            "Final" -> ShowStatus.Completed
            else -> ShowStatus.Ongoing
        }
        val duration = data.optInt("run_time").takeIf { it > 0 }

        if (data.optString("type") == "movie") {
            return newMovieLoadResponse(title, url, TvType.Movie, "$mainUrl/watch/$id") {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = score
                this.duration = duration
                this.trailers = trailers
                this.recommendations = recommendations
            }
        }

        val episodes = data.optJSONArray("seasons").objects().flatMap { season ->
            val seasonNumber = season.optInt("number", 1)
            season.optJSONArray("episodes").objects().mapNotNull { episode ->
                val number = episode.optInt("number", 0)
                if (number <= 0) return@mapNotNull null
                newEpisode("$mainUrl/watch/$id?season=$seasonNumber&episode=$number") {
                    this.name = episode.optString("name").takeIf { it.isNotBlank() }
                    this.season = seasonNumber
                    this.episode = number
                    this.description = episode.optString("overview").takeIf { it.isNotBlank() }
                    this.runTime = episode.optInt("run_time").takeIf { it > 0 }
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.score = score
            this.showStatus = status
            this.duration = duration
            this.trailers = trailers
            this.recommendations = recommendations
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
        val subtitleSeen = mutableSetOf<String>()
        val linkSeen = mutableSetOf<String>()
        val id = Regex("""/watch/([^/?#]+)""").find(data)?.groupValues?.get(1) ?: return false
        val season = Regex("""[?&]season=(\d+)""").find(data)?.groupValues?.get(1)
        val episode = Regex("""[?&]episode=(\d+)""").find(data)?.groupValues?.get(1)

        val sourceUrl = buildString {
            append("$apiUrl/anime/source?id=$id&site=main")
            if (season != null && episode != null) append("&season=$season&episode=$episode")
        }
        val body = runCatching { apiObject(sourceUrl) }
            .getOrNull() ?: throw ErrorLoadingException("$name: kaynak isteği başarısız. $REPORT_HINT")
        if (!body.optBoolean("success")) throw ErrorLoadingException("$name: kaynak bulunamadı. $REPORT_HINT")

        body.optJSONArray("subtitles").objects().forEach { subtitle ->
            val link = subtitle.optString("link")
            if (link.isNotBlank() && subtitleSeen.add(link)) {
                subtitleFilter(SubtitleFile(subtitle.optString("name"), link))
            }
        }

        var found = false
        body.optJSONArray("groups").objects().forEach { group ->
            val audio = group.optString("name")
            val label = if (audio.isBlank()) name else "$name ($audio)"
            group.optJSONArray("items").objects()
                .filter { it.optString("link").isNotBlank() && linkSeen.add(it.optString("link")) }
                .forEach { item ->
                    val link = item.optString("link")
                    linkCallback(
                        newExtractorLink(
                            source = name,
                            name = label,
                            url = link,
                            type = if (link.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                        ) {
                            this.quality = item.optInt("quality", Qualities.Unknown.value)
                            this.referer = "https://x.anizium.co/"
                        }
                    )
                    found = true
                }
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir akış yok. $REPORT_HINT")
        return found
    }

    private suspend fun apiObject(url: String): JSONObject =
        JSONObject(app.get(url, headers = apiHeaders()).text)

    private fun apiHeaders(): Map<String, String> = mapOf(
        "Cf-Control" to cfControl(),
        "language" to "tr",
        "site" to "main",
    )

    private fun JSONObject.toSearchResult(): SearchResponse? {
        val id = optString("ID")
        if (id.isBlank()) return null
        val title = optString("name")
        val poster = optString("poster").takeIf { it.isNotBlank() }
        val type = if (optString("type") == "movie") TvType.Movie else TvType.Anime
        return newTvSeriesSearchResponse(title, "$mainUrl/anime/$id", type) {
            this.posterUrl = poster
        }
    }

    private fun JSONArray?.objects(): List<JSONObject> {
        val array = this ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    private fun cfControl(): String {
        val weekday = SimpleDateFormat("EEEE", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("Europe/Istanbul") }
            .format(Date())
            .lowercase(Locale.US)
        val nonce = (0 until 6).map { ALPHANUM[Random.nextInt(ALPHANUM.length)] }.joinToString("")
        return xorHex(JSONObject().put(nonce, System.currentTimeMillis()).toString(), "${TOKEN_KEY}_$weekday")
    }

    private fun xorHex(text: String, key: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size * 2)
        bytes.forEachIndexed { index, byte ->
            val value = (byte.toInt() and 0xFF) xor (keyBytes[index % keyBytes.size].toInt() and 0xFF)
            out.append(Character.forDigit(value shr 4, 16)).append(Character.forDigit(value and 0xF, 16))
        }
        return out.toString()
    }

    companion object {
        /** Homepage row labels, in display order; the API only exposes these two feeds. */
        val ROWS = listOf("Son Eklenen Bölümler", "Popüler")
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
        // Public client keys shipped in klausBundle.js / the embed page.
        private const val TOKEN_KEY = "hlxjl1c2w281ax473rt1ofgrvhyjvi"
        private const val ALPHANUM = "abcdefghijklmnopqrstuvwxyz0123456789"
    }
}
