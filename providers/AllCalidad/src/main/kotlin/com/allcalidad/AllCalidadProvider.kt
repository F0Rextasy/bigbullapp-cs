package com.allcalidad

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
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * AllCalidad — allcalidad.re (SPA "gomovies" sobre WordPress).
 * Todo el catálogo sale del REST propio `/api/rest`: búsqueda, ficha, reproductor y
 * relacionados. Los embeds son `vimeos.net` (payload empaquetado con el packer de
 * Dean Edwards) y `voe.sx` (lo resuelve el extractor del core).
 */
class AllCalidadProvider : MainAPI() {
    override var mainUrl = "https://allcalidad.re"
    override var name = "AllCalidad"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie)

    private val api = "$mainUrl/api/rest"
    private val uaHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
        "Accept-Language" to "es-MX,es;q=0.9",
    )

    override val mainPage = mainPageOf(
        "$api/listing?page=SAYFA&post_type=movies&posts_per_page=30" to "Películas",
    )

    private suspend fun getJson(url: String): JSONObject {
        val body = app.get(url, headers = uaHeaders + ("Accept" to "application/json")).text
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: throw ErrorLoadingException("$name: respuesta inválida. $REPORT_HINT")
        if (json.optBoolean("error")) {
            throw ErrorLoadingException("$name: ${json.optString("message").ifBlank { "error de API" }}. $REPORT_HINT")
        }
        return json
    }

    private fun JSONArray.items(): List<JSONObject> =
        (0 until length()).mapNotNull { optJSONObject(it) }

    private fun filmUrl(slug: String) = "$mainUrl/peliculas/${slug.trim('/')}"

    private fun posterUrl(item: JSONObject): String? {
        val poster = item.optJSONObject("images")?.optString("poster").orEmpty()
        if (poster.isBlank()) return null
        return "$mainUrl/wp-content/uploads/${poster.trimStart('/')}"
    }

    private fun cleanTitle(raw: String) =
        raw.trim().replace(Regex("""\s*\(\d{4}\)\s*$"""), "").ifBlank { raw.trim() }

    private fun JSONObject.toSearchResponse(): SearchResponse? {
        val slug = optString("slug").trim('/').takeIf { it.isNotBlank() } ?: return null
        val title = cleanTitle(optString("title")).takeIf { it.isNotBlank() } ?: return null
        return newMovieSearchResponse(title, filmUrl(slug), TvType.Movie) {
            this.posterUrl = posterUrl(this@toSearchResponse)
            this.year = optString("release_date").take(4).toIntOrNull()
            this.score = optString("rating").toDoubleOrNull()?.takeIf { it > 0 }?.let { Score.from10(it) }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val json = getJson(request.data.replace("SAYFA", page.toString()))
        val posts = json.optJSONObject("data")?.optJSONArray("posts")?.items().orEmpty()
        val items = posts.mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val json = getJson("$api/search?post_type=movies&query=$encoded&posts_per_page=30")
        return json.optJSONObject("data")?.optJSONArray("posts")?.items()
            .orEmpty().mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.trimEnd('/').substringAfterLast('/')
        val json = getJson("$api/single?post_name=$slug&post_type=movies")
        val data = json.optJSONObject("data")
            ?: throw ErrorLoadingException("$name: ficha no disponible. $REPORT_HINT")
        val title = cleanTitle(data.optString("title")).takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: ficha sin título. $REPORT_HINT")

        val related = runCatching {
            getJson("$api/related?post_id=${data.optInt("_id")}&post_type=movies&posts_per_page=16")
                .optJSONArray("data")?.items().orEmpty().mapNotNull { it.toSearchResponse() }
        }.getOrDefault(emptyList())

        return newMovieLoadResponse(title, url, TvType.Movie, filmUrl(slug)) {
            this.posterUrl = posterUrl(data)
            this.plot = data.optString("overview").trim().takeIf { it.isNotBlank() }
            this.year = data.optString("release_date").take(4).toIntOrNull()
            this.score = data.optString("rating").toDoubleOrNull()?.takeIf { it > 0 }?.let { Score.from10(it) }
            this.duration = data.optString("runtime").toDoubleOrNull()?.toInt()?.takeIf { it > 0 }
            this.recommendations = related
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val slug = data.trimEnd('/').substringAfterLast('/')
        val single = getJson("$api/single?post_name=$slug&post_type=movies")
        val postId = single.optJSONObject("data")?.optInt("_id") ?: 0
        if (postId <= 0) throw ErrorLoadingException("$name: contenido sin id. $REPORT_HINT")

        val player = getJson("$api/player?post_id=$postId&_any=1")
        val embeds = player.optJSONObject("data")?.optJSONArray("embeds")?.items().orEmpty()
            .filter { it.optString("url").isNotBlank() }
        if (embeds.isEmpty()) throw ErrorLoadingException("$name: sin fuentes. $REPORT_HINT")

        var found = false
        for (embed in embeds) {
            val embedUrl = embed.optString("url").trim()
            val quality = qualityOf(embed.optString("quality"))
            if (!Settings.qualityAllowed(quality)) continue
            val langLabel = embed.optString("lang").trim()
            val referer = "$mainUrl/"
            found = if (embedUrl.contains("vimeos.net")) {
                resolveVimeos(embedUrl, quality, langLabel, subtitleCallback, callback) || found
            } else {
                loadExtractor(embedUrl, referer, subtitleCallback, callback) || found
            }
        }
        if (!found) throw ErrorLoadingException("$name: no se pudo resolver ninguna fuente. $REPORT_HINT")
        return true
    }

    /** vimeos.net sirve un `jwplayer` config empaquetado con el packer (p,a,c,k,e,d). */
    private suspend fun resolveVimeos(
        embedUrl: String,
        quality: Int,
        langLabel: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = runCatching {
            app.get(embedUrl, headers = uaHeaders + ("Referer" to "$mainUrl/")).text
        }.getOrNull() ?: return false
        val config = unpack(html) ?: return false
        val sources = Regex("""sources:\[(.*?)]""", RegexOption.DOT_MATCHES_ALL).find(config)
            ?.groupValues?.get(1).orEmpty()
        val streams = Regex("""file:"([^"]+)"""").findAll(sources)
            .map { it.groupValues[1].replace("\\/", "/") }.toList()
        if (streams.isEmpty()) return false

        val linkName = listOf("Vimeos", langLabel).filter { it.isNotBlank() }.joinToString(" ")
        for (stream in streams) {
            callback.invoke(
                newExtractorLink(name, "$name $linkName", stream, ExtractorLinkType.M3U8) {
                    this.referer = embedUrl
                    this.quality = quality
                }
            )
        }

        val tracks = Regex("""tracks:\[(.*?)]""", RegexOption.DOT_MATCHES_ALL).find(config)
            ?.groupValues?.get(1).orEmpty()
        Regex("""file:"([^"]+)",label:"([^"]+)"""").findAll(tracks).forEach { match ->
            val file = match.groupValues[1].replace("\\/", "/")
            val trackLabel = match.groupValues[2]
            if (file.endsWith("empty.srt")) return@forEach
            if (Settings.subtitleAllowed(trackLabel)) {
                subtitleCallback.invoke(
                    newSubtitleFile(subtitleLabel(trackLabel), file) {
                        this.headers = mapOf("Referer" to embedUrl)
                    }
                )
            }
        }
        return true
    }

    /**
     * Dean Edwards packer: `eval(function(p,a,c,k,e,d){…}('payload',a,c,'k|…'.split('|')))`.
     * Los tokens del payload son índices en base `a` dentro del diccionario `k`.
     */
    private fun unpack(html: String): String? {
        val match = Regex(
            """\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(html) ?: return null
        val payload = match.groupValues[1]
        val base = match.groupValues[2].toIntOrNull() ?: return null
        val count = match.groupValues[3].toIntOrNull() ?: return null
        val dict = match.groupValues[4].split('|')
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

        return Regex("[0-9a-zA-Z]+").replace(payload) { token ->
            val word = token.value
            var value = 0
            var valid = true
            for (char in word) {
                val digit = alphabet.indexOf(char)
                if (digit < 0 || digit >= base) {
                    valid = false
                    break
                }
                value = value * base + digit
            }
            if (valid && value < count && value < dict.size && dict[value].isNotEmpty()) dict[value] else word
        }
    }

    private fun qualityOf(label: String): Int {
        val height = Regex("""(\d{3,4})p""").find(label)?.groupValues?.get(1)?.toIntOrNull()
            ?: return Qualities.Unknown.value
        return when {
            height >= 1000 -> Qualities.P1080.value
            height >= 700 -> Qualities.P720.value
            height >= 500 -> Qualities.P480.value
            height >= 350 -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun subtitleLabel(label: String) = when {
        label.contains("span", true) || label.equals("spa", true) -> "Español"
        label.contains("ingl", true) || label.contains("english", true) -> "Inglés"
        else -> label
    }

    companion object {
        const val REPORT_HINT = "Reportar en el canal de soporte del plugin"
    }
}
