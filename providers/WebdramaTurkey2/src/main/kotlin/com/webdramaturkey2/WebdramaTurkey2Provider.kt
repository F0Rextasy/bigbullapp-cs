package com.webdramaturkey2

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
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
import java.net.URI
import java.net.URLEncoder

/**
 * WebdramaTurkey2 — webdramaturkey2.com (Korean/Chinese/Japanese dramas and films, Turkish subs).
 *
 * Search ignores `?s=`; the query travels as a path segment on `/arama/<query>` (Turkish letters
 * and spaces URL-encoded). Results are tabbed: series/movies/shows/animes/actors.
 *
 * Player chain: `button[data-embed]` ids on an episode/film page resolve through
 * `POST /ajax/embed` into a `video.php?hash=…` wrapper that carries the real player in
 * `#main-iframe`. The site's own source is FirePlayer: `<host>/video/<id>` answers
 * `/player/index.php?data=<id>&do=getVideo` with a signed HLS master. Everything else
 * (VK, vidmoly, …) is handed to the core extractors.
 */
class WebdramaTurkey2Provider : MainAPI() {
    override var mainUrl = "https://webdramaturkey2.com"
    override var name = "WebdramaTurkey2"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    private val browserHeaders = mapOf(
        "User-Agent" to UA,
        "Accept-Language" to "tr-TR,tr;q=0.9",
    )

    override val mainPage get() = ROWS.mapNotNull { name ->
        if (!Settings.rowEnabled(name)) return@mapNotNull null
        val data = when (name) {
            "Son Bölümler" -> mainUrl
            "Diziler" -> "$mainUrl/diziler?page=SAYFA"
            "Filmler" -> "$mainUrl/filmler?page=SAYFA"
            "Animeler" -> "$mainUrl/animeler?page=SAYFA"
            "Kore Dizileri" -> countryUrl(1)
            "Çin Dizileri" -> countryUrl(2)
            "Japon Dizileri" -> countryUrl(3)
            else -> return@mapNotNull null
        }
        MainPageData(name, data)
    }

