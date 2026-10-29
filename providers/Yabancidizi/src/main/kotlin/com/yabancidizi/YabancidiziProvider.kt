package com.yabancidizi

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
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
import org.jsoup.nodes.Document
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Yabancıdizi — yabancidizi.live */
class YabancidiziProvider : MainAPI() {
    override var mainUrl = "https://yabancidizi.live"
    override var name = "Yabancıdizi"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/diziler?sort=date_desc" to "Son Eklenen Diziler",
        "$mainUrl/filmler?sort=date_desc" to "Son Eklenen Filmler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("${request.data}&page=$page").document
        // Beyond the last page the site repeats the last one, so bound the count with nav.mpg.
        val lastPage = doc.select("nav.mpg a[href]").mapNotNull { a ->
            Regex("""[?&]page=(\d+)""").find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull() ?: page
        return newHomePageResponse(request.name, doc.cards(), hasNext = page < lastPage)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        app.get("$mainUrl/arama?q=${java.net.URLEncoder.encode(query, "UTF-8")}").document.cards()

    /** Card anchors are shared by home, listings and search results. */
    private fun Document.cards(): List<SearchResponse> =
        select("a.movie-link[href]").mapNotNull { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val isMovie = href.contains("/film/")
            if (!isMovie && !href.contains("/dizi/")) return@mapNotNull null
            val title = a.selectFirst("h4")?.text()?.trim()
                ?: a.selectFirst("img")?.attr("alt")?.trim()
                ?: return@mapNotNull null
            val poster = fixUrlNull(a.selectFirst("img")?.attr("src"))
            if (isMovie) {
                newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
            } else {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
            }
        }.distinctBy { it.url }

    /** JSON-LD @graph node (TVSeries/Movie) — present on every detail page. */
    private fun Document.jsonLdNode(): JSONObject? {
        val json = selectFirst("script[type='application/ld+json']")?.data() ?: return null
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val graph = obj.optJSONArray("@graph") ?: return obj
        return (0 until graph.length())
            .mapNotNull { graph.optJSONObject(it) }
            .firstOrNull { it.optString("@type") in setOf("TVSeries", "Movie") }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val ld = doc.jsonLdNode()
        val title = ld?.optString("name")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("h1")?.text()?.trim()?.substringBefore(" Türkçe")?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val poster = fixUrlNull(ld?.optString("image"))
            ?: fixUrlNull(doc.selectFirst("meta[property='og:image']")?.attr("content"))
        val plot = ld?.optString("description")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[name='description']")?.attr("content")
        val year = (ld?.optString("startDate")?.takeIf { it.isNotBlank() }
            ?: ld?.optString("datePublished"))?.take(4)?.toIntOrNull()
        val tags = ld?.optJSONArray("genre")
            ?.let { g -> (0 until g.length()).map { g.optString(it) } }
            .orEmpty()
        val score = Score.from10(
            Regex("""IMDb\s*([\d.]+)""")
                .find(doc.selectFirst(".watchParams__paramsList")?.text().orEmpty())
                ?.groupValues?.get(1)
                ?: doc.selectFirst(".rating-box")?.text()?.trim()
        )
        // Film JSON-LD carries e.g. "duration":"PT92M"; series pages have no runtime.
        val duration = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?""")
            .find(ld?.optString("duration").orEmpty())?.let {
                (it.groupValues[1].toIntOrNull() ?: 0) * 60 + (it.groupValues[2].toIntOrNull() ?: 0)
            }?.takeIf { it > 0 }

        if (url.contains("/film/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = score
                this.duration = duration
            }
        }

        val episodes = doc.select("a.episode-link[href]").mapNotNull { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val m = Regex("""/sezon-(\d+)/bolum-(\d+)""").find(href) ?: return@mapNotNull null
            val season = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val number = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            newEpisode(href) {
                this.name = "Bölüm $number"
                this.season = season
                this.episode = number
            }
        }.distinctBy { it.data }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
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
        val doc = runCatching { app.get(data).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: kaynak bulunamadı. $REPORT_HINT")
        val players = doc.select("iframe#player-iframe, [data-frame]").mapNotNull { el ->
            val url = el.attr("data-frame")
                .ifBlank { el.attr("data-src") }
                .ifBlank { el.attr("src") }
            url.takeIf { it.startsWith("http") }
        }.distinct()

        var found = false
        val seenLinks = HashSet<String>()
        val seenSubtitles = HashSet<String>()
        players.forEach { player ->
            runCatching {
                if (player.contains("/player/")) {
                    if (resolveObfuscatedPlayer(player, data, linkCallback, subtitleFilter, seenLinks, seenSubtitles)) {
                        found = true
                    }
                } else if (loadExtractor(player, data, subtitleFilter, linkCallback)) {
                    found = true
                }
            }
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir akış yok. $REPORT_HINT")
        return found
    }

    /**
     * The player page ships a reversed-base64 payload that is AES-CBC encrypted with
     * CryptoJS' OpenSSL KDF (MD5) and padded with junk tokens; the decrypted HTML
     * holds the master.m3u8 in its `file:` property.
     */
    private suspend fun resolveObfuscatedPlayer(
        playerUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit,
        seenLinks: MutableSet<String>,
        seenSubtitles: MutableSet<String>,
    ): Boolean {
        val html = app.get(playerUrl, referer = referer).text
        val decoder = Regex("""var\s+(_\w+)\s*=\s*function\s*\(\s*s\s*\)\s*\{\s*return\s+atob\(\s*s\.split\(\s*''\s*\)\.reverse\(\)\.join\(\s*''\s*\)\s*\)\s*\}""")
            .find(html)?.groupValues?.get(1) ?: return false
        val parts = Regex(Regex.escape(decoder) + """\("([^"]*)"\)""")
            .findAll(html).map { it.groupValues[1] }.filter { it.isNotEmpty() }.toList()
        if (parts.size < 2) return false

