package com.monoschinos

import android.util.Base64
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.net.URLEncoder

/**
 * MonosChinos — monoschinos.st
 *
 * Cards (catálogo, búsqueda) are `article > a[href*='/anime/']` blocks with the title in
 * `h3.card-title` and the poster in `img.card-img[data-src]`.
 *
 * A ficha page keeps its episodes out of the HTML: `section.caplist[data-ajax]` points at
 * `/ajax/ajax_pagination/<id>`, and the real list is `/ajax/caplist/<id>?p=<page>` (50 per
 * page) as JSON `{caps:[{episodio,url}]}`.
 *
 * The episode page lists every mirror as a base64 URL in `[data-player]`; the site's own
 * `reproductor?url=` wrapper only turns that value into an iframe, so the decoded URL goes
 * straight to the Cloudstream extractors. Audio and subtitles are burned into the mirrors,
 * the site exposes no subtitle track of its own.
 */
class MonoschinosProvider : MainAPI() {
    override var mainUrl = "https://monoschinos.st"
    override var name = "MonosChinos"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
        "Accept-Language" to "es-ES,es;q=0.9",
    )

    override val mainPage = mainPageOf("$mainUrl/animes?p=SAYFA" to "Catálogo")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data.replace("SAYFA", page.toString()), headers = headers).document
        val items = doc.select("article a[href*='/anime/']").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items, hasNext = doc.selectFirst("a[rel=next]") != null)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val doc = app.get("$mainUrl/buscar?q=$encoded", headers = headers).document
        return doc.select("article a[href*='/anime/']").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val title = selectFirst("h3.card-title")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return newTvSeriesSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = fixUrlNull(selectFirst("img.card-img")?.attr("data-src"))
            this.year = YEAR.find(text())?.value?.toIntOrNull()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url, headers = headers).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: no se pudo cargar la ficha. $REPORT_HINT")
        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: no se pudo cargar la ficha. $REPORT_HINT")

        val episodes = loadEpisodes(doc)
        if (episodes.isEmpty()) {
            throw ErrorLoadingException("$name: no se encontraron episodios. $REPORT_HINT")
        }

        val badge = doc.selectFirst("div[class*=top-3][class*=left-3]")?.text().orEmpty()
        val date = doc.selectFirst("span:matchesOwn(\\d{1,2} de .* de \\d{4})")?.text().orEmpty()
        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
            this.plot = doc.selectFirst("#tab-info p")?.text()?.trim()
            this.year = YEAR.find(date)?.value?.toIntOrNull()
            this.tags = doc.select("a[href*='/genero/']")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .distinct()
            this.showStatus = when {
                badge.contains("Finalizado", true) -> ShowStatus.Completed
                badge.contains("emisi", true) || badge.contains("Estreno", true) -> ShowStatus.Ongoing
                else -> null
            }
        }
    }

    /** `caplist` is a plain JSON endpoint, 50 episodes per page and no page count. */
    private suspend fun loadEpisodes(doc: Document): List<com.lagradost.cloudstream3.Episode> {
        val id = doc.selectFirst("section.caplist")?.attr("data-ajax")
            ?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: return emptyList()
        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        var page = 1
        while (page <= MAX_EPISODE_PAGES) {
            val body = app.get("$mainUrl/ajax/caplist/$id?p=$page", headers = headers).text
            val caps = runCatching { JSONObject(body).optJSONArray("caps") }.getOrNull()
            if (caps == null || caps.length() == 0) break
            for (i in 0 until caps.length()) {
                val cap = caps.optJSONObject(i) ?: continue
                val href = fixUrlNull(cap.optString("url")) ?: continue
                val number = cap.optInt("episodio", -1).takeIf { it >= 0 }
                episodes += newEpisode(href) {
                    this.name = number?.let { "Episodio $it" } ?: "Episodio"
                    this.season = 1
                    this.episode = number
                }
            }
            page++
        }
        return episodes.distinctBy { it.data }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val linkCallback: (ExtractorLink) -> Unit = { if (Settings.qualityAllowed(it.quality)) callback(it) }
        val subtitleFilter: (SubtitleFile) -> Unit = { if (Settings.subtitleAllowed(it.lang)) subtitleCallback(it) }
        val doc = runCatching { app.get(data, headers = headers).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: no se pudo cargar el episodio. $REPORT_HINT")

        val mirrors = doc.select("[data-player]")
            .mapNotNull { decodePlayer(it.attr("data-player")) }
            .distinct()
        if (mirrors.isEmpty()) {
            throw ErrorLoadingException("$name: el episodio no tiene servidores. $REPORT_HINT")
        }

        var found = false
        mirrors.forEach { mirror ->
            val ok = runCatching { loadExtractor(mirror, data, subtitleFilter, linkCallback) }
                .getOrDefault(false)
            if (ok) found = true
        }
        if (!found) throw ErrorLoadingException("$name: ningún servidor respondió. $REPORT_HINT")
        return found
    }

    /** Mirrors are base64 of the embed URL; the site's wrapper only iframes them. */
    private fun decodePlayer(value: String): String? {
        if (value.isBlank()) return null
        val decoded = if (value.startsWith("http")) value else runCatching {
            String(Base64.decode(value, Base64.DEFAULT)).trim()
        }.getOrNull()
        if (decoded.isNullOrBlank()) return null
        return if (decoded.startsWith("http")) decoded else fixUrlNull(decoded)
    }

    companion object {
        private const val REPORT_HINT =
            "Si el problema continúa, repórtalo en https://github.com/F0Rextasy/bigbullapp-cs/issues"
        private const val MAX_EPISODE_PAGES = 40
        private val YEAR = Regex("""\b(19\d{2}|20\d{2})\b""")
    }
}
