package com.roketdizi

import android.util.Base64
import com.bigbull.common.browserHeaders
import com.bigbull.common.pbkdf2AesCbcDecrypt
import com.bigbull.common.pichiveSign
import com.bigbull.common.posterSrcLazy
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
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
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * RoketDizi — roketdizi.life (Next.js App Router, macellan CMS, pichive oynatıcı).
 *
 * `roketdizi.live` alan adı park sayfasına düşüyor; sitenin canonical'ı roketdizi.life.
 * Yollar: dizi `/dizi/<slug>`, bölüm `/dizi/<slug>/sezon-<n>/bolum-<m>`, film
 * `/film/<slug>/izle`; listeler `/dizi-izle`, `/film-izle`, `/trend`, `/dizi/tur/<tür>`,
 * arama `/arama-yap?s=` — hepsi `?page=N` ile sayfalanır.
 *
 * Oynatıcı: sayfa yükünde `enc` (ciphertext/iv/salt) ve aynı yükte `k` parolası durur.
 * PBKDF2-HMAC-SHA512(999) + AES-256-CBC çözümü `//<host>.pichive.online/iframe.php?v=…`
 * verir; `window.NP_CONFIG` içindeki `sources[].master` proof-of-work imzası ister
 * ([pichiveSign]) ve HLS master olarak verilir. Altyazılar `subtitles[].url|tok` (WebVTT).
 */
