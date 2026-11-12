package com.turkanimetv

import android.util.Base64
import com.bigbull.common.browserHeaders
import com.bigbull.common.jsonLdDescription
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
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
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Türk Anime TV — turkanime.live (PHP + Tailwind, Türkçe altyazılı anime arşivi).
 *
 * Yollar: dizi `/dizi/<slug>`, film `/film/<slug>`, bölüm `/bolum/<dizi>-<sezon>-sezon-<bölüm>-bolum`.
 * Arama `api/search.php?q=` JSON'u (sayfalama yok). Liste `/animeler?sort=rating|year|page`,
 * son bölümler `/bolumler?page=`; ana sayfa bölümleri `data-section=` bloklarında sunucu tarafında basılır.
 *
 * Oynatıcı iki adımlı: bölüm/film sayfasındaki base64 `encodedContent` iframe'i
 * (`streamcorecdn.com/dizi/<içerik>/<sezon>/<bölüm>` veya `/film/<id>`) açar; o sayfadaki
 * `PLAYER_BOOT` master playlist'i (`hlsStorageDomain` + `src`) ve altyazı listesini taşır.
 * Segmentler `.jpg` adıyla gelen MPEG-TS; şifreleme yok, master doğrudan oynar.
 */
class TurkAnimeTVProvider : MainAPI() {
    override var mainUrl = "https://turkanime.live"
    override var name = "TurkAnimeTV"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override val mainPage get() = ROWS.mapNotNull { row ->
        if (!Settings.rowEnabled(row)) return@mapNotNull null
        MainPageData(
            row,
            when (row) {
                "Son Eklenen Animeler" -> "section:latest_animes"
                "Trend Animeler" -> "section:trending_animes"
                "Güncel Bölümler" -> "episodes:$mainUrl/bolumler"
                "Popüler Animeler" -> "$mainUrl/animeler?sort=rating"
                "Sezonluk Animeler" -> "$mainUrl/animeler?year=$SEASON_YEAR&tab=series"
                else -> return@mapNotNull null
            },
        )
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data
        if (data.startsWith("section:")) {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            val section = data.removePrefix("section:")
            val doc = app.get("$mainUrl/", headers = HEADERS).document
            val cards = doc.select(
                "[data-section=$section] a[href*='/dizi/'], [data-section=$section] a[href*='/film/']",
            ).mapNotNull { it.toSearchResult() }.distinctBy { it.url }
            return newHomePageResponse(request.name, cards, false)
        }

        val episodes = data.startsWith("episodes:")
        val base = if (episodes) data.removePrefix("episodes:") else data
        val url = base + (if ('?' in base) "&" else "?") + "page=$page"
        val doc = app.get(url, headers = HEADERS).document
        val items = if (episodes) {
            doc.select("a[href*='/bolum/']").mapNotNull { it.toSeriesFromEpisode() }
        } else {
            doc.select("#anime-grid-container a[href*='/dizi/'], #anime-grid-container a[href*='/film/']")
                .mapNotNull { it.toSearchResult() }
        }
        return newHomePageResponse(
            request.name,
            items.distinctBy { it.url },
            doc.selectFirst("a[href*='page=${page + 1}']") != null,
        )
    }

    override suspend fun search(query: String): List<SearchResponse> =
        search(query, 1)?.items.orEmpty()

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val term = query.trim()
        // Uç nokta tüm eşleşmeleri tek yanıtta veriyor.
        if (term.isEmpty() || page > 1) return newSearchResponseList(emptyList(), false)
        val body = runCatching {
            app.get("$mainUrl/api/search.php?q=${URLEncoder.encode(term, "UTF-8")}", headers = HEADERS).text
        }.getOrNull() ?: return newSearchResponseList(emptyList(), false)
        val rows = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull()
            ?: return newSearchResponseList(emptyList(), false)

