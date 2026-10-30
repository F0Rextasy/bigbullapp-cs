package com.hdfilmizle

import android.util.Base64
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
import com.lagradost.cloudstream3.MainPageData
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
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * HDFilmizle — hdfilmizle.ink (WordPress "stmovie" teması; Full HD film + yabancı dizi).
 *
 * Listeler: `/film/page/N/`, `/dizi/page/N/`, `/bolum/page/N/`, türler `/tur/<tür>/page/N/`.
 * Arama `/?s=<sorgu>`; site aramayı sayfalamaz (`/page/2/?s=` kendi 404 şablonunu döner).
 * Dizi sayfası bütün sezonları satır içinde basar: `#seasons div.se-c` → `ul.episodios`.
 * Bölüm sayfası ayrı bir içerik türüdür; üst dizisine alttaki breadcrumb'dan geçilir.
 *
 * Oynatıcı zinciri: `#playex` nonce + `a.options[data-post-id][data-player-name]` →
 * `admin-ajax.php` `get_video_url` bir köprü sayfası döner (setplay.sbs). Köprü, gerçek
 * oynatıcıyı `SPG.cerceve("b2", ...)` içindeki XOR'lanmış base64 çiftinde saklar. İkinci
 * kat (fastplay.mom) `window.FSP.stream` içinde HLS master yolunu, `tracks` içinde altyazıları
 * verir. Manifest kapısı masaüstü tarayıcı kimliğinde 404 döndüğü için akış mobil UA ile
 * veriliyor (SetFilmIzle ile aynı altyapı).
 */
class HDFilmizleProvider : MainAPI() {
    override var mainUrl = "https://www.hdfilmizle.ink"
    override var name = "HDFilmizle"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val uaHeaders = mapOf(
        "User-Agent" to UA,
        "Accept-Language" to "tr-TR,tr;q=0.9",
    )

    override val mainPage get() = ROWS.mapNotNull { name ->
        if (!Settings.rowEnabled(name)) return@mapNotNull null
        when (name) {
            "Son Filmler" -> MainPageData(name, "$mainUrl/film/page/SAYFA/")
            "Son Diziler" -> MainPageData(name, "$mainUrl/dizi/page/SAYFA/")
            "Son Bölümler" -> MainPageData(name, "$mainUrl/bolum/page/SAYFA/")
            "Aksiyon" -> MainPageData(name, "$mainUrl/tur/aksiyon/page/SAYFA/")
            "Komedi" -> MainPageData(name, "$mainUrl/tur/komedi/page/SAYFA/")
            "Dram" -> MainPageData(name, "$mainUrl/tur/dram/page/SAYFA/")
            "Gerilim" -> MainPageData(name, "$mainUrl/tur/gerilim/page/SAYFA/")
            "Korku" -> MainPageData(name, "$mainUrl/tur/korku/page/SAYFA/")
            "Bilim Kurgu" -> MainPageData(name, "$mainUrl/tur/bilim-kurgu/page/SAYFA/")
            "Fantastik" -> MainPageData(name, "$mainUrl/tur/fantastik/page/SAYFA/")
            "Animasyon" -> MainPageData(name, "$mainUrl/tur/animasyon/page/SAYFA/")
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data.replace("SAYFA", page.toString()), headers = uaHeaders).document
        return newHomePageResponse(
            request.name,
            doc.cards(),
            hasNext = doc.selectFirst("a[href*='/page/${page + 1}/']") != null,
        )
    }

