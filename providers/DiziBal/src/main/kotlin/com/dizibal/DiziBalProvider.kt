package com.dizibal

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
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
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
 * DiziBal — dizibal.org (altyazılı yabancı dizi + film arşivi; Laravel + Tailwind, HTML düz).
 *
 * Listeler: `/diziler?page=N` (1253 dizi, 53 sayfa), `/filmler?page=N`, tür süzgeci
 * `?tur=<slug>&page=N`, arama `/ara?q=<sorgu>` — arama tek yanıt döner, sayfalama bağlantısı basmaz.
 * Dizi sayfası LD+JSON TVSeries basar (poster, özet, tür, kadro, puan, fragman);
 * bölümler sezon başına ayrı gelir: `?sezon=N#bolumler` sekmelerinde
 * `/season/<n>/episode/<m>` kartları. Etkin sezon sayfada gömülüdür, diğerleri istek ister.
 * Filmler `/movie/<slug>` altındadır ve dizi bölümleriyle aynı oynatıcıyı kullanır; kaynağı
 * olmayan içerikte sayfa `data-player-type="none"` basar.
 *
 * Oynatıcı: bölüm sayfasındaki `[data-pv]` slug'ı → `<oynatıcı>/assets/js/s.php?s=<slug>`
 * → `window.__PLAYER__` (master HLS + altyazılar). Oynatıcı alan adı bölüme göre değişir
 * (pilavyerplay.top / play2.pilavyerplay.top), taban sayfadaki `core.js` script adresinden
 * türetilir. s.php origin kilidi ister: Referer bölüm sayfasıdır.
 */
class DiziBalProvider : MainAPI() {
    override var mainUrl = "https://dizibal.org"
    override var name = "DiziBal"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val uaHeaders = mapOf(
        "User-Agent" to UA,
        "Accept-Language" to "tr-TR,tr;q=0.9",
    )

