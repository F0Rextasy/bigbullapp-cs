package com.selcukflix

import com.bigbull.common.pichiveSign
import android.util.Base64
import com.lagradost.cloudstream3.Episode
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
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.bigbull.common.pbkdf2AesCbcDecrypt
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * SelcukFlix — selcukflix.app (DiziPal ile aynı Next.js platformu).
 *
 * Arama `POST /api/mj` (`action=searchContent`). Film `/film/<slug>/izle`, dizi `/dizi/<slug>`
 * ve bölüm `/dizi/<slug>/sezon-S/bolum-E` adresleridir; sayfa RSC yükü `enc` (ciphertext/iv/salt)
 * ve sayfaya özel `k` parolasını taşır. PBKDF2-HMAC-SHA512(999) + AES-256-CBC çözümü pichive
 * oynatıcı adresini (`iframe.php?v=…`) verir; oynatıcı `NP_CONFIG.sources[].master` HLS master'ı
 * sunar. Dublaj/altyazı alternatifleri aynı yükte `sources[{label,href}]` olarak listelenir.
 */
class SelcukFlixProvider : MainAPI() {
    override var mainUrl = "https://selcukflix.app"
    override var name = "SelcukFlix"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val uaHeaders get() = mapOf("user-agent" to UA, "Accept-Language" to "tr-TR,tr;q=0.9")

    override val mainPage get() = ROW_PATHS.mapNotNull { (title, path) ->
        if (!Settings.rowEnabled(title)) return@mapNotNull null
        MainPageData(title, "$mainUrl$path")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}?page=$page" else request.data
        val doc = runCatching { app.get(url, headers = uaHeaders).document }.getOrNull()
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val items = doc.select("a.tc[href]").mapNotNull { it.toCard() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.length < 3) return emptyList()
        val body = runCatching {
            app.post(
                "$mainUrl/api/mj",
                json = JSONObject().put("action", "searchContent").put("params", JSONArray().put(q)),
                headers = uaHeaders + mapOf("Content-Type" to "application/json", "Origin" to mainUrl),
                referer = "$mainUrl/",
            ).text
        }.getOrNull() ?: throw ErrorLoadingException("$name: arama isteği başarısız. $REPORT_HINT")
        val rows = runCatching { JSONObject(body).optJSONArray("result") }.getOrNull()
            ?: throw ErrorLoadingException("$name: arama yanıtı okunamadı. $REPORT_HINT")
        return rows.objects().mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = httpsUrl(url)
        val doc = runCatching { app.get(pageUrl, headers = uaHeaders).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: içerik sayfası yüklenemedi. $REPORT_HINT")
        val isSeries = pageUrl.contains("/dizi/")
        val ld = jsonLd(doc, if (isSeries) "TVSeries" else "Movie")
        val title = ld?.optString("name")?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("$name: başlık okunamadı. $REPORT_HINT")
        val plot = ld?.optString("description")?.trim()?.takeIf { it.isNotEmpty() }
        val poster = ld?.optString("image")?.takeIf { it.startsWith("http") }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.startsWith("http") }
        val year = ld?.optString("datePublished")?.take(4)?.toIntOrNull()?.takeIf { it in 1900..2100 }
        val score = Score.from10(ld?.optJSONObject("aggregateRating")?.optString("ratingValue"))
        val duration = minutes(ld?.optString("timeRequired"))
        val tags = doc.select("a[href^='/dizi/tur/'], a[href^='/film/tur/']")
            .map { it.text().trim() }.filter { it.isNotEmpty() }.distinct().takeIf { it.isNotEmpty() }
        val actors = ld?.optJSONArray("actor").objects()
            .mapNotNull { it.optString("name").trim().takeIf(String::isNotEmpty) }
            .takeIf { it.isNotEmpty() }

