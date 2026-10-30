package com.dizipal

import android.util.Base64
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
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
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * DiziPal — dizipal1592.com (Next.js arayüzü, Türkçe dizi + film, platform bölümleri).
 *
 * Arama `POST /api/mj` JSON servisine gider (`action=searchContent`). Liste ve detay sayfaları
 * sunucu tarafında render edilir; künye `application/ld+json` (TVSeries/Movie) bloğundadır.
 *
 * Oynatıcı zinciri: bölüm/film sayfası RSC yükünde `enc` (ciphertext/iv/salt) ve aynı yükte
 * sayfa başına üretilen `k` parolası taşınır. PBKDF2-HMAC-SHA512(999) + AES-256-CBC çözümü
 * `//<host>.dplayer82.site/(iframe|multiplayer).php?v=…` adresini verir. İki oynatıcı ailesi var:
 *  - `NP_CONFIG` (iframe.php): `sources[].master` doğrudan HLS master; `subtitles[].url` WebVTT.
 *  - `openPlayer(blob)` (multiplayer.php): `/source2.php?v=blob` JSON'u m3u8 dosya yolları verir.
 * Her iki CDN de isteklerde oynatıcı sayfasını `Referer` olarak şart koşar.
 */
class DiziPalProvider : MainAPI() {
    override var mainUrl = "https://dizipal1592.com"
    override var name = "DiziPal"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val uaHeaders = mapOf(
        "User-Agent" to UA,
        "Accept-Language" to "tr-TR,tr;q=0.9",
    )

    /** Yalnız bu iki bölüm `?page=N` ile sayfalanıyor; diğerleri tek istekte tüm listeyi verir. */
    private val pagedSections = setOf("$mainUrl/yabanci-dizi-izle", "$mainUrl/film-izle")

    override val mainPage get() = mainPageOf(
        "$mainUrl/yabanci-dizi-izle" to "Yeni Diziler",
        "$mainUrl/film-izle" to "Yeni Filmler",
        "$mainUrl/trendler" to "Trendler",
        "$mainUrl/kesfet" to "Keşfet",
        "$mainUrl/platform-bolumler/netflix" to "Netflix",
        "$mainUrl/platform-bolumler/prime" to "Amazon Prime",
        "$mainUrl/platform-bolumler/disneyplus" to "Disney+",
    ).filter { Settings.rowEnabled(it.name) }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val paged = request.data in pagedSections
        val url = if (paged && page > 1) "${request.data}?page=$page" else request.data
        val doc = app.get(url, headers = uaHeaders).document
        val items = doc.select("a.tc[href]").mapNotNull { it.toCard() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = paged && items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim()
        // Servis üç karakterden kısa sorguları 400 ile reddediyor.
        if (term.length < 3) return emptyList()

        val payload = JSONObject()
            .put("action", "searchContent")
            .put("params", JSONArray().put(term))
        val body = runCatching {
            app.post(
                "$mainUrl/api/mj",
                json = payload,
                headers = uaHeaders + mapOf(
                    "Accept" to "application/json, text/plain, */*",
                    "Origin" to mainUrl,
                ),
                referer = "$mainUrl/",
            ).text
        }.getOrNull() ?: throw ErrorLoadingException("$name: arama servisi yanıt vermedi. $REPORT_HINT")

        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: throw ErrorLoadingException("$name: arama yanıtı okunamadı. $REPORT_HINT")
        return root.optJSONArray("result").objects()
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = httpsUrl(url)
        val doc = runCatching { app.get(pageUrl, headers = uaHeaders).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: içerik sayfası yüklenemedi. $REPORT_HINT")
        val isSeries = pageUrl.contains("/series/")
        val ld = jsonLd(doc, if (isSeries) "TVSeries" else "Movie")
        val title = ld?.optString("name")?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("$name: başlık okunamadı. $REPORT_HINT")

        if (isSeries) {
            val episodes = episodesOf(doc, ld)
            if (episodes.isEmpty()) {
                throw ErrorLoadingException("$name: bölüm listesi bulunamadı. $REPORT_HINT")
            }
            return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                this.posterUrl = posterOf(doc, ld)
                this.plot = ld?.optString("description")?.trim()?.takeIf { it.isNotEmpty() }
                this.year = yearOf(ld)
                this.score = Score.from10(ld?.optJSONObject("aggregateRating")?.optString("ratingValue"))
                this.duration = minutes(ld?.optString("timeRequired"))
                this.tags = tagsOf(doc).takeIf { it.isNotEmpty() }
                this.recommendations = recommendationsOf(doc, isSeries)
                actorsOf(ld)?.let { addActors(it) }
            }
        }

        return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
            this.posterUrl = posterOf(doc, ld)
            this.plot = ld?.optString("description")?.trim()?.takeIf { it.isNotEmpty() }
            this.year = yearOf(ld)
            this.score = Score.from10(ld?.optJSONObject("aggregateRating")?.optString("ratingValue"))
            this.duration = minutes(ld?.optString("timeRequired"))
            this.tags = tagsOf(doc).takeIf { it.isNotEmpty() }
            this.recommendations = recommendationsOf(doc, isSeries)
            actorsOf(ld)?.let { addActors(it) }
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
            .getOrNull()
            ?: throw ErrorLoadingException("$name: bölüm sayfası yüklenemedi. $REPORT_HINT")

        val playerUrl = decryptEmbed(pageHtml)
            ?: throw ErrorLoadingException("$name: oynatıcı adresi çözülemedi. $REPORT_HINT")
        val playerHtml = runCatching { app.get(playerUrl, headers = uaHeaders, referer = pageUrl).text }
            .getOrNull()
            ?: throw ErrorLoadingException("$name: oynatıcı sayfası yüklenemedi. $REPORT_HINT")

        val found = npLinks(playerHtml, playerUrl, links, subtitles) ||
            dplayerLinks(playerHtml, playerUrl, links)

        if (!found) throw ErrorLoadingException("$name: oynatılabilir kaynak bulunamadı. $REPORT_HINT")
        return true
    }

