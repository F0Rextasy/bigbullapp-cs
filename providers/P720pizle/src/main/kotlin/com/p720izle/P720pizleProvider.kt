package com.p720izle

import com.bigbull.common.browserHeaders
import com.bigbull.common.pichiveSign
import com.bigbull.common.posterSrcLazy
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 720pizle — 720pizle.my (macellan CMS, geniş HD/4K film arşivi).
 *
 * Yollar: film `/izle/<slug>`, listeler `/son-eklenen/<sayfa>` ve
 * `/kategori/<tür>/page/<sayfa>`; kartlar `article.card` (afiş, yıl, IMDb rozeti).
 *
 * Arama: ana sayfadaki `form[data-action=/bg/searchcontentfulltext]` içindeki
 * `cKey`/`cValue` jetonlarıyla aynı uca form POST'u; yanıt `data.result[]`
 * (`used_type: "Movies"`, `used_slug: "izle/<slug>"`). Jeton bayatlarsa bir kez
 * tazelenip yinelenir.
 *
 * Oynatıcı iki pichive varyantından biri: yeni katalog `//four.pichive.online/iframe.php?v=<hash>`
 * basar ve `window.NP_CONFIG` içinde `sources[].master` (nm.php proof-of-work imzası →
 * [pichiveSign]) ile `subtitles[].url|tok` (nsub.php, WebVTT) taşır; eski katalog
 * `pichive.online/multiplayer.php` içinde `openPlayer('<jeton>')` basar, jeton
 * `source2.php` playlist'ini (`m.php` → `master.m3u8`) verir. Kalite seçimi oynatıcıya
 * bırakılır (master ayrı ses grubu taşıyabiliyor).
 */
class P720pizleProvider : MainAPI() {
    override var mainUrl = "https://720pizle.my"
    override var name = "720pizle"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    private val posterHdrs get() = browserHeaders + mapOf("Referer" to "$mainUrl/")

    override val mainPage get() = ROWS.mapNotNull { row ->
        if (!Settings.rowEnabled(row)) return@mapNotNull null
        val path = ROW_PATHS[row] ?: return@mapNotNull null
        MainPageData(row, mainUrl + path)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = ROW_PATHS[request.name] ?: return newHomePageResponse(request.name, emptyList(), false)
        val url = if (page <= 1) {
            mainUrl + path
        } else {
            mainUrl + if (path == "/son-eklenen") "$path/$page" else "$path/page/$page"
        }
        val doc = runCatching { app.get(url, headers = browserHeaders).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: liste yüklenemedi. $REPORT_HINT")
        val items = doc.select("article.card").mapNotNull { it.toCard() }.distinctBy { it.url }
        // Liste sayfaları `ul.pagination` (/son-eklenen), tür sayfaları `ul.pager` kullanıyor.
        val hasNext = doc.select("ul.pagination a, ul.pager a").any { it.text().trim() == "Sonraki" }
        return newHomePageResponse(request.name, items, hasNext)
    }

    // ---------------------------------------------------------------- arama

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        var message = "bilinmeyen hata"
        repeat(2) {
            val token = searchToken()
            val body = app.post(
                "$mainUrl/bg/searchcontentfulltext",
                headers = browserHeaders + mapOf(
                    "accept" to "application/json, text/javascript, */*; q=0.01",
                    "referer" to "$mainUrl/",
                    "x-requested-with" to "XMLHttpRequest",
                ),
                data = mapOf("cKey" to token.first, "cValue" to token.second, "searchTerm" to term),
            ).text
            val data = runCatching { JSONObject(body).optJSONObject("data") }.getOrNull()
                ?: throw ErrorLoadingException("$name: arama yanıtı okunamadı. $REPORT_HINT")
            if (data.optBoolean("state")) {
                val rows = data.optJSONArray("result") ?: JSONArray()
                return (0 until rows.length()).mapNotNull { index -> rows.optJSONObject(index)?.toSearchCard() }
                    .distinctBy { it.url }
            }
            message = data.optString("message").trim().ifEmpty { "bilinmeyen hata" }
        }
        throw ErrorLoadingException("$name: arama başarısız ($message). $REPORT_HINT")
    }

