package com.canlidizi

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

class CanliDiziProvider : MainAPI() {
    private val uaHeaders get() = browserHeaders

    override var mainUrl = "https://canlidizi.sbs"
    override var name = "CanliDizi"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val hasMainPage = true
    override val mainPage get() = ROWS.mapNotNull { rowName ->
        if (!Settings.rowEnabled(rowName)) return@mapNotNull null
        MainPageData(rowName, when (rowName) {
            "Aile" -> "$mainUrl/tur/aile"
            "Aksiyon & Macera" -> "$mainUrl/tur/aksiyon-macera"
            "Animasyon" -> "$mainUrl/tur/animasyon"
            "Belgesel" -> "$mainUrl/tur/belgesel"
            "Bilim Kurgu & Fantastik" -> "$mainUrl/tur/bilim-kurgu-fantastik"
            "Çocuklar" -> "$mainUrl/tur/cocuklar"
            "Dram" -> "$mainUrl/tur/dram"
            "Gizem" -> "$mainUrl/tur/gizem"
            "Komedi" -> "$mainUrl/tur/komedi"
            "Reality" -> "$mainUrl/tur/reality"
            "Savaş & Politika" -> "$mainUrl/tur/savas-politika"
            "Suç" -> "$mainUrl/tur/suc"
            "Talk Show" -> "$mainUrl/tur/talk"
            "Western" -> "$mainUrl/tur/western"
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
            "Aile", "Aksiyon & Macera", "Animasyon", "Belgesel",
            "Bilim Kurgu & Fantastik", "Çocuklar", "Dram", "Gizem",
            "Komedi", "Reality", "Savaş & Politika", "Suç", "Talk Show", "Western",
        )
    }

    private val pathPrefixes = listOf("/dizi/")

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/ara?q=" + java.net.URLEncoder.encode(query, "UTF-8")
        return cards(app.get(url, headers = uaHeaders).document, query)
    }

    private fun cards(doc: Document, query: String? = null): List<SearchResponse> = doc.select("a[href]").mapNotNull { card ->
        val href = fixUrlNull(card.attr("href")) ?: return@mapNotNull null
        val path = card.attr("href").substringAfter(mainUrl, card.attr("href")).substringBefore('?')
        if (pathPrefixes.none { path.startsWith(it) }) return@mapNotNull null
        if (path.contains("login") || path.contains("uye-ol") || path.contains("/giris")) return@mapNotNull null
        val img = card.selectFirst("img") ?: return@mapNotNull null
        val title = card.attr("title").trim()
            .ifBlank { card.selectFirst("h2, h3, .title")?.text()?.trim().orEmpty() }
            .ifBlank { img.attr("alt").replace(Regex("""(?i)\s+(?:dizi|film)?\s*izle$"""), "").trim() }
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
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
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
        val resp = app.get(data, headers = uaHeaders)
        val text = resp.text.replace("\\/", "/").replace("\\\"", "\"")
        var found = false
        val seen = mutableSetOf<String>()
        suspend fun emit(stream: String, label: String, referer: String, forcedQuality: Int? = null) {
            val quality = forcedQuality ?: getQualityFromName(label)
            if (!stream.startsWith("http") || !seen.add("$quality|$stream")) return
            val type = if (stream.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            if (!Settings.qualityAllowed(quality)) return
            callback.invoke(newExtractorLink(name, label, stream, type) {
                this.referer = referer
                this.quality = quality
            })
            found = true
        }
        val pvs = (
            Regex("""data-pv=["']([^"']+)""").findAll(resp.text).map { it.groupValues[1] } +
                Regex("""s\.php\?s=([\w-]+)""").findAll(resp.text).map { it.groupValues[1] }
            ).distinct().toList()
        if (pvs.isNotEmpty()) {
            val host = Regex("""https?://([^/"']+)/assets/js/core\.js""").find(resp.text)?.groupValues?.get(1)
                ?.takeUnless { it.contains("kankaplayer") } ?: "pilavyerplay.top"
            val refUrl = "https://$host/"
            for ((idx, pv) in pvs.withIndex()) {
                val cfg = runCatching {
                    val js = app.get("https://$host/assets/js/s.php?s=$pv", headers = uaHeaders, referer = "$mainUrl/").text
                    JSONObject(js.substringAfter("__PLAYER__").substringAfter("=").substringBefore("</script>").trim().trimEnd(';'))
                }.getOrNull() ?: continue
                val stream = cfg.optString("stream").orEmpty()
                val baseLabel = if (pvs.size > 1) "Pilavyer ${idx + 1}" else "Pilavyer"
                if (stream.startsWith("http")) {
                    emit(stream, baseLabel, refUrl, forcedQuality = 0)
                    val masterText = runCatching { app.get(stream, headers = uaHeaders, referer = refUrl).text }.getOrNull().orEmpty()
                    for ((h, variantUrl) in parseHlsVariants(stream, masterText)) {
                        emit(variantUrl, "$baseLabel • ${h}p", refUrl, forcedQuality = h)
                    }
                    val subs = cfg.optJSONArray("subs")
                    for (i in 0 until (subs?.length() ?: 0)) {
                        val s = subs?.optJSONObject(i) ?: continue
                        val subSrc = s.optString("src")
                        val subLang = s.optString("lang", "tr")
                        if (subSrc.startsWith("http") && Settings.subtitleAllowed(subLang)) {
                            subtitleCallback.invoke(SubtitleFile(subLang, subSrc))
                        }
                    }
                }
            }
        }
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
            fixUrlNull(raw)?.takeUnless { it.contains("youtu") }
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
}
