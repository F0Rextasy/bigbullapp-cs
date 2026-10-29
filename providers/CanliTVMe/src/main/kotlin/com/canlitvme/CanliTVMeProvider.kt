package com.canlitvme

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * CanliTVMe — canlitv.me canlı kanal dizini.
 *
 * Bu modül aslen GolgeTV (panel.cloudgolge.shop) için istenmişti; panel yazım sırasında ölüydü
 * (alan adı NXDOMAIN), bu yüzden canlı olan canlitv.me üzerinden yazıldı.
 *
 * Akış: /live/<kanal> → geolive.php → yayin.php oynatıcısındaki `file` dizesi. Oynatıcı HLS adresini
 * 35 sembollük döngüsel yerine-koyma ile saklar; anahtar, dizenin başındaki sayıdır.
 */
class CanliTVMeProvider : MainAPI() {
    override var mainUrl = "https://www.canlitv.me"
    override var name = "CanliTVMe"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/category/ulusalkanallar" to "Ulusal",
        "$mainUrl/category/haber-kanallari-izle" to "Haber",
        "$mainUrl/category/spor-kanallari-hd" to "Spor",
        "$mainUrl/category/muzik-kanallari" to "Müzik",
        "$mainUrl/category/belgesel-kanallari" to "Belgesel",
        "$mainUrl/category/cocuk-kanallari" to "Çocuk",
        "$mainUrl/category/dini-kanallar" to "Dini",
        "$mainUrl/category/yerel-kanallar" to "Yerel",
        "$mainUrl/category/kktc-tv-kanallari" to "KKTC",
        "$mainUrl/category/azerbaycan-kanallari" to "Azerbaycan",
        "$mainUrl/category/deutsche-fernsehsender" to "Almanca",
        "$mainUrl/category/iran-tv-kanallari-izle" to "İran",
        "$mainUrl/category/yabanci-kanallar" to "Yabancı",
        "$mainUrl/category/diger-kanallar" to "Diğer",
        "$mainUrl/category/trt-eba-tv-kanallari" to "TRT EBA",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data).document
        // Kategori sayfaları tüm kanalları tek sayfada listeler.
        return newHomePageResponse(request.name, parseCards(doc.select("li.canlitvlist")), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // /s yalnızca ana sayfadaki form jetonuyla yanıt verir.
        val security = app.get(mainUrl).document
            .selectFirst("form#arama input[name=security]")
            ?.attr("value")
            ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: arama jetonu alınamadı. $REPORT_HINT")
        val doc = app.get("$mainUrl/s", params = mapOf("s" to query, "security" to security)).document
        return parseCards(doc.select("li.canlitvlist"))
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: kanal sayfası açılamadı. $REPORT_HINT")
        val channelName = doc.selectFirst("h1.baslik")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: kanal adı okunamadı. $REPORT_HINT")
        val streamUrl = resolveStream(doc, url)
            ?: throw ErrorLoadingException("$name: $channelName için HLS yayını bulunamadı. $REPORT_HINT")
        return newLiveStreamLoadResponse(channelName, url, streamUrl) {
            this.posterUrl = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        }
    }

    /** /live/<kanal> → geolive.php → yayin.php zincirindeki gizlenmiş HLS adresini çözer. */
    private suspend fun resolveStream(doc: Document, pageUrl: String): String? {
        val geoUrl = doc.selectFirst("iframe[src*=geolive.php]")?.attr("src")?.let { fixUrl(it) } ?: return null
        val playerPage = runCatching { app.get(geoUrl, referer = pageUrl).document }.getOrNull() ?: return null
        val yayinUrl = playerPage.selectFirst("iframe[src*=yayin.php]")?.attr("src")?.let { fixUrl(it) }
        val playerHtml = if (yayinUrl != null) {
            runCatching { app.get(yayinUrl, referer = geoUrl).text }.getOrNull() ?: return null
        } else {
            playerPage.html()
        }
        val encoded = FILE_PATTERN.find(playerHtml)?.groupValues?.get(1) ?: return null
        return if (encoded.contains(MARKER)) decodePlayerUrl(encoded) else encoded
    }

    private fun decodePlayerUrl(encoded: String): String {
        var key = encoded.substringBefore(MARKER).toIntOrNull() ?: 0
        var result = encoded.substringAfter(MARKER)
        PLAIN.forEachIndexed { index, plain ->
            if (key > CIPHER.lastIndex) key = 0
            result = result.replace(CIPHER[key], plain)
            key++
        }
        return result
    }

    private fun parseCards(cards: List<Element>): List<LiveSearchResponse> = cards.mapNotNull { card ->
        val anchor = card.selectFirst("a[href*=/live/]") ?: return@mapNotNull null
        val channelUrl = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
        val channelName = anchor.attr("title").trim().ifBlank { anchor.text().trim() }
        if (channelName.isBlank()) return@mapNotNull null
        newLiveSearchResponse(channelName, channelUrl, TvType.Live) {
            this.posterUrl = fixUrlNull(card.selectFirst("img")?.attr("src"))
        }
    }.distinctBy { it.url }

    companion object {
        private const val MARKER = "\u00C4x|Xf|x"
        private val FILE_PATTERN = Regex("""file\s*:\s*'([^']+)'""")

        // Oynatıcının sembol alfabesi ve karşılıkları (yayin.php içindeki x34fcag3).
        private val CIPHER = charArrayOf(
            '\u20AC', '\u0024', '\u0102', '\u00C4', '\u00CB', '\u0122', '\u1E28', '\u0136', '\u1E3C',
            '\u0145', '\u0147', '\u0160', '\u021A', '\u017D', '\u04D8', '\u0404', '\u0411', '\u0496',
            '\u0427', '\u0416', '\u0414', '\u04E0', '\u0424', '\u0492', '\u04F6', '\u042B', '\u0418',
            '\u041A', '\u0409', '\u04E8', '\u040E', '\u040A', '\u04BA', '\u0413', '\u015E',
        )
        private val PLAIN = charArrayOf(
            '\u0030', '\u0031', '\u0032', '\u0033', '\u0034', '\u0035', '\u0036', '\u0037', '\u0038',
            '\u0039', '\u002E', '\u0026', '\u003D', '\u0077', '\u003F', '\u0063', '\u006F', '\u006D',
            '\u0061', '\u0066', '\u006C', '\u0069', '\u0068', '\u0074', '\u0073', '\u003A', '\u002F',
            '\u0072', '\u0065', '\u0064', '\u006E', '\u006B', '\u0070', '\u005F', '\u002D',
        )

        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}
