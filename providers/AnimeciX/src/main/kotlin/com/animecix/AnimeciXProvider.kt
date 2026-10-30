package com.animecix

import android.util.Base64
import com.lagradost.cloudstream3.Episode
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
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URLEncoder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AnimeciX — animecix.tv
 *
 * The site is an Angular front end over a JSON API under `/secure/`. That API answers requests
 * whose `X-E-H` header is missing or undecryptable with the wrong title, so every call carries an
 * AES-GCM token produced exactly like the site's own HTTP interceptor does it. Episodes are
 * `secure/best-video?...` redirects that end on tau-video.xyz, and tau's `/api/video/<key>`
 * answers with the plain mp4 renditions we hand to the player.
 */
class AnimeciXProvider : MainAPI() {
    override var mainUrl = "https://animecix.tv"
    override var name = "AnimeciX"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime)

    override val mainPage get() = ROWS.mapNotNull { name ->
        if (!Settings.rowEnabled(name)) return@mapNotNull null
        when (name) {
            "Son Bölümler" -> MainPageData(name, "$mainUrl/secure/last-episodes?perPage=10")
            "Seriler" -> MainPageData(name, "$mainUrl/secure/titles?type=series&onlyStreamable=true&perPage=16")
            "Filmler" -> MainPageData(name, "$mainUrl/secure/titles?type=movie&onlyStreamable=true&perPage=16")
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val body = api("${request.data}&page=$page")
        if (request.data.contains("/last-episodes")) {
            val rows = body.optJSONArray("data") ?: return newHomePageResponse(request.name, emptyList())
            val items = (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.toEpisodeCard() }
            return newHomePageResponse(request.name, items, hasNext = page < body.optInt("last_page", 0))
        }
        val pager = body.optJSONObject("pagination") ?: return newHomePageResponse(request.name, emptyList())
        val rows = pager.optJSONArray("data") ?: return newHomePageResponse(request.name, emptyList())
        val items = (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.toSearchResponse() }
        return newHomePageResponse(request.name, items, hasNext = page < pager.optInt("last_page", 0))
    }

    override suspend fun search(query: String): List<SearchResponse> = search(query, 1)?.items ?: emptyList()

    /**
     * shortcut: `/secure/search` caps its answer at 15 hits and ignores every paging parameter we
     * probed (page, perPage, offset, skip), so only the first page has results; upgrade when the
     * site grows a real pager.
     */
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (page > 1) return newSearchResponseList(emptyList())
        val rows = api("search/${URLEncoder.encode(query, "UTF-8")}?limit=20").optJSONArray("results")
            ?: return newSearchResponseList(emptyList())
        return newSearchResponseList((0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.toSearchResponse() })
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = url.substringAfter("/titles/", "").substringBefore('?').toIntOrNull() ?: return null
        val title = api("titles/$id?titleId=$id").optJSONObject("title") ?: return null
        val episodes = mutableListOf<Episode>()
        val seasons = title.optJSONArray("seasons")
        if (seasons != null && seasons.length() > 0) {
            for (i in 0 until seasons.length()) {
                val number = seasons.optJSONObject(i)?.optInt("number", -1) ?: continue
                if (number < 0) continue
                episodes.addAll(seasonEpisodes(id, number))
            }
        } else {
            movieEpisode(title)?.let { episodes.add(it) }
        }
        if (episodes.isEmpty()) throw ErrorLoadingException("$name: bölüm bulunamadı. $REPORT_HINT")

        val genres = title.optJSONArray("genres")
        val tags = genres?.let { array ->
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("display_name")?.takeIf(String::isNotBlank) }
        }
        val poster = title.optString("poster").takeIf(String::isNotBlank)
        return newTvSeriesLoadResponse(title.optString("name"), titleUrl(id), TvType.Anime, episodes) {
            this.posterUrl = poster
            this.year = title.optInt("year", 0).takeIf { it > 0 }
            this.plot = title.optString("description").takeIf(String::isNotBlank)
            this.tags = tags
            this.score = Score.from10(title.optString("mal_vote_average").takeIf(String::isNotBlank))
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val emit: (ExtractorLink) -> Unit = { if (Settings.qualityAllowed(it.quality)) callback(it) }
        val subtitleFilter: (SubtitleFile) -> Unit = { if (Settings.subtitleAllowed(it.lang)) subtitleCallback(it) }

        // Episode data is the relative `secure/best-video?...` path; movie data is already the embed.
        val target = if (data.startsWith("http")) data else "$mainUrl/$data"
        val embed = if (target.contains("$TAU_HOST/embed/")) target
        else app.get(target, headers = uaHeaders(), referer = "$mainUrl/").url

        if (!embed.contains("$TAU_HOST/embed/")) return loadExtractor(embed, "$mainUrl/", subtitleFilter, emit)

        val key = embed.substringAfter("/embed/").substringBefore('?').takeIf(String::isNotBlank)
            ?: throw ErrorLoadingException("$name: akış adresi çözülemedi. $REPORT_HINT")
        val urls = runCatching {
            JSONObject(app.get("$TAU_HOST/api/video/$key", headers = uaHeaders(), referer = "$TAU_HOST/").text)
                .optJSONArray("urls")
        }.getOrNull() ?: throw ErrorLoadingException("$name: akış bulunamadı. $REPORT_HINT")

        var found = false
        for (i in 0 until urls.length()) {
            val item = urls.optJSONObject(i) ?: continue
            val stream = item.optString("url").takeIf(String::isNotBlank) ?: continue
            val label = item.optString("label")
            emit(
                newExtractorLink(
                    name,
                    "TauVideo $label".trim(),
                    stream,
                    if (stream.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    this.referer = "$TAU_HOST/"
                    this.quality = getQualityFromName(label)
                }
            )
            found = true
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir akış yok. $REPORT_HINT")
        return true
    }

    /** One request per season; the answer also carries the neighbouring seasons' edges. */
    private suspend fun seasonEpisodes(titleId: Int, season: Int): List<Episode> {
        val rows = api("related-videos?episode=1&season=$season&videoId=0&titleId=$titleId")
            .optJSONArray("videos") ?: return emptyList()
        val episodes = mutableListOf<Episode>()
        for (i in 0 until rows.length()) {
            val video = rows.optJSONObject(i) ?: continue
            if (video.optInt("season_num", -1) != season) continue
            val data = video.optString("url").takeIf(String::isNotBlank) ?: continue
            val number = video.optInt("episode_num", 0)
            episodes.add(newEpisode(data) {
                this.name = video.optString("name").takeIf(String::isNotBlank) ?: "$season. Sezon $number. Bölüm"
                this.season = season
                this.episode = number
            })
        }
        return episodes
    }

    /** Films ship their players inline; we can only resolve the tau-video ones ourselves. */
    private fun movieEpisode(title: JSONObject): Episode? {
        val videos = title.optJSONArray("videos") ?: return null
        val urls = (0 until videos.length()).mapNotNull {
            videos.optJSONObject(it)?.optString("url")?.takeIf(String::isNotBlank)
        }
        val data = urls.firstOrNull { it.contains("$TAU_HOST/embed/") } ?: urls.firstOrNull() ?: return null
        return newEpisode(data) {
            this.name = "Filmi İzle"
            this.season = 1
            this.episode = 1
        }
    }

    private fun JSONObject.toSearchResponse(): SearchResponse? {
        val id = optInt("id", 0)
        val title = optString("name").trim()
        if (id <= 0 || title.isBlank()) return null
        val poster = optString("poster").takeIf(String::isNotBlank)
        return newAnimeSearchResponse(title, titleUrl(id), TvType.Anime) { this.posterUrl = poster }
    }

    private fun JSONObject.toEpisodeCard(): SearchResponse? {
        val titleId = optInt("title_id", 0)
        val title = optString("title_name").trim()
        if (titleId <= 0 || title.isBlank()) return null
        val poster = optString("title_poster").takeIf(String::isNotBlank)
        val label = "S${optInt("season_number", 1)}B${optInt("episode_number", 0)} - $title"
        return newAnimeSearchResponse(label, titleUrl(titleId), TvType.Anime) { this.posterUrl = poster }
    }

    private fun titleUrl(id: Int) = "$mainUrl/secure/titles/$id?titleId=$id"

    /** Both list and detail routes need the token or they answer with an unrelated title. */
    private suspend fun api(path: String): JSONObject {
        val url = if (path.startsWith("http")) path else "$mainUrl/secure/$path"
        val body = app.get(
            url,
            headers = uaHeaders() + ("X-E-H" to edgeHeader(url.substringAfter('?', ""))),
            referer = "$mainUrl/",
        ).text
        return runCatching { JSONObject(body) }
            .getOrElse { throw ErrorLoadingException("$name: API yanıtı okunamadı. $REPORT_HINT") }
    }

    /** AES-256-GCM over `{version}<query>`; the site keeps the same static key in its bundle. */
    private fun edgeHeader(query: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(EDGE_KEY.toByteArray(), "AES"), GCMParameterSpec(128, iv))
        val sealed = cipher.doFinal((EDGE_TAG + query).toByteArray())
        return Base64.encodeToString(sealed, Base64.NO_WRAP) + "." + Base64.encodeToString(iv, Base64.NO_WRAP)
    }

    private fun uaHeaders() = mapOf("User-Agent" to UA)

    companion object {
        /** Homepage rows in display order; all map to live listing pages. */
        val ROWS = listOf(
            "Son Bölümler",
            "Seriler",
            "Filmler",
        )

        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val TAU_HOST = "https://tau-video.xyz"
        private const val EDGE_KEY = "i4C7R2fXGocdYgFLzCbDlsJjukf8G58b"
        private const val EDGE_TAG = "{version}"
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}
