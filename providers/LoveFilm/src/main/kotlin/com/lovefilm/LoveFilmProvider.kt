package com.lovefilm

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import kotlin.math.abs
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * LoveFilm — lovefilmizle.net (WordPress "filmzone" teması, Türkçe dublaj/altyazılı film arşivi).
 *
 * Listeler: ana sayfa `/page/N/`, türler `/aksiyon/page/N/`, yıl arşivi `/yapim/2026/page/N/`,
 * arama `/?s=<sorgu>` ve sonraki sayfaları `/page/N/?s=<sorgu>`.
 * Oynatıcı zinciri: film sayfasındaki litespeed gecikmeli iframe yerel sarmalayıcıya
 * (`/bemoly/bemoly.php?url=<embed>`) gider, sarmalayıcı vidmoly embed'ini basar, embed
 * jwplayer kurulumunda master m3u8 adresini düz metin olarak taşır.
 */
class LoveFilmProvider : MainAPI() {
    override var mainUrl = "https://lovefilmizle.net"
    override var name = "LoveFilm"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    private val uaHeaders = mapOf(
        "User-Agent" to UA,
        "Accept-Language" to "tr-TR,tr;q=0.9",
    )

    override val mainPage get() = ROWS.mapNotNull { name ->
        if (!Settings.rowEnabled(name)) return@mapNotNull null
        when (name) {
            "Son Eklenen Filmler" -> MainPageData(name, "$mainUrl/page/SAYFA/")
            "Türkçe Dublaj" -> MainPageData(name, "$mainUrl/turkce-dublaj/page/SAYFA/")
            "Türkçe Altyazılı" -> MainPageData(name, "$mainUrl/turkce-altyazili/page/SAYFA/")
            "Yerli Filmler" -> MainPageData(name, "$mainUrl/yerli-film/page/SAYFA/")
            "Aksiyon" -> MainPageData(name, "$mainUrl/aksiyon/page/SAYFA/")
            "Dram" -> MainPageData(name, "$mainUrl/dram/page/SAYFA/")
            "Komedi" -> MainPageData(name, "$mainUrl/komedi/page/SAYFA/")
            "Korku" -> MainPageData(name, "$mainUrl/korku/page/SAYFA/")
            "Bilim Kurgu" -> MainPageData(name, "$mainUrl/bilim-kurgu/page/SAYFA/")
            "2026 Filmleri" -> MainPageData(name, "$mainUrl/yapim/2026/page/SAYFA/")
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data.replace("SAYFA", page.toString()), headers = uaHeaders).document
        return newHomePageResponse(
            request.name,
            doc.cards(),
            hasNext = doc.selectFirst("a.nextpostslink") != null,
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = if (page <= 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$page/?s=$encoded"
        val doc = app.get(url, headers = uaHeaders).document
        return newSearchResponseList(doc.cards(), hasNext = doc.selectFirst("a.nextpostslink") != null)
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = fixUrl(url)
        val doc = runCatching { app.get(pageUrl, headers = uaHeaders).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: film sayfası yüklenemedi. $REPORT_HINT")
        val title = doc.selectFirst("h1")?.text()?.cleanTitle()?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("$name: film adı okunamadı. $REPORT_HINT")
        val info = doc.info()

        return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
            this.posterUrl = doc.selectFirst("div.block-poster-left img")
                ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
                ?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                ?.let { fixUrlNull(it) }
                ?: fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
            this.plot = doc.selectFirst("div.block-post")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            this.year = info.value("Yıl").toIntOrNull()
            this.duration = info.value("Süre").filter { it.isDigit() }.toIntOrNull()
            this.tags = info.links("Kategori")
            this.score = info.value("IMDB Puanı").takeIf { it.isNotEmpty() }?.let { Score.from10(it) }
            addActors(info.links("Oyuncular"))
            doc.selectFirst("a[href*='youtu']")?.attr("href")?.trim()
                ?.takeIf { it.isNotEmpty() }?.let { addTrailer(it) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageUrl = fixUrl(data)
        val doc = runCatching { app.get(pageUrl, headers = uaHeaders).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: film sayfası yüklenemedi. $REPORT_HINT")

        val embeds = embedUrls(doc, pageUrl)
        if (embeds.isEmpty()) throw ErrorLoadingException("$name: oynatıcı bulunamadı. $REPORT_HINT")
        val streams = LinkedHashMap<String, Pair<String, Int>>()
        for (embed in embeds) {
            for ((stream, label) in streamsOf(embed, pageUrl)) {
                val quality = hlsQuality(stream) ?: labelQuality(label)
                if (!streams.containsKey(stream)) streams[stream] = embedLabel(embed) to quality
            }
        }
        if (streams.isEmpty()) {
            throw ErrorLoadingException("$name: oynatılabilir kaynak bulunamadı. $REPORT_HINT")
        }

        val all = streams.map { (stream, meta) -> Triple(stream, meta.first, meta.second) }
        val allowed = all.filter { Settings.qualityAllowed(it.third) }
        // Kalite sınırı her kaynağı elediyse en düşük kaliteli tek kaynak yine de verilir.
        val chosen = allowed.ifEmpty { listOfNotNull(all.minByOrNull { it.third }) }
        for ((stream, label, quality) in chosen) {
            val type = if (stream.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback.invoke(
                newExtractorLink(name, label, stream, type) {
                    this.referer = pageUrl
                    this.headers = mapOf("User-Agent" to UA)
                    this.quality = quality
                }
            )
        }
        return true
    }

    // ---------------------------------------------------------------- listeler

    private fun Document.cards(): List<SearchResponse> =
        select("div.moviebox-item").mapNotNull { it.toSearchCard() }.distinctBy { it.url }

    private fun Element.toSearchCard(): SearchResponse? {
        val anchor = selectFirst("div.poster > a[href]") ?: selectFirst("a[href]") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        if (!href.startsWith(mainUrl)) return null
        val title = selectFirst("div.title")?.text()?.cleanTitle()
            ?.takeIf { it.isNotEmpty() }
            ?: anchor.attr("title").cleanTitle().takeIf { it.isNotEmpty() }
            ?: return null

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = fixUrlNull(
                selectFirst("img[data-src]")?.attr("data-src")
                    ?: selectFirst("img[src]")?.attr("src")
                        ?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            )
            this.year = selectFirst("span.icon-year")?.text()?.trim()?.toIntOrNull()
            this.score = Score.from10(selectFirst("div.poster-imdb")?.text()?.replace("IMDb", "")?.trim())
        }
    }

    /** Kart ve başlık metinleri "<ad> izle" kalıbıyla gelir. */
    private fun String.cleanTitle(): String = replace(TITLE_SUFFIX, "").trim()

    // ---------------------------------------------------------------- künye

    /** "Film Bilgileri" bloğu: `<div class="block-item"><span>Etiket </span>değer</div>`. */
    private fun Document.info(): Map<String, Element> =
        select("div.block-item").mapNotNull { item ->
            val label = item.selectFirst("span")?.text()?.trim() ?: return@mapNotNull null
            label to item
        }.toMap()

    private fun Map<String, Element>.value(label: String): String =
        this[label]?.text()?.removePrefix(label)?.trim().orEmpty()

    private fun Map<String, Element>.links(label: String): List<String> =
        this[label]?.select("a")?.map { it.text().trim() }?.filter { it.isNotEmpty() }.orEmpty()

    // ---------------------------------------------------------------- oynatıcı

    /** Film sayfasındaki oynatıcı iframe'leri; yerel sarmalayıcılar bir kat açılır. */
    private suspend fun embedUrls(doc: Document, pageUrl: String): List<String> {
        val frames = doc.select("div.player-container iframe, #player iframe").ifEmpty { doc.select("iframe") }
        val out = LinkedHashSet<String>()
        for (frame in frames) {
            val src = frame.attr("data-litespeed-src").ifBlank { frame.attr("data-src") }
                .ifBlank { frame.attr("src") }.trim()
            if (src.isEmpty() || src == "about:blank") continue
            var url = fixUrl(src)
            if (url.startsWith(mainUrl)) {
                val wrapper = runCatching { app.get(url, headers = uaHeaders, referer = pageUrl).document }
                    .getOrNull() ?: continue
                url = innerFrame(wrapper) ?: continue
            }
            out += url
        }
        return out.toList()
    }

    private fun innerFrame(doc: Document): String? =
        doc.selectFirst("iframe")?.let { frame ->
            frame.attr("data-litespeed-src").ifBlank { frame.attr("data-src") }.ifBlank { frame.attr("src") }
        }?.trim()?.takeIf { it.isNotEmpty() && it != "about:blank" }?.let { fixUrl(it) }

    /**
     * Embed sayfasındaki jwplayer kurulumundan akış adresleri. Kaynak listesi yoksa düz
     * m3u8 aramasına düşülür. Küçük resim/slayt `file:` girdileri uzantı süzgeciyle elenir.
     */
    private suspend fun streamsOf(embedUrl: String, referer: String): List<Pair<String, String>> {
        if (VIDEO_FILE.containsMatchIn(embedUrl)) return listOf(embedUrl to "")
        val html = runCatching { app.get(embedUrl, headers = uaHeaders, referer = referer).text }
            .getOrNull() ?: return emptyList()
        val label = LABEL.find(html)?.groupValues?.get(1)?.trim().orEmpty()
        val listed = FILE_URL.findAll(html).map { it.groupValues[1].replace("\\", "") }
            .filter { VIDEO_FILE.containsMatchIn(it) }
            .map { it to label }
            .toList()
        return listed.ifEmpty {
            M3U8_ANY.findAll(html).map { it.value.replace("\\", "") to label }.toList()
        }.distinctBy { it.first }
    }

    /** Master m3u8'deki en yüksek çözünürlük; kaynak listesinde yoksa null. */
    private suspend fun hlsQuality(stream: String): Int? {
        if (!stream.contains(".m3u8")) return null
        val text = runCatching { app.get(stream, headers = uaHeaders).text }.getOrNull() ?: return null
        return RESOLUTION.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }
            .maxOrNull()?.let { height -> STANDARD_QUALITIES.minByOrNull { abs(it - height) } }
    }

    private fun labelQuality(label: String): Int =
        QUALITY_IN_LABEL.find(label)?.groupValues?.get(1)?.toIntOrNull() ?: Qualities.Unknown.value

    private fun embedLabel(embedUrl: String): String =
        embedUrl.substringAfter("://").substringBefore('/').removePrefix("www.")
            .substringBefore('.').replaceFirstChar { it.uppercase() }.ifBlank { name }

    companion object {
        /** Homepage rows in display order; all map to live listing pages. */
        val ROWS = listOf(
            "Son Eklenen Filmler",
            "Türkçe Dublaj",
            "Türkçe Altyazılı",
            "Yerli Filmler",
            "Aksiyon",
            "Dram",
            "Komedi",
            "Korku",
            "Bilim Kurgu",
            "2026 Filmleri",
        )

        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
        private val STANDARD_QUALITIES = listOf(2160, 1440, 1080, 720, 480, 360, 240)

        private val TITLE_SUFFIX = Regex("""\s*[iİ]zle$""", RegexOption.IGNORE_CASE)
        private val FILE_URL = Regex("""file\s*:\s*['"]([^'"]+)['"]""")
        private val M3U8_ANY = Regex("""https?:[^\s"'<>]+?\.m3u8[^\s"'<>]*""")
        private val VIDEO_FILE = Regex("""\.(m3u8|mp4)(\?|$)""")
        private val RESOLUTION = Regex("""RESOLUTION=\d+x(\d+)""")
        private val LABEL = Regex("""label\s*:\s*['"]([^'"]+)['"]""")
        private val QUALITY_IN_LABEL = Regex("""(\d{3,4})p""")
    }
}