    /** The country filter rides along as JSON: 1 Korea, 2 China, 3 Japan. */
    private fun countryUrl(country: Int): String {
        val filter = URLEncoder.encode("""{"country":"$country","sorting":"newest"}""", "UTF-8")
        return "$mainUrl/diziler?filter=$filter&page=SAYFA"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // "Son Bölümler" is a single page; every other row paginates through ?page=N.
        if (!request.data.contains("SAYFA")) {
            val doc = fetch(request.data)
            val items = doc.select("div.col.sonyuklemeler")
                .mapNotNull { it.toLatestEpisode() }
                .distinctBy { it.url }
            return newHomePageResponse(request.name, items, hasNext = false)
        }

        val doc = fetch(request.data.replace("SAYFA", page.toString()))
        val items = doc.select("div.list-movie").mapNotNull { it.toSearchItem() }.distinctBy { it.url }
        val hasNext = doc.select("ul.pagination a[href]").any { link ->
            link.attr("href").substringAfter("page=", "").substringBefore('&').toIntOrNull() == page + 1
        }
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = fetch("$mainUrl/arama/${URLEncoder.encode(query, "UTF-8")}")
        // The #actors pane answers actor credits; only the content panes are read.
        return doc.select("div.tab-pane div.list-movie")
            .mapNotNull { it.toSearchItem() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = url.replace(EPISODE_SUFFIX, "")
        val doc = fetch(pageUrl)
        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("$name: içerik bilgisi okunamadı. $REPORT_HINT")
        val fields = doc.detailFields()
        val posterUrl = fixUrlNull(doc.selectFirst("div.app-detail div.media.media-cover")?.attr("data-src"))
        val plot = fields["Genel Bakış"]
        val year = fields["Yayın yılı"]?.take(4)?.toIntOrNull()
        // Series pages use `div.categories`, film pages `div.category` — both live under app-detail.
        val tags = doc.select("div.app-detail a[href*='/tur/']").map { it.text().trim() }
            .filter { it.isNotEmpty() }.distinct()
        val actors = doc.select("#actorsList a[href*='/oyuncu/']").mapNotNull { anchor ->
            val actorName = anchor.selectFirst("div.list-title")?.text()?.trim().orEmpty()
            if (actorName.isEmpty()) return@mapNotNull null
            Actor(actorName, fixUrlNull(anchor.selectFirst("div.media")?.attr("data-src")))
        }

        if (pageUrl.contains("/film/")) {
            return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.duration = parseDuration(fields["Süre"])
                this.tags = tags
                if (actors.isNotEmpty()) addActors(actors)
            }
        }

        val episodes = doc.select("div.season-list a[href*='-sezon/'][href*='-bolum']").mapNotNull { anchor ->
            val episodeUrl = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            newEpisode(episodeUrl) {
                this.name = anchor.selectFirst("div.episode")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "Bölüm"
                this.season = SEASON.find(episodeUrl)?.groupValues?.get(1)?.toIntOrNull()
                this.episode = EPISODE.find(episodeUrl)?.groupValues?.get(1)?.toIntOrNull()
            }
        }.distinctBy { it.data }
        if (episodes.isEmpty()) {
            throw ErrorLoadingException("$name: bölüm listesi bulunamadı. $REPORT_HINT")
        }

        val type = if (pageUrl.contains("/anime/")) TvType.Anime else TvType.TvSeries
        return newTvSeriesLoadResponse(title, pageUrl, type, episodes) {
            this.posterUrl = posterUrl
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
        // Collect first so a quality cap that drops every variant still leaves the lowest one.
        val collected = mutableListOf<ExtractorLink>()
        val linkCallback: (ExtractorLink) -> Unit = { collected += it }
        val subtitleFilter: (SubtitleFile) -> Unit = { if (Settings.subtitleAllowed(it.lang)) subtitleCallback(it) }
        val doc = fetch(data)

        val sources = doc.select("button[data-embed]").mapNotNull { button ->
            val id = button.attr("data-embed").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            id to (button.selectFirst("span.name")?.text()?.trim().orEmpty().ifEmpty { "Kaynak" })
        }.distinctBy { it.first }
        if (sources.isEmpty()) {
            throw ErrorLoadingException("$name: oynatıcı kaynağı bulunamadı. $REPORT_HINT")
        }

        var found = false
        for ((id, label) in sources) {
            val embedHtml = runCatching {
                app.post(
                    "$mainUrl/ajax/embed",
                    data = mapOf("id" to id),
                    headers = browserHeaders + mapOf("X-Requested-With" to "XMLHttpRequest"),
                    referer = data,
                ).text
            }.getOrNull() ?: continue
            val wrapper = IFRAME_SRC.find(embedHtml)?.groupValues?.get(1) ?: continue

            // The video.php wrapper hides the real player in #main-iframe.
            val target = if (wrapper.contains("/video.php")) {
                runCatching { app.get(wrapper, referer = data, headers = browserHeaders).text }
                    .getOrNull()
                    ?.let { MAIN_IFRAME_SRC.find(it)?.groupValues?.get(1) }
            } else {
                wrapper
            }
            if (target.isNullOrEmpty()) continue

            if (emitPlayer(target, label, data, linkCallback, subtitleFilter)) found = true
            // shortcut: stop at the first working source — Cloudflare rate-limits /ajax/embed
            // (error 1015); drop this break only if every mirror has to be listed.
            if (found) break
        }

        val allowed = collected.filter { Settings.qualityAllowed(it.quality) }
        val chosen = allowed.ifEmpty {
            listOfNotNull(collected.minByOrNull { it.quality })
        }
        if (chosen.isEmpty()) throw ErrorLoadingException("$name: oynatılabilir kaynak yok. $REPORT_HINT")
        chosen.forEach(callback)
        return true
    }

    /** Resolves FirePlayer sources in-house and delegates every other host to the core extractors. */
    private suspend fun emitPlayer(
        target: String,
        label: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit,
    ): Boolean {
        val firePlayer = FIREPLAYER.find(target)
        if (firePlayer != null) {
            val origin = firePlayer.groupValues[1]
            val videoId = firePlayer.groupValues[2]
            val body = runCatching {
                app.post(
                    "$origin/player/index.php?data=$videoId&do=getVideo",
                    data = mapOf("hash" to videoId, "r" to referer),
                    headers = browserHeaders + mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Origin" to origin,
                    ),
                    referer = target,
                ).text
            }.getOrNull()
            val panel = body?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (panel != null) {
                val stream = panel.optString("securedLink").ifEmpty { panel.optString("videoSource") }
                if (stream.isNotEmpty()) {
                    return if (panel.optBoolean("hls") || stream.contains(".m3u8", true)) {
                        emitHls(stream, label, origin, callback)
                    } else {
                        callback.invoke(
                            newExtractorLink(name, label, stream, ExtractorLinkType.VIDEO) {
                                this.referer = origin
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        true
                    }
                }
            }
        }
        return runCatching { loadExtractor(target, referer, subtitleCallback, callback) }
            .getOrDefault(false)
    }

    /** Splits a master m3u8 into variants; a single-part playlist is served as-is with Unknown quality. */
    private suspend fun emitHls(
        masterUrl: String,
        label: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val playlist = runCatching { app.get(masterUrl, referer = referer, headers = browserHeaders).text }
            .getOrNull() ?: return false
        val variants = VARIANT.findAll(playlist).mapNotNull { match ->
            val height = RESOLUTION.find(match.groupValues[1])?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val uri = match.groupValues[2].trim()
            if (uri.isEmpty()) return@mapNotNull null
            // Variant URIs are root-relative and resolve against the master's origin.
            runCatching { URI(masterUrl).resolve(uri).toString() }.getOrNull()?.let { it to height }
        }.distinctBy { it.first }.toList()

        if (variants.isEmpty()) {
            callback.invoke(
                newExtractorLink(name, label, masterUrl, ExtractorLinkType.M3U8) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }

        for ((variantUrl, height) in variants) {
            callback.invoke(
                newExtractorLink(name, if (height > 0) "$label ${height}p" else label, variantUrl, ExtractorLinkType.M3U8) {
                    this.referer = referer
                    this.quality = height
                }
            )
        }
        return true
    }

    private suspend fun fetch(url: String): Document = runCatching {
        app.get(url, headers = browserHeaders).document
    }.getOrNull() ?: throw ErrorLoadingException("$name: sayfa yüklenemedi. $REPORT_HINT")

    /** Listing / search-tab card: `div.list-movie`. */
    private fun Element.toSearchItem(): SearchResponse? {
        val anchor = selectFirst("a.list-title") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        val title = anchor.text().trim().ifEmpty { anchor.attr("title").trim() }
        if (title.isEmpty()) return null
        return searchResult(title, href, fixUrlNull(selectFirst("div.media[data-src]")?.attr("data-src")))
    }

    /** Home page "Son Bölümler" card; the link points at the episode, so it is cut back to the series root. */
    private fun Element.toLatestEpisode(): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val href = fixUrlNull(anchor.attr("href").replace(EPISODE_SUFFIX, ""))
            ?.takeIf { it.isNotEmpty() } ?: return null
        val title = selectFirst("div.list-title")?.text()?.trim().orEmpty()
        if (title.isEmpty()) return null
        val label = selectFirst("div.list-category")?.text()?.trim().orEmpty()
        val name = if (label.isEmpty()) title else "$title · $label"
        return searchResult(name, href, fixUrlNull(selectFirst("div.media[data-src]")?.attr("data-src")))
    }

    private fun searchResult(title: String, url: String, posterUrl: String?): SearchResponse = when {
        url.contains("/film/") -> newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = posterUrl
        }

        url.contains("/anime/") -> newAnimeSearchResponse(title, url, TvType.Anime) {
            this.posterUrl = posterUrl
        }

        else -> newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    /** Label → value map from the `featured-attr` / `video-attr` / `detail-attr` blocks. */
    private fun Document.detailFields(): Map<String, String> {
        val fields = linkedMapOf<String, String>()
        for (block in select("div.featured-attr, div.video-attr, div.detail-attr")) {
            val label = block.selectFirst("div.attr")?.text()?.trim().orEmpty()
            val value = block.selectFirst("div.text")?.text()?.trim().orEmpty()
            if (label.isEmpty() || value.isEmpty() || fields.containsKey(label)) continue
            fields[label] = value
        }
        return fields
    }

    /** "2 Saat 37 Dakika" → 157. */
    private fun parseDuration(value: String?): Int? {
        if (value.isNullOrBlank()) return null
        val hours = Regex("""(\d+)\s*Saat""").find(value)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val minutes = Regex("""(\d+)\s*Dakika""").find(value)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return (hours * 60 + minutes).takeIf { it > 0 }
    }

    companion object {
        /** Homepage rows in display order. */
        val ROWS = listOf(
            "Son Bölümler",
            "Diziler",
            "Filmler",
            "Animeler",
            "Kore Dizileri",
            "Çin Dizileri",
            "Japon Dizileri",
        )
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"

        private val EPISODE_SUFFIX = Regex("""/\d+-sezon/\d+-bolum/?$""")
        private val SEASON = Regex("""/(\d+)-sezon/""")
        private val EPISODE = Regex("""/(\d+)-bolum""")
        private val IFRAME_SRC = Regex("""<iframe[^>]*\ssrc=["']([^"']+)["']""")
        private val MAIN_IFRAME_SRC = Regex("""id=["']main-iframe["'][^>]*\ssrc=["']([^"']+)["']""")
        private val FIREPLAYER = Regex("""^(https?://[^/]+)/(?:video|embed|e)/([0-9a-zA-Z]{16,})""")
        private val VARIANT = Regex("""#EXT-X-STREAM-INF:([^\r\n]*)\r?\n([^\r\n#]+)""")
        private val RESOLUTION = Regex("""RESOLUTION=\d+x(\d+)""")
    }
}
