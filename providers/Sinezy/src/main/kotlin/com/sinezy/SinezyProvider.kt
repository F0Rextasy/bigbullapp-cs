package com.sinezy

import android.util.Base64
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
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Element

/**
 * Sinezy — sinezy.si film arşivi.
 *
 * Arama `/arama/?s=<sorgu>`; detay sayfası ilk oynatıcı iframe'ini base64
 * `ilkpartkod` içinde taşır. Iframe `player.filmizle.in` FirePlayer'dır:
 * `POST /player/index.php?data=<id>&do=getVideo` → `securedLink` HLS master.
 */
class SinezyProvider : MainAPI() {
    override var mainUrl = "https://sinezy.si"
    override var name = "Sinezy"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9",
    )

    override val mainPage get() = listOf(
        MainPageData("Yeni Filmler", "$mainUrl/izle/en-yeni-filmler/"),
    )

    private fun Element.toCard(): SearchResponse? {
        val anchor = selectFirst("a.image") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        val title = selectFirst("h2")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: anchor.attr("title").removeSuffix(" izle").trim().takeIf { it.isNotEmpty() }
            ?: return null
        val poster = fixUrlNull(selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } })
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
        val doc = app.get(request.data, headers = headers).document
        return newHomePageResponse(request.name, doc.select(".movie_box").mapNotNull { it.toCard() }, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/arama/?s=${query.trim()}", headers = headers).document
        return doc.select(".movie_box").mapNotNull { it.toCard() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = fixUrl(url)
        val doc = app.get(pageUrl, headers = headers).document
        val title = doc.selectFirst("h1")?.text()?.trim()?.removeSuffix(" izle")?.takeIf { it.isNotEmpty() }
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
        val encoded = Regex("""var\s+ilkpartkod\s*=\s*'([^']+)'""", RegexOption.DOT_MATCHES_ALL)
            .find(page)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("$name: oynatıcı verisi bulunamadı. $REPORT_HINT")
        val iframeHtml = runCatching {
            Base64.decode(encoded, Base64.DEFAULT).toString(Charsets.UTF_8)
        }.getOrNull() ?: throw ErrorLoadingException("$name: oynatıcı verisi çözülemedi. $REPORT_HINT")
        val playerUrl = fixUrlNull(Regex("""src=["']([^"']+)["']""").find(iframeHtml)?.groupValues?.get(1))
            ?: throw ErrorLoadingException("$name: oynatıcı adresi bulunamadı. $REPORT_HINT")

        val playerHtml = app.get(playerUrl, headers = headers, referer = pageUrl).text
        val id = playerUrl.substringAfterLast('/').substringBefore('?').trim()
        if (id.isEmpty()) throw ErrorLoadingException("$name: oynatıcı kimliği bulunamadı. $REPORT_HINT")
        val api = "${playerUrl.substringBefore("/video/")}/player/index.php?data=$id&do=getVideo"
        val response = app.post(
            api,
            data = mapOf("hash" to id, "r" to "$mainUrl/"),
            headers = headers + mapOf(
                "Referer" to playerUrl,
                "Origin" to playerUrl.substringBefore("/video/"),
                "X-Requested-With" to "XMLHttpRequest",
            ),
        ).text
        val body = runCatching { JSONObject(response) }.getOrNull()
            ?: throw ErrorLoadingException("$name: oynatıcı yanıtı okunamadı. $REPORT_HINT")

        var found = false
        for (key in listOf("securedLink", "videoSource")) {
            val stream = body.optString(key).trim().replace("\\/", "/")
            if (!stream.startsWith("http")) continue
            val live = runCatching {
                app.get(stream, headers = headers, referer = playerUrl).text.trimStart().startsWith("#EXTM3U")
            }.getOrDefault(false)
            if (!live) continue
            callback.invoke(
                newExtractorLink(name, name, stream, ExtractorLinkType.M3U8) {
                    this.referer = playerUrl
                    this.headers = headers
                    this.quality = Qualities.Unknown.value
                },
            )
            found = true
            break
        }

        if (Settings.subtitleLang != "off") {
            Regex("""playerjsSubtitle\s*=\s*"\[([^\]]+)\]([^"]+)""")
                .find(playerHtml)?.let { match ->
                    val language = match.groupValues[1]
                    val subtitle = match.groupValues[2].replace("\\/", "/")
                    if (Settings.subtitleAllowed(language)) {
                        subtitleCallback.invoke(newSubtitleFile(language, subtitle))
                    }
                }
        }

        if (!found) throw ErrorLoadingException("$name: oynatılabilir akış yok. $REPORT_HINT")
        return true
    }

    companion object {
        val ROWS = listOf("Yeni Filmler")
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}
