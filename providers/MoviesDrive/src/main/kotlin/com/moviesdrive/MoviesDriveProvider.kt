package com.moviesdrive

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * MoviesDrive — moviesdrives.bid (mirrors: new*.moviesdrive.beer, moviesdrives.cfd).
 * Search is a Meilisearch proxy at /search.php, post pages link to per-quality "archive"
 * posts on mdrive.lol which carry the hubcloud file, resolved to direct R2/Pixeldrain links.
 */
class MoviesDriveProvider : MainAPI() {
    override var mainUrl = MIRRORS.first()
    override var name = "MoviesDrive"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie)

    private var resolvedBase: String? = null

    /** The site rotates through new*.moviesdrive.beer; keep the first mirror that serves search. */
    private suspend fun baseUrl(): String {
        resolvedBase?.let { return it }
        val live = MIRRORS.firstOrNull { mirror ->
            runCatching { app.get("$mirror/search.php?q=a&page=1", headers = headers).text }
                .getOrNull()?.trimStart()?.startsWith("{") == true
        }
        return (live ?: mainUrl).also {
            resolvedBase = it
            mainUrl = it
        }
    }

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    private val headers = mapOf(
        "User-Agent" to ua,
        "Accept-Language" to "en-US,en;q=0.9,hi;q=0.8",
    )

    override val mainPage = mainPageOf(
        "latest" to "Latest Releases",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("${baseUrl()}/page/$page/", headers = headers).document
        val items = doc.select("div.movies-grid a[href]").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val title = selectFirst("p.poster-title")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        return newMovieSearchResponse(title, href, TvType.Movie) {
            posterUrl = fixUrlNull(selectFirst("img")?.attr("src"))
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val body = runCatching {
            app.get("${baseUrl()}/search.php?q=$encoded&page=1", headers = headers).text
        }.getOrNull() ?: return emptyList()
        val hits = runCatching { JSONObject(body).optJSONArray("hits") }.getOrNull()
            ?: return emptyList()
        return (0 until hits.length()).mapNotNull { index ->
            val doc = hits.optJSONObject(index)?.optJSONObject("document") ?: return@mapNotNull null
            val href = fixUrlNull(doc.optString("permalink")) ?: return@mapNotNull null
            val title = doc.optString("post_title").trim().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = doc.optString("post_thumbnail").takeIf { it.isNotBlank() }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url, headers = headers).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: page did not load. $REPORT_HINT")
        val title = doc.selectFirst("h1.post-title")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: title not found. $REPORT_HINT")
        val content = doc.selectFirst("article.post-content")

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = fixUrlNull(content?.selectFirst("img[src*=tmdb]")?.attr("src"))
            this.year = Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
            this.plot = content?.select("h3")
                ?.firstOrNull { it.text().contains("Storyline", true) }
                ?.nextElementSibling()?.text()?.trim()
            this.tags = content?.select("a.category-tag")?.map { it.text().trim() } ?: emptyList()
            this.score = content?.text()
                ?.let { Regex("""iMDB Rating:\s*([\d.]+)""").find(it)?.groupValues?.get(1) }
                ?.let { Score.from10(it) }
            this.actors = Regex("""Stars:\s*([^|]+)""")
                .find(content?.text().orEmpty())?.groupValues?.get(1)
                ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
                ?.map { ActorData(Actor(it)) } ?: emptyList()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val seen = mutableSetOf<String>()
        val emit: (ExtractorLink) -> Unit = {
            if (seen.add(it.url) && Settings.qualityAllowed(it.quality)) callback(it)
        }
        val doc = runCatching { app.get(data, headers = headers).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: page did not load. $REPORT_HINT")
        val content = doc.selectFirst("article.post-content") ?: doc

        // Each download row is an <h5>/<h4> quality heading followed by the archive/hubcloud link.
        val sources = mutableListOf<Pair<String, String>>()
        var lastHeading = ""
        content.select("h5, h4, a[href]").forEach { element ->
            if (element.tagName() == "a") {
                val href = fixUrlNull(element.attr("href")) ?: return@forEach
                if (!isSourceUrl(href)) return@forEach
                val text = element.text().trim()
                sources.add((if (RESOLUTION.containsMatchIn(text)) text else lastHeading) to href)
            } else {
                val text = element.text().trim()
                if (RESOLUTION.containsMatchIn(text)) lastHeading = text
            }
        }

        var found = false
        sources.distinctBy { it.second }.forEach { (label, href) ->
            if (href.contains("/archive/")) {
                val archive = runCatching { app.get(href, referer = data, headers = headers).document }
                    .getOrNull() ?: return@forEach
                val quality = label.ifBlank { archive.selectFirst("title")?.text().orEmpty() }
                archive.select("a[href]").map { it.attr("href") }.distinct().forEach { link ->
                    when {
                        link.contains("hubcloud") ->
                            if (resolveHub(fixUrl(link), quality, href, emit)) found = true

                        link.contains("gdflix") || link.contains("only-apis") ->
                            if (runCatching {
                                    loadExtractor(
                                        fixUrl(link), href, subtitleCallback, emit
                                    )
                                }.getOrDefault(false)
                            ) found = true
                    }
                }
            } else if (href.contains("hubcloud")) {
                if (resolveHub(href, label, data, emit)) found = true
            }
        }

        if (!found) throw ErrorLoadingException("$name: no playable source. $REPORT_HINT")
        return found
    }

    /**
     * hubcloud drive page -> "Generate Direct Download Link" -> page with the direct R2 file
     * links (they answer 206 range requests, so they play as plain video links).
     */
    private suspend fun resolveHub(
        hubUrl: String,
        label: String,
        referer: String,
        emit: (ExtractorLink) -> Unit,
    ): Boolean {
        val page = runCatching { app.get(hubUrl, referer = referer, headers = headers).document }
            .getOrNull() ?: return false
        val generate = fixUrlNull(page.selectFirst("a#download")?.attr("href"))
            ?: fixUrlNull(page.selectFirst("a[href*=hubcloud.php]")?.attr("href"))
            ?: return false
        val final = runCatching {
            app.get(generate, referer = hubUrl, headers = headers).document
        }.getOrNull() ?: return false

        var resolved = false
        // Only the R2 links are per-file; the page's pixeldrain /u/ id is a shared, dead decoy.
        final.select("a[href*=\"cloudflarestorage.com\"]").forEach { anchor ->
            emit.invoke(
                newExtractorLink(name, "$name HubCloud", anchor.attr("href"), ExtractorLinkType.VIDEO) {
                    this.quality = getQualityFromName(label)
                    this.referer = hubUrl
                }
            )
            resolved = true
        }
        return resolved
    }

    private fun isSourceUrl(href: String): Boolean =
        href.contains("/archive/") || href.contains("hubcloud") ||
            href.contains("gdflix") || href.contains("only-apis")

    companion object {
        private val MIRRORS = listOf(
            "https://new1.moviesdrive.cyou",
            "https://new5.moviesdrive.beer",
            "https://new4.moviesdrive.beer",
        )
        private val RESOLUTION = Regex("""\b(\d{3,4}p|[248]K)\b""", RegexOption.IGNORE_CASE)
        private const val REPORT_HINT =
            "Report if it persists: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}
