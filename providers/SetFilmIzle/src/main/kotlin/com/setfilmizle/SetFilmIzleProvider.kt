package com.setfilmizle

import android.util.Base64
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * SetFilmIzle — setfilmizle.ltd (WordPress, "setfilm" teması).
 *
 * Oynatıcı zinciri: film/bölüm sayfası `button.fsrc` üzerinde oynatıcı adı ve bölüm anahtarını,
 * `<script id="stf-ajax-bridge">` içinde AJAX nonce'unu taşır; `admin-ajax.php` →
 * `get_video_url` bir köprü sayfası döner (setplay.sbs). Köprü sayfası gerçek oynatıcı adresini
 * XOR'lanmış base64 çiftinde (`SPG.cerceve`) saklar. İkinci kat (fastplay.mom) `STF_KOPRU`
 * içinde HLS master yolunu ve altyazı listesini verir.
 *
 * Manifest kapısı: aynı adres masaüstü tarayıcı kimliğinde istek başına üretilen tek kullanımlık
 * bir `X-Sp` imzası istiyor (statik başlıkla oynatılamaz). Mobil/TV/okhttp kimliklerinde imza
 * aranmıyor; bu yüzden link mobil bir UA ile veriliyor. Segmentler yalnızca izinli origin'den
 * gelen `Referer` istiyor.
 */
class SetFilmIzleProvider : MainAPI() {
    override var mainUrl = "https://www.setfilmizle.ltd"
    override var name = "SetFilmIzle"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    companion object {
        /** Homepage rows in display order. */
        val ROWS = listOf(
            "Son Filmler",
            "Son Diziler",
            "Aksiyon",
            "Komedi",
            "Korku",
            "Dram",
            "Bilim Kurgu",
            "Animasyon",
        )
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        /** Manifest kapısı masaüstü tarayıcı kimliğinde imza istediği için oynatma mobil kimlikle yapılır. */
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }

    private val uaHeaders = mapOf(
        "User-Agent" to UA,
        "Accept-Language" to "tr-TR,tr;q=0.9",
    )

