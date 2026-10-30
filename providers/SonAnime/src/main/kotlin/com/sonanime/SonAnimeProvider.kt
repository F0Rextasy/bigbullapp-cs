package com.sonanime

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

class SonAnimeProvider : MainAPI() {
    private val uaHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9",
    )

    override var mainUrl = "https://sonanime.com"
    override var name = "SonAnime"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val hasMainPage = true
    override val mainPage get() = ROWS.mapNotNull { rowName ->
        if (!Settings.rowEnabled(rowName)) return@mapNotNull null
        MainPageData(rowName, when (rowName) {
            "Son Eklenenler" -> "https://api.sonanime.com/api/anime/home/random?type=movie&limit=12"
            else -> mainUrl
        })
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

            val out = mutableListOf<SearchResponse>()
            var hasNext = false
            when (request.name) {

        "Son Eklenenler" -> {
            val url = request.data.replace("{page}", page.toString())
            val raw = app.get(url, headers = uaHeaders).text
            val arr = if ("".isEmpty()) {
                JSONArray(raw)
            } else {
                JSONObject(raw).optJSONArray("") ?: JSONArray()
            }
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val title = o.optString("anime_name").trim()
                val slug = o.optString("anime_link").trim()
                if (title.isBlank() || slug.isBlank()) continue
                val href = if (slug.startsWith("http")) slug else "$mainUrl/anime/" + slug.trimStart('/')
                out += newMovieSearchResponse(title, href, TvType.Movie) {
                    posterUrl = fixUrlNull(o.optString("anime_photo").takeIf { it.startsWith("http") })
                    year = o.optInt("").takeIf { it > 1900 }
                    score = Score.from10(o.optString(""))
                }
            }
            hasNext = page < 200
        }
                else -> Unit
            }
            return newHomePageResponse(request.name, out, hasNext)
    }

    companion object {
        val ROWS = listOf("Son Eklenenler")
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = if (false) asciiFold(query) else query
        val url = "https://api.sonanime.com/api/anime/search?q={q}".replace("{q}", java.net.URLEncoder.encode(q, "UTF-8"))
        val raw = app.get(url, headers = uaHeaders).text
        val root = if (raw.trimStart().startsWith("[")) JSONObject() else JSONObject(raw)
        val out = mutableListOf<SearchResponse>()

        run {
            val arr = if ("".isEmpty()) JSONArray(raw) else root.optJSONArray("") ?: return@run
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val title = o.optString("anime_name").trim()
                val slug = o.optString("anime_link").trim()
                if (title.isBlank() || slug.isBlank()) continue
                val href = if (slug.startsWith("http")) slug else "$mainUrl/anime/" + slug.trimStart('/')
                out += newMovieSearchResponse(title, href, TvType.Movie) {
                    posterUrl = fixUrlNull(o.optString("anime_photo").takeIf { it.startsWith("http") })
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
        if (listOf("/").none { path.startsWith(it) }) return@mapNotNull null
        
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
        val episodes = doc.select("a[href]").mapNotNull { anchor ->
            val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            val path = href.substringAfter(mainUrl).substringBefore('?')
            if (basePath.isNotBlank() && !path.startsWith(basePath) && !path.contains(slug)) return@mapNotNull null
            val match = Regex("""(?:bolum|episode)[-_/]?(\d+)""").find(path) ?: return@mapNotNull null
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
        val known = listOf<String>("https://cdn.plyr.io/static/blank.mp4", "https://sonanime.com/intro.mp4")
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
        val frames = resp.document.select("iframe[src]").mapNotNull { fixUrlNull(it.attr("src")) }.distinct()
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

}
