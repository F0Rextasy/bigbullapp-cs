package com.fullhdfilmizlesene

import android.util.Base64
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element

/** FullHDFilmizlesene — fullhdfilmizlesene.now (mirrors: .pw/.biz/.nl redirect here) */
class FullHDFilmizleseneProvider : MainAPI() {
    override var mainUrl = "https://www.fullhdfilmizlesene.now"
    private val siteHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9",
    )
    override var name = "FullHDFilmizlesene"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/yeni-filmler/SAYFA" to "Son Eklenenler",
        "$mainUrl/en-cok-izlenen-filmler/SAYFA" to "En Çok İzlenenler",
        "$mainUrl/filmizle/turkce-dublaj-filmler-1/SAYFA" to "Türkçe Dublaj",
        "$mainUrl/filmizle/turkce-altyazili-filmler-1/SAYFA" to "Türkçe Altyazı",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data.replace("SAYFA", page.toString()), headers = siteHeaders).document
        val items = doc.select("li.film").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(selectFirst("a.tt")?.attr("href")) ?: return null
        val title = (selectFirst(".film-title")?.text() ?: selectFirst("a.tt")?.text())
            ?.removeSuffix("izle")?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = fixUrlNull(selectFirst("img.afis")?.attr("data-src"))
            this.year = selectFirst(".film-yil")?.text()?.trim()?.toIntOrNull()
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/arama/${query.trim().replace(" ", "+")}", headers = siteHeaders).document
        return doc.select("li.film").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url, headers = siteHeaders).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: film yüklenemedi.")
        val title = doc.selectFirst(".izle-titles h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: film bulunamadı.")
        val trailer = doc.selectFirst(".frg[data-code]")?.attr("data-code")
            ?.let { runCatching { String(Base64.decode(it, Base64.DEFAULT)) }.getOrNull() }
            ?.let { Regex("""src="([^"]+)"""").find(it)?.groupValues?.get(1) }
            ?.takeIf { it.contains("youtube") || it.contains("youtu.be") }
        val year = doc.select("span.dt").firstOrNull { it.text() == "Yapım" }?.parent()
            ?.selectFirst("a.category")?.text()?.take(4)?.toIntOrNull()
        val rating = Regex("""aggregateRating".*?"ratingValue":\s*([\d.]+)""", RegexOption.DOT_MATCHES_ALL)
            .find(doc.html())?.groupValues?.get(1)
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
                ?: fixUrlNull(doc.selectFirst("img.ic-afis")?.attr("data-src"))
            this.plot = doc.selectFirst(".ozet-ic")?.text()?.trim()
            this.year = year
            this.score = Score.from10(rating)
            this.tags = doc.select("span.dt").firstOrNull { it.text() == "Tür" }
                ?.parent()?.select("a")?.map { it.text().trim() }
            if (trailer != null) addTrailer(trailer)
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
        val html = runCatching { app.get(data, headers = siteHeaders).text }.getOrNull()
            ?: throw ErrorLoadingException("$name: kaynak bulunamadı.")
        val scx = Regex("""var scx = (\{.*?\});""", RegexOption.DOT_MATCHES_ALL).find(html)
            ?.groupValues?.get(1)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: throw ErrorLoadingException("$name: kaynak bulunamadı.")

        var found = false
        scx.keys().forEach { sourceKey ->
            val source = scx.optJSONObject(sourceKey) ?: return@forEach
            val label = decode(source.optString("tt")).trim().ifBlank { sourceKey }
            val streams = source.optJSONObject("sx")?.optJSONArray("t") ?: JSONArray()
            for (i in 0 until streams.length()) {
                val embed = decodeEncoded(streams.optString(i)) ?: continue
                val ok = when {
                    embed.contains("rapidvid.") -> resolveAtom(embed, label, subtitleFilter, linkCallback)
                    embed.contains(".m3u8") -> {
                        linkCallback(
                            newExtractorLink(name, label, embed, ExtractorLinkType.M3U8) {
                                this.referer = mainUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        true
                    }

                    else -> runCatching { loadExtractor(embed, mainUrl, subtitleFilter, linkCallback) }
                        .getOrDefault(false)
                }
                if (ok) found = true
            }
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir kaynak yok.")
        return found
    }

    /** rapidvid embed -> atomCodecTelemetry h264 HLS master + jwSetup.tracks subtitles. */
    private suspend fun resolveAtom(
        embed: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = runCatching {
            app.get(embed, headers = siteHeaders + mapOf("Referer" to mainUrl)).text
        }.getOrNull() ?: return false
        val telemetry = Regex("""atomCodecTelemetry=(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val stream = telemetry?.optString("h264")?.takeIf { it.startsWith("http") }
            ?: telemetry?.optString("av1")?.takeIf { it.startsWith("http") }
            ?: return false
        callback(
            newExtractorLink(name, label, stream, ExtractorLinkType.M3U8) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.Unknown.value
            }
        )
        val tracks = Regex("""jwSetup\.tracks\s*=\s*(\[.*?\]);""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.let { runCatching { JSONArray(it) }.getOrNull() }
        for (i in 0 until (tracks?.length() ?: 0)) {
            val track = tracks?.optJSONObject(i) ?: continue
            val file = track.optString("file").takeIf { it.isNotBlank() } ?: continue
            subtitleCallback(SubtitleFile(subtitleLang(track.optString("label")), file))
        }
        return true
    }

    private fun subtitleLang(label: String): String = when {
        label.contains("rkçe", true) || label.contains("ürk", true) || label.contains("Forced", true) -> "tr"
        label.contains("ngiliz", true) || label.contains("English", true) -> "en"
        else -> label.trim().ifBlank { "tr" }
    }

    /** Source entries are base64(rot13(url)); titles are plain base64. */
    private fun decodeEncoded(value: String): String? {
        if (value.isBlank()) return null
        val rotated = buildString(value.length) {
            value.forEach { c ->
                append(
                    when {
                        c in 'a'..'z' -> 'a' + (c - 'a' + 13) % 26
                        c in 'A'..'Z' -> 'A' + (c - 'A' + 13) % 26
                        else -> c
                    }
                )
            }
        }
        return decode(rotated)
    }

    private fun decode(value: String): String {
        if (value.isBlank()) return ""
        val padded = value.trim() + "=".repeat((4 - value.trim().length % 4) % 4)
        return runCatching { String(Base64.decode(padded, Base64.DEFAULT)) }.getOrDefault("")
    }
}
