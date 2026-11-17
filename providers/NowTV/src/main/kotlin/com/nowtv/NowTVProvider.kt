package com.nowtv

import com.bigbull.common.browserHeaders
import com.bigbull.common.parseHlsVariants
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
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * NowTV — nowtv.com.tr resmî arşivi (dizi, program, film).
 * Arşiv listeleri `/ajax/archive` (data-type=series) ve `/ajax/programs` (data-type=movies) ile sayfalanır;
 * oynatma tek uçtan gelir: `POST /ajax/stream {video_id}` → erbvr master m3u8 (360p–1080p).
 */

class NowTVProvider : MainAPI() {
    override var mainUrl = "https://www.nowtv.com.tr"
    override var name = "NowTV"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val uaHeaders get() = browserHeaders

    override val mainPage get() = ROWS.mapNotNull { row ->
        if (!Settings.rowEnabled(row)) return@mapNotNull null
        when (row) {
            "Öne Çıkanlar" -> MainPageData(row, "home|ÖNE ÇIKANLAR")
            "Yeni Bölümler" -> MainPageData(row, "home|YENİ BÖLÜMLER")
            "Eskimeyen Diziler" -> MainPageData(row, "home|ESKİMEYEN DİZİLER")
            "Diziler" -> MainPageData(row, "series|$mainUrl/dizi-arsivi")
            "Filmler" -> MainPageData(row, "movies|$mainUrl/film-izle")
            "Programlar" -> MainPageData(row, "single|$mainUrl/program-izle")
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val kind = request.data.substringBefore('|')
        val arg = request.data.substringAfter('|')
        val (items, hasNext) = when (kind) {
            "home" -> homeRow(arg) to false
            "single" -> listCards(app.get(arg, headers = uaHeaders).document, TvType.TvSeries) to false
            else -> listPage(page, kind, arg)
        }
        return newHomePageResponse(request.name, items, hasNext)
    }

    private suspend fun homeRow(label: String): List<SearchResponse> {
        val doc = app.get(mainUrl, headers = uaHeaders).document
        val section = doc.select("section.videos").firstOrNull {
            it.selectFirst("h2")?.text()?.trim()?.equals(label, ignoreCase = true) == true
        } ?: return emptyList()
        val hint = if (label == "YENİ BÖLÜMLER") TvType.Movie else TvType.TvSeries
        return section.select("div.poster, div.thumb")
            .mapNotNull { it.toSearchResult(hint) }
            .distinctBy { it.url }
    }

    /** İlk sayfa sunucudan gelir; sonrakiler `/ajax/archive` (series) ya da `/ajax/programs` (movies) ile. */
    private suspend fun listPage(page: Int, kind: String, url: String): Pair<List<SearchResponse>, Boolean> {
        val hint = if (kind == "movies") TvType.Movie else TvType.TvSeries
        if (page <= 1) {
            val doc = app.get(url, headers = uaHeaders).document
            val total = doc.selectFirst(".ajax-load-more-archive, .ajax-load-more-programs")
                ?.attr("data-rows")?.toIntOrNull() ?: 0
            return listCards(doc, hint) to (total > PAGE_SIZE)
        }
        val endpoint = if (kind == "movies") "/ajax/programs" else "/ajax/archive"
        val json = JSONObject(
            app.post(
                mainUrl + endpoint,
                data = mapOf(
                    "page" to (page - 1).toString(),
                    "type" to kind,
                    "count" to PAGE_SIZE.toString(),
                    "orderBy" to "id",
                    "sorting" to "desc",
                ),
                headers = uaHeaders,
            ).text
        )
        val items = Jsoup.parse(json.optString("data")).select("div.list-item")
            .mapNotNull { it.toSearchResult(hint) }
        return items to (page * PAGE_SIZE < json.optInt("count", 0))
    }

    private fun listCards(doc: Document, hint: TvType): List<SearchResponse> =
        doc.select("div.list-item").mapNotNull { it.toSearchResult(hint) }.distinctBy { it.url }

    /**
     * `POST /arama` yalnızca POST edilen arama kutusunun HTML parçasını döndürür; kartlarda başlık alanı yok
     * (img alt boş), bu yüzden başlık URL slug'ından üretilir.
     * shortcut: site arama kartlarına başlık eklerse slug yerine alt/title okunmalı.
     */
    override suspend fun search(query: String): List<SearchResponse> {
        val html = runCatching {
            app.post("$mainUrl/arama", data = mapOf("query" to query.trim()), headers = uaHeaders).text
        }.getOrNull() ?: return emptyList()
        val doc = Jsoup.parse(html)
        val out = ArrayList<SearchResponse>()
        for (heading in doc.select("h2")) {
            val hint = when (heading.text().trim()) {
                "DİZİLER", "PROGRAMLAR" -> TvType.TvSeries
                "FİLMLER" -> TvType.Movie
                else -> continue // OYUNCULAR / SUNUCULAR
            }
            val row = postersRow(heading) ?: continue
            row.select("div.poster").mapNotNullTo(out) { it.toSearchResult(hint) }
        }
        return out.distinctBy { it.url }
    }

    private fun Element.toSearchResult(hint: TvType): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        if (href.contains("/oyuncular/")) return null
        val img = selectFirst("img")
        val title = img?.attr("alt")?.trim().orEmpty()
            .ifBlank { selectFirst("strong")?.text()?.trim().orEmpty() }
            .ifBlank { slugTitle(href) }
        if (title.isBlank()) return null
        val type = when {
            href.contains("/bolum/") || href.contains("/fragman/") || href.contains("/ozel-sahne/") -> TvType.Movie
            else -> hint
        }
        val poster = fixUrlNull(img?.posterSrcLazy())
        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href, type) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, type) { this.posterUrl = poster }
        }
    }

    /** `POST /arama` parçasında h2 bir `.container` içinde; kartlar bir sonraki `.posters` bölümünde. */
    private fun postersRow(heading: Element): Element? {
        var node: Element? = heading.parent()
        repeat(3) {
            val current = node ?: return null
            if (current.hasClass("posters")) return current
            node = current.nextElementSibling()
        }
        return null
    }

    private fun slugTitle(url: String): String =
        url.substringAfter(mainUrl).trim('/').removeSuffix("/izle").substringBefore('?')
            .substringAfterLast('/').replace('-', ' ').trim()

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url, headers = uaHeaders).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: içerik yüklenemedi.")
        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringAfterLast(" - ")?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: başlık okunamadı")
        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = seriesPlot(doc)
            ?: doc.selectFirst("div.program-desc")?.text()?.trim()?.takeIf { it.length > 40 }

        // Dizi sayfaları da (son bölüm için) oynatıcı div'i taşır; ayırt edici işaret bölüm listesidir.
        val seriesPage = !EPISODE_PATH.containsMatchIn(url) &&
            (doc.selectFirst("#video-finder-changer") != null || doc.selectFirst("a[href$=bolumler]") != null)
        if (seriesPage) {
            val episodes = episodes(url)
            if (episodes.isEmpty()) throw ErrorLoadingException("$name: bölüm listesi bulunamadı")
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    /**
     * `/X/bolumler` sayfası tüm sezonların bölümlerini tek listede verir (`#video-finder-changer option[data-target]`).
     * shortcut: bölüm seçicide sezon numarası yok; tek sezonlu yapımlarda season=1, çok sezonlularda null kalır.
     */
    private suspend fun episodes(programUrl: String): List<Episode> {
        val base = programUrl.trimEnd('/').removeSuffix("/izle")
        val doc = runCatching { app.get("$base/bolumler", headers = uaHeaders).document }.getOrNull()
            ?: return emptyList()
        val singleSeason = doc.select("#video-filter-changer option")
            .count { it.text().contains("SEZON", ignoreCase = true) } <= 1
        return doc.select("#video-finder-changer option[data-target]").mapNotNull { option ->
            val href = fixUrlNull(option.attr("data-target").trim()) ?: return@mapNotNull null
            val number = option.attr("value").trim().toIntOrNull()
            newEpisode(href) {
                this.name = option.text().trim().ifBlank { "Bölüm ${number ?: ""}" }
                this.episode = number
                if (singleSeason) this.season = 1
            }
        }.distinctBy { it.data }.sortedBy { it.episode ?: 0 }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // Dizi sayfası için en yeni bölüm; film/program sayfası kendi oynatıcısını taşır.
        val page = if (EPISODE_PATH.containsMatchIn(data)) data else episodes(data).firstOrNull()?.data ?: data
        val doc = runCatching { app.get(page, headers = uaHeaders).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: bölüm sayfası yüklenemedi.")
        val videoId = doc.selectFirst("div.adm-player-container[data-video-id]")?.attr("data-video-id")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("$name: video kimliği bulunamadı")

        val master = JSONObject(
            app.post("$mainUrl/ajax/stream", data = mapOf("video_id" to videoId), headers = uaHeaders).text
        ).optString("video_url").takeIf { it.startsWith("http") }
            ?: throw ErrorLoadingException("$name: akış adresi gelmedi")

        val playlist = runCatching { app.get(master, headers = uaHeaders).text }.getOrNull()
        val variants = playlist?.let { parseHlsVariants(master, it) }.orEmpty()
        if (variants.isEmpty()) {
            if (Settings.qualityAllowed(Qualities.Unknown.value)) callback.invoke(
                newExtractorLink(name, name, master, ExtractorLinkType.M3U8) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }
        var found = false
        for ((height, url) in variants) {
            val quality = getQualityFromName("${height}p")
            if (!Settings.qualityAllowed(quality)) continue
            callback.invoke(
                newExtractorLink(name, name, url, ExtractorLinkType.M3U8) {
                    this.referer = "$mainUrl/"
                    this.quality = quality
                }
            )
            found = true
        }
        if (!found) throw ErrorLoadingException("$name: ayarlardaki kalite sınırına uyan kaynak yok")
        return true
    }

    /** Dizi (TVSeries) JSON-LD'sindeki konu metni; sitedeki diğer açıklamalar SEO cümlesi. */
    private fun seriesPlot(doc: Document): String? {
        val ld = doc.selectFirst("script[type=application/ld+json]")?.data() ?: return null
        val raw = Regex("\"description\"\\s*:\\s*\"([^\"]{60,6000})\"").find(ld)?.groupValues?.get(1)
            ?: return null
        return Jsoup.parse(Jsoup.parse(raw).text()).text().trim().takeIf { it.length > 60 }
    }

    companion object {
        val ROWS = listOf("Öne Çıkanlar", "Yeni Bölümler", "Diziler", "Filmler", "Programlar", "Eskimeyen Diziler")
        private const val PAGE_SIZE = 10
        private val EPISODE_PATH = Regex("""/bolum/\d+|/fragman/|/ozel-sahne/""")
    }
}
