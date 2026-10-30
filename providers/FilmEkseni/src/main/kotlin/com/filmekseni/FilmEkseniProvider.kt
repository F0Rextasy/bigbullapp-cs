package com.filmekseni

import android.util.Base64
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * FilmEkseni — filmekseni.vip (Laravel + Alpine, Türkçe dublaj film arşivi).
 *
 * Film sayfası `/<slug>/`; listeler `/filmler/`, `/en-cok-izlenenler/`, `/imdb-250/` ve
 * `/tur/<tür>-filmleri/` altında 24 kartlık tek yanıt olarak gelir, sayfalayıcı yok.
 * Arama `/api/search?q=&page=` JSON'u (Meilisearch arkası, 24/sayfa); uç nokta dizi
 * kayıtlarını da döndürdüğü için `contentableType` ile süzülür.
 *
 * Oynatıcı: film sayfasındaki `data-video-player` Alpine bileşeni dil -> servis listesini
 * `videoPlayerData(JSON.parse('{"dual":[…]'))` olarak taşır. Her servisin base64 `template`
 * alanı `{url}` yer tutuculu iframe'i verir: VIP (eksenload.top) jwplayer
 * `setup(JSON.parse('…'))` içinde m3u8 basar, Moly (vidmoly.net) `sources[{file: '…m3u8'}]`.
 */
private fun Element.posterSrcLazy(): String? =
    attr("data-src").takeIf { it.isNotBlank() } ?: attr("src")

class FilmEkseniProvider : MainAPI() {
    override var mainUrl = "https://filmekseni.vip"
    override var name = "FilmEkseni"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    private val uaHeaders = mapOf(
        "User-Agent" to UA,
        "Accept-Language" to "tr-TR,tr;q=0.9",
    )

