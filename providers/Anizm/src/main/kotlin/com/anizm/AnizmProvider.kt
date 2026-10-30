package com.anizm

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Collections

/** Anizm (turkanime.info) — Türkçe altyazılı anime; anizm.net Cloudflare arkasında. */
class AnizmProvider : MainAPI() {
    override var mainUrl = "https://turkanime.info"
    override var name = "Anizm"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Anime)

    private val uaHeaders = mapOf(
        "User-Agent" to UA,
        "Accept-Language" to "tr-TR,tr;q=0.9",
    )
    private val ajaxHeaders = uaHeaders + mapOf("X-Requested-With" to "XMLHttpRequest")

    override val mainPage get() = ROWS.mapNotNull { name ->
        if (!Settings.rowEnabled(name)) return@mapNotNull null
        when (name) {
            "Katalog" -> MainPageData(name, "$mainUrl/arama?sayfa=SAYFA")
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data.replace("SAYFA", page.toString()), headers = uaHeaders).document
        val items = doc.select("article.v3-catalog-card").mapNotNull { it.toSearchResult() }
        val total = doc.selectFirst("nav.v3-pagination span")
            ?.text()?.substringAfter('/', "")?.trim()?.toIntOrNull()
        return newHomePageResponse(request.name, items, total == null || page < total)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val doc = app.get("$mainUrl/arama?q=$encoded", headers = uaHeaders).document
        return doc.select("article.v3-catalog-card").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(selectFirst("a.v3-catalog-poster")?.attr("href")) ?: return null
        val title = selectFirst("h2 a")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return newTvSeriesSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = fixUrlNull(selectFirst("a.v3-catalog-poster img")?.attr("src"))
            this.year = selectFirst(".v3-catalog-card-copy p")?.text()?.trim()?.toIntOrNull()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url, headers = uaHeaders).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val title = doc.selectFirst("h1")?.text()?.removeSuffix(" izle")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")

        val episodes = doc.select(".v3-anime-episode-grid a[href]").mapNotNull { anchor ->
            val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            val number = anchor.attr("data-episode-number").toIntOrNull()
                ?: anchor.selectFirst(".v3-anime-episode-number")?.text()?.trim()?.toIntOrNull()
            val episodeName = anchor.attr("data-episode-title")
                .ifBlank { anchor.selectFirst("strong")?.text().orEmpty() }
                .trim()
            if (episodeName.contains("fragman", true) || (number != null && number < 1)) {
                return@mapNotNull null
            }
            newEpisode(href) {
                this.name = episodeName.ifBlank { null }
                this.episode = number
            }
        }
        if (episodes.isEmpty()) {
            throw ErrorLoadingException("$name: bölüm listesi bulunamadı. $REPORT_HINT")
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = fixUrlNull(doc.selectFirst(".v3-anime-detail-cover img")?.attr("src"))
            this.plot = doc.selectFirst(".v3-anime-summary p")?.text()?.trim()?.takeIf { it.isNotBlank() }
            this.year = doc.select(".v3-anime-detail-meta span").firstNotNullOfOrNull { span ->
                span.text().trim().toIntOrNull()?.takeIf { it in 1900..2100 }
            }
            this.tags = doc.select(".v3-anime-detail-tags a").map { it.text().trim() }.filter { it.isNotBlank() }
            this.score = Score.from10(doc.selectFirst(".v3-anime-rating-card strong")?.text()?.substringBefore('/'))
            doc.selectFirst(".v3-anime-detail-trailer iframe")?.attr("src")
                ?.replace("youtube-nocookie.com", "www.youtube.com")
                ?.takeIf { it.startsWith("http") }
                ?.let { addTrailer(it) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val seenLinks = Collections.synchronizedSet(mutableSetOf<String>())
        val seenSubs = Collections.synchronizedSet(mutableSetOf<String>())
        val emit: (ExtractorLink) -> Unit = {
            if (Settings.qualityAllowed(it.quality)) callback(it)
        }
        val emitSub: (SubtitleFile) -> Unit = {
            if (seenSubs.add(it.url) && Settings.subtitleAllowed(it.lang)) subtitleCallback(it)
        }

        val doc = runCatching { app.get(data, headers = uaHeaders).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: bölüm yüklenemedi. $REPORT_HINT")
        val episodeId = doc.selectFirst("[data-episode-id]")?.attr("data-episode-id")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: bölüm kimliği bulunamadı. $REPORT_HINT")

        val translators = doc.select("button[data-v3-translator]").mapNotNull { button ->
            val id = button.attr("data-translator-id").trim().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val name = button.attr("title").trim().let { if (it == "-") "" else it }
            id to name
        }.distinctBy { it.first }
        val perTranslator = translators.amap { (translatorId, translatorName) ->
            val payload = runCatching {
                app.get(
                    "$mainUrl/bolum/$episodeId/cevirmen/$translatorId",
                    referer = data,
                    headers = ajaxHeaders,
                ).text
            }.getOrNull() ?: return@amap emptyList()
            val videos = runCatching { JSONObject(payload).optJSONArray("videos") }.getOrNull()
                ?: return@amap emptyList()
            (0 until videos.length()).mapNotNull { index ->
                val video = videos.optJSONObject(index) ?: return@mapNotNull null
                val videoId = video.optInt("id").takeIf { it > 0 } ?: return@mapNotNull null
                val sourceName = video.optString("name").trim().ifBlank { "Video" }
                val label = if (translatorName.isBlank()) sourceName else "$sourceName · $translatorName"
                label to videoId
            }
        }

        // Every mirror costs one same-host /player probe plus an extractor pass; a 6-translator
        // episode lists 54 of them (152s under QA's 2s-per-host gate). Take each translator's
        // first mirror first, then round two, up to MAX_MIRRORS, and stop once MAX_LINKS answer.
        val sources = (0 until MAX_MIRRORS)
            .flatMap { rank -> perTranslator.mapNotNull { it.getOrNull(rank) } }
            .take(MAX_MIRRORS)

        // Emit as soon as a mirror answers so one slow embed host cannot hold back the rest.
        sources.amap { (label, videoId) ->
            if (seenLinks.size >= MAX_LINKS) return@amap
            val links = mutableListOf<ExtractorLink>()
            val subs = mutableListOf<SubtitleFile>()
            val playerUrl = "$mainUrl/player/$videoId"
            val probe = runCatching {
                app.get(playerUrl, referer = data, headers = uaHeaders, allowRedirects = false)
            }.getOrNull()
            // The app answers the 302 itself; the JVM QA runner ignores allowRedirects and lands
            // on the embed, so the final response URL is the redirect target there.
            val target = listOfNotNull(probe?.headers?.get("location"), probe?.url)
                .firstOrNull { it.startsWith("http") && it != playerUrl }
            if (target != null) {
                runCatching {
                    when {
                        target.contains("anizmplayer.com") -> loadAnizmPlayer(target, data, label) { links.add(it) }
                        target.contains("puffytr") -> loadPuffytr(target, label) { links.add(it) }
                        else -> loadExtractor(target, data, { subs.add(it) }, { links.add(it) })
                    }
                }
            }
            links.forEach { link -> if (seenLinks.add(link.url)) emit(link) }
            subs.forEach(emitSub)
        }

        if (seenLinks.isEmpty()) throw ErrorLoadingException("$name: oynatılabilir akış yok. $REPORT_HINT")
        return true
    }

    /**
     * Anizm's own player (PuffyTR engine): POST `player/index.php?do=getVideo` answers JSON
     * whose `videoSource` is the master playlist. The signed `securedLink` 403s on cold
     * requests, so only `videoSource` is used.
     */
    private suspend fun loadAnizmPlayer(
        embed: String,
        referer: String,
        label: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val hash = embed.substringAfterLast('/').takeIf { it.isNotBlank() } ?: return false
        val origin = embed.substringBefore("/video/").takeIf { it.startsWith("http") } ?: return false
        val body = runCatching {
            JSONObject(
                app.post(
                    "$origin/player/index.php?data=$hash&do=getVideo",
                    data = mapOf("hash" to hash, "r" to referer),
                    headers = uaHeaders + mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to embed,
                        "Origin" to origin,
                    ),
                ).text,
            )
        }.getOrNull() ?: return false
        val stream = body.optString("videoSource").trim().takeIf { it.startsWith("http") } ?: return false
        callback.invoke(
            newExtractorLink(name, label, stream, ExtractorLinkType.M3U8) {
                this.referer = "$origin/"
                // The CDN answers "security error" to requests without Accept.
                this.headers = mapOf("Accept" to "*/*")
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    /** pl.puffytr.tr/watch/{slug} plays the master at /stream/{slug}/native.m3u8. */
    private suspend fun loadPuffytr(target: String, label: String, callback: (ExtractorLink) -> Unit): Boolean {
        val origin = target.substringBefore("/watch/")
        val slug = target.substringAfterLast('/')
        if (slug.isBlank() || origin == target) return false
        callback.invoke(
            newExtractorLink(name, label, "$origin/stream/$slug/native.m3u8", ExtractorLinkType.M3U8) {
                this.referer = target
                this.headers = mapOf("Accept" to "*/*")
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    companion object {
        /** Homepage rows in display order; all map to live listing pages. */
        val ROWS = listOf("Katalog")

        private const val MAX_LINKS = 4
        private const val MAX_MIRRORS = 10
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}