    override suspend fun search(query: String): List<SearchResponse> =
        search(query, 1)?.items ?: emptyList()

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (page > 1) return newSearchResponseList(emptyList(), false)
        val term = URLEncoder.encode(query.trim(), "UTF-8")
        val doc = app.get("$mainUrl/?s=$term", headers = uaHeaders).document
        return newSearchResponseList(doc.cards(), false)
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = fixUrl(url)
        val doc = runCatching { app.get(pageUrl, headers = uaHeaders).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: içerik sayfası yüklenemedi. $REPORT_HINT")

        // Bölüm sayfası kendi başına künye basmaz; üst dizisinde bütün sezonlar var.
        if (pageUrl.contains("/bolum/")) {
            val seriesUrl = doc.selectFirst("div.dt-breadcrumb a[href*='/dizi/']")?.attr("href")
                ?.let { fixUrlNull(it) }
                ?: throw ErrorLoadingException("$name: bölümün dizisi bulunamadı. $REPORT_HINT")
            return load(seriesUrl)
        }

        val schema = doc.select("script[type=application/ld+json]").firstNotNullOfOrNull { script ->
            runCatching { JSONObject(script.data()) }.getOrNull()
                ?.takeIf { it.optString("@type") == "Movie" || it.optString("@type") == "TVSeries" }
        }
        val title = schema?.optString("name")?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("header.sheader h1")?.text()?.replace(YEAR_PAREN, "")
                ?.removeSuffix(" izle")?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("$name: başlık okunamadı. $REPORT_HINT")

        val fields = doc.customFields()
        val poster = fixUrlNull(schema?.optString("image")?.takeIf { it.startsWith("http") })
            ?: fixUrlNull(doc.selectFirst("header.sheader .poster img[src]")?.attr("src"))
        val plot = doc.selectFirst("section#info .wp-content")?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val year = doc.selectFirst("header.sheader h1 a[href*='/yil/']")?.text()?.trim()?.toIntOrNull()
        val score = Score.from10(doc.selectFirst("b#repimdb strong")?.text()?.trim())
        val duration = DURATION.find(doc.selectFirst("header.sheader span.runtime")?.text().orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()
        val tags = doc.select("div.sgeneros a").map { it.text().trim() }
            .filter { it.isNotEmpty() }.distinct()
        val actors = fields["Oyuncular"]?.select("a[href]")?.map { it.text().trim() }
            ?.filter { it.isNotEmpty() }?.distinct().orEmpty()
        val trailer = doc.selectFirst("section#trailer iframe")
            ?.let { it.attr("ysrc").ifBlank { it.attr("src") } }?.trim()
            ?.takeIf { it.isNotEmpty() }

        if (pageUrl.contains("/dizi/")) {
            val episodes = doc.episodes()
            if (episodes.isEmpty()) {
                throw ErrorLoadingException("$name: bölüm listesi okunamadı. $REPORT_HINT")
            }
            return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = score
                this.tags = tags
                addActors(actors)
                trailer?.let { addTrailer(it) }
            }
        }

        return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.score = score
            this.duration = duration
            this.tags = tags
            addActors(actors)
            trailer?.let { addTrailer(it) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageUrl = fixUrl(data)
        val page = runCatching { app.get(pageUrl, headers = uaHeaders) }.getOrNull()
            ?: throw ErrorLoadingException("$name: oynatıcı sayfası yüklenemedi. $REPORT_HINT")
        val doc = page.document
        val html = page.text
        val nonce = doc.selectFirst("#playex")?.attr("data-nonce")?.trim()?.takeIf { it.isNotEmpty() }
            ?: NONCE.find(html)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("$name: oynatıcı anahtarı bulunamadı. $REPORT_HINT")
        val players = doc.select("#playex a.options[data-post-id]").mapNotNull { el ->
            val postId = el.attr("data-post-id").trim().takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            Triple(postId, el.attr("data-player-name").trim(), el.attr("data-part-key").trim())
        }.distinct()
        if (players.isEmpty()) {
            throw ErrorLoadingException("$name: oynatıcı listesi bulunamadı. $REPORT_HINT")
        }

        val candidates = mutableListOf<Candidate>()
        var subtitlesSent = false
        for ((postId, playerName, partKey) in players) {
            val bridgeUrl = requestBridge(pageUrl, nonce, postId, playerName, partKey) ?: continue
            val playerUrl = resolveBridge(bridgeUrl, pageUrl) ?: continue
            val bridgeOrigin = ORIGIN.find(bridgeUrl)?.value ?: BRIDGE_REFERER
            val source = readPlayerSource(playerUrl, bridgeOrigin) ?: continue

            if (!subtitlesSent) {
                source.subtitles.distinctBy { it.label }.forEach { subtitle ->
                    if (!Settings.subtitleAllowed("${subtitle.label} ${subtitle.code}")) return@forEach
                    subtitleCallback.invoke(newSubtitleFile(subtitle.label, subtitle.url))
                }
                subtitlesSent = true
            }
            candidates += Candidate(
                url = source.masterUrl,
                label = playerName.ifEmpty { "SetPlay" },
                referer = playerUrl,
                quality = readQuality(source.masterUrl, playerUrl),
            )
        }
        if (candidates.isEmpty()) {
            throw ErrorLoadingException("$name: oynatılabilir kaynak bulunamadı. $REPORT_HINT")
        }

        // Kalite süzgeci tek kaynağı eleyebilir; o durumda en düşük kaliteli link verilir.
        val allowed = candidates.filter { Settings.qualityAllowed(it.quality) }
        val emit = allowed.ifEmpty {
            listOfNotNull(candidates.minByOrNull { if (it.quality > 0) it.quality else Int.MAX_VALUE })
        }
        for (candidate in emit) {
            callback.invoke(
                newExtractorLink(name, candidate.label, candidate.url, ExtractorLinkType.M3U8) {
                    this.referer = candidate.referer
                    this.headers = mapOf("User-Agent" to MOBILE_UA, "Accept" to "*/*")
                    this.quality = candidate.quality
                }
            )
        }
        return true
    }

    // ---------------------------------------------------------------- listeler

    private fun Document.cards(): List<SearchResponse> =
        select("article.item").mapNotNull { it.toSearchCard() }.distinctBy { it.url }

    private fun Element.toSearchCard(): SearchResponse? {
        val href = fixUrlNull(selectFirst("a[href]")?.attr("href")) ?: return null
        val isSeries = href.contains("/dizi/") || href.contains("/bolum/")
        if (!isSeries && !href.contains("/film/")) return null

        val title = selectFirst("div.data .title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: selectFirst("div.figure h2")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: selectFirst("div.data h3")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: selectFirst("div.data h2")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val poster = fixUrlNull(
            selectFirst("img[data-src]")?.attr("data-src")
                ?: selectFirst("img[src]")?.attr("src")
                    ?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
        )
        val year = selectFirst("span.anayil")?.text()?.trim()?.toIntOrNull()
            ?: selectFirst("span.year")?.text()?.trim()?.toIntOrNull()
        val score = Score.from10(
            selectFirst("span.rating")?.text()?.trim() ?: selectFirst("span.imdb-score")?.text()?.trim()
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

    // ---------------------------------------------------------------- künye

    /** Dizi sayfası tüm sezonları bastığı için bölümler tek istekle toplanır. */
    private fun Document.episodes(): List<Episode> {
        val found = LinkedHashMap<String, Episode>()
        for (panel in select("#seasons div.se-c")) {
            val season = panel.selectFirst("span.se-t")?.text()?.trim()?.toIntOrNull() ?: continue
            for (item in panel.select("ul.episodios li")) {
                val anchor = item.selectFirst("h4.episodiotitle a[href]") ?: continue
                val href = fixUrlNull(anchor.attr("href")) ?: continue
                if (found.containsKey(href)) continue
                val number = EPISODE_IN_URL.find(href)?.groupValues?.get(2)?.toIntOrNull()
                found[href] = newEpisode(href) {
                    this.season = season
                    this.episode = number
                    this.name = anchor.ownText().trim().takeIf { it.isNotEmpty() }
                }
            }
        }
        return found.values.sortedWith(compareBy({ it.season ?: Int.MAX_VALUE }, { it.episode ?: Int.MAX_VALUE }))
    }

    private fun Document.customFields(): Map<String, Element> =
        select("div.custom_fields").mapNotNull { row ->
            val key = row.selectFirst("span.variante")?.text()?.trim() ?: return@mapNotNull null
            val value = row.selectFirst("span.valor") ?: return@mapNotNull null
            key to value
        }.toMap()

    // ---------------------------------------------------------------- oynatıcı

    /** `admin-ajax.php` → `get_video_url`: köprü sayfasının adresini döner. */
    private suspend fun requestBridge(
        pageUrl: String,
        nonce: String,
        postId: String,
        playerName: String,
        partKey: String,
    ): String? {
        val body = runCatching {
            app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "get_video_url",
                    "nonce" to nonce,
                    "post_id" to postId,
                    "player_name" to playerName,
                    "part_key" to partKey,
                ),
                headers = uaHeaders + mapOf("X-Requested-With" to "XMLHttpRequest"),
                referer = pageUrl,
            ).text
        }.getOrNull() ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
            ?.optJSONObject("data")?.optString("url")?.takeIf { it.startsWith("http") }
    }

    /** Köprü sayfası gerçek oynatıcı adresini iki base64 metnin XOR'unda tutar. */
    private suspend fun resolveBridge(bridgeUrl: String, referer: String): String? {
        val html = runCatching {
            app.get(bridgeUrl, headers = uaHeaders, referer = referer).text
        }.getOrNull() ?: return null
        val match = BRIDGE.find(html) ?: return null
        return xorDecode(match.groupValues[1], match.groupValues[2])
            .split('|').firstOrNull { it.startsWith("http") }
    }

    /** Oynatıcı sayfası HLS master yolunu ve altyazı listesini `window.FSP` içinde verir. */
    private suspend fun readPlayerSource(playerUrl: String, bridgeOrigin: String): PlayerSource? {
        val html = runCatching {
            app.get(playerUrl, headers = uaHeaders, referer = bridgeOrigin).text
        }.getOrNull() ?: return null
        val path = STREAM.find(html)?.groupValues?.get(1)?.replace("\\/", "/") ?: return null
        val origin = ORIGIN.find(playerUrl)?.value ?: return null
        return PlayerSource("$origin$path", parseSubtitles(TRACKS.find(html)?.groupValues?.get(1)))
    }

    /** Master tek varyant basar (`RESOLUTION=1920x1080`); etiket oradan okunur. */
    private suspend fun readQuality(masterUrl: String, referer: String): Int {
        val text = runCatching {
            app.get(masterUrl, headers = mobileHeaders, referer = referer).text.take(MASTER_HEAD)
        }.getOrNull() ?: return Qualities.Unknown.value
        return HEIGHT.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()
            ?: Qualities.Unknown.value
    }

    private fun parseSubtitles(json: String?): List<PlayerSubtitle> {
        if (json.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val kind = item.optString("kind")
            if (kind.isNotBlank() && kind != "captions") return@mapNotNull null
            val url = item.optString("file").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val label = item.optString("label").takeIf { it.isNotBlank() }
                ?: item.optString("lang").takeIf { it.isNotBlank() } ?: "Altyazı"
            PlayerSubtitle(label, item.optString("lang"), url)
        }
    }

    private fun xorDecode(first: String, second: String): String {
        val a = Base64.decode(first.replace("\\/", "/"), Base64.DEFAULT)
        val b = Base64.decode(second.replace("\\/", "/"), Base64.DEFAULT)
        if (a.isEmpty() || b.isEmpty()) return ""
        return String(ByteArray(a.size) { i -> (a[i].toInt() xor b[i % b.size].toInt()).toByte() }, Charsets.UTF_8)
    }

    private val mobileHeaders = mapOf(
        "User-Agent" to MOBILE_UA,
        "Accept" to "*/*",
    )

    private data class Candidate(
        val url: String,
        val label: String,
        val referer: String,
        val quality: Int,
    )

    private data class PlayerSource(
        val masterUrl: String,
        val subtitles: List<PlayerSubtitle>,
    )

    private data class PlayerSubtitle(val label: String, val code: String, val url: String)

    companion object {
        /** Homepage rows in display order; all map to live listing pages. */
        val ROWS = listOf(
            "Son Filmler",
            "Son Diziler",
            "Son Bölümler",
            "Aksiyon",
            "Komedi",
            "Dram",
            "Gerilim",
            "Korku",
            "Bilim Kurgu",
            "Fantastik",
            "Animasyon",
        )

        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        /** Manifest kapısı masaüstü kimliğinde 404 verdiği için oynatma mobil kimlikle yapılır. */
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        private const val BRIDGE_REFERER = "https://setplay.sbs/"
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
        private const val MASTER_HEAD = 20000

        private val YEAR_PAREN = Regex("""\s*\(\s*\d{4}\s*\)\s*$""")
        private val DURATION = Regex("""(\d+)\s*Dak""")
        private val EPISODE_IN_URL = Regex("""-(\d+)-sezon-(\d+)-bolum""")
        private val NONCE = Regex(""""nonce"\s*:\s*"([^"]+)"""")
        private val BRIDGE = Regex("""cerceve\(\s*"b2"\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\)""")
        private val STREAM = Regex("""stream:\s*"([^"]+)"""")
        private val TRACKS = Regex("""tracks:\s*(\[[\s\S]*?])\s*,""")
        private val ORIGIN = Regex("""^(https?://[^/]+)""")
        private val HEIGHT = Regex("""RESOLUTION=\d+x(\d+)""")
    }
}
