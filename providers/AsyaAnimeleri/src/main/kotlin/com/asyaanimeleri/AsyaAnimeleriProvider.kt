package com.asyaanimeleri

import android.util.Base64
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TrailerData
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.net.URLEncoder

/**
 * AsyaAnimeleri — asyaanimeleri.top
 *
 * Search hits and catalogue cards are `/series/<slug>/` pages; a series page lists its
 * episodes in `.eplister`, a film has a single entry pointing at its root watch page.
 * The watch page keeps every mirror as a base64-encoded iframe in `select.mirror`,
 * one of them being `rumble.com/embed/<id>`.
 */
class AsyaAnimeleriProvider : MainAPI() {
    override var mainUrl = "https://asyaanimeleri.top"
    override var name = "AsyaAnimeleri"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)

    override val mainPage = mainPageOf("$mainUrl/series/" to "Son Eklenenler")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // `/series/page/N/` repeats the newest 20 (page 2 == page 1), so there is no next page.
        if (page > 1) return newHomePageResponse(request.name, emptyList())
        val doc = app.get(request.data).document
        val items = doc.select("article.bs").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a[itemprop=url]") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        if (!href.contains("/series/")) return null
        val title = anchor.attr("title").ifBlank { anchor.text() }.trim()
        if (title.isBlank()) return null
        val type = if (selectFirst(".typez")?.text().equals("Movie", true)) TvType.Movie else TvType.Anime
        return newTvSeriesSearchResponse(title, href, type) {
            this.posterUrl = fixUrlNull(selectFirst("img")?.attr("src"))
        }
    }

    override suspend fun search(query: String): List<SearchResponse> =
        search(query, 1)?.items ?: emptyList()

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val doc = app.get("$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}&paged=$page").document
        val items = doc.select("article.bs").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newSearchResponseList(items, doc.selectFirst(".pagination a.next") != null)
    }

    /**
     * The theme's live search posts to admin-ajax (`action=ts_ac_do_search`) and answers with
     * the ten best title matches as JSON instead of a full search page.
     */
    override suspend fun quickSearch(query: String): List<SearchResponse>? = runCatching {
        val body = app.post(
            "$mainUrl/wp-admin/admin-ajax.php",
            data = mapOf("action" to "ts_ac_do_search", "ts_ac_query" to query),
        ).text
        val hits = JSONObject(body).optJSONArray("series")?.optJSONObject(0)?.optJSONArray("all")
            ?: return@runCatching emptyList()
        (0 until hits.length()).mapNotNull { index ->
            val hit = hits.optJSONObject(index) ?: return@mapNotNull null
            val title = hit.optString("post_title")
            val href = hit.optString("post_link")
            if (title.isBlank() || href.isBlank()) return@mapNotNull null
            val type = if (hit.optString("post_type") == "Movie") TvType.Movie else TvType.Anime
            newTvSeriesSearchResponse(title, href, type) {
                this.posterUrl = hit.optString("post_image").takeIf { it.isNotBlank() }
            }
        }
    }.getOrNull()

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?.removeSuffix(" - Asya Animeleri")?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val episodes = doc.select(".eplister li a[href]").mapNotNull { anchor ->
            val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            val number = anchor.selectFirst(".epl-num")?.text()?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull()
            newEpisode(href) {
                this.name = anchor.selectFirst(".epl-title")?.text()?.trim().orEmpty().ifBlank { "Bölüm $number" }
                this.episode = number
            }
        }.distinctBy { it.data }.reversed()
        if (episodes.isEmpty()) throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")

        val type = if (doc.info("Tip") == "Movie") TvType.Movie else TvType.Anime
        return newTvSeriesLoadResponse(title, url, type, episodes) {
            this.posterUrl = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
            this.plot = doc.selectFirst(".synp .entry-content")?.text()?.trim()
            this.year = Regex("""\d{4}""").find(doc.info("Yayın Yılı").orEmpty())?.value?.toIntOrNull()
            this.duration = doc.info("Süre").minutes()
            this.score = Score.from(doc.selectFirst("meta[itemprop=ratingValue]")?.attr("content"), 10)
            this.showStatus = when (doc.info("Durum")) {
                "Tamamlandı" -> ShowStatus.Completed
                "Devam Ediyor" -> ShowStatus.Ongoing
                else -> null
            }
            this.trailers = listOfNotNull(
                doc.selectFirst("a.trailerbutton[href]")?.attr("href")?.youtubeId()
                    ?.let { TrailerData("https://www.youtube.com/embed/$it", null, false) },
            ).toMutableList()
            // On a series page `.listupd` is only the "Önerilen Seriler" grid.
            this.recommendations = doc.select("div.listupd article.bs").mapNotNull { it.toSearchResult() }
        }
    }

    /** `.spe` rows read `<span><b>Etiket:</b> değer</span>`; the labels are Turkish. */
    private fun Document.info(label: String): String? =
        select(".spe span").firstOrNull { it.selectFirst("b")?.text()?.startsWith(label) == true }
            ?.text()?.substringAfter(":")?.trim()?.takeIf { it.isNotBlank() }

    /** "24 dk", "24 Dakika", "1 Saat 45 dk". */
    private fun String?.minutes(): Int? {
        val text = this ?: return null
        val hours = Regex("""(\d+)\s*Saat""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val minutes = Regex("""(\d+)\s*(?:dk|Dakika)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return (hours * 60 + minutes).takeIf { it > 0 }
    }

    /** The trailer button links a plain YouTube watch page. */
    private fun String.youtubeId(): String? =
        Regex("""(?:[?&]v=|youtu\.be/|/embed/)([\w-]{11})""").find(this)?.groupValues?.get(1)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val linkCallback: (ExtractorLink) -> Unit = { if (Settings.qualityAllowed(it.quality)) callback(it) }
        val subtitleFilter: (SubtitleFile) -> Unit = { if (Settings.subtitleAllowed(it.lang)) subtitleCallback(it) }
        val linkSeen = mutableSetOf<String>()
        val emit: (ExtractorLink) -> Unit = { if (linkSeen.add(it.url)) linkCallback(it) }
        val doc = runCatching { app.get(data).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: kaynak bulunamadı. $REPORT_HINT")
        val embeds = (
            doc.select("#pembed iframe[src]").map { it.attr("src") } +
                doc.select("select.mirror option[value]").mapNotNull { decodeMirror(it.attr("value")) }
            ).mapNotNull { fixUrlNull(it) }.distinct()

        var found = false
        embeds.forEach { embed ->
            // A dead mirror must not take the other ones down with it.
            val ok = runCatching {
                if (embed.contains("rumble.com/embed")) {
                    loadRumble(embed, emit)
                } else {
                    loadExtractor(embed, "$mainUrl/", subtitleFilter, emit)
                }
            }.getOrDefault(false)
            if (ok) found = true
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir akış yok. $REPORT_HINT")
        return found
    }

    /** Mirror entries are base64-encoded `<iframe>` tags: `<option value="PGlam...">Sibnet</option>`. */
    private fun decodeMirror(value: String): String? = runCatching {
        val html = String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8)
        Regex("""(?i)<iframe[^>]*\bsrc\s*=\s*["']?([^"'\s>]+)""").find(html)?.groupValues?.get(1)
    }.getOrNull()

    /**
     * The embed page carries the whole player config inline:
     * `m.f["v7fbwew"]={"u":{"hls":{"url":"https:\/\/rumble.com\/hls-vod\/<slug>\/playlist.m3u8"}}}`.
     * That playlist is a plain HLS master and answers without a referer.
     */
    private suspend fun loadRumble(embed: String, callback: (ExtractorLink) -> Unit): Boolean {
        val id = Regex("""rumble\.com/embed/([^/?"]+)""").find(embed)?.groupValues?.get(1) ?: return false
        val body = app.get("https://rumble.com/embed/$id/").text
        val stream = body.substringAfter("\"hls\":{\"url\":\"", "").substringBefore('"')
            .replace("\\/", "/")
        if (stream.isBlank()) return false
        callback.invoke(
            newExtractorLink(name, "Rumble", stream, ExtractorLinkType.M3U8) {
                this.referer = "https://rumble.com/"
            }
        )
        return true
    }

    companion object {
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}
