package com.filmmakinesi

import com.bigbull.common.browserHeaders
import com.bigbull.common.posterSrcLazy
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
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * FilmMakinesi — filmmakinesi.co (WordPress "movifox" teması). Liste kartları `a.poster`,
 * arama `/?s=`, sayfalama `/page/N`. Detaydaki `oynatloload.top/embed/<id>` iframe'i
 * `__ULTRA_EAUTH` JWT'sini (cookie'si de aynı) taşır; `/api/video-bilgi/<id>?eauth=` imzalı
 * HLS master + kalite varyantları + VTT altyazıları döner. Alternatif detay sayfaları
 * (`/<slug>/2/`) vidmoly gibi ikinci bir kaynak ekler.
 */
class FilmMakinesiProvider : MainAPI() {
    override var mainUrl = "https://filmmakinesi.co"
    private val uaHeaders get() = browserHeaders
    override var name = "FilmMakinesi"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage get() = ROW_BASES.keys.mapNotNull { row ->
        if (!Settings.rowEnabled(row)) return@mapNotNull null
        MainPageData(row, "$mainUrl${ROW_BASES.getValue(row)}")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = ROW_BASES[request.name] ?: request.data.removePrefix(mainUrl)
        val url = if (page <= 1) "$mainUrl$base/" else "$mainUrl$base/page/$page/"
        val doc = runCatching { app.get(url, headers = uaHeaders).document }.getOrNull()
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val items = doc.select(CARD).mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val title = selectFirst("strong.poster-title")?.text()?.trim().orEmpty()
            .ifBlank { attr("title").trim() }
        if (title.isBlank()) return null
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = fixUrlNull(selectFirst("img.wp-post-image")?.posterSrcLazy())
            this.posterHeaders = uaHeaders
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/", headers = uaHeaders, params = mapOf("s" to query.trim())).document
        return doc.select(CARD).mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url, headers = uaHeaders).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")

