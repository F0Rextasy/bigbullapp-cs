package com.aniarsiv

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * AniArsiv — aniarsiv.com SPA'sının genel API'si.
 *
 * Arama `/api/animes?q=`, detay `/api/anime/<slug>`, bölüm `/api/bolum/<slug>`.
 * Bölüm yanıtındaki `cdn_video.video_url` doğrudan MP4; diğer `links[].url` embed.
 */
class AniArsivProvider : MainAPI() {
    override var mainUrl = "https://aniarsiv.com"
    override var name = "AniArsiv"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Anime, TvType.TvSeries, TvType.Movie)
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val mainPage get() = ROWS.mapNotNull { row ->
        if (!Settings.rowEnabled(row)) return@mapNotNull null
        when (row) {
            "Trend" -> MainPageData(row, "$mainUrl/api/trending")
            "Tüm Animeler" -> MainPageData(row, "$mainUrl/api/animes")
            else -> null
        }
    }

    private val headers = mapOf(
        "user-agent" to UA,
        "accept" to "application/json, text/plain, */*",
        "referer" to "$mainUrl/",
    )

    private fun posterOf(o: JSONObject): String? =
        o.optString("cover_image").trim().takeIf { it.startsWith("http") }
            ?: o.optString("banner_image").trim().takeIf { it.startsWith("http") }
            ?: o.optString("resim").trim().takeIf { it.startsWith("https://") }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1 && !request.data.endsWith("/api/animes")) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
        val url = if (request.data.endsWith("/api/animes")) "${request.data}?page=$page" else request.data
        val raw = app.get(url, headers = headers).text.trim()
        val arr = if (raw.startsWith("[")) JSONArray(raw) else JSONObject(raw).optJSONArray("items") ?: JSONArray()
        val items = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val title = o.optString("baslik").trim()
            val slug = o.optString("slug").trim()
            if (title.isBlank() || slug.isBlank()) return@mapNotNull null
            newTvSeriesSearchResponse(title, "$mainUrl/anime/$slug", TvType.Anime) {
                this.posterUrl = posterOf(o)
                this.year = o.optInt("yil").takeIf { it > 1900 }
                this.score = Score.from10(o.optString("puani"))
            }
        }
        return newHomePageResponse(request.name, items, hasNext = request.data.endsWith("/api/animes") && items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/api/animes?q=${URLEncoder.encode(query.trim(), "UTF-8")}"
        val root = JSONObject(app.get(url, headers = headers).text)
        val arr = root.optJSONArray("items") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val title = o.optString("baslik").trim()
            val slug = o.optString("slug").trim()
            if (title.isBlank() || slug.isBlank()) return@mapNotNull null
            newTvSeriesSearchResponse(title, "$mainUrl/anime/$slug", TvType.Anime) {
                this.posterUrl = posterOf(o)
                this.year = o.optInt("yil").takeIf { it > 1900 }
                this.score = Score.from10(o.optString("puani"))
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.substringAfter("/anime/").substringBefore('/').substringBefore('?')
        if (slug.isBlank()) throw ErrorLoadingException("$name: slug okunamadı. $REPORT_HINT")
        val o = JSONObject(app.get("$mainUrl/api/anime/$slug", headers = headers).text)
        val title = o.optString("baslik").trim()
            .ifBlank { o.optString("ingilizce").trim() }
            .ifBlank { throw ErrorLoadingException("$name: başlık okunamadı. $REPORT_HINT") }
        val episodesArr = o.optJSONArray("bolumler")
        val episodes = if (episodesArr == null) emptyList() else (0 until episodesArr.length()).mapNotNull { i ->
            val e = episodesArr.optJSONObject(i) ?: return@mapNotNull null
            val epSlug = e.optString("slug").trim()
            if (epSlug.isBlank()) return@mapNotNull null
            newEpisode("$mainUrl/bolum/$epSlug") {
                this.name = e.optString("ad").trim().ifBlank { "Bölüm ${e.optInt("index")}" }
                this.episode = e.optInt("index").takeIf { it > 0 }
            }
        }.distinctBy { it.data }
        val poster = posterOf(o)
        val banner = o.optString("banner_image").trim().takeIf { it.startsWith("http") } ?: poster
        val plot = o.optString("ozet").trim().ifBlank { null }
        val year = o.optInt("yil").takeIf { it > 1900 }
        val score = Score.from10(o.optString("puani"))
        val tags = o.optString("turler").split(',').map { it.trim() }.filter { it.isNotBlank() }
        if (episodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(title, "$mainUrl/anime/$slug", TvType.Anime, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = banner
                this.plot = plot
                this.year = year
                this.score = score
                this.tags = tags.ifEmpty { null }
            }
        }
        return newMovieLoadResponse(title, "$mainUrl/anime/$slug", TvType.Movie, "$mainUrl/anime/$slug") {
            this.posterUrl = poster
            this.backgroundPosterUrl = banner
            this.plot = plot
            this.year = year
            this.score = score
            this.tags = tags.ifEmpty { null }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val slug = data.substringAfter("/bolum/").substringBefore('/').substringBefore('?')
        if (slug.isBlank()) throw ErrorLoadingException("$name: bölüm slug okunamadı. $REPORT_HINT")
        val root = JSONObject(app.get("$mainUrl/api/bolum/$slug", headers = headers).text)
        var found = false
        val seen = mutableSetOf<String>()
        suspend fun emit(stream: String, label: String, referer: String = "$mainUrl/") {
            if (!stream.startsWith("http") || !seen.add(stream)) return
            val quality = getQualityFromName(label)
            if (!Settings.qualityAllowed(quality)) return
            callback(newExtractorLink(name, label, stream, ExtractorLinkType.VIDEO) {
                this.referer = referer
                this.quality = quality
                this.headers = mapOf("user-agent" to UA)
            })
            found = true
        }
        val cdn = root.optJSONObject("cdn_video")
        if (cdn != null && !cdn.optBoolean("is_short", false)) {
            cdn.optString("video_url").takeIf { it.startsWith("http") }?.let { emit(it, "AniArsiv") }
        }
        val links = root.optJSONArray("links")
        if (links != null) {
            for (i in 0 until minOf(links.length(), MAX_LINKS)) {
                val link = links.optJSONObject(i) ?: continue
                val embed = link.optString("url").trim()
                if (!embed.startsWith("http") || !seen.add(embed)) continue
                val player = link.optString("player").ifBlank { "Kaynak" }
                val handled = if (embed.contains("tau-video.xyz")) {
                    resolveTau(embed, callback)
                } else {
                    runCatching { loadExtractor(embed, "$mainUrl/", subtitleCallback, callback) }
                        .getOrDefault(false)
                }
                if (handled) found = true else if (embed.contains(".mp4")) emit(embed, player)
                if (found && seen.size >= 4) break
            }
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir kaynak yok. $REPORT_HINT")
        return true
    }
    private suspend fun resolveTau(embed: String, callback: (ExtractorLink) -> Unit): Boolean {
        val id = Regex("""/embed/([^/?#]+)""").find(embed)?.groupValues?.get(1) ?: return false
        val root = runCatching {
            JSONObject(app.get("https://tau-video.xyz/api/video/$id", headers = headers).text)
        }.getOrNull() ?: return false
        val urls = root.optJSONArray("urls") ?: return false
        var sent = false
        for (index in 0 until urls.length()) {
            val row = urls.optJSONObject(index) ?: continue
            val stream = row.optString("url").trim()
            if (!stream.startsWith("http")) continue
            val label = row.optString("label").ifBlank { "Kaynak" }
            val quality = getQualityFromName(label)
            if (!Settings.qualityAllowed(quality)) continue
            callback(newExtractorLink(name, "TAU $label", stream, ExtractorLinkType.VIDEO) {
                this.referer = embed
                this.quality = quality
                this.headers = mapOf("user-agent" to UA)
            })
            sent = true
        }
        return sent
    }

    companion object {
        val ROWS = listOf("Trend", "Tüm Animeler")
        private const val MAX_LINKS = 12
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}
