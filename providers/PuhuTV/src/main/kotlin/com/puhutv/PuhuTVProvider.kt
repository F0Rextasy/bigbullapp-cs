package com.puhutv

import com.bigbull.common.browserHeaders
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import java.net.URLEncoder
import java.util.Calendar
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

/**
 * PuhuTV — puhutv.com yerli dizi, puhutv Orijinal, film ve belgesel arşivi.
 *
 * Kategori ve detay verisi `/api/slug/<slug>` ve `/api/seasons/<id>/episodes` uçlarından,
 * arama `appservice.puhutv.com/search/search` ucundan, oynatma ise `video_id` üzerinden
 * DYG (`PublisherId=29`) imzalı HLS manifestinden çözülür.
 */
class PuhuTVProvider : MainAPI() {
    override var mainUrl = "https://puhutv.com"
    override var name = "PuhuTV"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage get() = ROW_SLUGS.mapNotNull { (label, slug) ->
        if (Settings.rowEnabled(label)) MainPageData(label, slug) else null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = fetchJson("$mainUrl/api/slug/${request.data}?s_page=$page&s_per=24")
            ?.optJSONObject("data")
            ?: if (page == 1) throw ErrorLoadingException("$name: ana sayfa yüklenemedi. $REPORT_HINT")
            else return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val items = data.optJSONArray("titles").toObjects().mapNotNull { toSearchResult(it) }
        val hasNext = data.optBoolean("has_more", false) && items.isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        if (encoded.isBlank()) return emptyList()
        val json = fetchJson("$SEARCH_API?query=$encoded&v=2&source=web")
            ?: throw ErrorLoadingException("$name: arama yanıt vermedi. $REPORT_HINT")
        val items = json.optJSONArray("data").toObjects().flatMap { entry ->
            val nested = entry.optJSONArray("data")
            if (nested != null) nested.toObjects() else listOf(entry)
        }
        return items.mapNotNull { toSearchResult(it) }.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? =
        runCatching { search(query) }.getOrNull()

    override suspend fun load(url: String): LoadResponse? {
        val rawSlug = slugOf(url) ?: throw ErrorLoadingException("$name: geçersiz içerik adresi. $REPORT_HINT")
        var data = fetchJson("$mainUrl/api/slug/$rawSlug")?.optJSONObject("data")
            ?: throw ErrorLoadingException("$name: içerik detayı alınamadı. $REPORT_HINT")
        // Doğrudan bölüm (-izle) adresi gelirse ana yapım (-detay) kaydına geç.
        val parentSlug = data.optJSONObject("title")?.optString("slug_path")?.takeIf(String::isNotBlank)
        if (parentSlug != null && data.optJSONArray("seasons") == null && !data.optBoolean("is_movie")) {
            data = fetchJson("$mainUrl/api/slug/$parentSlug")?.optJSONObject("data") ?: data
        }
        val slug = data.optString("slug_path").takeIf(String::isNotBlank) ?: rawSlug
        val title = data.optString("name").trim().takeIf(String::isNotBlank)
            ?: throw ErrorLoadingException("$name: başlık okunamadı. $REPORT_HINT")
        val plot = data.optString("description").trim()
            .ifBlank { data.optString("short_description").trim() }
            .takeIf(String::isNotBlank)
        val poster = pickPoster(data)
        val year = parseYear(data.optLong("released_at"))
        val tags = data.optJSONArray("genres").toObjects()
            .mapNotNull { it.optString("name").trim().takeIf(String::isNotBlank) }
        val actors = data.optJSONArray("cast").toObjects()
            .mapNotNull { "${it.optString("name")} ${it.optString("last_name")}".trim().takeIf(String::isNotBlank) }

        if (data.optBoolean("is_movie")) {
            val firstAsset = data.optJSONArray("assets")?.optJSONObject(0)
            val watchSlug = data.optString("to_watch_asset_slug_path").takeIf(String::isNotBlank)
                ?: firstAsset?.optString("slug_path")?.takeIf(String::isNotBlank)
                ?: slug
            val durationMs = firstAsset?.optJSONObject("content")?.optLong("duration_in_ms") ?: 0L
            return newMovieLoadResponse(title, "$mainUrl/$slug", TvType.Movie, "$mainUrl/$watchSlug") {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.duration = (durationMs / 60_000L).toInt().takeIf { it > 0 }
                if (actors.isNotEmpty()) addActors(actors)
            }
        }

        val episodes = mutableListOf<Episode>()
        val seasons = data.optJSONArray("seasons").toObjects()
            .sortedBy { it.optInt("position", Int.MAX_VALUE) }
        for ((index, season) in seasons.withIndex()) {
            val seasonId = season.optInt("id")
            if (seasonId <= 0) continue
            val seasonNum = season.optInt("position").takeIf { it > 0 } ?: (index + 1)
            for (epPage in 1..10) {
                val sData = fetchJson("$mainUrl/api/seasons/$seasonId/episodes?e_page=$epPage&e_per=100")
                    ?.optJSONObject("data") ?: break
                for (ep in sData.optJSONArray("episodes").toObjects()) {
                    val asset = ep.optJSONArray("assets")?.optJSONObject(0)
                    val epSlug = asset?.optString("slug_path")?.takeIf(String::isNotBlank)
                        ?: ep.optString("slug_path").takeIf(String::isNotBlank)
                        ?: continue
                    val epNum = ep.optInt("position").takeIf { it > 0 }
                        ?: asset?.optInt("episode_number")?.takeIf { it > 0 }
                    val epName = ep.optString("name").trim()
                        .ifBlank { epNum?.let { "$it. Bölüm" } ?: title }
                    val epDesc = ep.optString("short_description").trim()
                        .ifBlank { ep.optString("description").trim() }
                        .takeIf(String::isNotBlank)
                    episodes += newEpisode("$mainUrl/$epSlug") {
                        this.name = epName
                        this.season = seasonNum
                        this.episode = epNum
                        this.description = epDesc
                        this.posterUrl = pickPoster(ep)
                    }
                }
                if (!sData.optBoolean("has_more", false)) break
            }
        }
        if (episodes.isEmpty()) {
            val fallbackSlug = data.optString("to_watch_asset_slug_path").takeIf(String::isNotBlank)
                ?: data.optJSONArray("assets")?.optJSONObject(0)?.optString("slug_path")?.takeIf(String::isNotBlank)
            if (fallbackSlug != null) {
                episodes += newEpisode("$mainUrl/$fallbackSlug") {
                    this.name = title
                    this.season = 1
                    this.episode = 1
                }
            }
        }
        return newTvSeriesLoadResponse(title, "$mainUrl/$slug", TvType.TvSeries, episodes.distinctBy { it.data }) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            if (actors.isNotEmpty()) addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val watchSlug = slugOf(data) ?: throw ErrorLoadingException("$name: geçersiz bölüm adresi. $REPORT_HINT")
        val wData = fetchJson("$mainUrl/api/slug/$watchSlug")?.optJSONObject("data")
            ?: throw ErrorLoadingException("$name: bölüm verisi alınamadı. $REPORT_HINT")
        val videoId = wData.optString("video_id").takeIf(String::isNotBlank)
            ?: wData.optJSONArray("assets")?.optJSONObject(0)?.optString("video_id")?.takeIf(String::isNotBlank)
            ?: throw ErrorLoadingException("$name: video kimliği bulunamadı. $REPORT_HINT")

        val dygUrl = "$DYG_API?akamai=true&PublisherId=29&ReferenceId=$videoId&SecretKey=$DYG_SECRET"
        val vData = fetchJson(dygUrl)?.optJSONObject("data")
            ?: throw ErrorLoadingException("$name: video bilgisi alınamadı. $REPORT_HINT")

        for (track in vData.optJSONArray("tracks").toObjects()) {
            val kind = track.optString("kind")
            if (kind.isNotBlank() && kind != "subtitles" && kind != "captions") continue
            val subUrl = fixImageOrUrl(track.optString("file").ifBlank { track.optString("src") }) ?: continue
            val subLang = track.optString("label").ifBlank { track.optString("language") }.ifBlank { "Türkçe" }
            if (Settings.subtitleAllowed(subLang)) subtitleCallback(SubtitleFile(subLang, subUrl))
        }

        val flavors = vData.optJSONObject("flavors")
            ?: throw ErrorLoadingException("$name: video kaynağı yok. $REPORT_HINT")
        val master = flavors.optString("hls").takeIf { it.startsWith("http") }
            ?: flavors.optJSONObject("0")?.optString("file_url_1")?.takeIf { it.startsWith("http") }
            ?: throw ErrorLoadingException("$name: HLS adresi bulunamadı. $REPORT_HINT")

        val playlist = runCatching {
            app.get(master, headers = browserHeaders, referer = "$mainUrl/").text
        }.getOrNull().orEmpty()
        val variants = parseVariants(playlist, master)
        var emitted = 0
        suspend fun emit(streamUrl: String, quality: Int) {
            if (!Settings.qualityAllowed(quality)) return
            val label = if (quality > 0) "$name ${quality}p" else name
            callback(newExtractorLink(name, label, streamUrl, ExtractorLinkType.M3U8) {
                this.referer = "$mainUrl/"
                this.quality = quality
                this.headers = browserHeaders
            })
            emitted++
        }
        if (variants.isEmpty()) {
            emit(master, Qualities.Unknown.value)
        } else {
            variants.distinctBy { it.second }.forEach { (q, u) -> emit(u, q) }
        }
        if (emitted == 0) throw ErrorLoadingException("$name: oynatılabilir kaynak yok. $REPORT_HINT")
        return true
    }

    private fun toSearchResult(item: JSONObject): SearchResponse? {
        val itemType = item.optString("type")
        if (itemType.isNotBlank() && !itemType.startsWith("title_")) return null
        val slug = item.optString("slug_path").trim().takeIf(String::isNotBlank)
            ?: slugOf(item.optString("web_url"))
            ?: return null
        val title = item.optString("name").trim().takeIf(String::isNotBlank) ?: return null
        val poster = fixImageOrUrl(item.optString("image")) ?: pickPoster(item)
        val year = parseYear(item.optLong("released_at"))
        val url = "$mainUrl/$slug"
        val isMovie = item.optBoolean("is_movie") || itemType == "title_movie"
        return if (isMovie) {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    private suspend fun fetchJson(url: String): JSONObject? = runCatching {
        JSONObject(app.get(url, headers = browserHeaders, referer = "$mainUrl/").text)
    }.getOrNull()

    private fun slugOf(url: String): String? = runCatching {
        URI(url.trim()).path.trim('/')
    }.getOrNull()?.takeIf(String::isNotBlank)

    private fun pickPoster(obj: JSONObject): String? {
        for (containerKey in listOf("images", "search_images")) {
            val images = obj.optJSONObject(containerKey) ?: continue
            for ((group, keys) in IMAGE_PREFS) {
                val bucket = images.optJSONObject(group) ?: continue
                for (k in keys) {
                    fixImageOrUrl(bucket.optString(k))?.let { return it }
                }
            }
        }
        return null
    }

    private fun fixImageOrUrl(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isBlank() || s == "null") return null
        return when {
            s.startsWith("http://") || s.startsWith("https://") -> s
            s.startsWith("//") -> "https:$s"
            s.startsWith("/") -> "$mainUrl$s"
            else -> "https://$s"
        }
    }

    private fun parseYear(raw: Long): Int? = when {
        raw in 1900L..2100L -> raw.toInt()
        raw > 100_000_000L -> Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            .apply { timeInMillis = raw * 1000L }
            .get(Calendar.YEAR)
            .takeIf { it in 1900..2100 }
        else -> null
    }

    private fun parseVariants(playlist: String, base: String): List<Pair<Int, String>> {
        if (playlist.contains("TYPE=AUDIO", ignoreCase = true) || playlist.contains("AUDIO=", ignoreCase = true)) return emptyList()
        val variants = mutableListOf<Pair<Int, String>>()
        var quality = 0
        for (line in playlist.lineSequence()) {
            val text = line.trim()
            when {
                text.startsWith("#EXT-X-STREAM-INF") ->
                    quality = RESOLUTION.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                text.isEmpty() || text.startsWith("#") -> Unit
                quality > 0 -> {
                    val resolved = runCatching { URI(base).resolve(text).toString() }.getOrDefault(text)
                    variants += quality to resolved
                    quality = 0
                }
            }
        }
        return variants
    }

    private fun JSONArray?.toObjects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    companion object {
        private val ROW_SLUGS = listOf(
            "Öne Çıkanlar" to "list/anasayfa-one-cikanlar",
            "puhutv Top 15" to "list/anasayfa-puhutv-top-15",
            "puhutv Orijinal" to "puhutv-orijinal",
            "Yerli Diziler" to "yerli-diziler",
            "Komedi Dizileri" to "list/komedi-dizileri",
            "Gerilim Dolu Hikayeler" to "list/anasayfa-gerilim-dolu-hikayeler",
            "Belgesel Yapımlar" to "list/belgesel-yapimlar",
            "Uzak Doğu Rüzgarı" to "uzak-dogu-ruzgari",
            "Yaşam & Gastronomi" to "yasam",
        )
        val ROWS = ROW_SLUGS.map { it.first }
        private val IMAGE_PREFS = listOf(
            "vertical_thumbnail" to listOf("360x514", "main"),
            "wide" to listOf("640x360", "main"),
            "super_wide" to listOf("640x246", "main"),
        )
        private const val SEARCH_API = "https://appservice.puhutv.com/search/search"
        private const val DYG_API = "https://dygvideo.dygdigital.com/api/video_info"
        private const val DYG_SECRET = "NtvApiSecret2014*"
        private val RESOLUTION = Regex("""RESOLUTION=\d+x(\d+)""")
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}