        if (isSeries) {
            val episodes = episodesOf(pageUrl, doc)
            if (episodes.isEmpty()) throw ErrorLoadingException("$name: bölüm listesi bulunamadı. $REPORT_HINT")
            return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = score
                this.duration = duration
                this.tags = tags
                actors?.let { addActors(it) }
            }
        }
        return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.score = score
            this.duration = duration
            this.tags = tags
            actors?.let { addActors(it) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val links: (ExtractorLink) -> Unit = { if (Settings.qualityAllowed(it.quality)) callback(it) }
        val subtitles: (SubtitleFile) -> Unit = { if (Settings.subtitleAllowed(it.lang)) subtitleCallback(it) }

        val pageUrl = httpsUrl(data)
        val pageHtml = runCatching { app.get(pageUrl, headers = uaHeaders, referer = "$mainUrl/").text }
            .getOrNull() ?: throw ErrorLoadingException("$name: sayfa yüklenemedi. $REPORT_HINT")

        // Etkin kaynak + (dublaj/altyazı) alternatifleri; her biri kendi sayfa yükünü taşır.
        val here = pageUrl.removePrefix(mainUrl)
        val alternatives = SOURCE_ENTRY.findAll(pageHtml)
            .map { it.groupValues[1] to it.groupValues[2] }
            .filter { (_, href) -> href.startsWith("/") && href.contains("?") && href != here }
            .distinctBy { it.second }
            .take(3)
            .toList()

        var found = playPage(pageHtml, pageUrl, ownLabel(pageHtml, here), links, subtitles)
        for ((label, href) in alternatives) {
            val url = mainUrl + href
            val html = runCatching { app.get(url, headers = uaHeaders, referer = "$mainUrl/").text }.getOrNull()
                ?: continue
            if (playPage(html, url, label, links, subtitles)) found = true
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir kaynak bulunamadı. $REPORT_HINT")
        return true
    }

    // ---------------------------------------------------------------- listeler

    private fun Element.toCard(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val isSeries = href.contains("/dizi/")
        if (!isSeries && !href.contains("/film/")) return null
        val parts = attr("aria-label").split(",").map { it.trim() }
        val title = parts.firstOrNull()?.takeIf { it.isNotEmpty() }
            ?: selectFirst("img[alt]")?.attr("alt")?.removeSuffix(" izle")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val poster = selectFirst("img[src^=http]")?.attr("src")
        val year = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1900..2100 }
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    private fun JSONObject.toSearchResult(): SearchResponse? {
        val slug = optString("used_slug").trim().trimStart('/')
        if (slug.isEmpty()) return null
        val title = optString("object_name").trim().takeIf { it.isNotEmpty() } ?: return null
        val url = "$mainUrl/$slug"
        val poster = optString("object_poster_url").trim().takeIf { it.startsWith("http") }
            ?.let { if (IMAGE_PROXY in it) "https://" + it.substringAfter(IMAGE_PROXY) else it }
        val year = optString("object_release_year").toIntOrNull()?.takeIf { it in 1900..2100 }
        val score = Score.from10(optString("object_related_imdb_point").takeIf { it != "0" })
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

    // ------------------------------------------------------------------ künye

    /** Dizi sayfası yalnız etkin sezonun bölümlerini listeler; diğer sezonlar `/sezon-N` sayfasındadır. */
    private suspend fun episodesOf(seriesUrl: String, first: Document): List<Episode> {
        val base = seriesUrl.substringBefore("?").trimEnd('/')
        val slug = base.substringAfter("/dizi/").substringBefore("/")
        val seasons = first.select("a[href^='/dizi/$slug/sezon-']").map { it.attr("href") }
            .mapNotNull { SEASON_ONLY.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
            .distinct().sorted().ifEmpty { listOf(1) }

        val episodes = LinkedHashMap<String, Episode>()
        for (season in seasons) {
            val doc = if (season == 1) first else
                runCatching { app.get("$mainUrl/dizi/$slug/sezon-$season", headers = uaHeaders).document }
                    .getOrNull() ?: continue
            doc.select("a[href^='/dizi/$slug/sezon-$season/bolum-']").forEach { anchor ->
                val href = anchor.attr("href").substringBefore("?")
                val number = EPISODE_URL.matchEntire(href)?.groupValues?.get(2)?.toIntOrNull()
                    ?: return@forEach
                if (episodes.containsKey(href)) return@forEach
                val label = anchor.attr("aria-label").substringAfter(": ", "").trim()
                episodes[href] = newEpisode("$mainUrl$href") {
                    this.name = label.ifEmpty { "$season. Sezon $number. Bölüm" }
                    this.season = season
                    this.episode = number
                }
            }
        }
        return episodes.values.toList()
    }

    private fun jsonLd(doc: Document, type: String): JSONObject? =
        doc.select("script[type=application/ld+json]").firstNotNullOfOrNull { script ->
            runCatching { JSONObject(script.data()) }.getOrNull()?.takeIf { it.optString("@type") == type }
        }

    /** ISO-8601 süre (`PT1H46M`) → dakika. */
    private fun minutes(iso: String?): Int? {
        val match = ISO_DURATION.find(iso.orEmpty()) ?: return null
        val total = (match.groupValues[1].toIntOrNull() ?: 0) * 60 + (match.groupValues[2].toIntOrNull() ?: 0)
        return total.takeIf { it > 0 }
    }

    // --------------------------------------------------------------- oynatıcı

    private fun ownLabel(html: String, here: String): String =
        SOURCE_ENTRY.findAll(html).firstOrNull { it.groupValues[2] == here }?.groupValues?.get(1).orEmpty()

    private suspend fun playPage(
        html: String,
        pageUrl: String,
        label: String,
        emit: (ExtractorLink) -> Unit,
        emitSubtitle: (SubtitleFile) -> Unit,
    ): Boolean {
        val playerUrl = decryptEmbed(html) ?: return false
        val playerHtml = runCatching { app.get(playerUrl, headers = uaHeaders, referer = pageUrl).text }
            .getOrNull() ?: return false
        return npLinks(playerHtml, playerUrl, label, emit, emitSubtitle) ||
            dplayerLinks(playerHtml, playerUrl, label, emit)
    }

    /** Parola `k` anahtarında durur; aynı yükte sayfa yolu da `k` olarak geçtiği için `/` içerenler elenir. */
    private fun decryptEmbed(html: String): String? {
        val fields = ENC_FIELD.findAll(html).groupBy({ it.groupValues[1] }) { it.groupValues[2] }
        val cipherText = fields["ciphertext"]?.firstOrNull() ?: return null
        val iv = fields["iv"]?.firstOrNull() ?: return null
        val salt = fields["salt"]?.firstOrNull() ?: return null
        val passphrase = fields["k"]?.filter { '/' !in it }?.maxByOrNull { it.length } ?: return null
        val clear = pbkdf2AesCbcDecrypt(Base64.decode(cipherText, Base64.DEFAULT), passphrase, salt, iv)
            ?: return null
        return httpsUrl(clear.trim('\t', '\r', '\n', ' ', '\u000f'))
    }

    private suspend fun npLinks(
        playerHtml: String,
        playerUrl: String,
        label: String,
        emit: (ExtractorLink) -> Unit,
        emitSubtitle: (SubtitleFile) -> Unit,
    ): Boolean {
        val anchor = playerHtml.indexOf("window.NP_CONFIG")
        val open = if (anchor < 0) -1 else playerHtml.indexOf('{', anchor)
        if (open < 0) return false
        val config = objectAt(playerHtml, open)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return false

        config.optJSONArray("subtitles").objects().forEach { track ->
            val url = track.optString("url").takeIf { it.startsWith("http") }?.let { pichiveSign(playerHtml, it) }
                ?: track.optString("tok").takeIf { it.isNotEmpty() }
                    ?.let { pichiveSign(playerHtml, "${ORIGIN.find(playerUrl)?.value.orEmpty()}/nsub.php?v=$it") }
                ?: return@forEach
            val lang = track.optString("label").trim().ifEmpty { track.optString("lang").trim() }
                .ifEmpty { "Altyazı" }
            emitSubtitle(SubtitleFile(lang, url).apply {
                this.headers = mapOf("Referer" to playerUrl, "user-agent" to UA)
            })
        }

        var found = false
        config.optJSONArray("sources").objects().forEach { source ->
            val master = source.optString("master").takeIf { it.startsWith("http") }?.let { pichiveSign(playerHtml, it) } ?: return@forEach
            val title = source.optString("title").trim().ifEmpty { "Kaynak" }
            emitPlaylist(master, playerUrl, listOf(label, title).filter { it.isNotEmpty() }.joinToString(" · "), emit)
            found = true
        }
        return found
    }

    private suspend fun dplayerLinks(
        playerHtml: String,
        playerUrl: String,
        label: String,
        emit: (ExtractorLink) -> Unit,
    ): Boolean {
        val playlist = OPEN_PLAYER.find(playerHtml)?.groupValues?.get(1) ?: return false
        val origin = ORIGIN.find(playerUrl)?.value ?: return false
        val body = runCatching {
            app.get(
                "$origin/source2.php",
                params = mapOf("v" to playlist),
                headers = uaHeaders + mapOf("X-Requested-With" to "XMLHttpRequest"),
                referer = playerUrl,
            ).text
        }.getOrNull() ?: return false
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return false

        var found = false
        root.optJSONArray("playlist").objects().forEach { item ->
            val title = item.optString("title").trim().ifEmpty { "Kaynak" }
            item.optJSONArray("sources").objects().forEach eachSource@{ source ->
                val file = source.optString("file").takeIf { it.startsWith("http") } ?: return@eachSource
                val master = if ("m.php" in file) file.replace("m.php", "master.m3u8") else file
                emitPlaylist(master, playerUrl, listOf(label, title).filter { it.isNotEmpty() }.joinToString(" · "), emit)
                found = true
            }
        }
        return found
    }

    /** HLS master'ı varyantlarına ayırır; tek varyant kaldıysa master olduğu gibi (`Unknown`) verilir. */
    private suspend fun emitPlaylist(
        masterUrl: String,
        referer: String,
        label: String,
        emit: (ExtractorLink) -> Unit,
    ) {
        val playlist = runCatching { app.get(masterUrl, headers = uaHeaders, referer = referer).text }.getOrNull()
        val variants = parseVariants(playlist.orEmpty())
        if (variants.size <= 1) {
            emit(hlsLink(masterUrl, label, Qualities.Unknown.value, referer))
            return
        }
        variants.forEach { (height, url) ->
            val quality = height.takeIf { it > 0 } ?: Qualities.Unknown.value
            emit(hlsLink(url, if (quality > 0) "$label ${quality}p" else label, quality, referer))
        }
    }

    private fun parseVariants(playlist: String): List<Pair<Int, String>> {
        if (!playlist.startsWith("#EXTM3U") ||
            playlist.contains("TYPE=AUDIO", ignoreCase = true) ||
            playlist.contains("AUDIO=", ignoreCase = true)
        ) return emptyList()
        val variants = mutableListOf<Pair<Int, String>>()
        var height: Int? = null
        for (raw in playlist.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                height = RESOLUTION.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                continue
            }
            val pending = height ?: continue
            if (line.isEmpty() || line.startsWith("#")) continue
            variants.add(pending to line)
            height = null
        }
        return variants.distinctBy { it.second }
    }

    private suspend fun hlsLink(url: String, label: String, quality: Int, referer: String): ExtractorLink =
        newExtractorLink(name, label, url, ExtractorLinkType.M3U8) {
            this.referer = referer
            this.quality = quality
            this.headers = uaHeaders
        }

    // ------------------------------------------------------------------ ortak

    /** `window.NP_CONFIG = {…}` gövdesini süslü parantez sayarak çıkarır (string içindekiler atlanır). */
    private fun objectAt(html: String, from: Int): String? {
        if (from < 0 || from >= html.length || html[from] != '{') return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in from until html.length) {
            val char = html[index]
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
                    if (depth == 0) return html.substring(from, index + 1)
                }
            }
        }
        return null
    }

    private fun httpsUrl(raw: String?): String {
        val url = raw?.trim().orEmpty()
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http://") -> "https://" + url.removePrefix("http://")
            else -> url
        }
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    companion object {
        private val ROW_PATHS = linkedMapOf(
            "Yeni Filmler" to "/film-izle",
            "Yeni Diziler" to "/dizi-izle",
            "Yabancı Filmler" to "/yabanci-film-izle",
            "Türkçe Dublaj Diziler" to "/turkce-dublaj-dizi-izle",
            "Türkçe Altyazılı Diziler" to "/turkce-altyazili-dizi-izle",
            "Anime" to "/anime-izle",
            "K-Drama" to "/kdrama-izle",
        )
        /** Ana sayfa satırları; ayarlardan kapatılabilir. */
        val ROWS: List<String> = ROW_PATHS.keys.toList()

        /** Oynatıcı CDN'i Chrome tarzı UA'lara 403 verebildiği için Firefox UA kullanılır. */
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
        private const val IMAGE_PROXY = "/i/s/"

        private val ISO_DURATION = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?""")
        private val RESOLUTION = Regex("""RESOLUTION=\d+x(\d+)""")
        private val OPEN_PLAYER = Regex("""openPlayer\s*\(\s*['"]([^'"]+)""")
        private val ORIGIN = Regex("""^https?://[^/]+""")
        private val SEASON_ONLY = Regex("""/dizi/[^/]+/sezon-(\d+)""")
        private val EPISODE_URL = Regex("""/dizi/[^/]+/sezon-(\d+)/bolum-(\d+)""")

        /** RSC kaçışlı `{"label":"…","quality":"…","href":"…"}` kayıtları (dublaj/altyazı kaynakları). */
        private val SOURCE_ENTRY = Regex(
            """\\{0,3}"label\\{0,3}":\\{0,3}"([^"\\]+)\\{0,3}",(?:\\{0,3}"quality\\{0,3}":\\{0,3}"[^"\\]*\\{0,3}",)?\\{0,3}"href\\{0,3}":\\{0,3}"([^"\\]+)""",
        )
        private val ENC_FIELD = Regex("""\\{0,3}"(ciphertext|iv|salt|k)\\{0,3}":\\{0,3}"([^"\\]+)""")
    }
}
