package com.dizimodu

import com.bigbull.common.browserHeaders
import com.bigbull.common.extractVidmolyStreams
import com.bigbull.common.posterSrcLazy
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
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
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.newHomePageResponse
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * DiziModu — dizimodu.com (ToroFlix WordPress teması).
 * Bölüm sayfasındaki `li[data-typ=episode]` seçenekleri (key/trid) `POST /partgetirply.php`
 * ile oynatıcı adresine çevrilir; vidmoly/ddplayer iframe'leri loadExtractor'a verilir.
 */
class DiziModuProvider : MainAPI() {
    override var mainUrl = "https://dizimodu.com"
    override var name = "DiziModu"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries)

    private val uaHeaders get() = browserHeaders

    override val mainPage get() = ROWS.mapNotNull { row ->
        if (!Settings.rowEnabled(row)) return@mapNotNull null
        val url = when (row) {
            "Popüler" -> "$mainUrl/"
            "Son Eklenen" -> "$mainUrl/dizi/page/SAYFA/"
            "Türkçe Dublaj" -> "$mainUrl/?s=filter&dilnedir%5B%5D=Dublaj"
            "Türkçe Altyazı" -> "$mainUrl/?s=filter&dilnedir%5B%5D=Altyaz%C4%B1"
            "IMDb 8+" -> "$mainUrl/?s=filter&imdb%5B%5D=8"
            else -> return@mapNotNull null
        }
        MainPageData(row, url)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val paginated = request.data.contains("SAYFA")
        if (!paginated && page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val doc = app.get(request.data.replace("SAYFA", page.toString()), headers = uaHeaders).document
        return newHomePageResponse(
            request.name,
            cards(doc),
            hasNext = paginated && doc.selectFirst("link[rel=next]") != null,
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val doc = runCatching { app.get("$mainUrl/?s=$encoded", headers = uaHeaders).document }.getOrNull()
            ?: return emptyList()
        return cards(doc)
    }

    private fun cards(doc: Document): List<SearchResponse> =
        doc.select("article.TPost").mapNotNull { it.toSearchResult() }.distinctBy { it.url }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a[href*=\"/dizi/\"]") ?: return null
        val href = fixUrlNull(anchor.attr("href"))?.substringBefore('#') ?: return null
        val title = selectFirst("h2.Title")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: anchor.attr("title").trim().takeIf { it.isNotBlank() }
            ?: return null
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = fixUrlNull(
                (selectFirst(".Image img") ?: selectFirst("img[data-src]"))?.posterSrcLazy(),
            )
            this.year = selectFirst("span.Date")?.text()?.trim()?.toIntOrNull()
            this.score = Score.from10(IMDB.find(selectFirst("div.Qlty.Yr")?.text().orEmpty())?.value)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url, headers = uaHeaders).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi")
        val art = doc.selectFirst("article.TPost.A") ?: doc.selectFirst("article.TPost")
        val title = (doc.selectFirst("h1.Title")?.text() ?: doc.selectFirst("meta[property=og:title]")?.attr("content"))
            ?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: başlık okunamadı")

        val episodes = mutableListOf<Episode>()
        for (season in doc.select(".SeasonBx")) {
            val seasonNo = Regex("""\d+""")
                .find(season.selectFirst(".Top .Title span")?.text().orEmpty())?.value?.toIntOrNull()
            for (row in season.select(".TPTblCn tr")) {
                val anchor = row.selectFirst("td.MvTbTtl a") ?: row.selectFirst("a[href*=\"/bolum/\"]") ?: continue
                val href = fixUrlNull(anchor.attr("href")) ?: continue
                val parsed = EPISODE_PATH.find(href)
                val episodeNo = parsed?.groupValues?.get(2)?.toIntOrNull()
                    ?: row.selectFirst(".Num")?.text()?.trim()?.toIntOrNull()
                episodes += newEpisode(href) {
                    this.season = seasonNo ?: parsed?.groupValues?.get(1)?.toIntOrNull()
                    this.episode = episodeNo
                    this.name = anchor.text().trim().ifBlank { "Bölüm ${episodeNo ?: 0}" }
                }
            }
        }

        val tags = art?.select(".Genre a")?.map { it.text().trim() }?.filter { it.isNotBlank() }
        val actors = art?.select(".Cast a")?.map { it.text().trim() }?.filter { it.isNotBlank() }.orEmpty()
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
            this.posterUrl = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
            this.backgroundPosterUrl = fixUrlNull(art?.selectFirst("img.TPostBg")?.posterSrcLazy())
            this.plot = art?.select(".Description p")?.firstOrNull { it.className().isBlank() }?.text()?.trim()
            this.year = art?.selectFirst(".Info span.Date")?.text()?.trim()?.toIntOrNull()
            this.score = Score.from10(art?.selectFirst(".Info .Vote")?.text()?.trim())
            this.tags = tags?.takeIf { it.isNotEmpty() }
            if (actors.isNotEmpty()) addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val doc = runCatching { app.get(data, headers = uaHeaders).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: bölüm sayfası yüklenemedi")
        val linkCallback: (ExtractorLink) -> Unit = { if (Settings.qualityAllowed(it.quality)) callback(it) }
        val ajaxHeaders = uaHeaders + mapOf("x-requested-with" to "XMLHttpRequest")
        var found = false
        val seen = mutableSetOf<String>()

        for (option in doc.select("li[data-typ=episode]")) {
            val key = option.attr("data-key").trim().takeIf { it.isNotEmpty() } ?: continue
            val trid = option.attr("data-id").trim().takeIf { it.isNotEmpty() } ?: continue
            val embed = "$mainUrl/?trembed=$key&trid=$trid&trtype=2"
            val quality = getQualityFromName(option.selectFirst("p.AAIco-equalizer")?.text()?.trim())
            val body = runCatching {
                app.post(
                    "$mainUrl/partgetirply.php",
                    data = mapOf("data" to "$trid,$key,2"),
                    headers = ajaxHeaders,
                    referer = embed,
                ).text
            }.getOrNull().orEmpty()
            val rawPlayer = PLAYER_URL.find(body)?.value ?: continue
            val player = VIDMOLY_MIRROR.replace(rawPlayer, "vidmoly.net")
            if (!seen.add(player)) continue
            if (loadExtractor(player, embed, subtitleCallback, linkCallback)) {
                found = true
                continue
            }
            // Vidmoly tarzı gövdeler: önce "location.replace('...')" JS sıçraması, sonra
            // akış "sources: [{file: "..."}]" içinde düz metin gelir.
            val first = runCatching { app.get(player, headers = uaHeaders, referer = embed).text }
                .getOrNull() ?: continue
            val jsNext = JS_REDIRECT.find(first)?.groupValues?.get(1)
            val html = (if (jsNext != null) {
                runCatching { app.get(jsNext, headers = uaHeaders, referer = player).text }.getOrNull()
            } else {
                first
            })?.replace("\\/", "/") ?: continue
            val streams = extractVidmolyStreams(html).ifEmpty {
                listOfNotNull(PLAYER_FILE.find(html)?.groupValues?.get(1))
            }
            if (streams.isEmpty()) continue
            val label = option.selectFirst("p.AAIco-dns")?.text()?.trim().orEmpty()
            streams.distinct().forEach { stream ->
                linkCallback.invoke(
                    newExtractorLink(
                        name,
                        "$name $label".trim(),
                        stream,
                        if (stream.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                    ) {
                        this.referer = if (player.contains("vidmoly.", ignoreCase = true)) "https://vidmoly.net/" else embed
                        this.quality = quality
                    },
                )
            }
            found = true
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir kaynak yok")
        return true
    }

    companion object {
        val ROWS = listOf("Popüler", "Son Eklenen", "Türkçe Dublaj", "Türkçe Altyazı", "IMDb 8+")

        private val IMDB = Regex("""\d+(?:[.,]\d+)?""")
        private val EPISODE_PATH = Regex("""sezon-(\d+)-bolum-(\d+)""")
        private val PLAYER_URL = Regex("""https?://[^"'\s<>]+""")
        private val PLAYER_FILE = Regex("""file\s*:\s*["'](https?://[^"']+)["']""")
        private val JS_REDIRECT = Regex("""location\.replace\('([^']+)'\)""")
        private val VIDMOLY_MIRROR = Regex("""vidmoly\.(?:to|me|biz)""", RegexOption.IGNORE_CASE)
    }
}