        val title = cleanTitle(doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty())
            .ifBlank { cleanTitle(doc.selectFirst("h1")?.text().orEmpty()) }
            .ifBlank { throw ErrorLoadingException("$name: başlık bulunamadı. $REPORT_HINT") }
        val year = doc.selectFirst(".post-info-year-country a[href*=/yil/]")?.text()?.trim()?.toIntOrNull()
            ?: YEAR.find(doc.selectFirst("h1")?.text().orEmpty())?.groupValues?.get(1)?.toIntOrNull()
        val poster = doc.selectFirst(".post-info-poster img")?.posterSrcLazy()
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst(".post-info-content")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val duration = MINUTES.find(doc.selectFirst(".post-info-duration")?.text().orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()
        val tags = doc.select(".post-info-cats a").map { it.text().trim() }.filter { it.isNotBlank() }
        val score = Score.from10(doc.selectFirst(".post-info-imdb-rating span")?.text()?.trim())

        // Oynatıcı iframe'leri yalnızca bu sayfada; alternatifler loadLinks'te taranır.
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = plot
            this.year = year
            this.duration = duration
            this.tags = tags
            this.score = score
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val linkCallback: (ExtractorLink) -> Unit = { if (Settings.qualityAllowed(it.quality)) callback(it) }
        val subtitleFilter: (SubtitleFile) -> Unit = { if (Settings.subtitleAllowed(it.lang)) subtitleCallback(it) }
        val pageUrl = data.substringBefore("|")
        val doc = runCatching { app.get(pageUrl, headers = uaHeaders).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: kaynak bulunamadı. $REPORT_HINT")

        val embeds = LinkedHashSet<String>()
        fun collect(page: Document) {
            page.select(".player-container iframe[src]").forEach { frame ->
                val src = frame.attr("src").trim()
                if (src.isNotBlank()) embeds.add(src)
            }
        }
        collect(doc)
        doc.select("a.alternative-link[href]")
            .map { fixUrl(it.attr("href")) }
            .filter { it.trimEnd('/') != pageUrl.trimEnd('/') }
            .take(MAX_ALTERNATES)
            .forEach { alt ->
                runCatching { app.get(alt, headers = uaHeaders).document }.getOrNull()?.let(::collect)
            }

        var found = false
        for (embed in embeds) {
            val ok = when {
                embed.contains("oynatloload.") -> resolveEmbed(embed, pageUrl, subtitleFilter, linkCallback)
                else -> loadExtractor(embed, pageUrl, subtitleFilter, linkCallback)
            }
            if (ok) found = true
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir akış yok. $REPORT_HINT")
        return found
    }

    /** oynatloload embed -> E-AUTH JWT -> video-bilgi JSON -> imzalı HLS + altyazılar. */
    private suspend fun resolveEmbed(
        embedUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val id = EMBED_ID.find(embedUrl)?.groupValues?.get(1) ?: return false
        val page = runCatching { app.get(embedUrl, headers = uaHeaders, referer = referer) }.getOrNull()
            ?: return false
        // Token hem script içinde hem Set-Cookie'de; cookie jar'a güvenmek için ikisi de denenir.
        val token = EAUTH.find(page.text)?.groupValues?.get(1)
            ?: page.cookies[EAUTH_COOKIE]
            ?: return false
        val info = runCatching {
            JSONObject(
                app.get(
                    "$EMBED_HOST/api/video-bilgi/$id",
                    headers = uaHeaders,
                    referer = embedUrl,
                    params = mapOf("eauth" to token),
                ).text
            )
        }.getOrNull() ?: return false

        var found = false
        val sources = info.optJSONArray("sources")
        if (sources != null && sources.length() > 0) {
            for (i in 0 until sources.length()) {
                val source = sources.optJSONObject(i) ?: continue
                val file = source.optString("file").trim().takeIf { it.isNotBlank() } ?: continue
                val label = source.optString("label").trim().ifBlank { "auto" }
                callback(
                    newExtractorLink(name, "$name $label", file, ExtractorLinkType.M3U8) {
                        this.referer = "$EMBED_HOST/"
                        this.headers = mapOf("Origin" to EMBED_HOST)
                        this.quality = qualityOf(label)
                    }
                )
                found = true
            }
        } else {
            val master = info.optString("src").trim()
            if (master.isNotBlank()) {
                callback(
                    newExtractorLink(name, "$name Auto", master, ExtractorLinkType.M3U8) {
                        this.referer = "$EMBED_HOST/"
                        this.headers = mapOf("Origin" to EMBED_HOST)
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }
        }

        val subtitles = info.optJSONArray("subtitles")
        if (subtitles != null) {
            for (i in 0 until subtitles.length()) {
                val subtitle = subtitles.optJSONObject(i) ?: continue
                val file = subtitle.optString("file").trim().takeIf { it.isNotBlank() } ?: continue
                val label = subtitle.optString("label").trim().ifBlank { "Altyazı" }
                val url = if (file.startsWith("http")) file else EMBED_HOST + file
                subtitleCallback(newSubtitleFile(label, url))
            }
        }
        return found
    }

    /** "Onun Evi Filmi 1080p Full HD izle" -> "Onun Evi"; "X izle (2026)" -> "X". */
    private fun cleanTitle(raw: String): String = raw.trim()
        .replace(FILMI_SUFFIX, "")
        .replace(IZLE_SUFFIX, "")
        .replace(YEAR_SUFFIX, "")
        .trim()

    /** sources label'ı ("480p"/"900p"/"auto") sayısal kaliteye çevrilir. */
    private fun qualityOf(label: String): Int =
        label.filter { it.isDigit() }.toIntOrNull()?.takeIf { it >= 240 } ?: Qualities.Unknown.value

    companion object {
        private const val EMBED_HOST = "https://oynatloload.top"
        private const val EAUTH_COOKIE = "ultra_embed_auth"
        private const val MAX_ALTERNATES = 3
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"

        private val ROW_BASES = linkedMapOf(
            "Yeni Filmler" to "",
            "Film Arşivi" to "/film-arsivi",
            "Türkçe Dublaj" to "/turkce-dublaj-filmler",
            "Türkçe Altyazılı" to "/turkce-altyazili-filmler",
            "2026 Filmleri" to "/yil/2026",
            "Aksiyon" to "/aksiyon-filmleri-hd-izle",
        )
        val ROWS: List<String> = ROW_BASES.keys.toList()

        private const val CARD = ".posters-4-col a.poster"
        private val EMBED_ID = Regex("""/embed/(\d+)""")
        private val EAUTH = Regex("__ULTRA_EAUTH\\s*=\\s*\"([^\"]+)\"")
        private val FILMI_SUFFIX = Regex("""\s+Filmi\b.*$""")
        private val IZLE_SUFFIX = Regex("""\s+izle\b.*$""")
        private val YEAR_SUFFIX = Regex("""\s*\(\d{4}\)\s*$""")
        private val YEAR = Regex("""\((\d{4})\)""")
        private val MINUTES = Regex("""(\d+)\s*dakika""")
    }
}
