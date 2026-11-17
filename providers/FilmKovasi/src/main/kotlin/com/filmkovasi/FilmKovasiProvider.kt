package com.filmkovasi

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
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Filmkovası — filmkovasi.co (WordPress/filmplus, ~778 sayfalık film arşivi).
 * Film sayfası oynatıcıyı `atob("<base64 JSON>")` dizisindeki gömme adreslerle kurar
 * (vidmoly/vtbe JWPlayer master m3u8, ok.ru, my.mail.ru, videoseyred, YouTube).
 * YouTube fragman gömmeleri Cloudstream'de oynatılamadığı için atlanır; site altyazı sunmaz.
 */
class FilmKovasiProvider : MainAPI() {
    override var mainUrl = "https://filmkovasi.co"
    override var name = "Filmkovası"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    private val uaHeaders get() = browserHeaders

    override val mainPage get() = ROWS.mapNotNull { name ->
        if (!Settings.rowEnabled(name)) return@mapNotNull null
        val path = GENRES[name]?.let { "filmizle/$it/" }.orEmpty()
        MainPageData(name, "$mainUrl/$path")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val doc = app.get(url, headers = uaHeaders).document
        val cards = doc.select(CARD).mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        // Son sayfa dışında liste 24 kart döner; site `rel=next` etiketini yalnız 1. sayfada basıyor.
        return newHomePageResponse(request.name, cards, hasNext = cards.size >= PAGE_SIZE)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/?s=$encoded", headers = uaHeaders).document
        return doc.select(CARD).mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst(".film-ismi a[href]") ?: selectFirst(".poster a[href]") ?: return null
        // Arama sonuçları utm parametreleriyle gelir; detay adresi temiz tutulur.
        val href = fixUrlNull(anchor.attr("href").substringBefore("?")) ?: return null
        val raw = anchor.text().trim().ifBlank { selectFirst("img")?.attr("alt").orEmpty() }
        val title = cleanTitle(raw) ?: return null
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = fixUrlNull((selectFirst(".poster img") ?: selectFirst("img[alt]") ?: selectFirst("img"))?.posterSrcLazy())
            this.year = YEAR.find(raw)?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url, headers = uaHeaders).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val heading = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        val title = cleanTitle(heading)
            ?: cleanTitle(doc.title().substringBefore("|"))
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = fixUrlNull(doc.selectFirst(".film-afis img")?.posterSrcLazy())
                ?: fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
            this.year = YEAR.find(heading)?.groupValues?.get(1)?.toIntOrNull()
            this.plot = doc.selectFirst("#film-aciklama")?.text()?.trim()?.takeIf { it.isNotBlank() }
            this.tags = doc.select("#filmbilgileri .elements a[href*='/filmizle/']")
                .map { it.text().trim() }.filter { it.isNotBlank() }
            val actors = doc.select(".list-item a[href*='/oyuncu/']")
                .map { it.text().trim() }.filter { it.isNotBlank() }
            if (actors.isNotEmpty()) addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val linkCallback: (ExtractorLink) -> Unit = { if (Settings.qualityAllowed(it.quality)) callback(it) }
        val subtitleFilter: (SubtitleFile) -> Unit = { if (Settings.subtitleAllowed(it.lang)) subtitleCallback(it) }
        val resp = runCatching { app.get(data, headers = uaHeaders) }.getOrNull()
            ?: throw ErrorLoadingException("$name: kaynak yüklenemedi. $REPORT_HINT")

        var found = false
        for (src in embedUrls(resp.text, resp.document)) {
            val ok = when {
                // YouTube gömmeleri (fragman/uzun metraj) Cloudstream'de oynatılamaz.
                src.contains("youtube.com") || src.contains("youtu.be") -> false
                src.contains("my.mail.ru") -> loadMailRu(src, linkCallback)
                src.contains("ok.ru") || src.contains("odnoklassniki") -> loadOkRu(src, linkCallback)
                MIRROR_HOST.containsMatchIn(src) ->
                    loadMirror(src, linkCallback) || loadExtractor(src, data, subtitleFilter, linkCallback)
                else -> loadExtractor(src, data, subtitleFilter, linkCallback)
            }
            if (ok) found = true
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir kaynak yok. $REPORT_HINT")
        return true
    }

    /** Sayfa oynatıcıyı `atob(...)` JSON dizisiyle kurar; iframe'lerin src'si `about:blank` başlar. */
    private fun embedUrls(html: String, doc: Document): List<String> {
        val out = LinkedHashSet<String>()
        for (match in AT_OB.findAll(html)) {
            val json = decodeBase64(match.groupValues[1]) ?: continue
            val array = runCatching { JSONArray(json) }.getOrNull() ?: continue
            for (i in 0 until array.length()) {
                array.optString(i).trim().takeIf { it.startsWith("http") }?.let { out.add(it) }
            }
        }
        for (frame in doc.select("iframe[src]")) {
            val src = frame.attr("src").trim()
            if (src.startsWith("http")) out.add(src)
        }
        return out.toList()
    }

