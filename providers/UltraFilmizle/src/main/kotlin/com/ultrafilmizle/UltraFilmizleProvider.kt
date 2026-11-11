package com.ultrafilmizle

import com.bigbull.common.jsonLdDescription
import com.bigbull.common.browserHeaders
import com.bigbull.common.posterSrcLazy
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
import org.json.JSONArray
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * UltraFilmizle — ufilmizle.com film arşivi.
 *
 * Arama `/?s=<sorgu>`; filmler `/filmler9/<slug>/`. Sayfadaki `ilkpartkod`
 * base64 iframe'i çözülür; bepeak `bePlayer` AES yapılandırması çözülüp HLS verilir.
 */

class UltraFilmizleProvider : MainAPI() {
    override var mainUrl = "https://ufilmizle.com"
    override var name = "UltraFilmizle"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie)

    private val headers get() = browserHeaders
    // hotstream /list/ 404s unless Accept is exactly */* ; the player must send it too.
    private val streamHeaders get() = browserHeaders + ("Accept" to "*/*")

    override val mainPage get() = mainPageOf(
        "$mainUrl/" to "Filmler",
        "$mainUrl/kategori/aksiyon/" to "Aksiyon",
        "$mainUrl/kategori/aile/" to "Aile",
        "$mainUrl/kategori/animasyon/" to "Animasyon",
        "$mainUrl/kategori/belgesel/" to "Belgesel",
        "$mainUrl/kategori/bilim-kurgu/" to "Bilim Kurgu",
        "$mainUrl/kategori/dram/" to "Dram",
        "$mainUrl/kategori/fantastik/" to "Fantastik",
        "$mainUrl/kategori/gerilim/" to "Gerilim",
        "$mainUrl/kategori/gizem/" to "Gizem",
        "$mainUrl/kategori/komedi/" to "Komedi",
        "$mainUrl/kategori/korku/" to "Korku",
        "$mainUrl/kategori/macera/" to "Macera",
        "$mainUrl/kategori/romantik/" to "Romantik",
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
        // tema ?s= sorguyu yok sayıp "son eklenenler" döndürüyor; gerçek arama wp-json'da
        val items = runCatching {
            val arr = JSONArray(app.get("$mainUrl/wp-json/wp/v2/search", params = mapOf("search" to query.trim(), "per_page" to "20"), headers = headers).text)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val title = o.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val url = o.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                newMovieSearchResponse(title, url, TvType.Movie)
            }
        }.getOrNull().orEmpty()
        if (items.isNotEmpty()) return newSearchResponseList(items, false)
        val doc = app.get(mainUrl, params = mapOf("s" to query.trim()), headers = headers).document
        return newSearchResponseList(doc.cards().filter { it.name.contains(query.trim(), ignoreCase = true) }, false)
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = fixUrl(url)
        val doc = runCatching { app.get(pageUrl, headers = headers).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: film sayfası yüklenemedi. $REPORT_HINT")
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?.replace(Regex("""\s+izle$""", RegexOption.IGNORE_CASE), "")
            ?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("$name: film başlığı okunamadı. $REPORT_HINT")
        val description = doc.selectFirst("#film-aciklama")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.jsonLdDescription()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
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
        val embed = IFRAME_CODE.find(html)?.let { match ->
            val decoded = runCatching {
                String(Base64.getDecoder().decode(match.groupValues[1]), Charsets.UTF_8)
            }.getOrNull().orEmpty()
            IFRAME_SRC.find(decoded)?.groupValues?.get(1)?.trim()
        } ?: IFRAME_ANY.findAll(html).map { it.groupValues[1] }
            .firstOrNull { !it.contains("youtube") && !it.contains("facebook") }
            ?: throw ErrorLoadingException("$name: oynatıcı adresi okunamadı. $REPORT_HINT")
        val embedHtml = runCatching {
            app.get(embed, headers = headers, referer = pageUrl).text
        }.getOrNull() ?: throw ErrorLoadingException("$name: oynatıcı yanıt vermedi. $REPORT_HINT")
        val player = BE_PLAYER.find(embedHtml)?.let { match ->
            decrypt(match.groupValues[1], JSONObject(match.groupValues[2]))
        } ?: BE_CONFIG.find(embedHtml)?.let { match ->
            val rawConfig = match.groupValues[1]
                .replace("&quot;", "\"")
                .replace("&#34;", "\"")
                .replace("&amp;", "&")
            val cfg = JSONObject(rawConfig)
            val videoId = cfg.optString("videoId")
            val settingsRaw = cfg.optString("settings")
            if (videoId.isNotEmpty() && settingsRaw.isNotEmpty()) {
                decrypt(videoId, JSONObject(settingsRaw))
            } else null
        } ?: throw ErrorLoadingException("$name: oynatıcı yapılandırması okunamadı. $REPORT_HINT")
        val stream = player.optString("video_location").trim()
        if (!stream.startsWith("http")) {
            throw ErrorLoadingException("$name: oynatılabilir akış yok. $REPORT_HINT")
        }
        val master = runCatching {
            app.get(stream, headers = streamHeaders, referer = embed).text
        }.getOrNull().orEmpty()
        val quality = MASTER_HEIGHT.findAll(master).mapNotNull { it.groupValues[1].toIntOrNull() }
            .maxOrNull() ?: Qualities.Unknown.value
        if (!Settings.qualityAllowed(quality)) {
            throw ErrorLoadingException("$name: akış kalite sınırının üstünde. $REPORT_HINT")
        }
        callback(newExtractorLink(name, name, stream, ExtractorLinkType.M3U8) {
            this.referer = embed
            this.headers = streamHeaders
            this.quality = quality
        })

        player.optJSONArray("strSubtitles")?.let { subs ->
            (0 until subs.length()).mapNotNull { subs.optJSONObject(it) }.forEach { sub ->
                val file = sub.optString("file").trim()
                if (!file.startsWith("/")) return@forEach
                val label = sub.optString("label").trim().ifEmpty { "Altyazı" }
                if (!Settings.subtitleAllowed(label)) return@forEach
                val base = embed.substringBefore("/embed/")
                subtitleCallback(SubtitleFile(label, "$base$file").apply {
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
        select("div.movie-box").mapNotNull { box ->
            val link = box.selectFirst(".film-ismi a[href]") ?: box.selectFirst("a[href*='/filmler9/']") ?: return@mapNotNull null
            val href = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
            val img = box.selectFirst("img") ?: return@mapNotNull null
            val title = link.text().trim()
                .ifBlank { img.attr("alt").replace(Regex("""\s+izle$""", RegexOption.IGNORE_CASE), "").trim() }
                .takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = fixUrlNull(img.posterSrcLazy())
                this.year = YEAR.find(title)?.groupValues?.get(1)?.toIntOrNull()
                this.score = box.selectFirst(".bolum-ust, .imdb")?.text()?.let { value ->
                    Regex("""(\d+[.,]\d+)""").find(value)?.groupValues?.get(1)
                        ?.replace(',', '.')?.toDoubleOrNull()?.let { Score.from10(it) }
                }
            }
        }.distinctBy { it.url }

    companion object {
        val ROWS = listOf("Filmler", "Aksiyon", "Aile", "Animasyon", "Belgesel", "Bilim Kurgu", "Dram", "Fantastik", "Gerilim", "Gizem", "Komedi", "Korku", "Macera", "Romantik")
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
        private val YEAR = Regex("""\b((?:19|20)\d{2})\b""")
        private val IFRAME_CODE = Regex("""ilkpartkod\s*=\s*'([^']+)'""")
        private val IFRAME_SRC = Regex("""src="([^"]+)"""")
        private val IFRAME_ANY = Regex("""<iframe[^>]+src="([^"]+)"""", RegexOption.IGNORE_CASE)
        private val BE_PLAYER = Regex("""bePlayer\('([^']+)'\s*,\s*'(\{.*?\})'""", RegexOption.DOT_MATCHES_ALL)
        private val BE_CONFIG = Regex("""id="be-player-config"[^>]*data-config="([^"]+)"""")
        private val MASTER_HEIGHT = Regex("""RESOLUTION=\d+x(\d+)""")
    }
}
