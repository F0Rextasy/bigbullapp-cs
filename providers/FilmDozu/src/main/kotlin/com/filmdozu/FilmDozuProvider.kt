package com.filmdozu

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
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * FilmDozu — filmdozu.com. Oynatıcılar film sayfasındaki `.source-strip` düğmelerinde:
 * vidmoly embed (JWPlayer `sources[0].file` = master m3u8) ve ok.ru `videoembed/<id>`;
 * "Fragman" düğmeleri YouTube'a gider ve atlanır. Altyazı dosyası site sunmaz.
 */
class FilmDozuProvider : MainAPI() {
    override var mainUrl = "https://filmdozu.com"
    override var name = "FilmDozu"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)
    private val uaHeaders get() = browserHeaders

    override val mainPage get() = ROWS.mapNotNull { row ->
        if (!Settings.rowEnabled(row)) return@mapNotNull null
        val path = when (row) {
            "Son Eklenenler" -> "/kesfet?page=SAYFA"
            "IMDb Puanı" -> "/kesfet?sort=rating&page=SAYFA"
            "Türkçe Dublaj" -> "/dublaj/?page=SAYFA"
            else -> "/kategori/${GENRE_SLUGS[row]}/sayfa/SAYFA/"
        }
        MainPageData(row, "$mainUrl$path")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data.replace("SAYFA", page.toString()), headers = uaHeaders).document
        val cards = doc.select(CARD).mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newHomePageResponse(request.name, cards, hasNext = doc.hasNextPage())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/ara?q=${URLEncoder.encode(query, "UTF-8")}", headers = uaHeaders).document
        return doc.select(CARD).mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(selectFirst("a[href*='/film/']")?.attr("href")) ?: return null
        val title = selectFirst("h3.card-title")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        // Arama ızgarası yılı `.card-info` içinde, keşfet ızgarası `.card-meta` içinde verir.
        val meta = (selectFirst(".card-meta") ?: selectFirst(".card-info"))?.text().orEmpty()
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = fixUrlNull(selectFirst("img")?.posterSrcLazy())
            this.year = YEAR.find(meta)?.value?.toIntOrNull()
            this.score = Score.from10(selectFirst(".rating, .rate")?.text()?.trim())
        }
    }

    /** Keşif sayfaları `?page=` (kesfet) ya da `div.pagination` (dublaj/kategori) kullanır. */
    private fun Document.hasNextPage(): Boolean =
        selectFirst("link[rel=next]") != null ||
            select("div.pagination a.page-link").any { it.selectFirst("i.ri-arrow-right-s-line") != null }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = uaHeaders).document
        val title = doc.selectFirst("h1.hero-title")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = fixUrlNull(doc.selectFirst("img.poster-img")?.attr("src"))
            this.plot = doc.selectFirst(".synopsis p")?.text()?.trim()?.takeIf { it.isNotBlank() }
            // ".hero-sub" = "Orijinal Ad (Yıl)".
            this.year = doc.selectFirst(".hero-sub")?.text()?.let { YEAR_STARRED.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            this.score = Score.from10(doc.selectFirst(".tag.imdb")?.text()?.let { RATING.find(it)?.value })
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val doc = app.get(data, headers = uaHeaders).document
        val sources = LinkedHashMap<String, String>()
        for (button in doc.select(".source-strip button[onclick]")) {
            val src = SOURCE_URL.find(button.attr("onclick"))?.groupValues?.get(1)?.trim() ?: continue
            if (src.startsWith("http")) sources.putIfAbsent(src, button.text().trim())
        }
        if (sources.isEmpty()) {
            doc.selectFirst("iframe#video-frame[data-src]")?.attr("data-src")?.trim()
                ?.takeIf { it.startsWith("http") }?.let { sources[it] = "Varsayılan" }
        }

        var found = false
        for ((src, label) in sources) {
            val tag = label.ifBlank { "FilmDozu" }
            when {
                src.contains("youtube") || src.contains("youtu.be") -> Unit
                src.contains("ok.ru") || src.contains("odnoklassniki") ->
                    if (loadOkRu(src, tag, callback)) found = true
                src.contains("vidmoly") -> if (loadVidmoly(src, tag, callback)) found = true
                else -> if (loadExtractor(src, data, Settings.filterSubtitles(subtitleCallback), Settings.filterLinks(callback))) found = true
            }
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir kaynak yok. $REPORT_HINT")
        return true
    }

    /** vidmoly.net `.biz`'e 301 atar; JWPlayer kurulumundaki `file:` master m3u8'dir. */
    private suspend fun loadVidmoly(embed: String, label: String, callback: (ExtractorLink) -> Unit): Boolean {
        val html = runCatching { app.get(embed, referer = "$mainUrl/", headers = uaHeaders).text }
            .getOrNull() ?: return false
        val stream = VM_FILE.find(html)?.groupValues?.get(1)
            ?: VM_M3U8.find(html)?.groupValues?.get(1)
            ?: return false
        callback.invoke(
            newExtractorLink(name, label, stream, ExtractorLinkType.M3U8) {
                this.referer = "https://vidmoly.biz/"
                this.quality = Qualities.Unknown.value
                this.headers = uaHeaders
            }
        )
        return true
    }

    /** ok.ru embed kabuğu; HLS master + MP4 yedekleri. */
    private suspend fun loadOkRu(embed: String, label: String, callback: (ExtractorLink) -> Unit): Boolean {
        val ua = browserHeaders.getValue("user-agent")
        val meta = com.bigbull.common.fetchOkRuMetadata(embed, userAgent = ua) ?: return false
        var found = false
        com.bigbull.common.okRuHlsUrl(meta)?.let { hls ->
            callback.invoke(
                newExtractorLink(name, "$label • Ok.Ru HLS", hls, ExtractorLinkType.M3U8) {
                    this.referer = "https://ok.ru/"
                    this.headers = mapOf("User-Agent" to ua)
                    this.quality = Qualities.Unknown.value
                }
            )
            found = true
        }
        val videos = meta.optJSONArray("videos") ?: return found
        for (i in 0 until videos.length()) {
            val item = videos.optJSONObject(i) ?: continue
            val stream = item.optString("url").takeIf { it.startsWith("http") } ?: continue
            val quality = when (item.optString("name").lowercase()) {
                "mobile", "lowest" -> 240
                "low" -> 360
                "sd" -> 480
                "hd" -> 720
                "full" -> 1080
                else -> getQualityFromName(item.optString("name").uppercase())
            }
            if (!Settings.qualityAllowed(quality)) continue
            callback.invoke(
                newExtractorLink(name, "$label • Ok.Ru", stream, ExtractorLinkType.VIDEO) {
                    this.referer = "https://ok.ru/"
                    this.headers = mapOf("User-Agent" to ua)
                    this.quality = quality
                }
            )
            found = true
        }
        return found
    }

    companion object {
        /** Sidebar türleri; sayfa 1 `/kategori/<slug>/`, sonrası `/sayfa/N/`. */
        // `/kategori/tv-film/` var ama boş; tür listesine alınmadı.
        private val GENRE_SLUGS = linkedMapOf(
            "Aksiyon" to "aksiyon",
            "Macera" to "macera",
            "Bilim Kurgu" to "bilim-kurgu",
            "Fantastik" to "fantastik",
            "Animasyon" to "animasyon",
            "Komedi" to "komedi",
            "Dram" to "dram",
            "Romantik" to "romantik",
            "Aile" to "aile",
            "Korku" to "korku",
            "Gerilim" to "gerilim",
            "Gizem" to "gizem",
            "Suç" to "suc",
            "Tarih" to "tarih",
            "Savaş" to "savas",
            "Belgesel" to "belgesel",
            "Müzik" to "muzik",
            "Spor" to "spor",
        )
        val ROWS: List<String> =
            listOf("Son Eklenenler", "IMDb Puanı", "Türkçe Dublaj") + GENRE_SLUGS.keys

        private const val CARD = "article.card"
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
        private val YEAR = Regex("""\b(19|20)\d{2}\b""")
        private val YEAR_STARRED = Regex("""\((\d{4})\)""")
        private val RATING = Regex("""\d+(?:[.,]\d+)?""")
        private val SOURCE_URL = Regex("""loadSource\('([^']+)'""")
        private val VM_FILE = Regex("""sources:\s*\[\s*\{\s*file:\s*['"]([^'"]+)['"]""")
        private val VM_M3U8 = Regex("""file\s*:\s*['"](https?://[^'"]+\.m3u8[^'"]*)['"]""")
        private val OK_ID = Regex("""videoembed/(\d+)""")
    }
}
