package com.dramadizilerim

import com.bigbull.common.browserHeaders
import com.bigbull.common.posterSrcLazy
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder

/**
 * DramaDizilerim — dramadizilerim.com (dikey mini dizi / kısa drama arşivi, Türkçe dublaj + altyazı).
 *
 * Listeler: `/dizi?page=N` katalog, `/drama-izle?page=N` popüler, `/turkce-dublaj-drama?page=N`,
 * platform hub'ları `/p/<platform>?page=N`. Arama `/diziler?q=<sorgu>`.
 *
 * Detay `/dizi/<slug>`: bölüm bağlantıları `/izle/<slug>?s=<sezon>&e=<bölüm>`.
 * Oynatıcı `/izle/` sayfasındaki `data-episode-id` ile `/api/playback` çağırır; yanıt
 * HLS/MP4 adresini `/api/media?ticket=` olarak verir.
 */
class DramaDizilerimProvider : MainAPI() {
    override var mainUrl = "https://dramadizilerim.com"
    override var name = "DramaDizilerim"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val uaHeaders get() = browserHeaders

    override val mainPage get() = ROWS.mapNotNull { name ->
        if (!Settings.rowEnabled(name)) return@mapNotNull null
        when (name) {
            "Son Eklenen" -> MainPageData(name, "$mainUrl/dizi?page=SAYFA")
            "Popüler" -> MainPageData(name, "$mainUrl/drama-izle?page=SAYFA")
            "Türkçe Dublaj" -> MainPageData(name, "$mainUrl/turkce-dublaj-drama?page=SAYFA")
            "DramaBox" -> MainPageData(name, "$mainUrl/p/dramabox?page=SAYFA")
            "ReelShort" -> MainPageData(name, "$mainUrl/p/reelshort?page=SAYFA")
            "ShortMax" -> MainPageData(name, "$mainUrl/p/shortmax?page=SAYFA")
            "DramaWave" -> MainPageData(name, "$mainUrl/p/dramawave?page=SAYFA")
            "GoodShort" -> MainPageData(name, "$mainUrl/p/goodshort?page=SAYFA")
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data.replace("SAYFA", page.toString()), headers = uaHeaders).document
        return newHomePageResponse(request.name, doc.cards(), hasNext = doc.hasNextPage())
    }

