package com.sinezy

import com.bigbull.common.jsonLdDescription
import android.util.Base64
import com.bigbull.common.browserHeaders
import com.bigbull.common.parseHlsVariants
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
import java.net.URLEncoder
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

    private val headers get() = browserHeaders

    override val mainPage get() = listOf(
        MainPageData("Yeni Filmler", "$mainUrl/izle/en-yeni-filmler/"),
        MainPageData("Bilim Kurgu", "$mainUrl/izle/bilim-kurgu-filmleri/"),
        MainPageData("Animasyon", "$mainUrl/izle/animasyon-filmleri/"),
        MainPageData("Macera", "$mainUrl/izle/macera-filmleri/"),
        MainPageData("Suç", "$mainUrl/izle/suc-filmleri/"),
        MainPageData("Film Arşivi", "$mainUrl/film-arsivi/"),
    ).filter { Settings.rowEnabled(it.name) }

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
        val doc = app.get("$mainUrl/arama/?s=${URLEncoder.encode(query.trim(), "UTF-8")}", headers = headers).document
        return doc.select(".movie_box").mapNotNull { it.toCard() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = fixUrl(url)
        val doc = app.get(pageUrl, headers = headers).document
        val title = doc.selectFirst("h1")?.text()?.trim()?.removeSuffix(" izle")?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("$name: başlık okunamadı. $REPORT_HINT")
        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = doc.jsonLdDescription()
            ?: doc.selectFirst("div.desc, div.yeniscroll")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
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
        val rawPlayerUrl = fixUrlNull(Regex("""src=["']([^"']+)["']""").find(iframeHtml)?.groupValues?.get(1))
            ?: throw ErrorLoadingException("$name: oynatıcı adresi bulunamadı. $REPORT_HINT")
        val playerOrigin = rawPlayerUrl.substringBefore("/video/").lowercase()
        val playerUrl = "$playerOrigin/video/${rawPlayerUrl.substringAfter("/video/")}"

        val playerResp = app.get(playerUrl, headers = headers, referer = pageUrl)
        val playerHtml = playerResp.text
        val id = playerUrl.substringAfterLast('/').substringBefore('?').trim()
        if (id.isEmpty()) throw ErrorLoadingException("$name: oynatıcı kimliği bulunamadı. $REPORT_HINT")
        val api = "$playerOrigin/player/index.php?data=$id&do=getVideo"
        val response = app.post(
            api,
            data = mapOf("hash" to id, "r" to "$mainUrl/"),
            headers = headers + mapOf(
                "Referer" to playerUrl,
                "Origin" to playerOrigin,
                "X-Requested-With" to "XMLHttpRequest",
            ),
            cookies = playerResp.cookies,
        ).text
        val body = runCatching { JSONObject(response) }.getOrNull()
            ?: throw ErrorLoadingException("$name: oynatıcı yanıtı okunamadı. $REPORT_HINT")

        var found = false
        for (key in listOf("securedLink", "videoSource")) {
            val stream = body.optString(key).trim().replace("\\/", "/")
            if (!stream.startsWith("http")) continue
            val m3u8Text = runCatching {
                app.get(stream, headers = headers, referer = playerUrl).text
            }.getOrNull().orEmpty()
            if (!m3u8Text.trimStart().startsWith("#EXTM3U")) continue
            if (Settings.qualityAllowed(Qualities.Unknown.value)) {
                callback.invoke(
                    newExtractorLink(name, name, stream, ExtractorLinkType.M3U8) {
                        this.referer = playerUrl
                        this.headers = headers
                        this.quality = Qualities.Unknown.value
                    },
                )
            }
            for ((h, vUrl) in parseHlsVariants(stream, m3u8Text)) {
                if (!Settings.qualityAllowed(h)) continue
                callback.invoke(
                    newExtractorLink(name, "$name • ${h}p", vUrl, ExtractorLinkType.M3U8) {
                        this.referer = playerUrl
                        this.headers = headers
                        this.quality = h
                    },
                )
            }
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
        val ROWS = listOf("Yeni Filmler", "Bilim Kurgu", "Animasyon", "Macera", "Suç", "Film Arşivi")
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}
