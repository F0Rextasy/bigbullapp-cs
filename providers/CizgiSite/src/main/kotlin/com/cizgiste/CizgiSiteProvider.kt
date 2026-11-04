package com.cizgiste

import com.bigbull.common.browserHeaders
import com.bigbull.common.posterSrcLazy
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.brotli.dec.BrotliInputStream
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

class CizgiSiteProvider : MainAPI() {
    override var mainUrl = "https://www.cizgi.site"
    override var name = "CizgiSite"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val uaHeaders get() = browserHeaders

    private val client = OkHttpClient()

    private fun vkApiMedia(frame: String, page: String): List<Pair<String, String>> {
        val oid = Regex("""[?&]oid=(-?\d+)""").find(frame)?.groupValues?.get(1) ?: return emptyList()
        val id = Regex("""[?&]id=(\d+)""").find(frame)?.groupValues?.get(1) ?: return emptyList()
        val hash = Regex("""[?&]hash=([0-9a-fA-F]+)""").find(frame)?.groupValues?.get(1).orEmpty()
        val token = Regex("""access_token"\s*:\s*"(anonym\.[^"]+)"""").find(page)?.groupValues?.get(1)
            ?: return emptyList()
        val body = FormBody.Builder()
            .add("owner_id", oid)
            .add("video_id", id)
            .add("hash", hash)
            .add("access_token", token)
            .build()
        val request = Request.Builder()
            .url("https://api.vk.com/method/video.getEmbed?v=5.289&client_id=6287487")
            .header("User-Agent", uaHeaders.getValue("User-Agent"))
            .header("Origin", "https://vk.com")
            .header("Referer", "https://vk.com/")
            .post(body)
            .build()
        val text = runCatching {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        }.getOrNull() ?: return emptyList()
        val files = runCatching {
            JSONObject(text).optJSONObject("response")
                ?.optJSONObject("video")?.optJSONObject("files")
        }.getOrNull() ?: return emptyList()
        return files.keys().asSequence()
            .map { it to files.optString(it) }
            .filter { (kind, url) ->
                url.startsWith("http") && (kind.startsWith("mp4_") || kind.startsWith("hls"))
            }
            .toList()
    }

    private fun fetchHtml(url: String, referer: String? = null, browserHeaders: Boolean = true): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", uaHeaders.getValue("User-Agent"))
            .header("Accept-Language", uaHeaders.getValue("Accept-Language"))
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .apply {
                if (browserHeaders) {
                    header("Sec-Fetch-Dest", "iframe")
                    header("Sec-Fetch-Mode", "navigate")
                    header("Sec-Fetch-Site", "cross-site")
                }
            }
            .apply { if (referer != null) header("Referer", referer) }
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ErrorLoadingException("$name: HTTP ${response.code}")
            val raw = response.body?.bytes() ?: return ""
            val encoding = response.header("Content-Encoding")?.lowercase()
            val decoded = runCatching {
                val stream = when (encoding) {
                    "br" -> BrotliInputStream(ByteArrayInputStream(raw))
                    "gzip" -> GZIPInputStream(ByteArrayInputStream(raw))
                    else -> ByteArrayInputStream(raw)
                }
                stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrNull()
            if (decoded.isNullOrBlank() && encoding == "br") {
                throw ErrorLoadingException("$name: Brotli yanıtı çözülemedi")
            }
            return decoded?.takeIf { it.isNotBlank() } ?: raw.toString(Charsets.UTF_8)
        }
    }

    override val mainPage get() = ROWS.mapNotNull { name ->
        if (!Settings.rowEnabled(name)) return@mapNotNull null
        when (name) {
            "Çizgi Filmler" -> MainPageData(name, "$mainUrl/tr")
            else -> null
        }
    }