    override val mainPage get() = ROWS.mapNotNull { name ->
        if (!Settings.rowEnabled(name)) return@mapNotNull null
        when (name) {
            "Son Eklenen Filmler" -> MainPageData(name, "$mainUrl/filmler/")
            "En Çok İzlenenler" -> MainPageData(name, "$mainUrl/en-cok-izlenenler/")
            "IMDb Top 250" -> MainPageData(name, "$mainUrl/imdb-250/")
            "Aksiyon" -> MainPageData(name, "$mainUrl/tur/aksiyon-filmleri/")
            "Bilim Kurgu" -> MainPageData(name, "$mainUrl/tur/bilim-kurgu-filmleri/")
            "Komedi" -> MainPageData(name, "$mainUrl/tur/komedi-filmleri/")
            "Dram" -> MainPageData(name, "$mainUrl/tur/dram-filmleri/")
            "Gerilim" -> MainPageData(name, "$mainUrl/tur/gerilim-filmleri/")
            "Korku" -> MainPageData(name, "$mainUrl/tur/korku-filmleri/")
            "Macera" -> MainPageData(name, "$mainUrl/tur/macera-filmleri/")
            "Animasyon" -> MainPageData(name, "$mainUrl/tur/animasyon-filmleri/")
            "Belgesel" -> MainPageData(name, "$mainUrl/tur/belgesel-filmleri/")
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Liste sayfaları tek yanıtta geliyor; `?page=` aynı 24 kartı tekrar basıyor.
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val doc = app.get(request.data, headers = uaHeaders).document
        return newHomePageResponse(request.name, doc.cards(), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        search(query, 1)?.items ?: emptyList()

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val term = query.trim()
        if (term.isEmpty()) return newSearchResponseList(emptyList(), false)
        val body = app.get(
            "$mainUrl/api/search?q=${URLEncoder.encode(term, "UTF-8")}&page=$page",
            headers = uaHeaders + ("Accept" to "application/json"),
        ).text
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return newSearchResponseList(emptyList(), false)
        val rows = json.optJSONArray("data") ?: return newSearchResponseList(emptyList(), false)
        val items = (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            if (row.optString("contentableType") != "App\\Models\\Movie") return@mapNotNull null
            val title = row.optString("title").trim()
            val slug = row.optString("slug").trim().trim('/')
            if (title.isEmpty() || slug.isEmpty()) return@mapNotNull null
            newMovieSearchResponse(title, "$mainUrl/$slug/", TvType.Movie) {
                this.posterUrl = fixUrlNull(row.optString("posterUrl"))
                this.year = row.optString("releaseYear").toIntOrNull()
                this.score = row.optDouble("imdbRating", 0.0)
                    .takeIf { it > 0 }?.let { Score.from10(it) }
            }
        }.distinctBy { it.url }
        val lastPage = json.optJSONObject("meta")?.optInt("last_page") ?: 0
        return newSearchResponseList(items, page < lastPage)
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = fixUrl(url)
        val doc = runCatching { app.get(pageUrl, headers = uaHeaders).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: film sayfası yüklenemedi. $REPORT_HINT")
        val schema = doc.schema()
        if (schema?.optString("@type") == "TVSeries") {
            throw ErrorLoadingException("$name: bu modül yalnız filmleri destekliyor. $REPORT_HINT")
        }
        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("$name: film adı okunamadı. $REPORT_HINT")

        return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
            this.posterUrl = fixUrlNull(schema?.optString("image"))
            this.plot = doc.selectFirst("div[x-ref='content']")?.text()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: schema?.optString("description")?.trim()?.takeIf { it.isNotEmpty() }
            this.year = schema?.optString("dateCreated")?.take(4)?.toIntOrNull()
            this.duration = schema?.optString("duration")
                ?.let { DURATION.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            this.tags = schema?.optJSONArray("genre").strings()
            this.score = doc.selectFirst("a[href*='imdb.com/title/']")?.text()
                ?.substringBefore('/')?.trim()?.toDoubleOrNull()?.let { Score.from10(it) }
            addActors(schema?.optJSONArray("actor").names())
            doc.selectFirst("iframe[src*=youtube]")?.attr("src")?.trim()
                ?.takeIf { it.isNotEmpty() }?.let { addTrailer(fixUrl(it)) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageUrl = fixUrl(data)
        val doc = runCatching { app.get(pageUrl, headers = uaHeaders).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: film sayfası yüklenemedi. $REPORT_HINT")
        val payload = payloadOf(doc.selectFirst("[data-video-player]")?.attr("x-data").orEmpty())
            ?: throw ErrorLoadingException("$name: oynatıcı verisi bulunamadı. $REPORT_HINT")
        val sources = runCatching { JSONObject(payload) }.getOrNull()
            ?: throw ErrorLoadingException("$name: oynatıcı verisi okunamadı. $REPORT_HINT")

        val links = ArrayList<ExtractorLink>()
        for (language in sources.keys()) {
            val rows = sources.optJSONArray(language) ?: continue
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val embed = embedOf(row) ?: continue
                // embed yalnızca film sayfasını referer olarak kabul ediyor.
                val embedHtml = runCatching {
                    app.get(embed, headers = uaHeaders, referer = pageUrl).text
                }.getOrNull() ?: continue
                val quality = qualityOf(row.optString("quality"))
                val label = listOf(languageOf(language), row.optString("service_name"))
                    .filter { it.isNotBlank() }.joinToString(" ")
                streamsOf(embedHtml).forEach { stream ->
                    val type = if (".m3u8" in stream) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    links += newExtractorLink(name, label, stream, type) {
                        this.referer = embed
                        this.headers = mapOf("User-Agent" to UA)
                        this.quality = quality
                    }
                }
            }
        }

        val found = links.distinctBy { it.url }
        if (found.isEmpty()) throw ErrorLoadingException("$name: oynatılabilir kaynak bulunamadı. $REPORT_HINT")
        val allowed = found.filter { Settings.qualityAllowed(it.quality) }
        // Kalite sınırı bütün kaynakları elediğinde en düşük kaliteli tek link verilir.
        (allowed.ifEmpty { listOfNotNull(found.minByOrNull { it.quality }) }).forEach(callback)
        return true
    }

    // ---------------------------------------------------------------- listeler

    private fun Document.cards(): List<SearchResponse> =
        select("a[href]").mapNotNull { it.toSearchCard() }.distinctBy { it.url }

    private fun Element.toSearchCard(): SearchResponse? {
        if (!hasClass("group/poster")) return null
        val href = fixUrlNull(attr("href")) ?: return null
        val title = selectFirst("h3")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = fixUrlNull(selectFirst("img[src]")?.posterSrcLazy()?.takeIf { it.startsWith("http") })
            this.year = selectFirst("span.text-gray-300")?.text()?.trim()?.toIntOrNull()
            this.score = selectFirst("span.text-accent")?.text()?.trim()?.toDoubleOrNull()
                ?.let { Score.from10(it) }
        }
    }

    // ---------------------------------------------------------------- künye

    private fun Document.schema(): JSONObject? =
        select("script[type=application/ld+json]").firstNotNullOfOrNull { script ->
            runCatching { JSONObject(script.data()) }.getOrNull()?.takeIf {
                it.optString("@type") == "Movie" || it.optString("@type") == "TVSeries"
            }
        }

    private fun JSONArray?.strings(): List<String> =
        this?.let { array -> (0 until array.length()).mapNotNull { array.opt(it) as? String } }.orEmpty()

    private fun JSONArray?.names(): List<String> =
        this?.let { array ->
            (0 until array.length()).mapNotNull {
                array.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank)
            }
        }.orEmpty()

    // ---------------------------------------------------------------- oynatıcı

    /** `x-data` içindeki `JSON.parse('…')` gövdesi tek kaçış katmanıyla (`\u0022`) gelir. */
    private fun payloadOf(attribute: String): String? {
        val start = attribute.indexOf(JSON_PARSE)
        if (start < 0) return null
        val from = start + JSON_PARSE.length
        val end = attribute.indexOf("')", from)
        if (end <= from) return null
        return attribute.substring(from, end).replace("\\u0022", "\"")
    }

    private fun embedOf(row: JSONObject): String? {
        val template = runCatching {
            String(Base64.decode(row.optString("template"), Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull() ?: return null
        val src = EMBED_SRC.find(template)?.groupValues?.get(1)
            ?.replace("{url}", row.optString("link"))
            ?.replace("{slug}", row.optString("service_slug"))
            ?: return null
        return if (src.startsWith("//")) "https:$src" else src
    }

    /**
     * Sunucu iki biçim basıyor: jwplayer `"file":"https:\\/\\/…"` (iki kat kaçışlı) ve
     * `file: 'https://…'` (düz). Tek kat kaçış çözümü `\/` bıraktığı için kalan ters
     * bölüler atılır; adreste ters bölü olmaz.
     */
    private fun streamsOf(html: String): List<String> =
        STREAM.findAll(jsUnescape(html).replace("\\", "")).map { it.value }.distinct().toList()

    private fun jsUnescape(value: String): String {
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '\\' && index + 1 < value.length) {
                val next = value[index + 1]
                if (next == 'u' && index + 6 <= value.length) {
                    runCatching { value.substring(index + 2, index + 6).toInt(16).toChar() }
                        .onSuccess { out.append(it); index += 6 }
                        .onFailure { out.append(next); index += 2 }
                    continue
                }
                out.append(if (next == 'n') '\n' else if (next == 't') '\t' else next)
                index += 2
            } else {
                out.append(char)
                index++
            }
        }
        return out.toString()
    }

    private fun languageOf(language: String): String = when (language) {
        "dual" -> "Türkçe Dublaj & Altyazı"
        "tr" -> "Türkçe Dublaj"
        "en" -> "Türkçe Altyazı"
        else -> language.uppercase()
    }

    /** Sitenin kalite etiketi `1080p` ya da `HD`; yalnızca okunan etiket eşlenir. */
    private fun qualityOf(label: String?): Int {
        val text = label?.lowercase().orEmpty()
        QUALITY_IN_TEXT.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return when {
            "fhd" in text || "full hd" in text -> Qualities.P1080.value
            "hd" in text -> Qualities.P720.value
            else -> Qualities.Unknown.value
        }
    }

    companion object {
        /** Homepage rows in display order; all map to live listing pages. */
        val ROWS = listOf(
            "Son Eklenen Filmler",
            "En Çok İzlenenler",
            "IMDb Top 250",
            "Aksiyon",
            "Bilim Kurgu",
            "Komedi",
            "Dram",
            "Gerilim",
            "Korku",
            "Macera",
            "Animasyon",
            "Belgesel",
        )

        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
        private const val JSON_PARSE = "JSON.parse('"

        private val DURATION = Regex("""PT(\d+)M""")
        private val EMBED_SRC = Regex("""(?:data-)?src="([^"]+)"""")
        private val STREAM = Regex("""(?i)https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)[^\s"'<>\\]*""")
        private val QUALITY_IN_TEXT = Regex("""(\d{3,4})""")
    }
}