package com.diziyou

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ActorRole
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
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
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/** DiziYou — diziyou.one (CSS: storage.diziyou.one, m3u8 + VTT) */
class DiziYouProvider : MainAPI() {
    override var mainUrl = "https://www.diziyou.one"
    override var name = "DiziYou"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage get() = ROWS.mapNotNull { name ->
        if (!Settings.rowEnabled(name)) return@mapNotNull null
        when (name) {
            "Son Eklenen Diziler" -> MainPageData(name, "$mainUrl/dizi-arsivi/?filtrele=tarih&sirala=DESC")
            "IMDb 7+" -> MainPageData(name, "$mainUrl/dizi-arsivi/?filtrele=imdb&sirala=DESC&yil=&imdb=7")
            "Dizi Arşivi" -> MainPageData(name, "$mainUrl/dizi-arsivi/?filtrele=alfabetik&sirala=ASC")
            "Aksiyon" -> MainPageData(name, "$mainUrl/dizi-arsivi/?tur=Aksiyon")
            "Dram" -> MainPageData(name, "$mainUrl/dizi-arsivi/?tur=Dram")
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            request.data
        } else {
            val query = request.data.substringAfter("?", "")
            val path = request.data.substringBefore("?").trimEnd('/')
            if (query.isEmpty()) "$path/page/$page/" else "$path/page/$page/?$query"
        }
        val doc = app.get(url).document
        val items = doc.select("#list-series, .single-item").mapNotNull { card ->
            val anchor = card.selectFirst("#categorytitle a") ?: card.selectFirst(".cat-img a") ?: return@mapNotNull null
            val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            newTvSeriesSearchResponse(anchor.text().trim(), href, TvType.TvSeries) {
                this.posterUrl = fixUrlNull(card.selectFirst("img")?.attr("src"))
                this.score = com.lagradost.cloudstream3.Score.from10(
                    card.selectFirst("#imdbp")?.text()?.trim()?.trim('(', ')')
                )
            }
        }.distinctBy { it.url }
        val hasNext = doc.selectFirst(".paginate-links a.next") != null
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("#list-series").mapNotNull { card ->
            val anchor = card.selectFirst("#categorytitle a") ?: return@mapNotNull null
            val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            newTvSeriesSearchResponse(anchor.text().trim(), href, TvType.TvSeries) {
                this.posterUrl = fixUrlNull(card.selectFirst("img")?.attr("src"))
                this.score = com.lagradost.cloudstream3.Score.from10(
                    card.selectFirst("#imdbp")?.text()?.trim()?.trim('(', ')')
                )
            }
        }.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> =
        app.post(
            "$mainUrl/wp-admin/admin-ajax.php",
            data = mapOf("action" to "data_fetch", "keyword" to query),
        ).document.select("#searchelement").mapNotNull { block ->
            val anchor = block.select("a[href]").lastOrNull() ?: return@mapNotNull null
            val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            newTvSeriesSearchResponse(anchor.text().trim(), href, TvType.TvSeries) {
                this.posterUrl = fixUrlNull(block.selectFirst(".search-cat-img img")?.attr("src"))
            }
        }.distinctBy { it.url }

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val ld = doc.selectFirst("script[type=application/ld+json]")?.data().orEmpty()
        val title = doc.selectFirst("h1.title-border")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: Regex(""""name"\s*:\s*"([^"]+)"""").find(ld)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")

        val episodes = doc.select("a[href*='-sezon'][href*='-bolum']").mapNotNull { anchor ->
            val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            val label = anchor.selectFirst("div.baslik")?.text()?.replace(Regex("""\s*\(.*\)$"""), "")?.trim()
                ?: anchor.attr("title").trim()
            newEpisode(href) {
                this.name = label
                this.season = Regex("""(\d+)\.\s*Sezon""").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                this.episode = Regex("""(\d+)\.\s*Bölüm""").find(label)?.groupValues?.get(1)?.toIntOrNull()
            }
        }.distinctBy { it.data }
        val cast = doc.selectFirst("#icerikcat2")?.let { meta ->
            val info = meta.clone().apply { select(".genres, .modaltrailer, script").remove() }
            info.text().substringAfter("Oyuncular:", "").trim()
        }?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = fixUrlNull(Regex(""""image"\s*:\s*"([^"]+)"""").find(ld)?.groupValues?.get(1))
            this.plot = Regex(""""description"\s*:\s*"([^"]+)"""").find(ld)?.groupValues?.get(1)
            this.year = Regex(""""startDate"\s*:\s*"(\d{4})"""").find(ld)?.groupValues?.get(1)?.toIntOrNull()
            this.score = com.lagradost.cloudstream3.Score.from10(
                Regex(""""ratingValue"\s*:\s*"?([\d.]+)"""").find(ld)?.groupValues?.get(1)
            )
            if (cast.isNotEmpty()) this.actors = cast.map { ActorData(Actor(it, null), ActorRole.Main) }
            doc.selectFirst("iframe.trailer-video[src]")?.attr("src")?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { addTrailer(it) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val seenLinks = mutableSetOf<String>()
        val seenSubs = mutableSetOf<String>()
        val linkCallback: (ExtractorLink) -> Unit = {
            if (seenLinks.add(it.url) && Settings.qualityAllowed(it.quality)) callback(it)
        }
        val subtitleFilter: (SubtitleFile) -> Unit = {
            if (seenSubs.add(it.url) && Settings.subtitleAllowed(it.lang)) subtitleCallback(it)
        }
        val doc = runCatching { app.get(data).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: kaynak bulunamadı. $REPORT_HINT")
        val playerUrl = fixUrlNull(doc.selectFirst("iframe#diziyouPlayer")?.attr("src"))
            ?: throw ErrorLoadingException("$name: kaynak bulunamadı. $REPORT_HINT")

        val player = runCatching { app.get(playerUrl, referer = data).text }
            .getOrNull() ?: throw ErrorLoadingException("$name: kaynak bulunamadı. $REPORT_HINT")
        val stream = Regex("""<source[^>]+src="([^"]+\.m3u8[^"]*)"""").find(player)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("$name: oynatılabilir akış yok. $REPORT_HINT")

        Regex("""<track[^>]+src="([^"]+)".*?label="([^"]+)"""")
            .findAll(player)
            .forEach { match ->
                subtitleFilter.invoke(SubtitleFile(match.groupValues[2], fixUrl(match.groupValues[1])))
            }

        linkCallback.invoke(
            newExtractorLink(name, name, stream, ExtractorLinkType.M3U8) {
                this.referer = fixUrl(playerUrl)
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    companion object {
        /** Homepage row labels, in display order; each maps to a live archive filter above. */
        val ROWS = listOf("Son Eklenen Diziler", "IMDb 7+", "Dizi Arşivi", "Aksiyon", "Dram")
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}
