package com.deokwave

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

class DeokwaveProvider : MainAPI() {
    private val uaHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9",
    )

    override var mainUrl = "https://deokwave.com"
    override var name = "Deokwave"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val hasMainPage = true
    override val mainPage get() = ROWS.mapNotNull { rowName ->
        if (!Settings.rowEnabled(rowName)) return@mapNotNull null
        MainPageData(rowName, when (rowName) {
            "Animeler" -> "https://deokwave.com/animeler/"
            "Rastgele Anime" -> "https://deokwave.com/kesfet/"
            "İzlemeye Başla" -> "https://deokwave.com/anime/9A926A4/"
            "2 #2 Trend - Bu Hafta Bleach Animasyon" -> "https://deokwave.com/anime/4F4589C/"
            "Drama" -> "https://deokwave.com/kategoriler/?category=drama"
            "Seriyi İzle" -> "https://deokwave.com/anime/12FA456/"
            "Seri Sonbahar Firefly Wedding 0 2026" -> "https://deokwave.com/anime/F523D85/"
            "Seri Sonbahar VERTEX FORCE 0 2026" -> "https://deokwave.com/anime/9B514A3/"
            "Seri Sonbahar A Certain Dark Item 0 2026" -> "https://deokwave.com/anime/A242323/"
            "Seri Sonbahar Magical Explorer 0 2026" -> "https://deokwave.com/anime/DEC572A/"
            else -> mainUrl
        })
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

            if (request.name in listOf("Animeler", "Rastgele Anime", "İzlemeye Başla", "2 #2 Trend - Bu Hafta Bleach Animasyon", "Drama", "Seriyi İzle", "Seri Sonbahar Firefly Wedding 0 2026", "Seri Sonbahar VERTEX FORCE 0 2026", "Seri Sonbahar A Certain Dark Item 0 2026", "Seri Sonbahar Magical Explorer 0 2026")) {
                if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
                val doc = app.get(request.data, headers = uaHeaders).document
                return newHomePageResponse(request.name, cards(doc), hasNext = false)
            }
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
    }

    companion object {
        val ROWS = listOf("Animeler", "Rastgele Anime", "İzlemeye Başla", "2 #2 Trend - Bu Hafta Bleach Animasyon", "Drama", "Seriyi İzle", "Seri Sonbahar Firefly Wedding 0 2026", "Seri Sonbahar VERTEX FORCE 0 2026", "Seri Sonbahar A Certain Dark Item 0 2026", "Seri Sonbahar Magical Explorer 0 2026")
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = if (false) asciiFold(query) else query
        val url = "https://deokwave.com/search_api.php?q={q}".replace("{q}", java.net.URLEncoder.encode(q, "UTF-8"))
        val raw = app.get(url, headers = uaHeaders).text
        val root = if (raw.trimStart().startsWith("[")) JSONObject() else JSONObject(raw)
        val out = mutableListOf<SearchResponse>()

        run {
            val arr = if ("animes".isEmpty()) JSONArray(raw) else root.optJSONArray("animes") ?: return@run
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val title = o.optString("name_english").trim()
                val slug = o.optString("animeid").trim()
                if (title.isBlank() || slug.isBlank()) continue
                val href = if (slug.startsWith("http")) slug else "$mainUrl/anime/" + slug.trimStart('/')
                out += newMovieSearchResponse(title, href, TvType.Movie) {
                    posterUrl = fixUrlNull(o.optString("poster").takeIf { it.startsWith("http") })
                    year = o.optInt("").takeIf { it > 1900 }
                    score = Score.from10(o.optString(""))
                }
            }
        }
        return out.distinctBy { it.url }
    }

    private fun cards(doc: Document, query: String? = null): List<SearchResponse> = doc.select("a[href]").mapNotNull { card ->
        val href = fixUrlNull(card.attr("href")) ?: return@mapNotNull null
        val path = card.attr("href").substringAfter(mainUrl, card.attr("href")).substringBefore('?')
        if (listOf("/", "/anime/", "/watch/").none { path.startsWith(it) }) return@mapNotNull null
        
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
        val pageUrl = if (url.contains("?")) url else url.trimEnd('/') + "/"
        val resp = app.get(pageUrl, headers = uaHeaders)
        val doc = resp.document
        val raw = resp.text
        val title = cleanTitle(
            doc.selectFirst("h1")?.text()?.takeIf(String::isNotBlank)
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
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
            if (!Regex("""(?:bolum|episode)""", RegexOption.IGNORE_CASE).containsMatchIn(path)) return@mapNotNull null
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
        loadTokenFlow(data) { stream, label, referer -> emit(stream, label, referer) }
        val known = listOf<String>()
        for (u in known) emit(u, "Site", if (u.contains("rumble.com")) "https://rumble.com/" else data)
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
        val subs = Regex("""https?://[^"'\s<>]+?\.(?:vtt|srt)(?:\?[^"'\s<>]*)""").findAll(text)
        for (m in subs) {
            if (Settings.subtitleAllowed("tr")) subtitleCallback.invoke(SubtitleFile("tr", m.value))
        }
        if (!found && frames.isEmpty() && embeds.isEmpty() && media.none()) {
            throw ErrorLoadingException("$name: oynatılabilir akış yok")
        }
        return found
    }


    private suspend fun loadTokenFlow(
        data: String,
        emit: suspend (String, String, String) -> Unit,
    ) {
        val page = runCatching { app.get(data, headers = uaHeaders + mapOf("Referer" to data)).text }
            .getOrNull()?.replace("&quot;", "\"")?.replace("\\\"", "\"") ?: return
        val cfg = Regex("""([A-Za-z0-9_]{3,40})"\s*:\s*"([^"]{1,400})""")
            .findAll(page).associate { it.groupValues[1] to it.groupValues[2] }
        val qualities = listOf("1080", "720", "480")
        for (q in qualities) {
            if (!Settings.qualityAllowed(q.toIntOrNull() ?: 0)) continue
            val payload = JSONObject()
            payload.put("videoid", cfg["videoId"] ?: "")
            payload.put("quality", q)
            payload.put("sid", cfg["playbackSessionId"] ?: "")
            val headers = uaHeaders + mapOf(
                "Content-Type" to "application/json",
                "X-CSRF-Token" to (cfg["csrfToken"] ?: ""),
            )
            val body = runCatching {
                app.post(fixUrl("/api/v1/video/token/"), json = payload, headers = headers, referer = data).text
            }.getOrNull() ?: continue
            val token = runCatching { JSONObject(body).optString("token") }
                .getOrNull().orEmpty()
            if (token.isBlank()) continue
            var url = "https://sw2.deokwave.com/v/{videoid}/{quality}/?vt={token}"
        url = url.replace("{videoid}", cfg["videoId"] ?: "")
            url = url.replace("{quality}", q)
                .replace("{token}", java.net.URLEncoder.encode(token, "UTF-8"))
            emit(url, q + "p", data)
        }
    }

}
