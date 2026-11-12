package com.trdiziizle

import com.bigbull.common.browserHeaders
import com.bigbull.common.posterSrcLazy
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
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
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * TrDiziIzle — trdiziizle.tv (WordPress + "diziplus" teması, yalnız Türkçe diziler).
 *
 * Kök adres `/tr2/`ye 301 atar; arama `/?s=<terim>`, arşiv `/dizi-arsivi-01/` (460 dizi,
 * IMDb puanına göre azalan, tek yanıt), bölüm arşivi `/tum-bolumler/`.
 * Dizi sayfası `/diziler/<slug>/`, bölüm sayfası `/<slug>-izle/` biçimindedir; bölüm sayfası
 * `a[rel='category tag']` ile dizisine bağlanır. Dizi sayfasındaki bölüm listesi sayfalanmaz:
 * `/page/2/` aynı 53 kaydı tekrar basar.
 *
 * Oynatıcı: bölüm sayfası `/player/oynat/<hash>` iframe'i gömer. Oynatıcı sayfası referersiz
 * istekte 404 döndüğü için bölüm adresi `Referer` olarak gönderilir. jwplayer `sources` dizisi
 * ya ciner / Yandex Disk mp4 varyantlarını (`type: "mp4"`, 240p-1080p) ya da HLS master yazar
 * (`type: "hls"`, twimg/tabii). Eski bölümler yalnız YouTube embed'i taşır; doğrudan kaynak yok.
 * CDN'ler referer gönderilince 403 verdiği için stream linklerine referer eklenmez.
 */
class TrDiziIzleProvider : MainAPI() {
    override var mainUrl = "https://www.trdiziizle.tv"
    override var name = "TrDiziIzle"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    /** Ana sayfanın iki satırı aynı HTML'den okunur; tek istek yeter. */
    private var cache: Pair<String, Document>? = null

    override val mainPage get() = mainPageOf(
        "$mainUrl/" to "Son Eklenen Diziler",
        "$mainUrl/" to "Son Eklenen Bölümler",
        "$mainUrl/dizi-arsivi-01/" to "IMDb Puanına Göre",
    ).filter { Settings.rowEnabled(it.name) }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val doc = fetch(request.data)
        val items = if (request.name == "Son Eklenen Bölümler") doc.episodeCards() else doc.seriesCards()
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        search(query, 1)?.items ?: emptyList()

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val term = query.trim()
        // Site arama sonuçlarını tek yanıtta basıyor, sayfalayıcı yok.
        if (term.isEmpty() || page > 1) return newSearchResponseList(emptyList(), false)
        val doc = fetch("$mainUrl/?s=${URLEncoder.encode(term, "UTF-8")}")
        return newSearchResponseList(doc.seriesCards() + doc.episodeCards(), false)
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = fixUrl(url)
        val doc = fetch(pageUrl)
        // Bölüm sayfasının kendi bölüm listesi kısadır; künye ve tüm bölümler dizi sayfasında.
        val seriesUrl = doc.seriesUrl()?.takeIf { it != pageUrl }
        val seriesDoc = if (seriesUrl == null) doc else fetch(seriesUrl)
        val title = seriesDoc.selectFirst("#content h1")?.text()?.let(::cleanName)
            ?: throw ErrorLoadingException("$name: dizi adı okunamadı. $REPORT_HINT")
        val episodes = seriesDoc.episodes()
        if (episodes.isEmpty()) throw ErrorLoadingException("$name: bölüm listesi bulunamadı. $REPORT_HINT")

        return newTvSeriesLoadResponse(title, seriesUrl ?: pageUrl, TvType.TvSeries, episodes) {
            this.posterUrl = seriesDoc.selectFirst(".category_image img")?.posterSrcLazy()?.let { fixUrlNull(it) }
            this.plot = seriesDoc.selectFirst("#icerikcatright")?.ownText()?.trim()?.takeIf { it.isNotEmpty() }
            this.year = seriesDoc.dizimeta("Yapım Yılı")?.toIntOrNull()
            this.tags = seriesDoc.dizimeta("Tür")?.split(",")?.map(String::trim)?.filter(String::isNotEmpty)
            seriesDoc.dizimeta("Oyuncular")?.split(",")?.map(String::trim)
                ?.filter(String::isNotEmpty)?.let { addActors(it) }
            seriesDoc.selectFirst("iframe.trailer-video[src]")?.attr("src")?.trim()
                ?.takeIf { it.isNotEmpty() }?.let { addTrailer(fixUrl(it)) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageUrl = fixUrl(data)
        val doc = fetch(pageUrl)
        val embed = doc.selectFirst("#content .video-container iframe[src]")?.attr("src")?.trim()
            ?.takeIf { it.isNotEmpty() }?.let { fixUrl(it) }
            ?: throw ErrorLoadingException("$name: oynatıcı bulunamadı. $REPORT_HINT")
        if (!embed.contains("/player/")) {
            throw ErrorLoadingException(
                "$name: bu bölüm harici oynatıcıda (YouTube) yayınlanıyor, doğrudan kaynağı yok. $REPORT_HINT",
            )
        }
        // Oynatıcı yalnızca bölüm sayfasını referer kabul ediyor; referersiz istek 404 döner.
        val html = runCatching { app.get(embed, headers = browserHeaders, referer = pageUrl).text }.getOrNull()
            ?: throw ErrorLoadingException("$name: oynatıcı sayfası açılamadı. $REPORT_HINT")

        val links = ArrayList<ExtractorLink>()
        for (match in SOURCES.findAll(html)) {
            val src = match.groupValues[1].replace("\\/", "/")
            if (src.isBlank()) continue
            val label = match.groupValues[2]
            val kind = match.groupValues[3]
            val type = if (kind.equals("hls", true) || ".m3u8" in src) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }
            // CDN'ler (twimg, ciner) referer gönderilince 403 döndüğü için referer verilmez.
            links += newExtractorLink(name, label.ifBlank { "Kaynak" }, src, type) {
                this.quality = QUALITY.find(label)?.groupValues?.get(1)?.toIntOrNull() ?: Qualities.Unknown.value
            }
        }
        val found = links.distinctBy { it.url }
        if (found.isEmpty()) throw ErrorLoadingException("$name: oynatılabilir kaynak bulunamadı. $REPORT_HINT")
        val allowed = found.filter { Settings.qualityAllowed(it.quality) }
        // Kalite sınırı tüm kaynakları elediğinde en düşük kaliteli tek link verilir.
        (allowed.ifEmpty { listOfNotNull(found.minByOrNull { it.quality }) }).forEach(callback)
        return true
    }

