package com.izlefilm

import com.bigbull.common.browserHeaders
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
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * IzleFilm — izlefilm.net (Türkçe dublaj/altyazılı film arşivi, ~272 film).
 *
 * Liste `/filmler?sayfa=N`; tür `/filmler?tur=<Tür>` (sayfa parametresi sonda olacak şekilde
 * 301 ile normalize edilir), sıralama `?sirala=imdb_desc`. Arama `/arama?q=<sorgu>&sayfa=N`
 * (`?s=` değil; sayfa `a[rel=next]` ile ilerler). Kart `article.movie-card`.
 *
 * Detay `/film/<slug>`: başlık/yıl `div.watch-title`, künye `div.watch-details`
 * (`p.detail-summary`, `ul.tag-list`, `.imdb-score`), poster `img.detail-poster`.
 * Kaynaklar `div.source-tabs button[data-player-url]`; hepsi ok.ru `videoembed/<id>`,
 * ayrıca fragman için YouTube sekmesi var (fragman akış olarak verilmez).
 */
class IzleFilmProvider : MainAPI() {
    override var mainUrl = "https://izlefilm.net"
    override var name = "IzleFilm"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    private val uaHeaders get() = browserHeaders

    override val mainPage get() = ROW_PATHS
        .filter { Settings.rowEnabled(it.first) }
        .map { MainPageData(it.first, "$mainUrl/${it.second}") }

    private fun Document.cards(): List<SearchResponse> =
        select("article.movie-card").mapNotNull { it.toCard() }

