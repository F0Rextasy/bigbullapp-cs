package com.dizikutusu

import com.bigbull.common.browserHeaders
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
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
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element

/**
 * DiziKutusu — dizikutusu.com (WordPress "movifox" teması).
 * Bölüm sayfası tek bir `<iframe>` taşır: ok.ru/odnoklassniki, vk.com, vidmoly
 * ya da kendi fscdn/fembeds oynatıcısı. ok.ru JSON'u HTML-escape'li gelir,
 * VK ise `video.getEmbed` API'sini ister; ikisi de burada çözülür.
 */
class DiziKutusuProvider : MainAPI() {
    override var mainUrl = "https://dizikutusu.com"
    override var name = "DiziKutusu"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries)

    private val uaHeaders get() = browserHeaders

    override val mainPage get() = ROWS.mapNotNull { name ->
        if (!Settings.rowEnabled(name)) return@mapNotNull null
        when (name) {
            "Son Eklenen Diziler" -> MainPageData(name, "$mainUrl/dizi-arsivi/page/SAYFA/")
            "En Çok İzlenenler" -> MainPageData(name, "$mainUrl/dizi-arsivi/page/SAYFA/?sort=views")
            "IMDB Puanı" -> MainPageData(name, "$mainUrl/dizi-arsivi/?sort=imdb")
            "Kore Dizileri" -> MainPageData(name, "$mainUrl/dizi-kategori/guney-kore-dizileri-izle/page/SAYFA/")
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data.replace("SAYFA", page.toString()), headers = uaHeaders).document
        val items = doc.select(CARD).mapNotNull { it.toSeriesCard() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = doc.selectFirst("a.loadnavi") != null)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/?s=$encoded", headers = uaHeaders).document
        return doc.select(CARD).mapNotNull { it.toSeriesCard() }.distinctBy { it.url }
    }

    private fun Element.toSeriesCard(): SearchResponse? {
        val anchor = selectFirst(".movie-details .name a") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        if (!href.contains("/dizi/")) return null
        val title = anchor.text().trim().ifBlank { selectFirst(".poster .img img")?.attr("alt").orEmpty() }
        if (title.isBlank()) return null
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = fixUrlNull(selectFirst(".poster .img img")?.attr("src"))
            this.posterHeaders = uaHeaders
            this.score = Score.from10(selectFirst(".poster .rating .align-right")?.text()?.trim())
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url, headers = uaHeaders).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val title = doc.selectFirst("h1.film")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")

        val episodes = doc.select("#seasons .s-wrap").flatMap { wrap ->
            val season = wrap.id().removePrefix("s-").toIntOrNull() ?: 1
            wrap.select(".ep-box").mapNotNull { card ->
                val href = fixUrlNull(card.selectFirst(".poster a[href]")?.attr("href"))
                    ?: return@mapNotNull null
                if (!href.contains("/bolum/")) return@mapNotNull null
                // Bazı (Sherlock gibi) kartlarda episodetitle yok; başlık "Sherlock 1. Sezon 1. Bölüm" olarak gelir.
                val label = card.selectFirst(".episodetitle")?.text()?.trim()?.takeIf { it.isNotBlank() }
                    ?: card.selectFirst(".movie-details .name a")?.text()?.trim()?.takeIf { it.isNotBlank() }
                val number = Regex("""(\d+)\.\s*Bölüm""").find(label.orEmpty())
                    ?.groupValues?.get(1)?.toIntOrNull()
                newEpisode(href) {
                    this.name = label?.let { full -> full.removePrefix(title).trim().ifBlank { full } }
                    this.season = season
                    this.episode = number
                }
            }
        }.distinctBy { it.data }
        if (episodes.isEmpty()) throw ErrorLoadingException("$name: bölüm listesi bulunamadı. $REPORT_HINT")

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
                ?: fixUrlNull(doc.selectFirst(".movie-left .poster .img img")?.attr("src"))
            this.posterHeaders = uaHeaders
            this.plot = doc.selectFirst(".description")?.text()?.trim()
            this.year = Regex("""\d{4}""").find(doc.selectFirst(".info li.release")?.text().orEmpty())
                ?.value?.toIntOrNull()
            this.tags = doc.select(".category a").map { it.text().trim() }.filter { it.isNotBlank() }
            this.score = Score.from10(
                Regex("""\d+(?:[.,]\d+)?""").find(doc.selectFirst(".imdb-count")?.text().orEmpty())
                    ?.value?.replace(',', '.')
            )
            this.actors = doc.select(".cast .actors").text().removePrefix("Oyuncular").trim()
                .split(",").map { it.trim() }.filter { it.isNotBlank() }
                .map { ActorData(Actor(it)) }
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

        val doc = runCatching { app.get(data, headers = uaHeaders).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: kaynak bulunamadı. $REPORT_HINT")
        val embeds = doc.select("iframe[data-src], iframe[src]")
            .mapNotNull { frame ->
                val raw = frame.attr("data-src").ifBlank { frame.attr("src") }.trim()
                when {
                    raw.isBlank() -> null
                    raw.startsWith("//") -> "https:$raw"
                    else -> fixUrlNull(raw)
                }
            }
            .filter { it.startsWith("http") }
            .distinct()

        var found = false
        // VK önce: ok.ru CDN'i bazı ağlarda HTTP hatası veriyor.
        for (embed in embeds.sortedBy {
            when {
                it.contains("vk.com/video_ext") || it.contains("vkvideo.ru") -> 0
                it.contains("ok.ru/") || it.contains("odnoklassniki.ru") -> 1
                else -> 2
            }
        }) {
            val ok = when {
                embed.contains("ok.ru/") || embed.contains("odnoklassniki.ru") ->
                    loadOkRu(embed, linkCallback)
                embed.contains("vk.com/video_ext") || embed.contains("vkvideo.ru") ->
                    loadVk(embed, linkCallback)
                else -> loadExtractor(embed, data, subtitleFilter, linkCallback)
            }
            if (ok) found = true
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir kaynak yok. $REPORT_HINT")
        return true
    }

    /** ok.ru/odnoklassniki videoembed player JSON'unu HTML-escape'ten çözer ve `videos` dizisini doğrudan MP4 olarak verir. */
    private suspend fun loadOkRu(embed: String, callback: (ExtractorLink) -> Unit): Boolean {
        val url = embed.replace("odnoklassniki.ru", "ok.ru")
        val ua = browserHeaders.getValue("user-agent")
        val body = runCatching { app.get(url, referer = "$mainUrl/", headers = uaHeaders).text }
            .getOrNull()?.replace("&quot;", "\"") ?: return false
        val array = Regex(""""videos"\s*:\s*(\[.*?])""", RegexOption.DOT_MATCHES_ALL)
            .find(body)?.groupValues?.get(1) ?: return false
        val videos = runCatching { JSONArray(array) }.getOrNull() ?: return false

        val parsed = (0 until videos.length()).mapNotNull { i ->
            val video = videos.optJSONObject(i) ?: return@mapNotNull null
            val bucket = video.optString("name").lowercase()
            if (bucket == "mobile") return@mapNotNull null
            val stream = video.optString("url").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val quality = okRuQuality(bucket)
            if (!Settings.qualityAllowed(quality)) return@mapNotNull null
            quality to stream
        }.sortedByDescending { it.first }

        for ((quality, stream) in parsed) {
            callback.invoke(
                newExtractorLink(name, "ok.ru", stream, ExtractorLinkType.VIDEO) {
                    this.referer = "https://ok.ru/"
                    this.headers = mapOf("User-Agent" to ua)
                    this.quality = quality
                }
            )
        }
        return parsed.isNotEmpty()
    }

    /** vk.com embed'i anonim `access_token` taşır; `video.getEmbed` mp4_* ve hls dosyalarını JSON döner. */
    private suspend fun loadVk(embed: String, callback: (ExtractorLink) -> Unit): Boolean {
        val page = runCatching {
            app.get(embed.replace("vkvideo.ru", "vk.com"), referer = "$mainUrl/", headers = uaHeaders).text
        }.getOrNull() ?: return false
        val token = Regex("""access_token"\s*:\s*"(anonym\.[^"]+)""")
            .find(page)?.groupValues?.get(1) ?: return false
        val oid = Regex("""[?&]oid=(-?\d+)""").find(embed)?.groupValues?.get(1) ?: return false
        val id = Regex("""[?&]id=(\d+)""").find(embed)?.groupValues?.get(1) ?: return false
        val hash = Regex("""[?&]hash=([0-9a-fA-F]+)""").find(embed)?.groupValues?.get(1).orEmpty()

        val body = runCatching {
            app.post(
                "https://api.vk.com/method/video.getEmbed?v=5.289&client_id=6287487",
                data = mapOf(
                    "owner_id" to oid,
                    "video_id" to id,
                    "hash" to hash,
                    "access_token" to token,
                ),
                headers = mapOf("Origin" to "https://vk.com", "Referer" to "https://vk.com/"),
            ).text
        }.getOrNull() ?: return false
        val files = runCatching {
            JSONObject(body).optJSONObject("response")?.optJSONObject("video")?.optJSONObject("files")
        }.getOrNull() ?: return false

        var found = false
        for (kind in files.keys()) {
            val stream = files.optString(kind)
            if (!stream.startsWith("http")) continue
            val type = when {
                kind == "hls" -> ExtractorLinkType.M3U8
                kind.startsWith("mp4_") -> ExtractorLinkType.VIDEO
                else -> continue
            }
            callback.invoke(
                newExtractorLink(name, "VK", stream, type) {
                    this.referer = "https://vk.com/"
                    this.quality = if (type == ExtractorLinkType.M3U8) Qualities.Unknown.value
                    else vkQuality(kind.removePrefix("mp4_"))
                }
            )
            found = true
        }
        return found
    }

    private fun okRuQuality(label: String): Int = when (label) {
        "mobile", "lowest" -> Qualities.P240.value
        "low" -> Qualities.P360.value
        "sd" -> Qualities.P480.value
        "hd" -> Qualities.P720.value
        "full" -> Qualities.P1080.value
        else -> Qualities.Unknown.value
    }

    private fun vkQuality(height: String): Int = when (height) {
        "240" -> Qualities.P240.value
        "360" -> Qualities.P360.value
        "480" -> Qualities.P480.value
        "720" -> Qualities.P720.value
        "1080" -> Qualities.P1080.value
        "1440" -> Qualities.P1440.value
        "2160" -> Qualities.P2160.value
        else -> Qualities.P144.value
    }

    companion object {
        private const val CARD = "div.series-box"
        val ROWS = listOf("Son Eklenen Diziler", "En Çok İzlenenler", "IMDB Puanı", "Kore Dizileri")
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}