    private fun Element.toCard(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val title = selectFirst("h4")?.text()?.trim()
            ?: attr("title").trim().takeIf { it.isNotBlank() }
            ?: return null
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = fixUrlNull(selectFirst("img")?.posterSrcLazy())
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = Jsoup.parse(fetchHtml(request.data))
        val items = doc.select("div.ikon-dis a[href]").mapNotNull { it.toCard() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim().lowercase()
        if (term.isBlank()) return emptyList()
        val doc = Jsoup.parse(fetchHtml("$mainUrl/tr"))
        return doc.select("div.ikon-dis a[href]").mapNotNull { it.toCard() }
            .filter { it.name.lowercase().contains(term) || it.url.lowercase().contains(term) }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val page = fetchHtml(url)
        val doc = Jsoup.parse(page)
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: throw ErrorLoadingException("$name: başlık okunamadı")
        val poster = fixUrlNull(
            doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("img[data-src]")?.posterSrcLazy()
        )
        val episodes = doc.select("article.icerik").mapNotNull { card ->
            val link = card.selectFirst("a[href]") ?: return@mapNotNull null
            val href = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
            val name = card.selectFirst("h3.baslik a")?.text()?.trim()
                ?: link.attr("title").trim().ifBlank { "Bölüm" }
            val season = card.selectFirst("a.sezon")?.text()?.filter { it.isDigit() }?.toIntOrNull()
            val number = card.selectFirst("a.bolum")?.text()?.filter { it.isDigit() }?.toIntOrNull()
                ?: Regex("""(\d+)-""").find(href.substringAfterLast('/'))?.groupValues?.get(1)?.toIntOrNull()
            newEpisode(href) {
                this.name = name
                this.season = season
                this.episode = number
            }
        }.distinctBy { it.data }
        if (episodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
            }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        }
    }

    private suspend fun pageHtml(url: String, referer: String? = null): String {
        var last = ""
        repeat(2) {
            last = fetchHtml(url, referer)
            if (last.length > 20_000 || last.contains("article.icerik") || last.contains("video_ext")) return last
        }
        return last
    }

    private fun frameUrls(page: String): List<String> =
        Regex("""(?:src|data-src)=["']([^"']*(?:vk\.com/video_ext|video_ext\.php)[^"']*)""", RegexOption.IGNORE_CASE)
            .findAll(page).map {
                val raw = it.groupValues[1]
                if (raw.startsWith("//")) "https:$raw" else fixUrl(raw)
            }.distinct().toList()

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var page = pageHtml(data)
        var frames = frameUrls(page)
        if (frames.isEmpty()) {
            val firstEpisode = Jsoup.parse(page).selectFirst("article.icerik a[href]")?.attr("href")
            if (!firstEpisode.isNullOrBlank()) {
                page = pageHtml(fixUrl(firstEpisode), data)
                frames = frameUrls(page)
            }
        }
        var found = false
        for (frame in frames) {
            var embed = runCatching { fetchHtml(frame, data) }.getOrNull() ?: continue
            fun extract(html: String): List<Pair<String, String>> =
                Regex(""""(mp4_\d+|hls|hls_fmp4)"\s*:\s*"(https?:[^"]+)"""")
                    .findAll(html).map {
                        it.groupValues[1] to it.groupValues[2]
                            .replace("\\\\/", "/").replace("\\/", "/").replace("\\u0026", "&")
                    }.toList()
            var media = extract(embed)
            if (media.isEmpty()) media = vkApiMedia(frame, embed)
            if (media.isEmpty()) {
                embed = runCatching { fetchHtml(frame, data, browserHeaders = false) }.getOrNull() ?: continue
                media = extract(embed).ifEmpty { vkApiMedia(frame, embed) }
            }
            for ((kind, url) in media) {
                val quality = getQualityFromName(kind)
                if (!Settings.qualityAllowed(quality)) continue
                val type = if (kind.startsWith("hls")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback.invoke(newExtractorLink(name, kind, url, type) {
                    this.referer = frame
                    this.headers = mapOf("User-Agent" to uaHeaders.getValue("User-Agent"))
                    this.quality = quality
                })
                found = true
            }
        }
        if (!found) {
            for (m in Regex("""https?://[^"'\s<>]+?\.(?:mp4|m3u8)(?:\?[^"'\s<>]*)""").findAll(page)) {
                callback.invoke(newExtractorLink(name, "Direct", m.value, ExtractorLinkType.VIDEO) {
                    this.referer = data
                })
                found = true
            }
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir akış yok")
        return true
    }

    companion object {
        val ROWS = listOf("Çizgi Filmler")
    }
}
