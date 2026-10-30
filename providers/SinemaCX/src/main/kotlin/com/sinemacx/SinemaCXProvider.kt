package com.sinemacx

import android.util.Base64
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Element

/**
 * SinemaCX — sinemacc.com (Türk film/dublaj arşivi, "Sinema" teması).
 *
 * Oynatıcı zinciri: film sayfasındaki `button.part_button` düğmeleri iframe adresini base64
 * `rel` içinde taşır (player.filmizle.in/video/<id>?lng=...). Aynı origin'deki panel
 * `POST /player/index.php?data=<id>&do=getVideo` çağrısına `securedLink` (HLS master m3u8)
 * döner; altyazı listesi oynatıcı sayfasındaki `playerjsSubtitle` değişkenindedir.
 */
class SinemaCXProvider : MainAPI() {
    override var mainUrl = "https://sinemacc.com"
    override var name = "SinemaCX"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    private val uaHeaders = mapOf(
        "User-Agent" to UA,
        "Accept-Language" to "tr-TR,tr;q=0.9",
    )

    override val mainPage get() = ROWS.mapNotNull { name ->
        if (!Settings.rowEnabled(name)) return@mapNotNull null
        val data = when (name) {
            "Son Eklenen Filmler" -> "$mainUrl/page/SAYFA/"
            "Aksiyon" -> "$mainUrl/tur/aksiyon-filmleri/page/SAYFA/"
            "Komedi" -> "$mainUrl/tur/komedi-filmleri/page/SAYFA/"
            "Korku" -> "$mainUrl/tur/korku-filmleri/page/SAYFA/"
            "Bilim Kurgu" -> "$mainUrl/tur/bilim-kurgu-filmleri/page/SAYFA/"
            "Animasyon" -> "$mainUrl/tur/animasyon-filmleri/page/SAYFA/"
            "Gerilim" -> "$mainUrl/tur/gerilim-filmleri/page/SAYFA/"
            "Dram" -> "$mainUrl/tur/dram-filmleri/page/SAYFA/"
            "Belgesel" -> "$mainUrl/tur/belgesel/page/SAYFA/"
            else -> return@mapNotNull null
        }
        MainPageData(name, data)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data.replace("SAYFA", page.toString()), headers = uaHeaders).document
        return newHomePageResponse(
            request.name,
            doc.select("div.film_kutusu > a[href*='/film/']").mapNotNull { it.toSearchResult() }
                .distinctBy { it.url },
            hasNext = doc.select("a[href*='/page/${page + 1}/']").isNotEmpty(),
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Arama yalnızca AJAX ile çalışıyor; sonuç sayfası istemci tarafında dolduruluyor.
        val doc = app.post(
            "$mainUrl/arama/",
            data = mapOf("action" to "ajax_search", "arama_kelime" to query),
            headers = uaHeaders + mapOf("X-Requested-With" to "XMLHttpRequest"),
            referer = "$mainUrl/",
        ).document
        return doc.select("a.arama_s[href]").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val title = selectFirst("span.title span.text")?.text()?.trim()
            ?: selectFirst("span.title")?.text()?.trim()
        if (title.isNullOrBlank()) return null
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = imageUrl()
            this.year = selectFirst("span.years")?.text()?.take(4)?.toIntOrNull()
                ?: selectFirst("span.year")?.text()?.trim()?.toIntOrNull()
            this.score = Score.from10(selectFirst("span.imdb")?.text())
        }
    }

    /** Posterler `src` içinde placeholder data URI taşır; gerçek adres `data-src`'de. */
    private fun Element.imageUrl(): String? =
        selectFirst("img[data-src]")?.attr("data-src")?.takeIf { it.startsWith("http") }
            ?: selectFirst("img[src]")?.attr("src")?.takeIf { it.startsWith("http") }

    override suspend fun load(url: String): LoadResponse? {
        val response = runCatching { app.get(url, headers = uaHeaders) }.getOrNull()
            ?: throw ErrorLoadingException("$name: film sayfası yüklenemedi. $REPORT_HINT")
        val doc = response.document
        val schema = movieSchema(doc)
        val title = schema?.optString("name")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("h1.baslik")?.text()?.trim()?.removeSuffix("İzle")?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: film bilgisi okunamadı. $REPORT_HINT")
        // /arama/?fsid= yönlendirmesi gerçek film adresine düşer; kanonik adresi sakla.
        val filmUrl = response.url.takeIf { it.contains("/film/") } ?: url
        val actors = schema?.optJSONArray("actor")?.let { array ->
            (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }
            }
        }.orEmpty()

        return newMovieLoadResponse(title, filmUrl, TvType.Movie, filmUrl) {
            this.posterUrl = schema?.optString("image")?.takeIf { it.startsWith("http") }
                ?: fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
            this.plot = schema?.optString("description")?.takeIf { it.isNotBlank() }
            this.year = schema?.optString("dateCreated")?.take(4)?.toIntOrNull()
            this.duration = Regex("""PT(\d+)M""").find(schema?.optString("duration").orEmpty())
                ?.groupValues?.get(1)?.toIntOrNull()
            this.score = Score.from10(
                schema?.optJSONObject("aggregateRating")?.optString("ratingValue")
            )
            this.tags = doc.select("a[href*='/tur/']").map { it.text().trim() }
                .filter { it.isNotEmpty() }.distinct()
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
        val doc = runCatching { app.get(data, headers = uaHeaders).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: film sayfası yüklenemedi. $REPORT_HINT")

        // Aynı video kimliği dublaj/altyazı sekmelerinde tekrar eder; her kimlik bir kez denenir.
        val players = doc.select("button.part_button[rel]").mapNotNull { button ->
            val embed = decodeRel(button.attr("rel")) ?: return@mapNotNull null
            if (embed.contains("youtube", true) || embed.contains("youtu.be", true)) return@mapNotNull null
            val id = embed.substringBefore('?').trimEnd('/').substringAfterLast('/')
                .takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Triple(id, embed, button.text().trim().ifBlank { "Kaynak" })
        }.distinctBy { it.first }
        if (players.isEmpty()) throw ErrorLoadingException("$name: oynatıcı bulunamadı. $REPORT_HINT")

        val seenSubtitles = mutableSetOf<String>()
        var found = false
        for ((id, embed, label) in players) {
            val origin = Regex("""^(https?://[^/]+)""").find(embed)?.groupValues?.get(1) ?: continue
            val playerHtml = runCatching { app.get(embed, referer = data, headers = uaHeaders).text }
                .getOrNull()
            if (playerHtml != null) emitSubtitles(playerHtml, embed, seenSubtitles, subtitleFilter)

            val body = runCatching {
                app.post(
                    "$origin/player/index.php?data=$id&do=getVideo",
                    data = mapOf("hash" to id, "r" to "$mainUrl/"),
                    headers = uaHeaders + mapOf("X-Requested-With" to "XMLHttpRequest"),
                    referer = embed,
                ).text
            }.getOrNull() ?: continue

            val panel = runCatching { JSONObject(body) }.getOrNull() ?: continue
            val link = panel.optString("securedLink").takeIf { it.startsWith("http") } ?: continue
            val type = if (panel.optBoolean("hls") || link.contains(".m3u8")) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }

            linkCallback.invoke(
                newExtractorLink(name, label, link, type) {
                    this.referer = "$origin/"
                    this.quality = Qualities.Unknown.value
                }
            )
            found = true
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir kaynak yok. $REPORT_HINT")
        return true
    }

    /** `playerjsSubtitle = "[Türkçe]https://...,[English]https://..."` */
    private fun emitSubtitles(
        playerHtml: String,
        playerUrl: String,
        seen: MutableSet<String>,
        emit: (SubtitleFile) -> Unit,
    ) {
        val value = Regex("""playerjsSubtitle\s*=\s*"(.*?)";""", RegexOption.DOT_MATCHES_ALL)
            .find(playerHtml)?.groupValues?.get(1) ?: return
        for (match in Regex("""\[([^\]]+)\](https?://[^",\s]+)""").findAll(value)) {
            val language = match.groupValues[1].trim().ifBlank { "Altyazı" }
            val url = match.groupValues[2]
            if (!seen.add(url)) continue
            // Dosyalar .jpg uzantısı arkasında düz WebVTT olarak servis ediliyor.
            emit(SubtitleFile(language, url).apply { this.headers = mapOf("Referer" to playerUrl) })
        }
    }

    /** Part düğmesinin `rel` alanı base64 kodlu iframe adresidir. */
    private fun decodeRel(value: String): String? {
        val raw = value.trim()
        if (raw.startsWith("http")) return raw
        if (raw.isEmpty()) return null
        val padded = raw + "=".repeat((4 - raw.length % 4) % 4)
        return runCatching { String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8) }
            .getOrNull()?.trim()?.takeIf { it.startsWith("http") }
    }

    /** Film sayfasındaki Movie düğümü (@type Movie) tüm künyeyi taşır. */
    private fun movieSchema(doc: org.jsoup.nodes.Document): JSONObject? {
        for (script in doc.select("script[type=application/ld+json]")) {
            val node = runCatching { JSONObject(script.data()) }.getOrNull() ?: continue
            if (node.optString("@type") == "Movie") return node
        }
        return null
    }

    companion object {
        /** Homepage rows in display order. */
        val ROWS = listOf(
            "Son Eklenen Filmler",
            "Aksiyon",
            "Komedi",
            "Korku",
            "Bilim Kurgu",
            "Animasyon",
            "Gerilim",
            "Dram",
            "Belgesel",
        )
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}
