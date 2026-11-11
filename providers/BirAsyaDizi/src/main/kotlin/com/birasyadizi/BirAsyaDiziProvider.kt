package com.birasyadizi

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

class BirAsyaDiziProvider : MainAPI() {
    private val uaHeaders get() = browserHeaders

    override var mainUrl = "https://www.birasyadizi.cx"
    override var name = "BirAsyaDizi"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val hasMainPage = true
    override val mainPage get() = ROWS.mapNotNull { rowName ->
        if (!Settings.rowEnabled(rowName)) return@mapNotNull null
        MainPageData(rowName, when (rowName) {
            "Aile" -> "$mainUrl/category/aile/"
            "Aksiyon" -> "$mainUrl/category/aksiyon/"
            "Animasyon" -> "$mainUrl/category/animasyon/"
            "Arkadaşlık" -> "$mainUrl/category/arkadaslik/"
            "Belgesel" -> "$mainUrl/category/belgesel/"
            "Bilim Kurgu" -> "$mainUrl/category/bilim-kurgu/"
            "Dram" -> "$mainUrl/category/dram/"
            "Fantastik" -> "$mainUrl/category/fantastik/"
            "Gizem" -> "$mainUrl/category/gizem/"
            "Komedi" -> "$mainUrl/category/komedi/"
            "Korku" -> "$mainUrl/category/korku/"
            "Macera" -> "$mainUrl/category/macera/"
            "Romantik" -> "$mainUrl/category/romantik/"
            else -> mainUrl
        })
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.name in ROWS) {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
            val doc = app.get(request.data, headers = uaHeaders).document
            return newHomePageResponse(request.name, cards(doc), hasNext = false)
        }
        return newHomePageResponse(request.name, emptyList(), hasNext = false)
    }

    companion object {
        val ROWS = listOf("Aile", "Aksiyon", "Animasyon", "Arkadaşlık",
            "Belgesel", "Bilim Kurgu", "Dram", "Fantastik",
            "Gizem", "Komedi", "Korku", "Macera", "Romantik")
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val rows = feedSearch(query)
        if (rows.isNotEmpty()) return rows
        val url = "$mainUrl/?s=" + java.net.URLEncoder.encode(query, "UTF-8")
        return cards(app.get(url, headers = uaHeaders).document, query)
    }

    private fun feedItems(body: String): List<Pair<String, String>> =
        Regex("""<item>([\s\S]*?)</item>""").findAll(body).mapNotNull { item ->
            val block = item.groupValues[1]
            val link = Regex("""<link>\s*([\s\S]*?)\s*</link>""").find(block)
                ?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
            val title = Regex("""<title>\s*([\s\S]*?)\s*</title>""").find(block)
                ?.groupValues?.get(1)?.replace("<![CDATA[", "")?.replace("]]>", "")?.trim()
                .orEmpty()
            link to title
        }.toList()

    private suspend fun feedSearch(query: String): List<SearchResponse> {
        val rows = LinkedHashMap<String, SearchResponse>()
        for (page in 1..2) {
            val suffix = if (page > 1) "&paged=$page" else ""
            val url = "$mainUrl/?s=" + java.net.URLEncoder.encode(query, "UTF-8") + suffix
            val body = runCatching { app.get(url, headers = uaHeaders).text }.getOrNull() ?: break
            if (!body.contains("<rss")) break
            for ((link, title) in feedItems(body)) {
                if (!link.contains("/dizi/")) continue
                rows.getOrPut(link) { newTvSeriesSearchResponse(cleanTitle(title), link, TvType.TvSeries) }
            }
            if (rows.isNotEmpty()) break
        }
        return rows.values.toList()
    }

    private fun cards(doc: Document, query: String? = null): List<SearchResponse> =
        doc.select("div.sayfa-sol a[href], div.icerik a[href]").ifEmpty { doc.select("a[href]") }.mapNotNull { card ->
        val href = fixUrlNull(card.attr("href")) ?: return@mapNotNull null
        val path = card.attr("href").substringAfter(mainUrl, card.attr("href")).substringBefore('?')
        if (listOf("/", "/dizi/").none { path.startsWith(it) }) return@mapNotNull null
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
        val plot = doc.jsonLdDescription()
        val year = Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(raw)?.value?.toIntOrNull()
        val score = Score.from10(
            doc.selectFirst("[itemprop=ratingValue]")?.attr("content")
                ?: Regex(""""(?:imdbPuani|ratingValue|imdb)"\s*:\s*"?([0-9.]+)""").find(raw)?.groupValues?.get(1)
        )
        val slug = url.substringAfter(mainUrl).substringBefore('?').trim('/').substringAfterLast('/')
        val stem = slug.replace(Regex("""-\d{4}.*$"""), "")
        val episodeAnchors = doc.select("ul.scroll-liste a[href*='/bolum/']").ifEmpty {
            doc.select("a[href*='/bolum/']").filter { anchor ->
                stem.isNotBlank() &&
                    anchor.attr("href").substringBefore('?').trimEnd('/')
                        .substringAfterLast('/').startsWith(stem)
            }
        }
        val episodes = episodeAnchors.mapNotNull { anchor ->
            val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            val path = href.substringAfter(mainUrl).substringBefore('?')
            if (!path.contains("/bolum/")) return@mapNotNull null
            val number = Regex("""(?:sezon|season)[-_/]?(\d+).*?(?:bolum|episode)[-_/]?([0-9]*)""")
                .find(path)?.groupValues?.getOrNull(2)?.toIntOrNull()
                ?: Regex("""(?:bolum|episode)[-_/]?(\d+)""").find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""(\d+)[-_]bolum""").find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(href) {
                this.name = anchor.text().trim().ifBlank { number?.let { n -> "Bölüm $n" } ?: "Bölüm" }
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
        val partUrls = (listOf(data) + firstResp.document.select("#f_part a.partsec[href], a[href]").mapNotNull { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            if (href.trimEnd('/').matches(Regex(Regex.escape(baseData) + """/\d+""")) ||
                (a.hasClass("partsec") && href.startsWith(mainUrl))) href else null
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
