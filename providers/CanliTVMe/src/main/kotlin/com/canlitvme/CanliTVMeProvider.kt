package com.canlitvme

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * CanliTVMe — canlitv.me canlı kanal dizini, aynı yayıncının erişilebilir aynası üzerinden.
 *
 * canlitv.me Türkiye'de BTK kararıyla engelli (HTTP'de ESB yönlendirmesi, TLS'te SNI reset'i);
 * aynı yayıncının aynası blog.canlitvizle.tv erişilebilir ve aynı kanal listesini kullanır.
 *
 * Akış: /kategori/<kategori> → /<kanal> → geolive.php → yayinim.php oynatıcısındaki `file` dizesi.
 * Oynatıcı HLS adresini 35 sembollük döngüsel yerine-koyma ile saklar; anahtar, dizenin başındaki sayıdır.
 */
class CanliTVMeProvider : MainAPI() {
    override var mainUrl = "https://blog.canlitvizle.tv"
    override var name = "CanliTVMe"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true

    override val mainPage get() = ROWS.mapNotNull { name ->
        if (!Settings.rowEnabled(name)) return@mapNotNull null
        when (name) {
            "Ulusal" -> MainPageData(name, "$mainUrl/kategori/ulusal")
            "Haber" -> MainPageData(name, "$mainUrl/kategori/haber")
            "Spor" -> MainPageData(name, "$mainUrl/kategori/spor")
            "Çocuk" -> MainPageData(name, "$mainUrl/kategori/cocuk")
            "Müzik" -> MainPageData(name, "$mainUrl/kategori/muzik")
            "Belgesel" -> MainPageData(name, "$mainUrl/kategori/belgesel")
            "Dini" -> MainPageData(name, "$mainUrl/kategori/dini")
            "Yerel" -> MainPageData(name, "$mainUrl/kategori/yerel")
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data).document
        // Kategori sayfaları tüm kanalları tek sayfada listeler.
        return newHomePageResponse(request.name, parseCards(doc.select("a.channel-card")), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // /arama yalnızca ana sayfadaki form jetonuyla yanıt verir.
        val security = app.get(mainUrl).document
            .selectFirst("form[action*=arama] input[name=security]")
            ?.attr("value")
            ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: arama jetonu alınamadı. $REPORT_HINT")
        val doc = app.get("$mainUrl/arama", params = mapOf("s" to query, "security" to security)).document
        return parseCards(doc.select("a.channel-card"))
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: kanal sayfası açılamadı. $REPORT_HINT")
        val channelName = doc.selectFirst("span.breadcrumb-current")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: kanal adı okunamadı. $REPORT_HINT")
        val streamUrl = resolveStream(doc, url)
            ?: throw ErrorLoadingException("$name: $channelName için HLS yayını bulunamadı. $REPORT_HINT")
        return newLiveStreamLoadResponse(channelName, url, streamUrl)
    }

    /** /<kanal> → geolive.php → yayinim.php zincirindeki gizlenmiş HLS adresini çözer. */
    private suspend fun resolveStream(doc: Document, pageUrl: String): String? {
        val geoUrl = doc.selectFirst("iframe[src*=geolive.php]")?.attr("src")?.let { fixUrl(it) } ?: return null
        // Bazı kanallarda varsayılan sekme boştur; yayın sekmeleri de denenir.
        for (candidate in listOf(geoUrl, "$geoUrl&yayin=1", "$geoUrl&yayin=2")) {
            val playerPage = runCatching { app.get(candidate, referer = pageUrl).document }.getOrNull() ?: continue
            val yayinUrl = playerPage.selectFirst("iframe[src*=yayinim.php]")?.attr("src")?.let { fixUrl(it) }
            val playerHtml = if (yayinUrl != null) {
                runCatching { app.get(yayinUrl, referer = candidate).text }.getOrNull() ?: continue
            } else {
                playerPage.html()
            }
            val encoded = FILE_PATTERN.find(playerHtml)?.groupValues?.get(1) ?: continue
            return if (encoded.contains(MARKER)) decodePlayerUrl(encoded) else encoded
        }
        return null
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
        val channelUrl = fixUrlNull(card.attr("href")) ?: return@mapNotNull null
        val channelName = card.attr("title").trim().ifBlank { card.text().trim() }
        if (channelName.isBlank()) return@mapNotNull null
        newLiveSearchResponse(channelName, channelUrl, TvType.Live)
    }.distinctBy { it.url }

    companion object {
        /** Homepage rows in display order; all map to category pages. */
        val ROWS = listOf("Ulusal", "Haber", "Spor", "Çocuk", "Müzik", "Belgesel", "Dini", "Yerel")

        private const val MARKER = "\u00C4x|Xf|x"
        private val FILE_PATTERN = Regex("""file\s*:\s*'([^']+)'""")

        // Oynatıcının sembol alfabesi ve karşılıkları (yayinim.php içindeki x34fcag3).
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