    /** Liste/arama kartı: poster `img`, yıl `.card-year`, puan `.card-imdb`, başlık `.movie-title`. */
    private fun Element.toCard(): SearchResponse? {
        val href = fixUrlNull(selectFirst("a[href*='/film/']")?.attr("href") ?: return null) ?: return null
        val title = selectFirst("h3.movie-title")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = fixUrlNull(selectFirst("img")?.attr("src"))
            this.year = selectFirst(".card-year")?.text()?.trim()?.toIntOrNull()
            this.score = Score.from10(selectFirst(".card-imdb")?.text()?.filter { it.isDigit() || it == '.' })
        }
    }

    /** Ana sayfadaki "Editörün Önerileri" vitrini (`article.editor-slide`, 4 film). */
    private fun Element.toEditorCard(): SearchResponse? {
        val href = fixUrlNull(selectFirst("a.editor-cta[href]")?.attr("href") ?: return null) ?: return null
        val title = selectFirst("h3")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = fixUrlNull(selectFirst("a.editor-poster img")?.attr("src"))
            this.year = selectFirst(".editor-meta span")?.text()?.trim()?.toIntOrNull()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data.replace(PAGE, page.toString()), headers = uaHeaders).document
        if (!request.data.contains(PAGE)) {
            return newHomePageResponse(request.name, doc.select("article.editor-slide").mapNotNull { it.toEditorCard() }, false)
        }
        return newHomePageResponse(request.name, doc.cards(), hasNext = doc.selectFirst("a[rel=next]") != null)
    }

    override suspend fun search(query: String): List<SearchResponse> = search(query, 1)?.items ?: emptyList()

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val term = query.trim()
        if (term.isEmpty()) return newSearchResponseList(emptyList(), false)
        val doc = app.get(
            "$mainUrl/arama?q=${URLEncoder.encode(term, "UTF-8")}&sayfa=$page",
            headers = uaHeaders,
        ).document
        return newSearchResponseList(doc.cards(), doc.selectFirst("a[rel=next]") != null)
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = fixUrl(url)
        val doc = app.get(pageUrl, headers = uaHeaders).document
        val title = doc.selectFirst("div.watch-title h1")?.text()?.replace(YEAR_TAIL, "")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("$name: başlık okunamadı. $REPORT_HINT")
        val year = doc.selectFirst("div.watch-title h1 span")?.text()?.let { YEAR.find(it)?.value?.toIntOrNull() }
            ?: doc.selectFirst("ul.tag-list a[href*=yil]")?.text()?.trim()?.toIntOrNull()
        val tags = doc.select("ul.tag-list a[href*=tur]").map { it.text().trim() }.filter { it.isNotEmpty() }
        return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
            this.posterUrl = fixUrlNull(doc.selectFirst("img.detail-poster")?.attr("src"))
                ?: fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
            this.plot = doc.selectFirst("p.detail-summary")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            this.year = year
            this.tags = tags.ifEmpty { null }
            this.score = Score.from10(doc.selectFirst(".imdb-score strong")?.text())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val doc = app.get(fixUrl(data), headers = uaHeaders).document
        val sources = doc.select("div.source-tabs button[data-player-url]")
            .map { it.attr("data-player-url") to it.selectFirst("span")?.text()?.trim().orEmpty() }
            .filter { it.first.isNotBlank() }
            .ifEmpty { doc.select("iframe[src]").map { it.attr("src") to "" }.filter { it.first.isNotBlank() } }

        var found = false
        for ((embed, label) in sources) {
            when {
                // YouTube sekmesi yalnız fragman; akış/bölüm olarak verilmez.
                embed.contains("youtube") || label.equals("Fragman", ignoreCase = true) -> Unit
                embed.contains("ok.ru") || embed.contains("odnoklassniki") ->
                    if (loadOkRu(embed, label, callback)) found = true
                else -> if (loadExtractor(embed, "$mainUrl/", subtitleCallback, callback)) found = true
            }
        }
        return found
    }

    /**
     * ok.ru `videoembed/<id>` kabuğu; aynı kimliğe `POST /dk?cmd=videoPlayerMetadata&mid=<id>`
     * player durumunu JSON olarak verir: imzalı HLS master `hlsManifestUrl`, o yoksa doğrudan
     * mp4 adresleri `videos[]`. Linkler istemci IP'sine bağlı ve ~1 gün geçerlidir.
     */
    private suspend fun loadOkRu(embed: String, label: String, callback: (ExtractorLink) -> Unit): Boolean {
        val id = OK_ID.find(embed)?.groupValues?.get(1) ?: return false
        val body = runCatching {
            app.post(
                "https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$id",
                data = emptyMap(),
                headers = mapOf("Referer" to "https://ok.ru/"),
            ).text
        }.getOrNull() ?: return false
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return false
        val source = if (label.isBlank()) "ok.ru" else "ok.ru $label"

        json.optString("hlsManifestUrl").takeIf { it.isNotBlank() }?.let { hls ->
            callback.invoke(newExtractorLink(name, "$source (HLS)", hls, ExtractorLinkType.M3U8) {
                this.referer = "https://ok.ru/"
                this.headers = browserHeaders
                this.quality = Qualities.Unknown.value
            })
            return true
        }

        val videos = json.optJSONArray("videos") ?: return false
        var found = false
        for (index in 0 until videos.length()) {
            val video = videos.optJSONObject(index) ?: continue
            val stream = video.optString("url").takeIf { it.isNotBlank() } ?: continue
            val bucket = video.optString("name")
            val quality = when (bucket.lowercase()) {
                "mobile" -> 144
                "lowest" -> 240
                "low" -> 360
                "sd" -> 480
                "hd" -> 720
                "full" -> 1080
                else -> Qualities.Unknown.value
            }
            if (!Settings.qualityAllowed(quality)) continue
            found = true
            callback.invoke(
                newExtractorLink(
                    name,
                    "$source ${if (quality > 0) "${quality}p" else bucket}",
                    stream,
                    ExtractorLinkType.VIDEO,
                ) {
                    this.referer = "https://ok.ru/"
                    this.headers = browserHeaders
                    this.quality = quality
                },
            )
        }
        return found
    }

    companion object {
        private const val PAGE = "SAYFA"

        /** Ana sayfa satırları; ayarlardan kapatılabilir. */
        private val ROW_PATHS = listOf(
            "Seçkiler" to "",
            "Son Eklenen" to "filmler?sayfa=$PAGE",
            "IMDb En Yüksek" to "filmler?sirala=imdb_desc&sayfa=$PAGE",
            "Aksiyon" to "filmler?tur=Aksiyon&sayfa=$PAGE",
            "Komedi" to "filmler?tur=Komedi&sayfa=$PAGE",
            "Dram" to "filmler?tur=Dram&sayfa=$PAGE",
            "Korku" to "filmler?tur=Korku&sayfa=$PAGE",
            "Gerilim" to "filmler?tur=Gerilim&sayfa=$PAGE",
            "Bilim Kurgu" to "filmler?tur=Bilim-Kurgu&sayfa=$PAGE",
            "Animasyon" to "filmler?tur=Animasyon&sayfa=$PAGE",
            "Romantik" to "filmler?tur=Romantik&sayfa=$PAGE",
            "Macera" to "filmler?tur=Macera&sayfa=$PAGE",
            "Fantastik" to "filmler?tur=Fantastik&sayfa=$PAGE",
        )
        val ROWS = ROW_PATHS.map { it.first }

        private val OK_ID = Regex("""videoembed/(\d+)""")
        private val YEAR = Regex("""\d{4}""")
        private val YEAR_TAIL = Regex("""\s*\(\d{4}\)\s*$""")
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}