    // ---------------------------------------------------------------- listing

    private fun Element.toCard(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        val isSeries = href.contains("/series/")
        if (!isSeries && !href.contains("/movies/")) return null

        val title = selectFirst("span.tt")?.text()?.trim()?.ifBlank { null }
            ?: selectFirst("span.ft1")?.text()?.trim()?.ifBlank { null }
            ?: attr("aria-label").substringBefore(',').trim().ifBlank { null }
            ?: return null
        val poster = selectFirst("img[src^=http]")?.attr("src")
        val year = YEAR.find(text())?.value?.toIntOrNull()

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
        val poster = directImage(optString("object_poster_url"))
        val year = optString("object_release_year").toIntOrNull()?.takeIf { it in 1900..2100 }
        val score = Score.from10(optString("object_related_imdb_point").takeIf { it != "0" })

        return if (optString("used_type").equals("Series", ignoreCase = true)) {
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

    /** Arama servisi görselleri ampproject önbelleğinden verir; gerçek adres `/i/s/` sonrasındadır. */
    private fun directImage(url: String): String? {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http")) return null
        return if (IMAGE_PROXY in trimmed) "https://" + trimmed.substringAfter(IMAGE_PROXY) else trimmed
    }

    // ------------------------------------------------------------------ künye

    private fun episodesOf(doc: Document, ld: JSONObject?): List<Episode> {
        val episodes = LinkedHashMap<String, Episode>()

        ld?.opt("containsSeason").seasons().forEach { season ->
            val seasonNumber = season.optString("seasonNumber").toIntOrNull() ?: 1
            season.optJSONArray("episode").objects().forEach { entry ->
                val episodeUrl = httpsUrl(entry.optString("url"))
                if (episodeUrl.isEmpty()) return@forEach
                val number = entry.optString("episodeNumber").toIntOrNull()
                episodes[episodeUrl] = newEpisode(episodeUrl) {
                    this.name = entry.optString("name").trim()
                        .ifEmpty { "$seasonNumber. Sezon $number. Bölüm" }
                    this.season = seasonNumber
                    this.episode = number
                    this.description = entry.optString("description").trim().takeIf { it.isNotEmpty() }
                }
            }
        }

        // ld+json kırpılırsa sayfadaki /bolum/<slug>-<S>x<E> bağlantıları aynı listeyi verir.
        if (episodes.isEmpty()) {
            doc.select("a[href*='/bolum/']").forEach { anchor ->
                val episodeUrl = httpsUrl(anchor.attr("href").substringBefore('?'))
                if (episodeUrl.isEmpty() || episodes.containsKey(episodeUrl)) return@forEach
                val match = EPISODE_IN_SLUG.find(episodeUrl) ?: return@forEach
                val season = match.groupValues[1].toIntOrNull() ?: return@forEach
                val number = match.groupValues[2].toIntOrNull()
                episodes[episodeUrl] = newEpisode(episodeUrl) {
                    this.name = "$season. Sezon $number. Bölüm"
                    this.season = season
                    this.episode = number
                }
            }
        }
        return episodes.values.toList()
    }

    private fun jsonLd(doc: Document, type: String): JSONObject? =
        doc.select("script[type=application/ld+json]").firstNotNullOfOrNull { script ->
            runCatching { JSONObject(script.data()) }.getOrNull()
                ?.takeIf { it.optString("@type") == type }
        }

    /** Dizi sayfası künye görseli olarak backdrop taşır; afiş `…/tv/poster/…` yolundan gelir. */
    private fun posterOf(doc: Document, ld: JSONObject?): String? =
        doc.selectFirst("img[src*=/poster/]")?.attr("src")?.takeIf { it.startsWith("http") }
            ?: ld?.optString("image")?.takeIf { it.startsWith("http") }

    private fun yearOf(ld: JSONObject?): Int? =
        ld?.optString("datePublished")?.take(4)?.toIntOrNull()?.takeIf { it in 1900..2100 }

    private fun tagsOf(doc: Document): List<String> =
        doc.select("a[href^='/kategori/'], a[href^='/film-kategori/']")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    /** Detay sayfasındaki "Benzer diziler"/"Benzer filmler" şeridi (`ds-ben` / `df-ben`). */
    private fun recommendationsOf(doc: Document, isSeries: Boolean): List<SearchResponse> {
        val section = if (isSeries) "ds-ben" else "df-ben"
        return doc.select("section[aria-labelledby='$section'] a.tc[href]")
            .mapNotNull { it.toCard() }
            .distinctBy { it.url }
    }

    private fun actorsOf(ld: JSONObject?): List<String>? =
        ld?.optJSONArray("actor").objects()
            .mapNotNull { it.optString("name").trim().takeIf(String::isNotEmpty) }
            .takeIf { it.isNotEmpty() }

    /** ISO-8601 süre (`PT1H46M`) → dakika. */
    private fun minutes(iso: String?): Int? {
        val match = ISO_DURATION.find(iso.orEmpty()) ?: return null
        val total = (match.groupValues[1].toIntOrNull() ?: 0) * 60 + (match.groupValues[2].toIntOrNull() ?: 0)
        return total.takeIf { it > 0 }
    }

    // --------------------------------------------------------------- oynatıcı

    /**
     * Sayfadaki `enc` bloğunu ve aynı sayfada üretilen parolayı (`k`) çözerek oynatıcı adresini verir.
     * Parola istek başına değiştiği için sayfadan okunur; sabit kopya tutulmaz.
     */
    private fun decryptEmbed(html: String): String? {
        val fields = ENC_FIELD.findAll(html).groupBy({ it.groupValues[1] }) { it.groupValues[2] }
        val cipherText = fields["ciphertext"]?.firstOrNull() ?: return null
        val iv = fields["iv"]?.firstOrNull() ?: return null
        val salt = fields["salt"]?.firstOrNull() ?: return null
        val passphrase = fields["k"]?.maxByOrNull { it.length } ?: return null

        val key = runCatching {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
                .generateSecret(PBEKeySpec(passphrase.toCharArray(), salt.hex(), KDF_ROUNDS, KEY_BITS))
                .encoded
        }.getOrNull() ?: return null

        val clear = runCatching {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(iv.hex()),
            )
            String(cipher.doFinal(Base64.decode(cipherText, Base64.DEFAULT)), Charsets.UTF_8)
        }.getOrNull() ?: return null

        return httpsUrl(clear.trim('\t', '\r', '\n', ' ', '\u000f'))
    }

    private suspend fun npLinks(
        playerHtml: String,
        playerUrl: String,
        emit: (ExtractorLink) -> Unit,
        emitSubtitle: (SubtitleFile) -> Unit,
    ): Boolean {
        val anchor = playerHtml.indexOf("window.NP_CONFIG")
        val open = if (anchor < 0) -1 else playerHtml.indexOf('{', anchor)
        if (open < 0) return false
        val config = objectAt(playerHtml, open)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return false

        config.optJSONArray("subtitles").objects().forEach { track ->
            val url = track.optString("url").takeIf { it.startsWith("http") } ?: return@forEach
            val label = track.optString("label").trim()
                .ifEmpty { track.optString("lang").trim() }
                .ifEmpty { "Altyazı" }
            emitSubtitle(SubtitleFile(label, url).apply {
                this.headers = mapOf("Referer" to playerUrl, "User-Agent" to UA)
            })
        }

        var found = false
        config.optJSONArray("sources").objects().forEach { source ->
            val master = source.optString("master").takeIf { it.startsWith("http") } ?: return@forEach
            val label = source.optString("title").trim().ifEmpty { "Kaynak" }
            emitPlaylist(master, playerUrl, label, emit)
            found = true
        }
        return found
    }

    private suspend fun dplayerLinks(
        playerHtml: String,
        playerUrl: String,
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
            val label = item.optString("title").trim().ifEmpty { "Kaynak" }
            item.optJSONArray("sources").objects().forEach eachSource@{ source ->
                val file = source.optString("file").takeIf { it.startsWith("http") } ?: return@eachSource
                val master = if ("m.php" in file) file.replace("m.php", "master.m3u8") else file
                emitPlaylist(master, playerUrl, label, emit)
                found = true
            }
        }
        return found
    }

