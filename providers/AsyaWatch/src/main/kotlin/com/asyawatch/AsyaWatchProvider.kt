package com.asyawatch

import android.util.Base64
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * AsyaWatch — asyawatch.com (Kore/Asya web dramaları, Türkçe altyazı & dublaj).
 *
 * Site Next.js + aynı panel ailesi: liste ve arama `/api/bg/…` altında POST + query parametreleriyle
 * çalışır ve `response` alanı base64 JSON zarfıdır. Her sayfa `#__NEXT_DATA__` içindeki
 * `pageProps.secureData` alanında aynı zarfı taşır; bölüm/film künyesi ve kaynak listesi oradan okunur.
 *
 * Oynatıcı iki embed biçimi üretiyor: yeni oynatıcı `window.NP_CONFIG` (sources[].master HLS master +
 * subtitles[] VTT), eski oynatıcı `openPlayer('<token>')` → `source2.php` → `m.php` master.
 * Embed'ler yalnız asyawatch.com referer'ı ile, master/VTT ise yalnız oynatıcı origin'i referer'ı ile
 * açılıyor. NP master'ı ayrı bir ses rendition grubu taşıdığı için varyant listeleri sessiz kalır;
 * bu yüzden tek link olarak master verilir.
 */
class AsyaWatchProvider : MainAPI() {
    override var mainUrl = "https://asyawatch.com"
    override var name = "AsyaWatch"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val uaHeaders = mapOf("User-Agent" to UA)
    private val apiHeaders = uaHeaders + mapOf(
        "Accept" to "application/json, text/plain, */*",
        "X-Requested-With" to "XMLHttpRequest",
    )

    override val mainPage = mainPageOf(
        "$mainUrl/dizi-izle" to "Son Bölümler",
        "$mainUrl/film-izle" to "Son Filmler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val movies = request.data.contains("film")
        val json = if (movies) {
            api("findMovies", MOVIE_PARAMS + ("currentPage" to page.toString()))
        } else {
            api(
                "getEpisodesOnBrand",
                mapOf(
                    "curPage" to page.toString(),
                    "curLength" to PAGE_SIZE.toString(),
                    "languageId" to "2,3,4",
                    "typeId" to "1",
                ),
            )
        }
        val rows = json.optJSONArray("result").objects()
        val items = rows.mapNotNull { if (movies) it.toMovieCard() else it.toSeriesCard() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = rows.size >= PAGE_SIZE)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        // Sunucu aramayı sayfalamıyor: `page` yok sayılıp ilk 30 kayıt dönüyor.
        if (page > 1) return newSearchResponseList(emptyList(), false)
        val json = api("searchContent", mapOf("searchterm" to query.trim()))
        val items = json.optJSONArray("result").objects()
            .mapNotNull { it.toSearchCard() }
            .distinctBy { it.url }
        return newSearchResponseList(items, false)
    }

