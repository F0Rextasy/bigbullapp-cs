package com.dizibaba

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
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Element

/**
 * Dizibaba — dizibaba.net film arşivi.
 *
 * Film detayında `/izle` sayfasındaki `data-pv` slug'ı
 * `pilavyerplay.top/assets/js/s.php?s=<slug>` player'ını yükler; yanıt
 * `window.__PLAYER__.stream` imzalı HLS master adresini taşır.
 */
class DizibabaProvider : MainAPI() {
    override var mainUrl = "https://dizibaba.net"
    override var name = "Dizibaba"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9",
    )

    override val mainPage get() = listOf(
        MainPageData("Filmler", "$mainUrl/filmler"),
    )

    private fun Element.toCard(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        if (!href.contains("/film/")) return null
        val img = selectFirst("img")
        val title = img?.attr("alt")?.removeSuffix(" Film izle")?.trim()?.takeIf { it.isNotEmpty() }
            ?: selectFirst("h3")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = fixUrlNull(img?.attr("src"))
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
        val doc = app.get(request.data, headers = headers).document
        return newHomePageResponse(request.name, doc.select("a[href*=/film/]").mapNotNull { it.toCard() }, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/ara?q=${query.trim()}", headers = headers).document
        return doc.select("a[href*=/film/]").mapNotNull { it.toCard() }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = fixUrl(url)
        val doc = app.get(pageUrl, headers = headers).document
        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("$name: başlık okunamadı. $REPORT_HINT")
        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?.takeIf { it.isNotEmpty() }
        return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageUrl = fixUrl(data)
        val page = app.get(pageUrl, headers = headers).text
        val playerPage = if (page.contains("data-pv=")) {
            page
        } else {
            app.get(pageUrl.trimEnd('/') + "/izle", headers = headers).text
        }
        val slug = Regex("""data-pv=["']([^"']+)""").find(playerPage)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("$name: oynatıcı kimliği bulunamadı. $REPORT_HINT")
        val base = Regex("""src=["'](https?://[^"']+/assets/js/core\.js)""")
            .find(playerPage)?.groupValues?.get(1)?.substringBefore("/assets/js/core.js")
            ?: "https://pilavyerplay.top"

        val playerBody = app.get("$base/assets/js/s.php?s=$slug", headers = headers, referer = pageUrl).text
        val json = Regex("""window\.__PLAYER__\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
            .find(playerBody)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("$name: player yapılandırması okunamadı. $REPORT_HINT")
        val stream = runCatching { JSONObject(json).optString("stream") }.getOrDefault("")
            .replace("\\/", "/")
            .trim()
        if (!stream.startsWith("http")) {
            throw ErrorLoadingException("$name: oynatılabilir akış yok. $REPORT_HINT")
        }
        val live = runCatching {
            app.get(stream, headers = headers, referer = "$base/").text.trimStart().startsWith("#EXTM3U")
        }.getOrDefault(false)
        if (!live) throw ErrorLoadingException("$name: HLS kaynağı yanıt vermedi. $REPORT_HINT")

        callback.invoke(
            newExtractorLink(name, name, stream, ExtractorLinkType.M3U8) {
                this.referer = "$base/"
                this.headers = headers
                this.quality = Qualities.Unknown.value
            },
        )
        return true
    }

    companion object {
        val ROWS = listOf("Filmler")
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}
