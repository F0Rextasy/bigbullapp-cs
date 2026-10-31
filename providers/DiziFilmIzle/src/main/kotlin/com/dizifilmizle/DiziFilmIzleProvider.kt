package com.dizifilmizle

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
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.jsoup.nodes.Document

private fun org.jsoup.nodes.Element.posterSrcLazy(): String? =
    attr("data-src").takeIf { it.isNotBlank() } ?: attr("src")

private fun asciiFold(value: String): String = value
    .replace('ı', 'i').replace('İ', 'I')
    .replace('ş', 's').replace('Ş', 'S')
    .replace('ğ', 'g').replace('Ğ', 'G')
    .replace('ü', 'u').replace('Ü', 'U')
    .replace('ö', 'o').replace('Ö', 'O')
    .replace('ç', 'c').replace('Ç', 'C')

private fun cleanTitle(raw: String?): String = (raw ?: "")
    .replace(Regex("""\s*\|.*$"""), "")
    .replace(Regex("""\s*[-–]\s*(?:HDFilm|Film|Dizi|Full).*$""", RegexOption.IGNORE_CASE), "")
    .replace(Regex("""\s+izle\b.*$""", RegexOption.IGNORE_CASE), "")
    .replace(Regex("""\s*\(\d{4}\).*$"""), "")
    .replace(Regex("""\s+(?:Türkçe|Dublaj|Altyazılı)\b.*$""", RegexOption.IGNORE_CASE), "")
    .trim().trim('-', '–', '|', ',')

