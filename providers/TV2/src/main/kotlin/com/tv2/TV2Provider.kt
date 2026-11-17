package com.tv2

import com.bigbull.common.browserHeaders
import com.bigbull.common.posterSrcLazy
import com.lagradost.cloudstream3.Episode
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
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * TV2 — tv2.com.tr resmi VOD: dizi bölümleri ve filmler.
 *
 * Liste sayfaları `/diziler` (program-card) ve `/filmler` (thumbnail); film arşivinin
 * bir kısmı `/filmler?page=2`'de gelir. Bölüm listesi, dizi sayfasındaki "Bölümler"
 * bağlantısından alınır (arşiv dizilerde `/diziler/guncel/.../bolumler` gösterir) ve
 * `?page=N` ile sayfalanır.
 * Oynatıcı: sayfadaki `#video-element` data-id → `/action/media/<id>` JSON'undaki
 * `Media.Link.ServiceUrl` + `SecurePath` HLS yolunu verir.
 */
class TV2Provider : MainAPI() {
    override var mainUrl = "https://www.tv2.com.tr"
    override var name = "TV2"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage get() = listOf(
        MainPageData("Diziler", "$mainUrl/diziler"),
        MainPageData("Filmler", "$mainUrl/filmler"),
    ).filter { Settings.rowEnabled(it.name) }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) {
            return newHomePageResponse(request.name, emptyList<SearchResponse>(), hasNext = false)
        }
        val cards = app.get(request.data, headers = browserHeaders).document.cards().toMutableList()
        if (request.data.endsWith("/filmler")) {
            // Arşiv filmlerinin bir kısmı ikinci sayfada; tek satırda birleştirilir.
            cards += app.get("$mainUrl/filmler?page=2", headers = browserHeaders).document.cards()
        }
        return newHomePageResponse(request.name, cards.distinctBy { it.url }, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        val folded = fold(q)
        // Sitenin araması klipleri öne çıkarıyor; kataloğu da yerel olarak eşleştir.
        val catalog = runCatching {
            app.get("$mainUrl/diziler", headers = browserHeaders).document.cards() +
                app.get("$mainUrl/filmler", headers = browserHeaders).document.cards()
        }.getOrDefault(emptyList()).filter { fold(it.name).contains(folded) }
        val site = runCatching {
            app.get("$mainUrl/arama", params = mapOf("q" to q), headers = browserHeaders).document.cards()
        }.getOrDefault(emptyList())
        return (catalog + site).distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = fixUrlNull(url) ?: url
        val doc = runCatching { app.get(pageUrl, headers = browserHeaders).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val title = meta(doc, "og:title")
            ?: throw ErrorLoadingException("$name: başlık okunamadı. $REPORT_HINT")
        val poster = fixUrlNull(meta(doc, "og:image"))
        val plot = meta(doc, "og:description")

        if (SERIES_PAGE.containsMatchIn(pageUrl)) {
            // Bölüm listesi yalnızca "Bölümler" nav bağlantısı olan dizilerde var; arşiv
            // sayfaları bu bağlantıda /diziler/guncel/ yolunu verir.
            val listUrl = fixUrlNull(doc.selectFirst("a[href*='/bolumler']")?.attr("href"))
            val episodes = if (listUrl == null) emptyList() else episodes(listUrl)
            return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
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
        val pageUrl = fixUrlNull(data) ?: data
        val doc = runCatching { app.get(pageUrl, headers = browserHeaders).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: sayfa yüklenemedi. $REPORT_HINT")
        val videoId = doc.selectFirst("#video-element")?.attr("data-id")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("$name: video kimliği bulunamadı. $REPORT_HINT")
        val media = runCatching {
            JSONObject(app.get("$mainUrl/action/media/$videoId", headers = browserHeaders, referer = pageUrl).text)
        }.getOrNull() ?: throw ErrorLoadingException("$name: oynatıcı bilgisi okunamadı. $REPORT_HINT")
        val link = media.optJSONObject("Media")?.optJSONObject("Link")
            ?: throw ErrorLoadingException("$name: akış bilgisi yok. $REPORT_HINT")
        val service = link.optString("ServiceUrl").trim()
        val hls = link.optString("SecurePath").split(' ')
            .firstOrNull { it.contains(".m3u8") }
            ?.let { absolute(service, it) }
            ?: throw ErrorLoadingException("$name: HLS adresi bulunamadı. $REPORT_HINT")

        val stream = newExtractorLink(name, name, hls, ExtractorLinkType.M3U8) {
            this.referer = pageUrl
            this.headers = browserHeaders
            this.quality = Qualities.Unknown.value
        }
        if (Settings.qualityAllowed(stream.quality)) callback(stream)
        return true
    }

    /** Bölüm listesi sayfalanır (`?page=N`); sıra siteye göre değiştiği için numaraya göre dizilir. */
    private suspend fun episodes(listUrl: String): List<Episode> {
        val first = runCatching { app.get(listUrl, headers = browserHeaders).document }.getOrNull()
            ?: return emptyList()
        val lastPage = first.select("ul.pagination-numbers a[href]")
            .mapNotNull { PAGE.find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 1
        val pages = buildList {
            add(first)
            for (p in 2..minOf(lastPage, MAX_PAGES)) {
                runCatching {
                    app.get(listUrl, params = mapOf("page" to p.toString()), headers = browserHeaders).document
                }.getOrNull()?.let { add(it) }
            }
        }
        return pages.flatMap { doc ->
            doc.select("a.thumbnail[href]").mapNotNull { it.toEpisode() }
        }.distinctBy { it.data }.sortedBy { it.episode ?: 0 }
    }

    private fun Element.toEpisode(): Episode? {
        val href = fixUrlNull(attr("href")) ?: return null
        val name = selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return newEpisode(href) {
            this.name = name
            this.season = 1
            // Bazı kartların URL'sinde numara yok (".../camdaki-kiz-son-bolum"); başlıktan okunur.
            this.episode = EPISODE_NUM.find(href)?.groupValues?.get(1)?.toIntOrNull()
                ?: EPISODE_TITLE.find(name)?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    /** Liste sayfalarında iki kart düzeni var: `a.thumbnail` ve `div.program-card`. */
    private fun Document.cards(): List<SearchResponse> {
        val thumbnails = select("a.thumbnail[href]").mapNotNull { card ->
            val href = fixUrlNull(card.attr("href")) ?: return@mapNotNull null
            val img = card.selectFirst("img")
            val title = img?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                ?: card.selectFirst(".title")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            toCard(title, href, fixUrlNull(img?.posterSrcLazy()))
        }
        val programs = select("div.program-card").mapNotNull { card ->
            val href = fixUrlNull(card.selectFirst("a[href]")?.attr("href")) ?: return@mapNotNull null
            val title = card.selectFirst(".detail-title")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            toCard(title, href, fixUrlNull(card.selectFirst(".program-image")?.posterSrcLazy()))
        }
        return (thumbnails + programs).distinctBy { it.url }
    }

    private fun toCard(title: String, url: String, poster: String?): SearchResponse? = when {
        SERIES_PAGE.containsMatchIn(url) ->
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster }
        FILM_PAGE.containsMatchIn(url) ->
            newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster }
        else -> null
    }

    private fun absolute(service: String, path: String): String =
        if (path.startsWith("http")) path else service.trimEnd('/') + "/" + path.trimStart('/')

    companion object {
        val ROWS = listOf("Diziler", "Filmler")

        private const val REPORT_HINT = "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
        private const val MAX_PAGES = 30

        private val FILM_PAGE =
            Regex("""^https://www\.tv2\.com\.tr/filmler/(?:(?:guncel|arsiv)/)?[^/]+$""")
        private val SERIES_PAGE =
            Regex("""^https://www\.tv2\.com\.tr/diziler/(?:guncel|arsiv)/[^/]+$""")
        private val EPISODE_NUM = Regex("""-(\d+)-bolum""")
        private val EPISODE_TITLE = Regex("""(\d+)\.\s*Bölüm""", RegexOption.IGNORE_CASE)
        private val PAGE = Regex("""[?&]page=(\d+)""")

        private fun meta(doc: Document, property: String): String? =
            doc.selectFirst("meta[property=$property]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }

        private fun fold(value: String): String = value.lowercase()
            .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
            .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')
    }
}