    // ---------------------------------------------------------------- listeler

    private suspend fun fetch(url: String): Document {
        cache?.let { if (it.first == url) return it.second }
        val doc = runCatching { app.get(url, headers = browserHeaders).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: sayfa yüklenemedi. $REPORT_HINT")
        cache = url to doc
        return doc
    }

    /** Ada göre üç liste kabı da aynı kart yapısını kullanır: ana sayfa, arşiv, arama. */
    private fun Document.seriesCards(): List<SearchResponse> =
        select("#content div[id='list-series-main'], #content div.single-item, #content div[id='list-series']")
            .mapNotNull { it.toSeriesCard() }
            .distinctBy { it.url }

    private fun Element.toSeriesCard(): SearchResponse? {
        // Kart içinde iki bağlantı var: kapak (metinsiz) ve başlık.
        val link = select("a[href*='/diziler/']").firstOrNull { it.text().isNotBlank() } ?: return null
        val url = fixUrlNull(link.attr("href")) ?: return null
        val title = cleanName(link.text()) ?: return null
        val meta = link.parent()?.text().orEmpty()
        return newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
            this.posterUrl = selectFirst("img")?.posterSrcLazy()?.let { fixUrlNull(it) }
            this.year = HOME_YEAR.find(meta)?.value?.toIntOrNull()
            this.score = SCORE.find(meta)?.groupValues?.get(1)?.toDoubleOrNull()?.let { Score.from10(it) }
        }
    }

    private fun Document.episodeCards(): List<SearchResponse> =
        select("#content div.listepisodes").mapNotNull { card ->
            val url = card.selectFirst("a[href]")?.attr("href")?.let { fixUrlNull(it) } ?: return@mapNotNull null
            val series = card.selectFirst("#dizi-ismi a")?.text()?.trim().orEmpty()
            val episode = card.select("div.bolum-alt a").lastOrNull()?.text()?.trim().orEmpty()
            val title = listOf(series, episode).filter(String::isNotEmpty).joinToString(" ")
            if (title.isEmpty()) return@mapNotNull null
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = card.selectFirst("img[alt=afis]")?.posterSrcLazy()?.let { fixUrlNull(it) }
            }
        }.distinctBy { it.url }

    // ---------------------------------------------------------------- künye

    private fun Document.seriesUrl(): String? =
        selectFirst("a[rel='category tag'][href*='/diziler/']")?.attr("href")?.let { fixUrlNull(it) }

    /** `#icerikcat2` satırı etiket `<span>` + değer metin düğümü olarak gelir. */
    private fun Document.dizimeta(label: String): String? =
        select("#icerikcat2 span.dizimeta").firstOrNull { it.text().contains(label) }
            ?.nextSibling()?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    private fun Document.episodes(): List<Episode> {
        val rows = select("#content .bolumust").mapNotNull { row ->
            // Satırdaki ilk bağlantı "İzledim" (?wpfpaction=add…) bağlantısıdır.
            val link = row.select("a[href]").firstOrNull { !it.attr("href").startsWith("?") }
                ?: return@mapNotNull null
            val url = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
            val name = row.selectFirst(".baslik")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            url to name
        }.distinctBy { it.first }.reversed()
        return rows.mapIndexed { index, (url, name) ->
            newEpisode(url) {
                this.name = name
                this.episode = EPISODE.find(name)?.groupValues?.get(1)?.toIntOrNull() ?: (index + 1)
            }
        }
    }

    /** H1/kart başlığındaki `… izle Son Bölüm izle | Trdiziizle` eklerini atar. */
    private fun cleanName(raw: String): String? =
        raw.substringBefore('|').trim().replace(SUFFIX, "").trim().ifEmpty { null }

    companion object {
        val ROWS = listOf("Son Eklenen Diziler", "Son Eklenen Bölümler", "IMDb Puanına Göre")

        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"

        private val SUFFIX = Regex("""(?i)(?:\s+(?:son\s*bölüm|izle))+$""")
        private val EPISODE = Regex("""(\d+)\.\s*Bölüm""")
        private val HOME_YEAR = Regex("""\b(?:19|20)\d{2}\b""")
        private val SCORE = Regex("""IMDb:\s*([\d.]+)""")
        private val QUALITY = Regex("""(\d{3,4})""")
        private val SOURCES = Regex(
            """file\s*:\s*"([^"]+)"(?:\s*,\s*label\s*:\s*"([^"]*)")?(?:\s*,\s*type\s*:\s*"([^"]*)")?""",
        )
    }
}
