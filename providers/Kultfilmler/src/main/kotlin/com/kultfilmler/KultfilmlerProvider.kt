package com.kultfilmler

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONArray
import org.json.JSONObject

/** Kült Filmler — kultfilmler.net (WordPress + vidpapi.xyz FirePlayer embed) */
class KultfilmlerProvider : MainAPI() {
    override var mainUrl = "https://kultfilmler.net"
    override var name = "Kült Filmler"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/film-arsivi/?sayfa=SAYFA" to "Son Eklenenler",
        "$mainUrl/film-arsivi/?sort=imdb&sayfa=SAYFA" to "IMDb Puanı",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data.replace("SAYFA", page.toString())).document
        val items = doc.select("a.mcard").mapNotNull { it.toSearchResult() }
        // Archive pager ends with the next-page arrow (svg) on every page but the last.
        return newHomePageResponse(
            request.name,
            items,
            hasNext = doc.selectFirst("nav.pager a:last-child svg") != null,
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val title = selectFirst("h3")?.text()?.trim() ?: return null
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = fixUrlNull(selectFirst("img")?.attr("src"))
            this.year = selectFirst("span.pyear")?.text()?.trim()?.toIntOrNull()
            this.score = Score.from10(selectFirst("span.mscore b")?.text()?.trim())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> =
        search(query, 1)?.items ?: emptyList()

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/?s=$encoded&paged=$page").document
        val items = doc.select("a.mcard").mapNotNull { it.toSearchResult() }
        return newSearchResponseList(items, hasNext = doc.selectFirst("nav.pager a.next") != null)
    }

    /** Header live search: /wp-admin/admin-ajax.php?action=kf_live_search returns {title,url,poster,year,type}. */
    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val data = runCatching {
            JSONObject(app.get("$mainUrl/wp-admin/admin-ajax.php?action=kf_live_search&q=$encoded").text)
        }.getOrNull()?.optJSONArray("data") ?: return null
        return (0 until data.length()).mapNotNull { i ->
            val item = data.optJSONObject(i) ?: return@mapNotNull null
            if (item.optString("type") != "Film") return@mapNotNull null
            newMovieSearchResponse(item.optString("title"), item.optString("url"), TvType.Movie) {
                this.posterUrl = fixUrlNull(item.optString("poster"))
                this.year = item.optString("year").toIntOrNull()
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val schema = movieSchema(doc)
        val title = doc.selectFirst("h1.vtitle")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: schema?.optString("name")?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val duration = doc.select("div.irow").firstOrNull {
            it.selectFirst("div.ilabel")?.text()?.startsWith("Süre") == true
        }?.selectFirst("div.ivalue")?.text()?.takeWhile { c -> c.isDigit() }?.toIntOrNull()
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = schema?.let { jsonString(it.opt("image")) }?.let { fixUrlNull(it) }
                ?: fixUrlNull(doc.selectFirst("div.poster img")?.attr("src"))
            this.plot = doc.selectFirst("p#desc")?.text()?.trim()
            this.year = schema?.optString("datePublished")?.take(4)?.toIntOrNull()
            this.tags = schema?.optJSONArray("genre")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
            }
            this.score = Score.from10(doc.selectFirst("div.imdb .score")?.text()?.trim())
            this.duration = duration
            addActors(doc.select("div.cast a.cmember h5").map { it.text().trim() })
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val linkCallback: (ExtractorLink) -> Unit = { if (Settings.qualityAllowed(it.quality)) callback(it) }
        val subtitleFilter: (com.lagradost.cloudstream3.SubtitleFile) -> Unit =
            { if (Settings.subtitleAllowed(it.lang)) subtitleCallback(it) }
        val doc = runCatching { app.get(data).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: kaynak bulunamadı. $REPORT_HINT")

        // #kf-srcdata lists every mirror (altyazılı + dublaj); the rendered iframe only shows the first.
        val sources = doc.selectFirst("script#kf-srcdata")?.data()?.let { raw ->
            runCatching { JSONArray(raw) }.getOrNull()
        }?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val src = Regex("""src=["']([^"']+)""").find(item.optString("html"))
                    ?.groupValues?.get(1) ?: return@mapNotNull null
                val embedUrl = fixUrlNull(src) ?: return@mapNotNull null
                embedUrl to item.optString("langLabel")
            }
        }.orEmpty().ifEmpty {
            listOfNotNull(fixUrlNull(doc.selectFirst("iframe[src*=vidpapi]")?.attr("src"))?.let { it to "" })
        }
        if (sources.isEmpty()) throw ErrorLoadingException("$name: kaynak bulunamadı. $REPORT_HINT")

        var found = false
        val seenLinks = HashSet<String>()
        for ((embed, langLabel) in sources.distinctBy { it.first }) {
            val hash = embed.substringAfterLast('/').substringBefore('?')
            if (hash.isBlank()) continue

            // https://vidpapi.xyz/player/assets/scripts.php -> POST index.php?do=getVideo {hash, r}
            val body = runCatching {
                app.post(
                    "https://vidpapi.xyz/player/index.php?data=$hash&do=getVideo",
                    data = mapOf("hash" to hash, "r" to data),
                    headers = mapOf(
                        "Referer" to data,
                        "Origin" to "https://vidpapi.xyz",
                        "X-Requested-With" to "XMLHttpRequest",
                    ),
                ).text
            }.getOrNull() ?: continue
            val link = runCatching { JSONObject(body).optString("securedLink").takeIf { it.isNotBlank() } }
                .getOrNull() ?: continue
            if (!seenLinks.add(link)) continue

            linkCallback.invoke(
                newExtractorLink(
                    name,
                    listOf(name, langLabel).filter { it.isNotBlank() }.joinToString(" "),
                    link,
                    ExtractorLinkType.M3U8,
                ) {
                    this.referer = embed
                    this.quality = Qualities.Unknown.value
                }
            )
            found = true
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir akış yok. $REPORT_HINT")
        return true
    }

    /** Rank Math JSON-LD keeps the Movie node inside @graph. */
    private fun movieSchema(doc: Document): JSONObject? {
        doc.select("script[type=application/ld+json]").forEach { script ->
            val json = runCatching { JSONObject(script.data()) }.getOrNull() ?: return@forEach
            val graph = json.optJSONArray("@graph")
            if (graph == null) {
                if (json.optString("@type").equals("Movie", true)) return json
            } else {
                for (i in 0 until graph.length()) {
                    val node = graph.optJSONObject(i) ?: continue
                    if (node.optString("@type").equals("Movie", true)) return node
                }
            }
        }
        return null
    }

    private fun jsonString(value: Any?): String? = when (value) {
        is String -> value
        is JSONArray -> value.opt(0)?.let { jsonString(it) }
        is JSONObject -> value.optString("url").ifBlank { value.optString("contentUrl") }
            .takeIf { it.isNotBlank() }
        else -> null
    }

    companion object {
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}