class RoketDiziProvider : MainAPI() {
    override var mainUrl = "https://roketdizi.life"
    override var name = "RoketDizi"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage get() = ROWS.mapNotNull { row ->
        if (!Settings.rowEnabled(row)) return@mapNotNull null
        val data = when (row) {
            "Son Eklenen Diziler" -> "$mainUrl/dizi-izle?page=$PAGE_TOKEN"
            "Son Eklenen Filmler" -> "$mainUrl/film-izle?page=$PAGE_TOKEN"
            "Diziler — Dram" -> "$mainUrl/dizi/tur/dram?page=$PAGE_TOKEN"
            "Diziler — Suç" -> "$mainUrl/dizi/tur/suc?page=$PAGE_TOKEN"
            "Trend" -> "$mainUrl/trend"
            else -> return@mapNotNull null
        }
        MainPageData(row, data)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val paged = request.data.contains(PAGE_TOKEN)
        if (!paged && page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val url = request.data.replace(PAGE_TOKEN, page.toString())
        val doc = runCatching { app.get(url, headers = browserHeaders).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: ana sayfa yüklenemedi. $REPORT_HINT")
        val hasNext = paged && doc.selectFirst("a[href*='page=${page + 1}']") != null
        return newHomePageResponse(request.name, doc.cards(), hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> = search(query, 1)?.items ?: emptyList()

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val term = query.trim()
        if (term.isEmpty()) return newSearchResponseList(emptyList(), false)
        val doc = runCatching {
            app.get(
                "$mainUrl/arama-yap",
                params = mapOf("s" to term, "page" to page.toString()),
                headers = browserHeaders,
            ).document
        }.getOrNull() ?: throw ErrorLoadingException("$name: arama isteği başarısız. $REPORT_HINT")
        val hasNext = doc.selectFirst("a[href*='page=${page + 1}']") != null
        return newSearchResponseList(doc.cards(), hasNext)
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = fixUrl(url)
        val response = runCatching { app.get(pageUrl, headers = browserHeaders) }.getOrNull()
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val doc = response.document
        val rsc = response.text.replace("\\\"", "\"")
        val ld = doc.jsonLd()
        val slots = jsonObjectAfter(rsc, "\"slots\":")
        val title = slots?.optString("mjTitle")?.trim()?.takeIf { it.isNotEmpty() }
            ?: ld?.optString("name")?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("$name: başlık okunamadı. $REPORT_HINT")
        val poster = doc.selectFirst("img[alt*=afişi]")?.posterSrcLazy()
            ?: fixUrlNull(ld?.optString("image"))
        val plot = slots?.optString("mjOverview")?.takeIf { it.isNotBlank() }
            ?: ld?.optString("description")?.takeIf { it.isNotBlank() }
        val year = slots?.optString("mjYear")?.toIntOrNull()
            ?: ld?.optString("datePublished")?.take(4)?.toIntOrNull()?.takeIf { it in 1900..2100 }
        val score = Score.from10(
            slots?.optString("mjImdb")?.takeIf { it.toDoubleOrNull() != null }
                ?: ld?.optJSONObject("aggregateRating")?.optString("ratingValue")
        )
        val tags = doc.select("a[href^='/dizi/tur/'], a[href^='/film/tur/']")
            .map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
        val recommendations = jsonArrayAfter(rsc, "\"similar\":[")?.let { recommendationsOf(it) }
            ?.takeIf { it.isNotEmpty() }

        if (pageUrl.contains("/film/")) {
            return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = score
                this.duration = runtimeMinutes(slots?.optString("mjRuntime"))
                this.recommendations = recommendations
                this.actors = actorsOf(ld)
            }
        }

        val episodes = jsonArrayAfter(rsc, "\"seasons\":[")?.let { episodesOf(it) }.orEmpty()
        if (episodes.isEmpty()) throw ErrorLoadingException("$name: bölüm listesi bulunamadı. $REPORT_HINT")
        return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.score = score
            this.recommendations = recommendations
            this.actors = actorsOf(ld)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageUrl = fixUrl(data)
        val pageHtml = runCatching { app.get(pageUrl, headers = browserHeaders).text }.getOrNull()
            ?: throw ErrorLoadingException("$name: içerik sayfası yüklenemedi. $REPORT_HINT")
        val text = pageHtml.replace("\\\"", "\"")
        val playerUrl = decryptPlayerUrl(text)
            ?: throw ErrorLoadingException("$name: oynatıcı adresi çözülemedi. $REPORT_HINT")
        val playerHtml = runCatching {
            app.get(playerUrl, headers = browserHeaders, referer = "$mainUrl/").text
        }.getOrNull() ?: throw ErrorLoadingException("$name: oynatıcı yüklenemedi. $REPORT_HINT")
        val config = jsonObjectAfter(playerHtml, "window.NP_CONFIG")
            ?: throw ErrorLoadingException("$name: oynatıcı verisi okunamadı. $REPORT_HINT")
        val quality = QUALITY.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: Qualities.Unknown.value

        val masters = config.optJSONArray("sources").objects().mapNotNull { source ->
            val master = source.optString("master").takeIf { it.startsWith("http") }
                ?.let { pichiveSign(playerHtml, it) } ?: return@mapNotNull null
            source.optString("title").trim() to master
        }
        if (masters.isEmpty()) throw ErrorLoadingException("$name: oynatılabilir kaynak bulunamadı. $REPORT_HINT")
        // Master tüm rendition'ları taşır; ayar üst sınırı aşınca kalite "bilinmiyor" işaretlenir.
        val linkQuality = if (Settings.qualityAllowed(quality)) quality else Qualities.Unknown.value
        masters.forEach { (label, url) ->
            callback(
                newExtractorLink(name, label.ifEmpty { name }, url, ExtractorLinkType.M3U8) {
                    this.referer = playerUrl
                    this.quality = linkQuality
                    this.headers = browserHeaders
                }
            )
        }

        val playerOrigin = ORIGIN.find(playerUrl)?.value.orEmpty()
        config.optJSONArray("subtitles").objects().forEach { track ->
            val raw = track.optString("url").takeIf { it.startsWith("http") }
                ?: track.optString("tok").takeIf { it.isNotEmpty() }?.let { "$playerOrigin/nsub.php?v=$it" }
                ?: return@forEach
            val language = track.optString("lang").ifEmpty { track.optString("label") }
            if (!Settings.subtitleAllowed(language)) return@forEach
            val label = track.optString("label").trim().ifEmpty { language.ifEmpty { "Altyazı" } }
            subtitleCallback(
                newSubtitleFile(label, pichiveSign(playerHtml, raw)) {
                    this.headers = browserHeaders + ("Referer" to playerUrl)
                }
            )
        }
        return true
    }

    // ---------------------------------------------------------------- listeler

    private fun Document.cards(): List<SearchResponse> =
        select("a.tc[href], a.rk-mv[href], a.rk-rank-item[href], a.rk-ep[href]")
            .mapNotNull { it.toSearchCard() }
            .distinctBy { it.url }

    /** Ortak kart: `span.tt`/`span.rk-card-t` başlık, `img` afiş, aria-label künyesi. */
    private fun Element.toSearchCard(): SearchResponse? {
        val href = fixUrlNull(attr("href").substringBefore('?')) ?: return null
        val url = href.replace(EPISODE_SUFFIX, "")
        val isMovie = url.contains("/film/")
        if (!isMovie && (!url.contains("/dizi/") || url.contains("/dizi/tur/"))) return null
        val aria = attr("aria-label")
        val title = selectFirst("span.tt, span.rk-card-t")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: aria.replace(RANK_PREFIX, "").substringBefore(',').trim().takeIf { it.isNotEmpty() }
            ?: return null
        val poster = selectFirst("img")?.posterSrcLazy()
        val score = Score.from10(IMDB.find(aria)?.groupValues?.get(1))
        return if (isMovie) {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
                this.score = score
            }
        } else {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
                this.score = score
            }
        }
    }

