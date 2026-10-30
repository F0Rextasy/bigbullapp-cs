package com.onepacetr

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
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONObject
import java.net.URLEncoder

/**
 * One Pace Türkçe — onepacetr.net
 *
 * onepacetr.net is a Vite SPA; seasons/episodes/players all come from its public Strapi API.
 * A season is one One Pace arc, its episodes are the arc parts.
 */
class OnepacetrProvider : MainAPI() {
    override var mainUrl = "https://onepacetr.net"
    override var name = "One Pace TR"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime)

    private val api = "https://onepacetradmin-v3-4f0db9f5d700.herokuapp.com/api"

    // Read-only token shipped in the site's own JS bundle (/assets/index-*.js); refresh it there if the API starts returning 403.
    private val auth = mapOf(
        "Authorization" to "Bearer 1fe5d72626ea6144e9d685128cfc40a54c303573432381c8943fc497bb2203f693922fa8afaa872168eef456bf3aa7802507fb2a526582a642241acc0665b30861c60ba344f5d8b925b7ebb75073d9419dfbf9be4b3420427bd22302889792973faaf01c8f1d5bd40105187d22aa9f5595b00a8e2700b94614678cc3303a7ac3"
    )

    /** The Strapi API has one listing (all seasons); the row label is purely cosmetic. */
    override val mainPage get() = ROWS.filter { Settings.rowEnabled(it) }.map { MainPageData(it, mainUrl) }

    // Encrypted JS players with no CloudStream extractor (clone/abyss, Google Drive, upnshare).
    private val jsOnlyPlayers =
        listOf("abyssplayer", "short.icu", "short.ink", "gett.su", "listeamed.net", "upns.live")

    // The site hides these two itself (index-*.js: name!=="gettsu" && name!=="vidguard"); Vidguard's domains answer 522 or are parked.
    private val hiddenPlayers = listOf("gettsu", "vidguard")

    private suspend fun getJson(url: String): JSONObject =
        JSONObject(app.get(url, headers = auth).text)

    private fun seasonUrl(slug: String) = "$api/seasons?filters[slug][\$eq]=$slug&populate=*"

    private val seasonFields =
        "fields[0]=name&fields[1]=number&fields[2]=slug&populate[image][fields][0]=url"

    private val searchPageSize = 30

    private fun JSONObject.objects(): List<JSONObject> {
        val data = optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { data.optJSONObject(it) }
    }

    private fun JSONObject.pagination() = optJSONObject("meta")?.optJSONObject("pagination")

    private fun JSONObject.toSeasonResult(): SearchResponse? {
        val slug = optString("slug")
        if (slug.isBlank()) return null
        return newTvSeriesSearchResponse(optString("name"), seasonUrl(slug), TvType.Anime) {
            this.posterUrl = optJSONObject("image")?.optString("url")?.takeIf { it.isNotBlank() }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val body = getJson("$api/seasons?pagination[page]=$page&pagination[pageSize]=100&$seasonFields")
        val items = body.objects().sortedBy { it.optInt("number") }.mapNotNull { it.toSeasonResult() }
        val pageCount = body.pagination()?.optInt("pageCount", 1) ?: 1
        return newHomePageResponse(request.name, items, hasNext = page < pageCount)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        search(query, 1)?.items ?: emptyList()

    /** Strapi filters server-side on season name or any episode name, paged with start/limit. */
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val start = (page - 1).coerceAtLeast(0) * searchPageSize
        val q = URLEncoder.encode(query, "UTF-8")
        val body = getJson(
            "$api/seasons?sort[0]=number:asc&pagination[start]=$start&pagination[limit]=$searchPageSize" +
                "&$seasonFields&filters[\$or][0][name][\$containsi]=$q&filters[\$or][1][episodes][name][\$containsi]=$q"
        )
        val data = body.objects()
        val total = body.pagination()?.optInt("total", 0) ?: 0
        return newSearchResponseList(data.mapNotNull { it.toSeasonResult() }, start + data.size < total)
    }

    override suspend fun load(url: String): LoadResponse? {
        val season = runCatching { getJson(url).optJSONArray("data")?.optJSONObject(0) }
            .getOrNull() ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val title = season.optString("name").takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val seasonNumber = season.optInt("number", 1)
        val raw = season.optJSONArray("episodes")
        val episodes = (0 until (raw?.length() ?: 0))
            .mapNotNull { raw?.optJSONObject(it) }
            .sortedBy { it.optInt("number") }
            .mapIndexed { index, episode ->
                newEpisode("$api/episodes/${episode.optString("slug")}") {
                    this.name = episode.optString("name")
                    this.season = seasonNumber
                    this.episode = episode.optInt("number").takeIf { it > 0 } ?: index + 1
                    this.runTime = parseSeconds(episode.optString("duration"))
                }
            }
        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = season.optJSONObject("image")?.optString("url")?.takeIf { it.isNotBlank() }
            this.plot = season.optString("content").takeIf { it.isNotBlank() }
            this.tags = listOfNotNull(season.optString("resolution").takeIf { it.isNotBlank() })
        }
    }

    /** Episode runtimes are "30:58" or "1:00:00"; Episode.runTime is in seconds. */
    private fun parseSeconds(value: String): Int? {
        val parts = value.split(":").map { it.trim().toIntOrNull() ?: return null }
        return parts.fold(0) { acc, part -> acc * 60 + part }.takeIf { it > 0 }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val players = runCatching { getJson(data).optJSONObject("data")?.optJSONArray("players") }
            .getOrNull() ?: throw ErrorLoadingException("$name: kaynak bulunamadı. $REPORT_HINT")
        var loaded = false
        val linkCallback: (ExtractorLink) -> Unit = {
            loaded = true
            if (Settings.qualityAllowed(it.quality)) callback(it)
        }
        val subtitleFilter: (SubtitleFile) -> Unit = { if (Settings.subtitleAllowed(it.lang)) subtitleCallback(it) }
        val seenPlayers = HashSet<String>()
        for (index in 0 until players.length()) {
            val player = players.optJSONObject(index) ?: continue
            val embed = player.optString("url")
            if (embed.isBlank() || !seenPlayers.add(embed)) continue
            if (player.optString("name") in hiddenPlayers) continue
            if (jsOnlyPlayers.any { embed.contains(it) }) continue
            if (embed.contains("drive.google.com")) {
                val fileId = Regex("""/file/d/([^/]+)""").find(embed)?.groupValues?.get(1)
                if (fileId != null) {
                    linkCallback(
                        newExtractorLink(
                            name, "Google Drive",
                            "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t",
                            ExtractorLinkType.VIDEO,
                        )
                    )
                }
                continue
            }
            loadExtractor(embed, mainUrl, subtitleFilter, linkCallback)
        }
        if (!loaded) throw ErrorLoadingException("$name: oynatılabilir akış yok. $REPORT_HINT")
        return loaded
    }

    companion object {
        /** Homepage row labels; the API has one listing, so each row maps to the same data. */
        val ROWS = listOf("Sezonlar")
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}