    private fun decodeBase64(value: String): String? =
        runCatching { String(Base64.getDecoder().decode(value), Charsets.UTF_8) }.getOrNull()
            ?: runCatching { String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8) }.getOrNull()

    /** vidmoly/vtbe benzeri JWPlayer kabuğu: `sources: [{file:"…master.m3u8"}]`. */
    private suspend fun loadMirror(embed: String, callback: (ExtractorLink) -> Unit): Boolean {
        val html = runCatching { app.get(embed, referer = "$mainUrl/", headers = uaHeaders).text }
            .getOrNull() ?: return false
        val stream = JW_FILE.find(html)?.groupValues?.get(1)
            ?: M3U8.find(html)?.value
            ?: return false
        callback.invoke(
            newExtractorLink(name, name, stream, ExtractorLinkType.M3U8) {
                this.referer = if (embed.contains("vidmoly.")) "https://vidmoly.biz/" else hostRoot(embed)
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    /** ok.ru/odnoklassniki gömme kabuğu; aynı kimliğe videoPlayerMetadata `videos` dizisini verir. */
    private suspend fun loadOkRu(embed: String, callback: (ExtractorLink) -> Unit): Boolean {
        val id = OK_ID.find(embed)?.groupValues?.get(1) ?: return false
        val body = runCatching {
            app.post(
                "https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$id",
                data = emptyMap(),
                headers = mapOf("user-agent" to ANDROID_UA, "Referer" to "https://ok.ru/"),
            ).text
        }.getOrNull() ?: return false
        val videos = runCatching { JSONObject(body).optJSONArray("videos") }.getOrNull() ?: return false

        var found = false
        for (i in 0 until videos.length()) {
            val item = videos.optJSONObject(i) ?: continue
            val stream = item.optString("url").takeIf { it.startsWith("http") } ?: continue
            val quality = when (item.optString("name").lowercase()) {
                "mobile", "lowest" -> 240
                "low" -> 360
                "sd" -> 480
                "hd" -> 720
                "full" -> 1080
                else -> getQualityFromName(item.optString("name").uppercase())
            }
            if (!Settings.qualityAllowed(quality)) continue
            callback.invoke(
                newExtractorLink(name, "$name • Ok.Ru", stream, ExtractorLinkType.VIDEO) {
                    this.referer = "https://ok.ru/"
                    this.quality = quality
                }
            )
            found = true
        }
        return found
    }

    /** my.mail.ru gömme kabuğu: `/+/video/meta/<id>` `videos[]` mp4 URL'lerini verir. */
    private suspend fun loadMailRu(embed: String, callback: (ExtractorLink) -> Unit): Boolean {
        val id = MAIL_ID.find(embed)?.groupValues?.get(1) ?: return false
        val body = runCatching {
            app.get(
                "https://my.mail.ru/+/video/meta/$id",
                headers = mapOf("user-agent" to "Mozilla/5.0", "Referer" to "https://my.mail.ru/video/embed/$id"),
            ).text
        }.getOrNull() ?: return false
        val videos = runCatching { JSONObject(body).optJSONArray("videos") }.getOrNull() ?: return false

        var found = false
        for (i in 0 until videos.length()) {
            val item = videos.optJSONObject(i) ?: continue
            val raw = item.optString("url")
            val stream = if (raw.startsWith("//")) "https:$raw" else raw
            if (!stream.startsWith("http")) continue
            val label = item.optString("key").ifBlank { "MailRu" }
            callback.invoke(
                newExtractorLink(name, "$name • MailRu $label", stream, ExtractorLinkType.VIDEO) {
                    this.referer = "https://my.mail.ru/"
                    this.quality = getQualityFromName(label.uppercase())
                }
            )
            found = true
        }
        return found
    }

    private fun cleanTitle(raw: String): String? =
        raw.replace(YEAR, " ")
            .replace(Regex("""\s+[İi]zle\s*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { null }

    private fun hostRoot(url: String): String =
        HOST.find(url)?.value?.plus("/") ?: "$mainUrl/"

    companion object {
        private val GENRES = linkedMapOf(
            "Vizyon" to "vizyon",
            "Aksiyon" to "aksiyon",
            "Macera" to "macera",
            "Bilim Kurgu" to "bilim-kurgu",
            "Fantastik" to "fantastik",
            "Animasyon" to "animasyon",
            "Dram" to "dram",
            "Komedi" to "komedi",
            "Romantik" to "romantik",
            "Korku" to "korku",
            "Gerilim" to "gerilim",
            "Gizem" to "gizem",
            "Suç" to "suc",
            "Savaş" to "savas",
            "Tarih" to "tarih",
            "Belgesel" to "belgesel",
            "Aile" to "aile",
            "Gençlik" to "genclik",
            "Çocuk" to "cocuk",
            "Müzik" to "muzik",
            "Spor" to "spor",
            "TV Film" to "tv-film",
            "Hint" to "hint",
            "Kore" to "kore",
            "Western" to "western",
        )

        /** Mainpage rows in display order; ilk satır sitenin "Son Eklenen Filmler" listesidir. */
        val ROWS: List<String> = listOf("Son Eklenenler") + GENRES.keys.distinct()

        private const val CARD = "#listehizala .movie-box"
        private const val PAGE_SIZE = 20
        private const val ANDROID_UA = "Mozilla/5.0 (Linux; Android 13)"
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"

        private val YEAR = Regex("""\((\d{4})\)""")
        private val AT_OB = Regex("""atob\("([^"]+)"\)""")
        private val HOST = Regex("""https?://[^/]+""")
        private val OK_ID = Regex("""videoembed/(\d+)""")
        private val MAIL_ID = Regex("""/video/embed/(\d+)""")
        private val MIRROR_HOST = Regex("""vidmoly\.|vtbe\.|kpjdry""")
        private val JW_FILE = Regex("""file\s*:\s*['"](https?://[^'"]+\.m3u8[^'"]*)['"]""")
        private val M3U8 = Regex("""https?://[^"'\s<>]+\.m3u8[^"'\s<>]*""")
    }
}
