package com.jetfilmizle

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** JetFilmizle — jetfilmizle.app (jetfilmizle.tv/.com/.now aynı yayına yönlenir) */
class JetFilmizleProvider : MainAPI() {
    override var mainUrl = "https://jetfilmizle.app"
    private val uaHeaders = mapOf(
        "User-Agent" to BROWSER_UA,
        "Accept-Language" to "tr-TR,tr;q=0.9",
    )
    override var name = "JetFilmizle"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/filmler/sayfa-SAYFA" to "Son Eklenenler",
        "$mainUrl/turkce-dublaj/sayfa-SAYFA" to "Türkçe Dublaj",
        "$mainUrl/turkce-altyazili/sayfa-SAYFA" to "Türkçe Altyazılı",
    )

    override suspend fun getMainPage(page: Int, request: com.lagradost.cloudstream3.MainPageRequest): com.lagradost.cloudstream3.HomePageResponse {
        val doc = app.get(request.data.replace("SAYFA", page.toString()), headers = uaHeaders).document
        val items = doc.select("div.film-card").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a[href*=/film/]") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        val title = selectFirst("h3.fc-title")?.text()?.trim()
            ?: anchor.attr("title").substringBefore(" Full").trim()
        if (title.isBlank()) return null
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = fixUrlNull(selectFirst("img")?.attr("src"))
            this.year = selectFirst("span.fc-year")?.text()?.trim()?.toIntOrNull()
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/arama", headers = uaHeaders, params = mapOf("q" to query.trim())).document
        return doc.select("div.film-card").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url, headers = uaHeaders).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val title = doc.selectFirst("h1")?.ownText()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" (")?.trim()
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")

        val year = doc.selectFirst("a[href*=/yil/]")?.text()?.trim()?.toIntOrNull()
            ?: Regex("""\((\d{4})\)""").find(doc.title())?.groupValues?.get(1)?.toIntOrNull()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
            this.plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
            this.year = year
            this.tags = doc.select("a[href*=/tur/]").map { it.text().trim() }
            this.score = Score.from10(doc.selectFirst("meta[name=film:rating]")?.attr("content"))
            this.actors = doc.select("a[href*=/oyuncu/]").map { ActorData(Actor(it.text().trim())) }
            doc.selectFirst("#trailerIframe")?.attr("data-src")
                ?.let { Regex("""youtube\.com/embed/([A-Za-z0-9_-]+)""").find(it)?.groupValues?.get(1) }
                ?.let { addTrailer("https://www.youtube.com/embed/$it") }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val doc = runCatching { app.get(data, headers = uaHeaders).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: kaynak bulunamadı. $REPORT_HINT")
        val filmId = doc.selectFirst("input[name=film_id]")?.attr("value")?.trim().orEmpty()
        if (filmId.isBlank()) throw ErrorLoadingException("$name: kaynak bulunamadı. $REPORT_HINT")

        var found = false
        doc.select("div.player-sources-group").forEach { group ->
            val type = group.attr("data-player-group").ifBlank { "genel" }
            group.select("button.player-source-btn").forEach { button ->
                val index = button.attr("data-source-index").ifBlank { return@forEach }
                val label = "${typeLabel(type)} · ${button.text().trim()}"
                runCatching {
                    val iframe = app.post(
                        "$mainUrl/jetplayer",
                        headers = uaHeaders + mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to data),
                        data = mapOf(
                            "film_id" to filmId,
                            "source_index" to index,
                            "player_type" to type,
                        ),
                    ).text
                    val player = Regex("""<iframe[^>]+src=['"]([^'"]+)""").find(iframe)?.groupValues?.get(1)
                        ?: return@runCatching
                    if (resolvePlayer(player, data, label, subtitleCallback, callback)) found = true
                }
            }
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir akış yok. $REPORT_HINT")
        return found
    }

    private suspend fun resolvePlayer(
        playerUrl: String,
        referer: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = when {
        playerUrl.contains("playerx.info") -> resolvePlayerx(playerUrl, referer, label, subtitleCallback, callback)
        playerUrl.contains("videopark.top/vip/") -> resolveVip(playerUrl, referer, label, callback)
        playerUrl.contains("videopark.top/go/") -> resolveGo(playerUrl, referer, label, callback)
        playerUrl.contains("/jetembed/") -> resolveVideopark(playerUrl, referer, label, subtitleCallback, callback) ||
            loadExtractor(playerUrl, referer, subtitleCallback, callback)
        else -> loadExtractor(playerUrl, referer, subtitleCallback, callback)
    }

    /** playerx.info/?id=NNN — jwplayer config `var initialPlayback = {...}` içinde HLS + altyazılar. */
    private suspend fun resolvePlayerx(
        playerUrl: String,
        referer: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = app.get(playerUrl, headers = uaHeaders, referer = referer).text
        val config = Regex("""var initialPlayback = (\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return false
        val stream = Regex(""""sources":\[\{"file":"([^"]+)"""")
            .find(config)?.groupValues?.get(1)?.replace("\\/", "/") ?: return false
        settingsSubtitle(config, subtitleCallback)
        callback.invoke(
            newExtractorLink(
                name,
                label,
                if (stream.startsWith("http")) stream else "https://playerx.info/$stream",
                ExtractorLinkType.M3U8,
            ) {
                this.referer = "https://playerx.info/"
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    private suspend fun settingsSubtitle(config: String, subtitleCallback: (SubtitleFile) -> Unit) {
        Regex(""""file":"(https:[^"]+)","label":"([^"]*)","kind":"captions"""")
            .findAll(config)
            .forEach { match ->
                val url = match.groupValues[1].replace("\\/", "/")
                val lang = match.groupValues[2].ifBlank { "Türkçe" }
                if (Settings.subtitleAllowed(lang)) subtitleCallback.invoke(newSubtitleFile(lang, url))
            }
    }

    /** videopark.top/vip/?id=NNN — worker /api/stream -> hlsSource + mp4Sources. */
    private suspend fun resolveVip(
        playerUrl: String,
        referer: String,
        label: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = app.get(playerUrl, headers = uaHeaders, referer = referer).text
        val worker = Regex("""WORKER_URL\s*=\s*'([^']+)'""").find(html)?.groupValues?.get(1)?.replace("\\/", "/")
            ?: return false
        val videoId = Regex("""VIDEO_ID\s*=\s*"([^"]*)"""").find(html)?.groupValues?.get(1).orEmpty()
        val pubId = Regex("""PUB_ID\s*=\s*"([^"]*)"""").find(html)?.groupValues?.get(1).orEmpty()
        val publisherId = Regex("""PUBLISHER_ID\s*=\s*"([^"]*)"""").find(html)?.groupValues?.get(1).orEmpty()
        val apiUrl = if (pubId.isNotBlank()) {
            "$worker/api/stream?pubId=$pubId&title=${videoId.ifBlank { pubId }}&publisherId=$publisherId"
        } else {
            "$worker/api/video?id=$videoId"
        }
        val json = app.get(apiUrl, headers = uaHeaders, referer = playerUrl).text
        val hls = Regex(""""hlsSource"\s*:\s*\{\s*"file"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)?.replace("\\/", "/")
        val mp4 = Regex(""""mp4Sources"\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL).find(json)?.groupValues?.get(1)
            .orEmpty()
        var found = false
        if (!hls.isNullOrBlank()) {
            callback.invoke(
                newExtractorLink(name, label, hls, ExtractorLinkType.M3U8) {
                    this.referer = "$worker/"
                    this.quality = Qualities.Unknown.value
                }
            )
            found = true
        }
        Regex(""""file"\s*:\s*"([^"]+)"\s*,\s*"label"\s*:\s*"([^"]*)"""").findAll(mp4).forEach { match ->
            val url = match.groupValues[1].replace("\\/", "/")
            val quality = getQuality(match.groupValues[2])
            if (!Settings.qualityAllowed(quality)) return@forEach
            callback.invoke(
                newExtractorLink(name, "$label ${match.groupValues[2]}", url, ExtractorLinkType.VIDEO) {
                    this.referer = "$worker/"
                    this.quality = quality
                }
            )
            found = true
        }
        return found
    }

    /** videopark.top/go/?id=NNN — godrive worker /v/ID/info kaliteleri. */
    private suspend fun resolveGo(
        playerUrl: String,
        referer: String,
        label: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = app.get(playerUrl, headers = uaHeaders, referer = referer).text
        val base = Regex("""WORKER_BASE\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            ?.replace("\\/", "/")?.trimEnd('/')
            ?: return false
        val videoId = Regex("""VIDEO_ID\s*=\s*(\d+)""").find(html)?.groupValues?.get(1) ?: return false
        val info = runCatching { app.get("$base/v/$videoId/info", headers = uaHeaders, referer = playerUrl).text }
            .getOrNull()
        val qualities = Regex(""""qualities"\s*:\s*\[([^\]]*)]""").find(info.orEmpty())
            ?.groupValues?.get(1)?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
        var found = false
        if (qualities.isEmpty()) {
            callback.invoke(
                newExtractorLink(name, label, "$base/v/$videoId", ExtractorLinkType.VIDEO) {
                    this.referer = "$base/"
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }
        qualities.forEach { quality ->
            if (!Settings.qualityAllowed(quality)) return@forEach
            callback.invoke(
                newExtractorLink(name, "$label ${quality}p", "$base/v/$videoId?q=$quality", ExtractorLinkType.VIDEO) {
                    this.referer = "$base/"
                    this.quality = quality
                }
            )
            found = true
        }
        return found
    }

    /** videopark.top/apollo/?id=NNN — get_video.php -> hlsUrl (embed klasörüne göre göreli). */
    private suspend fun resolveApollo(
        playerUrl: String,
        referer: String,
        label: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val id = Regex("""[?&]id=([^&]+)""").find(playerUrl)?.groupValues?.get(1) ?: return false
        val base = playerUrl.substringBefore("?").let { if (it.endsWith("/")) it else "$it/" }
        val json = app.post(
            "${base}get_video.php",
            headers = uaHeaders + mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to playerUrl),
            data = mapOf("id" to id),
        ).text
        val hls = Regex(""""hlsUrl"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
            ?.replace("\\/", "/")?.takeIf { it.isNotBlank() } ?: return false
        callback.invoke(
            newExtractorLink(name, label, fixUrl(base + hls), ExtractorLinkType.M3U8) {
                this.referer = playerUrl
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    /** videopark.top/jetembed/ttNNN — SPA; backend kaynak listesindeki moviesapi doğrudan HLS verir. */
    private suspend fun resolveVideopark(
        playerUrl: String,
        referer: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val imdb = Regex("""/jetembed/(tt\d+)""").find(playerUrl)?.groupValues?.get(1) ?: return false
        val embed = playerUrl.substringBefore("/jetembed/")
        val sources = runCatching {
            JSONObject(
                app.get(
                    "$embed/jetembed/backend/api_sources.php",
                    headers = uaHeaders,
                    referer = referer,
                    params = mapOf("imdb" to imdb),
                ).text
            ).optJSONArray("sources")
        }.getOrNull() ?: return false

        for (i in 0 until sources.length()) {
            val embedUrl = sources.optJSONObject(i)?.optString("url")?.takeIf { it.contains("/movie/") } ?: continue
            val apiBase = embedUrl.substringBefore("/movie/")
            if (!apiBase.contains("moviesapi")) continue
            val root = runCatching {
                JSONObject(
                    app.get(
                        "$apiBase/api/vidora/v1/movie/$imdb",
                        headers = uaHeaders + mapOf("x-player-key" to MOVIESAPI_KEY),
                        referer = "$apiBase/",
                    ).text
                )
            }.getOrNull() ?: continue
            val streamSource = root.optJSONArray("sources")?.optJSONObject(0) ?: continue
            val stream = streamSource.optString("url").takeIf { it.startsWith("http") } ?: continue
            // shortcut: workers.dev vekili Cloudflare 429 dönüyor, imza paretocdn.site üzerinde de geçerli.
            val playable = WORKERS_DEV.replace(stream, "https://paretocdn.site/")
            callback.invoke(
                newExtractorLink(name, "$label · MoviesAPI", playable, ExtractorLinkType.M3U8) {
                    this.referer = "$apiBase/"
                    this.quality = Qualities.Unknown.value
                }
            )
            val tracks = streamSource.optJSONArray("tracks")
            if (tracks != null) {
                for (t in 0 until tracks.length()) {
                    val track = tracks.optJSONObject(t) ?: continue
                    val file = track.optString("file").takeIf { it.startsWith("http") } ?: continue
                    val language = track.optString("label").ifBlank { "Türkçe" }
                    if (Settings.subtitleAllowed(language)) subtitleCallback.invoke(newSubtitleFile(language, file))
                }
            }
            return true
        }
        return false
    }

    private fun typeLabel(type: String) = when (type) {
        "dublaj" -> "Türkçe Dublaj"
        "altyazili" -> "Türkçe Altyazılı"
        else -> "Orijinal"
    }

    private fun getQuality(label: String): Int = when (label.trim().removeSuffix("p")) {
        "2160" -> Qualities.P2160.value
        "1440" -> Qualities.P1440.value
        "1080" -> Qualities.P1080.value
        "720" -> Qualities.P720.value
        "480" -> Qualities.P480.value
        "360" -> Qualities.P360.value
        "240" -> Qualities.P240.value
        else -> Qualities.Unknown.value
    }

    companion object {
        // shortcut: moviesapi.to oynatıcı paketindeki sabit anahtar; 401 alınırsa paketten yenilenmeli.
        private const val MOVIESAPI_KEY =
            "3a67e8866ae1d2bb9e81fe7f73315a56eb3bdf5e3e755c7554c8be6910aa6b13"
        private val WORKERS_DEV = Regex("""^https://[^/]+\.workers\.dev/""")
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}
