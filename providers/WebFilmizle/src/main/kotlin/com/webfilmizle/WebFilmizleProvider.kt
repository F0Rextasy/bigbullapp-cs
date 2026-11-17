package com.webfilmizle

import com.bigbull.common.browserHeaders
import com.bigbull.common.evpMd5AesCbcDecrypt
import com.bigbull.common.jsonLdDescription
import com.bigbull.common.posterSrcLazy
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.Base64
import org.json.JSONObject
import org.jsoup.nodes.Document

/**
 * WebFilmizle — webfilmizle.com WordPress (filmplus teması) film arşivi.
 *
 * Arama `/?s=<sorgu>`; filmler `/page/N/`, türler `/kategori/<tür>/page/N/`.
 * Sayfadaki hotstream iframe'inin `#be-player-config` JSON'u,
 * `videoId` parolasıyla AES-CBC çözülüp BEpeak HLS master adresini verir.
 */
class WebFilmizleProvider : MainAPI() {
    override var mainUrl = "https://webfilmizle.com"
    override var name = "WebFilmizle"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie)

    private val headers get() = browserHeaders
    // hotstream /list/ 404s unless Accept is exactly */* ; the player must send it too.
    private val streamHeaders get() = browserHeaders + ("Accept" to "*/*")

    override val mainPage get() = mainPageOf(
        "$mainUrl/" to "Son Eklenen Filmler",
        "$mainUrl/kategori/aksiyon/" to "Aksiyon",
        "$mainUrl/kategori/komedi/" to "Komedi",
        "$mainUrl/kategori/korku/" to "Korku",
        "$mainUrl/kategori/dram/" to "Dram",
        "$mainUrl/kategori/bilim-kurgu/" to "Bilim Kurgu",
        "$mainUrl/kategori/fantastik/" to "Fantastik",
        "$mainUrl/kategori/gerilim/" to "Gerilim",
    ).filter { Settings.rowEnabled(it.name) }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data
        val url = if (page <= 1) base else "${base.trimEnd('/')}/page/$page/"
        val doc = app.get(url, headers = headers).document
        return newHomePageResponse(request.name, doc.cards(), hasNext = doc.hasNext(page))
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val term = query.trim()
        if (term.isEmpty()) return newSearchResponseList(emptyList(), false)
        val params = buildMap {
            put("s", term)
            if (page > 1) put("paged", page.toString())
        }
        val doc = app.get(mainUrl, params = params, headers = headers).document
        return newSearchResponseList(doc.cards(), doc.hasNext(page))
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = fixUrl(url)
        val doc = runCatching { app.get(pageUrl, headers = headers).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: film sayfası yüklenemedi. $REPORT_HINT")
        val title = doc.selectFirst("h1.title-border")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("$name: film başlığı okunamadı. $REPORT_HINT")
        val description = doc.selectFirst("#film-aciklama")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.jsonLdDescription()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
        val poster = fixUrlNull(doc.selectFirst(".film-afis img")?.attr("src"))
            ?: fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val year = doc.selectFirst("a[href*='/yil/']")?.text()?.trim()?.toIntOrNull()
            ?: YEAR.find(title)?.value?.toIntOrNull()
        return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageUrl = fixUrl(data)
        val html = runCatching { app.get(pageUrl, headers = headers).text }.getOrNull()
            ?: throw ErrorLoadingException("$name: film sayfası yüklenemedi. $REPORT_HINT")
        val iframes = IFRAME.findAll(html).map { it.groupValues[1] }.toList()
        val embed = iframes.firstOrNull { it.contains("hotstream") }
            ?: iframes.firstOrNull { !it.contains("youtube") && !it.contains("facebook") }
            ?: throw ErrorLoadingException("$name: oynatıcı adresi okunamadı. $REPORT_HINT")
        val embedDoc = runCatching { app.get(embed, headers = headers, referer = pageUrl).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: oynatıcı yanıt vermedi. $REPORT_HINT")
        val configRaw = embedDoc.selectFirst("#be-player-config")?.attr("data-config")?.trim()
            ?.takeIf { it.startsWith("{") }
            ?: throw ErrorLoadingException("$name: oynatıcı yapılandırması okunamadı. $REPORT_HINT")
        val stream = runCatching {
            val cfg = JSONObject(configRaw)
            val settings = JSONObject(cfg.optString("settings"))
            val clear = evpMd5AesCbcDecrypt(
                Base64.getDecoder().decode(settings.optString("ct")),
                cfg.optString("videoId"),
                settings.optString("s"),
                settings.optString("iv"),
            )
            JSONObject(clear).optString("video_location").trim()
        }.getOrNull().orEmpty()
        if (!stream.startsWith("http")) {
            throw ErrorLoadingException("$name: oynatılabilir akış yok. $REPORT_HINT")
        }
        val master = runCatching {
            app.get(stream, headers = streamHeaders, referer = embed).text
        }.getOrNull().orEmpty()
        if (master.isBlank()) {
            throw ErrorLoadingException("$name: akış listesi alınamadı. $REPORT_HINT")
        }
        val quality = MASTER_HEIGHT.findAll(master).mapNotNull { it.groupValues[1].toIntOrNull() }
            .maxOrNull() ?: Qualities.Unknown.value
        if (!Settings.qualityAllowed(quality)) {
            throw ErrorLoadingException("$name: akış kalite sınırının üstünde. $REPORT_HINT")
        }
        callback(newExtractorLink(name, name, stream, ExtractorLinkType.M3U8) {
            this.referer = embed
            this.headers = streamHeaders
            this.quality = quality
        })
        return true
    }

    private fun Document.cards(): List<SearchResponse> =
        select("div.movie-box").mapNotNull { box ->
            val link = box.selectFirst(".film-ismi a[href]") ?: return@mapNotNull null
            val href = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
            val img = box.selectFirst(".poster img") ?: return@mapNotNull null
            val title = link.text().trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = fixUrlNull(img.posterSrcLazy())
                this.year = YEAR.find(box.selectFirst(".film-yil")?.text().orEmpty())?.value?.toIntOrNull()
                this.score = box.selectFirst(".bolum-ust")?.text()?.let { value ->
                    SCORE.find(value)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
                        ?.let { Score.from10(it) }
                }
            }
        }.distinctBy { it.url }

    private fun Document.hasNext(page: Int): Boolean =
        select("div.sayfalama a[href*='/page/']").mapNotNull {
            PAGE_NO.find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        }.any { it > page }

    companion object {
        val ROWS = listOf(
            "Son Eklenen Filmler", "Aksiyon", "Komedi", "Korku", "Dram", "Bilim Kurgu", "Fantastik", "Gerilim",
        )
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
        private val YEAR = Regex("""\b((?:19|20)\d{2})\b""")
        private val SCORE = Regex("""(\d+[.,]\d+)""")
        private val PAGE_NO = Regex("""/page/(\d+)/""")
        private val IFRAME = Regex("""<iframe[^>]+src="([^"]+)"""", RegexOption.IGNORE_CASE)
        private val MASTER_HEIGHT = Regex("""RESOLUTION=\d+x(\d+)""")
    }
}
