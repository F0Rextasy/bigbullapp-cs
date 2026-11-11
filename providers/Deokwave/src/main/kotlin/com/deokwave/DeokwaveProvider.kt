package com.deokwave

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
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
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

class DeokwaveProvider : MainAPI() {
    private val uaHeaders get() = browserHeaders

    override var mainUrl = "https://deokwave.com"
    override var name = "Deokwave"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie, TvType.TvSeries)

    override val hasMainPage = true
    override val mainPage get() = ROWS.mapNotNull { rowName ->
        if (!Settings.rowEnabled(rowName)) return@mapNotNull null
        MainPageData(rowName, when (rowName) {
            "Animeler" -> "$mainUrl/animeler/"
            "Keşfet" -> "$mainUrl/kesfet/"
            "Aksiyon" -> "$mainUrl/kategoriler/?category=action"
            "Komedi" -> "$mainUrl/kategoriler/?category=komedi"
            "Dram" -> "$mainUrl/kategoriler/?category=dram"
            "Fantastik" -> "$mainUrl/kategoriler/?category=fantasy"
            "Bilim Kurgu" -> "$mainUrl/kategoriler/?category=scifi__fantasy"
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
        val ROWS = listOf(
            "Animeler", "Keşfet", "Aksiyon", "Komedi",
            "Dram", "Fantastik", "Bilim Kurgu",
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "https://deokwave.com/search_api.php?q=" + java.net.URLEncoder.encode(query, "UTF-8")
        val raw = app.get(url, headers = uaHeaders).text
        val root = if (raw.trimStart().startsWith("[")) JSONObject() else JSONObject(raw)
        val out = mutableListOf<SearchResponse>()
        val arr = root.optJSONArray("animes") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("name_english").trim().ifBlank { o.optString("name").trim() }
            val slug = o.optString("animeid").trim()
            if (title.isBlank() || slug.isBlank()) continue
            val href = if (slug.startsWith("http")) slug else "$mainUrl/anime/" + slug.trimStart('/')
            out += newAnimeSearchResponse(title, href, TvType.Anime) {
                posterUrl = fixUrlNull(o.optString("poster").takeIf { it.startsWith("http") })
            }
        }
        return out.distinctBy { it.url }
    }

    private fun cards(doc: Document, query: String? = null): List<SearchResponse> = doc.select("a[href]").mapNotNull { card ->
        val href = fixUrlNull(card.attr("href")) ?: return@mapNotNull null
        val path = card.attr("href").substringAfter(mainUrl, card.attr("href")).substringBefore('?')
        if (!path.startsWith("/anime/")) return@mapNotNull null
        val img = card.selectFirst("img") ?: return@mapNotNull null
        val title = card.attr("title").trim()
            .ifBlank { img.attr("alt").trim() }
            .ifBlank { card.text().trim() }
            .takeIf { it.isNotBlank() } ?: return@mapNotNull null
        if (query != null && !title.contains(query, ignoreCase = true) &&
            !href.contains(query, ignoreCase = true)) return@mapNotNull null
        newAnimeSearchResponse(title, href, TvType.Anime) {
            posterUrl = fixUrlNull(img.posterSrcLazy())
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
            ?: Regex("""animeBasePoster\s*=\s*['"](https?:[^'"]+)['"]""").find(raw)?.groupValues?.get(1)
        val plot = doc.jsonLdDescription()
        val genres = doc.select("[itemprop=genre], a[href*='/tur/'], a[href*='/kategori/']")
            .mapNotNull { it.attr("content").ifBlank { it.text() }.trim().takeIf(String::isNotBlank) }
            .distinct().take(8)
        val year = Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(raw)?.value?.toIntOrNull()
        val score = Score.from10(
            doc.selectFirst("[itemprop=ratingValue]")?.attr("content")
                ?: Regex(""""(?:imdbPuani|ratingValue|imdb)"\s*:\s*"?([0-9.]+)""").find(raw)?.groupValues?.get(1)
        )
        val animeId = Regex("""animeIdJs\s*=\s*['"]([A-Za-z0-9_]+)['"]""").find(raw)?.groupValues?.get(1)
            ?: pageUrl.substringAfter("/anime/").trim('/').substringBefore('/')
        val jsEpisodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        Regex("""var\s+allSezonlar\s*=\s*(\{[^\r\n;]+\})\s*;""").find(raw)?.groupValues?.get(1)?.let { jsonText ->
            runCatching {
                val seasonsObj = JSONObject(jsonText)
                for (snKey in seasonsObj.keys()) {
                    val sn = snKey.toIntOrNull() ?: continue
                    if (sn < 1) continue
                    val epMap = seasonsObj.optJSONObject(snKey) ?: continue
                    for (epKey in epMap.keys()) {
                        val epNum = epKey.toIntOrNull() ?: continue
                        val epInfo = epMap.optJSONObject(epKey)
                        val thumb = epInfo?.optString("thumbnail")?.replace("\\/", "/")?.takeIf { it.startsWith("http") }
                        val epHref = "$mainUrl/watch/$animeId/season/$sn/episode/$epNum"
                        jsEpisodes += newEpisode(epHref) {
                            this.season = sn
                            this.episode = epNum
                            this.name = "$sn. Sezon $epNum. Bölüm"
                            this.posterUrl = thumb
                        }
                    }
                }
            }
        }
        val domEpisodes = doc.select("a[href]").mapNotNull { anchor ->
            val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            val path = href.substringAfter(mainUrl).substringBefore('?')
            if (!Regex("""(?:bolum|episode)""", RegexOption.IGNORE_CASE).containsMatchIn(path)) return@mapNotNull null
            val seasonNum = Regex("""(?:sezon|season)[-_/]?(\d+)""", RegexOption.IGNORE_CASE).find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val number = Regex("""(?:sezon|season)[-_/]?(\d+).*?(?:bolum|episode)[-_/]?([0-9]*)""")
                .find(path)?.groupValues?.getOrNull(2)?.toIntOrNull()
                ?: Regex("""(?:bolum|episode)[-_/]?(\d+)""").find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""(\d+)[-_]bolum""").find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(href) {
                this.season = seasonNum ?: 1
                this.name = anchor.text().trim().ifBlank { "Bölüm $number" }
                this.episode = number
            }
        }
        val episodes = (jsEpisodes + domEpisodes)
            .distinctBy { it.data }
            .sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
        if (episodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = score
                this.backgroundPosterUrl = poster
                this.tags = genres.ifEmpty { null }
            }
        }
        val watchUrl = doc.selectFirst("a[href*='/watch/']")?.let { fixUrlNull(it.attr("href")) }
            ?: if (animeId.isNotBlank()) "$mainUrl/watch/$animeId/" else url
        return newMovieLoadResponse(title, url, TvType.Movie, watchUrl) {
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
        val watchData = if (data.contains("/anime/")) data.replace("/anime/", "/watch/") else data
        val resp = app.get(watchData, headers = uaHeaders)
        val text = resp.text.replace("\\/", "/").replace("\\\"", "\"")
        var found = false
        val seen = mutableSetOf<String>()
        suspend fun emit(stream: String, label: String, referer: String, explicitQuality: Int? = null, headers: Map<String, String>? = null) {
            if (!stream.startsWith("http") || !seen.add(stream)) return
            val isM3u8 = stream.contains(".m3u8")
            val type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            val quality = explicitQuality ?: if (isM3u8) Qualities.Unknown.value else getQualityFromName(label)
            if (Settings.qualityAllowed(quality)) {
                callback.invoke(newExtractorLink(name, label, stream, type) {
                    this.referer = referer
                    this.quality = quality
                    if (headers != null) this.headers = headers
                })
                found = true
            }
            if (isM3u8 && explicitQuality == null) {
                val playlist = runCatching { app.get(stream, headers = uaHeaders, referer = referer).text }.getOrNull().orEmpty()
                for ((h, vUrl) in parseHlsVariants(stream, playlist)) {
                    emit(vUrl, "$label ${h}p", referer, h, headers)
                }
            }
        }
        val cookieHeader = resp.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val streamHeaders = uaHeaders + if (cookieHeader.isNotBlank()) mapOf("Cookie" to cookieHeader, "Referer" to watchData) else mapOf("Referer" to watchData)
        loadTokenFlow(watchData, resp.text, resp.cookies) { stream, label, referer -> emit(stream, label, referer, headers = streamHeaders) }
        val media = Regex("""https?://[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?""").findAll(text)
        for (m in media) emit(m.value, "Direct", watchData)
        val embeds = Regex(""""(?:embedUrl|contentUrl|embed_url|player_url)"\s*:\s*"([^"]+)""")
            .findAll(text).map { it.groupValues[1] }.toList()
        for (embed in embeds) {
            val absolute = fixUrl(embed)
            if (absolute == watchData) continue
            if (!absolute.contains("embed") && !absolute.contains(".mp4") && !absolute.contains(".m3u8")) continue
            if (absolute.matches(Regex(""".*\.(?:mp4|m3u8).*"""))) {
                emit(absolute, "Embed", watchData)
                continue
            }
            val inner = runCatching { app.get(absolute, headers = uaHeaders, referer = watchData).text }.getOrNull() ?: continue
            val innerMedia = Regex("""https?://[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?""").findAll(inner)
            for (m in innerMedia) emit(m.value, "Embed", absolute)
        }
        val frames = resp.document.select("iframe").mapNotNull {
            val raw = it.attr("src").ifBlank { it.attr("data-src") }.ifBlank { it.attr("vdo-src") }
            fixUrlNull(raw)
        }.distinct()
        for (frame in frames) {
            if (frame.contains(".mp4") || frame.contains(".m3u8")) {
                emit(frame, "Embed", watchData)
                continue
            }
            val inner = runCatching { app.get(frame, headers = uaHeaders, referer = watchData).text }.getOrNull()
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
        if (!found && frames.isEmpty() && embeds.isEmpty() && media.none()) {
            throw ErrorLoadingException("$name: oynatılabilir akış yok")
        }
        return found
    }

    private suspend fun loadTokenFlow(
        data: String,
        rawPage: String,
        cookies: Map<String, String>,
        emit: suspend (String, String, String) -> Unit,
    ) {
        val page = rawPage.replace("&quot;", "\"").replace("\\\"", "\"")
        val cfg = Regex("""([A-Za-z0-9_]{3,40})"\s*:\s*"([^"]{1,400})""")
            .findAll(page).associate { it.groupValues[1] to it.groupValues[2] }
        val mainVideoId = cfg["videoId"].orEmpty()
        if (mainVideoId.isBlank()) return
        val sid = cfg["playbackSessionId"].orEmpty()
        val csrf = cfg["csrfToken"].orEmpty()
        data class SourceEntry(val labelPrefix: String, val vid: String, val quals: List<String>)
        val sources = mutableListOf<SourceEntry>()
        val cfgJson = Regex("""data-config="(\{.*?\})"\s*>""").find(rawPage)?.groupValues?.get(1)
            ?.replace("&quot;", "\"")
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        val fansubs = cfgJson?.optJSONArray("fansubs")
        if (fansubs != null && fansubs.length() > 0) {
            for (i in 0 until fansubs.length()) {
                val fs = fansubs.optJSONObject(i) ?: continue
                if (fs.optBoolean("is4k", false)) continue
                val vid = fs.optString("videoid").trim()
                if (vid.isBlank()) continue
                val fname = fs.optString("name").trim().ifBlank { "Deokwave" }
                val qArr = fs.optJSONArray("qualities")
                val qs = mutableListOf<String>()
                if (qArr != null) {
                    for (j in 0 until qArr.length()) {
                        val q = qArr.optString(j).trim().trimEnd('p')
                        if (q.isNotBlank()) qs += q
                    }
                }
                sources += SourceEntry(fname, vid, qs.ifEmpty { listOf("1080", "720", "480", "360") })
            }
        }
        if (sources.isEmpty()) {
            val qArr = cfgJson?.optJSONArray("qualities")
            val qs = mutableListOf<String>()
            if (qArr != null) {
                for (j in 0 until qArr.length()) {
                    val q = qArr.optString(j).trim().trimEnd('p')
                    if (q.isNotBlank()) qs += q
                }
            }
            sources += SourceEntry("Deokwave", mainVideoId, (qs + listOf("1080", "720", "480", "360")).distinct())
        }
        val headers = uaHeaders + mapOf(
            "Content-Type" to "application/json",
            "X-CSRF-Token" to csrf,
        )
        for (src in sources.distinctBy { it.vid }) {
            for (q in src.quals) {
                if (!Settings.qualityAllowed(q.toIntOrNull() ?: 0)) continue
                val payload = JSONObject()
                payload.put("videoid", src.vid)
                payload.put("quality", q)
                payload.put("sid", sid)
                val body = runCatching {
                    app.post(fixUrl("/api/v1/video/token/"), json = payload, headers = headers, cookies = cookies, referer = data).text
                }.getOrNull() ?: continue
                val token = runCatching { JSONObject(body).optString("token") }
                    .getOrNull().orEmpty()
                if (token.isBlank()) continue
                val url = "https://sw2.deokwave.com/v/${src.vid}/$q/?vt=" + java.net.URLEncoder.encode(token, "UTF-8")
                emit(url, "${src.labelPrefix} • ${q}p", data)
            }
        }
    }
}
