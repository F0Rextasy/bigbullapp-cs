package com.cizgiste

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
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
import org.jsoup.nodes.Element

private fun Element.posterSrcLazy(): String? =
    attr("data-src").takeIf { it.isNotBlank() } ?: attr("src")

class CizgiSiteProvider : MainAPI() {
    override var mainUrl = "https://www.cizgi.site"
    override var name = "CizgiSite"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val uaHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9",
    )

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
        val doc = app.get(request.data, headers = uaHeaders).document
        val items = doc.select("div.ikon-dis a[href]").mapNotNull { it.toCard() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim().lowercase()
        if (term.isBlank()) return emptyList()
        val doc = app.get("$mainUrl/tr", headers = uaHeaders).document
        return doc.select("div.ikon-dis a[href]").mapNotNull { it.toCard() }
            .filter { it.name.lowercase().contains(term) || it.url.lowercase().contains(term) }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val page = app.get(url, headers = uaHeaders)
        val doc = page.document
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val page = app.get(data, headers = uaHeaders).text
        val frames = Regex("""(?:src|data-src)=["']([^"']*(?:vk\.com/video_ext|video_ext\.php)[^"']*)""", RegexOption.IGNORE_CASE)
            .findAll(page).map {
                val raw = it.groupValues[1]
                if (raw.startsWith("//")) "https:$raw" else fixUrl(raw)
            }.distinct().toList()
        var found = false
        for (frame in frames) {
            val embed = runCatching {
                app.get(frame, headers = uaHeaders, referer = data).text
                    .replace("\\\\/", "/").replace("\\/", "/")
            }.getOrNull() ?: continue
            val media = Regex(""""(mp4_\d+|hls|hls_fmp4)":"(https?://[^"]+)"""")
                .findAll(embed).map { it.groupValues[1] to it.groupValues[2].replace("\\/", "/") }
            for ((kind, url) in media) {
                val quality = getQualityFromName(kind)
                if (!Settings.qualityAllowed(quality)) continue
                val type = if (kind.startsWith("hls")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback.invoke(newExtractorLink(name, kind, url, type) {
                    this.referer = frame
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
