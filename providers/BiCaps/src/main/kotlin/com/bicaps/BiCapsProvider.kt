package com.bicaps

import com.bigbull.common.browserHeaders
import com.bigbull.common.evpMd5AesCbcDecrypt
import com.bigbull.common.parseHlsVariants
import com.bigbull.common.posterSrcLazy
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
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
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import org.jsoup.nodes.Element

/**
 * BiCaps — WordPress film arşivi; tarayıcı UA'sı olmadan 403.
 * Oynatıcı iki zincir: /biplayer/vid/<hash> → /biplayer/drive/<hash> → film.popcornvakti.net
 * (CryptoJS AES "Salted__" gövdesi, parola sayfanın içinde) ve /biplayer/embed2.php → vidmo
 * → vidmoly.biz. biplayer uçları Referer olmadan boş gövde döner.
 */
class BiCapsProvider : MainAPI() {
    override var mainUrl = "https://www.bicaps.live"
    override var name = "BiCaps"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    private val posterHdrs get() = browserHeaders + mapOf("Referer" to "$mainUrl/")

    override val mainPage get() = ROWS.mapNotNull { row ->
        if (!Settings.rowEnabled(row)) return@mapNotNull null
        val url = rowUrl(row) ?: return@mapNotNull null
        MainPageData(row, url)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}/page/$page"
        val doc = app.get(url, headers = browserHeaders).document
        return newHomePageResponse(
            request.name,
            doc.select(CARD).mapNotNull { it.toSearchResult() }.distinctBy { it.url },
            hasNext = doc.select(".sayfalama a[href]").any { it.attr("href").contains("/page/${page + 1}") },
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return app.get("$mainUrl/?s=$encoded", headers = browserHeaders).document
            .select(CARD).mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = selectFirst(".film-ismi a[href]")?.attr("href")?.let { fixUrlNull(it) } ?: return null
        val title = selectFirst(".film-ismi a")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = selectFirst("img")?.posterSrcLazy()?.let { fixUrlNull(it) }
            this.posterHeaders = posterHdrs
            this.year = selectFirst(".film-yil")?.text()?.trim()?.toIntOrNull()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url, headers = browserHeaders).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: film sayfası açılamadı")
        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: film başlığı okunamadı")
        val elements = doc.select("#listelements .elements").map { it.text().trim() }.filter { it.isNotEmpty() }
        val actors = doc.select("div.list-item")
            .filter { it.selectFirst("span.infoelements")?.text()?.contains("Oyuncular") == true }
            .flatMap { it.select("a[rel=tag]").map { a -> a.text().trim() }.filter { n -> n.isNotEmpty() } }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = doc.selectFirst(".film-afis img")?.posterSrcLazy()?.let { fixUrlNull(it) }
            this.posterHeaders = posterHdrs
            this.year = doc.select("div.list-item a[href*='/yil/']").firstOrNull()?.text()?.trim()?.toIntOrNull()
            this.plot = doc.selectFirst("#film-aciklama")?.text()?.trim()
            this.tags = elements.filter { !it.contains("izlenme") && !it.startsWith("IMDb") }
            this.score = elements.firstOrNull { it.startsWith("IMDb") }
                ?.substringAfter("IMDb:")?.trim()?.let { Score.from10(it) }
            if (actors.isNotEmpty()) addActors(actors)
            doc.selectFirst("#trailer iframe[src]")?.attr("src")?.takeIf { it.isNotBlank() }?.let { addTrailer(it) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = runCatching { app.get(data, headers = browserHeaders).text }.getOrNull()
            ?: throw ErrorLoadingException("$name: film sayfası açılamadı")
        val seen = mutableSetOf<String>()
        var found = false
        val emit: (ExtractorLink) -> Unit = { link ->
            if (Settings.qualityAllowed(link.quality)) {
                callback.invoke(link)
                found = true
            }
        }
        val subtitleFilter: (SubtitleFile) -> Unit = { if (Settings.subtitleAllowed(it.lang)) subtitleCallback.invoke(it) }

        for (source in sources(html)) {
            val label = source.label + when (source.lang) {
                "tra" -> " · Altyazılı"
                "trd" -> " · Dublaj"
                else -> ""
            }
            val url = absolute(source.url)
            when {
                url.contains("/biplayer/embed2.php") -> vidmoly(url, data, label, seen, emit)
                url.contains("/biplayer/vid/") -> player(url, data, label, seen, subtitleFilter, emit)
                else -> runCatching { loadExtractor(url, data, subtitleFilter, emit) }
            }
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir kaynak yok")
        return true
    }

    /** Kaynak düğmeleri: `<span id="sourceN" dil=..>Ad</span>` + ardından gelen script'teki iframe. */
    private fun sources(html: String): List<Source> {
        val labeled = SOURCE_RX.findAll(html).map {
            Source(it.groupValues[3], it.groupValues[2].trim(), it.groupValues[1])
        }.toList()
        if (labeled.isNotEmpty()) return labeled.distinctBy { it.url }
        return IFRAME_RX.findAll(html).map { Source(it.groupValues[1], "Kaynak", "") }
            .filter { it.url.contains("/biplayer/") }.distinctBy { it.url }.toList()
    }

    /** /biplayer/vid/<hash> zinciri: drive → popcornvakti (AES) veya ok.ru gibi dış gömülü oynatıcı. */
    private suspend fun player(
        embed: String,
        detail: String,
        label: String,
        seen: MutableSet<String>,
        subtitleFilter: (SubtitleFile) -> Unit,
        emit: (ExtractorLink) -> Unit,
    ) {
        var current = embed
        repeat(3) {
            val html = runCatching { app.get(current, headers = browserHeaders, referer = detail).text }.getOrNull() ?: return
            val next = iframeSrc(html)?.let { absolute(it) } ?: return
            if (next.contains("hata.php")) return
            when {
                next.contains("popcornvakti") -> {
                    popcorn(next, current, label, seen, emit)
                    return
                }
                next.contains("/biplayer/") -> current = next
                else -> {
                    runCatching { loadExtractor(next, current, subtitleFilter, emit) }
                    return
                }
            }
        }
    }

    private suspend fun popcorn(
        embed: String,
        referer: String,
        label: String,
        seen: MutableSet<String>,
        emit: (ExtractorLink) -> Unit,
    ) {
        val html = runCatching { app.get(embed, headers = browserHeaders, referer = referer).text }.getOrNull() ?: return
        val decrypted = decryptPayload(html) ?: return
        val stream = FILE_RX.find(decrypted)?.groupValues?.get(1)?.takeIf { it.startsWith("http") } ?: return
        emitStream(stream, label, embed, seen, emit)
    }

    /** /biplayer/embed2.php → vidmo → vidmoly.biz embed'i, jwplayer `sources` m3u8'i. */
    private suspend fun vidmoly(
        embed: String,
        detail: String,
        label: String,
        seen: MutableSet<String>,
        emit: (ExtractorLink) -> Unit,
    ) {
        val mid = runCatching { app.get(embed, headers = browserHeaders, referer = detail).text }.getOrNull()
            ?.let { iframeSrc(it) }?.let { absolute(it) } ?: return
        val pageUrl = runCatching { app.get(mid, headers = browserHeaders, referer = embed).text }.getOrNull()
            ?.let { iframeSrc(it) }?.let { absolute(it) } ?: return
        val page = runCatching { app.get(pageUrl, headers = browserHeaders, referer = mid).text }.getOrNull() ?: return
        val stream = VIDMOLY_SRC_RX.find(page)?.groupValues?.get(1) ?: return
        emitStream(stream, label, pageUrl, seen, emit)
    }

    private suspend fun emitStream(
        stream: String,
        label: String,
        referer: String,
        seen: MutableSet<String>,
        emit: (ExtractorLink) -> Unit,
    ) {
        val playlist = runCatching { app.get(stream, headers = browserHeaders, referer = referer).text }.getOrNull()
        val variants = playlist?.let { parseHlsVariants(stream, it) }.orEmpty()
        if (variants.isEmpty()) {
            if (seen.add(stream)) emit.invoke(newExtractorLink(name, label, stream, ExtractorLinkType.M3U8) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
            })
            return
        }
        for ((height, variantUrl) in variants) {
            if (!seen.add(variantUrl)) continue
            emit.invoke(newExtractorLink(name, "$label ${height}p", variantUrl, ExtractorLinkType.M3U8) {
                this.referer = referer
                this.quality = height
            })
        }
    }