    /** Ana sayfadaki arama formunun oturum jetonları; bayat jeton `state:false` döndürür. */
    private suspend fun searchToken(): Pair<String, String> {
        val doc = runCatching { app.get("$mainUrl/", headers = browserHeaders).document }.getOrNull()
        val form = doc?.selectFirst("form[data-action*='searchcontentfulltext']")
        val key = form?.selectFirst("input[name=cKey]")?.attr("value")?.trim().orEmpty()
        val value = form?.selectFirst("input[name=cValue]")?.attr("value")?.trim().orEmpty()
        if (key.isEmpty() || value.isEmpty()) throw ErrorLoadingException(JETON_HINT)
        return key to value
    }

    private fun JSONObject.toSearchCard(): SearchResponse? {
        if (optString("used_type") != "Movies") return null
        val title = optString("object_name").trim().ifEmpty { optString("object_name_alternative").trim() }
        if (title.isEmpty()) return null
        val slug = optString("used_slug").trim().trim('/')
        if (slug.isEmpty()) return null
        val url = if (slug.startsWith("http")) slug else "$mainUrl/$slug"
        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = fixUrlNull(optString("object_poster_url"))
            this.posterHeaders = posterHdrs
            this.year = optInt("object_release_year", 0).takeIf { it in 1900..2100 }
            this.score = optDouble("object_related_imdb_point", 0.0).takeIf { it > 0 }
                ?.let { Score.from10(it) }
        }
    }

    // ---------------------------------------------------------------- künye

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = fixUrl(url)
        val doc = runCatching { app.get(pageUrl, headers = browserHeaders).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: film sayfası yüklenemedi. $REPORT_HINT")
        val schema = doc.movieSchema()
        val heading = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        // og:title sitenin kanonik adı ("Fifty Shades of Grey", "Ever Victorious"); h1
        // kimi filmlerde yalnızca özgün dildeki adı taşıyor.
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?.substringBefore(" izle")?.trim()?.takeIf { it.isNotEmpty() }
            ?: heading.replace(YEAR_SUFFIX, "").trim()
        if (title.isEmpty()) throw ErrorLoadingException("$name: film adı okunamadı. $REPORT_HINT")

        return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
            this.posterUrl = doc.selectFirst("img.detail-poster-image")?.posterSrcLazy()
            this.posterHeaders = posterHdrs
            this.plot = doc.selectFirst("div.synopsis")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: schema?.optString("description")?.trim()?.takeIf { it.isNotEmpty() }
            this.year = doc.labeledValue("Yayın")?.selectFirst("a[href*=/yil/]")?.text()
                ?.let { YEAR.find(it)?.value?.toIntOrNull() }
                ?: YEAR_SUFFIX.find(heading)?.groupValues?.get(1)?.toIntOrNull()
            this.duration = doc.selectFirst("span.runtime")?.text()
                ?.substringBefore(" dk")?.trim()?.toIntOrNull()
            this.tags = doc.labeledValue("Tür")?.select("a[href*=/kategori/]")
                ?.map { it.text().trim() }?.filter { it.isNotEmpty() }?.distinct().orEmpty()
            this.score = doc.selectFirst("div.score--imdb b")?.text()?.trim()?.toDoubleOrNull()
                ?.takeIf { it > 0 }?.let { Score.from10(it) }
            addActors(schema?.optJSONArray("actor").names())
        }
    }

    /** `div.meta-row` satırları: `<span class=label>Tür</span> ... <span class=value>…` */
    private fun Document.labeledValue(label: String): Element? =
        select("div.meta-row").firstOrNull { it.selectFirst("span.label")?.text()?.trim() == label }
            ?.selectFirst("span.value")

    private fun Document.movieSchema(): JSONObject? =
        select("script[type='application/ld+json']").firstNotNullOfOrNull { script ->
            runCatching { JSONObject(script.data()) }.getOrNull()
                ?.takeIf { it.optString("@type") == "Movie" || it.optString("@type") == "TVMovie" }
        }

    private fun JSONArray?.names(): List<String> = this?.let { array ->
        (0 until array.length()).mapNotNull {
            array.optJSONObject(it)?.optString("name")?.trim()?.takeIf(String::isNotEmpty)
        }
    }.orEmpty()

    // ---------------------------------------------------------------- listeler

    private fun Element.toCard(): SearchResponse? {
        val link = selectFirst("a.card__art") ?: selectFirst("a[href*=/izle/]") ?: return null
        val url = fixUrlNull(link.attr("href")) ?: return null
        val title = link.attr("title").trim()
            .ifEmpty { selectFirst("h3.card__title")?.text()?.trim().orEmpty() }
            .replace(IZLE_SUFFIX, "").trim()
        if (title.isEmpty()) return null
        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = selectFirst("img.poster-img")?.posterSrcLazy()
                ?: selectFirst("img")?.posterSrcLazy()
            this.posterHeaders = posterHdrs
            this.year = selectFirst("a[href*=/yil/]")?.text()
                ?.let { YEAR.find(it)?.value?.toIntOrNull() }
            this.score = selectFirst("span.tag--imdb")?.text()
                ?.let { SCORE.find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
                ?.takeIf { it > 0 }?.let { Score.from10(it) }
        }
    }

    /** Film sayfası oynatıcı iframe'i (`four.pichive.online/iframe.php` ya da `pichive.online/multiplayer.php`). */
    private fun Document.playerIframe(): String? =
        (selectFirst("div.player iframe")
            ?: selectFirst("iframe[src*='iframe.php']")
            ?: selectFirst("iframe[src*='multiplayer.php']"))
            ?.let { el -> el.attr("src").ifBlank { el.attr("data-src") } }
            ?.trim()?.takeIf { it.isNotEmpty() }?.let { httpsUrl(it) }

    // ---------------------------------------------------------------- oynatma

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageUrl = fixUrl(data)
        val page = runCatching { app.get(pageUrl, headers = browserHeaders).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: film sayfası yüklenemedi. $REPORT_HINT")
        val embed = page.playerIframe()
            ?: throw ErrorLoadingException("$name: oynatıcı iframe bulunamadı. $REPORT_HINT")
        val player = app.get(embed, headers = browserHeaders, referer = pageUrl)
        if (player.code == 403) {
            throw ErrorLoadingException("$name: oynatıcı sunucusu isteği engelledi (HTTP 403). $REPORT_HINT")
        }

        val quality = Qualities.Unknown.value
        val seen = HashSet<String>()
        var emitted = false
        suspend fun emit(embedUrl: String, stream: String, label: String, type: ExtractorLinkType) {
            if (!stream.startsWith("http") || !seen.add(stream)) return
            if (!Settings.qualityAllowed(quality)) return
            callback(
                newExtractorLink(name, label, stream, type) {
                    this.referer = embedUrl
                    this.headers = browserHeaders
                    // Master ayrı ses grubu taşıyor; kalite seçimi oynatıcıya bırakılır.
                    this.quality = quality
                }
            )
            emitted = true
        }

        suspend fun play(embedUrl: String, html: String) {
            val config = jsonObjectAfter(html, "window.NP_CONFIG")
            if (config != null) {
                config.optJSONArray("sources").objects().forEach { source ->
                    val master = source.optString("master").takeIf { it.startsWith("http") }
                        ?.let { pichiveSign(html, it) } ?: return@forEach
                    emit(embedUrl, master, source.optString("title").trim().ifEmpty { name }, ExtractorLinkType.M3U8)
                }
                val playerOrigin = ORIGIN.find(embedUrl)?.value.orEmpty()
                config.optJSONArray("subtitles").objects().forEach { track ->
                    val raw = track.optString("url").takeIf { it.startsWith("http") }
                        ?: track.optString("tok").takeIf { it.isNotEmpty() }
                            ?.let { "$playerOrigin/nsub.php?v=$it" }
                        ?: return@forEach
                    val language = track.optString("lang").ifEmpty { track.optString("label") }
                    if (!Settings.subtitleAllowed(language)) return@forEach
                    val label = track.optString("label").trim().ifEmpty { language.ifEmpty { "Altyazı" } }
                    subtitleCallback(
                        newSubtitleFile(label, pichiveSign(html, raw)) {
                            this.headers = browserHeaders + ("Referer" to embedUrl)
                        }
                    )
                }
                return
            }
            // Eski katalog (ör. 2009 yapımları) ynp oynatıcısını basıyor: `openPlayer('<jeton>')`
            // jetonu `source2.php`'den playlist JSON'u alır, kaynak adresi `m.php` → `master.m3u8`.
            val token = Regex("""openPlayer\(\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1) ?: return
            val origin = ORIGIN.find(embedUrl)?.value.orEmpty()
            val playlist = runCatching {
                JSONObject(app.get("$origin/source2.php?v=$token", headers = browserHeaders, referer = embedUrl).text)
            }.getOrNull()?.optJSONArray("playlist").objects().orEmpty()
            playlist.forEach { item ->
                val itemTitle = item.optString("title").trim()
                item.optJSONArray("sources").objects().forEach { source ->
                    val file = source.optString("file").takeIf { it.startsWith("http") } ?: return@forEach
                    val type = if (file.contains("/m.php") || file.contains(".m3u8") ||
                        source.optString("mimeType").contains("mpegurl")
                    ) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    val stream = if (file.contains("/m.php")) file.replace("/m.php", "/master.m3u8") else file
                    val label = source.optString("title").trim().ifEmpty { itemTitle }.ifEmpty { name }
                    emit(embedUrl, stream, label, type)
                }
            }
        }

        play(embed, player.text)
        if (jsonObjectAfter(player.text, "window.NP_CONFIG") == null) {
            // Dil seçenekleri ayrı multiplayer oynatıcı basabiliyor; hazır master oradan gelir.
            page.select("a[href*='?lang=']").mapNotNull { fixUrlNull(it.attr("href")) }.distinct()
                .forEach { langUrl ->
                    val langPage = runCatching { app.get(langUrl, headers = browserHeaders).document }.getOrNull()
                        ?: return@forEach
                    val alt = langPage.playerIframe() ?: return@forEach
                    if (alt == embed) return@forEach
                    val altHtml = runCatching {
                        app.get(alt, headers = browserHeaders, referer = langUrl).text
                    }.getOrNull() ?: return@forEach
                    play(alt, altHtml)
                }
        }
        if (!emitted) throw ErrorLoadingException("$name: oynatılabilir kaynak yok. $REPORT_HINT")
        return true
    }


    // ---------------------------------------------------------------- json

    private fun httpsUrl(raw: String): String? {
        val url = raw.trim()
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http") -> url
            else -> null
        }
    }

    private fun jsonObjectAfter(text: String, marker: String): JSONObject? {
        val at = text.indexOf(marker)
        if (at < 0) return null
        val open = text.indexOf('{', at)
        if (open < 0) return null
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
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return runCatching { JSONObject(text.substring(open, index + 1)) }.getOrNull()
                    }
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
            "Son Eklenenler",
            "Aksiyon",
            "Komedi",
            "Korku",
            "Bilim Kurgu",
            "Dram",
        )

        private val ROW_PATHS = linkedMapOf(
            "Son Eklenenler" to "/son-eklenen",
            "Aksiyon" to "/kategori/aksiyon-filmleri",
            "Komedi" to "/kategori/komedi-filmleri",
            "Korku" to "/kategori/korku-filmleri",
            "Bilim Kurgu" to "/kategori/bilim-kurgu-filmleri",
            "Dram" to "/kategori/dram-filmleri",
        )

        private const val JETON_HINT =
            "720pizle: arama jetonu okunamadı (site arama formunu değiştirmiş olabilir). " +
                "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"

        private val YEAR_SUFFIX = Regex("""\((\d{4})\)""")
        private val YEAR = Regex("""\b(19|20)\d{2}\b""")
        private val SCORE = Regex("""([0-9]+(?:\.[0-9]+)?)""")
        private val IZLE_SUFFIX = Regex("""\s+izle$""", RegexOption.IGNORE_CASE)
        private val ORIGIN = Regex("""^https?://[^/]+""")
    }
}
