package com.yabancidizi

import com.bigbull.common.pichiveSign
import com.bigbull.common.browserHeaders
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
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
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Yabancıdizi — ybncidizi.com (Next.js RSC + PBKDF2-SHA512/AES-CBC pichive player). */
class YabancidiziProvider : MainAPI() {
    override var mainUrl = "https://ybncidizi.com"
    private val uaHeaders = mapOf(
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0",
        "accept-language" to "tr-TR,tr;q=0.9",
    )
    override var name = "Yabancıdizi"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage get() = ROWS.mapNotNull { name ->
        if (!Settings.rowEnabled(name)) return@mapNotNull null
        when (name) {
            "Son Eklenen Diziler" -> MainPageData(name, "$mainUrl/dizi-izle?page=SAYFA")
            "Son Eklenen Filmler" -> MainPageData(name, "$mainUrl/film-izle?page=SAYFA")
            "Diziler — Aksiyon" -> MainPageData(name, "$mainUrl/dizi/tur/aksiyon?page=SAYFA")
            "Diziler — Bilim Kurgu" -> MainPageData(name, "$mainUrl/dizi/tur/bilim-kurgu?page=SAYFA")
            "Diziler — Dram" -> MainPageData(name, "$mainUrl/dizi/tur/dram?page=SAYFA")
            "Trend" -> MainPageData(name, "$mainUrl/trend")
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val paged = request.data.contains("SAYFA")
        if (!paged && page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val url = if (paged) request.data.replace("SAYFA", page.toString()) else request.data
        val doc = app.get(url, headers = uaHeaders).document
        val hasNext = paged && doc.selectFirst("a[href*='page=${page + 1}']") != null
        return newHomePageResponse(request.name, doc.cards(), hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        return app.get("$mainUrl/arama-yap?s=$encoded", headers = uaHeaders).document.cards()
    }

    private fun Document.cards(): List<SearchResponse> =
        select("a.tc[href], a.yd-rk[href]").mapNotNull { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val isMovie = href.contains("/film/")
            if (!isMovie && (!href.contains("/dizi/") || href.contains("/dizi/tur/") || href.contains("/sezon-"))) {
                return@mapNotNull null
            }
            val aria = a.attr("aria-label")
            val title = a.selectFirst("span.tt")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: aria.replace(RANK_PREFIX, "").substringBefore(",").trim().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val poster = fixUrlNull(a.selectFirst("img")?.attr("src"))
            val score = Score.from10(IMDB_RE.find(aria)?.groupValues?.get(1))
            if (isMovie) {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                    this.score = score
                }
            } else {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.score = score
                }
            }
        }.distinctBy { it.url }

    private fun Document.jsonLdNode(): JSONObject? =
        select("script[type='application/ld+json']")
            .mapNotNull { runCatching { JSONObject(it.data()) }.getOrNull() }
            .firstOrNull { it.optString("@type") in setOf("TVSeries", "Movie") }

    override suspend fun load(url: String): LoadResponse? {
        val response = runCatching { app.get(url, headers = uaHeaders) }.getOrNull()
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val doc = response.document
        val rsc = response.text.replace("\\\"", "\"")
        val ld = doc.jsonLdNode()

        val title = ld?.optString("name")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val poster = fixUrlNull(MJ_POSTER.find(rsc)?.groupValues?.get(1))
            ?: fixUrlNull(ld?.optString("image"))
            ?: fixUrlNull(doc.selectFirst("meta[property='og:image']")?.attr("content"))
        val plot = ld?.optString("description")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[name='description']")?.attr("content")
        val year = MJ_DATE.find(rsc)?.groupValues?.get(1)?.toIntOrNull()
            ?: (ld?.optString("startDate")?.ifBlank { ld.optString("dateCreated") })?.take(4)?.toIntOrNull()
        val score = ld?.optJSONObject("aggregateRating")?.optString("ratingValue")
            ?.toDoubleOrNull()?.let { Score.from10(it) }
        val duration = RUNTIME_MIN.find(rsc)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
        val tags = jsonArrayAfter(rsc, "\"mjGenres\":[")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("label")?.takeIf(String::isNotBlank) }
        }.orEmpty()
        val actors = jsonArrayAfter(rsc, "\"mjCast\":[")?.let { arr ->
            (0 until arr.length()).mapNotNull {
                arr.optJSONObject(it)?.optString("label")?.takeIf(String::isNotBlank)?.let { n -> ActorData(Actor(n)) }
            }
        }?.takeIf { it.isNotEmpty() }
        val recommendations = jsonArrayAfter(rsc, "\"similar\":[")?.let { arr ->
            (0 until arr.length()).mapNotNull { idx ->
                val item = arr.optJSONObject(idx) ?: return@mapNotNull null
                val rTitle = item.optString("t").ifBlank { item.optString("title") }.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                val rUrl = fixUrlNull(item.optString("href").ifBlank { item.optString("url") })
                    ?: return@mapNotNull null
                val rPoster = fixUrlNull(item.optString("img").ifBlank { item.optString("poster") })
                if (rUrl.contains("/film/")) {
                    newMovieSearchResponse(rTitle, rUrl, TvType.Movie) { this.posterUrl = rPoster }
                } else {
                    newTvSeriesSearchResponse(rTitle, rUrl, TvType.TvSeries) { this.posterUrl = rPoster }
                }
            }
        }?.takeIf { it.isNotEmpty() }

