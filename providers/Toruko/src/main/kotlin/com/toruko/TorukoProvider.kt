package com.toruko

import com.bigbull.common.browserHeaders
import com.bigbull.common.jsonLdDescription
import com.bigbull.common.posterSrcLazy
import com.lagradost.cloudstream3.Episode
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
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Element

/**
 * TORUKO — turkceanime.com, Türkçe altyazılı/dublajlı anime.
 *
 * Bölüm listesi sunucuda üretilir: uzun sezonlar detay sayfasında 24 bölümle kesilir, kalanı
 * `/anime/<slug>/<n>-sezon/?sayfa=k` sayfalarında 48'er gelir. Oynatma zinciri: bölüm kimliği ->
 * `/api/episode/<id>/sources` (çevirmen + kaynak başına token) -> `/player/<token>`; oynatıcı ya
 * aynı alan adındaki `/hls?u=` (m3u8) / `/mp4/stream/?token=` vekilini ya da tau-video iframe'ini verir.
 * Altyazı akışa gömülü (master m3u8'de EXT-X-MEDIA yok), ayrı altyazı dosyası yayınlanmıyor.
 */
class TorukoProvider : MainAPI() {
    override var mainUrl = "https://turkceanime.com"
    override var name = "TORUKO"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime)

    /** Ana sayfa rafları tek istekte gelir; raf başına ayrı istek atılmaz. */
    private var homeCache: Pair<Long, Map<String, List<SearchResponse>>>? = null

    override val mainPage get() = ROWS.filter { Settings.rowEnabled(it) }.map { MainPageData(it, it) }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        return newHomePageResponse(request.name, homeShelves()[request.name] ?: emptyList(), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/ara/?q=" + java.net.URLEncoder.encode(query, "UTF-8")
        return tiles(app.get(url, headers = browserHeaders).document)
    }

    private suspend fun homeShelves(): Map<String, List<SearchResponse>> {
        homeCache?.let { (at, cached) ->
            if (System.currentTimeMillis() - at < 10 * 60_000) return cached
        }
        val doc = app.get(mainUrl, headers = browserHeaders).document
        val shelves = doc.select("section.shelf").mapNotNull { shelf ->
            val title = shelf.selectFirst("h2.shelf-title")?.text()?.trim().orEmpty()
            val cards = tiles(shelf)
            if (title.isBlank() || cards.isEmpty()) null else title to cards
        }.toMap()
        if (shelves.isNotEmpty()) homeCache = System.currentTimeMillis() to shelves
        return shelves
    }

    private fun tiles(root: Element): List<SearchResponse> =
        root.select("article.tile a.tile-link[href]").mapNotNull { card ->
            val href = fixUrlNull(card.attr("href")) ?: return@mapNotNull null
            val title = card.selectFirst(".tile-title")?.text()?.trim().orEmpty()
            if (title.isBlank()) return@mapNotNull null
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = fixUrlNull(card.selectFirst("img")?.posterSrcLazy())
            }
        }.distinctBy { it.url }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = browserHeaders).document
        val title = doc.selectFirst("h1.hero-title")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: başlık okunamadı")
        val facts = doc.select("dl.facts dt").associate {
            it.text().trim() to (it.nextElementSibling()?.text()?.trim().orEmpty())
        }
        val blocks = doc.select("section.season-block")
        val episodes = mutableListOf<Episode>()
        for (block in blocks) {
            val season = block.attr("data-season-block").toIntOrNull() ?: 1
            for (card in block.select("a.ep-card")) addEpisode(episodes, card, season, blocks.size > 1)
            // Detay sayfası sezonu ilk 24 bölümle keser; sezon sayfası 48'er bölüm verir, 1. sayfa da gerekli.
            val seasonUrl = block.selectFirst("p.more a[href]")?.let { fixUrlNull(it.attr("href")) } ?: continue
            val base = seasonUrl.trimEnd('/')
            // shortcut: sayfa üst sınırı 60 (2880 bölüm); sınır sayacın kendisinden değil buradan gelir.
            var pages = 60
            var page = 1
            while (page <= pages) {
                val pageDoc = runCatching { app.get("$base/?sayfa=$page", headers = browserHeaders).document }.getOrNull()
                    ?: break
                Regex("""Sayfa \d+ / (\d+)""").find(pageDoc.text())?.groupValues?.get(1)?.toIntOrNull()
                    ?.let { pages = minOf(pages, it) }
                val cards = pageDoc.select("a.ep-card")
                if (cards.isEmpty()) break
                for (card in cards) addEpisode(episodes, card, season, blocks.size > 1)
                page++
            }
        }
        val list = episodes.distinctBy { it.data }
        if (list.isEmpty()) throw ErrorLoadingException("$name: bölüm bulunamadı")
        val score = SCORE.find(doc.selectFirst(".hero-meta .score")?.text().orEmpty())?.value?.replace(',', '.')
        return newTvSeriesLoadResponse(title, url, TvType.Anime, list) {
            this.posterUrl = fixUrlNull(doc.selectFirst(".anime-poster img")?.let { el -> el.attr("data-src").ifBlank { el.attr("data-lazy-src").ifBlank { el.attr("data-original").ifBlank { el.attr("data-srcset").substringBefore(" ").trim().ifBlank { el.attr("src") } } } } })
            this.plot = doc.selectFirst(".room-desc")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: doc.jsonLdDescription()
            this.year = doc.selectFirst(".hero-meta a[href^=/yil/]")?.text()?.trim()?.toIntOrNull()
                ?: YEAR.find(facts["Yapım"].orEmpty())?.value?.toIntOrNull()
            this.score = Score.from10(score)
            this.tags = doc.select(".hero-badges a.badge").map { it.text().trim() }
                .filter { it.isNotBlank() }.ifEmpty { null }
            this.duration = Regex("""\d+""").find(facts["Süre"].orEmpty())?.value?.toIntOrNull()
        }
    }

    private fun addEpisode(into: MutableList<Episode>, card: Element, season: Int, numberedSeasons: Boolean) {
        val id = card.attr("data-episode").toIntOrNull() ?: return
        val number = EPISODE.find(card.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        into += newEpisode("$mainUrl/api/episode/$id/sources") {
            this.name = when {
                season == 0 -> "Film"
                number == null -> card.selectFirst(".ep-t")?.text()?.trim().orEmpty().ifBlank { "Bölüm" }
                numberedSeasons -> "$season. Sezon $number. Bölüm"
                else -> "$number. Bölüm"
            }
            this.season = season.takeIf { it > 0 }
            this.episode = number ?: 1
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val body = runCatching { JSONObject(app.get(data, headers = browserHeaders).text) }.getOrNull()
            ?: throw ErrorLoadingException("$name: kaynak listesi okunamadı")
        val translators = body.optJSONArray("translators") ?: throw ErrorLoadingException("$name: bu bölümde kaynak yok")
        var found = false
        for (i in 0 until translators.length()) {
            val translator = translators.optJSONObject(i) ?: continue
            val who = translator.optString("name").trim().ifBlank { "Çevirmen" }
            val sources = translator.optJSONArray("sources") ?: continue
            for (j in 0 until sources.length()) {
                val source = sources.optJSONObject(j) ?: continue
                if (source.optBoolean("locked")) continue
                val token = source.optString("token").trim().takeIf { it.isNotBlank() } ?: continue
                val raw = source.optString("quality").trim()
                val quality = QUALITY.find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: Qualities.Unknown.value
                if (!Settings.qualityAllowed(quality)) continue
                val label = "$who · ${raw.ifBlank { "kaynak" }}"
                val player = runCatching { app.get("$mainUrl/player/$token", headers = browserHeaders).text }.getOrNull()
                    ?: continue
                val target = STREAM_URL.find(player)?.groupValues?.get(1)?.trim()?.trim('"')
                if (target == null) {
                    val frame = Regex("""<iframe[^>]+src="([^"]+)""").find(player)?.groupValues?.get(1)
                        ?.let { fixUrl(it) } ?: continue
                    if (emitTau(frame, label, quality, subtitleCallback, callback)) found = true
                    continue
                }
                val stream = if (target.startsWith("/")) "$mainUrl$target" else target
                val type = if (stream.contains(".mp4") || stream.contains("/mp4/")) ExtractorLinkType.VIDEO
                else ExtractorLinkType.M3U8
                callback.invoke(newExtractorLink(name, label, stream, type) {
                    this.referer = "$mainUrl/"
                    this.quality = quality
                this.headers = browserHeaders
                })
                found = true
            }
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir kaynak yok")
        return found
    }

    /** Sunucunun iframe oynatıcısı tau-video; mp4 varyantlarını kendi API'si veriyor. */
    private suspend fun emitTau(
        frame: String,
        label: String,
        quality: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!frame.contains(TAU_EMBED)) return loadExtractor(frame, "$mainUrl/", subtitleCallback, callback)
        val key = frame.substringAfter("/embed/").substringBefore('?')
        if (key.isBlank()) return false
        val urls = runCatching {
            JSONObject(app.get(TAU_API + key, headers = browserHeaders, referer = "$TAU_HOST/").text)
                .optJSONArray("urls")
        }.getOrNull() ?: return false
        var emitted = false
        for (k in 0 until urls.length()) {
            val item = urls.optJSONObject(k) ?: continue
            val stream = item.optString("url").trim().takeIf { it.isNotBlank() } ?: continue
            val own = getQualityFromName(item.optString("label")).takeIf { it != Qualities.Unknown.value } ?: quality
            if (!Settings.qualityAllowed(own)) continue
            callback.invoke(newExtractorLink(name, "$label · ${item.optString("label")}".trim(' ', '·'), stream, ExtractorLinkType.VIDEO) {
                this.referer = "$TAU_HOST/"
                this.quality = own
                this.headers = browserHeaders
            })
            emitted = true
        }
        return emitted
    }

    companion object {
        private const val TAU_HOST = "https://tau-video.xyz"
        private const val TAU_EMBED = "$TAU_HOST/embed/"
        private const val TAU_API = "$TAU_HOST/api/video/"
        private val STREAM_URL = Regex("""id="stream-url"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        private val EPISODE = Regex("""(\d+)-bolum/""")
        private val QUALITY = Regex("""(\d{3,4})""")
        private val SCORE = Regex("""(\d+[.,]\d+)""")
        private val YEAR = Regex("""\b(19|20)\d{2}\b""")

        val ROWS = listOf(
            "Toruko'da Bu Hafta En Çok İzlenenler",
            "En Popüler",
            "Toruko'da Yeni",
            "Fantastik",
            "Komedi",
        )
    }
}
