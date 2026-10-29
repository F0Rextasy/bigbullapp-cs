package com.dizilla

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Dizilla — dizilla.nl (301 → dizilla.club → dizilla.now).
 * Next.js site: series metadata + episode list live in the JSON-LD blocks,
 * the episode page carries its player iframes in the RSC payload ("sources").
 */
class DizillaProvider : MainAPI() {
    override var mainUrl = "https://dizilla.nl"
    override var name = "Dizilla"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage get() = ROWS.mapNotNull { name ->
        if (!Settings.rowEnabled(name)) return@mapNotNull null
        when (name) {
            "Yabancı Diziler" -> MainPageData(name, "$mainUrl/yabanci-dizi-izle?page=SAYFA")
            "Yerli Diziler" -> MainPageData(name, "$mainUrl/yerli-dizi-izle?page=SAYFA")
            "K-Drama" -> MainPageData(name, "$mainUrl/kdrama-izle?page=SAYFA")
            "Asya Dizileri" -> MainPageData(name, "$mainUrl/asya-dizileri-izle?page=SAYFA")
            "Tüm Diziler" -> MainPageData(name, "$mainUrl/dizi-izle?page=SAYFA")
            "Anime" -> MainPageData(name, "$mainUrl/anime-izle?page=SAYFA")
            "Türkçe Dublaj" -> MainPageData(name, "$mainUrl/turkce-dublaj-dizi-izle?page=SAYFA")
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data.replace("SAYFA", page.toString())).document
        return newHomePageResponse(request.name, doc.cards(), hasNext = doc.hasNextPage())
    }

