package com.cizgimax

import android.util.Base64
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
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
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.CancellationException

/**
 * ÇizgiMax — cizgimax.online
 *
 * Catalog is plain HTML: `div.film-item` cards, series under `/diziler/`, episodes as
 * `<slug>-<s>-sezon-<e>-bolum-izle/`. Players are hidden in a base64 JSON blob
 * (`var servers = JSON.parse(atob("…"))`) with three shapes:
 *  - `streamUrl` — site-owned proxy `/api/stream/<provider>/?t=<signed>`; HLS for most
 *    providers, MP4 for `mailru`, and 403 without the episode page as Referer.
 *  - `src` — `/oynat/?t=<signed>` answering 302 to the real host (sibnet, vidmoly,
 *    uqload, …), which the core extractors understand.
 *  - `resolveUrl` — `dzen` rows; the site itself resolves these to a tau-video key.
 */
private fun Element.posterSrcLazy(): String? =
    attr("data-src").takeIf { it.isNotBlank() } ?: attr("src")

class CizgimaxProvider : MainAPI() {
    override var mainUrl = "https://cizgimax.online"
    private val uaHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9",
    )
    override var name = "ÇizgiMax"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage get() = ROWS.mapNotNull { name ->
        if (!Settings.rowEnabled(name)) return@mapNotNull null
        when (name) {
            "Son Eklenenler" -> MainPageData(name, "$mainUrl/")
            "Anime" -> MainPageData(name, "$mainUrl/diziler/anime/")
            "Popüler" -> MainPageData(name, "$mainUrl/arsiv/?sort=populer&donem=daily")
            else -> null
        }
    }

    private data class RawEpisode(
        val url: String,
        val season: Int?,
        val episode: Int?,
        val title: String,
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data, headers = uaHeaders).document
        val items = doc.select("div.film-item").mapNotNull { seriesOf(it) }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        search(query, 1)?.items ?: emptyList()

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val doc = app.get("$mainUrl/ara/?q=${URLEncoder.encode(query, "UTF-8")}&page=$page", headers = uaHeaders).document
        val items = doc.select("div.film-item").mapNotNull { seriesOf(it) }.distinctBy { it.url }
        // An active "Sonraki" is an <a>; the last page renders it as a disabled <span>.
        val hasNext = doc.selectFirst("div.listing-pager a.lp-btn:contains(Sonraki)") != null
        return newSearchResponseList(items, hasNext)
    }

    /** Header autocomplete: `/api/search/suggest/` returns five hits without a full page load. */
    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        val body = try {
            JSONObject(app.get("$mainUrl/api/search/suggest/?q=${URLEncoder.encode(query, "UTF-8")}", headers = uaHeaders).text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        val animes = body.optJSONArray("animes") ?: return null
        return (0 until animes.length()).mapNotNull { index ->
            val item = animes.optJSONObject(index) ?: return@mapNotNull null
            val href = fixUrlNull(item.optString("url")) ?: return@mapNotNull null
            newTvSeriesSearchResponse(item.optString("name"), href, TvType.TvSeries) {
                posterUrl = fixUrlNull(item.optString("poster"))
            }
        }
    }

    private fun seriesOf(item: Element): SearchResponse? {
        val link = item.selectFirst("a.film-name[href*='/diziler/']") ?: return null
        val url = fixUrlNull(link.attr("href")) ?: return null
        val poster = fixUrlNull(item.selectFirst("img")?.attr("src"))
        return newTvSeriesSearchResponse(link.text().trim(), url, TvType.TvSeries) {
            posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url, headers = uaHeaders).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val title = doc.selectFirst("h1 a.anime-title-link")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")

        val raw = doc.select("a.ep-num-btn[href]").mapNotNull { anchor ->
            val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            RawEpisode(
                url = href,
                season = SeasonRegex.find(href)?.groupValues?.get(1)?.toIntOrNull(),
                episode = anchor.selectFirst("span.ep-num-label")?.text()?.trim()?.toIntOrNull(),
                title = anchor.attr("title").trim(),
            )
        }.distinctBy { it.url }
        if (raw.isEmpty()) throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")

        val multiSeason = raw.mapNotNull { it.season }.distinct().size > 1
        val episodes = raw.map { item ->
            newEpisode(item.url) {
                season = item.season
                episode = item.episode
                name = buildString {
                    if (multiSeason) append("S${item.season ?: 1} ")
                    item.episode?.let { append("B$it") }
                    if (item.title.isNotBlank() && item.title != "Bölüm ${item.episode}") {
                        if (isNotEmpty()) append(" — ")
                        append(item.title)
                    }
                }
            }
        }

        val infoRows = doc.select("ul.anime-meta-col--info li")
        val year = infoRows
            .firstOrNull { it.selectFirst("span")?.text()?.startsWith("Yayın Yılı") == true }
            ?.selectFirst("strong")?.text()?.trim()?.toIntOrNull()
        val tags = doc.select("li.meta-genres a").map { it.text().trim() }
        val score = doc.selectFirst("div.anime-rating-box span.ep-score-value")
            ?.text()?.trim()?.replace(',', '.')?.toDoubleOrNull()
        val recommendations = doc.select("div.similar-box div.film-item")
            .mapNotNull { seriesOf(it) }.distinctBy { it.url }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = fixUrlNull(doc.selectFirst("div.anime-poster img")?.posterSrcLazy())
            plot = doc.selectFirst("p.anime-desc")?.text()?.trim()
            this.year = year
            this.tags = tags
            if (score != null && score > 0) this.score = Score.from10(score)
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = runCatching { app.get(data, headers = uaHeaders).text }
            .getOrNull() ?: throw ErrorLoadingException("$name: kaynak bulunamadı. $REPORT_HINT")
        val encoded = ServersRegex.find(html)?.groupValues?.get(1) ?: ""
        val servers = runCatching { JSONArray(decodeBlob(encoded)) }.getOrNull()
            ?: throw ErrorLoadingException("$name: kaynak bulunamadı. $REPORT_HINT")
        val linkCallback: (ExtractorLink) -> Unit = { if (Settings.qualityAllowed(it.quality)) callback(it) }
        val subtitleFilter: (SubtitleFile) -> Unit = { if (Settings.subtitleAllowed(it.lang)) subtitleCallback(it) }
        var emitted = 0
        val seen = mutableSetOf<String>()
        val emit: (ExtractorLink) -> Unit = {
            if (seen.add(it.url)) {
                emitted++
                linkCallback(it)
            }
        }

        for (index in 0 until servers.length()) {
            val server = servers.optJSONObject(index) ?: continue
            val label = server.optString("label").ifBlank { server.optString("type") }
            val streamUrl = server.optString("streamUrl")
            val src = server.optString("src")
            val resolveUrl = server.optString("resolveUrl")
            val youtubeId = server.optString("ytId")

            // A dead embed must not take the live ones down with it.
            try {
                when {
                    youtubeId.isNotBlank() -> loadExtractor(
                        "https://www.youtube.com/watch?v=$youtubeId", data, subtitleFilter, emit
                    )

                    streamUrl.isNotBlank() -> {
                        val isMp4 = server.optString("type") == "mailru" || streamUrl.contains("mp4=1")
                        val qualitiesUrl = server.optString("qualitiesUrl")
                        // mailru's streamUrl alone answers only the default rendition; the
                        // sibling qualitiesUrl lists every signed one (verified 1080p + 360p).
                        val renditions = if (isMp4 && qualitiesUrl.isNotBlank()) {
                            runCatching {
                                JSONObject(app.get(fixUrl(qualitiesUrl), referer = data, headers = uaHeaders).text)
                                    .optJSONArray("qualities")
                            }.getOrNull()
                        } else {
                            null
                        }
                        if (renditions != null && renditions.length() > 0) {
                            for (q in 0 until renditions.length()) {
                                val item = renditions.optJSONObject(q) ?: continue
                                val url = item.optString("url").replace("\\/", "/")
                                if (url.isBlank()) continue
                                emit(
                                    newExtractorLink(
                                        name,
                                        "$label ${item.optString("label").ifBlank { item.optString("q") }}",
                                        fixUrl(url),
                                        ExtractorLinkType.VIDEO,
                                    ) {
                                        referer = data
                                        quality = getQualityFromName(item.optString("label"))
                                    }
                                )
                            }
                        } else {
                            emit(
                                newExtractorLink(
                                    name,
                                    label,
                                    fixUrl(streamUrl),
                                    if (isMp4) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8,
                                ) {
                                    referer = data
                                    quality = getQualityFromName(label)
                                }
                            )
                        }
                    }

                    src.isNotBlank() -> {
                        val embed = if (src.startsWith("http")) {
                            src
                        } else {
                            // Our own /oynat/ page only signs a redirect; the host is in Location.
                            app.get(fixUrl(src), referer = data, allowRedirects = false, headers = uaHeaders)
                                .headers["location"]
                        }
                        if (!embed.isNullOrBlank()) loadExtractor(embed, data, subtitleFilter, emit)
                    }

                    resolveUrl.isNotBlank() -> {
                        val resolve = if (resolveUrl.startsWith("http")) resolveUrl else "$mainUrl$resolveUrl"
                        val videoKey = JSONObject(app.get(resolve, referer = data, headers = uaHeaders).text)
                            .optString("id")
                        if (videoKey.isNotBlank()) {
                            // tau-video's SPA loads its own API; read the renditions directly.
                            val body = runCatching {
                                app.get(
                                    "https://tau-video.xyz/api/video/$videoKey",
                                    referer = "https://tau-video.xyz/",
                                    headers = uaHeaders,
                                ).text
                            }.getOrNull().orEmpty()
                            val urls = runCatching { JSONObject(body).optJSONArray("urls") }.getOrNull()
                            if (urls != null) {
                                for (i in 0 until urls.length()) {
                                    val item = urls.optJSONObject(i) ?: continue
                                    val stream = item.optString("url").takeIf { it.startsWith("http") }
                                        ?: continue
                                    val label = item.optString("label").ifBlank { "TauVideo" }
                                    emit(
                                        newExtractorLink(name, "$name · $label", stream, ExtractorLinkType.VIDEO) {
                                            this.quality = getQualityFromName(label)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (ignored: Exception) {
            }
        }
        if (emitted == 0) throw ErrorLoadingException("$name: oynatılabilir akış yok. $REPORT_HINT")
        return true
    }

    /** The site escapes `/` inside the blob, which a plain atob cannot decode; drop it. */
    private fun decodeBlob(value: String): String {
        val clean = value.replace("\\", "")
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        return String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
    }

    companion object {
        /** Homepage row labels, in display order; each maps to a live listing page above. */
        val ROWS = listOf("Son Eklenenler", "Anime", "Popüler")
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
        private val ServersRegex = Regex("""var servers\s*=\s*JSON\.parse\(atob\("([^"]+)"\)\)""")
        private val SeasonRegex = Regex("""-(\d+)-sezon-\d+-bolum-izle""")
    }
}