        val junk = Regex("""\[\s*((?:"[^"]*"\s*,?\s*)+)\]\s*\.join\(\s*''\s*\)""")
            .find(html)?.groupValues?.get(1)
            ?.let { arr -> Regex("\"([^\"]*)\"").findAll(arr).joinToString("") { it.groupValues[1] } }
            .orEmpty()

        // atob(reverse(literal)) yields base64 text, which CryptoJS decodes again.
        val payload = decodeB64(parts[0].reversed()).toString(Charsets.UTF_8)
        val blob = decodeB64(payload)
        if (blob.size < 17 || !blob.copyOfRange(0, 8).contentEquals("Salted__".toByteArray())) return false

        val plain = openSslDecrypt(decodeB64(parts[1].reversed()), blob).replace(junk, "")
        val stream = Regex("""file\s*:\s*"([^"]+)"""").find(plain)?.groupValues?.get(1) ?: return false
        val base = java.net.URI(stream)

        // The master playlist carries every quality variant and both subtitle tracks; emit them all.
        val playlist = runCatching { app.get(stream, referer = playerUrl).text }.getOrNull()
        var found = false
        val variants = playlist?.lines()?.zipWithNext()?.mapNotNull { (line, next) ->
            if (!line.startsWith("#EXT-X-STREAM-INF") || next.startsWith("#")) return@mapNotNull null
            val height = Regex("""RESOLUTION=\d+x(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            runCatching { base.resolve(next.trim()).toString() }.getOrNull()?.let { it to height }
        }.orEmpty()

        for ((variant, height) in variants.distinctBy { it.first }) {
            if (!seenLinks.add(variant)) continue
            callback.invoke(
                newExtractorLink(this.name, "${this.name} ${height}p", variant, ExtractorLinkType.M3U8) {
                    this.referer = playerUrl
                    this.quality = height
                }
            )
            found = true
        }
        if (!found && seenLinks.add(stream)) {
            callback.invoke(
                newExtractorLink(name, name, stream, ExtractorLinkType.M3U8) {
                    this.referer = playerUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            found = true
        }

        val subtitleTracks = playlist?.lines()?.mapNotNull { line ->
            if (!line.startsWith("#EXT-X-MEDIA") || !line.contains("TYPE=SUBTITLES")) return@mapNotNull null
            val uri = Regex("""URI="([^"]+)"""").find(line)?.groupValues?.get(1) ?: return@mapNotNull null
            val language = Regex("""LANGUAGE="([^"]+)"""").find(line)?.groupValues?.get(1) ?: return@mapNotNull null
            runCatching { base.resolve(uri).toString() }.getOrNull()?.let { it to language }
        }.orEmpty()

        for ((subPlaylist, language) in subtitleTracks) {
            // Each sub playlist holds one VTT file; point the subtitle at it directly.
            val vtt = runCatching {
                app.get(subPlaylist, referer = playerUrl).text.lines()
                    .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
            }.getOrNull() ?: continue
            val subUrl = runCatching { java.net.URI(subPlaylist).resolve(vtt.trim()).toString() }.getOrNull()
                ?: continue
            if (!seenSubtitles.add(subUrl)) continue
            subtitleCallback.invoke(
                newSubtitleFile(language, subUrl) {
                    this.headers = mapOf("Referer" to playerUrl)
                }
            )
        }
        return found
    }

    /** Android's base64 decoder rejects unpadded input, and `atob` input is reversed. */
    private fun decodeB64(value: String): ByteArray =
        android.util.Base64.decode(
            value + "=".repeat((4 - value.length % 4) % 4),
            android.util.Base64.DEFAULT,
        )

    /** CryptoJS.AES.decrypt(passphrase, ciphertext): EVP_BytesToKey(MD5) + AES-256-CBC. */
    private fun openSslDecrypt(passphrase: ByteArray, blob: ByteArray): String {
        val salt = blob.copyOfRange(8, 16)
        val md = MessageDigest.getInstance("MD5")
        val derived = ByteArrayOutputStream()
        var block = ByteArray(0)
        while (derived.size() < 48) {
            md.reset()
            md.update(block)
            md.update(passphrase)
            md.update(salt)
            block = md.digest()
            derived.write(block)
        }
        val keys = derived.toByteArray()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keys.copyOfRange(0, 32), "AES"),
            IvParameterSpec(keys.copyOfRange(32, 48)),
        )
        return cipher.doFinal(blob.copyOfRange(16, blob.size)).toString(Charsets.UTF_8)
    }

    companion object {
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}