    private fun episodesOf(seasons: JSONArray): List<Episode> {
        val episodes = LinkedHashMap<String, Episode>()
        for (i in 0 until seasons.length()) {
            val season = seasons.optJSONObject(i) ?: continue
            val seasonNo = season.optInt("no", i + 1)
            val rows = season.optJSONArray("episodes") ?: continue
            for (j in 0 until rows.length()) {
                val row = rows.optJSONObject(j) ?: continue
                val episodeUrl = fixUrlNull(row.optString("href")) ?: continue
                val episodeNo = row.optInt("n", j + 1)
                val episodeName = row.optString("title").trim().takeIf { it.isNotEmpty() } ?: "$episodeNo. Bölüm"
                episodes.getOrPut(episodeUrl) {
                    newEpisode(episodeUrl) {
                        this.season = seasonNo
                        this.episode = episodeNo
                        this.name = episodeName
                    }
                }
            }
        }
        return episodes.values.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
    }

    private fun recommendationsOf(items: JSONArray): List<SearchResponse> =
        (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val title = item.optString("t").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val url = fixUrlNull(item.optString("href")) ?: return@mapNotNull null
            val poster = fixUrlNull(item.optString("img"))
            if (url.contains("/film/")) {
                newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster }
            } else {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster }
            }
        }

    // ---------------------------------------------------------------- künye

    private fun Document.jsonLd(): JSONObject? =
        select("script[type='application/ld+json']").firstNotNullOfOrNull { script ->
            runCatching { JSONObject(script.data()) }.getOrNull()
                ?.takeIf { it.optString("@type") == "TVSeries" || it.optString("@type") == "Movie" }
        }

    private fun JSONArray?.names(): List<String> =
        this?.let { array ->
            (0 until array.length()).mapNotNull {
                array.optJSONObject(it)?.optString("name")?.trim()?.takeIf(String::isNotEmpty)
            }
        }.orEmpty()

    private fun actorsOf(ld: JSONObject?): List<ActorData> =
        ld?.optJSONArray("actor").names().map { ActorData(Actor(it)) }

    /** `mjRuntime` biçimi `2 sa 16 dk`. */
    private fun runtimeMinutes(text: String?): Int? {
        val match = RUNTIME.find(text.orEmpty()) ?: return null
        val hours = match.groupValues[1].toIntOrNull() ?: 0
        val minutes = match.groupValues[2].toIntOrNull() ?: 0
        return (hours * 60 + minutes).takeIf { it > 0 }
    }

    // ---------------------------------------------------------------- oynatıcı

    /** RSC yükündeki `enc` bloğunu ve hemen ardından gelen `k` parolasını çözer. */
    private fun decryptPlayerUrl(text: String): String? {
        val encAt = text.indexOf("\"enc\":{")
        if (encAt < 0) return null
        val enc = jsonObjectAt(text, text.indexOf('{', encAt)) ?: return null
        val keyAt = text.indexOf(KEY_PREFIX, encAt)
        if (keyAt < 0) return null
        val passphrase = text.substring(keyAt + KEY_PREFIX.length).substringBefore('"')
        if (passphrase.isEmpty()) return null
        val clear = pbkdf2AesCbcDecrypt(
            Base64.decode(enc.optString("ciphertext"), Base64.DEFAULT),
            passphrase,
            enc.optString("salt"),
            enc.optString("iv"),
        ) ?: return null
        return httpsUrl(clear.trim('\t', '\r', '\n', ' ', '\u000f'))
    }

    private fun httpsUrl(raw: String?): String? {
        val url = raw?.trim().orEmpty()
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http") -> url
            else -> null
        }
    }

    // ---------------------------------------------------------------- json

    /**
     * Sayfa yükünde aynı anahtar birden çok kez geçer (`"slots":{}` kabuk nesnesi önce gelir);
     * boş eşleşmeler atlanıp ilk dolu gövde döndürülür.
     */
    private fun jsonObjectAfter(text: String, marker: String): JSONObject? {
        var from = 0
        while (true) {
            val at = text.indexOf(marker, from)
            if (at < 0) return null
            val open = text.indexOf('{', at)
            if (open < 0) return null
            val parsed = jsonObjectAt(text, open)
            if (parsed != null && parsed.length() > 0) return parsed
            from = open + 1
        }
    }

    private fun jsonArrayAfter(text: String, marker: String): JSONArray? {
        var from = 0
        var empty: JSONArray? = null
        while (true) {
            val at = text.indexOf(marker, from)
            if (at < 0) return empty
            val open = text.indexOf('[', at)
            if (open < 0) return empty
            val parsed = sliceBalanced(text, open, '[', ']')
                ?.let { runCatching { JSONArray(it) }.getOrNull() }
            if (parsed != null && parsed.length() > 0) return parsed
            if (parsed != null) empty = parsed
            from = open + 1
        }
    }

    private fun jsonObjectAt(text: String, open: Int): JSONObject? {
        if (open < 0) return null
        val slice = sliceBalanced(text, open, '{', '}') ?: return null
        return runCatching { JSONObject(slice) }.getOrNull()
    }

    private fun sliceBalanced(text: String, open: Int, openChar: Char, closeChar: Char): String? {
        if (open < 0 || open >= text.length) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in open until text.length) {
            val char = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                openChar -> depth++
                closeChar -> {
                    depth--
                    if (depth == 0) return text.substring(open, index + 1)
                }
            }
        }
        return null
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    companion object {
        /** Ana sayfa satırları; ayarlardan kapatılabilir. */
        val ROWS = listOf(
            "Son Eklenen Diziler",
            "Son Eklenen Filmler",
            "Diziler — Dram",
            "Diziler — Suç",
            "Trend",
        )

        private const val PAGE_TOKEN = "SAYFA"
        private const val KEY_PREFIX = "\"k\":\""
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"

        private val EPISODE_SUFFIX = Regex("""/sezon-\d+/bolum-\d+.*""")
        private val RANK_PREFIX = Regex("""^\d+\.\s*sırada,\s*""")
        private val IMDB = Regex("""IMDb\s*([\d.]+)""")
        private val QUALITY = Regex(""""quality"\s*:\s*"(\d{3,4})[pP]?""")
        private val ORIGIN = Regex("""^https?://[^/]+""")
        private val RUNTIME = Regex("""(\d+)\s*sa\s*(\d+)\s*dk""")
    }
}
