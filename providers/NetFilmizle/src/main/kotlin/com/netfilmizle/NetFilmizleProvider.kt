package com.netfilmizle

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
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
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * NetFilmizle — netfilmizle.com WordPress film arşivi.
 *
 * Arama `/arama/?s=<sorgu>`; filmler kök `/slug/`. Sayfadaki `ilkpartkod`
 * base64 iframe'i çözülür; vidmixi `bePlayer` AES yapılandırması çözülüp HLS verilir.
 */
private fun Element.posterSrcLazy(): String? =
    attr("data-src").takeIf { it.isNotBlank() } ?: attr("src")

class NetFilmizleProvider : MainAPI() {
    override var mainUrl = "https://netfilmizle.com"
    override var name = "NetFilmizle"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie)

    private val headers = mapOf(
        "User-Agent" to UA,
        "Accept-Language" to "tr-TR,tr;q=0.9",
    )

    override val mainPage get() = mainPageOf(
        "$mainUrl/turkce-dublaj-filmler/" to "Türkçe Dublaj",
        "$mainUrl/turkce-altyazili-filmler/" to "Türkçe Altyazı",
        "$mainUrl/yerli-filmler/" to "Yerli",
        "$mainUrl/netflix-filmleri/" to "Netflix",
        "$mainUrl/aksiyon-filmleri-izle" to "Aksiyon",
        "$mainUrl/komedi-filmleri" to "Komedi",
        "$mainUrl/korku-filmleri" to "Korku",
    ).filter { Settings.rowEnabled(it.name) }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.trimEnd('/')}/page/$page"
        val doc = app.get(url, headers = headers).document
        val items = doc.cards()
        val hasNext = doc.selectFirst("a[href*='/page/${page + 1}']") != null
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (page > 1) return newSearchResponseList(emptyList(), false)
        val doc = app.get("$mainUrl/arama/", params = mapOf("s" to query.trim()), headers = headers).document
        return newSearchResponseList(doc.cards(), false)
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = fixUrl(url)
        val doc = runCatching { app.get(pageUrl, headers = headers).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: film sayfası yüklenemedi. $REPORT_HINT")
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?.replace(Regex("""\s+izle$""", RegexOption.IGNORE_CASE), "")
            ?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("$name: film başlığı okunamadı. $REPORT_HINT")
        val description = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?.takeIf { it.isNotEmpty() }
        val year = YEAR.find("$title $description")?.groupValues?.get(1)?.toIntOrNull()
        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val score = doc.selectFirst(".imdb")?.text()?.let {
            it.replace(',', '.').toDoubleOrNull()?.let { value -> Score.from10(value) }
        }
        return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.score = score
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageUrl = fixUrl(data)
        val html = runCatching { app.get(pageUrl, headers = headers).text }.getOrNull()
            ?: throw ErrorLoadingException("$name: film sayfası yüklenemedi. $REPORT_HINT")
        val encoded = IFRAME_CODE.find(html)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("$name: oynatıcı iframe'i bulunamadı. $REPORT_HINT")
        val decoded = runCatching {
            String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
        }.getOrNull().orEmpty()
        val embed = IFRAME_SRC.find(decoded)?.groupValues?.get(1)?.trim()
            ?: throw ErrorLoadingException("$name: oynatıcı adresi okunamadı. $REPORT_HINT")
        val embedHtml = runCatching {
            app.get(embed, headers = headers, referer = pageUrl).text
        }.getOrNull() ?: throw ErrorLoadingException("$name: oynatıcı yanıt vermedi. $REPORT_HINT")
        val player = BE_PLAYER.find(embedHtml)?.let { match ->
            decrypt(match.groupValues[1], JSONObject(match.groupValues[2]))
        } ?: throw ErrorLoadingException("$name: oynatıcı yapılandırması okunamadı. $REPORT_HINT")
        val stream = player.optString("video_location").trim()
        if (!stream.startsWith("http")) {
            throw ErrorLoadingException("$name: oynatılabilir akış yok. $REPORT_HINT")
        }
        val master = runCatching {
            app.get(stream, headers = headers, referer = embed).text
        }.getOrNull().orEmpty()
        val quality = MASTER_HEIGHT.findAll(master).mapNotNull { it.groupValues[1].toIntOrNull() }
            .maxOrNull() ?: Qualities.Unknown.value
        if (!Settings.qualityAllowed(quality)) {
            throw ErrorLoadingException("$name: akış kalite sınırının üstünde. $REPORT_HINT")
        }
        callback(newExtractorLink(name, name, stream, ExtractorLinkType.M3U8) {
            this.referer = embed
            this.headers = headers
            this.quality = quality
        })

        player.optJSONArray("strSubtitles")?.let { subs ->
            (0 until subs.length()).mapNotNull { subs.optJSONObject(it) }.forEach { sub ->
                val file = sub.optString("file").trim()
                if (!file.startsWith("/")) return@forEach
                val label = sub.optString("label").trim().ifEmpty { "Altyazı" }
                if (!Settings.subtitleAllowed(label)) return@forEach
                subtitleCallback(SubtitleFile(label, "https://vidmixi.com$file").apply {
                    this.headers = headers
                })
            }
        }
        return true
    }

    private fun decrypt(hash: String, params: JSONObject): JSONObject {
        val password = hash.toByteArray(Charsets.UTF_8)
        val salt = hex(params.optString("s"))
        val iv = hex(params.optString("iv"))
        val cipherText = Base64.getDecoder().decode(params.optString("ct"))
        val first = md5(password, salt)
        val second = md5(first, password, salt)
        val key = (first + second).copyOf(32)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return JSONObject(String(cipher.doFinal(cipherText), Charsets.UTF_8))
    }

    private fun md5(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("MD5")
        parts.forEach { digest.update(it) }
        return digest.digest()
    }

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun Document.cards(): List<SearchResponse> =
        select("a[href]").mapNotNull { card ->
            val href = fixUrlNull(card.attr("href")) ?: return@mapNotNull null
            if (!href.startsWith(mainUrl) || href.contains("/arama/")) return@mapNotNull null
            val path = href.removePrefix(mainUrl).substringBefore('?').trim('/')
            if (path.isBlank() || path.contains('/')) return@mapNotNull null
            val title = card.selectFirst("h2")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = fixUrlNull(card.selectFirst("img")?.posterSrcLazy())
                this.year = card.selectFirst(".year")?.text()?.trim()?.toIntOrNull()
                this.score = card.selectFirst(".imdb")?.text()?.let { value ->
                    value.replace(',', '.').toDoubleOrNull()?.let { Score.from10(it) }
                }
            }
        }.distinctBy { it.url }

    companion object {
        val ROWS = listOf("Türkçe Dublaj", "Türkçe Altyazı", "Yerli", "Netflix", "Aksiyon", "Komedi", "Korku")
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
        private val YEAR = Regex("""\b((?:19|20)\d{2})\b""")
        private val IFRAME_CODE = Regex("""ilkpartkod\s*=\s*'([^']+)'""")
        private val IFRAME_SRC = Regex("""src="([^"]+)"""")
        private val BE_PLAYER = Regex("""bePlayer\('([^']+)'\s*,\s*'(\{.*?\})'""", RegexOption.DOT_MATCHES_ALL)
        private val MASTER_HEIGHT = Regex("""RESOLUTION=\d+x(\d+)""")
    }
}
