package com.selcukflix

import android.util.Base64
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Selcukflix — selcukflix.app.
 *
 * Site Next.js: her sayfa `#__NEXT_DATA__` içinde `pageProps.secureData` alanını taşır ve bu alan
 * AES-CBC (sabit anahtar, sıfır IV) ile şifrelenmiş JSON'dur; arama API'si de aynı zarfta cevap verir.
 * Oynatıcı iframe'i (pichive) `window.NP_CONFIG.sources[].master` altında bir HLS master m3u8 verir,
 * master ve segmentler yalnızca pichive origin'ini referer kabul eder.
 */
class SelcukFlixProvider : MainAPI() {
    override var mainUrl = "https://selcukflix.app"
    override var name = "SelcukFlix"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val pageHeaders = mapOf(
        "User-Agent" to UA,
        "Accept-Language" to "tr-TR,tr;q=0.9",
    )
    private val apiHeaders = pageHeaders + mapOf(
        "Accept" to "application/json, text/plain, */*",
        "X-Requested-With" to "XMLHttpRequest",
    )

    override val mainPage = mainPageOf(
        "$mainUrl/film-izle" to "Yeni Filmler",
        "$mainUrl/dizi-izle" to "Yeni Diziler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Liste sayfaları sunucuda sayfalanmıyor: ?page= ve ?sayfa= aynı 24 kaydı döndürüyor.
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val items = pageJson(request.data).optJSONArray("listItems").objects()
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val term = URLEncoder.encode(query.trim(), "UTF-8")
        val body = runCatching {
            app.post("$mainUrl/api/bg/searchcontent?searchterm=$term", headers = apiHeaders, referer = "$mainUrl/").text
        }.getOrNull() ?: throw ErrorLoadingException("$name: arama isteği başarısız. $REPORT_HINT")

        val sealed = runCatching { JSONObject(body).optString("response") }.getOrNull()
            ?: throw ErrorLoadingException("$name: arama yanıtı okunamadı. $REPORT_HINT")
        if (sealed.isBlank()) return emptyList()

        val json = runCatching { JSONObject(unseal(sealed)) }.getOrNull()
            ?: throw ErrorLoadingException("$name: arama yanıtı çözülemedi. $REPORT_HINT")
        return json.optJSONArray("result").objects()
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val json = pageJson(url)
        val item = json.optJSONObject("contentItem")
            ?: throw ErrorLoadingException("$name: içerik verisi okunamadı. $REPORT_HINT")
        val related = json.optJSONObject("RelatedResults")
        val title = item.displayTitle()
            ?: throw ErrorLoadingException("$name: içerik adı okunamadı. $REPORT_HINT")

        val poster = image(item.firstOf("poster_url", "face_url", "back_url"))
        val backdrop = image(item.firstOf("back_url", "poster_url", "face_url"))
        val plot = item.optString("description").takeIf { it.isNotBlank() && it != "null" }
        val year = item.optInt("release_year").takeIf { it > 0 }
        val tags = item.optString("categories").split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { null }
        val score = Score.from10(item.firstOf("imdb_point"))

        if (url.contains("/dizi/")) {
            val episodes = related?.optJSONObject("getSerieSeasonAndEpisodes")
                ?.optJSONArray("result").objects()
                .flatMap { season ->
                    val seasonNo = season.optInt("season_no").takeIf { it > 0 }
                    season.optJSONArray("episodes").objects().mapNotNull { episode ->
                        val slug = episode.optString("used_slug").takeIf { it.isNotBlank() }
                            ?: return@mapNotNull null
                        newEpisode("$mainUrl/$slug") {
                            this.name = episode.optString("episode_text").takeIf { it.isNotBlank() }
                                ?: "Bölüm ${episode.optInt("episode_no")}"
                            this.season = seasonNo
                            this.episode = episode.optInt("episode_no").takeIf { it > 0 }
                        }
                    }
                }
                .distinctBy { it.data }
                .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop ?: poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = score
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop ?: poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.score = score
            this.duration = item.optInt("total_minutes").takeIf { it > 0 }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val related = pageJson(data).optJSONObject("RelatedResults")
            ?: throw ErrorLoadingException("$name: kaynak verisi yok. $REPORT_HINT")

        // Bölüm sayfası getEpisodeSources, film sayfası her parça için getMoviePartSourcesById_<id> taşır.
        val entries = mutableListOf<JSONObject>()
        related.optJSONObject("getEpisodeSources")?.optJSONArray("result")?.let { entries += it.objects() }
        for (key in related.keys()) {
            if (!key.startsWith("getMoviePartSourcesById")) continue
            related.optJSONObject(key)?.optJSONArray("result")?.let { entries += it.objects() }
        }

        val emitted = mutableSetOf<String>()
        val embeds = mutableSetOf<String>()
        suspend fun emit(url: String, label: String, quality: Int, referer: String) {
            if (!emitted.add(url) || !Settings.qualityAllowed(quality)) return
            val type = if (url.contains(".mp4")) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
            callback(newExtractorLink(name, label, url, type) {
                this.referer = referer
                this.headers = mapOf("User-Agent" to UA)
                this.quality = quality
            })
        }
        // Master HLS'i varyantlarına ayırır; tek parça kalırsa master'ı kendisi olarak verir.
        suspend fun emitMaster(master: String, label: String, hint: Int) {
            val referer = ORIGIN.find(master)?.value?.plus("/") ?: "$mainUrl/"
            val playlist = runCatching {
                app.get(master, referer = referer, headers = pageHeaders).text
            }.getOrNull()
            val variants = playlist?.let { parseVariants(it, master) }.orEmpty()
            if (variants.isEmpty()) {
                emit(master, label, hint, referer)
            } else {
                variants.forEach { (height, url) -> emit(url, "$name ${height}p", height, referer) }
            }
        }

        for (entry in entries) {
            val iframe = IFRAME_SRC.find(entry.optString("source_content"))?.groupValues?.get(1) ?: continue
            val embed = absolute(iframe)
            // Aynı iframe birden çok dil kaydında tekrarlanıyor ve her çözümleme yeni bir master token üretiyor.
            if (!embeds.add(embed)) continue
            val language = entry.optString("language_name").takeIf { it.isNotBlank() }
            val label = listOfNotNull(name, language).joinToString(" ")
            // Kaynağın kendi etiketi ("1080P") sadece master tek parça kalırsa kullanılır.
            val hint = entry.optString("quality_name").filter { it.isDigit() }.toIntOrNull() ?: 0

            if (embed.contains(".mp4") || embed.contains(".m3u8")) {
                emit(embed, label, hint, "$mainUrl/")
                continue
            }

            val html = runCatching {
                app.get(embed, referer = "$mainUrl/", headers = pageHeaders).text
            }.getOrNull() ?: continue
            val config = npConfig(html)
            if (config != null) {
                for (source in config.optJSONArray("sources").objects()) {
                    val master = source.optString("master").takeIf { it.startsWith("http") } ?: continue
                    emitMaster(master, label, hint)
                }
            } else {
                // Eski oynatıcı: openPlayer('<token>') → source2.php → m.php adresi master.m3u8'e çevrilir.
                val token = OPEN_PLAYER.find(html)?.groupValues?.get(1) ?: continue
                val origin = ORIGIN.find(embed)?.value ?: continue
                val legacy = runCatching {
                    JSONObject(app.get("$origin/source2.php?v=$token", referer = embed, headers = pageHeaders).text)
                }.getOrNull() ?: continue
                val legacySources = legacy.optJSONArray("playlist")?.objects()
                    ?.flatMap { it.optJSONArray("sources").objects() }.orEmpty()
                for (source in legacySources) {
                    val file = source.optString("file").takeIf { it.startsWith("http") } ?: continue
                    emitMaster(file.replace("m.php", "master.m3u8"), label, hint)
                }
            }
        }

        if (emitted.isEmpty()) throw ErrorLoadingException("$name: oynatılabilir kaynak yok. $REPORT_HINT")
        return true
    }

    private suspend fun pageJson(url: String): JSONObject {
        val html = runCatching { app.get(url, headers = pageHeaders).text }.getOrNull()
            ?: throw ErrorLoadingException("$name: sayfa yüklenemedi. $REPORT_HINT")
        val sealed = SECURE_DATA.find(html)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("$name: içerik verisi bulunamadı. $REPORT_HINT")
        return runCatching { JSONObject(unseal(sealed)) }.getOrNull()
            ?: throw ErrorLoadingException("$name: içerik verisi çözülemedi. $REPORT_HINT")
    }

    /** `secureData` düz base64 JSON (`eyJ`) ya da AES-CBC zarfıdır. */
    private fun unseal(sealed: String): String {
        val bytes = Base64.decode(sealed, Base64.DEFAULT)
        if (sealed.startsWith("eyJ")) return String(bytes, Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(AES_KEY.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(ByteArray(16)),
        )
        return String(cipher.doFinal(bytes), Charsets.UTF_8)
    }

    /** Oynatıcı sayfasındaki `window.NP_CONFIG = {...}` gövdesi. */
    private fun npConfig(html: String): JSONObject? {
        val start = html.indexOf("NP_CONFIG")
        if (start < 0) return null
        val brace = html.indexOf('{', start)
        if (brace < 0) return null
        return runCatching { JSONTokener(html.substring(brace)).nextValue() as? JSONObject }.getOrNull()
    }

    /** Master m3u8'deki `#EXT-X-STREAM-INF` çiftleri: (yükseklik, varyant URL'si). */
    private fun parseVariants(playlist: String, base: String): List<Pair<Int, String>> {
        val variants = mutableListOf<Pair<Int, String>>()
        var height = 0
        for (line in playlist.lineSequence()) {
            val text = line.trim()
            when {
                text.startsWith("#EXT-X-STREAM-INF") ->
                    height = RESOLUTION.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0

                text.isEmpty() || text.startsWith("#") -> Unit

                height > 0 -> {
                    variants += height to runCatching { URI(base).resolve(text).toString() }.getOrDefault(text)
                    height = 0
                }
            }
        }
        return variants
    }

    private suspend fun JSONObject.toSearchResult(): SearchResponse? {
        val slug = optString("used_slug").takeIf { it.isNotBlank() } ?: return null
        val title = displayTitle() ?: return null
        val url = "$mainUrl/$slug"
        val poster = image(firstOf("poster_url", "object_poster_url", "face_url", "object_face_url"))
        val year = firstOf("release_year", "object_release_year")?.toIntOrNull()
        val score = Score.from10(firstOf("imdb_point", "object_related_imdb_point"))

        return if (slug.startsWith("dizi/")) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                this.score = score
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                this.score = score
            }
        }
    }