        if (url.contains("/film/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = score
                this.duration = duration
                this.actors = actors
                this.recommendations = recommendations
            }
        }

        val episodes = LinkedHashMap<String, Episode>()
        jsonArrayAfter(rsc, "\"seasons\":[")?.let { seasons ->
            for (s in 0 until seasons.length()) {
                val sObj = seasons.optJSONObject(s) ?: continue
                val seasonNo = sObj.optInt("no", s + 1)
                val epArr = sObj.optJSONArray("episodes") ?: continue
                for (e in 0 until epArr.length()) {
                    val epObj = epArr.optJSONObject(e) ?: continue
                    val epUrl = fixUrlNull(epObj.optString("href")) ?: continue
                    val epNo = epObj.optInt("n", e + 1)
                    val epTitle = epObj.optString("title").takeIf(String::isNotBlank) ?: "$epNo. Bölüm"
                    episodes.getOrPut(epUrl) {
                        newEpisode(epUrl) {
                            this.season = seasonNo
                            this.episode = epNo
                            this.name = epTitle
                        }
                    }
                }
            }
        }
        if (episodes.isEmpty()) {
            doc.select("a[href*='/sezon-'][href*='/bolum-']").forEach { a ->
                val href = fixUrlNull(a.attr("href")) ?: return@forEach
                val m = EP_RE.find(href) ?: return@forEach
                val sNo = m.groupValues[1].toIntOrNull() ?: return@forEach
                val eNo = m.groupValues[2].toIntOrNull() ?: return@forEach
                episodes.getOrPut(href) {
                    newEpisode(href) {
                        this.season = sNo
                        this.episode = eNo
                        this.name = "$eNo. Bölüm"
                    }
                }
            }
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes.values.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 })),
        ) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.score = score
            this.actors = actors
            this.recommendations = recommendations
        }
    }

    private data class SourceTab(val href: String, val label: String, val quality: String, val on: Boolean)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val linkCallback: (ExtractorLink) -> Unit = { if (Settings.qualityAllowed(it.quality)) callback(it) }
        val subtitleFilter: (SubtitleFile) -> Unit = { if (Settings.subtitleAllowed(it.lang)) subtitleCallback(it) }
        val firstRsc = runCatching { app.get(data, headers = uaHeaders).text }.getOrNull()?.replace("\\\"", "\"")
            ?: throw ErrorLoadingException("$name: kaynak bulunamadı. $REPORT_HINT")

        val tabs = jsonArrayAfter(firstRsc, "\"sources\":[")?.let { arr ->
            (0 until arr.length()).mapNotNull { idx ->
                val item = arr.optJSONObject(idx) ?: return@mapNotNull null
                val href = fixUrlNull(item.optString("href")) ?: return@mapNotNull null
                SourceTab(href, item.optString("label"), item.optString("quality"), item.optBoolean("on"))
            }
        }.orEmpty().ifEmpty { listOf(SourceTab(data, "", "", true)) }

        var found = false
        val seenLinks = HashSet<String>()
        val seenSubtitles = HashSet<String>()
        for (tab in tabs) {
            runCatching {
                val rsc = if (tab.on || tab.href == data) {
                    firstRsc
                } else {
                    app.get(tab.href, referer = data, headers = uaHeaders).text.replace("\\\"", "\"")
                }
                val iframeUrl = decryptIframeUrl(rsc) ?: return@runCatching
                if (resolvePichive(iframeUrl, tab, linkCallback, subtitleFilter, seenLinks, seenSubtitles)) {
                    found = true
                } else if (loadExtractor(iframeUrl, "$mainUrl/", subtitleFilter, linkCallback)) {
                    found = true
                }
            }
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir akış yok. $REPORT_HINT")
        return found
    }

    private fun decryptIframeUrl(rsc: String): String? {
        val encAt = rsc.indexOf("\"enc\":{")
        if (encAt < 0) return null
        val enc = jsonObjectAfter(rsc.substring(encAt), "\"enc\":") ?: return null
        val tail = rsc.substring(encAt, minOf(rsc.length, encAt + 1400))
        val password = KEY_RE.find(tail)?.groupValues?.get(1) ?: return null
        val ct = android.util.Base64.decode(enc.optString("ciphertext"), android.util.Base64.DEFAULT)
        val iv = hexToBytes(enc.optString("iv"))
        val salt = hexToBytes(enc.optString("salt"))
        if (ct.isEmpty() || iv.size != 16 || salt.isEmpty()) return null
        val key = pbkdf2HmacSha512(password.toByteArray(Charsets.UTF_8), salt, 999, 32)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val plain = cipher.doFinal(ct).toString(Charsets.UTF_8).trim()
        return when {
            plain.startsWith("//") -> "https:$plain"
            plain.startsWith("http") -> plain
            else -> null
        }
    }

    private suspend fun resolvePichive(
        playerUrl: String,
        tab: SourceTab,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit,
        seenLinks: MutableSet<String>,
        seenSubtitles: MutableSet<String>,
    ): Boolean {
        val html = listOf("$mainUrl/", "https://selcukflix.app/", "https://dizipal1595.com/")
            .firstNotNullOfOrNull { ref ->
                runCatching { app.get(playerUrl, referer = ref, headers = uaHeaders).text }
                    .getOrNull()?.takeIf { it.contains("window.NP_CONFIG") }
            } ?: return false
        val config = jsonObjectAfter(html, "window.NP_CONFIG") ?: return false
        val linkName = listOf(this.name, tab.label).filter { it.isNotBlank() }.joinToString(" · ")
        val quality = QUALITY_RE.find(tab.quality)?.value?.toIntOrNull() ?: Qualities.Unknown.value

        var found = false
        val masters = config.optJSONArray("sources") ?: return false
        for (i in 0 until masters.length()) {
            val master = masters.optJSONObject(i)?.optString("master")?.takeIf { it.startsWith("http") }?.let { pichiveSign(html, it) } ?: continue
            if (!seenLinks.add(master)) continue
            callback.invoke(
                newExtractorLink(this.name, linkName, master, ExtractorLinkType.M3U8) {
                    this.referer = playerUrl
                    this.quality = quality
                    this.headers = uaHeaders
                }
            )
            found = true
        }
        val subtitles = config.optJSONArray("subtitles")
        if (subtitles != null) {
            for (i in 0 until subtitles.length()) {
                val item = subtitles.optJSONObject(i) ?: continue
                val subUrl = item.optString("url").takeIf { it.startsWith("http") }?.let { pichiveSign(html, it) } ?: continue
                if (!seenSubtitles.add(subUrl)) continue
                val subLang = item.optString("label").ifBlank { "Türkçe" }
                subtitleCallback.invoke(
                    newSubtitleFile(subLang, subUrl) {
                        this.headers = uaHeaders + mapOf("Referer" to playerUrl)
                    }
                )
            }
        }
        return found
    }

    // Pure HmacSHA512 PBKDF2 so minSdk 21 works without SecretKeyFactory API-26 gate.
    private fun pbkdf2HmacSha512(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(password, "HmacSHA512"))
        val saltBlock = salt.copyOf(salt.size + 4)
        saltBlock[salt.size + 3] = 1
        var u = mac.doFinal(saltBlock)
        val t = u.copyOf()
        for (i in 2..iterations) {
            u = mac.doFinal(u)
            for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
        }
        return t.copyOf(dkLen)
    }

    private fun hexToBytes(hex: String): ByteArray {
        if (hex.length % 2 != 0) return ByteArray(0)
        return ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun jsonArrayAfter(text: String, marker: String): JSONArray? {
        var from = 0
        while (true) {
            val at = text.indexOf(marker, from)
            if (at < 0) return null
            val open = at + marker.length - 1
            val slice = sliceBalanced(text, open, '[', ']')
            if (slice != null) runCatching { JSONArray(slice) }.getOrNull()?.let { return it }
            from = open + 1
        }
    }

    private fun jsonObjectAfter(text: String, marker: String): JSONObject? {
        val at = text.indexOf(marker)
        if (at < 0) return null
        val open = text.indexOf('{', at)
        if (open < 0) return null
        val slice = sliceBalanced(text, open, '{', '}') ?: return null
        return runCatching { JSONObject(slice) }.getOrNull()
    }

    private fun sliceBalanced(text: String, open: Int, openChar: Char, closeChar: Char): String? {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in open until text.length) {
            val char = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                openChar -> depth++
                closeChar -> {
                    depth--
                    if (depth == 0) return text.substring(open, index + 1)
                }
            }
        }
        return null
    }

    companion object {
        val ROWS = listOf(
            "Son Eklenen Diziler",
            "Son Eklenen Filmler",
            "Diziler — Aksiyon",
            "Diziler — Bilim Kurgu",
            "Diziler — Dram",
            "Trend",
        )
        private val RANK_PREFIX = Regex("""^\d+\.\s*sırada,\s*""")
        private val IMDB_RE = Regex("""IMDb\s*([\d.]+)""")
        private val MJ_POSTER = Regex(""""mjPoster"\s*:\s*"([^"]+)"""")
        private val MJ_DATE = Regex(""""mjDate"\s*:\s*"(\d{4})""")
        private val RUNTIME_MIN = Regex(""""runtimeMin"\s*:\s*(\d+)""")
        private val EP_RE = Regex("""/sezon-(\d+)/bolum-(\d+)""")
        private val KEY_RE = Regex(""""k"\s*:\s*"([^"]+)"""")
        private val QUALITY_RE = Regex("""(\d{3,4})""")
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}
