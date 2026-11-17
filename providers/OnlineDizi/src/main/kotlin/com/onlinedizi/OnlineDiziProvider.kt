package com.onlinedizi

import com.bigbull.common.browserHeaders
import com.bigbull.common.posterSrcLazy
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
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
import org.json.JSONObject
import org.jsoup.nodes.Element

/**
 * OnlineDizi — onlinedizi.sbs (WordPress/tema: dizi, film ve anime kataloğu).
 * Dizi sayfası `/diziler/<slug>/`; bölümler kök slug'lı (`/<slug>-<n>-sezon-<m>-bolum/`).
 * Bölüm sayfası `/players/n/<b64>` iframe'ini basar, o sayfa da `/players/p/<b64>` adresini
 * verir; `/players/p/` vidmoly embed'ine gider, embed `sources: [{ file: '<master.m3u8>' }]`
 * ve `label: "720p HD"` taşır.
 */
class OnlineDiziProvider : MainAPI() {
    override var mainUrl = "https://onlinedizi.sbs"
    override var name = "OnlineDizi"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries)

    private val uaHeaders get() = browserHeaders

    override val mainPage get() = ROWS.mapNotNull { row ->
        if (!Settings.rowEnabled(row)) return@mapNotNull null
        MainPageData(row, row)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(
            if (page <= 1) "$mainUrl/" else "$mainUrl/sayfa/$page/",
            headers = uaHeaders,
        ).document
        val block = doc.select("div.content-block").firstOrNull {
            it.selectFirst("h2.section-title")?.text()?.trim() == request.data
        }
        val items = block?.select("li a[href]")?.mapNotNull { it.toSearchResult() }.orEmpty()
        val hasNext = doc.selectFirst("div.v-pagination a[aria-label=Next]") != null
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        if (!SERIES_PATH.containsMatchIn(href)) return null
        val title = selectFirst("div.post-title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: attr("title").trim().removeSuffix(" İzle").trim().takeIf { it.isNotEmpty() }
            ?: return null
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = selectFirst("img")?.posterSrcLazy()?.let { fixUrlNull(it) }
            this.posterHeaders = uaHeaders
        }
    }

    /** Sitedeki canlı arama (`?s=` yalnızca ana sayfayı döndürüyor); `ajaxy_sf` JSON'u kullanılır. */
    override suspend fun search(query: String): List<SearchResponse> {
        val raw = runCatching {
            app.post(
                "$mainUrl/ajax_submit.php",
                data = mapOf("action" to "ajaxy_sf", "sf_value" to query, "search" to ""),
                headers = uaHeaders + mapOf(
                    "x-requested-with" to "XMLHttpRequest",
                    "referer" to "$mainUrl/",
                ),
            ).text
        }.getOrNull() ?: return emptyList()
        val groups = runCatching { JSONObject(raw).optJSONArray("diziler") }.getOrNull()
            ?: return emptyList()
        val out = mutableListOf<SearchResponse>()
        for (g in 0 until groups.length()) {
            val all = groups.optJSONObject(g)?.optJSONArray("all") ?: continue
            for (i in 0 until all.length()) {
                val item = all.optJSONObject(i) ?: continue
                val href = fixUrlNull(item.optString("post_link")) ?: continue
                if (!SERIES_PATH.containsMatchIn(href)) continue
                val title = item.optString("post_title").trim().takeIf { it.isNotEmpty() } ?: continue
                out += newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = fixUrlNull(item.optString("post_image").trim())
                    this.posterHeaders = uaHeaders
                }
            }
        }
        return out.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = uaHeaders).document
        val title = doc.selectFirst("h1.series-name a")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("$name: dizi adı okunamadı. $REPORT_HINT")
        val casts = doc.select("div.movie-casts div.cast")
        fun cast(label: String) =
            casts.firstOrNull { it.selectFirst("strong")?.text()?.trim() == label }

        val episodes = doc.select("div.series-list-area li a[href]").mapNotNull { anchor ->
            val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            val seasons = SEASON_EP.find(href)
            val season = seasons?.groupValues?.get(1)?.toIntOrNull()
            val episode = (seasons?.groupValues?.get(2) ?: EP_ONLY.find(href)?.groupValues?.get(1))
                ?.toIntOrNull() ?: return@mapNotNull null
            newEpisode(href) {
                this.name = anchor.text().trim().ifBlank { "S${season ?: 1} B$episode" }
                this.season = season
                this.episode = episode
            }
        }.distinctBy { it.data }.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
        if (episodes.isEmpty()) throw ErrorLoadingException("$name: bölüm listesi boş. $REPORT_HINT")

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = fixUrlNull(
                doc.selectFirst("img.series-poster")?.posterSrcLazy()
                    ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            )
            this.plot = doc.selectFirst("div.about-series")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            this.year = cast("Tarih:")?.selectFirst("a")?.text()?.trim()?.toIntOrNull()
            this.tags = cast("Tür:")?.select("span[itemprop=genre]")
                ?.map { it.text().trim() }?.filter { it.isNotEmpty() }
            addActors(
                cast("Oyuncular:")?.select("span[itemprop=actor]")
                    ?.map { it.text().trim() }?.filter { it.isNotEmpty() }.orEmpty()
            )
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val doc = runCatching { app.get(data, headers = uaHeaders).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: bölüm sayfası açılamadı. $REPORT_HINT")
        val iframe = doc.selectFirst("iframe[src*=players]")?.attr("src")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("$name: oynatıcı bulunamadı. $REPORT_HINT")

        val gate = runCatching {
            app.get(fixUrl(iframe), referer = data, headers = uaHeaders).text
        }.getOrNull() ?: throw ErrorLoadingException("$name: oynatıcı açılamadı. $REPORT_HINT")
        val inner = IFSRC.find(gate)?.groupValues?.get(1) ?: iframe
        val embedResponse = runCatching {
            app.get(fixUrl(inner), referer = data, headers = uaHeaders)
        }.getOrNull() ?: throw ErrorLoadingException("$name: oynatıcı açılamadı. $REPORT_HINT")
        val embed = embedResponse.text

        var found = false
        val streams = M3U8.findAll(embed).map { it.groupValues[1] }.toList()
        if (streams.isEmpty()) {
            // vidmoly yerine başka bir gömme gelirse core extractor'a bırakılır.
            if (loadExtractor(embedResponse.url, data, subtitleCallback, callback)) found = true
        } else {
            val label = LABEL.find(embed)?.groupValues?.get(1)
            val quality = getQualityFromName(DIGITS.find(label.orEmpty())?.value)
            streams.forEach { stream ->
                if (!Settings.qualityAllowed(quality)) return@forEach
                callback(newExtractorLink(name, name, stream, ExtractorLinkType.M3U8) {
                    this.referer = mainUrl
                    this.quality = quality
                })
                found = true
            }
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir akış yok. $REPORT_HINT")
        return true
    }

    companion object {
        /** Ana sayfa/sayfalama (`/sayfa/<n>/`) satırları; film ve bölüm satırları TvSeries'e girmez. */
        val ROWS = listOf("Son Eklenen Diziler", "Son Eklenen Kore Dizileri", "Son Eklenen Animeler")

        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"

        private val SERIES_PATH = Regex("""/diziler/[^/]+/?$""")
        private val SEASON_EP = Regex("""-(\d+)-sezon-(\d+)-bolum/?$""")
        private val EP_ONLY = Regex("""-(\d+)-bolum/?$""")
        private val IFSRC = Regex("var ifsrc = \"([^\"]+)\"")
        private val M3U8 = Regex("""sources:\s*\[\s*\{\s*file:\s*'([^']+)'""")
        private val LABEL = Regex("label:\\s*\"([^\"]+)\"")
        private val DIGITS = Regex("""\d{3,4}""")
    }
}