class DiziFilmIzleProvider : MainAPI() {
    private val uaHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9",
    )

    override var mainUrl = "https://dizifilmizle.to"
    override var name = "DiziFilmIzle"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val hasMainPage = true
    override val mainPage get() = ROWS.mapNotNull { rowName ->
        if (!Settings.rowEnabled(rowName)) return@mapNotNull null
        MainPageData(rowName, when (rowName) {
            "Diziler" -> "https://dizifilmizle.to/yabanci-dizi-izle"
            "Türkçe Dublaj" -> "https://dizifilmizle.to/turkce-dublaj-filmler"
            "Türkçe Altyazı" -> "https://dizifilmizle.to/turkce-altyazili-filmler"
            "Yerli Filmler" -> "https://dizifilmizle.to/ulke/turkiye-hd"
            "Seri Filmler" -> "https://dizifilmizle.to/seriler"
            "Tümünü Gör" -> "https://dizifilmizle.to/efsane-diziler"
            "Filmler" -> "https://dizifilmizle.to/"
            else -> mainUrl
        })
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

            if (request.name in listOf("Diziler", "Türkçe Dublaj", "Türkçe Altyazı", "Yerli Filmler", "Seri Filmler", "Tümünü Gör", "Filmler")) {
                if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
                val doc = app.get(request.data, headers = uaHeaders).document
                return newHomePageResponse(request.name, cards(doc), hasNext = false)
            }
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
    }

    companion object {
        val ROWS = listOf("Diziler", "Türkçe Dublaj", "Türkçe Altyazı", "Yerli Filmler", "Seri Filmler", "Tümünü Gör", "Filmler")
        private val BE_PLAYER = Regex("""bePlayer\('([^']+)'\s*,\s*'(\{.*?\})'""", RegexOption.DOT_MATCHES_ALL)
        private val MEDIA = Regex("""https?://[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)""")
        private val SUBS = Regex("""https?://[^"'\s<>]+?\.(?:vtt|srt)(?:\?[^"'\s<>]*)""")
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = runCatching {
            app.get("$mainUrl/api/search", params = mapOf("q" to query.trim()), headers = uaHeaders).text
        }.getOrNull() ?: return emptyList()
        val results = runCatching { JSONObject(response).optJSONArray("results") }.getOrNull()
            ?: return emptyList()
        return (0 until results.length()).mapNotNull { index ->
            val item = results.optJSONObject(index) ?: return@mapNotNull null
            val slug = item.optString("slug").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val title = item.optString("title").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val isSeries = item.optString("content_type") == "series"
            val href = if (isSeries) "$mainUrl/dizi/$slug" else "$mainUrl/film/$slug"
            if (isSeries) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = item.optString("poster_url").takeIf { it.startsWith("http") }
                    this.year = item.optInt("start_year").takeIf { it > 1900 }
                    this.score = Score.from10(item.optString("imdb_rating"))
                }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = item.optString("poster_url").takeIf { it.startsWith("http") }
                    this.year = item.optInt("start_year").takeIf { it > 1900 }
                    this.score = Score.from10(item.optString("imdb_rating"))
                }
            }
        }
    }

    private val pathPrefixes = listOf("/dizi/", "/film/", "/serifilm/")

    private fun cards(doc: Document, query: String? = null): List<SearchResponse> = doc.select("a[href]").mapNotNull { card ->
        val href = fixUrlNull(card.attr("href")) ?: return@mapNotNull null
        val path = card.attr("href").substringAfter(mainUrl, card.attr("href")).substringBefore('?')
        if (listOf("/dizi/", "/film/", "/serifilm/").none { path.startsWith(it) }) return@mapNotNull null
        
        if (path == "/" || path.contains("login") || path.contains("uye-ol") ||
            path.contains("/giris") || href.contains("?ara=") || href.contains("sirala=")) return@mapNotNull null
        val img = card.selectFirst("img")
        val title = card.attr("title").trim()
            .ifBlank { card.text().trim() }
            .ifBlank { img?.attr("alt")?.trim().orEmpty() }
            .takeIf { it.isNotBlank() } ?: return@mapNotNull null
        if (query != null && !title.contains(query, ignoreCase = true) &&
            !href.contains(query, ignoreCase = true)) return@mapNotNull null
        val hrefLower = href.lowercase()
        val isSeries = hrefLower.contains("/dizi/") || hrefLower.contains("/seri/") ||
            hrefLower.contains("/anime/") || hrefLower.contains("/series/")
        if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = fixUrlNull(img?.posterSrcLazy())
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = fixUrlNull(img?.posterSrcLazy())
            }
        }
    }.distinctBy { it.url }

    override suspend fun load(url: String): LoadResponse? {
        val resp = app.get(url, headers = uaHeaders)
        val doc = resp.document
        val raw = resp.text
        val title = cleanTitle(
            doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: doc.selectFirst("h1")?.text()
                ?: throw ErrorLoadingException("$name: başlık okunamadı")
        ).ifBlank { throw ErrorLoadingException("$name: başlık okunamadı") }
        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val genres = doc.select("[itemprop=genre], a[href*='/tur/'], a[href*='/kategori/']")
            .mapNotNull { it.attr("content").ifBlank { it.text() }.trim().takeIf(String::isNotBlank) }
            .distinct().take(8)
        val year = Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(raw)?.value?.toIntOrNull()
        val score = Score.from10(
            doc.selectFirst("[itemprop=ratingValue]")?.attr("content")
                ?: Regex(""""(?:imdbPuani|ratingValue|imdb)"\s*:\s*"?([0-9.]+)""").find(raw)?.groupValues?.get(1)
        )
        val basePath = url.substringAfter(mainUrl).substringBefore('?').trimEnd('/')
        val slug = basePath.trim('/').split('/').lastOrNull().orEmpty()
        val slugBase = slug.removeSuffix("-izle").removeSuffix("-film-izle")
        val episodes = doc.select("a[href]").mapNotNull { anchor ->
            val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            val path = href.substringAfter(mainUrl).substringBefore('?')
            if (!Regex("""(?:bolum|episode)[-_/]?\d+""", RegexOption.IGNORE_CASE).containsMatchIn(path)) return@mapNotNull null
            val number = Regex("""(?:sezon|season)[-_/]?(\d+).*?(?:bolum|episode)[-_/]?([0-9]*)""")
                .find(path)?.groupValues?.getOrNull(2)?.toIntOrNull()
                ?: Regex("""(?:bolum|episode)[-_/]?(\d+)""").find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""(\d+)[-_]bolum""").find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(href) {
                this.name = anchor.text().trim().ifBlank { "Bölüm $number" }
                this.episode = number
            }
        }.distinctBy { it.data }.sortedBy { it.episode ?: 0 }
        if (episodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = score
                this.backgroundPosterUrl = poster
                this.tags = genres.ifEmpty { null }
            }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.score = score
            this.backgroundPosterUrl = poster
            this.tags = genres.ifEmpty { null }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val resp = app.get(data, headers = uaHeaders)
        val text = resp.text.replace("\\\\/", "/").replace("\\/", "/").replace("\\\"", "\"")
        var found = false
        val seen = mutableSetOf<String>()
        suspend fun emit(stream: String, label: String, referer: String) {
            if (!stream.startsWith("http") || !seen.add(stream)) return
            val type = if (stream.contains(".m3u8") || referer.contains("vidmixi") ||
                referer.contains("dosyaload")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            val quality = getQualityFromName(label)
            if (!Settings.qualityAllowed(quality)) return
            callback.invoke(newExtractorLink(name, label, stream, type) {
                this.referer = referer
                this.quality = quality
            })
            found = true
        }

        val allEmbeds = Regex("""https?://[^"'\s<>]+/embed/[^"'\s<>]+""")
            .findAll(text).map { it.value }.filter { it != data }.toList()
        val currentEpisode = Regex("""/bolum-(\d+)""").find(data)?.groupValues?.get(1)?.toIntOrNull()
        val embeds = currentEpisode?.let { number -> allEmbeds.getOrNull(number - 1)?.let { setOf(it) } }
            ?: allEmbeds.toSet()
        for (embed in embeds) {
            val player = runCatching {
                app.get(embed, headers = uaHeaders, referer = data).text
            }.getOrNull() ?: continue
            decryptBePlayer(player)?.let { emit(it, "Vidmixi", embed) }
        }

        for (m in MEDIA.findAll(text)) emit(m.value, "Direct", data)
        val frames = resp.document.select("iframe").mapNotNull {
            val raw = it.attr("src").ifBlank { it.attr("data-src") }.ifBlank { it.attr("vdo-src") }
            fixUrlNull(raw)
        }.distinct()
        for (frame in frames) {
            if (frame.contains(".mp4") || frame.contains(".m3u8")) {
                emit(frame, "Embed", data)
                continue
            }
            val inner = runCatching { app.get(frame, headers = uaHeaders, referer = data).text }.getOrNull()
            if (inner != null) {
                for (m in MEDIA.findAll(inner.replace("\\/", "/"))) emit(m.value, "Embed", frame)
            }
            if (loadExtractor(frame, "$mainUrl/", subtitleCallback, callback)) found = true
        }

        for (m in SUBS.findAll(text)) {
            if (Settings.subtitleAllowed("tr")) subtitleCallback.invoke(SubtitleFile("tr", m.value))
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir akış yok")
        return true
    }

    private fun decryptBePlayer(html: String): String? {
        val match = BE_PLAYER.find(html) ?: return null
        val params = JSONObject(match.groupValues[2])
        val password = match.groupValues[1].toByteArray(Charsets.UTF_8)
        val salt = hex(params.optString("s"))
        val iv = hex(params.optString("iv"))
        val cipherText = Base64.getDecoder().decode(params.optString("ct"))
        val first = md5(password, salt)
        val second = md5(first, password, salt)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec((first + second).copyOf(32), "AES"), IvParameterSpec(iv))
        val json = JSONObject(String(cipher.doFinal(cipherText), Charsets.UTF_8))
        return json.optString("video_location").trim().takeIf { it.startsWith("http") }
    }

    private fun md5(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("MD5")
        parts.forEach { digest.update(it) }
        return digest.digest()
    }

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()


}
