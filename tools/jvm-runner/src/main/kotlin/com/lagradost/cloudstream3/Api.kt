package com.lagradost.cloudstream3

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

enum class TvType { Movie, TvSeries, Anime, AnimeMovie, Cartoon, Documentary, Live, Others }
enum class ShowStatus { Ongoing, Completed, Unknown }

class Score private constructor(val value: Int) {
    companion object {
        fun from10(value: Double?): Score? = value?.let { Score((it * 10).toInt()) }
        fun from10(value: String?): Score? = value?.trim()?.toDoubleOrNull()?.let { Score((it * 10).toInt()) }
    }
}

data class Actor(val name: String)
data class ActorData(val actor: Actor, val role: String? = null)

open class SearchResponse(
    var name: String = "",
    var url: String = "",
    var apiName: String = "",
    var type: TvType = TvType.Movie,
) {
    var posterUrl: String? = null
    var year: Int? = null
    var score: Score? = null
}

class MovieSearchResponse(name: String, url: String, type: TvType) : SearchResponse(name, url, "", type)
class TvSeriesSearchResponse(name: String, url: String, type: TvType) : SearchResponse(name, url, "", type)

data class Episode(val data: String) {
    var name: String? = null
    var season: Int? = null
    var episode: Int? = null
    var posterUrl: String? = null
    var description: String? = null
}

data class SubtitleFile(val lang: String, val url: String) {
    var headers: Map<String, String>? = null
}

open class LoadResponse(
    var name: String = "",
    var url: String = "",
    var apiName: String = "",
    var type: TvType = TvType.Movie,
) {
    var posterUrl: String? = null
    var plot: String? = null
    var year: Int? = null
    var tags: List<String>? = null
    var duration: Int? = null
    var score: Score? = null
    var actors: List<ActorData>? = null
    var recommendations: List<SearchResponse>? = null
    var episodes: List<Episode>? = null
    var status: ShowStatus? = null
    var showStatus: ShowStatus? = null
    fun addTrailer(trailer: String?) { }
}

class MovieLoadResponse(name: String, url: String, type: TvType, var data: String) :
    LoadResponse(name, url, "", type) {
    var trailerUrl: String? = null
}

class TvSeriesLoadResponse(
    name: String, url: String, type: TvType, episodes: List<Episode>
) : LoadResponse(name, url, "", type) {
    init { this.episodes = episodes }
}

class MainPageData(val name: String, val data: String)
class MainPageRequest(val name: String, val data: String, val hasNext: Boolean = false)
class HomePageResponse(val name: String, val items: List<SearchResponse>, val hasNext: Boolean = false)
class SearchResponseList(val items: List<SearchResponse>, val hasNext: Boolean = false)

class ErrorLoadingException(message: String) : Exception(message)

abstract class MainAPI {
    open var mainUrl: String = ""
    open var name: String = ""
    open var lang: String = ""
    open val hasMainPage: Boolean = false
    open val hasQuickSearch: Boolean = false
    open val supportedTypes: Set<TvType> = emptySet()
    open val mainPage: List<MainPageData> = emptyList()

    open suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        HomePageResponse(request.name, emptyList())

    abstract suspend fun search(query: String): List<SearchResponse>
    open suspend fun quickSearch(query: String): List<SearchResponse> = search(query)
    abstract suspend fun load(url: String): LoadResponse?
    abstract suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit,
    ): Boolean
}

fun mainPageOf(vararg pairs: Pair<String, String>): List<MainPageData> =
    pairs.map { MainPageData(it.second, it.first) }

fun newMovieSearchResponse(name: String, url: String, type: TvType, init: MovieSearchResponse.() -> Unit = {}): SearchResponse =
    MovieSearchResponse(name, url, type).apply(init)

fun newTvSeriesSearchResponse(name: String, url: String, type: TvType, init: TvSeriesSearchResponse.() -> Unit = {}): SearchResponse =
    TvSeriesSearchResponse(name, url, type).apply(init)

fun newMovieLoadResponse(name: String, url: String, type: TvType, data: String, init: MovieLoadResponse.() -> Unit = {}): LoadResponse =
    MovieLoadResponse(name, url, type, data).apply(init)

fun newTvSeriesLoadResponse(name: String, url: String, type: TvType, episodes: List<Episode>, init: TvSeriesLoadResponse.() -> Unit = {}): LoadResponse =
    TvSeriesLoadResponse(name, url, type, episodes).apply(init)

fun newEpisode(data: String, init: Episode.() -> Unit = {}): Episode = Episode(data).apply(init)

fun newSubtitleFile(lang: String, url: String, init: SubtitleFile.() -> Unit = {}): SubtitleFile =
    SubtitleFile(lang, url).apply(init)

fun newHomePageResponse(name: String, items: List<SearchResponse>, hasNext: Boolean = false): HomePageResponse =
    HomePageResponse(name, items, hasNext)

fun newSearchResponseList(items: List<SearchResponse>, hasNext: Boolean = false): SearchResponseList =
    SearchResponseList(items, hasNext)

object UrlContext {
    val base = ThreadLocal<String?>()
}

fun fixUrl(url: String): String {
    if (url.startsWith("http://") || url.startsWith("https://")) return url
    val base = UrlContext.base.get() ?: return url
    return when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> base.trimEnd('/') + url
        else -> base.trimEnd('/') + "/" + url
    }
}

fun fixUrlNull(url: String?): String? = url?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }

class AppResponse(
    val text: String,
    val url: String,
    val headers: Map<String, String>,
    val cookies: Map<String, String>,
) {
    val document: Document by lazy { Jsoup.parse(text, url) }
    inline fun <reified T> parsedSafe(): T? =
        if (T::class == Document::class) document as? T else null
}

object app {
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun buildHeaders(headers: Map<String, String>?, referer: String?, cookies: Map<String, String>?): Map<String, String> {
        val h = headers?.toMutableMap() ?: mutableMapOf()
        referer?.let { h["Referer"] = it }
        cookies?.takeIf { it.isNotEmpty() }?.let { h["Cookie"] = it.entries.joinToString("; ") { e -> "${e.key}=${e.value}" } }
        return h
    }

    private fun exec(req: Request, baseUrl: String): AppResponse {
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            val hdrs = resp.headers.names().associateWith { resp.headers[it] ?: "" }
            val cookies = resp.headers("Set-Cookie").mapNotNull { raw ->
                raw.substringBefore(';').split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
            }.toMap()
            return AppResponse(body, baseUrl, hdrs, cookies)
        }
    }

    suspend fun get(
        url: String,
        headers: Map<String, String>? = null,
        referer: String? = null,
        params: Map<String, String>? = null,
        cookies: Map<String, String>? = null,
        allowRedirects: Boolean = true,
    ): AppResponse {
        val finalUrl = if (params.isNullOrEmpty()) url else
            url + (if (url.contains('?')) "&" else "?") + params.entries.joinToString("&") {
                "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
            }
        val req = Request.Builder().url(finalUrl)
            .apply { buildHeaders(headers, referer, cookies).forEach { (k, v) -> header(k, v) } }
            .get().build()
        return exec(req, finalUrl)
    }

    suspend fun post(
        url: String,
        data: Map<String, String>? = null,
        headers: Map<String, String>? = null,
        referer: String? = null,
        cookies: Map<String, String>? = null,
    ): AppResponse {
        val body = (data ?: emptyMap()).entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }.toRequestBody()
        val req = Request.Builder().url(url)
            .apply { buildHeaders(headers, referer, cookies).forEach { (k, v) -> header(k, v) } }
            .post(body).build()
        return exec(req, url)
    }
}