    override val mainPage get() = mainPageOf(
        "$mainUrl/diziler?page=SAYFA" to "Popüler Diziler",
        "$mainUrl/diziler?sirala=yeni&page=SAYFA" to "Son Eklenenler",
        "$mainUrl/diziler?sirala=puan&page=SAYFA" to "En Yüksek Puan",
        "$mainUrl/diziler?tur=dram&page=SAYFA" to "Dram",
        "$mainUrl/diziler?tur=aksiyon&page=SAYFA" to "Aksiyon",
        "$mainUrl/diziler?tur=komedi&page=SAYFA" to "Komedi",
        "$mainUrl/diziler?tur=bilim-kurgu&page=SAYFA" to "Bilim Kurgu",
        "$mainUrl/diziler?tur=gizem&page=SAYFA" to "Gizem",
        "$mainUrl/diziler?tur=suc&page=SAYFA" to "Suç",
        "$mainUrl/diziler?tur=macera&page=SAYFA" to "Macera",
        "$mainUrl/diziler?tur=belgesel&page=SAYFA" to "Belgesel",
        "$mainUrl/filmler?page=SAYFA" to "Filmler",
    ).filter { Settings.rowEnabled(it.name) }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data.replace("SAYFA", page.toString()), headers = uaHeaders).document
        return newHomePageResponse(request.name, doc.cards(), hasNext = doc.hasNextPage(page))
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (page > 1) return newSearchResponseList(emptyList(), false)
        val doc = app.get("$mainUrl/ara", params = mapOf("q" to query.trim()), headers = uaHeaders).document
        return newSearchResponseList(doc.cards(), false)
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = fixUrl(url)
        val doc = runCatching { app.get(pageUrl, headers = uaHeaders).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: içerik sayfası yüklenemedi. $REPORT_HINT")
        if (MOVIE_PATH.matches(pageUrl)) return movieResponse(pageUrl, doc)

        val schema = doc.schema("TVSeries")
        val title = schema?.optString("name")?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("$name: dizi bilgisi okunamadı. $REPORT_HINT")

        val activeSeason = doc.selectFirst("a[href*='sezon='][class*='border-accent']")
            ?.attr("href")?.let { SEASON_PARAM.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val tabs = doc.select("a[href*='sezon=']").map { it.attr("href").substringBefore('#') }
            .filter { it.startsWith(pageUrl) }.distinct()
        val seasonDocs = if (tabs.isEmpty()) listOf(doc) else tabs.map { seasonUrl ->
            if (SEASON_PARAM.find(seasonUrl)?.groupValues?.get(1)?.toIntOrNull() == activeSeason) doc
            else runCatching { app.get(seasonUrl, headers = uaHeaders).document }.getOrNull()
        }
        val episodes = seasonDocs.filterNotNull()
            .flatMap { it.episodeCards() }
            .distinctBy { it.data }
            .sortedWith(compareBy({ it.season ?: Int.MAX_VALUE }, { it.episode ?: Int.MAX_VALUE }))
        if (episodes.isEmpty()) throw ErrorLoadingException("$name: bölüm listesi okunamadı. $REPORT_HINT")

        return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
            this.posterUrl = schema?.optString("image")?.takeIf { it.startsWith("http") }
                ?: fixUrlNull(doc.selectFirst("main img[src*='/posters/']")?.attr("src"))
            this.plot = schema?.optString("description")?.trim()?.takeIf { it.isNotEmpty() }
            this.year = schema?.optString("startDate")?.take(4)?.toIntOrNull()
            this.tags = schema?.optJSONArray("genre")?.let { stringsOf(it) }
                ?: doc.select("a[href*='/tur/']").map { it.text().trim() }
                    .filter { it.isNotEmpty() }.distinct()
            this.score = schema?.optJSONObject("aggregateRating")?.optString("ratingValue")
                ?.toDoubleOrNull()?.let { Score.from10(it) }
            addActors(
                schema?.optJSONArray("actor")?.let { actors ->
                    (0 until actors.length())
                        .mapNotNull { actors.optJSONObject(it)?.optString("name")?.trim() }
                        .filter { it.isNotEmpty() }
                } ?: doc.select("a[href*='/oyuncu/']").map { it.text().trim() }
                    .filter { it.isNotEmpty() }.distinct()
            )
            schema?.optJSONObject("trailer")?.optString("embedUrl")?.trim()
                ?.takeIf { it.isNotBlank() }?.let { addTrailer(it) }
        }
    }

    // Film sayfaları aynı oynatıcıyı kullanır; kaynak yoksa oynatıcı yerine bilgi kutusu basar.
    private suspend fun movieResponse(pageUrl: String, doc: Document): LoadResponse {
        val schema = doc.schema("Movie")
        val title = schema?.optString("name")?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("$name: film bilgisi okunamadı. $REPORT_HINT")

        return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
            this.posterUrl = schema?.optString("image")?.takeIf { it.startsWith("http") }
                ?: fixUrlNull(doc.selectFirst("main img[src*='/posters/']")?.attr("src"))
            this.plot = schema?.optString("description")?.trim()?.takeIf { it.isNotEmpty() }
            this.year = schema?.optString("datePublished")?.take(4)?.toIntOrNull()
            this.tags = schema?.optJSONArray("genre")?.let { stringsOf(it) }
                ?.takeIf { it.isNotEmpty() }
                ?: doc.select("a[href*='/tur/']").map { it.text().trim() }
                    .filter { it.isNotEmpty() }.distinct()
            this.score = schema?.optJSONObject("aggregateRating")?.optString("ratingValue")
                ?.toDoubleOrNull()?.let { Score.from10(it) }
            this.duration = schema?.optString("duration")?.let { ISO_DURATION.find(it) }?.let { m ->
                (m.groupValues[1].toIntOrNull() ?: 0) * 60 + (m.groupValues[2].toIntOrNull() ?: 0)
            }?.takeIf { it > 0 }
            addActors(
                schema?.optJSONArray("actor")?.let { actors ->
                    (0 until actors.length())
                        .mapNotNull { actors.optJSONObject(it)?.optString("name")?.trim() }
                        .filter { it.isNotEmpty() }
                } ?: doc.select("a[href*='/oyuncu/']").map { it.text().trim() }
                    .filter { it.isNotEmpty() }.distinct()
            )
            schema?.optJSONObject("trailer")?.optString("embedUrl")?.trim()
                ?.takeIf { it.isNotBlank() }?.let { addTrailer(it) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageUrl = fixUrl(data)
        val doc = runCatching { app.get(pageUrl, headers = uaHeaders).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: oynatıcı sayfası yüklenemedi. $REPORT_HINT")

        // Site kaynağı olmayan bölümde oynatıcı yerine "kaynak hazırlanıyor" kutusu basar.
        val playerType = doc.selectFirst("[data-player]")?.attr("data-player-type").orEmpty()
        if (playerType == "none") {
            throw ErrorLoadingException("$name: bu bölüm için izleme kaynağı hazırlanıyor. $REPORT_HINT")
        }
        val slug = doc.selectFirst("[data-pv]")?.attr("data-pv")?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("$name: oynatıcı kimliği bulunamadı. $REPORT_HINT")
        // Oynatıcı alan adı bölüme göre değişir; taban kendi script adresinden türetilir.
        val playerBase = doc.selectFirst("script[src*='/assets/js/core.js']")?.attr("src")
            ?.substringBefore("/assets/js/core.js")?.takeIf { it.startsWith("http") }
            ?: throw ErrorLoadingException("$name: oynatıcı adresi bulunamadı. $REPORT_HINT")

        val frame = runCatching {
            app.get(
                "$playerBase/assets/js/s.php",
                params = mapOf("s" to slug),
                headers = uaHeaders,
                referer = pageUrl,
            ).text
        }.getOrNull() ?: throw ErrorLoadingException("$name: oynatıcı yanıt vermedi. $REPORT_HINT")

        val player = PLAYER_CONFIG.find(frame)?.groupValues?.get(1)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: throw ErrorLoadingException("$name: oynatıcı yapılandırması okunamadı. $REPORT_HINT")

        val collected = mutableListOf<ExtractorLink>()
        player.optString("stream").trim().takeIf { it.isNotEmpty() }?.let { stream ->
            val master = runCatching { app.get(stream, headers = uaHeaders, referer = "$mainUrl/").text }
                .getOrNull()
            val isHls = master?.trimStart()?.startsWith("#EXTM3U") == true || stream.contains(".m3u8")
            val quality = master?.let { text ->
                MASTER_HEIGHT.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()
            } ?: Qualities.Unknown.value
            collected += newExtractorLink(
                name,
                name,
                stream,
                if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
            ) {
                this.referer = "$mainUrl/"
                this.headers = mapOf("User-Agent" to UA)
                this.quality = quality
            }
        }
        if (collected.isEmpty()) {
            throw ErrorLoadingException("$name: oynatılabilir kaynak bulunamadı. $REPORT_HINT")
        }
        val allowed = collected.filter { Settings.qualityAllowed(it.quality) }
        (allowed.ifEmpty { listOfNotNull(collected.minByOrNull { it.quality }) }).forEach(callback)

        player.optJSONArray("subs")?.let { subs ->
            (0 until subs.length()).mapNotNull { subs.optJSONObject(it) }.forEach { sub ->
                val src = sub.optString("src").trim().takeIf { it.isNotEmpty() } ?: return@forEach
                val code = sub.optString("lang").trim().ifEmpty { "tr" }
                if (!Settings.subtitleAllowed(code)) return@forEach
                val label = sub.optString("label").trim().ifEmpty { code }
                subtitleCallback(newSubtitleFile(label, src))
            }
        }
        return true
    }

    // ---------------------------------------------------------------- listeler

    private fun Document.cards(): List<SearchResponse> =
        select("a[href*='/series/'], a[href*='/movie/']").mapNotNull { it.toSearchCard() }
            .distinctBy { it.url }

    private fun Element.toSearchCard(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        // Bölüm kartları da /series/ ile başlar; dizi/film kökü tek segmentlidir.
        val isMovie = MOVIE_PATH.matches(href)
        if (!isMovie && !SERIES_PATH.matches(href)) return null
        val title = selectFirst("h3")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val poster = fixUrlNull(
            selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
        )
        val year = selectFirst("p")?.text()?.trim()?.toIntOrNull()
        val score = scoreOf(selectFirst("span.badge-rating")?.text())

        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                this.score = score
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                this.score = score
            }
        }
    }

    private fun Document.hasNextPage(page: Int): Boolean {
        val next = Regex("""[?&]page=${page + 1}(?:&|#|$)""")
        return select("a[href]").any { next.containsMatchIn(it.attr("href")) }
    }

    // ---------------------------------------------------------------- bölümler

    private fun Document.episodeCards(): List<Episode> =
        select("a[href*='/episode/']").mapNotNull { card ->
            val href = card.attr("href")
            val match = EPISODE_PATH.find(href) ?: return@mapNotNull null
            // Hero'daki "İlk Bölüm İzle" düğmesi aynı adrese gider ama başlık <p>'si yoktur.
            val title = card.selectFirst("p")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            newEpisode(fixUrl(href)) {
                this.name = title
                this.posterUrl = fixUrlNull(card.selectFirst("img")?.attr("src"))
                this.season = match.groupValues[1].toIntOrNull()
                this.episode = match.groupValues[2].toIntOrNull()
            }
        }.distinctBy { it.data }

    // ---------------------------------------------------------------- künye

    private fun Document.schema(type: String): JSONObject? =
        select("script[type=application/ld+json]")
            .mapNotNull { runCatching { JSONObject(it.data()) }.getOrNull() }
            .firstNotNullOfOrNull { root ->
                val graph = root.optJSONArray("@graph")
                if (graph == null) {
                    root.takeIf { it.optString("@type") == type }
                } else {
                    (0 until graph.length()).mapNotNull { graph.optJSONObject(it) }
                        .firstOrNull { it.optString("@type") == type }
                }
            }

    private fun stringsOf(array: org.json.JSONArray): List<String> =
        (0 until array.length()).map { array.optString(it).trim() }.filter { it.isNotEmpty() }

    private fun scoreOf(text: String?): Score? =
        text?.substringBefore('/')?.trim()?.replace(',', '.')?.toDoubleOrNull()?.let { Score.from10(it) }

    companion object {
        /** Ana sayfa satırları; ayarlardan kapatılabilir. */
        val ROWS = listOf(
            "Popüler Diziler", "Son Eklenenler", "En Yüksek Puan", "Dram", "Aksiyon",
            "Komedi", "Bilim Kurgu", "Gizem", "Suç", "Macera", "Belgesel", "Filmler",
        )
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"

        private val SERIES_PATH = Regex("""^https?://[^/]+/series/[^/?#]+/?$""")
        private val MOVIE_PATH = Regex("""^https?://[^/]+/movie/[^/?#]+/?$""")
        private val EPISODE_PATH = Regex("""/season/(\d+)/episode/(\d+)""")
        private val SEASON_PARAM = Regex("""[?&]sezon=(\d+)""")
        private val ISO_DURATION = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?""")
        private val PLAYER_CONFIG =
            Regex("""window\.__PLAYER__\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
        private val MASTER_HEIGHT = Regex("""RESOLUTION=\d+x(\d+)""")
    }
}