    /** CryptoJS AES (OpenSSL "Salted__" + EVP_BytesToKey/MD5); parola sayfadaki ikinci argüman. */
    private fun decryptPayload(html: String): String? {
        val match = AES_RX.find(html) ?: return null
        val raw = runCatching { Base64.getDecoder().decode(match.groupValues[1]) }.getOrNull() ?: return null
        if (raw.size < 32 || !String(raw, 0, 8, Charsets.ISO_8859_1).startsWith("Salted__")) return null
        val salt = raw.copyOfRange(8, 16)
        val password = match.groupValues[2]
        val pw = password.toByteArray(Charsets.UTF_8)
        fun digest(vararg parts: ByteArray): ByteArray =
            MessageDigest.getInstance("MD5").apply { parts.forEach(::update) }.digest()
        val d1 = digest(pw, salt)
        val d2 = digest(d1, pw, salt)
        val iv = digest(d2, pw, salt)
        return runCatching {
            evpMd5AesCbcDecrypt(raw.copyOfRange(16, raw.size), password, salt.toHex(), iv.toHex())
        }.getOrNull()
    }

    private fun iframeSrc(html: String): String? = IFRAME_RX.find(html)?.groupValues?.get(1)

    private fun absolute(url: String): String =
        if (url.startsWith("http")) url else "$mainUrl/${url.trimStart('/')}"