    override suspend fun search(query: String): List<SearchResponse> =
        search(query, 1)?.items ?: emptyList()

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val doc = app.get(
            "$mainUrl/diziler",
            params = mapOf("q" to query.trim(), "page" to page.toString()),
            headers = uaHeaders,
        ).document
        return newSearchResponseList(doc.cards(), doc.hasNextPage())
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = fixUrl(url)
        val doc = runCatching { app.get(pageUrl, headers = uaHeaders).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: dizi sayfası yüklenemedi. $REPORT_HINT")
        val title = doc.selectFirst("h1#series-title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("$name: dizi adı okunamadı. $REPORT_HINT")

        val info = doc.infoTable()
        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?.takeIf { it.isNotEmpty() }
        // Sitede tür/oyuncu/süre/fragman alanı yok; tek sınıflandırma platform kategorisi.
        val tags = doc.select("a[href^=/kategori/]").map { it.text().trim() }
            .filter { it.isNotEmpty() }.ifEmpty { info["Kategoriler"]?.select("a")?.map { it.text().trim() } ?: emptyList() }
        val score = Score.from10(
            SCORE.find(doc.selectFirst("span.wp-imdb")?.text().orEmpty())
                ?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
        )
        val recommendations = doc.select("a.card-link").mapNotNull { it.toSearchCard() }
            .distinctBy { it.url }

        // Sitede film kaydı yok; tür etiketi "Film" olursa tek parça olarak sunulur.
        if (info["Tür"]?.text()?.contains("Film", true) == true) {
            val watchUrl = fixUrlNull(doc.selectFirst("a.wp-play")?.attr("href")) ?: pageUrl
            return newMovieLoadResponse(title, pageUrl, TvType.Movie, watchUrl) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.score = score
                this.recommendations = recommendations
            }
        }

        val slug = pageUrl.substringAfter("/dizi/").substringBefore('?').trim('/')
        val watchBase = "$mainUrl/izle/$slug"
        val episodes = doc.select("a[href*=/izle/]").mapNotNull { anchor ->
            val link = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            if (link.substringBefore('?').trimEnd('/') != watchBase) return@mapNotNull null
            val query = link.substringAfter('?', "")
            val season = Regex("""(?:^|&)s=(\d+)""").find(query)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val number = Regex("""(?:^|&)e=(\d+)""").find(query)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            newEpisode(link) {
                this.season = season
                this.episode = number
                this.name = "S$season B$number"
            }
        }.sortedWith(compareBy({ it.season ?: Int.MAX_VALUE }, { it.episode ?: Int.MAX_VALUE }))
            .distinctBy { it.data }
        if (episodes.isEmpty()) throw ErrorLoadingException("$name: bölüm listesi bulunamadı. $REPORT_HINT")

        return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.score = score
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageUrl = fixUrl(data)
        val html = runCatching { app.get(pageUrl, headers = uaHeaders).text }.getOrNull()
            ?: throw ErrorLoadingException("$name: bölüm sayfası yüklenemedi. $REPORT_HINT")
        val episodeId = Regex("""data-episode-id="([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("$name: oynatıcı kimliği bulunamadı. $REPORT_HINT")
        val playback = runCatching {
            JSONObject(
                app.get(
                    "$mainUrl/api/playback",
                    params = mapOf("episodeId" to episodeId),
                    headers = uaHeaders,
                    referer = pageUrl,
                ).text,
            )
        }.getOrNull() ?: throw ErrorLoadingException("$name: oynatıcı yanıtı okunamadı. $REPORT_HINT")
        val stream = fixUrlNull(playback.optString("url"))
            ?: fixUrlNull(playback.optString("compatibilityUrl"))
            ?: throw ErrorLoadingException("$name: oynatılabilir akış yok. $REPORT_HINT")
        val quality = qualityOf(stream)
        if (!Settings.qualityAllowed(quality)) {
            throw ErrorLoadingException("$name: akış kalite sınırının üstünde. $REPORT_HINT")
        }
        val isHls = playback.optString("kind").contains("hls", true) || stream.contains(".m3u8")
        callback.invoke(
            newExtractorLink(name, if (quality > 0) "${quality}p" else "Otomatik", stream,
                if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.referer = pageUrl
                this.headers = uaHeaders
                this.quality = quality
            },
        )
        return true
    }

    // ---------------------------------------------------------------- listeler

    private fun Document.cards(): List<SearchResponse> = select(CARD)
        .mapNotNull { it.toSearchCard() }
        .distinctBy { it.url }

    private fun Element.toSearchCard(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        if (!href.contains("/dizi/")) return null
        val title = selectFirst("h3")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: attr("aria-label").substringBefore(" — ").trim().takeIf { it.isNotEmpty() }
            ?: selectFirst("p[title]")?.attr("title")?.trim()?.takeIf { it.isNotEmpty() }
            ?: selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = fixUrlNull(selectFirst("img")?.posterSrcLazy())
        }
    }

    /** Katalog ve arama listelerinde "Sonraki" bağlantısı sayfalamayı belirler. */
    private fun Document.hasNextPage(): Boolean = selectFirst("a[rel=next]") != null

    // ---------------------------------------------------------------- künye

    /** `div.wp-infobox` tablosu: Tür, Kategoriler, Eklenme Tarihi, Sezon Sayısı, Toplam Bölüm, Dil. */
    private fun Document.infoTable(): Map<String, Element> =
        select("div.wp-infobox tr").mapNotNull { row ->
            val head = row.selectFirst("th")?.text()?.trim() ?: return@mapNotNull null
            val cell = row.selectFirst("td") ?: return@mapNotNull null
            head to cell
        }.toMap()

    // ---------------------------------------------------------------- oynatıcı

    /** Kalite ipucu kaynak adresinde: `_1080/main.m3u8`, `?q=720p`, `.1080p.mp4`. */
    private fun qualityOf(stream: String): Int {
        val decoded = runCatching { URLDecoder.decode(stream, "UTF-8") }.getOrDefault(stream)
        val height = QUALITY.find(decoded)?.groupValues?.get(1)?.toIntOrNull()
            ?: return Qualities.Unknown.value
        return getQualityFromName("${height}p")
    }


    companion object {
        /** Homepage rows in display order; every entry maps to a live listing page. */
        val ROWS = listOf(
            "Son Eklenen",
            "Popüler",
            "Türkçe Dublaj",
            "DramaBox",
            "ReelShort",
            "ShortMax",
            "DramaWave",
            "GoodShort",
        )

        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"

        /** Katalog kartı, platform hub kartı ve arama satırı. */
        private const val CARD = "a.card-link, a.video-item-card, a.drama-card, a.video-link"
        private val SCORE = Regex("""(\d+(?:[.,]\d+)?)""")
        private val QUALITY = Regex("""(?:[_.]|q=)(\d{3,4})p?(?=[/&.]|$)""")
    }
}