package com.belgeselx

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray

/**
 * BelgeselX — belgeselx.com. Every catalog entry is a documentary series: the series page
 * `/belgesel/<slug>` carries all seasons' episode rows, each row's onclick holds the episode id
 * plus three source slot codes. Slot codes pick a `/video/data/<file>.php` endpoint that returns
 * either an embed iframe (Drive / ok.ru / Dailymotion / Yandex) or a jwplayer `file:` source.
 */
class BelgeselXProvider : MainAPI() {
    override var mainUrl = "https://belgeselx.com"
    override var name = "BelgeselX"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage get() = ROWS.mapNotNull { name ->
        if (!Settings.rowEnabled(name)) return@mapNotNull null
        when (name) {
            "Son Eklenenler" -> MainPageData(name, "$mainUrl/son-eklenenler")
            "En Çok İzlenen" -> MainPageData(name, "$mainUrl/en-cok-izlenen-belgeseller")
            "Bilim" -> MainPageData(name, "$mainUrl/konu/bilim-belgeselleri")
            "Doğa" -> MainPageData(name, "$mainUrl/konu/doga-belgeselleri")
            "Tarih" -> MainPageData(name, "$mainUrl/konu/tarih-belgeselleri")
            else -> null
        }
    }

    private suspend fun get(url: String, referer: String? = null, followRedirects: Boolean = true) =
        app.get(url, referer = referer ?: "$mainUrl/", headers = UA, allowRedirects = followRedirects)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Listing pages are single-shot; the site ships no pager.
        if (page > 1) return newHomePageResponse(request.name, emptyList())
        val doc = get(request.data).document
        val items = when {
            doc.selectFirst("a.px-ep-card") != null -> doc.select("a.px-ep-card").mapNotNull { card ->
                val series = card.selectFirst(".px-ep-series")?.text()?.trim()
                val episode = card.selectFirst(".px-ep-title")?.text()?.trim()
                entry(card, series ?: episode, card.selectFirst(".px-ep-thumb")?.attr("src"))
            }

            doc.selectFirst("a.px-rank-item") != null -> doc.select("a.px-rank-item").mapNotNull { card ->
                entry(card, card.selectFirst(".px-rank-title")?.text(), card.selectFirst(".px-rank-thumb")?.attr("src"))
            }

            else -> doc.select("a.px-card").mapNotNull { card ->
                entry(card, card.selectFirst(".px-card-title")?.text(), card.selectFirst(".px-card-img")?.attr("src"))
            }
        }
        return newHomePageResponse(request.name, items.distinctBy { it.url })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val tokens = fold(query).split(NON_WORD).filter { it.length > 1 }
        if (tokens.isEmpty()) throw ErrorLoadingException("$name: en az 2 karakter gir. $REPORT_HINT")
        val hits = slugs().filter { slug -> tokens.all { slug.contains(it) } }.take(SEARCH_LIMIT)
        val results = hits.map { pageEntry(it) }
        if (results.isEmpty()) throw ErrorLoadingException("$name: \"$query\" için sonuç yok. $REPORT_HINT")
        return results
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        var page = runCatching { get(url).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        var rows = page.select("a.px-ep-row[onclick]")
        if (rows.isEmpty()) {
            fixUrlNull(page.selectFirst("a.px-ep-card[href]")?.attr("href"))?.let { next ->
                runCatching { get(next).document }.getOrNull()?.let { doc ->
                    page = doc
                    rows = doc.select("a.px-ep-row[onclick]")
                }
            }
        }
        val title = page.selectFirst("h2.px-info-title")?.ownText()?.trim()?.takeIf { it.isNotBlank() }
            ?: page.selectFirst("h1.px-hero-title")?.ownText()?.trim()?.takeIf { it.isNotBlank() }
            ?: page.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: başlık bulunamadı. $REPORT_HINT")

        val episodes = rows.mapNotNull { row ->
            val args = args(row) ?: return@mapNotNull null
            val season = args[7].toIntOrNull() ?: 1
            val episode = args[8].toIntOrNull() ?: return@mapNotNull null
            newEpisode("$url|${args[0]}|${args[1]}|${args[2]}|${args[3]}") {
                this.name = args[4].trim().ifBlank { "S${season}B$episode" }
                this.season = season
                this.episode = episode
            }
        }.distinctBy { it.data }
        if (episodes.isEmpty()) throw ErrorLoadingException("$name: bölüm bulunamadı. $REPORT_HINT")

        val year = rows.firstOrNull()?.let { args(it)?.getOrNull(6) }?.let { YEAR.find(it)?.value?.toIntOrNull() }
        val channel = page.selectFirst(".px-stat a[href*=/belgeselkanali/]")?.text()?.trim()

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = fixUrlNull(page.selectFirst(".px-info-poster img")?.attr("src"))
            this.plot = page.selectFirst(".px-info-desc")?.text()?.trim()
            this.year = year
            if (!channel.isNullOrBlank()) this.tags = listOf(channel)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val linkCallback: (ExtractorLink) -> Unit = { if (Settings.qualityAllowed(it.quality)) callback(it) }
        val subtitleFilter: (SubtitleFile) -> Unit = { if (Settings.subtitleAllowed(it.lang)) subtitleCallback(it) }
        val parts = data.split("|")
        if (parts.size < 5) throw ErrorLoadingException("$name: kaynak verisi bozuk. $REPORT_HINT")
        val pageUrl = parts[0]
        val episodeId = parts[1]
        var found = false

        for ((slot, code) in parts.drop(2).take(3).withIndex()) {
            val file = SRC_MAP[code] ?: "default"
            val body = runCatching {
                get("$mainUrl/video/data/$file.php?id=$episodeId&sira=${slot + 1}", referer = pageUrl).text
            }.getOrNull() ?: continue

            for (match in IFRAME.findAll(body)) {
                val src = match.groupValues[1].trim()
                when {
                    src.isEmpty() -> Unit
                    src.all { it.isDigit() } -> {
                        if (loadOkRu("https://ok.ru/videoembed/$src", linkCallback)) found = true
                    }

                    src.contains("ok.ru") || src.contains("odnoklassniki") -> {
                        if (loadOkRu(fixUrl(src), linkCallback)) found = true
                    }
                    // Drive needs a signed virus-scan token and the site's Yandex links are dead
                    // (cloud-api answers DiskNotFoundError), so neither can be turned into a stream.
                    src.contains("drive.google.com") || src.contains("yadi.sk") || src.contains("disk.yandex") -> Unit
                    else -> if (loadExtractor(fixUrl(src), pageUrl, subtitleFilter, linkCallback)) found = true
                }
            }

            for (match in FILES.findAll(body)) {
                val src = match.groupValues[1].trim()
                when {
                    src.isEmpty() -> Unit
                    src.contains("/video/belgeselx") -> if (loadCid(src, pageUrl, linkCallback)) found = true
                    src.contains(".m3u8") -> {
                        linkCallback(
                            newExtractorLink(name, name, src, ExtractorLinkType.M3U8) {
                                this.referer = "$mainUrl/"
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        found = true
                    }

                    else -> if (loadExtractor(src, pageUrl, subtitleFilter, linkCallback)) found = true
                }
            }
        }

        if (!found) throw ErrorLoadingException("$name: oynatılabilir akış yok. $REPORT_HINT")
        return found
    }

    /** `/video/belgeselx*.php?cid=` signs a redirect to a CDN mp4; the `=mNN` tail is the itag. */
    private suspend fun loadCid(phpUrl: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        val location = runCatching {
            get(phpUrl, referer = referer, followRedirects = false).headers["location"]
        }.getOrNull()?.takeIf { it.startsWith("http") } ?: return false
        // Episodes without a real asset ship the `ogw/default-user=s83` placeholder, which answers 400.
        if (!location.contains("/pw/")) return false
        val itag = ITAG.find(location)?.groupValues?.get(1)
        callback.invoke(
            newExtractorLink(name, name, location, ExtractorLinkType.VIDEO) {
                // The Google CDN answers 403 when Referer is belgeselx.com, so leave it unset.
                this.quality = itag?.let { ITAG_QUALITY[it] } ?: Qualities.Unknown.value
            }
        )
        return true
    }

    /** ok.ru embeds HTML-escape their player JSON (`&quot;`), so unescape before reading `videos`. */
    private suspend fun loadOkRu(embed: String, callback: (ExtractorLink) -> Unit): Boolean {
        // odnoklassniki.ru only bounces to a 404; the same id on ok.ru serves the player payload.
        val url = embed.replace("odnoklassniki.ru", "ok.ru")
        val body = runCatching { get(url).text }.getOrNull()?.replace("&quot;", "\"") ?: return false
        val array = Regex(""""videos"\s*:\s*(\[.*?])""", RegexOption.DOT_MATCHES_ALL)
            .find(body)?.groupValues?.get(1) ?: return false
        val videos = runCatching { JSONArray(array) }.getOrNull() ?: return false

        var found = false
        for (i in 0 until videos.length()) {
            val video = videos.optJSONObject(i) ?: continue
            if (video.optBoolean("disallowed")) continue
            val stream = video.optString("url").replace("\\/", "/")
            if (stream.isBlank()) continue
            val quality = OKRU_QUALITY[video.optString("name").lowercase()] ?: Qualities.Unknown.value
            callback.invoke(
                newExtractorLink(name, "ok.ru", stream, ExtractorLinkType.VIDEO) {
                    this.referer = "https://ok.ru/"
                    this.quality = quality
                }
            )
            found = true
        }
        return found
    }

    private fun entry(card: org.jsoup.nodes.Element, title: String?, poster: String?): SearchResponse? {
        val href = fixUrlNull(card.attr("href")) ?: return null
        val name = title?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return newTvSeriesSearchResponse(name, href, TvType.TvSeries) {
            this.posterUrl = fixUrlNull(poster)
        }
    }

    private suspend fun pageEntry(slug: String): SearchResponse {
        val url = "$mainUrl/belgesel/$slug"
        val doc = runCatching { get(url).document }.getOrNull()
        val title = doc?.selectFirst("h2.px-info-title")?.ownText()?.trim()?.takeIf { it.isNotBlank() }
        return newTvSeriesSearchResponse(title ?: humanize(slug), url, TvType.TvSeries) {
            this.posterUrl = fixUrlNull(doc?.selectFirst(".px-info-poster img")?.attr("src"))
        }
    }

    /** The site's own search is a Google CSE widget, so the sitemap is the only complete index. */
    private suspend fun slugs(): List<String> {
        val cached = sitemap
        if (cached != null && System.currentTimeMillis() - cached.first < CACHE_MS) return cached.second
        val list = SITEMAP.findAll(get("$mainUrl/sitemap.xml").text).map { it.groupValues[1] }.distinct().toList()
        if (list.isEmpty()) throw ErrorLoadingException("$name: site dizini okunamadı. $REPORT_HINT")
        sitemap = System.currentTimeMillis() to list
        return list
    }

    private fun args(row: org.jsoup.nodes.Element): List<String>? =
        ARG.findAll(row.attr("onclick")).map { it.groupValues[1] }.toList().takeIf { it.size >= 9 }

    private fun humanize(slug: String) =
        slug.split('-').joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }

    /** Turkish query -> the site's ASCII slugs ("Antik Uzaylılar" -> "antik uzaylilar"). */
    private fun fold(text: String) = text.lowercase().map {
        when (it) {
            'ı', 'î' -> 'i'
            'ş' -> 's'
            'ğ' -> 'g'
            'ü', 'û' -> 'u'
            'ö' -> 'o'
            'ç' -> 'c'
            'â' -> 'a'
            else -> it
        }
    }.joinToString("")

    private var sitemap: Pair<Long, List<String>>? = null

    companion object {
        /** Homepage rows in display order; all map to live listing pages. */
        val ROWS = listOf(
            "Son Eklenenler",
            "En Çok İzlenen",
            "Bilim",
            "Doğa",
            "Tarih",
        )

        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
        private const val SEARCH_LIMIT = 8
        private const val CACHE_MS = 10 * 60 * 1000L

        private val UA = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        )

        /** ic1 / ic2 / ic3 slot code -> `/video/data/<file>.php` endpoint. */
        private val SRC_MAP = mapOf("0" to "new5", "2" to "new1", "5" to "new4", "3" to "new2", "4" to "new3")
        private val ITAG_QUALITY = mapOf("18" to 360, "22" to 720, "37" to 1080)
        private val OKRU_QUALITY = mapOf(
            "mobile" to 240, "lowest" to 240, "low" to 360, "sd" to 480,
            "high" to 720, "hd" to 1080, "full" to 1080,
        )

        private val ARG = Regex("""'([^']*)'""")
        private val IFRAME = Regex("""<iframe[^>]*\ssrc="([^"]*)"""")
        private val FILES = Regex("""file\s*:\s*"([^"]*)"""")
        private val ITAG = Regex("""=m(\d+)$""")
        private val SITEMAP = Regex("""<loc>https://belgeselx\.com/belgesel/([^<]+)</loc>""")
        private val YEAR = Regex("""\b(\d{4})\b""")
        private val NON_WORD = Regex("""[^a-z0-9]+""")
    }
}
