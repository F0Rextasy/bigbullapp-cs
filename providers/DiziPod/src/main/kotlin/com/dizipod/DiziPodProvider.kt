package com.dizipod

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
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
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
import java.net.URLEncoder

/**
 * DiziPod — dizipod.com (Türkçe altyazılı yabancı dizi + film arşivi, WordPress "lyssa" teması).
 *
 * Sayfa türleri: `/diziler/<slug>/` dizi, `/film/<slug>/` film, `/tur/<tür>/?pg=N` tür listesi,
 * `/?s=<sorgu>` arama (yalnız dizinleri kapsar, filmler site aramasında yok).
 * Dizi sayfası yalnız etkin sezonun bölümlerini basar; kalan sezonlar `a.session-tab`
 * bağlantısındaki `/dizi/<slug>/<slug>-N-sezon/` sayfalarından toplanır.
 *
 * Oynatıcı zinciri: bölüm/film sayfasındaki `#episode-player-container` data-post-id taşır,
 * `/wp/wp-admin/admin-ajax.php?action=get_episode_player` bir iframe döner, iframe'in
 * Dean Edwards packer ile paketlenmiş jwplayer kurulumu `sources[].file` m3u8 adresini verir.
 */
private fun Element.posterSrcLazy(): String? =
    attr("data-src").takeIf { it.isNotBlank() } ?: attr("src")

class DiziPodProvider : MainAPI() {
    override var mainUrl = "https://dizipod.com"
    override var name = "DiziPod"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val uaHeaders = mapOf(
        "User-Agent" to UA,
        "Accept-Language" to "tr-TR,tr;q=0.9",
    )