    /** HLS master'ı varyantlarına ayırır; tek varyant kaldıysa master olduğu gibi verilir. */
    private suspend fun emitPlaylist(
        masterUrl: String,
        referer: String,
        label: String,
        emit: (ExtractorLink) -> Unit,
    ) {
        val playlist = runCatching { app.get(masterUrl, headers = uaHeaders, referer = referer).text }
            .getOrNull()
        val variants = parseVariants(playlist.orEmpty())
        if (variants.size <= 1) {
            emit(hlsLink(masterUrl, label, Qualities.Unknown.value, referer))
            return
        }
        variants.forEach { (height, url) ->
            val quality = height.takeIf { it > 0 } ?: Qualities.Unknown.value
            val variantLabel = if (quality > 0) "$label ${quality}p" else label
            emit(hlsLink(url, variantLabel, quality, referer))
        }
    }

    /** Master'daki `#EXT-X-STREAM-INF` bloklarını (yükseklik, adres) çiftlerine çevirir. */
    private fun parseVariants(playlist: String): List<Pair<Int, String>> {
        if (!playlist.startsWith("#EXTM3U")) return emptyList()
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

    private fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun Any?.seasons(): List<JSONObject> = when (this) {
        is JSONArray -> objects()
        is JSONObject -> listOf(this)
        else -> emptyList()
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    companion object {
        /** Ana sayfa satırları; ayarlardan kapatılabilir. */
        val ROWS = listOf(
            "Yeni Diziler", "Yeni Filmler", "Trendler", "Keşfet",
            "Netflix", "Amazon Prime", "Disney+",
        )
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
        private const val IMAGE_PROXY = "/i/s/"
        private const val KDF_ROUNDS = 999
        private const val KEY_BITS = 256

        private val YEAR = Regex("""\b(19\d{2}|20\d{2})\b""")
        private val EPISODE_IN_SLUG = Regex("""-(\d+)x(\d+)""")
        private val ISO_DURATION = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?""")
        private val RESOLUTION = Regex("""RESOLUTION=\d+x(\d+)""")
        private val OPEN_PLAYER = Regex("""openPlayer\s*\(\s*['"]([^'"]+)""")
        private val ORIGIN = Regex("""^https?://[^/]+""")

        /**
         * Sayfa yükündeki alanlar RSC kaçışıyla (`\"ciphertext\":\"…\"`) gelir; 0-3 ters bölü
         * kabul edilir. Parola `k` anahtarında durur ve görseller gibi aynı yükte tekrarlanır.
         */
        private val ENC_FIELD = Regex("""\\{0,3}"(ciphertext|iv|salt|k)\\{0,3}":\\{0,3}"([^"\\]+)""")
    }
}
