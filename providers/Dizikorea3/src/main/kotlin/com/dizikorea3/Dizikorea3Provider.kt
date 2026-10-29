package com.dizikorea3

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
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
import com.lagradost.cloudstream3.extractors.Vidmoly
import com.lagradost.cloudstream3.fixUrlNull
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
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Dizikorea3 — dizikorea3.com
 *
 * The site ignores `?search=`; the working full-text search is WordPress-style
 * `?s=<query>` (series and movies mixed, paged by `/sayfa/N?s=<query>`).
 * Series episodes live at `/dizi/<slug>/sezon-N/bolum-M`; movies are single
 * watch pages at `/film/<slug>`.
 */
class Dizikorea3Provider : MainAPI() {
    override var mainUrl = "https://dizikorea3.com"
    override var name = "Dizikorea3"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/dizi-arsivi/sayfa/SAYFA" to "Tüm Diziler",
        "$mainUrl/filmler/sayfa/SAYFA" to "Filmler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data.replace("SAYFA", page.toString())).document
        val items = doc.select("section.listing-page a.poster-card[href]")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return newHomePageResponse(request, items, hasNext = hasNextPage(doc))
    }

    /** Pager renders "Sayfa 2 / 8"; missing when the listing has a single page. */
    private fun hasNextPage(doc: Document): Boolean {
        val groups = Regex("""(\d+)\s*/\s*(\d+)""")
            .find(doc.selectFirst(".page-info")?.text() ?: return false)?.groupValues ?: return false
        return groups[1].toInt() < groups[2].toInt()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val isMovie = href.contains("/film/")
        if (!isMovie && !href.contains("/dizi/")) return null
        val title = selectFirst(".poster-card-title")?.text()?.trim()
            ?: selectFirst("img")?.attr("alt")?.trim()
        if (title.isNullOrBlank()) return null
        val poster = fixUrlNull(selectFirst("img")?.attr("src"))
        return if (isMovie) {
            newMovieSearchResponse(title, href) { this.posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, href) { this.posterUrl = poster }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> =
        search(query, 1)?.items ?: emptyList()

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        // `?s=<q>&paged=N` is ignored by the site; paging is the `/sayfa/N` path.
        val doc = app.get("$mainUrl/sayfa/$page?s=${URLEncoder.encode(query, "UTF-8")}").document
        val items = doc.select("section.listing-page a.poster-card[href]")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return newSearchResponseList(items, hasNextPage(doc))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        val items = JSONObject(app.get("$mainUrl/ara?q=${URLEncoder.encode(query, "UTF-8")}").text)
            .optJSONArray("items") ?: return null
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val href = item.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = item.optString("title").trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val poster = item.optString("poster").takeIf { it.isNotBlank() }
            if (item.optString("type") == "movie") {
                newMovieSearchResponse(title, href) { this.posterUrl = poster }
            } else {
                newTvSeriesSearchResponse(title, href) { this.posterUrl = poster }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val title = doc.selectFirst("h1.series-title, h1.watch-title")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = doc.selectFirst(".series-about-text")?.text()?.trim()
        val meta = doc.selectFirst(".series-meta, .watch-ep-date")?.text().orEmpty()
        val year = Regex("""\b(19|20)\d{2}\b""").find(meta)?.value?.toIntOrNull()
        val tags = doc.select("a[href*='/tur/']").map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct()
        val score = Score.from10(doc.selectFirst(".meta-rating")?.text()?.replace("★", "")?.trim())
        val duration = Regex("""(\d+)\s*dk""").find(doc.selectFirst(".watch-meta-row")?.text().orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()
        val actors = doc.select("a.cast-card").mapNotNull { card ->
            val name = card.selectFirst(".cast-name")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            ActorData(Actor(name, fixUrlNull(card.selectFirst("img")?.attr("src"))))
        }
        val trailer = doc.selectFirst("button.btn-trailer[data-trailer]")?.attr("data-trailer")?.trim()
            ?.takeIf { it.isNotBlank() }
        val related = doc.selectFirst("h2:contains(Benzer)")
            ?.parents()?.firstOrNull { it.tagName() == "section" }
            ?.select("a.poster-card[href]")?.mapNotNull { it.toSearchResult() }
            ?.distinctBy { it.url }.orEmpty()

        if (url.contains("/film/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = score
                this.duration = duration
                this.actors = actors
                this.recommendations = related
                if (trailer != null) addTrailer(trailer)
            }
        }

        val episodes = doc.select("div.episode-list[data-season]").flatMap { list ->
            val season = list.attr("data-season").toIntOrNull()
            list.select("a.episode-item[href]").mapNotNull { anchor ->
                val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
                newEpisode(href) {
                    this.name = anchor.selectFirst(".ep-title")?.text()?.trim() ?: "Bölüm"
                    this.season = season
                    this.episode = anchor.selectFirst(".ep-number")?.text()?.trim()?.toIntOrNull()
                }
            }
        }.distinctBy { it.data }

        if (episodes.isEmpty()) throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.score = score
            this.duration = duration
            this.actors = actors
            this.recommendations = related
            if (trailer != null) addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val seenLinks = mutableSetOf<String>()
        val seenSubs = mutableSetOf<String>()
        val linkCallback: (ExtractorLink) -> Unit = {
            if (seenLinks.add(it.url) && Settings.qualityAllowed(it.quality)) callback(it)
        }
        val subtitleFilter: (SubtitleFile) -> Unit = {
            if (seenSubs.add(it.url) && Settings.subtitleAllowed(it.lang)) subtitleCallback(it)
        }
        val doc = runCatching { app.get(data).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: kaynak bulunamadı. $REPORT_HINT")
        // The player iframes are lazy-loaded, so the real URL sits in data-src.
        val embeds = doc.select("iframe[data-src], iframe[src]")
            .mapNotNull { iframe ->
                fixUrlNull(iframe.attr("data-src").ifBlank { iframe.attr("src") }.trim())
            }
            .filter { it.startsWith("http") }
            .distinct()

        var found = false
        embeds.forEach { embed ->
            val ok = if (embed.contains("playerdkorea") || embed.contains("playerkorea")) {
                loadPlayerDkorea(embed, linkCallback)
            } else {
                loadExtractor(embed, "$mainUrl/", subtitleFilter, linkCallback)
            }
            if (ok) found = true
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir akış yok. $REPORT_HINT")
        return found
    }

    /**
     * playerdkorea/FirePlayer (no core extractor): POST `player/index.php?do=getVideo`
     * answers JSON with `securedLink`/`videoSource`. The CDN returns 404 for cold
     * tokens, so a stream is emitted only after the playlist itself answers `#EXTM3U`.
     */
    private suspend fun loadPlayerDkorea(embed: String, callback: (ExtractorLink) -> Unit): Boolean {
        val id = embed.substringAfterLast('/')
        val json = runCatching {
            app.post(
                embed.replace("/video/$id", "/player/index.php?data=$id&do=getVideo"),
                data = mapOf("hash" to id, "r" to "$mainUrl/"),
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to "$mainUrl/",
                    "Origin" to "https://playerdkorea.xyz",
                ),
            ).text
        }.getOrNull() ?: return false
        val body = runCatching { JSONObject(json) }.getOrNull() ?: return false

        var found = false
        for (key in listOf("securedLink", "videoSource")) {
            val stream = body.optString(key)
            if (!stream.startsWith("http")) continue
            val live = runCatching {
                app.get(stream, referer = "https://playerdkorea.xyz/").text.startsWith("#EXTM3U")
            }.getOrDefault(false)
            if (!live) continue
            callback.invoke(
                newExtractorLink(name, "playerDKorea", stream, ExtractorLinkType.M3U8) {
                    this.referer = "https://playerdkorea.xyz/"
                    this.quality = Qualities.Unknown.value
                }
            )
            found = true
        }
        return found
    }

    companion object {
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}

/** Core ships vidmoly .to/.me/.biz/.net; dizikorea3 also serves the .org mirror. */
class VidmolyOrg : Vidmoly() {
    override val mainUrl = "https://vidmoly.org"
}
