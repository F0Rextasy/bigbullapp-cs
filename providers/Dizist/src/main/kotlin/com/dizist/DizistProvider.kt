package com.dizist

import com.bigbull.common.browserHeaders
import com.bigbull.common.pichiveSign
import com.bigbull.common.posterSrcLazy
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
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
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
 * Dizist — dizist.live (macellan CMS, sunucu tarafı render + gated AJAX, pichive oynatıcı).
 *
 * Yollar: dizi `/dizi/<slug>`, bölüm `/izle/<slug>-<S>-sezon-<E>-bolum`, sezon kabuğu
 * `/sezon/<slug>-<S>-sezon` (bölüm listesi taşımaz), listeler `/yabanci-diziler`,
 * `/animeler`, `/asyadizileri`, arşiv `/arsiv`.
 *
 * Arama: sayfadaki `form[data-action="/bg/searchcontent"]` POST'u `cKey`/`cValue` jetonu ve
 * `window.appCKey` başlığını ister; jeton yalnızca bu oturum için üretildiğinden jetonu
 * taşıyan sayfa da aynı çerezlerle çağrılır. Ana sayfa Cloudflare tarafından önbelleklendiği
 * için jetonları `/arsiv/` yanıtından alıyoruz. Yetkisiz yanıtta site `state:false` +
 * `"Unauthorized"` döndürür.
 *
 * Oynatıcı: bölüm sayfası `//<host>.pichive.online/iframe.php?v=…` iframe'i basar, iframe
 * `window.NP_CONFIG` içinde `sources[].master` (nm.php, proof-of-work imzası → [pichiveSign])
 * ve `subtitles[].url|tok` (nsub.php, WebVTT) taşır. pichive yanıtı `stats.country` ile
 * ülke kısıtlı; TR dışı çıkışlarda 403 döner.
 */
