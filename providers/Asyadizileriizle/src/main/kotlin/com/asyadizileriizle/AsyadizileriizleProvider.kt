package com.asyadizileriizle

import com.bigbull.common.jsonLdDescription
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
import org.json.JSONObject
import org.jsoup.nodes.Document

private fun cleanTitle(raw: String?): String = (raw ?: "")
    .replace(Regex("""\s*\|.*$"""), "")
    .replace(Regex("""\s*[-–]\s*(?:HDFilm|Film|Dizi|Full).*$""", RegexOption.IGNORE_CASE), "")
    .replace(Regex("""\s+izle\b.*$""", RegexOption.IGNORE_CASE), "")
    .replace(Regex("""\s*\(\d{4}\).*$"""), "")
    .replace(Regex("""\s+(?:Türkçe|Dublaj|Altyazılı)\b.*$""", RegexOption.IGNORE_CASE), "")
    .trim().trim('-', '–', '|', ',')

class AsyadizileriizleProvider : MainAPI() {
    private val uaHeaders get() = browserHeaders

    override var mainUrl = "https://asyadizileriizle.com"
    override var name = "Asyadizileriizle"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val hasMainPage = true
    override val mainPage get() = ROWS.mapNotNull { rowName ->
        if (!Settings.rowEnabled(rowName)) return@mapNotNull null
        MainPageData(rowName, when (rowName) {
            "Dizi Arşivi" -> "$mainUrl/dizi-arsivi/"
            "Son Bölümler" -> "$mainUrl/"
            else -> mainUrl
        })
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.name in ROWS) {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
            val doc = app.get(request.data, headers = uaHeaders).document
            val scoped = if (request.name == "Son Bölümler") {
                doc.clone().apply { select("div.series-box").remove() }
            } else {
                doc.clone().apply { select("div.episode-box").remove() }
            }
            return newHomePageResponse(request.name, cards(scoped), hasNext = false)
        }
        return newHomePageResponse(request.name, emptyList(), hasNext = false)
    }

    companion object {
        val ROWS = listOf("Dizi Arşivi", "Son Bölümler")
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "https://asyadizileriizle.com/?s=" + java.net.URLEncoder.encode(query, "UTF-8")
        return cards(app.get(url, headers = uaHeaders).document, query)
    }

    private fun cards(doc: Document, query: String? = null): List<SearchResponse> =
        doc.select("div.col-lg-8 a[href], div.episode-box a[href], div.movie-box a[href]").ifEmpty { doc.select("a[href]") }.mapNotNull { card ->
        val href = fixUrlNull(card.attr("href")) ?: return@mapNotNull null
        val path = card.attr("href").substringAfter(mainUrl, card.attr("href")).substringBefore('?')
        if (path == "/" || path.startsWith("/dizi-arsivi") || path.startsWith("/film-arsivi") ||
            path.startsWith("/kategori/") || path.startsWith("/tur/") || path.startsWith("/page/")) return@mapNotNull null

        if (path.contains("login") || path.contains("uye-ol") || path.contains("/giris")) return@mapNotNull null
        val img = card.selectFirst("img") ?: return@mapNotNull null
        val boxTitle = card.parents().firstOrNull { it.hasClass("movie-box") || it.hasClass("episode-box") }
            ?.selectFirst(".name, h2, h3")?.text()?.trim().orEmpty()
        val title = card.attr("title").trim()
            .ifBlank { boxTitle }
            .ifBlank { img.attr("alt").trim() }
            .ifBlank { card.text().trim() }
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
        val plot = doc.jsonLdDescription()
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
        val firstResp = app.get(data, headers = uaHeaders)
        val baseData = data.trimEnd('/')
        val partUrls = (listOf(data) + firstResp.document.select("a[href]").mapNotNull { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            if (href.trimEnd('/').matches(Regex(Regex.escape(baseData) + """/\d+"""))) href else null
        }).distinct().take(6)

        var found = false
        val seen = mutableSetOf<String>()
        suspend fun emit(stream: String, label: String, referer: String, explicitQuality: Int? = null) {
            if (!stream.startsWith("http") || !seen.add(stream)) return
            val isM3u8 = stream.contains(".m3u8")
            val type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            val quality = explicitQuality ?: if (isM3u8) Qualities.Unknown.value else getQualityFromName(label)
            if (Settings.qualityAllowed(quality)) {
                callback.invoke(newExtractorLink(name, label, stream, type) {
                    this.referer = referer
                    this.quality = quality
                })
                found = true
            }
            if (isM3u8 && explicitQuality == null) {
                val playlist = runCatching { app.get(stream, headers = uaHeaders, referer = referer).text }.getOrNull().orEmpty()
                for ((h, vUrl) in parseHlsVariants(stream, playlist)) {
                    emit(vUrl, "$label ${h}p", referer, h)
                }
            }
        }

        for (pageUrl in partUrls) {
            val resp = if (pageUrl == data) firstResp else runCatching { app.get(pageUrl, headers = uaHeaders, referer = data) }.getOrNull() ?: continue
            val text = resp.text.replace("\\/", "/").replace("\\\"", "\"")
            val media = Regex("""https?://[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?""").findAll(text)
            for (m in media) emit(m.value, "Direct", pageUrl)
            val embeds = Regex(""""(?:embedUrl|contentUrl|embed_url|player_url)"\s*:\s*"([^"]+)""")
                .findAll(text).map { it.groupValues[1] }.toList()
            for (embed in embeds) {
                val absolute = fixUrl(embed)
                if (absolute == pageUrl) continue
                if (!absolute.contains("embed") && !absolute.contains(".mp4") && !absolute.contains(".m3u8")) continue
                if (absolute.matches(Regex(""".*\.(?:mp4|m3u8).*"""))) {
                    emit(absolute, "Embed", pageUrl)
                    continue
                }
                val inner = runCatching { app.get(absolute, headers = uaHeaders, referer = pageUrl).text }.getOrNull() ?: continue
                val innerMedia = Regex("""https?://[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?""").findAll(inner)
                for (m in innerMedia) emit(m.value, "Embed", absolute)
            }
            val frames = resp.document.select("iframe").mapNotNull {
                val raw = it.attr("src").ifBlank { it.attr("data-src") }.ifBlank { it.attr("vdo-src") }
                fixUrlNull(raw)
            }.distinct()
            for (frame in frames) {
                if (!frame.startsWith("http") || frame.endsWith("/embed/") || frame.endsWith("/video/") ||
                    frame.contains("vzaar.com") || frame.contains("youtu")) continue
                if (frame.contains(".mp4") || frame.contains(".m3u8")) {
                    emit(frame, "Embed", pageUrl)
                    continue
                }
                if (frame.contains("ok.ru") || frame.contains("odnoklassniki")) {
                    if (loadOkRu(frame, seen, callback)) found = true
                    continue
                }
                val inner = runCatching { app.get(frame, headers = uaHeaders, referer = pageUrl).text }.getOrNull()
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
            val subs = Regex("""https?://[^"'\s<>]+?\.(?:vtt|srt)(?:\?[^"'\s<>]*)""").findAll(text)
            for (m in subs) {
                if (Settings.subtitleAllowed("tr")) subtitleCallback.invoke(SubtitleFile("tr", m.value))
            }
        }
        if (!found) {
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
}