        val items = (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val title = row.optString("title").trim()
            val slug = row.optString("slug").trim().trim('/')
            if (title.isEmpty() || slug.isEmpty()) return@mapNotNull null
            val movie = row.optString("type") == "movie"
            val url = "$mainUrl/${if (movie) "film" else "dizi"}/$slug"
            val poster = fixUrlNull(row.optString("poster"))
            if (movie) {
                newMovieSearchResponse(title, url, TvType.AnimeMovie) { this.posterUrl = poster }
            } else {
                newTvSeriesSearchResponse(title, url, TvType.Anime) { this.posterUrl = poster }
            }
        }.distinctBy { it.url }
        return newSearchResponseList(items, false)
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = fixUrl(url)
        val doc = runCatching { app.get(pageUrl, headers = HEADERS).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: sayfa yüklenemedi. $REPORT_HINT")
        val h1 = doc.selectFirst("h1")
            ?: throw ErrorLoadingException("$name: başlık okunamadı. $REPORT_HINT")
        val title = h1.text().trim().takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: başlık okunamadı. $REPORT_HINT")

        val header = h1.nextElementSibling()?.text().orEmpty()
        val schema = doc.schema()
        val year = schema?.optString("datePublished")?.take(4)?.toIntOrNull()
            ?: YEAR.find(header)?.value?.toIntOrNull()
        val score = SCORE.find(header)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: IMDB.find(header)?.groupValues?.get(1)?.toDoubleOrNull()
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val tags = doc.select("a[href*='/kategori/']").map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct()
        val plot = doc.jsonLdDescription()
        if (pageUrl.contains("/film/")) {
            return newMovieLoadResponse(title, pageUrl, TvType.AnimeMovie, pageUrl) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = plot
                this.year = year
                this.score = score?.let { Score.from10(it) }
                this.tags = tags
            }
        }

        val episodes = doc.select("a[href*='/bolum/']").mapNotNull { anchor ->
            val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            val season = anchor.parents().firstOrNull { it.id().startsWith("season-") }
                ?.id()?.removePrefix("season-")?.toIntOrNull()
            val slot = EPISODE_SUFFIX.find(href)
            val episode = anchor.selectFirst("span")?.text()?.trim()?.toIntOrNull()
                ?: slot?.groupValues?.get(2)?.toIntOrNull()
            val label = anchor.selectFirst("h4")?.text()?.trim().orEmpty()
            newEpisode(href) {
                this.name = label.ifBlank { null }
                this.season = season ?: slot?.groupValues?.get(1)?.toIntOrNull()
                this.episode = episode
                this.posterUrl = fixUrlNull(poster)
            }
        }.distinctBy { it.data }
        if (episodes.isEmpty()) {
            throw ErrorLoadingException("$name: bölüm listesi bulunamadı. $REPORT_HINT")
        }

        val actors = doc.select("div[class*='group/actor']")
            .mapNotNull { it.selectFirst("div.font-bold")?.text()?.trim()?.takeIf(String::isNotBlank) }