    override suspend fun search(query: String): List<SearchResponse> =
        search(query, 1)?.items ?: emptyList()

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val url = "$mainUrl/ara?q=${URLEncoder.encode(query, "UTF-8")}&page=$page"
        val doc = app.get(url).document
        return newSearchResponseList(doc.cards(), doc.hasNextPage())
    }

    private fun Document.cards(): List<SearchResponse> =
        select("a.pcard").distinctBy { it.attr("href") }.mapNotNull { it.toSearchResult() }

    private fun Document.hasNextPage(): Boolean = selectFirst("a[rel=next]") != null

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val title = selectFirst("span.mvk-t")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: attr("aria-label").substringBefore(",").trim().takeIf { it.isNotBlank() }
            ?: return null
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = fixUrlNull(selectFirst("img")?.attr("src"))
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = runCatching { app.get(url) }.getOrNull()
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val doc = response.document
        // Flight payload escapes its JSON inside a JS string, so unescape before slicing.
        val rsc = response.text.replace("\\\"", "\"")
        val objects = doc.select("script[type=application/ld+json]")
            .mapNotNull { runCatching { JSONObject(it.data()) }.getOrNull() }
            .filter { it.optString("@type") == "TVSeries" }
        val series = objects.maxByOrNull { countEpisodes(it) }
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")

        val episodes = LinkedHashMap<String, Episode>()
        objects.forEach { obj ->
            seasonsOf(obj).forEach { season ->
                val seasonNumber = season.optInt("seasonNumber", 1)
                val seasonEpisodes = season.optJSONArray("episode") ?: return@forEach
                for (index in 0 until seasonEpisodes.length()) {
                    val item = seasonEpisodes.optJSONObject(index) ?: continue
                    val episodeUrl = fixUrlNull(item.optString("url")) ?: continue
                    val episodeNumber = item.optInt("episodeNumber", index + 1)
                    episodes.getOrPut(episodeUrl) {
                        newEpisode(episodeUrl) {
                            this.season = seasonNumber
                            this.episode = episodeNumber
                            this.name = "$seasonNumber. Sezon $episodeNumber. Bölüm"
                            this.description = item.optString("description").takeIf { it.isNotBlank() }
                        }
                    }
                }
            }
        }

        val title = series.optString("name").takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val rating = series.optJSONObject("aggregateRating")?.optString("ratingValue")?.toDoubleOrNull()
        val poster = fixUrlNull(doc.selectFirst("img[src*='/images/tv/poster/']")?.attr("src"))
            ?: fixUrlNull(series.optString("image"))
        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes.values.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 })),
        ) {
            this.posterUrl = poster
            this.plot = series.optString("description").takeIf { it.isNotBlank() }
            this.year = doc.select("dl.frows dt").firstOrNull { it.text().contains("İlk yayın") }
                ?.nextElementSibling()?.text()?.trim()?.toIntOrNull()
            this.score = rating?.let { Score.from10(it) }
            this.duration = Regex("""PT(?:(\d+)H)?(\d+)M""").find(series.optString("timeRequired"))
                ?.let { (it.groupValues[1].toIntOrNull() ?: 0) * 60 + it.groupValues[2].toInt() }
            this.actors = series.optJSONArray("actor")?.let { array ->
                (0 until array.length()).mapNotNull {
                    val name = array.optJSONObject(it)?.optString("name")?.takeIf { name -> name.isNotBlank() }
                    name?.let { ActorData(Actor(it)) }
                }
            }?.takeIf { it.isNotEmpty() }
            this.recommendations = jsonArrayAfter(rsc, "\"similar\":[")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    val title = item.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val relatedUrl = fixUrlNull(item.optString("url")) ?: return@mapNotNull null
                    newTvSeriesSearchResponse(title, relatedUrl, TvType.TvSeries) {
                        this.posterUrl = fixUrlNull(item.optString("poster"))
                    }
                }
            }
            jsonArrayAfter(rsc, "\"trailers\":[")?.let { array ->
                (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("ytId") }
                    .firstOrNull { it.isNotBlank() }
                    ?.let { addTrailer("https://www.youtube.com/embed/$it") }
            }
        }
    }

    private fun seasonsOf(series: JSONObject): List<JSONObject> = when (val seasons = series.opt("containsSeason")) {
        is JSONObject -> listOf(seasons)
        is JSONArray -> (0 until seasons.length()).mapNotNull { seasons.optJSONObject(it) }
        else -> emptyList()
    }

    private fun countEpisodes(series: JSONObject): Int =
        seasonsOf(series).sumOf { it.optJSONArray("episode")?.length() ?: 0 }

    private data class Player(val url: String, val label: String, val quality: String, val lang: String)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val linkCallback: (ExtractorLink) -> Unit = { if (Settings.qualityAllowed(it.quality)) callback(it) }
        val subtitleFilter: (SubtitleFile) -> Unit = { if (Settings.subtitleAllowed(it.lang)) subtitleCallback(it) }
        // Flight payload escapes its JSON inside a JS string, so unescape before slicing.
        val page = runCatching { app.get(data).text }.getOrNull()?.replace("\\\"", "\"")
            ?: throw ErrorLoadingException("$name: kaynak bulunamadı. $REPORT_HINT")
        val players = jsonArrayAfter(page, "\"sources\":[")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                if (item.optBoolean("trailer")) return@mapNotNull null
                val src = item.optString("src").takeIf { it.startsWith("http") } ?: return@mapNotNull null
                Player(
                    src,
                    item.optString("name"),
                    item.optString("quality"),
                    item.optString("langLabel").ifBlank { item.optString("lang") },
                )
            }
        }.orEmpty()
            // dub-1 and sub-1 often point at the same player, whose own audio menu holds both; merge the labels.
            .groupBy { it.url }
            .map { (_, entries) ->
                entries.first().copy(
                    lang = entries.map { it.lang }.filter { it.isNotBlank() }.distinct().joinToString(" / "),
                )
            }

        var found = false
        val seenSubtitles = HashSet<String>()
        players.forEach { player ->
            runCatching {
                if (loadPlayer(data, player, linkCallback, subtitleFilter, seenSubtitles)) found = true
            }
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir akış yok. $REPORT_HINT")
        return found
    }

    /** pichive iframe -> NP_CONFIG -> nm.php master playlist (+ nsub.php subtitles). */
    private suspend fun loadPlayer(
        episodeUrl: String,
        player: Player,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit,
        seenSubtitles: MutableSet<String>,
    ): Boolean {
        // The player host only answers to a referer on the dizilla host, i.e. the episode page itself.
        val html = app.get(player.url, referer = episodeUrl).text
        val config = jsonObjectAfter(html, "window.NP_CONFIG") ?: return false
        val linkName = listOf(this.name, player.lang, player.label)
            .filter { it.isNotBlank() }.joinToString(" ")
        val quality = Regex("""(\d{3,4})""").find(player.quality)?.value?.toIntOrNull() ?: Qualities.Unknown.value

        var found = false
        val masters = config.optJSONArray("sources") ?: return false
        for (index in 0 until masters.length()) {
            val master = masters.optJSONObject(index)?.optString("master")
                ?.takeIf { it.startsWith("http") } ?: continue
            callback.invoke(
                newExtractorLink(this.name, linkName, master, ExtractorLinkType.M3U8) {
                    this.referer = player.url
                    this.quality = quality
                }
            )
            found = true
        }

        val subtitles = config.optJSONArray("subtitles")
        if (subtitles != null) {
            for (index in 0 until subtitles.length()) {
                val item = subtitles.optJSONObject(index) ?: continue
                val subtitleUrl = item.optString("url").takeIf { it.startsWith("http") } ?: continue
                if (!seenSubtitles.add(subtitleUrl)) continue
                val subtitleLang = item.optString("label").takeIf { it.isNotBlank() } ?: "Türkçe"
                subtitleCallback.invoke(
                    newSubtitleFile(subtitleLang, subtitleUrl) {
                        this.headers = mapOf("Referer" to player.url)
                    }
                )
            }
        }
        return found
    }

    private fun jsonArrayAfter(text: String, marker: String): JSONArray? {
        var from = 0
        while (true) {
            val at = text.indexOf(marker, from)
            if (at < 0) return null
            val open = at + marker.length - 1
            val slice = sliceBalanced(text, open, '[', ']')
            if (slice != null) runCatching { JSONArray(slice) }.getOrNull()?.let { return it }
            from = open + 1
        }
    }

    private fun jsonObjectAfter(text: String, marker: String): JSONObject? {
        val at = text.indexOf(marker)
        if (at < 0) return null
        val open = text.indexOf('{', at)
        if (open < 0) return null
        val slice = sliceBalanced(text, open, '{', '}') ?: return null
        return runCatching { JSONObject(slice) }.getOrNull()
    }

    private fun sliceBalanced(text: String, open: Int, openChar: Char, closeChar: Char): String? {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in open until text.length) {
            val char = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                openChar -> depth++
                closeChar -> {
                    depth--
                    if (depth == 0) return text.substring(open, index + 1)
                }
            }
        }
        return null
    }

    companion object {
        /** Homepage rows in display order; all map to live listing pages. */
        val ROWS = listOf(
            "Yabancı Diziler",
            "Yerli Diziler",
            "K-Drama",
            "Asya Dizileri",
            "Tüm Diziler",
            "Anime",
            "Türkçe Dublaj",
        )

        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}
