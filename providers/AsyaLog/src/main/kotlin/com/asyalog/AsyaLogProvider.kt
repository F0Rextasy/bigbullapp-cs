package com.asyalog

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

/** AsyaLog — asyalog.co (Asya dizileri/filmleri, Odnoklassniki player) */
private fun Element.posterSrcLazy(): String? =
    attr("data-src").takeIf { it.isNotBlank() } ?: attr("src")

class AsyaLogProvider : MainAPI() {
    override var mainUrl = "https://asyalog.co"
    override var name = "AsyaLog"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage get() = ROWS.mapNotNull { name ->
        if (!Settings.rowEnabled(name)) return@mapNotNull null
        when (name) {
            "Son Eklenenler" -> MainPageData(name, "$mainUrl/")
            "Asya Filmleri" -> MainPageData(name, "$mainUrl/asya-filmleri/")
            "Aksiyon" -> MainPageData(name, "$mainUrl/diziler/aksiyon/")
            "Çin Dizileri" -> MainPageData(name, "$mainUrl/diziler/cin-dizileri/")
            "Devam Eden Diziler" -> MainPageData(name, "$mainUrl/devam-eden-diziler/")
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            request.data
        } else {
            request.data.trimEnd('/') + "/page/$page/"
        }
        val doc = app.get(url).document
        val items = doc.select("a.resim[href]").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        if (!href.contains("/dizi/")) return null
        val title = attr("title").ifBlank { selectFirst("img")?.attr("alt").orEmpty() }.trim()
        if (title.isBlank()) return null
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = fixUrlNull(selectFirst("img")?.posterSrcLazy())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> =
        search(query, 1)?.items ?: emptyList()

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val doc = app.get("$mainUrl/", params = mapOf("s" to query, "paged" to "$page")).document
        val items = doc.select("a.resim[href]").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newSearchResponseList(items, doc.selectFirst(".sayfalama a[href*='/page/${page + 1}/']") != null)
    }

    /** Header live search (ajax_suggest.js) — POST answers an HTML fragment, no full page. */
    override suspend fun quickSearch(query: String): List<SearchResponse> =
        app.post("$mainUrl/index.php?ajax_inc=1&aislem=suggest", data = mapOf("aramakelime" to query))
            .document.select("a[href*='/dizi/']")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("title")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val episodes = doc.select("a[href*='/bolum/']").mapNotNull { anchor ->
            val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            val label = anchor.text().trim().ifBlank { anchor.attr("title").trim() }
            if (label.isBlank()) return@mapNotNull null
            newEpisode(href) {
                this.name = label
                this.episode = Regex("""(\d+)\s*[.\-]""").find(label)?.groupValues?.get(1)?.toIntOrNull()
            }
        }.distinctBy { it.data }

        if (episodes.isEmpty()) throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")

        // "BENZER DİZİLER" sidebar block (series pages only).
        val recommendations = doc.select("div.sag-vliste a[href]").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        // "Oyuncular" tab: ul.oync; image src has escaped slashes that 404 as-is.
        val actors = doc.select("ul.oync li.oyuncu-k a").mapNotNull { anchor ->
            val actorName = anchor.attr("title").ifBlank { anchor.selectFirst("span.isim")?.text().orEmpty() }.trim()
            if (actorName.isBlank()) return@mapNotNull null
            ActorData(Actor(actorName, fixUrlNull(anchor.selectFirst("img")?.attr("src")?.replace("\\", ""))))
        }
        val duration = Regex("""Bölüm Süresi:\s*(\d+)""")
            .find(doc.selectFirst("ul.detay")?.text().orEmpty())?.groupValues?.get(1)?.toIntOrNull()
        val score = Regex("""(?:İMDb|IMDb)\s*:\s*([\d.]+)""")
            .find(doc.selectFirst("div.ssag h2")?.text().orEmpty())?.groupValues?.get(1)

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
            this.plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
            this.tags = doc.select("a[href*='/tur/'], a[href*='/kategori/']").map { it.text() }.distinct().take(8)
            this.recommendations = recommendations
            this.actors = actors.ifEmpty { null }
            this.duration = duration
            this.score = Score.from10(score)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val subtitleFilter: (SubtitleFile) -> Unit = { if (Settings.subtitleAllowed(it.lang)) subtitleCallback(it) }
        val seen = mutableSetOf<String>()
        val linkCallback: (ExtractorLink) -> Unit = {
            if (seen.add(it.url) && Settings.qualityAllowed(it.quality)) callback(it)
        }
        val doc = runCatching { app.get(data).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: kaynak bulunamadı. $REPORT_HINT")
        // Split episodes list their parts as separate pages (`.../bolum/x/2/`), each with its
        // own ok.ru video; only the page being opened carries one iframe.
        val partPages = doc.select("ul#f_part a.partsec[href]")
            .mapNotNull { fixUrlNull(it.attr("href")) }
        val iframes = (listOf(data) + partPages).distinct().flatMap { page ->
            val pageDoc = if (page == data) doc else runCatching { app.get(page).document }.getOrNull()
            pageDoc?.select("iframe[src]")?.map { fixEmbed(it.attr("src")) } ?: emptyList()
        }.distinct()
        var found = false
        iframes.forEach { embed ->
            val ok = if (embed.contains("odnoklassniki") || embed.contains("ok.ru")) {
                loadOdnoklassniki(embed, linkCallback)
            } else {
                loadExtractor(embed, "$mainUrl/", subtitleFilter, linkCallback)
            }
            if (ok) found = true
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir akış yok. $REPORT_HINT")
        return found
    }

    private fun fixEmbed(src: String): String = when {
        src.startsWith("//") -> "https:$src"
        src.startsWith("/") -> fixUrl(src)
        else -> src
    }

    /**
     * ok.ru/odnoklassniki embeds escape JSON as HTML entities, so the core
     * extractor regex misses it. Unescape first, then read the `videos` array.
     */
    private suspend fun loadOdnoklassniki(
        embed: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val body = app.get(embed, referer = "$mainUrl/").text
            .replace("&quot;", "\"")
            .replace("\\\\", "\\")
        val videos = Regex(""""videos"\s*:\s*(\[.*?])""", RegexOption.DOT_MATCHES_ALL)
            .find(body)?.groupValues?.get(1) ?: return false

        var found = false
        Regex(""""name"\s*:\s*"([^"]+)"\s*,\s*"url"\s*:\s*"([^"]+)"""")
            .findAll(videos)
            .forEach { match ->
                val label = match.groupValues[1]
                val stream = match.groupValues[2]
                    .replace("\\u0026", "&")
                    .replace("\\/", "/")
                    .let { if (it.startsWith("//")) "https:$it" else it }
                callback.invoke(
                    newExtractorLink(name, "Odnoklassniki ($label)", stream, INFER_TYPE) {
                        this.referer = "https://odnoklassniki.ru/"
                        this.quality = getQualityFromName(label.uppercase())
                    }
                )
                found = true
            }
        return found
    }

    companion object {
        /** Homepage row labels, in display order; each maps to a live category page above. */
        val ROWS = listOf(
            "Son Eklenenler",
            "Asya Filmleri",
            "Aksiyon",
            "Çin Dizileri",
            "Devam Eden Diziler",
        )
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}