class DizistProvider : MainAPI() {
    override var mainUrl = "https://dizist.live"
    override var name = "Dizist"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage get() = ROWS.mapNotNull { row ->
        if (!Settings.rowEnabled(row)) return@mapNotNull null
        MainPageData(row, mainUrl.trimEnd('/') + (ROW_PATHS[row] ?: "/"))
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Ana sayfa ve listeler tek yanıtta gelir; sayfalama gated AJAX'ta kalıyor.
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val doc = runCatching { app.get(request.data, headers = browserHeaders).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: ana sayfa yüklenemedi. $REPORT_HINT")
        val items = if (request.name == EPISODE_ROW) doc.episodeCards() else doc.posterCards()
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        search(query, 1)?.items ?: emptyList()

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val term = query.trim()
        // Site araması 3 karakterden kısa terimleri hiç göndermiyor.
        if (term.length < MIN_TERM || page > 1) return newSearchResponseList(emptyList(), false)
        // Jeton/oturum bayatlayınca site `state:false` + "Unauthorized" döndürüyor; taze
        // çerezle bir kez daha denenir (2026-11-13: taramalarda ara ara 401).
        var message = "bilinmeyen hata"
        repeat(2) {
            val body = searchContent(term)
            val data = runCatching { JSONObject(body).optJSONObject("data") }.getOrNull()
                ?: throw ErrorLoadingException("$name: arama yanıtı okunamadı. $REPORT_HINT")
            if (data.optBoolean("state")) {
                val rows = data.optJSONArray("result") ?: JSONArray()
                val items = (0 until rows.length()).mapNotNull { index -> rows.optJSONObject(index)?.toSearchCard() }
                    .distinctBy { it.url }
                return newSearchResponseList(items, false)
            }
            message = data.optString("message").trim().ifEmpty { "bilinmeyen hata" }
        }
        throw ErrorLoadingException("$name: arama başarısız ($message). $REPORT_HINT")
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = fixUrl(url)
        // Ana sayfadaki "Son Eklenen Bölümler" kartları doğrudan bölüm adresidir;
        // bölüm sayfası dizi künyesi taşımaz, aynı dizinin sayfasına indirgenir.
        if (pageUrl.contains("/izle/")) {
            // Bölüm adresinden dizi slug'ı türetmek her zaman tutmuyor (ör. uzun İngilizce adlar
            // sitede kısaltılmış slug taşıyor); bölüm sayfasındaki dizi bağlantısı esas alınır.
            val episodeDoc = runCatching { app.get(pageUrl, headers = browserHeaders).document }.getOrNull()
            val seriesLink = episodeDoc?.selectFirst("a[href*='/dizi/']")?.attr("href")
            if (!seriesLink.isNullOrBlank()) return load(fixUrl(seriesLink))
            val slug = pageUrl.substringAfter("/izle/").substringBefore('?').replace(EPISODE_SUFFIX, "")
            if (slug.isNotBlank()) return load("$mainUrl/dizi/$slug/")
        }
        val doc = runCatching { app.get(pageUrl, headers = browserHeaders).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: dizi sayfası yüklenemedi. $REPORT_HINT")
        val schema = doc.seriesSchema()
        val heading = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        val title = YEAR_SUFFIX.replace(heading, "").trim()
            .ifEmpty { schema?.optString("name")?.trim().orEmpty() }
            .ifEmpty { throw ErrorLoadingException("$name: dizi adı okunamadı. $REPORT_HINT") }
        val slug = pageUrl.substringAfter("/dizi/").substringBefore('/')
        val episodes = doc.episodesOf(slug)
        if (episodes.isEmpty()) {
            throw ErrorLoadingException("$name: bölüm listesi bulunamadı. $REPORT_HINT")
        }
        return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
            this.posterUrl = doc.selectFirst(".series-profile-image img")?.posterSrcLazy()
                ?: fixUrlNull(schema?.optString("image"))
            this.plot = doc.selectFirst(".series-profile-summary p")?.text()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: schema?.optString("description")?.trim()?.takeIf { it.isNotEmpty() }
            this.year = YEAR_SUFFIX.find(heading)?.groupValues?.get(1)?.toIntOrNull()
                ?: schema?.optString("datePublished")?.take(4)?.toIntOrNull()?.takeIf { it in 1900..2100 }
            this.tags = doc.select(".series-profile-type a[href*='/kategori/']")
                .map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
            this.score = doc.selectFirst("strong.color-imdb")?.text()?.trim()?.toDoubleOrNull()
                ?.let { Score.from10(it) }
            addActors(schema?.optJSONArray("actor").names())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageUrl = fixUrl(data)
        val page = app.get(pageUrl, headers = browserHeaders).document
        val embed = (page.selectFirst("div.player iframe") ?: page.selectFirst("iframe[src*=iframe.php]"))
            ?.let { el -> el.attr("data-src").ifBlank { el.attr("data-lazy-src").ifBlank { el.attr("src") } } }?.trim()?.takeIf { it.isNotEmpty() }?.let { httpsUrl(it) }
            ?: throw ErrorLoadingException("$name: oynatıcı iframe bulunamadı. $REPORT_HINT")
        val player = app.get(embed, headers = browserHeaders, referer = pageUrl)
        // pichive `stats.country` ile ülke kısıtlıyor: TR dışı çıkışlarda Cloudflare 403 basar.
        if (player.code == 403) {
            throw ErrorLoadingException("$name: oynatıcı sunucusu isteği engelledi (HTTP 403). $REPORT_HINT")
        }
        val playerHtml = player.text
        val config = jsonObjectAfter(playerHtml, "window.NP_CONFIG")
            ?: throw ErrorLoadingException("$name: oynatıcı verisi okunamadı. $REPORT_HINT")

        val sources = config.optJSONArray("sources").objects().mapNotNull { source ->
            val master = source.optString("master").takeIf { it.startsWith("http") }
                ?.let { pichiveSign(playerHtml, it) } ?: return@mapNotNull null
            source.optString("title").trim() to master
        }
        if (sources.isEmpty()) {
            throw ErrorLoadingException("$name: oynatılabilir kaynak bulunamadı. $REPORT_HINT")
        }
        sources.forEach { (label, stream) ->
            callback(
                newExtractorLink(name, label.ifEmpty { name }, stream, ExtractorLinkType.M3U8) {
                    this.referer = embed
                    this.headers = browserHeaders
                    // Master tüm rendition'ları taşır; kalite seçimi oynatıcıya bırakılır.
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        val playerOrigin = ORIGIN.find(embed)?.value.orEmpty()
        config.optJSONArray("subtitles").objects().forEach { track ->
            val raw = track.optString("url").takeIf { it.startsWith("http") }
                ?: track.optString("tok").takeIf { it.isNotEmpty() }
                    ?.let { "$playerOrigin/nsub.php?v=$it" }
                ?: return@forEach
            val language = track.optString("lang").ifEmpty { track.optString("label") }
            if (!Settings.subtitleAllowed(language)) return@forEach
            val label = track.optString("label").trim().ifEmpty { language.ifEmpty { "Altyazı" } }
            subtitleCallback(
                newSubtitleFile(label, pichiveSign(playerHtml, raw)) {
                    this.headers = browserHeaders + ("Referer" to embed)
                }
            )
        }
        return true
    }

    // ---------------------------------------------------------------- arama

    /**
     * `/bg/searchcontent` POST'u: önce oturum çerezi (`/arsiv;x=1` koruma yanıtı PHPSESSID +
     * CNT bırakır), sonra o oturuma bağlı sayfa jetonları, en son arama isteği.
     */
    private suspend fun searchContent(term: String): String = try {
        // 403 koruma yanıtı normaldir; çerezleri taşır (NiceHttp hata kodunda fırlatmaz).
        val boot = app.get("$mainUrl$BOOT_PATH", headers = browserHeaders)
        val cookies = boot.cookies
        val pageHtml = app.get("$mainUrl$TOKEN_PATH", headers = browserHeaders, cookies = cookies).text
        val cKey = tokenOf(pageHtml, CKEY) ?: throw ErrorLoadingException(JETON_HINT)
        val cValue = tokenOf(pageHtml, CVALUE) ?: throw ErrorLoadingException(JETON_HINT)
        val appCKey = tokenOf(pageHtml, APCKEY) ?: throw ErrorLoadingException(JETON_HINT)
        app.post(
            "$mainUrl/bg/searchcontent",
            headers = browserHeaders + mapOf(
                "accept" to "application/json, text/javascript, */*; q=0.01",
                "origin" to mainUrl,
                "referer" to "$mainUrl$TOKEN_PATH",
                "x-requested-with" to "XMLHttpRequest",
                "x-global-ckey" to appCKey,
            ),
            cookies = cookies,
            data = mapOf("cKey" to cKey, "cValue" to cValue, "searchTerm" to term),
        ).text
    } catch (e: ErrorLoadingException) {
        throw e
    } catch (e: Exception) {
        throw ErrorLoadingException("$name: arama isteği başarısız (${e.message ?: "ağ hatası"}). $REPORT_HINT")
    }

    private fun tokenOf(html: String, pattern: Regex): String? =
        pattern.find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    private fun JSONObject.toSearchCard(): SearchResponse? {
        val title = optString("object_name").trim().takeIf { it.isNotEmpty() } ?: return null
        val url = fixUrlNull(optString("used_slug").trim()) ?: return null
        val poster = fixUrlNull(optString("object_poster_url"))
        val year = optInt("object_release_year", 0).takeIf { it in 1900..2100 }
        val score = optDouble("object_related_imdb_point", 0.0).takeIf { it > 0 }
            ?.let { Score.from10(it) }
        // Site tüm kayıtları `/dizi/` altında tutuyor; yanıtlar daima `used_type: "Series"`.
        return newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
            this.posterUrl = poster
            this.year = year
            this.score = score
        }
    }

    // ---------------------------------------------------------------- listeler

    /** Ana sayfanın ilk satırı ya da liste sayfasının kart ızgarası (`/dizi/<slug>`). */
    private fun Document.posterCards(): List<SearchResponse> {
        // Ana sayfada ikinci satır da afiş kartı basıyor; satırlar karışmasın diye ilk alan seçilir.
        val scope = selectFirst("div.area.last-episodes") ?: this
        return scope.select("div.poster-mb-bx").mapNotNull { it.toPosterCard() }.distinctBy { it.url }
    }

    /** "Yeni Eklenen Dizi Bölümleri" kartları bölüme gider; dizi sayfasına çevrilir. */
    private fun Document.episodeCards(): List<SearchResponse> =
        select(".area.last-episodes div.serie-box-mb").mapNotNull { card ->
            val href = fixUrlNull(card.selectFirst("a[href*='/izle/']")?.attr("href")) ?: return@mapNotNull null
            val slug = href.substringAfter("/izle/").replace(EPISODE_SUFFIX, "")
            if (slug.isEmpty() || slug == href.substringAfter("/izle/")) return@mapNotNull null
            val title = card.selectFirst("h2")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            newTvSeriesSearchResponse(title, "$mainUrl/dizi/$slug", TvType.TvSeries) {
                this.posterUrl = card.selectFirst("img")?.posterSrcLazy()
            }
        }.distinctBy { it.url }

    private fun Element.toPosterCard(): SearchResponse? {
        val link = selectFirst("a[href*='/dizi/']") ?: return null
        val url = fixUrlNull(link.attr("href")) ?: return null
        val title = link.attr("title").trim().ifEmpty { selectFirst("h2")?.text()?.trim().orEmpty() }
            .ifEmpty { return null }
        return newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
            this.posterUrl = selectFirst("img")?.posterSrcLazy()
            this.score = selectFirst("span.rating")?.text()?.trim()?.toDoubleOrNull()
                ?.takeIf { it > 0 }?.let { Score.from10(it) }
        }
    }

    // ---------------------------------------------------------------- bölümler

    /**
     * Dizi sayfası sunucu tarafında tek sezonun (genelde 1.) bölüm listesini basar; kalan
     * sezonlar yalnız gated AJAX ile geldiği için sayfada bulunan tüm bölüm bağlantıları
     * toplanıp sezona/bölüme göre sıralanır.
     */
    private fun Document.episodesOf(slug: String): List<Episode> {
        // Yalnız bu dizinin bölümleri: `<slug>-<S>-sezon-<E>-bolum`
        // (`<slug>-turkce-dublaj-…` başka bir dizi kaydıdır, dışarıda kalır).
        val pattern = Regex("""^${Regex.escape(slug)}-(\d+)-sezon-(\d+)-bolum$""")
        val items = LinkedHashMap<String, Episode>()
        select("a[href*='/izle/']").forEach { anchor ->
            val href = fixUrlNull(anchor.attr("href").substringBefore('?')) ?: return@forEach
            val match = pattern.find(href.substringAfter("/izle/")) ?: return@forEach
            val season = match.groupValues[1].toIntOrNull() ?: return@forEach
            val number = match.groupValues[2].toIntOrNull() ?: return@forEach
            // "Dizinin İlk Bölümünü İzle 1. Sezon · 1" gibi gezinme metinleri başlık sayılmaz.
            val label = anchor.text().trim().takeIf {
                it.isNotEmpty() && !EPISODE_LABEL.matches(it) && !JUNK_LABEL.containsMatchIn(it)
            }
            val existing = items[href]
            if (existing == null) {
                items[href] = newEpisode(href) {
                    this.season = season
                    this.episode = number
                    this.name = label ?: "$number. Bölüm"
                }
            } else if (label != null && existing.name == "$number. Bölüm") {
                existing.name = label
            }
        }
        return items.values.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
    }

    // ---------------------------------------------------------------- künye

    private fun Document.seriesSchema(): JSONObject? =
        select("script[type='application/ld+json']").firstNotNullOfOrNull { script ->
            runCatching { JSONObject(script.data()) }.getOrNull()
                ?.takeIf { it.optString("@type") == "TVSeries" }
        }

    private fun JSONArray?.names(): List<String> = this?.let { array ->
        (0 until array.length()).mapNotNull {
            array.optJSONObject(it)?.optString("name")?.trim()?.takeIf(String::isNotEmpty)
        }
    }.orEmpty()

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
            "Son Eklenen Bölümler",
            "Popüler Diziler",
            "Yabancı Diziler",
            "Animeler",
            "Asya Dizileri",
        )

        private const val EPISODE_ROW = "Son Eklenen Bölümler"
        private const val MIN_TERM = 3
        private const val BOOT_PATH = "/arsiv;x=1"
        private const val TOKEN_PATH = "/arsiv/"
        private const val JETON_HINT =
            "Dizist: arama jetonu okunamadı (site arama formunu değiştirmiş olabilir). " +
                "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"

        private val ROW_PATHS = mapOf(
            "Yabancı Diziler" to "/yabanci-diziler",
            "Animeler" to "/animeler",
            "Asya Dizileri" to "/asyadizileri",
        )

        private val CKEY = Regex("""name="cKey"\s+value="([^"]+)"""")
        private val CVALUE = Regex("""name="cValue"\s+value="([^"]+)"""")
        private val APCKEY = Regex("""window\.appCKey\s*=\s*'([^']+)'""")
        private val YEAR_SUFFIX = Regex("""\((\d{4})\)""")
        private val EPISODE_SUFFIX = Regex("""-\d+-sezon-\d+-bolum$""")
        private val EPISODE_LABEL = Regex("""^Bölüm\s+\d+$""", RegexOption.IGNORE_CASE)
        private val JUNK_LABEL = Regex("""(?i)izle|sezon|bölüm""")
        private val ORIGIN = Regex("""^https?://[^/]+""")
    }
}
