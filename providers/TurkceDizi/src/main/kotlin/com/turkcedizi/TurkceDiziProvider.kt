package com.turkcedizi

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
import org.jsoup.nodes.Document

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

class TurkceDiziProvider : MainAPI() {
    private val uaHeaders get() = browserHeaders

    override var mainUrl = "https://turkcedizi.com"
    override var name = "TurkceDizi"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val hasMainPage = true
    override val mainPage get() = ROWS.mapNotNull { rowName ->
        if (!Settings.rowEnabled(rowName)) return@mapNotNull null
        MainPageData(rowName, when (rowName) {
            "Diziler" -> "https://turkcedizi.com/#diziler"
            "Filmler" -> "https://turkcedizi.com/filmler"
            "DİZİ Komedi Kulübü" -> "https://turkcedizi.com/komedi-kulubu-turkce-izle"
            else -> mainUrl
        })
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

            if (request.name in listOf("Diziler", "Filmler", "DİZİ Komedi Kulübü")) {
                if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
                val doc = app.get(request.data, headers = uaHeaders).document
                return newHomePageResponse(request.name, cards(doc), hasNext = false)
            }
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
    }

    companion object {
        val ROWS = listOf("Diziler", "Filmler", "DİZİ Komedi Kulübü")
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = if (false) asciiFold(query) else query
        val url = "https://turkcedizi.com/api/search?q={q}".replace("{q}", java.net.URLEncoder.encode(q, "UTF-8"))
        val raw = app.get(url, headers = uaHeaders).text
        val root = if (raw.trimStart().startsWith("[")) JSONObject() else JSONObject(raw)
        val out = mutableListOf<SearchResponse>()

        run {
            val arr = if ("".isEmpty()) JSONArray(raw) else root.optJSONArray("") ?: return@run
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val title = o.optString("title").trim()
                val slug = o.optString("slug").trim()
                if (title.isBlank() || slug.isBlank()) continue
                val href = if (slug.startsWith("http")) slug else "$mainUrl/film/" + slug.trimStart('/')
                out += newMovieSearchResponse(title, href, TvType.Movie) {
                    posterUrl = fixUrlNull(o.optString("").takeIf { it.startsWith("http") })
                    year = o.optInt("year").takeIf { it > 1900 }
                    score = Score.from10(o.optString("rating"))
                }
            }
        }
        return out.distinctBy { it.url }
    }

    private fun cards(doc: Document, query: String? = null): List<SearchResponse> = doc.select("a[href]").mapNotNull { card ->
        val href = fixUrlNull(card.attr("href")) ?: return@mapNotNull null
        val path = card.attr("href").substringAfter(mainUrl, card.attr("href")).substringBefore('?')
        if (listOf("/", "/film/").none { path.startsWith(it) }) return@mapNotNull null
        
        if (path.contains("login") || path.contains("uye-ol") || path.contains("/giris")) return@mapNotNull null
        val img = card.selectFirst("img") ?: return@mapNotNull null
        val title = card.attr("title").trim()
            .ifBlank { card.text().trim() }
            .ifBlank { img.attr("alt").trim() }
            .takeIf { it.isNotBlank() } ?: return@mapNotNull null
        if (query != null && !title.contains(query, ignoreCase = true) &&
            !href.contains(query, ignoreCase = true)) return@mapNotNull null
        val hrefLower = href.lowercase()
        val isSeries = hrefLower.contains("/dizi/") || hrefLower.contains("/seri/") ||
            hrefLower.contains("/anime/") || hrefLower.contains("/series/")
        if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = fixUrlNull(img.posterSrcLazy())
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = fixUrlNull(img.posterSrcLazy())
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
            if (basePath.isNotBlank() && !path.startsWith(basePath) &&
                !path.contains(slug) && !path.contains(slugBase)) return@mapNotNull null
            if (!Regex("""(?:bolum|episode|sezon|season)""", RegexOption.IGNORE_CASE).containsMatchIn(path)) return@mapNotNull null
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
            }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
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
        val resp = app.get(data, headers = uaHeaders)
        val text = resp.text.replace("\\/", "/").replace("\\\"", "\"")
        var found = false
        val seen = mutableSetOf<String>()
        suspend fun emit(stream: String, label: String, referer: String) {
            if (!stream.startsWith("http") || !seen.add(stream)) return
            val type = if (stream.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            val quality = getQualityFromName(label)
            if (!Settings.qualityAllowed(quality)) return
            callback.invoke(newExtractorLink(name, label, stream, type) {
                this.referer = referer
                this.quality = quality
            })
            found = true
        }
        val media = Regex("""https?://[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)""").findAll(text)
        for (m in media) emit(m.value, "Direct", data)
        val embeds = Regex(""""(?:embedUrl|contentUrl|embed_url|player_url)"\s*:\s*"([^"]+)""")
            .findAll(text).map { it.groupValues[1] }.toList()
        for (embed in embeds) {
            val absolute = fixUrl(embed)
            if (absolute == data) continue
            if (!absolute.contains("embed") && !absolute.contains(".mp4") && !absolute.contains(".m3u8")) continue
            if (absolute.matches(Regex(""".*\.(?:mp4|m3u8).*"""))) {
                emit(absolute, "Embed", data)
                continue
            }
            val inner = runCatching { app.get(absolute, headers = uaHeaders, referer = data).text }.getOrNull() ?: continue
            val innerMedia = Regex("""https?://[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)""").findAll(inner)
            for (m in innerMedia) emit(m.value, "Embed", absolute)
        }
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
                val innerText = inner.replace("\\/", "/")
                for (m in Regex("""https?://[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)""").findAll(innerText)) {
                    emit(m.value, "Embed", frame)
                }
                for (sub in Regex("""<iframe[^>]+(?:src|data-src)="([^"]+)""").findAll(innerText)) {
                    val subUrl = fixUrl(sub.groupValues[1])
                    if (loadExtractor(subUrl, frame, subtitleCallback, callback)) found = true
                }
            }
            if (loadExtractor(frame, "$mainUrl/", subtitleCallback, callback)) found = true
        }
        // turkcedizi şeması (Nuvio JS zincirinin Kotlin port'u):
        // sayfa -> iframeUrl -> "ters-base64 + OpenSSL AES" oynatıcı -> master.m3u8 (varyantlar + VTT)
        val playerUrl = Regex("""iframeUrl\\?"\s*:\s*\\?"([^"\s\\]+)""").find(text)?.groupValues?.get(1)
        if (playerUrl != null) {
            val url = if (playerUrl.startsWith("//")) "https:$playerUrl" else fixUrl(playerUrl)
            val playerHtml = runCatching { app.get(url, headers = uaHeaders, referer = data).text }.getOrNull()
            val dbg = System.getenv("TD_DEBUG") != null
            if (dbg) runCatching {
                java.io.File("C:/Users/Extasy/Desktop/cloudstream/research/_td_player_sample.html")
                    .apply { parentFile.mkdirs() }.writeText("#url=$url#ref=$data\n$playerHtml")
            }
            val plain = playerHtml?.let { runCatching { decodeTurkceDiziPlayer(it) }.fold({ it }, { if (dbg) "EX:" + it.message else null }) }
            if (dbg) runCatching {
                java.io.File("C:/Users/Extasy/Desktop/cloudstream/research/_td_decode_state.txt").writeText("htmlLen=${playerHtml?.length}\nplain=${plain?.take(360)}")
            }
            val master = plain?.takeIf { !it.startsWith("EX:") }
                ?.let { p -> Regex("""file\s*:\s*"([^"]+)""").find(p)?.groupValues?.get(1) }
            if (master != null) {
                emit(master, "master", url)
                val playlist = runCatching { app.get(master, headers = uaHeaders, referer = url).text }.getOrNull() ?: ""
                val lines = playlist.lines()
                var variants = 0
                for (i in lines.indices) {
                    if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue
                    val h = Regex("RESOLUTION=\\d+x(\\d+)").find(lines[i])?.groupValues?.get(1)
                    var j = i + 1
                    while (j < lines.size && (lines[j].isBlank() || lines[j].startsWith("#"))) j++
                    if (j < lines.size) {
                        emit(java.net.URI(master).resolve(lines[j].trim()).toString(), h?.plus("p") ?: "auto", url)
                        variants++
                    }
                }
                if (variants == 0 && playlist.startsWith("#EXTM3U")) emit(master, "master", url)
                for (s in Regex("#EXT-X-MEDIA:[^\\r\\n]*TYPE=SUBTITLES[^\\r\\n]*").findAll(playlist).take(3)) {
                    val uri = Regex("URI=\"([^\"]+)\"").find(s.value)?.groupValues?.get(1) ?: continue
                    val lang = Regex("LANGUAGE=\"([^\"]+)\"").find(s.value)?.groupValues?.get(1)?.lowercase() ?: "tr"
                    val up = java.net.URI(master).resolve(uri).toString()
                    val body = runCatching { app.get(up, headers = uaHeaders, referer = url).text }.getOrNull() ?: continue
                    val vtt = body.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: continue
                    if (Settings.subtitleAllowed(lang)) {
                        subtitleCallback.invoke(SubtitleFile(lang, java.net.URI(up).resolve(vtt.trim()).toString()))
                    }
                }
            }
        }
        val subs = Regex("""https?://[^"'\s<>]+?\.(?:vtt|srt)(?:\?[^"'\s<>]*)""").findAll(text)
        for (m in subs) {
            if (Settings.subtitleAllowed("tr")) subtitleCallback.invoke(SubtitleFile("tr", m.value))
        }
        if (!found && frames.isEmpty() && embeds.isEmpty() && media.none()) {
            throw ErrorLoadingException("$name: oynatılabilir akış yok")
        }
        return found
    }

    private fun jstr(s: String): ByteArray {
        // JS atob boşluk/ters eğik çizgiyi esnetir; android Base64 esnetmez, filtrele.
        val letters = s.filter { it.isLetterOrDigit() || it == '+' || it == '/' }.replace('-', '+').replace('_', '/')
        val pad = (4 - letters.length % 4) % 4
        return try {
            android.util.Base64.decode(letters + "=".repeat(pad), android.util.Base64.DEFAULT)
        } catch (_: Exception) {
            ByteArray(0)
        }
    }

    // CryptoJS.AES.decrypt(passphrase, blob) == EVP_BytesToKey(MD5) + AES-256-CBC + "Salted__"
    private fun decodeTurkceDiziPlayer(html: String): String? {
        val decoder = Regex(
            """var\s+(_\w+)\s*=\s*function\s*\(\s*s\s*\)\s*\{\s*return\s+atob\(\s*s\.split\(\s*''\s*\)\.reverse\(\)\.join\(\s*''\s*\)\s*\)\s*\}"""
        ).find(html)?.groupValues?.get(1) ?: return null
        val parts = Regex("$decoder\\(\"([^\"]*)\"\\)").findAll(html)
            .mapNotNull { it.groupValues?.get(1) }.toList()
        if (parts.size < 2) return null
        val payload = String(jstr(parts[0].reversed()), Charsets.UTF_8)
        val blob = jstr(payload)
        if (blob.size < 17 || String(blob, 0, 8, Charsets.UTF_8) != "Salted__") return null
        val passphrase = jstr(parts[1].reversed())
        val salt = blob.copyOfRange(8, 16)
        val md = java.security.MessageDigest.getInstance("MD5")
        var prev = ByteArray(0)
        val derived = ArrayList<Byte>(48)
        while (derived.size < 48) {
            md.reset(); md.update(prev); md.update(passphrase); md.update(salt)
            prev = md.digest()
            derived.addAll(prev.toList())
        }
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(derived.subList(0, 32).toByteArray(), "AES"),
            javax.crypto.spec.IvParameterSpec(derived.subList(32, 48).toByteArray()),
        )
        return String(cipher.doFinal(blob.copyOfRange(16, blob.size)), Charsets.UTF_8)
    }
}