    private fun ByteArray.toHex(): String =
        joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1) }

    private data class Source(val url: String, val label: String, val lang: String)

    companion object {
        val ROWS = listOf(
            "Bu Ay En Çok İzlenenler", "Son Eklenenler", "En Çok İzlenenler", "En Çok Beğenilenler",
            "IMDb En İyiler", "Türkçe Dublaj", "Türkçe Altyazılı", "Aksiyon", "Korku", "Komedi",
            "Bilim Kurgu", "Dram", "2026", "2025",
        )

        private const val BASE = "https://www.bicaps.live"
        private const val CARD = "div.movie-box"

        private fun rowUrl(row: String): String? = when (row) {
            "Bu Ay En Çok İzlenenler" -> "$BASE/bu-ay-en-cok-izlenen-filmler-izle"
            "Son Eklenenler" -> "$BASE/film-arsivi"
            "En Çok İzlenenler" -> "$BASE/en-cok-izlenen-filmler-izle"
            "En Çok Beğenilenler" -> "$BASE/en-cok-begenilen-filmler-izle"
            "IMDb En İyiler" -> "$BASE/imdb-en-iyiler-izle"
            "Türkçe Dublaj" -> "$BASE/turkce-dublaj-filmler-izle"
            "Türkçe Altyazılı" -> "$BASE/turkce-altyazili-filmler-izle"
            "Aksiyon" -> "$BASE/category/aksiyon-izle"
            "Korku" -> "$BASE/category/korku"
            "Komedi" -> "$BASE/category/komedi"
            "Bilim Kurgu" -> "$BASE/category/bilim-kurgu"
            "Dram" -> "$BASE/category/dram"
            "2026" -> "$BASE/yil/2026"
            "2025" -> "$BASE/yil/2025"
            else -> null
        }

        private val SOURCE_RX = Regex(
            """<span id="source\d+"[^>]*\bdil="(\w+)"[^>]*>([^<]*)</span>\s*<script>.*?<iframe[^>]*src=['"]([^'"]+)""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val IFRAME_RX = Regex("""<iframe[^>]*\bsrc=['"]([^'"]+)""")
        private val AES_RX = Regex("""AES\.decrypt\("([^"]+)"\s*,\s*"([^"]+)"\)""")
        private val FILE_RX = Regex("""\bfile\s*:\s*'([^']+)'""")
        private val VIDMOLY_SRC_RX = Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*'([^']+)'""")
    }
}
