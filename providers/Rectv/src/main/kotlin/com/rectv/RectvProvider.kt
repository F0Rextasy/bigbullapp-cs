package com.rectv

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RecTV — resmî uygulamanın imzalı REST API'sini konuşur.
 * İmza: X-Signature = HMAC-SHA256(key, "METHOD\nPATH\nTS\nNONCE\nsha256(\"\")hex")
 * NONCE = GET /api/attest/nonce (tek kullanımlık; nonce_replay alırsan taze nonce ile bir kez tekrar et).
 * Kanıtlı uçlar:
 * - Liste: /api/{movie,serie}/by/filtres/{genre}/{order}/{page}/{K1}/{K2}/
 * - Arama: /api/search/{query}/{K1}/{K2}/ -> posters[]
 * - Dizi sezon/bölüm: /api/season/by/serie/{id}/{K1}/{K2}/ -> seasons[].episodes[].sources[]
 */
class RectvProvider : MainAPI() {
    override var mainUrl = "https://a.psrectv80.xyz"
    override var name = "RecTV"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true
    override val hasQuickSearch = true

    data class Row(val title: String, val kind: String, val genre: Int, val order: String) {
        fun data() = "rectvrow|$kind|$genre|$order"
    }

    override val mainPage: List<MainPageData>
        get() = ROWS.filter { Settings.rowEnabled(it.title) }.map { MainPageData(it.title, it.data()) }

    companion object {
        private const val HMAC_KEY = "3508611138826751fdf77beaa6f93eb93fd27e6a5acb910e7aad22665513dd6e"
        private const val F5 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        private const val K1 = "4F5A9C3D9A86FA54EACEDDD635185"
        private const val K2 = "c3c5bd17-e37b-4b94-a944-8a3688a30452"
        private const val CLIENT_ID = "rectv-android"
        private const val APP_VERSION = "22.0.6"
        private const val UA = "app okhttp/4.12.0"

        val ROWS = listOf(
            Row("Filmler", "movie", 0, "created"),
            Row("Diziler", "serie", 0, "created"),
            Row("En Yüksek Puanlı Filmler", "movie", 0, "rating"),
            Row("En Yüksek Puanlı Diziler", "serie", 0, "rating"),
            Row("Film: Türkçe Dublaj", "movie", 26, "created"),
            Row("Film: Türkçe Altyazı", "movie", 27, "created"),
            Row("Film: Aksiyon", "movie", 1, "created"),
            Row("Film: Komedi", "movie", 3, "created"),
            Row("Film: Korku", "movie", 8, "created"),
            Row("Film: Gerilim", "movie", 9, "created"),
            Row("Film: Romantik", "movie", 5, "created"),
            Row("Film: Animasyon", "movie", 13, "created"),
            Row("Film: Netflix", "movie", 57, "created"),
            Row("Film: Disney+", "movie", 67, "created"),
            Row("Dizi: Aksiyon", "serie", 1, "created"),
            Row("Dizi: Yerli", "serie", 23, "created"),
            Row("Dizi: Çocuk", "serie", 34, "created"),
            Row("Dizi: Netflix", "serie", 57, "created"),
        )

        private val CACHE = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
        private val EP_CACHE = java.util.concurrent.ConcurrentHashMap<String, List<JSONObject>>()
        private val PAGE_CACHE = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<JSONObject>>>()
        private const val PAGE_TTL_MS = 5 * 60 * 1000L
        private val DIGITS = Regex("(\\d+)")

        private fun qualityOf(raw: String?): Int = when ((raw ?: "").uppercase()) {
            "4K", "UHD" -> 2160
            "FHD", "1080" -> 1080
            "HD", "720" -> 720
            "SD", "480" -> 480
            else -> 0
        }
    }

