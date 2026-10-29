package com.dizilife

import android.util.Base64
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.helper.CryptoJS
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

/**
 * DiziLife — dizi74.life, which 301s to dizi82.life (Laravel/Livewire, server-rendered cards).
 * Episode pages embed a player that serves an obfuscated HTML shell: two reversed base64 strings,
 * the passphrase and an OpenSSL "Salted__" blob, both decrypted with CryptoJS (AES-256-CBC).
 * The decrypted shell holds the HLS master URL.
 */
class DiziLifeProvider : MainAPI() {
    override var mainUrl = "https://dizi82.life"
    override var name = "DiziLife"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage get() = ROWS.mapNotNull { name ->
        if (!Settings.rowEnabled(name)) return@mapNotNull null
        when (name) {
            "Diziler" -> MainPageData(name, "$mainUrl/diziler?page=")
            "Filmler" -> MainPageData(name, "$mainUrl/filmler?page=")
            "Aksiyon" -> MainPageData(name, "$mainUrl/tur/aksiyon?series_page=")
            "Romantik" -> MainPageData(name, "$mainUrl/tur/romantik?series_page=")
            "Bilim Kurgu" -> MainPageData(name, "$mainUrl/tur/bilim-kurgu?series_page=")
            "Komedi" -> MainPageData(name, "$mainUrl/tur/komedi?series_page=")
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data + page).document
        val items = doc.cardAnchors().mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = doc.selectFirst("a[rel=next]") != null)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        search(query, 1)?.items ?: emptyList()

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        // Only the "Diziler" block paginates (series_page); the movie block repeats on every page.
        val doc = app.get("$mainUrl/ara?q=$encoded&series_page=$page").document
        val items = doc.cardAnchors().mapNotNull { it.toSearchResult() }
            .filter { page == 1 || !it.url.contains("/film/") }
            .distinctBy { it.url }
        return newSearchResponseList(items, doc.selectFirst("a[rel=next]") != null)
    }

    private fun Element.cardAnchors(): List<Element> =
        select("a[href] > article.media-card").mapNotNull { it.parent() }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val card = selectFirst("article.media-card") ?: return null
        val title = card.selectFirst("div.font-display")?.text()?.trim().orEmpty()
            .ifBlank { card.selectFirst("img")?.attr("alt")?.trim().orEmpty() }
        if (title.isBlank()) return null
        val poster = fixUrlNull(card.selectFirst("img")?.attr("src"))
        return if (href.contains("/film/")) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val schema = doc.select("script[type=application/ld+json]")
            .mapNotNull { runCatching { JSONObject(it.data().trim()) }.getOrNull() }
            .firstOrNull { it.optString("@type") in setOf("TVSeries", "Movie") }

        val title = schema?.optString("name")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val poster = fixUrlNull(schema?.optString("image")?.takeIf { it.isNotBlank() })
            ?: fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = schema?.optString("description")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
        val year = schema?.optString("datePublished")?.take(4)?.toIntOrNull()
        val tags = (schema?.opt("genre") as? JSONArray)?.let { genre ->
            (0 until genre.length()).map { genre.optString(it).trim() }.filter { it.isNotBlank() }
        }
        val score = schema?.optJSONObject("aggregateRating")?.optDouble("ratingValue")
            ?.takeIf { !it.isNaN() && it > 0 }?.let { Score.from10(it) }
        // Movies carry "PT114M"; the series schema has no runtime, episodes do.
        val duration = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?""").find(schema?.optString("duration").orEmpty())
            ?.let { (it.groupValues[1].toIntOrNull() ?: 0) * 60 + (it.groupValues[2].toIntOrNull() ?: 0) }
            ?.takeIf { it > 0 }
        val actors = (schema?.opt("actor") as? JSONArray)?.let { list ->
            (0 until list.length())
                .mapNotNull { (list.opt(it) as? JSONObject)?.optString("name")?.trim()?.takeIf { n -> n.isNotBlank() } }
        }.orEmpty()
        val related = doc.selectFirst("section[aria-labelledby=similar-heading]")
            ?.cardAnchors()?.mapNotNull { it.toSearchResult() }.orEmpty()

        if (url.contains("/film/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = score
                this.duration = duration
                this.recommendations = related
                if (actors.isNotEmpty()) this.actors = actors.map { ActorData(Actor(it)) }
            }
        }

        val seriesBase = url.trimEnd('/')
        val episodes = LinkedHashMap<String, Episode>()
        doc.select("a[href*='/bolum/']").forEach { anchor ->
            val href = fixUrlNull(anchor.attr("href")) ?: return@forEach
            // Recommendations can carry other shows' episode links.
            if (!href.startsWith("$seriesBase/")) return@forEach
            val match = Regex("""/sezon/(\d+)/bolum/(\d+)""").find(href) ?: return@forEach
            val name = anchor.selectFirst("div.font-display")?.text()?.trim().orEmpty()
                .ifBlank { anchor.selectFirst("img")?.attr("alt")?.trim().orEmpty() }
            val description = anchor.selectFirst("p.text-text-dim")?.text()?.trim()?.takeIf { it.isNotBlank() }
            val runTime = Regex("""(\d+)\s*dk""").find(anchor.selectFirst("div.text-text-mute")?.text().orEmpty())
                ?.groupValues?.get(1)?.toIntOrNull()
            val existing = episodes[href]
            if (existing != null) {
                // The hero "İlk Bölümü İzle" button shares the first episode URL but carries no title.
                if (existing.name.isNullOrBlank() && name.isNotBlank()) existing.name = name
                if (existing.description.isNullOrBlank()) existing.description = description
                if (existing.runTime == null) existing.runTime = runTime
                return@forEach
            }
            episodes[href] = newEpisode(href) {
                this.name = name
                this.season = match.groupValues[1].toIntOrNull()
                this.episode = match.groupValues[2].toIntOrNull()
                this.description = description
                this.runTime = runTime
            }
        }
        if (episodes.isEmpty()) throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.values.toList()) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.score = score
            this.recommendations = related
            if (actors.isNotEmpty()) this.actors = actors.map { ActorData(Actor(it)) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val linkCallback: (ExtractorLink) -> Unit = { if (Settings.qualityAllowed(it.quality)) callback(it) }
        val subtitleFilter: (SubtitleFile) -> Unit = { if (Settings.subtitleAllowed(it.lang)) subtitleCallback(it) }
        val doc = runCatching { app.get(data).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: kaynak bulunamadı. $REPORT_HINT")
        val frames = doc.select("iframe[src]").mapNotNull { fixUrlNull(it.attr("src")) }.distinct()
        var found = false
        for (frame in frames) {
            val ok = if (frame.contains("/player/")) {
                resolvePlayer(data, frame, subtitleFilter, linkCallback)
            } else {
                runCatching { loadExtractor(frame, data, subtitleFilter, linkCallback) }.getOrDefault(false)
            }
            if (ok) found = true
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir akış yok. $REPORT_HINT")
        return found
    }

    private suspend fun resolvePlayer(
        refererUrl: String,
        playerUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = app.get(playerUrl, referer = refererUrl).text
        val plain = runCatching { decryptShell(html) }.getOrNull() ?: return false
        val stream = Regex("""file\s*:\s*["']([^"']+)["']""").find(plain)?.groupValues?.get(1)
            // The shell appends a cache-buster that its own JS strips before playback.
            ?.replace("?aztsdxdevfqwea", "")
            ?: return false

        callback.invoke(
            newExtractorLink(name, name, stream, ExtractorLinkType.M3U8) {
                this.referer = playerUrl
                this.quality = Qualities.Unknown.value
            }
        )

        // The master lists every subtitle rendition as a one-entry media playlist whose
        // only segment is the WebVTT file itself (verified tr + en on episodes and films).
        runCatching {
            val mediaBase = stream.substringBefore('?').substringBeforeLast('/') + "/"
            Regex("""#EXT-X-MEDIA:TYPE=SUBTITLES[^\n]*LANGUAGE="([^"]+)"[^\n]*NAME="([^"]+)"[^\n]*URI="([^"]+)"""")
                .findAll(app.get(stream, referer = playerUrl).text)
                .forEach { match ->
                    runCatching {
                        val playlist = URI(mediaBase).resolve(match.groupValues[3].replace(" ", "%20")).toString()
                        val vtt = app.get(playlist, referer = playerUrl).text.lineSequence()
                            .firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return@runCatching
                        subtitleCallback(
                            SubtitleFile(match.groupValues[2], URI(playlist).resolve(vtt.trim()).toString())
                        )
                    }
                }
        }
        return true
    }

    /** atob(s.reversed()) for both strings; the long one is the ciphertext, the other the passphrase. */
    private fun decryptShell(html: String): String {
        val fn = Regex("""(\w+)=function\(s\)\{return atob\(s\.split\(['"]{2}\)\.reverse\(\)\.join\(['"]{2}\)\)\}""")
            .find(html) ?: return html
        val args = Regex("""${Regex.escape(fn.groupValues[1])}\("([^"]*)"\)""")
            .findAll(html).map { it.groupValues[1] }.sortedByDescending { it.length }.toList()
        if (args.size < 2) return html
        val cipher = String(decodeReversedBase64(args[0]), Charsets.UTF_8)
        val password = String(decodeReversedBase64(args[1]), Charsets.UTF_8)
        return CryptoJS.decrypt(password, cipher)
    }

    private fun decodeReversedBase64(value: String): ByteArray {
        val reversed = value.reversed()
        return Base64.decode(reversed + "=".repeat((4 - reversed.length % 4) % 4), Base64.DEFAULT)
    }

    companion object {
        /** Homepage row labels, in display order; each maps to a live listing page above. */
        val ROWS = listOf("Diziler", "Filmler", "Aksiyon", "Romantik", "Bilim Kurgu", "Komedi")
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}
