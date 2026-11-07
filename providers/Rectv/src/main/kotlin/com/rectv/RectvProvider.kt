package com.rectv

import com.lagradost.cloudstream3.Episode
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
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RecTV — resmî uygulamanın imzalı REST API'sini konuşur.
 * İmza: X-Signature = HMAC-SHA256(key, "METHOD\nPATH\nTS\nNONCE\nsha256(\"\")hex")
 * NONCE = GET /api/attest/nonce (tek kullanımlık; nonce_replay alırsan taze nonce ile bir kez tekrar et).
 * Kanıtlı uçlar: /api/{movie,serie}/by/filtres/{genre}/{order}/{page}/{K1}/{K2}/ (item içinde sources[] m3u8 var).
 */
class RectvProvider : MainAPI() {
    override var mainUrl = "https://a.psrectv80.xyz"
    override var name = "RecTV"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true

    data class Row(val title: String, val kind: String, val genre: Int, val order: String) {
        fun data() = "rectvrow|$kind|$genre|$order"
    }

    override val mainPage: List<MainPageData>
        get() = ROWS.filter { Settings.rowEnabled(it.title) }.map { MainPageData(it.title, it.data()) }

    companion object {
        // Değerler resmî APK'dan çıkarıldı (NativeVault XOR) — repack edilirse değişir.
        private const val HMAC_KEY = "3508611138826751fdf77beaa6f93eb93fd27e6a5acb910e7aad22665513dd6e"
        private const val F5 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" // sha256(b"")
        private const val K1 = "4F5A9C3D9A86FA54EACEDDD635185"
        private const val K2 = "c3c5bd17-e37b-4b94-a944-8a3688a30452"
        private const val CLIENT_ID = "rectv-android"
        private const val APP_VERSION = "22.0.6"
        private const val UA = "app okhttp/4.12.0"

        val ROWS = listOf(
            Row("RecTV Filmler", "movie", 0, "created"),
            Row("RecTV Diziler", "serie", 0, "created"),
            Row("Film: Türkçe Dublaj", "movie", 26, "created"),
            Row("Film: Türkçe Altyazı", "movie", 27, "created"),
            Row("Film: Aksiyon", "movie", 1, "created"),
            Row("Film: Bilim Kurgu", "movie", 28, "created"),
            Row("Dizi: Aksiyon", "serie", 1, "created"),
        )

        private val CACHE = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
        private val PAGE_CACHE = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<JSONObject>>>()
        private const val PAGE_TTL_MS = 5 * 60 * 1000L

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

    private suspend fun nonceGet(): String? {
        return try {
            val r = app.get("$mainUrl/api/attest/nonce", headers = mapOf("User-Agent" to UA))
            JSONObject(r.text).optString("nonce", "").takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun apiText(kind: String, genre: Int, order: String, page: Int): String? {
        // kind = movie|serie; dönen path imza için kullanılan path'tir (query yok).
        val path = "/api/$kind/by/filtres/$genre/$order/$page/$K1/$K2/"
        var nonce = nonceGet() ?: return null
        repeat(3) { attempt ->
            val ts = (System.currentTimeMillis() / 1000).toString()
            val headers = mapOf(
                "X-Signature" to sign("GET", path, ts, nonce),
                "X-Timestamp" to ts,
                "X-Nonce" to nonce,
                "X-Client-Id" to CLIENT_ID,
                "X-App-Version" to APP_VERSION,
                "User-Agent" to UA,
            )
            val r = app.get("$mainUrl$path", headers = headers)
            val text = r.text
            if (r.isSuccessful) {
                val arr = JSONArray(text)
                val items = (0 until arr.length()).map { arr.getJSONObject(it) }
                items.forEach { item ->
                    val id = item.optString("id", "")
                    if (id.isNotEmpty()) CACHE[id] = item
                }
                return text
            }
            if (text.contains("nonce_replay")) {
                nonce = nonceGet() ?: return null
                return@repeat
            }
            return null
        }
        return null
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
        val url = "rectv://$type/$id"
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
        // Sunucu search ucu yok (route denemeleri 403/404); istemci tarafı tarama.
        // shortcut: sadece ilk 6 sayfa taranır; sunucu search ucu öğrenilirse buraya bağlanır
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        for (kind in listOf("movie", "serie")) {
            for (page in 1..6) {
                val items = fetchList(kind, 0, "created", page)
                if (items.isEmpty()) break
                for (item in items) {
                    val title = item.optString("title", "")
                    if (title.lowercase().contains(needle)) {
                        val sr = itemToSearch(item) ?: continue
                        val url = sr.url
                        if (seen.add(url)) out.add(sr)
                    }
                }
                if (out.isNotEmpty() && page >= 2) break
            }
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse? {
        // url biçimi: rectv://movie/11593 | rectv://serie/11479
        val parts = url.removePrefix("rectv://").split("/")
        if (parts.size < 2) return null
        val kind = parts[0]
        val id = parts[1]
        var item = CACHE[id]
        if (item == null || item.optString("sources").isNullOrEmpty()) {
            // önbellek soğuksa ilk sayfalarda ara (10 sayfa tavan)
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
        val year = item.optString("sublabel", "").filter { it.isDigit() }.take(4).toIntOrNull()
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
        if (kind == "serie") {
            val tvType = TvType.TvSeries
            val data = "rectv://src/serie/$id"
            val searchResponse = newTvSeriesLoadResponse(
                title,
                url,
                tvType,
                episodes = listOf(newEpisode(data) {
                    this.name = title
                    this.season = 1
                    this.episode = 1
                    this.description = plot
                }),
            ) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = genres
                this.score = score
                this.duration = duration
            }
            return searchResponse
        }
        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            data = "rectv://src/movie/$id",
        ) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = genres
            this.score = score
            this.duration = duration
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // data: rectv://src/{kind}/{id}
        val parts = data.removePrefix("rectv://src/").split("/")
        if (parts.size < 2) return false
        val kind = parts[0]
        val id = parts[1]
        val watched = HashSet<String>()
        var item = CACHE[id]
        if (item == null || item.optString("sources").isNullOrEmpty()) {
            // soğuk önbellek: filtres listelerinden id'yi bul (8 sayfa tavan)
            outer@ for (page in 1..8) {
                val items = fetchList(kind, 0, "created", page)
                for (cand in items) {
                    if (cand.optString("id") == id) { item = cand; break@outer }
                }
                if (items.isEmpty()) break
            }
        }
        item ?: return false
        val sources = item.optJSONArray("sources") ?: JSONArray()
        for (i in 0 until sources.length()) {
            val src = sources.optJSONObject(i) ?: continue
            val streamUrl = src.optString("url", "")
            if (streamUrl.isEmpty() || streamUrl in watched || !streamUrl.startsWith("http")) continue
            watched.add(streamUrl)
            val srcTitle = src.optString("title", "RecTV")
            val q = qualityOf(src.optString("quality"))
            if (!Settings.qualityAllowed(q)) continue
            callback(newExtractorLink(
                "RecTV",
                srcTitle.ifBlank { "RecTV ${i + 1}" },
                streamUrl,
            ) {
                this.referer = mainUrl
                this.quality = q
                this.type = ExtractorLinkType.M3U8
                this.headers = mapOf("User-Agent" to UA)
            })
        }
        return watched.isNotEmpty()
    }
}
