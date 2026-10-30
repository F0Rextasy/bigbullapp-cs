package com.koreanturk

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

/** KoreanTurk — koreanturk.tv (koreanturk.com erişime engelli, .tv güncel adres) */
private fun Element.posterSrcLazy(): String? =
    attr("data-src").takeIf { it.isNotBlank() } ?: attr("src")

class KoreanTurkProvider : MainAPI() {
    override var mainUrl = "https://koreanturk.tv"
    private val uaHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9",
    )
    override var name = "KoreanTurk"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage get() = ROWS.mapNotNull { name ->
        if (!Settings.rowEnabled(name)) return@mapNotNull null
        when (name) {
            "Yeni Bölümler" -> MainPageData(name, "$mainUrl/")
            "Dizi Arşivi" -> MainPageData(name, "$mainUrl/dizi-arsivi/")
            "Romantik" -> MainPageData(name, "$mainUrl/dizi-kategori/romantik-dizileri-izle/")
            "Aksiyon" -> MainPageData(name, "$mainUrl/dizi-kategori/aksiyon-dizileri-izle/")
            "Komedi" -> MainPageData(name, "$mainUrl/dizi-kategori/komedi-dizileri-izle/")
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            request.data.contains("dizi-arsivi") && page > 1 -> "${request.data}page/$page/"
            request.data.contains("dizi-arsivi") -> request.data
            page > 1 -> "${request.data}page/$page/"
            else -> request.data
        }
        val doc = app.get(url, headers = uaHeaders).document
        val items = doc.select("div.movie-box, div.episode-box").mapNotNull { it.toSearchResult() }
        if (items.isEmpty()) throw ErrorLoadingException("$name: sayfa boş. $REPORT_HINT")
        return newHomePageResponse(request.name, items, hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("div.name a") ?: selectFirst("a[title]") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        val title = anchor.attr("title").ifBlank { anchor.text() }.trim()
            .ifBlank { selectFirst("span.serietitle")?.text()?.trim().orEmpty() }
        if (title.isBlank()) return null
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl = fixUrlNull(selectFirst("img")?.posterSrcLazy())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=${query.trim().replace(" ", "+")}", headers = uaHeaders).document
        return doc.select("div.movie-box").mapNotNull { box ->
            val anchor = box.selectFirst("div.name a") ?: return@mapNotNull null
            val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            val title = anchor.attr("title").ifBlank { anchor.text() }.trim()
                .ifBlank { box.selectFirst("span.serietitle")?.text()?.trim().orEmpty() }
            if (title.isBlank()) return@mapNotNull null
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = fixUrlNull(box.selectFirst("img")?.posterSrcLazy())
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url, headers = uaHeaders).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val title = doc.selectFirst("h1.film span.serietitle")?.text()?.trim()
            ?: doc.selectFirst("h1.film")?.ownText()?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: başlık bulunamadı. $REPORT_HINT")

        val episodes = LinkedHashMap<String, Episode>()
        fun collect(page: org.jsoup.nodes.Document) {
            page.select("div.episode-box").forEach { box ->
                val anchor = box.selectFirst("a[title]") ?: return@forEach
                val href = fixUrlNull(anchor.attr("href")) ?: return@forEach
                val label = anchor.attr("title").trim().ifBlank {
                    box.selectFirst("span.episodetitle")?.text()?.trim().orEmpty()
                }
                if (href !in episodes) {
                    episodes[href] = newEpisode(href) {
                        name = label.ifBlank { "Bölüm" }
                        season = Regex("""(\d+)\.\s*Sezon""").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                        episode = Regex("""(\d+)\.\s*Bölüm""").find(label)?.groupValues?.get(1)?.toIntOrNull()
                    }
                }
            }
        }
        collect(doc)

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.values.toList()) {
            posterUrl = fixUrlNull(doc.selectFirst("meta[property=\"og:image\"]")?.attr("content"))
            plot = doc.selectFirst("meta[property=\"og:description\"]")?.attr("content")
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
        val doc = runCatching { app.get(data, headers = uaHeaders).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: kaynak bulunamadı. $REPORT_HINT")

        val playerUrl = fixUrlNull(doc.selectFirst("div.video-content iframe")?.attr("src"))
            ?: throw ErrorLoadingException("$name: oynatıcı bulunamadı. $REPORT_HINT")

        // player.html?v=2&video=%2Fstorage%2F...%2Fname.m3u8[&subtitle=...]
        val params = playerUrl.substringAfter('?', "")
        val videoParam = Regex("""video=([^&]+)""").find(params)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("$name: video parametresi yok. $REPORT_HINT")
        val videoPath = java.net.URLDecoder.decode(videoParam, "UTF-8")
        if (!videoPath.endsWith(".m3u8")) throw ErrorLoadingException("$name: bilinmeyen kaynak formatı. $REPORT_HINT")

        var found = false

        // Subtitles: relative VTT files next to the m3u8 inside the player origin.
        val playerOrigin = playerUrl.substringBefore("/player/")
        val baseDir = videoPath.substringBeforeLast('/')
        val subCandidates = listOf(
            "$playerOrigin$baseDir/turkce.vtt" to "Türkçe",
            "$playerOrigin$baseDir/ingilizce.vtt" to "İngilizce",
        )
        val subtitleParam = Regex("""subtitle=([^&]+)""").find(params)?.groupValues?.get(1)
        if (subtitleParam != null) {
            val subUrl = java.net.URLDecoder.decode(subtitleParam, "UTF-8")
            val lang = Regex("""subtitleLang=([^&]+)""").find(params)?.groupValues?.get(1)
            subtitleFilter(SubtitleFile(Regex("""label=([^&]+)""").find(params)?.groupValues?.get(1) ?: lang ?: "Türkçe", fixUrlNull(subUrl) ?: subUrl))
            found = true
        } else {
            for ((subUrl, label) in subCandidates) {
                runCatching {
                    val head = app.head(subUrl, headers = uaHeaders + mapOf("Referer" to playerUrl))
                    if (head.isSuccessful) {
                        subtitleFilter(SubtitleFile(subUrl.substringBeforeLast('.').substringAfterLast('/'), subUrl))
                        found = true
                    }
                }
            }
        }

        callback.invoke(
            newExtractorLink(name, name, "$playerOrigin$videoPath", ExtractorLinkType.M3U8) {
                this.referer = playerUrl
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    companion object {
        /** Homepage rows in display order; all map to live listing pages. */
        val ROWS = listOf("Yeni Bölümler", "Dizi Arşivi", "Romantik", "Aksiyon", "Komedi")

        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}