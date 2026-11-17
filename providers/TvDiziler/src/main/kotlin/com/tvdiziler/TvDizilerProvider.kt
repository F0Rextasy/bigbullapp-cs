package com.tvdiziler

import com.bigbull.common.browserHeaders
import com.bigbull.common.posterSrcLazy
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * TvDiziler — tvdiziler.tv yerli dizi arşivi.
 * Arama POST /search?qr=… (X-Requested-With şart) → {success, data: HTML}.
 * Bölüm oynatıcısı data-hhs → /vid/ply/<hash> → jwplayer sources (mp4/m3u8),
 * bazı bölümlerde streambox /player/git.php → YouTube sarmalayıcı.
 */
class TvDizilerProvider : MainAPI() {
    override var mainUrl = "https://tvdiziler.tv"
    override var name = "TvDiziler"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries)

    private val uaHeaders get() = browserHeaders
    private val ajaxHeaders get() = browserHeaders + ("x-requested-with" to "XMLHttpRequest")

    override val mainPage get() = ROWS.mapNotNull { row ->
        if (!Settings.rowEnabled(row)) return@mapNotNull null
        when (row) {
            "Dikkate Değer Diziler" -> MainPageData(row, "$mainUrl/")
            "Tüm Diziler" -> MainPageData(row, "$mainUrl/dizi-izle")
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data, headers = uaHeaders).document
        val selector = if (request.name == "Tüm Diziler") "div.series-list div.poster-md a"
            else "div.remarkable-series div.poster-md a"
        val cards = doc.select(selector).mapNotNull { it.toSeriesCard() }.distinctBy { it.url }
        return newHomePageResponse(request.name, cards.take(MAX_CARDS), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val qr = URLEncoder.encode(query, "UTF-8")
        val body = runCatching {
            app.post(
                "$mainUrl/search?qr=$qr",
                data = emptyMap<String, String>(),
                headers = ajaxHeaders,
                referer = "$mainUrl/",
            ).text
        }.getOrNull() ?: return emptyList()
        val html = runCatching { JSONObject(body).optString("data") }.getOrNull().orEmpty()
        if (html.isBlank()) return emptyList()
        return Jsoup.parse(html).select("div.search-container a[href]")
            .mapNotNull { it.toSeriesCard() }
            .distinctBy { it.url }
    }

    private fun Element.toSeriesCard(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        if (!href.contains("/dizi/")) return null
        val rawTitle = selectFirst("h3")?.text()
            ?: selectFirst("div.poster-md-logo span")?.text()
            ?: selectFirst("img")?.attr("alt")
            ?: return null
        val title = cleanTitle(rawTitle).ifBlank { return null }
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = fixUrlNull(selectFirst("img")?.posterSrcLazy())
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url, headers = uaHeaders).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: içerik yüklenemedi")
        val title = doc.selectFirst("p[itemprop=name]")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: cleanTitle(doc.selectFirst("h1")?.text().orEmpty()).takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: başlık okunamadı")
        val info = doc.select("div.filter-result-box-bottom li").mapNotNull { li ->
            val key = li.selectFirst("span")?.text()?.trim() ?: return@mapNotNull null
            key to li.selectFirst("p")?.text()?.trim().orEmpty()
        }.toMap()
        val episodes = parseEpisodes(doc)
        if (episodes.isEmpty()) throw ErrorLoadingException("$name: bölüm listesi boş")

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
            this.plot = doc.selectFirst("p[itemprop=description]")?.text()?.trim()
                ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
            this.year = info["Yapım yılı"]?.toIntOrNull()?.takeIf { it > 0 }
                ?: Regex("""\((19|20)\d{2}\)""").find(doc.selectFirst("h1")?.text().orEmpty())
                    ?.value?.trim('(', ')')?.toIntOrNull()?.takeIf { it > 0 }
            this.score = Score.from10(
                info["IMDb Puanı"]?.toDoubleOrNull()?.takeIf { it > 0 }?.toString()
            )
            this.tags = doc.select("div.series-profile-type a").map { it.text().trim() }
                .filter { it.isNotBlank() }
        }
    }

    private fun parseEpisodes(doc: Document): List<Episode> {
        val fromList = doc.select("div.series-profile-episode-list li").mapNotNull { it.toEpisode() }
        val episodes = fromList.ifEmpty {
            doc.select("div.series-profile-episode-buttons a[href]").mapNotNull { it.toEpisode() }
        }
        return episodes.distinctBy { it.data }
            .sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: Int.MAX_VALUE }))
    }

    private fun Element.toEpisode(): Episode? {
        val anchor = if (tagName() == "a") this else selectFirst("a[href]") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        val pos = Regex("""(\d+)-sezon-(\d+)-bolum""").find(href)
        val season = pos?.groupValues?.get(1)?.toIntOrNull()
            ?: selectFirst("meta[itemprop=name]")?.attr("content")?.toIntOrNull()
            ?: 1
        val number = pos?.groupValues?.get(2)?.toIntOrNull()
            ?: selectFirst("[itemprop=episodeNumber]")?.attr("content")?.toIntOrNull()
        val label = selectFirst("h6 a")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: anchor.text().trim().takeIf { it.isNotBlank() }
            ?: "Bölüm ${number ?: ""}"
        return newEpisode(href) {
            this.name = label
            this.season = season
            this.episode = number
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val doc = runCatching { app.get(data, headers = uaHeaders).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: bölüm yüklenemedi")
        val players = doc.select("[data-hhs]")
            .flatMap { it.attr("data-hhs").split(",") }
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.contains("/vid/kapat/") }
            .distinct()
        if (players.isEmpty()) throw ErrorLoadingException("$name: oynatıcı bulunamadı")

        var found = false
        val subFilter: (SubtitleFile) -> Unit = { if (Settings.subtitleAllowed(it.lang)) subtitleCallback(it) }
        val linkFilter: (ExtractorLink) -> Unit = { link ->
            found = true
            if (Settings.qualityAllowed(link.quality)) callback(link)
        }

        suspend fun emit(stream: String, label: String, referer: String): Boolean {
            if (!stream.startsWith("http")) return false
            found = true
            val quality = getQualityFromName(label)
            if (!Settings.qualityAllowed(quality)) return true
            val type = if (stream.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            // Yandex imzalı indirme bağlantıları tvdiziler referer'ını "Invalid Referer" ile reddediyor.
            val ref = if (stream.contains("yandex", true)) "https://disk.yandex.ru/" else referer
            callback(newExtractorLink(name, name, stream, type) {
                this.referer = ref
                this.quality = quality
            })
            return true
        }

        suspend fun resolveEmbed(html: String, referer: String): Boolean {
            Regex("""youtube(?:-nocookie)?\.com/(?:embed/|watch\?v=)([A-Za-z0-9_-]{6,})""")
                .find(html)?.groupValues?.get(1)?.let { id ->
                    if (loadExtractor("https://www.youtube.com/watch?v=$id", referer, subFilter, linkFilter)) return true
                }
            MEDIA.find(html)?.let { m ->
                return emit(m.value, if (m.value.contains(".m3u8", true)) "HLS" else "MP4", referer)
            }
            val iframe = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)?.let { fixUrl(it) } ?: return false
            return loadExtractor(iframe, referer, subFilter, linkFilter)
        }

        for (candidate in players) {
            val playerUrl = fixUrl(candidate)
            if (playerUrl.contains("/vid/ply/")) {
                val html = runCatching { app.get(playerUrl, headers = uaHeaders, referer = data).text }
                    .getOrNull() ?: continue
                var parsed = false
                for (m in SOURCE.findAll(html)) {
                    if (emit(m.groupValues[1].replace("\\/", "/"), m.groupValues[2], data)) parsed = true
                }
                if (!parsed) resolveEmbed(html, playerUrl)
            } else {
                val html = runCatching { app.get(playerUrl, headers = uaHeaders, referer = data).text }.getOrNull()
                if (html == null || !resolveEmbed(html, data)) {
                    loadExtractor(playerUrl, data, subFilter, linkFilter)
                }
            }
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir kaynak yok")
        return true
    }

    private fun cleanTitle(raw: String): String = raw
        .replace(Regex("""\s+izle\b.*$""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s*\((19|20)\d{2}\)\s*$"""), "")
        .trim()
        .trim('-', '|', '–')

    companion object {
        val ROWS = listOf("Dikkate Değer Diziler", "Tüm Diziler")

        private const val MAX_CARDS = 40

        /** jwplayer `sources: [{file:"…", label:"720p", type:"mp4"}]` girdileri. */
        private val SOURCE = Regex("""file\s*:\s*["']([^"']+)["'][^{}]*?label\s*:\s*["']([^"']*)["']""")

        private val MEDIA = Regex("""https?://[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)""")
    }
}
