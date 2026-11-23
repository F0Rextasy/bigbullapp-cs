package com.venusfilmizle

import com.bigbull.common.browserHeaders
import com.bigbull.common.jsonLdDescription
import com.bigbull.common.parseHlsVariants
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
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * VenusFilmizle — venusfilmizle.com (720+ film, dublaj/altyazı).
 * Arama JSON döner (`/ara?q=`), film sayfası Movie JSON-LD taşır.
 * Oynatma `/izle/<slug>` sayfasındaki `window.__lythvm` kaynak haritasından kurulur:
 * `/go?m=<slug>&s=<dil>&i=<sıra>` 302 ile ya `hd.player.rest/oynat?v=<token>`
 * (gerçek master `/hls?v=<token>`) ya da `vidmoly.biz` embed'ine gider.
 */
class VenusFilmizleProvider : MainAPI() {
    override var mainUrl = "https://venusfilmizle.com"
    override var name = "VenusFilmizle"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage get() = ROW_PATHS.mapNotNull { (row, path) ->
        if (Settings.rowEnabled(row)) MainPageData(row, mainUrl + path) else null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data.replace("SAYFA", page.toString()), headers = browserHeaders).document
        val items = doc.select("div[data-film]").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        // Son sayfada "Sonraki sayfa" düğmesi disabled gelir.
        val hasNext = doc.select("button[aria-label=\"Sonraki sayfa\"]").any { !it.hasAttr("disabled") }
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val json = runCatching {
            app.get(
                "$mainUrl/ara?q=" + URLEncoder.encode(query, "UTF-8"),
                headers = browserHeaders + mapOf(
                    "accept" to "application/json",
                    "x-requested-with" to "XMLHttpRequest",
                ),
            ).text
        }.getOrNull() ?: return emptyList()
        val items = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val href = fixUrlNull(item.optString("u")) ?: return@mapNotNull null
            val title = item.optString("t").ifBlank { item.optString("o") }.trim()
            if (title.isBlank()) return@mapNotNull null
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = fixUrlNull(item.optString("s"))
                this.year = item.optInt("y").takeIf { it > 0 }
                this.score = Score.from10(item.optString("r"))
            }
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = selectFirst("a[href^=/film/]")?.attr("href")?.let { fixUrlNull(it) } ?: return null
        val title = attr("data-film").ifBlank { selectFirst("figcaption")?.text().orEmpty() }.trim()
        if (title.isBlank()) return null
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = fixUrlNull(selectFirst("img")?.posterSrcLazy())
            this.year = attr("data-year").toIntOrNull()
            this.score = Score.from10(attr("data-imdb"))
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url, headers = browserHeaders).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: film sayfası açılamadı. $REPORT_HINT")
        val schema = movieSchema(doc)
        val title = doc.selectFirst("main h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: schema?.optString("name")?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: film bilgisi okunamadı. $REPORT_HINT")
        val clock = Regex("""(\d+)s\s*(\d+)dk""")
            .find(doc.selectFirst("main section")?.text().orEmpty())

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = fixUrlNull(schema?.optString("image"))
                ?: fixUrlNull(doc.selectFirst("figure img")?.posterSrcLazy())
            this.plot = doc.jsonLdDescription()
            this.year = schema?.optString("datePublished")?.take(4)?.toIntOrNull()
            this.tags = schema?.optJSONArray("genre")?.let { names(it) }
            this.score = Score.from10(doc.selectFirst("figure span b")?.text())
            this.duration = clock?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val slug = data.substringAfter("/film/").substringBefore("?").trim('/')
        val playUrl = "$mainUrl/izle/$slug"
        val html = runCatching { app.get(playUrl, headers = browserHeaders).text }.getOrNull()
            ?: throw ErrorLoadingException("$name: oynatıcı sayfası açılamadı. $REPORT_HINT")
        val sources = LYTHVM.find(html)?.groupValues?.get(1)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: throw ErrorLoadingException("$name: kaynak listesi okunamadı. $REPORT_HINT")

        val seen = mutableSetOf<String>()
        val subOut = Settings.filterSubtitles(subtitleCallback)
        val linkOut: (ExtractorLink) -> Unit = {
            if (seen.add(it.url) && Settings.qualityAllowed(it.quality)) callback(it)
        }
        var found = false
        for ((key, label) in LANGUAGES) {
            val entries = sources.optJSONArray(key) ?: continue
            for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index) ?: continue
                val target = entry.optString("u").takeIf { it.startsWith("http") }
                    ?: goTarget(slug, key, index, playUrl) ?: continue
                val server = "$label ${index + 1}"
                val ok = when {
                    target.contains("hd.player.rest/") -> hdPlayerLinks(target, server, linkOut)
                    target.contains("vidmoly.") -> vidmolyLinks(target, playUrl, server, linkOut)
                    YOUTUBE.containsMatchIn(target) -> false
                    else -> runCatching { loadExtractor(target, playUrl, subOut, linkOut) }.getOrDefault(false)
                }
                if (ok) found = true
            }
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir kaynak yok. $REPORT_HINT")
        return true
    }

    /** `/go` 302'sinin hedefi; uygulama yönlendirmeyi kendi izlerse son URL döner. */
    private suspend fun goTarget(slug: String, source: String, index: Int, referer: String): String? {
        val requestUrl = "$mainUrl/go?m=$slug&s=$source&i=$index"
        val probe = runCatching {
            app.get(requestUrl, referer = referer, headers = browserHeaders, allowRedirects = false)
        }.getOrNull() ?: return null
        return listOfNotNull(probe.headers["location"], probe.url)
            .firstOrNull { it.startsWith("http") && it != requestUrl }
    }

    /** hd.player.rest: `/oynat?v=<token>` sayfası master'ı `/hls?v=<token>` üzerinden çeker. */
    private suspend fun hdPlayerLinks(embed: String, label: String, emit: (ExtractorLink) -> Unit): Boolean {
        val token = Regex("""[?&]v=([^&]+)""").find(embed)?.groupValues?.get(1) ?: return false
        return emitHls("https://hd.player.rest/hls?v=$token", label, embed, emit)
    }

    /** vidmoly embed'i jwplayer `sources: [{file: '…master.m3u8'}]` taşır. */
    private suspend fun vidmolyLinks(
        embed: String,
        referer: String,
        label: String,
        emit: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = runCatching { app.get(embed, referer = referer, headers = browserHeaders).text }
            .getOrNull() ?: return false
        val master = Regex("""file\s*:\s*['"](https?://[^'"]+\.m3u8[^'"]*)['"]""")
            .find(html)?.groupValues?.get(1) ?: return false
        return emitHls(master, "$label VidMoly", embed, emit)
    }

    private suspend fun emitHls(
        masterUrl: String,
        label: String,
        referer: String,
        emit: (ExtractorLink) -> Unit,
    ): Boolean {
        val text = runCatching { app.get(masterUrl, referer = referer, headers = browserHeaders).text }
            .getOrNull() ?: return false
        if (!text.startsWith("#EXTM3U")) return false
        val variants = parseHlsVariants(masterUrl, text)
        if (variants.isEmpty()) {
            emit(link(label, masterUrl, Qualities.Unknown.value, referer))
            return true
        }
        variants.forEach { (height, url) -> emit(link("$label ${height}p", url, height, referer)) }
        return true
    }

    private suspend fun link(label: String, url: String, quality: Int, referer: String) =
        newExtractorLink(name, label, url, ExtractorLinkType.M3U8) {
            this.referer = referer
            this.quality = quality
            this.headers = browserHeaders
        }

    /** Film sayfasındaki Movie JSON-LD düğümü (VideoObject/BreadcrumbList ayrı bloklarda gelir). */
    private fun movieSchema(doc: Document): JSONObject? = doc.select("script[type=application/ld+json]")
        .firstNotNullOfOrNull { element ->
            runCatching { JSONObject(element.data().trim()) }
                .getOrNull()
                ?.takeIf { it.optString("@type").equals("Movie", ignoreCase = true) }
        }

    private fun names(array: JSONArray): List<String> =
        (0 until array.length()).mapNotNull { array.optString(it).trim().takeIf { s -> s.isNotBlank() } }

    companion object {
        /** Satır adı → liste yolu; SAYFA yer tutucusu sayfa numarasıyla değişir. */
        private val ROW_PATHS = linkedMapOf(
            "Son Eklenenler" to "/filmler?siralama=yeni&sayfa=SAYFA",
            "Tüm Filmler" to "/filmler?sayfa=SAYFA",
            "IMDb Vitrin" to "/imdb?sayfa=SAYFA",
            "Trend" to "/trend?sayfa=SAYFA",
            "Aksiyon" to "/tur/aksiyon?sayfa=SAYFA",
            "Dram" to "/tur/dram?sayfa=SAYFA",
            "Komedi" to "/tur/komedi?sayfa=SAYFA",
            "Gerilim" to "/tur/gerilim?sayfa=SAYFA",
            "Korku" to "/tur/korku?sayfa=SAYFA",
            "Bilim Kurgu" to "/tur/bilim-kurgu?sayfa=SAYFA",
            "Fantastik" to "/tur/fantastik?sayfa=SAYFA",
            "Macera" to "/tur/macera?sayfa=SAYFA",
            "Aile" to "/tur/aile?sayfa=SAYFA",
            "Animasyon" to "/tur/animasyon?sayfa=SAYFA",
            "Belgesel" to "/tur/belgesel?sayfa=SAYFA",
            "Romantik" to "/tur/romantik?sayfa=SAYFA",
            "Suç" to "/tur/suc?sayfa=SAYFA",
            "Gizem" to "/tur/gizem?sayfa=SAYFA",
            "Tarih" to "/tur/tarih?sayfa=SAYFA",
            "Savaş" to "/tur/savas?sayfa=SAYFA",
        )

        val ROWS: List<String> = ROW_PATHS.keys.toList()

        private val LANGUAGES = listOf("dublaj" to "Türkçe Dublaj", "altyazi" to "Türkçe Altyazı")
        private val LYTHVM = Regex("""window\.__lythvm=(\{.*?\});\s*</script>""", RegexOption.DOT_MATCHES_ALL)
        private val YOUTUBE = Regex("""youtube\.com|youtu\.be""")
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}
