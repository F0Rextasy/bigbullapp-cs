package com.animetr

import com.bigbull.common.browserHeaders
import com.bigbull.common.posterSrcLazy
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
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * AnimeTR — animetr.co (anime + donghua arşivi).
 *
 * Liste/arama `/seriler?title=` kartları; detayda bölümler `[data-ep-items] a.ep-item__link`;
 * bölüm sayfasının `#epspage-data` JSON'u fansub ve player embed listesini verir.
 */
class AnimeTRProvider : MainAPI() {
    override var mainUrl = "https://animetr.co"
    override var name = "AnimeTR"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Anime)
    override val hasMainPage = true

    override val mainPage get() = ROWS.mapNotNull { row ->
        if (!Settings.rowEnabled(row)) return@mapNotNull null
        MainPageData(row, "$mainUrl/seriler" + when (row) {
            "Popüler" -> "?sortOrder=views"
            "Tamamlananlar" -> "?status=tamam"
            "Puanı Yüksek" -> "?sortOrder=score"
            else -> ""
        })
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sep = if (request.data.contains('?')) "&" else "?"
        val doc = app.get("${request.data}${sep}page=$page", headers = browserHeaders).document
        val items = doc.select("a.anime-card").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items, hasNext = page < lastPage(doc, page))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/seriler?title=${URLEncoder.encode(query.trim(), "UTF-8")}"
        return app.get(url, headers = browserHeaders).document
            .select("a.anime-card").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = browserHeaders).document
        val title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
            .ifBlank { throw ErrorLoadingException("$name: başlık okunamadı. $REPORT_HINT") }
        val ld = tvSeriesLd(doc)
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrlNull(it) }
        val plot = doc.selectFirst("p.text-body.text-break")?.text()?.trim()?.ifBlank { null }
            ?: ld?.optString("description")?.trim()?.ifBlank { null }
        val year = Regex("""(\d{4})""").find(ld?.optString("startDate").orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()
        val score = doc.selectFirst("""[aria-label^="MAL puanı"]""")?.attr("aria-label")
            ?.let { Regex("""MAL puanı\s*([\d.]+)""").find(it)?.groupValues?.get(1) }
        val tags = ld?.optJSONArray("genre")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).trim().ifBlank { null } }
        }
        val season = Regex("""-(\d+)-sezon(?:[/?#]|$)""").find(url)?.groupValues?.get(1)?.toIntOrNull()
        val episodes = doc.select("[data-ep-items] a.ep-item__link").mapNotNull { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val epName = a.selectFirst(".ep-item__title")?.text()?.trim()?.ifBlank { null }
            newEpisode(href) {
                this.name = epName
                this.season = season
                this.episode = Regex("""/bolum-(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
            }
        }.distinctBy { it.data }
        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.score = Score.from10(score)
            this.tags = tags?.takeIf { it.isNotEmpty() }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val body = app.get(data, headers = browserHeaders).text
        val raw = Regex(
            """<script type="application/json" id="epspage-data">(.*?)</script>""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(body)?.groupValues?.get(1)
        val root = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: throw ErrorLoadingException("$name: oynatıcı verisi okunamadı. $REPORT_HINT")
        val subtitleFilter: (SubtitleFile) -> Unit = { if (Settings.subtitleAllowed(it.lang)) subtitleCallback(it) }
        val linkCallback: (ExtractorLink) -> Unit = { if (Settings.qualityAllowed(it.quality)) callback(it) }
        val fanSubs = root.optJSONArray("fansubs") ?: JSONArray()
        var found = false
        val seen = mutableSetOf<String>()
        for (i in 0 until fanSubs.length()) {
            val players = fanSubs.optJSONObject(i)?.optJSONArray("players") ?: continue
            for (j in 0 until players.length()) {
                if (seen.size >= MAX_PLAYERS) break
                val player = players.optJSONObject(j) ?: continue
                if (!player.optBoolean("embeddable", true)) continue
                val embed = player.optString("embed_url").ifBlank { player.optString("original_url") }.trim()
                if (!embed.startsWith("http") || !seen.add(embed)) continue
                val label = player.optString("provider").trim().ifBlank { "Kaynak" }
                // Ölü bir mirror diğerlerini düşürmesin.
                val ok = runCatching {
                    when {
                        embed.contains("drive.google.com/file/d/") -> loadGDrive(embed, linkCallback)
                        embed.contains("ok.ru/videoembed") || embed.contains("odnoklassniki.ru/videoembed") ->
                            loadOkRu(embed, label, linkCallback)
                        else -> loadExtractor(embed, "$mainUrl/", subtitleFilter, linkCallback)
                    }
                }.getOrDefault(false)
                if (ok) found = true
            }
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir kaynak yok. $REPORT_HINT")
        return true
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val title = selectFirst(".seri-title")?.text()?.trim().orEmpty().ifBlank { attr("aria-label").trim() }
        if (title.isBlank()) return null
        val poster = selectFirst("img")?.posterSrcLazy()?.let { fixUrlNull(it) }
        val score = Score.from10(selectFirst(".anime-card__score")?.text()?.trim())
        return newTvSeriesSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
            this.score = score
        }
    }

    private fun tvSeriesLd(doc: Document): JSONObject? {
        for (script in doc.select("""script[type="application/ld+json"]""")) {
            val graph = runCatching { JSONObject(script.data()).optJSONArray("@graph") }.getOrNull() ?: continue
            for (i in 0 until graph.length()) {
                val node = graph.optJSONObject(i) ?: continue
                if (node.optString("@type") == "TVSeries") return node
            }
        }
        return null
    }

    private fun lastPage(doc: Document, current: Int): Int =
        doc.select("a.page-link").mapNotNull {
            Regex("""[?&]page=(\d+)""").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull() ?: current

    /** ok.ru embed'i JS kabuğu; aynı kimliğe `videoPlayerMetadata` imzalı HLS master'ı JSON olarak verir. */
    private suspend fun loadOkRu(embed: String, label: String, callback: (ExtractorLink) -> Unit): Boolean {
        val id = Regex("""videoembed/(\d+)""").find(embed)?.groupValues?.get(1) ?: return false
        val body = runCatching {
            app.post(
                "https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$id",
                data = mapOf("cmd" to "videoPlayerMetadata", "mid" to id),
                headers = mapOf("user-agent" to BROWSER_UA),
                referer = "https://ok.ru/videoembed/$id",
            ).text
        }.getOrNull() ?: return false
        val stream = runCatching { JSONObject(body).optString("ondemandHls") }.getOrNull().orEmpty()
        if (!stream.startsWith("http")) return false
        callback(
            newExtractorLink(name, label, stream, ExtractorLinkType.M3U8) {
                this.referer = "https://ok.ru/"
            }
        )
        return true
    }

    /** Drive'ın `/preview` iframe'i JS-only; indirme ucu aynı dosyayı mp4 olarak verir. */
    private suspend fun loadGDrive(embed: String, callback: (ExtractorLink) -> Unit): Boolean {
        val id = Regex("""/file/d/([^/]+)""").find(embed)?.groupValues?.get(1) ?: return false
        val url = "https://drive.usercontent.google.com/download?id=$id&export=download&confirm=t"
        val probe = runCatching {
            app.get(url, headers = mapOf("Range" to "bytes=0-0", "user-agent" to BROWSER_UA))
        }.getOrNull() ?: return false
        val type = probe.headers["content-type"] ?: probe.headers["Content-Type"] ?: ""
        if (probe.code !in 200..299 || type.startsWith("text/")) return false
        callback(
            newExtractorLink(name, "Google Drive", url, ExtractorLinkType.VIDEO) {
                this.referer = embed
            }
        )
        return true
    }

    companion object {
        val ROWS = listOf("Son Eklenenler", "Popüler", "Tamamlananlar", "Puanı Yüksek")
        private const val MAX_PLAYERS = 10
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/130.0.0.0 Safari/537.36"
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}