    private fun sign(method: String, path: String, ts: String, nonce: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(HMAC_KEY.toByteArray(), "HmacSHA256"))
        val msg = "$method\n$path\n$ts\n$nonce\n$F5"
        return mac.doFinal(msg.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private suspend fun nonceGet(): String? = try {
        val r = app.get("$mainUrl/api/attest/nonce", headers = mapOf("User-Agent" to UA))
        JSONObject(r.text).optString("nonce", "").takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    private suspend fun signedGet(path: String): String? {
        var nonce = nonceGet() ?: return null
        repeat(3) {
            val ts = (System.currentTimeMillis() / 1000).toString()
            val headers = mapOf(
                "X-Signature" to sign("GET", path, ts, nonce),
                "X-Timestamp" to ts,
                "X-Nonce" to nonce,
                "X-Client-Id" to CLIENT_ID,
                "X-App-Version" to APP_VERSION,
                "User-Agent" to UA,
            )
            val r = runCatching { app.get("$mainUrl$path", headers = headers) }.getOrNull() ?: return null
            if (r.isSuccessful) return r.text
            if (r.text.contains("nonce_replay")) {
                nonce = nonceGet() ?: return null
                return@repeat
            }
            return null
        }
        return null
    }

    private suspend fun apiText(kind: String, genre: Int, order: String, page: Int): String? {
        val path = "/api/$kind/by/filtres/$genre/$order/$page/$K1/$K2/"
        val text = signedGet(path) ?: return null
        runCatching {
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val id = item.optString("id", "")
                if (id.isNotEmpty()) CACHE[id] = item
            }
        }
        return text
    }

    private suspend fun fetchList(kind: String, genre: Int, order: String, page: Int): List<JSONObject> {
        val key = "$kind|$genre|$order|$page"
        val now = System.currentTimeMillis()
        PAGE_CACHE[key]?.let { (ts, cached) ->
            if (now - ts < PAGE_TTL_MS) return cached
        }
        val text = apiText(kind, genre, order, page) ?: return emptyList()
        return try {
            val arr = JSONArray(text)
            val list = (0 until arr.length()).map { arr.getJSONObject(it) }
            if (list.isNotEmpty()) PAGE_CACHE[key] = now to list
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun itemToSearch(item: JSONObject): SearchResponse? {
        val id = item.optString("id", "")
        if (id.isEmpty()) return null
        val type = item.optString("type", "movie")
        val tvType = if (type == "serie") TvType.TvSeries else TvType.Movie
        val title = item.optString("title", id)
        val poster = item.optString("cover", "").ifBlank { item.optString("image", "") }
        val url = "$mainUrl/item/$type/$id"
        return if (tvType == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, url, tvType) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, url, tvType) { this.posterUrl = poster }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val row = ROWS.firstOrNull { it.data() == request.data } ?: ROWS.first()
        val items = fetchList(row.kind, row.genre, row.order, page)
        val results = items.mapNotNull { itemToSearch(it) }
        return newHomePageResponse(request.name, results, hasNext = results.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        val encoded = URLEncoder.encode(q, "UTF-8").replace("+", "%20")
        signedGet("/api/search/$encoded/$K1/$K2/")?.let { raw ->
            val posters = runCatching { JSONObject(raw).optJSONArray("posters") }.getOrNull()
            if (posters != null) {
                for (i in 0 until posters.length()) {
                    val item = posters.optJSONObject(i) ?: continue
                    val id = item.optString("id", "")
                    if (id.isNotEmpty()) CACHE[id] = item
                    val sr = itemToSearch(item) ?: continue
                    if (seen.add(sr.url)) out.add(sr)
                }
            }
        }
        if (out.isNotEmpty()) return out

        val needle = q.lowercase()
        for (kind in listOf("movie", "serie")) {
            for (page in 1..4) {
                val items = fetchList(kind, 0, "created", page)
                if (items.isEmpty()) break
                for (item in items) {
                    if (item.optString("title", "").lowercase().contains(needle)) {
                        val sr = itemToSearch(item) ?: continue
                        if (seen.add(sr.url)) out.add(sr)
                    }
                }
                if (out.isNotEmpty() && page >= 2) break
            }
        }
        return out
    }

    private suspend fun loadSerieEpisodes(serieId: String, fallbackTitle: String, fallbackPlot: String): List<Episode> {
        val raw = signedGet("/api/season/by/serie/$serieId/$K1/$K2/")
        val seasons = runCatching { if (raw != null) JSONArray(raw) else null }.getOrNull()
        if (seasons == null || seasons.length() == 0) {
            return listOf(newEpisode("$mainUrl/src/serie/$serieId") {
                this.name = fallbackTitle
                this.season = 1
                this.episode = 1
                this.description = fallbackPlot
            })
        }
        val grouped = LinkedHashMap<Pair<Int, Int>, Triple<String, String?, MutableList<JSONObject>>>()
        for (sIdx in 0 until seasons.length()) {
            val sObj = seasons.optJSONObject(sIdx) ?: continue
            val sTitle = sObj.optString("title", "")
            val sNum = DIGITS.find(sTitle)?.groupValues?.get(1)?.toIntOrNull() ?: (sIdx + 1)
            val langTag = when {
                sTitle.contains("Dublaj", ignoreCase = true) -> "Türkçe Dublaj"
                sTitle.contains("Altyaz", ignoreCase = true) -> "Türkçe Altyazı"
                else -> sTitle
            }
            val eps = sObj.optJSONArray("episodes") ?: continue
            for (eIdx in 0 until eps.length()) {
                val epObj = eps.optJSONObject(eIdx) ?: continue
                val epTitle = epObj.optString("title", "").trim()
                val epNum = DIGITS.find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: (eIdx + 1)
                val desc = epObj.optString("description", "").takeIf { it.isNotBlank() && it != "null" }
                val key = sNum to epNum
                val bucket = grouped.getOrPut(key) {
                    Triple(epTitle.ifBlank { "Bölüm $epNum" }, desc, mutableListOf())
                }
                val srcs = epObj.optJSONArray("sources") ?: JSONArray()
                for (k in 0 until srcs.length()) {
                    val src = srcs.optJSONObject(k) ?: continue
                    val tagged = JSONObject(src.toString())
                    val origTitle = tagged.optString("title", "RecTV")
                    tagged.put("title", if (langTag.isNotBlank()) "$langTag · $origTitle" else origTitle)
                    bucket.third.add(tagged)
                }
            }
        }
        return grouped.map { (key, info) ->
            val (sNum, epNum) = key
            val cacheKey = "$serieId:$sNum:$epNum"
            EP_CACHE[cacheKey] = info.third
            newEpisode("$mainUrl/src/ep/$serieId/$sNum/$epNum") {
                this.name = info.first
                this.season = sNum
                this.episode = epNum
                this.description = info.second
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val clean = if (url.contains("/item/")) url.substringAfter("/item/") else url.substringAfter("rectv://")
        val parts = clean.trim('/').split("/")
        if (parts.size < 2) return null
        val kind = parts[0]
        val id = parts[1]
        var item = CACHE[id]
        if (item == null || (kind == "movie" && item.optString("sources").isNullOrEmpty())) {
            outer@ for (page in 1..10) {
                val items = fetchList(kind, 0, "created", page)
                for (it2 in items) {
                    if (it2.optString("id") == id) { item = it2; break@outer }
                }
                if (items.isEmpty()) break
            }
        }
        item ?: return null
        val title = item.optString("title", id)
        val poster = item.optString("cover", "").ifBlank { item.optString("image", "") }
        val plot = item.optString("description", "")
        val year = item.optInt("year", 0).takeIf { it > 1900 }
            ?: item.optString("sublabel", "").filter { it.isDigit() }.take(4).toIntOrNull()
        val genres = try {
            val g = item.optJSONArray("genres") ?: JSONArray()
            (0 until g.length()).mapNotNull { g.optJSONObject(it)?.optString("title") }
        } catch (_: Exception) { emptyList() }
        val duration = item.optString("duration", "").let { d ->
            val h = Regex("(\\d+)\\s*h").find(d)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val m = Regex("(\\d+)\\s*min").find(d)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            (h * 60 + m).takeIf { it > 0 }
        }
        val score = Score.from10(item.optString("imdb", "").takeIf { it != "0" && it.isNotBlank() })
        val trailerUrl = item.optJSONObject("trailer")?.optString("url", "")?.takeIf { it.startsWith("http") }

        if (kind == "serie") {
            val episodes = loadSerieEpisodes(id, title, plot)
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = genres
                this.score = score
                this.duration = duration
                if (trailerUrl != null) addTrailer(trailerUrl)
            }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, "$mainUrl/src/movie/$id") {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = genres
            this.score = score
            this.duration = duration
            if (trailerUrl != null) addTrailer(trailerUrl)
        }
    }

    private suspend fun emitSources(
        sources: List<JSONObject>,
        watched: MutableSet<String>,
        callback: (ExtractorLink) -> Unit,
    ) {
        sources.forEachIndexed { i, src ->
            val streamUrl = src.optString("url", "")
            if (streamUrl.isEmpty() || !streamUrl.startsWith("http") || !watched.add(streamUrl)) return@forEachIndexed
            val srcTitle = src.optString("title", "RecTV")
            val q = qualityOf(src.optString("quality"))
            if (!Settings.qualityAllowed(q)) return@forEachIndexed
            callback(newExtractorLink("RecTV", srcTitle.ifBlank { "RecTV ${i + 1}" }, streamUrl) {
                this.referer = mainUrl
                this.quality = q
                this.type = ExtractorLinkType.M3U8
                this.headers = mapOf("User-Agent" to UA)
            })
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val parts = data.substringAfter("/src/").trim('/').split("/")
        if (parts.size < 2) return false
        val watched = HashSet<String>()
        if (parts[0] == "ep" && parts.size >= 4) {
            val serieId = parts[1]
            val sNum = parts[2].toIntOrNull() ?: 1
            val epNum = parts[3].toIntOrNull() ?: 1
            val key = "$serieId:$sNum:$epNum"
            if (!EP_CACHE.containsKey(key)) {
                loadSerieEpisodes(serieId, "", "")
            }
            val list = EP_CACHE[key].orEmpty()
            emitSources(list, watched, callback)
            return watched.isNotEmpty()
        }
        val kind = parts[0]
        val id = parts[1]
        var item = CACHE[id]
        if (item == null || item.optString("sources").isNullOrEmpty()) {
            outer@ for (page in 1..8) {
                val items = fetchList(kind, 0, "created", page)
                for (cand in items) {
                    if (cand.optString("id") == id) { item = cand; break@outer }
                }
                if (items.isEmpty()) break
            }
        }
        item ?: return false
        val arr = item.optJSONArray("sources") ?: JSONArray()
        val list = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        emitSources(list, watched, callback)
        return watched.isNotEmpty()
    }
}