    override suspend fun load(url: String): LoadResponse? {
        val json = pageJson(url)
        val item = json.optJSONObject("contentItem")
            ?: throw ErrorLoadingException("$name: içerik verisi okunamadı. $REPORT_HINT")
        val related = json.optJSONObject("RelatedResults")
        val title = item.firstOf("culture_title", "original_title")
            ?: throw ErrorLoadingException("$name: içerik adı okunamadı. $REPORT_HINT")

        val poster = image(item.firstOf("poster_url", "face_url"))
        val backdrop = image(item.firstOf("back_url"))
        val plot = item.optString("description").takeIf { it.isNotBlank() && it != "null" }
        val year = item.optInt("release_year").takeIf { it > 0 }
        val duration = item.optInt("total_minutes").takeIf { it > 0 }
        val tags = item.optString("categories").split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { null }
        val actors = (related?.optJSONObject("getSerieCastsById") ?: related?.optJSONObject("getMovieCastsById"))
            ?.optJSONArray("result").objects()
            .mapNotNull { it.optString("name").takeIf { name -> name.isNotBlank() } }
            .distinct()
        val trailer = related?.optJSONObject("getContentTrailers")
            ?.optJSONArray("result").objects()
            .firstOrNull()?.optString("raw_url")
            ?.takeIf { it.startsWith("http") }

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
                this.score = item.rating()
                addActors(actors)
                trailer?.let { addTrailer(it) }
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop ?: poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.score = item.rating()
            this.duration = duration
            addActors(actors)
            trailer?.let { addTrailer(it) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val related = pageJson(data).optJSONObject("RelatedResults")
            ?: throw ErrorLoadingException("$name: kaynak verisi bulunamadı. $REPORT_HINT")

        // Bölüm sayfası getEpisodeSources, film sayfası parça başına getMoviePartSourcesById_<id> taşır;
        // ikisini de aynı tarama toplar. Aynı iframe birden çok dil kaydında tekrarlanabiliyor.
        val embeds = linkedMapOf<String, String?>()
        for (key in related.keys()) {
            related.optJSONObject(key)?.optJSONArray("result").objects().forEach { entry ->
                val embed = IFRAME_SRC.find(entry.optString("source_content"))
                    ?.groupValues?.get(1)?.let { absolute(it) } ?: return@forEach
                if (embeds.containsKey(embed)) return@forEach
                embeds[embed] = entry.optString("language_name")
                    .takeIf { it.isNotBlank() && it != "null" }
            }
        }
        if (embeds.isEmpty()) throw ErrorLoadingException("$name: oynatıcı adresi bulunamadı. $REPORT_HINT")

        val plays = mutableListOf<Play>()
        val subtitles = mutableSetOf<String>()
        for ((embed, language) in embeds) {
            val origin = ORIGIN.find(embed)?.value ?: continue
            // Embed yalnız site referer'ı ile açılıyor; master ve altyazı ise oynatıcı origin'ini istiyor.
            val html = runCatching { app.get(embed, headers = uaHeaders, referer = "$mainUrl/").text }
                .getOrNull() ?: continue
            val config = npConfig(html)
            if (config != null) {
                for (source in config.optJSONArray("sources").objects()) {
                    val master = source.optString("master").takeIf { it.startsWith("http") } ?: continue
                    addMaster(plays, master, "$origin/", language)
                }
                for (subtitle in config.optJSONArray("subtitles").objects()) {
                    val subUrl = subtitle.optString("url").takeIf { it.startsWith("http") } ?: continue
                    val label = subtitle.optString("label").takeIf { it.isNotBlank() } ?: continue
                    if (!subtitles.add(subUrl) || !Settings.subtitleAllowed(label)) continue
                    subtitleCallback.invoke(SubtitleFile(subUrl, label).apply {
                        headers = mapOf("Referer" to "$origin/", "User-Agent" to UA)
                    })
                }
            } else {
                // Eski oynatıcı: openPlayer('<token>') → source2.php → m.php master.
                val token = OPEN_PLAYER.find(html)?.groupValues?.get(1) ?: continue
                val legacy = runCatching {
                    JSONObject(app.get("$origin/source2.php?v=$token", headers = uaHeaders, referer = embed).text)
                }.getOrNull() ?: continue
                legacy.optJSONArray("playlist").objects()
                    .flatMap { it.optJSONArray("sources").objects() }
                    .forEach { source ->
                        val file = source.optString("file").takeIf { it.startsWith("http") } ?: return@forEach
                        addMaster(plays, file, "$origin/", language)
                    }
            }
        }
        if (plays.isEmpty()) throw ErrorLoadingException("$name: oynatılabilir kaynak bulunamadı. $REPORT_HINT")

        val allowed = plays.filter { Settings.qualityAllowed(it.quality) }
        // Kalite filtresi hepsini elediyse en düşük kaliteli kaynak tek çıkış olarak verilir.
        val chosen = allowed.ifEmpty { listOfNotNull(plays.minByOrNull { it.quality }) }
        chosen.distinctBy { it.url }.forEach { play ->
            val type = if (play.url.contains(".mp4")) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
            callback(
                newExtractorLink(name, play.label, play.url, type) {
                    this.referer = play.referer
                    this.headers = mapOf("User-Agent" to UA)
                    this.quality = play.quality
                }
            )
        }
        return true
    }

    // ---------------------------------------------------------------- liste/künye

    /** Ana sayfa "Son Bölümler" satırı: bölüm kaydından dizi adresi (`dizi/<slug>`) türetilir. */
    private fun JSONObject.toSeriesCard(): SearchResponse? {
        val slug = optString("episode_used_slug").takeIf { it.isNotBlank() } ?: return null
        val title = firstOf("culture_title", "original_title") ?: return null
        return newTvSeriesSearchResponse(title, "$mainUrl/${slug.substringBefore("/sezon-")}", TvType.TvSeries) {
            this.posterUrl = image(firstOf("poster_url", "face_url"))
            this.year = optInt("release_year").takeIf { it > 0 }
            this.score = rating()
        }
    }

    private fun JSONObject.toMovieCard(): SearchResponse? {
        val slug = optString("used_slug").takeIf { it.isNotBlank() } ?: return null
        val title = firstOf("culture_title", "original_title") ?: return null
        return newMovieSearchResponse(title, "$mainUrl/$slug", TvType.Movie) {
            this.posterUrl = image(firstOf("poster_url", "face_url"))
            this.year = optInt("release_year").takeIf { it > 0 }
            this.score = rating()
        }
    }

    private fun JSONObject.toSearchCard(): SearchResponse? {
        val slug = optString("used_slug").takeIf { it.isNotBlank() } ?: return null
        val title = firstOf("object_alternative_name", "object_name") ?: return null
        val poster = image(firstOf("object_poster_url", "poster_url"))
        val year = firstOf("object_release_year", "release_year")?.toIntOrNull()
        val score = Score.from10(firstOf("object_related_imdb_point", "imdb_point"))
        return if (optString("used_type") == "Series") {
            newTvSeriesSearchResponse(title, "$mainUrl/$slug", TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                this.score = score
            }
        } else {
            newMovieSearchResponse(title, "$mainUrl/$slug", TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                this.score = score
            }
        }
    }

    // ---------------------------------------------------------------- oynatıcı

    /**
     * Master m3u8 tek link olarak toplanır: NP master'ı ses parçasını `#EXT-X-MEDIA` grubunda tutuyor,
     * varyant listelerini ayrı vermek sesi kaybettirir. Master okunamazsa kaynak atlanır.
     */
    private suspend fun addMaster(
        target: MutableList<Play>,
        master: String,
        referer: String,
        language: String?,
    ) {
        val playlist = runCatching { app.get(master, headers = uaHeaders, referer = referer).text }
            .getOrNull()?.takeIf { it.startsWith("#EXTM3U") } ?: return
        val height = RESOLUTION.findAll(playlist)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .maxOrNull() ?: 0
        val label = listOfNotNull(name, language, height.takeIf { it > 0 }?.let { "${it}p" })
            .joinToString(" ")
        target += Play(master, label, height, referer)
    }

    /** `#__NEXT_DATA__` → `pageProps.secureData` zarfı (base64 JSON). */
    private suspend fun pageJson(url: String): JSONObject {
        val html = runCatching { app.get(url, headers = uaHeaders).text }.getOrNull()
            ?: throw ErrorLoadingException("$name: sayfa yüklenemedi. $REPORT_HINT")
        return nextData(html)
            ?: throw ErrorLoadingException("$name: sayfa verisi okunamadı. $REPORT_HINT")
    }

    private suspend fun api(path: String, params: Map<String, String>): JSONObject {
        val query = params.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        val body = runCatching {
            app.post("$mainUrl/api/bg/$path?$query", headers = apiHeaders, referer = "$mainUrl/").text
        }.getOrNull() ?: throw ErrorLoadingException("$name: istek başarısız oldu. $REPORT_HINT")
        val sealed = runCatching { JSONObject(body).optString("response") }.getOrNull()
            ?: throw ErrorLoadingException("$name: yanıt okunamadı. $REPORT_HINT")
        if (sealed.isBlank()) return JSONObject()
        return runCatching { JSONObject(unseal(sealed)) }.getOrNull()
            ?: throw ErrorLoadingException("$name: yanıt çözülemedi. $REPORT_HINT")
    }

    private fun nextData(html: String): JSONObject? {
        val sealed = SECURE_DATA.find(html)?.groupValues?.get(1) ?: return null
        return runCatching { JSONObject(unseal(sealed)) }.getOrNull()
    }

    private fun unseal(sealed: String): String {
        val text = sealed.trim()
        val padded = text + "=".repeat((4 - text.length % 4) % 4)
        return String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
    }

    /** Oynatıcı sayfasındaki `window.NP_CONFIG = {...}` gövdesi. */
    private fun npConfig(html: String): JSONObject? {
        val start = html.indexOf("NP_CONFIG")
        if (start < 0) return null
        val brace = html.indexOf('{', start)
        if (brace < 0) return null
        return runCatching { JSONTokener(html.substring(brace)).nextValue() as? JSONObject }.getOrNull()
    }

    // ---------------------------------------------------------------- yardımcı

    private fun JSONObject.rating(): Score? {
        val points = optDouble("vote_point", 0.0).takeIf { it > 0 }
            ?: optDouble("imdb_point", 0.0).takeIf { it > 0 }
        return points?.let { Score.from10(it) }
    }

    private fun JSONObject.firstOf(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        optString(key).takeIf { it.isNotBlank() && it != "null" }
    }

    private fun image(raw: String?): String? =
        raw?.let { if (it.startsWith("//")) "https:$it" else it }

    private fun absolute(raw: String): String = when {
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("http") -> raw
        raw.startsWith("/") -> "$mainUrl$raw"
        else -> "$mainUrl/$raw"
    }

    private fun JSONArray?.objects(): List<JSONObject> {
        val array = this ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    private data class Play(val url: String, val label: String, val quality: Int, val referer: String)

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val PAGE_SIZE = 18
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"

        /** `findMovies` filtresiz gövdesi; site bu alanların hepsini bekliyor. */
        private val MOVIE_PARAMS = mapOf(
            "releaseYearStart" to "-1",
            "releaseYearEnd" to "-1",
            "imdbPointMin" to "-1",
            "imdbPointMax" to "-1",
            "categoryIdsComma" to "",
            "countryIdsComma" to "",
            "orderType" to "date_desc",
            "languageId" to "-1",
            "currentPageCount" to PAGE_SIZE.toString(),
            "queryStr" to "",
            "categorySlugsComma" to "",
            "countryCodesComma" to "",
        )

        private val SECURE_DATA = Regex(""""secureData"\s*:\s*"([^"]+)"""")
        private val IFRAME_SRC = Regex("""(?i)<iframe[^>]*\bsrc\s*=\s*["']([^"']+)""")
        private val OPEN_PLAYER = Regex("""openPlayer\(\s*'([^']+)'""")
        private val ORIGIN = Regex("^https?://[^/]+")
        private val RESOLUTION = Regex("""RESOLUTION=\d+x(\d+)""")
    }
}