    override val mainPage get() = mainPageOf(
        "$mainUrl/diziler/page/SAYFA/" to "Son Eklenen Diziler",
        "$mainUrl/filmler/page/SAYFA/" to "Son Eklenen Filmler",
        "$mainUrl/tur/aksiyon/?pg=SAYFA" to "Aksiyon",
        "$mainUrl/tur/dram/?pg=SAYFA" to "Dram",
        "$mainUrl/tur/komedi/?pg=SAYFA" to "Komedi",
        "$mainUrl/tur/gerilim/?pg=SAYFA" to "Gerilim",
        "$mainUrl/tur/korku/?pg=SAYFA" to "Korku",
        "$mainUrl/tur/bilim-kurgu/?pg=SAYFA" to "Bilim Kurgu",
        "$mainUrl/tur/fantastik/?pg=SAYFA" to "Fantastik",
        "$mainUrl/tur/animasyon/?pg=SAYFA" to "Animasyon",
        "$mainUrl/tur/macera/?pg=SAYFA" to "Macera",
        "$mainUrl/tur/romantik/?pg=SAYFA" to "Romantik",
        "$mainUrl/tur/belgesel/?pg=SAYFA" to "Belgesel",
        "$mainUrl/tur/anime/?pg=SAYFA" to "Anime",
    ).filter { Settings.rowEnabled(it.name) }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data.replace("SAYFA", page.toString()), headers = uaHeaders).document
        return newHomePageResponse(request.name, doc.cards(), hasNext = doc.hasNextPage(page))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val doc = app.get("$mainUrl/?s=$encoded", headers = uaHeaders).document
        return doc.cards()
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = fixUrl(url)
        val doc = runCatching { app.get(pageUrl, headers = uaHeaders).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: içerik sayfası yüklenemedi. $REPORT_HINT")
        return if (pageUrl.contains("/diziler/")) seriesOf(pageUrl, doc) else movieOf(pageUrl, doc)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val linkCallback: (ExtractorLink) -> Unit = { if (Settings.qualityAllowed(it.quality)) callback(it) }
        val pageUrl = fixUrl(data)
        val doc = runCatching { app.get(pageUrl, headers = uaHeaders).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: bölüm sayfası yüklenemedi. $REPORT_HINT")

        val postId = doc.selectFirst("#episode-player-container")?.attr("data-post-id")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("$name: oynatıcı kimliği bulunamadı. $REPORT_HINT")

        val payload = runCatching {
            app.get(
                "$mainUrl/wp/wp-admin/admin-ajax.php?action=get_episode_player&post_id=$postId",
                headers = uaHeaders,
                referer = pageUrl,
            ).text
        }.getOrNull() ?: throw ErrorLoadingException("$name: oynatıcı yanıt vermedi. $REPORT_HINT")

        val embedRaw = runCatching { JSONObject(payload) }.getOrNull()
            ?.takeIf { it.optBoolean("success") }
            ?.optString("data")
            ?.let { IFRAME_SRC.find(it)?.groupValues?.get(1) }
            ?: throw ErrorLoadingException("$name: oynatıcı adresi alınamadı. $REPORT_HINT")
        val embedUrl = fixUrl(if (embedRaw.startsWith("//")) "https:$embedRaw" else embedRaw)

        val embedHtml = runCatching { app.get(embedUrl, headers = uaHeaders, referer = pageUrl).text }
            .getOrNull() ?: throw ErrorLoadingException("$name: oynatıcı sayfası yüklenemedi. $REPORT_HINT")

        var emitted = 0
        for ((stream, label) in sourcesOf(embedHtml)) {
            val type = if (stream.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            linkCallback.invoke(
                newExtractorLink(name, label, stream, type) {
                    this.referer = embedUrl
                    this.headers = mapOf("User-Agent" to UA)
                    this.quality = Qualities.Unknown.value
                }
            )
            emitted++
        }
        if (emitted == 0) throw ErrorLoadingException("$name: oynatılabilir kaynak bulunamadı. $REPORT_HINT")
        return true
    }

    // ---------------------------------------------------------------- dizi/film

    private suspend fun seriesOf(url: String, doc: Document): LoadResponse {
        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("$name: dizi bilgisi okunamadı. $REPORT_HINT")
        val schema = doc.schema()
        val info = doc.infoTable()
        val episodes = linkedMapOf<String, Episode>()

        val tabs = doc.select("a.session-tab")
        // Açık sezonun bölümleri sayfada gömülü; diğer sezonlar kendi sayfalarında gelir.
        doc.select("div.episode-card a.episode-link").forEach { addEpisode(episodes, it.attr("href")) }
        val pending = if (episodes.isEmpty()) tabs else tabs.filterNot { it.hasClass("active") }
        pending.forEach { tab ->
            val seasonDoc = runCatching { app.get(fixUrl(tab.attr("href")), headers = uaHeaders).document }
                .getOrNull() ?: return@forEach
            seasonDoc.select("div.episode-item a[href*='-bolum/']").forEach {
                addEpisode(episodes, it.attr("href"), it.text())
            }
        }

        val sorted = episodes.values.sortedWith(
            compareBy({ it.season ?: Int.MAX_VALUE }, { it.episode ?: Int.MAX_VALUE })
        )
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sorted) {
            this.posterUrl = schema?.optString("image")?.takeIf { it.startsWith("http") }
                ?: fixUrlNull(doc.selectFirst(".serie-mini-detail .poster img")?.attr("src"))
            this.plot = schema?.optString("description")?.trim()?.takeIf { it.isNotEmpty() }
                ?: doc.selectFirst("div.serie-details p")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            this.year = info["Yapım Yılı"]?.toIntOrNull()
            this.tags = doc.select(".aside-category a").map { it.text().trim() }
                .filter { it.isNotEmpty() }.distinct()
            this.score = scoreOf(doc.selectFirst(".dp-votes")?.text())
            addActors(
                doc.select("a[href*='/oyuncu/']").map { it.text().trim() }
                    .filter { it.isNotEmpty() }.distinct()
            )
            doc.selectFirst("[data-trailer-url]")?.attr("data-trailer-url")?.trim()
                ?.takeIf { it.isNotEmpty() }?.let { addTrailer(it) }
        }
    }

    private suspend fun movieOf(url: String, doc: Document): LoadResponse {
        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("$name: film bilgisi okunamadı. $REPORT_HINT")
        val duration = doc.select(".movie-hero__meta-item").firstOrNull { it.text().contains("dk") }
            ?.text()?.filter { it.isDigit() }?.toIntOrNull()
        val related = doc.select("div.related-movies a.rel-card[href*='/film/']")
            .mapNotNull { it.toSearchCard() }.distinctBy { it.url }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = fixUrlNull(doc.selectFirst("img.movie-hero__poster")?.posterSrcLazy())
            this.plot = doc.selectFirst(".movie-hero__overview p")?.text()?.trim()
                ?.takeIf { it.isNotEmpty() }
            this.year = doc.selectFirst(".movie-hero__title a[href*='/yil/']")?.text()?.trim()
                ?.toIntOrNull()
            this.duration = duration
            this.tags = doc.select(".movie-hero__meta a[href*='/tur/']").map { it.text().trim() }
                .filter { it.isNotEmpty() }.distinct()
            this.score = scoreOf(doc.selectFirst(".imdb-pill__score")?.text())
            this.recommendations = related
        }
    }

    private fun addEpisode(target: MutableMap<String, Episode>, href: String?, label: String? = null) {
        val episodeUrl = fixUrlNull(href) ?: return
        val fromUrl = EPISODE_IN_URL.find(episodeUrl)
        val season = fromUrl?.groupValues?.get(1)?.toIntOrNull()
            ?: SEASON_IN_TEXT.find(label.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
        val number = fromUrl?.groupValues?.get(2)?.toIntOrNull()
            ?: EPISODE_IN_TEXT.find(label.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
        target[episodeUrl] = newEpisode(episodeUrl) {
            this.season = season
            this.episode = number
            this.name = label?.trim()?.takeIf { number == null && it.isNotEmpty() }
        }
    }

    // ---------------------------------------------------------------- listeler

    // Kart içindeki tür bağlantıları `<a>` içinde `<a>` üretir; jsoup dış bağlantıyı boşaltıp
    // içeriği kardeş düğümlere taşır, bu yüzden kart `<li>` üzerinden okunur.
    private fun Document.cards(): List<SearchResponse> =
        select("ul.list > li, ul.premium-listing > li")
            .mapNotNull { it.toSearchCard() }
            .distinctBy { it.url }

    private fun Element.toSearchCard(): SearchResponse? {
        val anchor = if (tagName() == "a") this else selectFirst("a[href]") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        val isSeries = href.contains("/diziler/")
        if (!isSeries && !href.contains("/film/")) return null

        val title = selectFirst("div.title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: anchor.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: return null
        val poster = fixUrlNull(
            selectFirst("img[data-src]")?.attr("data-src")
                ?: selectFirst("img[src]")?.attr("src")
                    ?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
        )
        val year = selectFirst("div.year")?.text()?.trim()?.toIntOrNull()
            ?: selectFirst("span.rel-card__year")?.text()?.trim()?.toIntOrNull()
        val score = scoreOf(
            selectFirst("span.imdb-score")?.text() ?: selectFirst("div.imdb span:last-child")?.text()
        )

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                this.score = score
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                this.score = score
            }
        }
    }

    /** Sayfalama bağlantısı iki biçimde gelir: `/page/N/` ve tür listelerinde `?pg=N`. */
    private fun Document.hasNextPage(page: Int): Boolean {
        val next = page + 1
        return select("a[href]").any {
            val href = it.attr("href")
            href.contains("/page/$next/") || href.endsWith("pg=$next")
        }
    }

    // ---------------------------------------------------------------- künye

    /** Dizi ve film sayfaları schema.org düğümü basar; dizi sayfasında `@type` "Movie" olur. */
    private fun Document.schema(): JSONObject? =
        select("script[type=application/ld+json]").firstNotNullOfOrNull { script ->
            runCatching { JSONObject(script.data()) }.getOrNull()
                ?.takeIf { it.optString("@type") == "Movie" || it.optString("@type") == "TVSeries" }
        }

    private fun Document.infoTable(): Map<String, String> =
        select("table.table_custom tr").mapNotNull { row ->
            val cells = row.select("td")
            if (cells.size < 2) null else cells[0].text().trim() to cells[1].text().trim()
        }.toMap()

    private fun scoreOf(text: String?): Score? =
        text?.substringBefore('/')?.trim()?.replace(',', '.')?.toDoubleOrNull()?.let { Score.from10(it) }

    // ---------------------------------------------------------------- oynatıcı

    /**
     * Oynatıcı sayfası jwplayer kurulumunu Dean Edwards packer ile saklar. Paket çözülüp
     * `sources[{file,label}]` alınır; kaynak listesi yoksa düz m3u8 aramasına düşülür.
     */
    private fun sourcesOf(embedHtml: String): List<Pair<String, String>> {
        val script = unpack(embedHtml)
        val listed = SOURCES.findAll(script).map { it.groupValues[1] to it.groupValues[2] }.toList()
        val pairs = listed.ifEmpty {
            M3U8_ANY.findAll(script).map { it.value to name }.toList()
        }
        // jwplayer kurulumu `\/` ve `\\/` biçiminde kaçışlı gelir; adreste ters bölü olmaz.
        return pairs.map { (stream, label) ->
            stream.replace("\\", "") to label.trim().ifEmpty { name }
        }.distinctBy { it.first }
    }

    private fun unpack(html: String): String {
        val match = PACKED.find(html) ?: return html
        val payload = match.groupValues[1]
        val base = match.groupValues[2].toIntOrNull() ?: return html
        val words = match.groupValues[4].split('|')
        if (base !in 2..ALPHABET.length) return html

        val dictionary = HashMap<String, String>(words.size * 2)
        words.forEachIndexed { index, word ->
            val key = encodeBase(index, base)
            dictionary[key] = word.ifEmpty { key }
        }
        return WORD.replace(payload.replace("\\'", "'")) { dictionary[it.value] ?: it.value }
    }

    /** Packer'ın sözlük anahtarı: indeksin `base` tabanındaki yazımı. */
    private fun encodeBase(value: Int, base: Int): String {
        if (value == 0) return "0"
        var rest = value
        val out = StringBuilder()
        while (rest > 0) {
            out.append(ALPHABET[rest % base])
            rest /= base
        }
        return out.reverse().toString()
    }

    companion object {
        /** Ana sayfa satırları; ayarlardan kapatılabilir. */
        val ROWS = listOf(
            "Son Eklenen Diziler", "Son Eklenen Filmler", "Aksiyon", "Dram", "Komedi",
            "Gerilim", "Korku", "Bilim Kurgu", "Fantastik", "Animasyon", "Macera",
            "Romantik", "Belgesel", "Anime",
        )
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
        private const val ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

        private val EPISODE_IN_URL = Regex("""-(\d+)-sezon-(\d+)-bolum""")
        private val SEASON_IN_TEXT = Regex("""(\d+)\.\s*Sezon""")
        private val EPISODE_IN_TEXT = Regex("""(\d+)\.\s*Bölüm""")
        private val IFRAME_SRC = Regex("""<iframe[^>]+src=["']([^"']+)["']""")
        private val SOURCES = Regex("\"file\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"label\"\\s*:\\s*\"([^\"]*)\"")
        private val M3U8_ANY = Regex("""https?:[\\/]{2}[^\s"']+?\.m3u8""")
        private val WORD = Regex("""\b\w+\b""")
        private val PACKED = Regex(
            """eval\(function\(p,a,c,k,e,[rd]\)\{.*?\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""",
            RegexOption.DOT_MATCHES_ALL,
        )
    }
}