    private fun JSONObject.displayTitle(): String? =
        firstOf("culture_title", "object_name", "original_title", "object_alternative_name", "used_title")

    private fun JSONObject.firstOf(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        optString(key).takeIf { it.isNotBlank() && it != "null" }
    }

    /**
     * Görseller ampproject proxy'si ve `f/f/100` boyut yolu ile geliyor; site `images.macellan.online`
     * üzerinden `280/420/100` (afiş) ve `960/540/100` (fon) yollarını sunuyor.
     */
    private fun image(raw: String?): String? {
        val value = raw?.takeIf { it.isNotBlank() && it != "null" } ?: return null
        var url = value.replace(AMP_PREFIX, "")
        if (url.startsWith("//")) url = "https:$url"
        else if (!url.contains("://")) url = "https://$url"
        val size = if (url.contains("/backdrop/")) "960/540/100" else "280/420/100"
        return url.replace("/f/f/100/", "/$size/")
    }

    private fun absolute(raw: String): String = when {
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("http") -> raw
        raw.startsWith("/") -> "$mainUrl$raw"
        else -> raw
    }

    private fun JSONArray?.objects(): List<JSONObject> {
        val array = this ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val AES_KEY = "9bYMCNQiWsXIYFWYAu7EkdsSbmGBTyUI"
        private const val AMP_PREFIX = "https://images-macellan-online.cdn.ampproject.org/i/s/"
        private val SECURE_DATA = Regex(""""secureData"\s*:\s*"([^"]+)"""")
        private val IFRAME_SRC = Regex("""(?i)<iframe[^>]*\bsrc\s*=\s*["']([^"']+)""")
        private val OPEN_PLAYER = Regex("""openPlayer\(\s*'([^']+)'""")
        private val ORIGIN = Regex("^https?://[^/]+")
        private val RESOLUTION = Regex("""RESOLUTION=\d+x(\d+)""")
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}
