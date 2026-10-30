package com.filmmodu

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
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Filmmodu — filmmodu.news (filmmodu.io artık kumar sitesine yönleniyor).
 * Film sayfasındaki oyuncu, gizli oynatıcıyı `data-pv` slug'ı ile kurar:
 * `<player>/assets/js/s.php?s=<slug>` → window.__PLAYER__ {stream, subs, audios}.
 * Oynatıcı host'u sayfadaki core.js kaynağından türetilir (pilavyerplay.top).
 */
class FilmModuProvider : MainAPI() {
    override var mainUrl = "https://filmmodu.news"
    override var name = "Filmmodu"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    private val uaHeaders = mapOf(
        "User-Agent" to UA,
        "Accept-Language" to "tr-TR,tr;q=0.9",
    )

    override val mainPage = mainPageOf(
        "$mainUrl/filmler?page=SAYFA" to "Filmler",
        "$mainUrl/filmler?sirala=yeni&page=SAYFA" to "En Yeni",
        "$mainUrl/filmler?sirala=puan&page=SAYFA" to "En Yüksek Puan",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data.replace("SAYFA", page.toString()), headers = uaHeaders).document
        return newHomePageResponse(
            request.name,
            doc.select(CARD).mapNotNull { it.toSearchResult() }.distinctBy { it.url },
            hasNext = doc.selectFirst("link[rel=next]") != null,
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/ara?q=$encoded", headers = uaHeaders).document
        return doc.select(CARD).mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val title = selectFirst("h3")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = fixUrlNull(selectFirst("img")?.attr("src"))
            this.year = selectFirst("p")?.text()?.trim()?.toIntOrNull()
            this.score = Score.from10(selectFirst("span.badge-rating")?.text()?.trim())
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url, headers = uaHeaders).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val schema = movieSchema(doc)
        val title = doc.selectFirst("main h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: schema?.optString("name")?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val duration = Regex("""PT(\d+)M""").find(schema?.optString("duration").orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()
        val actors = schema?.optJSONArray("actor")?.let { names(it) }.orEmpty()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = schema?.optString("image")?.takeIf { it.isNotBlank() }?.let { fixUrlNull(it) }
                ?: fixUrlNull(doc.selectFirst("main img[src*='/posters/']")?.attr("src"))
            this.plot = schema?.optString("description")?.takeIf { it.isNotBlank() }
            this.year = schema?.optString("datePublished")?.take(4)?.toIntOrNull()
            this.tags = schema?.optJSONArray("genre")?.let { names(it) }
            this.score = Score.from10(schema?.optJSONObject("aggregateRating")?.optString("ratingValue"))
            this.duration = duration
            if (actors.isNotEmpty()) addActors(actors)
            schema?.optJSONObject("trailer")?.optString("embedUrl")?.takeIf { it.isNotBlank() }
                ?.let { addTrailer(it) }
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
        val doc = runCatching { app.get(data, headers = uaHeaders).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: kaynak bulunamadı. $REPORT_HINT")
        val playerBase = doc.selectFirst("script[src*='/assets/js/core.js']")?.attr("src")
            ?.substringBefore("/assets/js/core.js")?.takeIf { it.startsWith("http") }
            ?: PLAYER_HOST

        var found = false
        for (slot in doc.select("div[data-pv]")) {
            val slug = slot.attr("data-pv").trim()
            if (slug.isEmpty()) continue
            val shellUrl = "$playerBase/assets/js/s.php?s=${URLEncoder.encode(slug, "UTF-8")}"
            // s.php origin kilidi: referer olmadan 403.
            val html = runCatching { app.get(shellUrl, referer = data, headers = uaHeaders).text }
                .getOrNull() ?: continue
            val json = Regex("""window\.__PLAYER__\s*=\s*(\{.*?\});\s*</script>""", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.get(1) ?: continue
            val player = runCatching { JSONObject(json) }.getOrNull() ?: continue
            val stream = player.optString("stream").takeIf { it.startsWith("http") } ?: continue

            val subs = player.optJSONArray("subs")
            for (i in 0 until (subs?.length() ?: 0)) {
                val sub = subs?.optJSONObject(i) ?: continue
                val src = sub.optString("src").takeIf { it.startsWith("http") } ?: continue
                val label = sub.optString("label").takeIf { it.isNotBlank() }
                    ?: sub.optString("lang").takeIf { it.isNotBlank() }
                    ?: "Altyazı"
                subtitleFilter.invoke(newSubtitleFile(label, src))
            }

            // Dublaj ayrı akış değil: master m3u8'in HLS ses gruplarında gelir (audios[]), oynatıcı seçer.
            linkCallback.invoke(
                newExtractorLink(name, name, stream, ExtractorLinkType.M3U8) {
                    this.referer = shellUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            found = true
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir kaynak yok. $REPORT_HINT")
        return true
    }

    /** Film sayfasındaki Movie düğümü @graph içinde gelir. */
    private fun movieSchema(doc: Document): JSONObject? {
        for (script in doc.select("script[type=application/ld+json]")) {
            val root = runCatching { JSONObject(script.data()) }.getOrNull() ?: continue
            val graph = root.optJSONArray("@graph") ?: JSONArray().put(root)
            for (i in 0 until graph.length()) {
                val node = graph.optJSONObject(i) ?: continue
                if (node.optString("@type") == "Movie") return node
            }
        }
        return null
    }

    /** [{"@type":"Person","name":"..."}] dizisinden adları çıkarır. */
    private fun names(array: JSONArray): List<String> =
        (0 until array.length())
            .mapNotNull { array.optJSONObject(it)?.optString("name")?.takeIf { s -> s.isNotBlank() } }

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val CARD = "a[class*=group][href*='/film/']"
        private const val PLAYER_HOST = "https://pilavyerplay.top"
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}
