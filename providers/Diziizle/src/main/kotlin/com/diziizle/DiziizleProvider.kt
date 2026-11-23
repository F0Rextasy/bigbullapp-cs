package com.diziizle

import com.bigbull.common.browserHeaders
import com.bigbull.common.posterSrcLazy
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
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
 * Diziizle — diziizle2.icu, yabancı dizi arşivi (TR/EN altyazı).
 * Bölüm sayfasındaki `div[data-pv]` slug'ı, core.js kaynağından türetilen oynatıcı host'unda
 * `/assets/js/s.php?s=<slug>` olarak çözülür; referer zorunlu (403). Yanıt `window.__PLAYER__`
 * JSON'u: {stream → HLS master, subs[] → TR/EN VTT}.
 */

class DiziizleProvider : MainAPI() {
    override var mainUrl = "https://diziizle2.icu"
    override var name = "Diziizle"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries)

    private val uaHeaders get() = browserHeaders

    override val mainPage get() = ROWS.mapNotNull { name ->
        if (!Settings.rowEnabled(name)) return@mapNotNull null
        when (name) {
            "Trend Diziler" -> MainPageData(name, "$mainUrl/diziler?page=SAYFA")
            "Son Eklenen Diziler" -> MainPageData(name, "$mainUrl/diziler?sirala=yeni&page=SAYFA")
            "Netflix" -> MainPageData(name, "$mainUrl/platform/netflix")
            "Disney+" -> MainPageData(name, "$mainUrl/platform/disney")
            "HBO Max" -> MainPageData(name, "$mainUrl/platform/hbo-max")
            "Aksiyon & Macera" -> MainPageData(name, "$mainUrl/tur/aksiyon-macera")
            "Bilim Kurgu & Fantastik" -> MainPageData(name, "$mainUrl/tur/bilim-kurgu-fantastik")
            "Animasyon" -> MainPageData(name, "$mainUrl/tur/animasyon")
            "Dram" -> MainPageData(name, "$mainUrl/tur/dram")
            "Gizem" -> MainPageData(name, "$mainUrl/tur/gizem")
            "Komedi" -> MainPageData(name, "$mainUrl/tur/komedi")
            "Suç" -> MainPageData(name, "$mainUrl/tur/suc")
            else -> null
        }
    }

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
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = fixUrlNull(selectFirst("img")?.posterSrcLazy())
            this.year = selectFirst("p")?.text()?.trim()?.toIntOrNull()
            this.score = Score.from10(selectFirst("span.badge-rating")?.text()?.trim())
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url, headers = uaHeaders).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: dizi yüklenemedi. $REPORT_HINT")
        val schema = tvSeriesSchema(doc)
        val title = doc.selectFirst("main h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: schema?.optString("name")?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: başlık okunamadı. $REPORT_HINT")

        // Sezon listesi tek sayfada gelir; her sezonun bölümleri ?sezon=N sayfasında.
        val seasons = doc.select("a[href*='?sezon=']").mapNotNull {
            Regex("""sezon=(\d+)""").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        }.distinct().sorted()
        val seasonDocs = if (seasons.isEmpty()) listOf(doc) else seasons.mapNotNull { season ->
            runCatching { app.get("$url?sezon=$season", headers = uaHeaders).document }.getOrNull()
        }

        val episodes = buildList {
            for (seasonDoc in seasonDocs) {
                for (card in seasonDoc.select("#bolumler a[href*='/bolum-']")) {
                    val href = fixUrlNull(card.attr("href")) ?: continue
                    val match = EPISODE.find(href) ?: continue
                    val episodeName = card.selectFirst("p")?.text()?.trim()?.takeIf { it.isNotBlank() }
                    add(newEpisode(href) {
                        this.name = episodeName
                            ?: "${match.groupValues[1]}. Sezon ${match.groupValues[2]}. Bölüm"
                        this.season = match.groupValues[1].toIntOrNull()
                        this.episode = match.groupValues[2].toIntOrNull()
                        this.posterUrl = fixUrlNull(card.selectFirst("img")?.posterSrcLazy())
                    })
                }
            }
        }.distinctBy { it.data }.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = schema?.optString("image")?.takeIf { it.isNotBlank() }?.let { fixUrlNull(it) }
                ?: fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
            this.plot = schema?.optString("description")?.takeIf { it.isNotBlank() }
            this.year = schema?.optString("startDate")?.take(4)?.toIntOrNull()
            this.tags = strings(schema?.optJSONArray("genre"))
            this.score = Score.from10(schema?.optJSONObject("aggregateRating")?.optString("ratingValue"))
            this.backgroundPosterUrl = schema?.optJSONObject("trailer")?.optString("thumbnailUrl")
                ?.takeIf { it.isNotBlank() }?.let { fixUrlNull(it) }
            val actors = strings(schema?.optJSONArray("actor"))
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
        val doc = runCatching { app.get(data, headers = uaHeaders).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: bölüm yüklenemedi. $REPORT_HINT")
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
            // Master m3u8 verilir: ses parçası #EXT-X-MEDIA grubunda, varyant tek başına sessiz oynar.
            val stream = player.optString("stream").takeIf { it.startsWith("http") } ?: continue

            val subs = player.optJSONArray("subs")
            for (i in 0 until (subs?.length() ?: 0)) {
                val sub = subs?.optJSONObject(i) ?: continue
                // "embedded" altyazıda src yok; akışa gömülü gelir.
                val src = sub.optString("src").takeIf { it.startsWith("http") } ?: continue
                val label = sub.optString("label").takeIf { it.isNotBlank() }
                    ?: sub.optString("lang").takeIf { it.isNotBlank() }
                    ?: "Altyazı"
                // "İngilizce Altyazı" gibi görünen etiket, tercih filtresinde "en" koduyla eşleşsin.
                val lang = sub.optString("lang").takeIf { it.isNotBlank() } ?: label
                if (!Settings.subtitleAllowed(lang)) continue
                subtitleCallback.invoke(newSubtitleFile(label, src))
            }

            linkCallback.invoke(
                newExtractorLink(name, name, stream, ExtractorLinkType.M3U8) {
                    this.referer = shellUrl
                    this.quality = Qualities.Unknown.value
                this.headers = uaHeaders
                }
            )
            found = true
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir kaynak yok. $REPORT_HINT")
        return true
    }

    /** Dizi sayfasındaki TVSeries düğümü @graph içinde gelir. */
    private fun tvSeriesSchema(doc: Document): JSONObject? {
        for (script in doc.select("script[type=application/ld+json]")) {
            val root = runCatching { JSONObject(script.data()) }.getOrNull() ?: continue
            val graph = root.optJSONArray("@graph") ?: JSONArray().put(root)
            for (i in 0 until graph.length()) {
                val node = graph.optJSONObject(i) ?: continue
                if (node.optString("@type") == "TVSeries") return node
            }
        }
        return null
    }

    /** JSON-LD dizileri: hem ["Dram"] hem [{"name":"..."}] biçimini okur. */
    private fun strings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }
                ?: array.optString(i).takeIf { it.isNotBlank() }
        }
    }

    companion object {
        /** Homepage rows in display order; all map to live listing pages. */
        val ROWS = listOf("Trend Diziler", "Son Eklenen Diziler", "Netflix", "Disney+", "HBO Max",
            "Aksiyon & Macera", "Bilim Kurgu & Fantastik", "Animasyon", "Dram", "Gizem", "Komedi", "Suç")

        private val EPISODE = Regex("""/sezon-(\d+)/bolum-(\d+)""")
        private const val CARD = "a[href*='/dizi/']"
        private const val PLAYER_HOST = "https://pilavyerplay.top"
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasty/bigbullapp-cs/issues"
    }
}
