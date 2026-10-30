package com.fullhdfilm

import android.util.Base64
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

/**
 * FullHDFilm — fullhdfilmizlebox.vip (Laravel frontend).
 * Search is JSON (`/api/search?q=`), players come from the base64 template in the
 * `data-video-player` Alpine attribute and resolve to an hdload.top JW Player embed.
 */
class FullHDFilmProvider : MainAPI() {
    override var mainUrl = "https://fullhdfilmizlebox.vip"
    override var name = "FullHDFilm"
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie)

    private val uaHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9",
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val body = app.get(
            "$mainUrl/api/search?q=$encoded",
            headers = uaHeaders + ("Accept" to "application/json"),
        ).text
        val data = runCatching { JSONObject(body).optJSONArray("data") }.getOrNull()
            ?: throw ErrorLoadingException("$name: arama başarısız. $REPORT_HINT")
        val items = ArrayList<SearchResponse>()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            if (item.optString("contentableType") != "App\\Models\\Movie") continue
            val slug = item.optString("slug").trim('/')
            val title = item.optString("title").trim()
            if (slug.isBlank() || title.isBlank()) continue
            items.add(
                newMovieSearchResponse(title, filmUrl(slug), TvType.Movie) {
                    this.posterUrl = fixUrlNull(item.optString("posterUrl"))
                    this.year = item.optString("releaseYear").toIntOrNull()
                    this.score = item.optDouble("imdbRating", 0.0)
                        .takeIf { it > 0 }?.let { Score.from10(it) }
                }
            )
        }
        return items
    }

    private fun filmUrl(slug: String) = "$mainUrl/${slug.trim('/')}/"

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url, headers = uaHeaders).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val ogTitle = doc.selectFirst("meta[property='og:title']")?.attr("content").orEmpty()
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = doc.selectFirst("img[src*='/uploads/posters/']")?.let { fixUrl(it.attr("src")) }
            this.plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
            this.year = Regex("""\((\d{4})\)""").find(ogTitle)?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val page = runCatching { app.get(data, headers = uaHeaders).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: kaynak bulunamadı. $REPORT_HINT")
        val attribute = page.selectFirst("[data-video-player]")?.attr("x-data").orEmpty()
        val videos = runCatching {
            JSONObject(jsUnescape(jsPayload(attribute, "JSON.parse('")))
        }.getOrNull() ?: throw ErrorLoadingException("$name: kaynak bulunamadı. $REPORT_HINT")

        var found = false
        for (language in videos.keys()) {
            val label = languageLabel(language)
            val items = videos.optJSONArray(language) ?: continue
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val template = runCatching {
                    String(Base64.decode(item.optString("template"), Base64.DEFAULT), Charsets.UTF_8)
                }.getOrNull() ?: continue
                val markup = template
                    .replace("{slug}", item.optString("slug"))
                    .replace("{url}", item.optString("link"))
                val embed = Regex("(?:data-)?src=\"([^\"]+)\"")
                    .find(markup)?.groupValues?.get(1)?.let { fixUrl(it) } ?: continue

                // hdload.top only answers with a site referer.
                val embedDoc = runCatching {
                    app.get(embed, headers = uaHeaders + ("Referer" to data)).text
                }.getOrNull() ?: continue
                val config = runCatching {
                    JSONObject(jsUnescape(jsPayload(embedDoc, "setup(JSON.parse('")))
                }.getOrNull() ?: continue
                val stream = config.optString("file").takeIf { it.isNotBlank() } ?: continue
                val quality = streamQuality(stream)

                val linkName = listOf(name, label, item.optString("quality")).filter { it.isNotBlank() }.joinToString(" ")
                found = true
                if (Settings.qualityAllowed(quality)) {
                    callback.invoke(
                        newExtractorLink(name, linkName, stream, ExtractorLinkType.M3U8) {
                            this.referer = embed
                            this.quality = quality
                        }
                    )
                }
                val tracks = config.optJSONArray("tracks") ?: continue
                for (t in 0 until tracks.length()) {
                    val track = tracks.optJSONObject(t) ?: continue
                    val file = track.optString("file").takeIf { it.isNotBlank() } ?: continue
                    val trackLabel = track.optString("label").ifBlank { label }
                    if (Settings.subtitleAllowed(trackLabel)) {
                        subtitleCallback.invoke(SubtitleFile(trackLabel, fixUrl(file)))
                    }
                }
            }
        }
        if (!found) throw ErrorLoadingException("$name: kaynak bulunamadı. $REPORT_HINT")
        return true
    }

    private fun jsPayload(source: String, marker: String): String {
        val start = source.indexOf(marker)
        if (start < 0) return ""
        val from = start + marker.length
        val end = source.indexOf("')", from)
        return if (end > from) source.substring(from, end) else ""
    }

    /** One JS/JSON string-literal layer: `\u0022` -> `"`, `\\` -> `\`, `\/` -> `/`. */
    private fun jsUnescape(value: String): String {
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                val n = value[i + 1]
                if (n == 'u' && i + 6 <= value.length) {
                    runCatching { value.substring(i + 2, i + 6).toInt(16).toChar() }
                        .onSuccess { out.append(it); i += 6 }
                        .onFailure { out.append(n); i += 2 }
                    continue
                }
                out.append(when (n) { 'n' -> '\n'; 't' -> '\t'; else -> n })
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun languageLabel(language: String) = when (language) {
        "dual" -> "Türkçe Dublaj & Altyazı"
        "tr" -> "Türkçe Dublaj"
        "en" -> "Türkçe Altyazı"
        else -> language.uppercase()
    }

    /** Highest variant height advertised by the master playlist, bucketed to a CS quality. */
    private suspend fun streamQuality(stream: String): Int {
        val playlist = runCatching { app.get(stream, headers = uaHeaders).text }.getOrNull()
            ?: return Qualities.Unknown.value
        val height = Regex("""RESOLUTION=\d+x(\d+)""").findAll(playlist)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .maxOrNull() ?: return Qualities.Unknown.value
        return when {
            height >= 1000 -> Qualities.P1080.value
            height >= 700 -> Qualities.P720.value
            height >= 500 -> Qualities.P480.value
            height >= 350 -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    companion object {
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}
