package com.turkcedizi

import com.bigbull.common.browserHeaders
import com.bigbull.common.parseHlsVariants
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
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document

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
            "Öne Çıkanlar" -> "$mainUrl/#featured"
            "Yeni Eklenenler" -> "$mainUrl/#new"
            "Diziler" -> "$mainUrl/#diziler"
            "Filmler" -> "$mainUrl/filmler"
            else -> mainUrl
        })
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.name !in ROWS || page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val url = request.data.substringBefore('#')
        val doc = app.get(url, headers = uaHeaders).document
        val scope = when (request.name) {
            "Öne Çıkanlar" -> doc.selectFirst("section[class*=featured]") ?: doc
            "Yeni Eklenenler" -> doc.selectFirst("section[class*=newReleases]") ?: doc
            "Diziler" -> doc.selectFirst("div[class*=movieGrid]") ?: doc
            "Filmler" -> doc.selectFirst("div[class*=movieGrid]") ?: doc
            else -> doc
        }
        return newHomePageResponse(request.name, cards(doc, scope = scope), hasNext = false)
    }

    companion object {
        val ROWS = listOf("Öne Çıkanlar", "Yeni Eklenenler", "Diziler", "Filmler")
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val arr = runCatching { JSONArray(app.get("$mainUrl/api/search?q=" + java.net.URLEncoder.encode(query, "UTF-8"), headers = uaHeaders).text) }
            .getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val title = o.optString("title").trim()
            val href = o.optString("href").takeIf { it.isNotBlank() }?.let { fixUrl(it) } ?: return@mapNotNull null
            if (title.isBlank()) return@mapNotNull null
            val poster = o.optString("posterUrl").takeIf { it.startsWith("http") }
            if (o.optString("type") == "series") newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster; year = o.optInt("year").takeIf { it > 1900 } }
            else newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster; year = o.optInt("year").takeIf { it > 1900 } }
        }
    }

    private fun cards(doc: Document, query: String? = null, scope: org.jsoup.nodes.Element = doc): List<SearchResponse> {
        val rscPosters = HashMap<String, String>()
        val unescaped = doc.html().replace("\\\"", "\"")
        Regex(""""href"\s*:\s*"(/[^"]+)"[\s\S]{0,180}?"src"\s*:\s*"(https?:[^"]+)"""")
            .findAll(unescaped)
            .forEach { m -> rscPosters[m.groupValues[1]] = m.groupValues[2] }
        return scope.select("a[href]").mapNotNull { card ->
            val rawHref = card.attr("href").trim()
            if (rawHref.startsWith("mailto:") || rawHref.startsWith("#") || rawHref.contains("?page=")) return@mapNotNull null
            val href = fixUrlNull(rawHref) ?: return@mapNotNull null
            val path = rawHref.substringAfter(mainUrl, rawHref).substringBefore('?').substringBefore('#')
            if (listOf("/", "/film/").none { path.startsWith(it) } || path.trim('/').length < 4 || path == "/filmler") return@mapNotNull null
            if (path.contains("login") || path.contains("uye-ol") || path.contains("/giris")) return@mapNotNull null
            val img = card.selectFirst("img")
            val poster = fixUrlNull(img?.posterSrcLazy()) ?: rscPosters[path] ?: return@mapNotNull null
            val title = card.attr("title").trim()
                .ifBlank { card.selectFirst("h2, h3, [class*=Title], [class*=title]")?.text()?.trim().orEmpty() }
                .ifBlank { img?.attr("alt")?.replace(Regex("""(?i)\s*(?:dizi|film)?\s*afişi$"""), "")?.trim().orEmpty() }
                .ifBlank { card.text().trim() }
                .takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (query != null && !title.contains(query, ignoreCase = true) &&
                !href.contains(query, ignoreCase = true)) return@mapNotNull null
            val hrefLower = href.lowercase()
            val isSeries = hrefLower.contains("dizi") || hrefLower.contains("/seri/") ||
                hrefLower.contains("/anime/") || hrefLower.contains("/series/") || (requestIsSeries(scope, rawHref))
            if (isSeries) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    posterUrl = poster
                }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    posterUrl = poster
                }
            }
        }.distinctBy { it.url }
    }

    private fun requestIsSeries(scope: org.jsoup.nodes.Element, rawHref: String): Boolean =
        scope.tagName() != "#root" && !rawHref.contains("film")

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
            val href = (fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null).substringBefore('#')
            val pathAndQuery = href.substringAfter(mainUrl)
            val path = href.substringAfter(mainUrl).substringBefore('?')
            if (basePath.isNotBlank() && !path.startsWith(basePath) &&
                !path.contains(slug) && !path.contains(slugBase)) return@mapNotNull null
            if (!Regex("""(?:bolum|episode)""", RegexOption.IGNORE_CASE).containsMatchIn(pathAndQuery)) return@mapNotNull null
            val seasonNum = Regex("""(?:sezon|season)[-_ /=]?(\d+)""", RegexOption.IGNORE_CASE).find(pathAndQuery)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val number = Regex("""(?:sezon|season)[-_ /=]?(\d+).*?(?:bolum|episode)[-_ /=]?(\d+)""", RegexOption.IGNORE_CASE)
                .find(pathAndQuery)?.groupValues?.getOrNull(2)?.toIntOrNull()
                ?: Regex("""(?:bolum|episode)[-_ /=]?(\d+)""", RegexOption.IGNORE_CASE).find(pathAndQuery)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""(\d+)[-_]bolum""", RegexOption.IGNORE_CASE).find(pathAndQuery)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            newEpisode(href) {
                this.season = seasonNum ?: 1
                this.name = anchor.text().trim().ifBlank { "Bölüm $number" }
                this.episode = number
            }
        }.distinctBy { it.data }.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
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
        suspend fun emit(stream: String, label: String, referer: String, explicitQuality: Int? = null) {
            if (!stream.startsWith("http") || !seen.add(stream)) return
            val isM3u8 = stream.contains(".m3u8")
            val type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            val quality = explicitQuality ?: if (isM3u8) Qualities.Unknown.value else getQualityFromName(label)
            if (!Settings.qualityAllowed(quality)) return
            callback.invoke(newExtractorLink(name, label, stream, type) {
                this.referer = referer
                this.quality = quality
            })
            found = true
        }
        val media = Regex("""https?://[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?""").findAll(text)
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
            val innerMedia = Regex("""https?://[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?""").findAll(inner)
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
            if (frame.contains("ok.ru") || frame.contains("odnoklassniki")) {
                if (loadOkRu(frame, seen, callback)) found = true
                continue
            }
            val inner = runCatching { app.get(frame, headers = uaHeaders, referer = data).text }.getOrNull()
            if (inner != null) {
                val innerText = inner.replace("\\/", "/")
                for (m in Regex("""https?://[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?""").findAll(innerText)) {
                    emit(m.value, "Embed", frame)
                }
                for (sub in Regex("""<iframe[^>]+(?:src|data-src)="([^"]+)""").findAll(innerText)) {
                    val subUrl = fixUrl(sub.groupValues[1])
                    if (loadExtractor(subUrl, frame, subtitleCallback, callback)) found = true
                }
            }
            if (loadExtractor(frame, "$mainUrl/", subtitleCallback, callback)) found = true
        }
        val playerUrls = Regex("""iframeUrl\\?"\s*:\s*\\?"([^"\s\\]+)""")
            .findAll(text).map { it.groupValues[1] }.distinct().toList()
        for (playerUrl in playerUrls) {
            val url = if (playerUrl.startsWith("//")) "https:$playerUrl" else fixUrl(playerUrl)
            if (url.contains("ok.ru") || url.contains("odnoklassniki")) {
                if (loadOkRu(url, seen, callback)) found = true
                continue
            }
            val playerHtml = runCatching { app.get(url, headers = uaHeaders, referer = data).text }.getOrNull()
            val plain = playerHtml?.let { runCatching { decodeTurkceDiziPlayer(it) }.getOrNull() }
            val master = plain?.let { p -> Regex("""file\s*:\s*"([^"]+)""").find(p)?.groupValues?.get(1) }
            if (master != null) {
                emit(master, "TurkceDizi", url, Qualities.Unknown.value)
                val playlist = runCatching { app.get(master, headers = uaHeaders, referer = url).text }.getOrNull() ?: ""
                for ((h, vUrl) in parseHlsVariants(master, playlist)) {
                    emit(vUrl, "TurkceDizi ${h}p", url, h)
                }
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
            } else if (loadExtractor(url, data, subtitleCallback, callback)) {
                found = true
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

    private suspend fun loadOkRu(embed: String, seen: MutableSet<String>, callback: (ExtractorLink) -> Unit): Boolean {
        val id = Regex("""videoembed/(\d+)""").find(embed)?.groupValues?.get(1) ?: return false
        val body = runCatching {
            app.post(
                "https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$id",
                data = emptyMap(),
                headers = mapOf("user-agent" to "Mozilla/5.0 (Linux; Android 13)", "Referer" to "https://ok.ru/"),
            ).text
        }.getOrNull() ?: return false
        val videos = runCatching { JSONObject(body).optJSONArray("videos") }.getOrNull() ?: return false
        var found = false
        for (i in 0 until videos.length()) {
            val item = videos.optJSONObject(i) ?: continue
            val stream = item.optString("url").takeIf { it.startsWith("http") } ?: continue
            if (!seen.add(stream)) continue
            val label = item.optString("name").ifBlank { "OkRu" }
            val q = when (label.lowercase()) {
                "mobile", "lowest" -> 240
                "low" -> 360
                "sd" -> 480
                "hd" -> 720
                "full" -> 1080
                else -> getQualityFromName(label.uppercase())
            }
            if (!Settings.qualityAllowed(q)) continue
            callback.invoke(
                newExtractorLink(name, "OkRu $label", stream, ExtractorLinkType.VIDEO) {
                    this.referer = "https://ok.ru/"
                    this.quality = q
                }
            )
            found = true
        }
        return found
    }

    private fun jstr(s: String): ByteArray {
        val letters = s.filter { it.isLetterOrDigit() || it == '+' || it == '/' }.replace('-', '+').replace('_', '/')
        val pad = (4 - letters.length % 4) % 4
        return try {
            android.util.Base64.decode(letters + "=".repeat(pad), android.util.Base64.DEFAULT)
        } catch (_: Exception) {
            ByteArray(0)
        }
    }

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