    override val mainPage get() = ROWS.mapNotNull { name ->
        if (!Settings.rowEnabled(name)) return@mapNotNull null
        val data = when (name) {
            "Son Filmler" -> "$mainUrl/film/page/SAYFA/"
            "Son Diziler" -> "$mainUrl/dizi/page/SAYFA/"
            "Aksiyon" -> "$mainUrl/tur/aksiyon/film/page/SAYFA/"
            "Komedi" -> "$mainUrl/tur/komedi/film/page/SAYFA/"
            "Korku" -> "$mainUrl/tur/korku/film/page/SAYFA/"
            "Dram" -> "$mainUrl/tur/dram/film/page/SAYFA/"
            "Bilim Kurgu" -> "$mainUrl/tur/bilim-kurgu/film/page/SAYFA/"
            "Animasyon" -> "$mainUrl/tur/animasyon/film/page/SAYFA/"
            else -> return@mapNotNull null
        }
        MainPageData(name, data)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data.replace("SAYFA", page.toString()), headers = uaHeaders).document
        return newHomePageResponse(
            request.name,
            doc.listCards(),
            hasNext = doc.selectFirst("a[href*='/page/${page + 1}/']") != null,
        )
    }

    /** Site aramayı tek sayfada döndürür (`?s=` sonuçları sayfalanmaz), bu yüzden sayfa parametresi yok. */
    override suspend fun search(query: String): List<SearchResponse> {
        val term = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/?s=$term", headers = uaHeaders).document
        return doc.listCards()
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = try {
            app.get(url, headers = uaHeaders).document
        } catch (e: Exception) {
            throw ErrorLoadingException("$name: sayfa yüklenemedi. $REPORT_HINT")
        }
        val title = doc.selectFirst("h1.fbox-title .fbox-title-tx")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: başlık okunamadı. $REPORT_HINT")

        val poster = fixUrlNull(doc.selectFirst("img.fbox-cover-img")?.attr("src"))
        val plot = doc.select("div.fbox-desc p").joinToString("\n") { it.text().trim() }
            .takeIf { it.isNotBlank() }
        val year = Regex("""\d{4}""").find(doc.selectFirst("span.fbox-date")?.text().orEmpty())
            ?.value?.toIntOrNull()
        val score = Score.from10(doc.selectFirst("b.imdb-score")?.text())
        val duration = Regex("""(\d+)\s*Dakika""")
            .find(doc.selectFirst("div.fbox-info")?.text().orEmpty())?.groupValues?.get(1)?.toIntOrNull()
        val tags = doc.select("div.fbox-info a[href*='/tur/']").map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct()
        val actors = doc.select("div.fbox-kadro a.fk-k").mapNotNull { link ->
            val person = link.selectFirst("b")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            if (link.selectFirst("i")?.text()?.trim() == "Yönetmen") return@mapNotNull null
            ActorData(Actor(person))
        }
        val trailer = doc.selectFirst("button.stf-trailer-btn[data-trailer]")
            ?.attr("data-trailer")?.trim()?.takeIf { it.isNotBlank() }
        val recommendations = doc.recommendations()

        if (url.contains("/dizi/")) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, doc.episodes()) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = score
                this.duration = duration
                this.tags = tags
                this.actors = actors.takeIf { it.isNotEmpty() }
                this.recommendations = recommendations
                trailer?.let { addTrailer("https://www.youtube.com/embed/$it") }
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.score = score
            this.duration = duration
            this.tags = tags
            this.actors = actors.takeIf { it.isNotEmpty() }
            this.recommendations = recommendations
            trailer?.let { addTrailer("https://www.youtube.com/embed/$it") }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCine: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val page = try {
            app.get(data, headers = uaHeaders).text
        } catch (e: Exception) {
            throw ErrorLoadingException("$name: oynatıcı sayfası yüklenemedi. $REPORT_HINT")
        }
        val postId = Regex("""id="stfPlayer"\s+data-post-id="(\d+)"""").find(page)
            ?.groupValues?.get(1)
            ?: throw ErrorLoadingException("$name: oynatıcı kimliği bulunamadı. $REPORT_HINT")
        val nonce = Regex("""video:\s*"([^"]+)"""").find(page)?.groupValues?.get(1).orEmpty()
        val players = Regex("""data-player-name="([^"]*)"\s+data-part-key="([^"]*)"""")
            .findAll(page).map { it.groupValues[1] to it.groupValues[2] }
            .toList().ifEmpty { listOf("SetPlay" to "") }

        var found = false
        var subtitlesSent = false
        for ((playerName, partKey) in players.distinct()) {
            val stream = requestStream(data, nonce, postId, playerName, partKey) ?: continue
            if (stream.optString("type") != "bridge") continue
            val bridgeUrl = stream.optString("url").takeIf { it.startsWith("http") } ?: continue
            val playerUrl = resolveBridge(bridgeUrl, data) ?: continue
            val source = readPlayerSource(playerUrl) ?: continue

            if (!subtitlesSent) {
                // Aynı dil için dolu ve "zorunlu" parça ayrı ayrı geliyor; dolu olanı ilk sırada.
                source.subtitles.distinctBy { it.label }.forEach { subtitle ->
                    if (!Settings.subtitleAllowed("${subtitle.label} ${subtitle.code}")) return@forEach
                    subtitleCallback.invoke(newSubtitleFile(subtitle.label, subtitle.url))
                }
                subtitlesSent = true
            }

            callback.invoke(
                newExtractorLink(name, playerName.ifBlank { "SetPlay" }, source.masterUrl, ExtractorLinkType.M3U8) {
                    this.referer = playerUrl
                    this.headers = mapOf("User-Agent" to MOBILE_UA, "Accept" to "*/*")
                    // Tek video varyantı var; kalite filtresi tek kaynağı elemesin.
                    this.quality = Qualities.Unknown.value
                }
            )
            found = true
        }
        if (!found) throw ErrorLoadingException("$name: oynatıcı kaynağı alınamadı. $REPORT_HINT")
        return true
    }

    private fun Document.listCards(): List<SearchResponse> =
        select("a.card-link[href]").mapNotNull { it.toSearchResult() }.distinctBy { it.url }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val title = selectFirst(".card-ad")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val poster = fixUrlNull(selectFirst("img[src]")?.attr("src"))
        val year = Regex("""\b(?:19|20)\d{2}\b""").find(selectFirst("p.meta")?.text().orEmpty())
            ?.value?.toIntOrNull()
        val score = Score.from10(selectFirst("span.badge-imdb")?.text())
        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                this.score = score
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                this.score = score
            }
        }
    }

    private fun Document.episodes(): List<Episode> {
        val seen = mutableSetOf<String>()
        val list = mutableListOf<Episode>()
        for (panel in select("div.season-panel[data-season]")) {
            val season = panel.attr("data-season").toIntOrNull() ?: 1
            for (link in panel.select("a.fep[href]")) {
                val href = fixUrlNull(link.attr("href"))?.takeIf { it.contains("/bolum/") } ?: continue
                if (!seen.add(href)) continue
                val number = Regex("""-(\d+)-bolum/?$""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                    ?: continue
                list += newEpisode(href) {
                    this.season = season
                    this.episode = number
                    this.name = link.selectFirst(".fep-title")?.text()?.trim()?.takeIf { it.isNotBlank() }
                        ?: "$season. Sezon $number. Bölüm"
                    this.posterUrl = fixUrlNull(link.selectFirst("img.fep-cover")?.attr("src"))
                }
            }
        }
        return list.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
    }

    private fun Document.recommendations(): List<SearchResponse> {
        val head = select("div.section-head").firstOrNull {
            it.selectFirst("h2")?.text()?.contains("İlginizi Çekebilir") == true
        } ?: return emptyList()
        return head.nextElementSibling()?.select("a.card-link[href]")
            ?.mapNotNull { it.toSearchResult() }?.distinctBy { it.url }.orEmpty()
    }

    /** `admin-ajax.php` → `get_video_url`: oynatıcıya ait akış tanımını döner. */
    private suspend fun requestStream(
        pageUrl: String,
        nonce: String,
        postId: String,
        playerName: String,
        partKey: String,
    ): JSONObject? {
        val body = try {
            app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "get_video_url",
                    "nonce" to nonce,
                    "post_id" to postId,
                    "player_name" to playerName,
                    "part_key" to partKey,
                    // Önbellekteki eski imzayı atlayıp taze akış adresi istiyoruz.
                    "taze" to "1",
                ),
                headers = uaHeaders + mapOf("X-Requested-With" to "XMLHttpRequest"),
                referer = pageUrl,
            ).text
        } catch (e: Exception) {
            null
        } ?: return null
        val data = runCatching { JSONObject(body).optJSONObject("data") }.getOrNull() ?: return null
        return data.optJSONObject("stream")
    }

    /** Köprü sayfası gerçek oynatıcı adresini iki base64 metnin XOR'unda tutar. */
    private suspend fun resolveBridge(bridgeUrl: String, referer: String): String? {
        val html = try {
            app.get(bridgeUrl, headers = uaHeaders, referer = referer).text
        } catch (e: Exception) {
            null
        } ?: return null
        val match = Regex("""cerceve\(\s*"b2"\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\)""").find(html)
            ?: return null
        return xorDecode(match.groupValues[1], match.groupValues[2])
            .split("|").firstOrNull { it.startsWith("http") }
    }

    private suspend fun readPlayerSource(playerUrl: String): PlayerSource? {
        val html = try {
            app.get(playerUrl, headers = uaHeaders, referer = "https://setplay.sbs/").text
        } catch (e: Exception) {
            null
        } ?: return null
        val path = Regex("""src:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            ?.replace("\\/", "/") ?: return null
        val origin = Regex("""^(https?://[^/]+)""").find(playerUrl)?.value ?: return null
        val subtitles = Regex("""subtitles:\s*(\[.*?])\s*,""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1).let { parseSubtitles(it) }
        return PlayerSource("$origin$path", subtitles)
    }

    private fun parseSubtitles(json: String?): List<PlayerSubtitle> {
        if (json.isNullOrBlank()) return emptyList()
        val array = runCatching { org.json.JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val kind = item.optString("kind")
            if (kind.isNotBlank() && kind != "captions") return@mapNotNull null
            val url = item.optString("file").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val label = item.optString("label").takeIf { it.isNotBlank() }
                ?: item.optString("lang").takeIf { it.isNotBlank() } ?: "Altyazı"
            PlayerSubtitle(label, item.optString("lang"), url)
        }
    }

    private fun xorDecode(first: String, second: String): String {
        val a = Base64.decode(first.replace("\\/", "/"), Base64.DEFAULT)
        val b = Base64.decode(second.replace("\\/", "/"), Base64.DEFAULT)
        if (a.isEmpty() || b.isEmpty()) return ""
        return String(ByteArray(a.size) { i -> (a[i].toInt() xor b[i % b.size].toInt()).toByte() }, Charsets.UTF_8)
    }

    private data class PlayerSource(
        val masterUrl: String,
        val subtitles: List<PlayerSubtitle>,
    )

    private data class PlayerSubtitle(val label: String, val code: String, val url: String)
}