        return newTvSeriesLoadResponse(title, pageUrl, TvType.Anime, episodes) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = plot
            this.year = year
            this.score = score?.let { Score.from10(it) }
            this.tags = tags
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageUrl = fixUrl(data)
        val html = runCatching { app.get(pageUrl, headers = HEADERS).text }.getOrNull()
            ?: throw ErrorLoadingException("$name: bölüm sayfası yüklenemedi. $REPORT_HINT")
        val blob = BLOB.find(html)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("$name: oynatıcı bağlantısı bulunamadı. $REPORT_HINT")
        val iframeHtml = runCatching {
            String(Base64.decode(blob, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull() ?: throw ErrorLoadingException("$name: oynatıcı çözülemedi. $REPORT_HINT")
        val iframeUrl = IFRAME.find(iframeHtml)?.groupValues?.get(1)?.let { fixUrl(it) }
            ?: throw ErrorLoadingException("$name: oynatıcı adresi okunamadı. $REPORT_HINT")

        val playerHtml = runCatching {
            app.get(iframeUrl, headers = HEADERS, referer = pageUrl).text
        }.getOrNull() ?: throw ErrorLoadingException("$name: yayın sayfası açılmadı. $REPORT_HINT")
        val storage = jsValue(playerHtml, "hlsStorageDomain")?.trimEnd('/')
            ?: throw ErrorLoadingException("$name: yayın sunucusu okunamadı. $REPORT_HINT")
        val src = jsValue(playerHtml, "src")
            ?: throw ErrorLoadingException("$name: mastar adresi okunamadı. $REPORT_HINT")
        val masterUrl = "$storage/${src.trimStart('/')}"

        val tracks = TRACKS.find(playerHtml)?.groupValues?.get(1)?.let { raw ->
            runCatching { JSONObject(raw) }.getOrNull()
        }
        val base = "$storage/${jsValue(playerHtml, "hlsBasePath").orEmpty().trimStart('/').trimEnd('/')}/"
        tracks?.optJSONArray("subtitles")?.forEachSubtitle { row ->
            val path = row.optString("url").trim()
            val code = row.optString("lang").trim()
            if (path.isEmpty() || !Settings.subtitleAllowed(code)) return@forEachSubtitle
            val label = row.optString("name").trim().ifBlank { code.ifBlank { "Altyazı" } }
            subtitleCallback(SubtitleFile(label, "$base${path.trimStart('/')}").apply {
                this.headers = mapOf("Referer" to iframeUrl, "user-agent" to UA)
            })
        }

        val master = runCatching { app.get(masterUrl, headers = HEADERS, referer = iframeUrl).text }
            .getOrNull().orEmpty()
        if (!master.trimStart().startsWith("#EXTM3U")) {
            throw ErrorLoadingException("$name: yayın listesi alınamadı. $REPORT_HINT")
        }
        val height = RESOLUTION.findAll(master).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()
        val quality = height?.takeIf { it >= 144 } ?: Qualities.Unknown.value

        val link = newExtractorLink(name, name, masterUrl, ExtractorLinkType.M3U8) {
            this.referer = iframeUrl
            this.headers = mapOf("Referer" to iframeUrl, "user-agent" to UA)
            this.quality = quality
        }
        // Tek kaynak var: kalite sınırı onu elediğinde de yayın verilir (repo kuralı).
        listOf(link).filter { Settings.qualityAllowed(it.quality) }.ifEmpty { listOf(link) }.forEach(callback)
        return true
    }

    // ---------------------------------------------------------------- kartlar

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val movie = href.contains("/film/")
        if (!movie && !href.contains("/dizi/")) return null
        val image = selectFirst("img")
        val title = image?.attr("alt")?.substringBefore(" izle")?.trim()?.takeIf { it.isNotBlank() }
            ?: selectFirst("h3")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val text = text()
        val year = YEAR.find(text)?.value?.toIntOrNull()
        val score = SCORE.find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        val poster = fixUrlNull(image?.attr("src"))
        return if (movie) {
            newMovieSearchResponse(title, href, TvType.AnimeMovie) {
                this.posterUrl = poster
                this.year = year
                this.score = score?.let { Score.from10(it) }
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
                this.year = year
                this.score = score?.let { Score.from10(it) }
            }
        }
    }

    /** `/bolumler` kartları yalnız bölüm adresi verir; dizi slug'ı son ek atılarak bulunur. */
    private fun Element.toSeriesFromEpisode(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val slug = href.substringAfterLast('/')
        val series = EPISODE_SUFFIX.replace(slug, "")
        if (series == slug || series.isBlank()) return null
        val title = selectFirst("h3")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return newTvSeriesSearchResponse(title, "$mainUrl/dizi/$series", TvType.Anime) {
            this.posterUrl = fixUrlNull(selectFirst("img")?.attr("src"))
        }
    }

    // ---------------------------------------------------------------- yardımcılar

    private fun Document.schema(): JSONObject? =
        select("script[type=application/ld+json]").firstNotNullOfOrNull { script ->
            runCatching { JSONObject(script.data()) }.getOrNull()?.takeIf {
                it.optString("@type") == "Movie" || it.optString("@type") == "TVSeries"
            }
        }

    private inline fun JSONArray.forEachSubtitle(action: (JSONObject) -> Unit) {
        for (index in 0 until length()) {
            action(optJSONObject(index) ?: continue)
        }
    }

    /** `PLAYER_BOOT` düz JS nesnesi; değeri tırnaklı alanlar JSON kaçışlarıyla (`\\/`) gelir. */
    private fun jsValue(html: String, key: String): String? =
        Regex("\\b$key\\s*:\\s*\"([^\"]*)\"").find(html)?.groupValues?.get(1)
            ?.let { JS_ESCAPE.replace(it) { match -> unescape(match.groupValues[1]) } }

    private fun unescape(token: String): String =
        if (token.startsWith("u") && token.length == 5) {
            token.substring(1).toIntOrNull(16)?.toChar()?.toString() ?: token
        } else {
            token
        }

    companion object {
        val ROWS = listOf(
            "Son Eklenen Animeler",
            "Trend Animeler",
            "Güncel Bölümler",
            "Popüler Animeler",
            "Sezonluk Animeler",
        )

        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
        private val HEADERS = browserHeaders

        // shortcut: sezon yılı sabit; site yeni sezona geçince güncellenmeli.
        private const val SEASON_YEAR = 2026

        private val YEAR = Regex("""\b(19\d{2}|20\d{2})\b""")
        private val SCORE = Regex("""★\s*([\d.]+)""")
        private val IMDB = Regex("""([\d.]+)\s*IMDB""")
        private val BLOB = Regex("""encodedContent\s*=\s*'([A-Za-z0-9+/=]+)'""")
        private val IFRAME = Regex("<iframe[^>]+src=\"([^\"]+)\"")
        private val TRACKS = Regex("""tracksData\s*:\s*(\{.*?\})\s*,\s*hlsBasePath""", RegexOption.DOT_MATCHES_ALL)
        private val RESOLUTION = Regex("""RESOLUTION=\d+x(\d+)""")
        private val JS_ESCAPE = Regex("""\\(u[0-9a-fA-F]{4}|.)""")
        private val EPISODE_SUFFIX = Regex("""-(\d+)-sezon-(\d+)-bolum/?$""")
    }
}
