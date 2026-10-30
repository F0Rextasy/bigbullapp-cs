package com.animeler

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

private fun Element.posterSrcLazy(): String? =
    attr("data-src").takeIf { it.isNotBlank() } ?: attr("src")

/** Animeler.live — Turkish subtitled anime catalogue; episodes expose ok.ru/embed mirrors. */
class AnimelerProvider : MainAPI() {
    private val uaHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9",
    )

    override var mainUrl = "https://animeler.live"
    override var name = "Animeler"
    override var lang = "tr"
    override val hasMainPage = true
    override val mainPage = mainPageOf(
        "$mainUrl/filtre?sort=popular" to "Popüler",
        "$mainUrl/filtre?sort=latest" to "Son Eklenenler",
    )
    override val supportedTypes = setOf(TvType.Anime)

    private val skip = setOf("/", "/filtre", "/takvim", "/birlikte", "/giris", "/kayit", "/az")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val doc = app.get(request.data, headers = uaHeaders).document
        return newHomePageResponse(request.name, cards(doc), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/filtre?search=${java.net.URLEncoder.encode(query, "UTF-8")}",
            headers = uaHeaders,
        ).document
        return cards(doc)
    }

    private fun cards(doc: Document): List<SearchResponse> = doc.select("a[href]").mapNotNull { card ->
        val href = fixUrlNull(card.attr("href")) ?: return@mapNotNull null
        val path = card.attr("href").substringBefore('?')
        if (!path.startsWith("/") || path in skip || path.startsWith("/filtre")) return@mapNotNull null
        val image = card.selectFirst("img") ?: return@mapNotNull null
        val title = card.attr("title").trim().ifBlank { card.text().trim() }
            .takeIf { it.isNotBlank() } ?: return@mapNotNull null
        newTvSeriesSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = fixUrlNull(image.posterSrcLazy())
        }
    }.distinctBy { it.url }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = uaHeaders).document
        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: başlık okunamadı")
        val seriesPath = url.substringAfter(mainUrl).substringBefore('?').trimEnd('/')
        val episodes = doc.select("a[href*='/bolum-']").mapNotNull { anchor ->
            val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            if (!href.contains("$seriesPath/bolum-")) return@mapNotNull null
            val number = Regex("""bolum-(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
            newEpisode(href) {
                this.name = anchor.text().trim().ifBlank { "Bölüm $number" }
                this.episode = number
            }
        }.distinctBy { it.data }.sortedBy { it.episode ?: 0 }
        if (episodes.isEmpty()) throw ErrorLoadingException("$name: bölüm bulunamadı")
        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
            this.plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
            this.year = Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
            this.score = Score.from10(doc.selectFirst("[itemprop=ratingValue]")?.attr("content"))
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val resp = app.get(data, headers = uaHeaders)
        val doc = resp.document
        val frames = doc.select("iframe[src]").mapNotNull { fixUrlNull(it.attr("src")) }.distinct()
        var found = false
        for (frame in frames) {
            val ok = if (frame.contains("ok.ru") || frame.contains("odnoklassniki")) {
                loadOkRu(frame, callback)
            } else {
                loadExtractor(frame, "$mainUrl/", subtitleCallback, callback)
            }
            if (ok) found = true
        }
        // Some episodes inject the iframe with JS; the raw page still has the stable id.
        val embedded = Regex("""(?:ok\.ru|odnoklassniki\.ru)/videoembed/(\d+)""")
            .findAll(resp.text).map { it.groupValues[1] }.distinct().take(3)
        for (id in embedded) {
            if (loadOkRu("https://ok.ru/videoembed/$id", callback)) found = true
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir akış yok")
        return found
    }

    private suspend fun loadOkRu(embed: String, callback: (ExtractorLink) -> Unit): Boolean {
        val id = Regex("""videoembed/(\d+)""").find(embed)?.groupValues?.get(1) ?: return false
        val body = runCatching {
            app.post(
                "https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$id",
                data = emptyMap(),
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 13)",
                    "Referer" to "https://ok.ru/",
                ),
            ).text
        }.getOrNull() ?: return false
        val videos = runCatching { JSONObject(body).optJSONArray("videos") }.getOrNull() ?: return false
        var found = false
        for (i in 0 until videos.length()) {
            val item = videos.optJSONObject(i) ?: continue
            val stream = item.optString("url").takeIf { it.startsWith("http") } ?: continue
            val label = item.optString("name").ifBlank { "OkRu" }
            callback.invoke(
                newExtractorLink(name, "OkRu $label", stream, ExtractorLinkType.VIDEO) {
                    this.referer = "https://ok.ru/"
                    this.quality = getQualityFromName(label.uppercase())
                    this.headers = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 13)")
                }
            )
            found = true
        }
        return found
    }

    companion object {
        val ROWS = listOf("Popüler", "Son Eklenenler")
    }
}
