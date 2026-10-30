package com.dramadizilerim

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
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder

/**
 * DramaDizilerim — dramadizilerim.com (dikey mini dizi / kısa drama arşivi, Türkçe dublaj + altyazı).
 *
 * Listeler: `/dizi?page=N` katalog, `/drama-izle?page=N` popüler, `/turkce-dublaj-drama?page=N`,
 * platform hub'ları `/p/<platform>?page=N`. Arama `/search?q=<sorgu>&page=N` (48 sonuç/sayfa).
 *
 * Detay `/dizi/<slug>`: bölüm kartları `a.wp-ecard[data-season][data-episode]` ve izleme adresleri
 * `/izle/<slug>?s=<sezon>&e=<bölüm>`; künye `div.wp-infobox` tablosunda, puan `span.wp-imdb`'de.
 *
 * Oynatıcı: izleme sayfası `embed.php` iframe'i basar; embed gövdesindeki `let source = "..."`
 * ya `hls_proxy.php` üzerinden m3u8 (çoğu platform) ya da doğrudan mp4 (NetShort/DramaBox) verir.
 * `window._captionUrl` doluysa Türkçe WebVTT altyazısı vardır. Melolo kaynakları AES-CTR ile
 * şifreli mp4'tür; tarayıcıda çözüldüğü için oynatıcıya verilemez.
 */
private fun Element.posterSrcLazy(): String? =
    attr("data-src").takeIf { it.isNotBlank() } ?: attr("src")

class DramaDizilerimProvider : MainAPI() {
    override var mainUrl = "https://dramadizilerim.com"
    override var name = "DramaDizilerim"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val uaHeaders = mapOf(
        "User-Agent" to UA,
        "Accept-Language" to "tr-TR,tr;q=0.9",
    )

    override val mainPage get() = ROWS.mapNotNull { name ->
        if (!Settings.rowEnabled(name)) return@mapNotNull null
        when (name) {
            "Son Eklenen" -> MainPageData(name, "$mainUrl/dizi?page=SAYFA")
            "Popüler" -> MainPageData(name, "$mainUrl/drama-izle?page=SAYFA")
            "Türkçe Dublaj" -> MainPageData(name, "$mainUrl/turkce-dublaj-drama?page=SAYFA")
            "DramaBox" -> MainPageData(name, "$mainUrl/p/dramabox?page=SAYFA")
            "ReelShort" -> MainPageData(name, "$mainUrl/p/reelshort?page=SAYFA")
            "ShortMax" -> MainPageData(name, "$mainUrl/p/shortmax?page=SAYFA")
            "DramaWave" -> MainPageData(name, "$mainUrl/p/dramawave?page=SAYFA")
            "GoodShort" -> MainPageData(name, "$mainUrl/p/goodshort?page=SAYFA")
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data.replace("SAYFA", page.toString()), headers = uaHeaders).document
        return newHomePageResponse(request.name, doc.cards(), hasNext = doc.hasNextPage())
    }

    override suspend fun search(query: String): List<SearchResponse> =
        search(query, 1)?.items ?: emptyList()

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val doc = app.get(
            "$mainUrl/search",
            params = mapOf("q" to query.trim(), "page" to page.toString()),
            headers = uaHeaders,
        ).document
        return newSearchResponseList(doc.cards(), doc.hasNextPage())
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = fixUrl(url)
        val doc = runCatching { app.get(pageUrl, headers = uaHeaders).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: dizi sayfası yüklenemedi. $REPORT_HINT")
        val title = doc.selectFirst("h1#video-title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("$name: dizi adı okunamadı. $REPORT_HINT")

        val info = doc.infoTable()
        val poster = fixUrlNull(doc.selectFirst("div.wp-poster img")?.attr("src"))
        val plot = doc.selectFirst("div.wp-desc")?.text()?.trim()?.takeIf { it.isNotEmpty() }
        // Sitede tür/oyuncu/süre/fragman alanı yok; tek sınıflandırma platform kategorisi.
        val tags = info["Kategoriler"]?.select("a")?.map { it.text().trim() }?.filter { it.isNotEmpty() }
        val score = Score.from10(
            SCORE.find(doc.selectFirst("span.wp-imdb")?.text().orEmpty())
                ?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
        )
        val recommendations = doc.select("a.wp-hcard").mapNotNull { it.toSearchCard() }
            .distinctBy { it.url }

        // Sitede film kaydı yok; tür etiketi "Film" olursa tek parça olarak sunulur.
        if (info["Tür"]?.text()?.contains("Film", true) == true) {
            val watchUrl = fixUrlNull(doc.selectFirst("a.wp-play")?.attr("href")) ?: pageUrl
            return newMovieLoadResponse(title, pageUrl, TvType.Movie, watchUrl) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.score = score
                this.recommendations = recommendations
            }
        }

        val episodes = doc.select("a.wp-ecard[href]").mapNotNull { anchor ->
            val link = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            newEpisode(link) {
                this.season = anchor.attr("data-season").toIntOrNull()
                this.episode = anchor.attr("data-episode").toIntOrNull()
                this.posterUrl = fixUrlNull(anchor.selectFirst("img")?.posterSrcLazy())
            }
        }.sortedWith(compareBy({ it.season ?: Int.MAX_VALUE }, { it.episode ?: Int.MAX_VALUE }))
            .distinctBy { it.data }
        if (episodes.isEmpty()) throw ErrorLoadingException("$name: bölüm listesi bulunamadı. $REPORT_HINT")

        return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.score = score
            this.recommendations = recommendations
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
            ?: throw ErrorLoadingException("$name: bölüm sayfası yüklenemedi. $REPORT_HINT")
        val embeds = doc.select("iframe[src]").mapNotNull { fixUrlNull(it.attr("src")) }.distinct()
        if (embeds.isEmpty()) throw ErrorLoadingException("$name: oynatıcı bulunamadı. $REPORT_HINT")

        val found = LinkedHashMap<String, Stream>()
        for (embed in embeds) {
            val html = runCatching {
                app.get(embed, headers = uaHeaders, referer = pageUrl).text
            }.getOrNull() ?: continue
            // Melolo mp4'ü AES-CTR ile şifreli; anahtar tarayıcıda uygulanıyor, ham URL oynatmaz.
            if (MELOLO_KEY.containsMatchIn(html)) continue

            val caption = fixUrlNull(CAPTION.find(html)?.groupValues?.get(1)?.trim())
            if (caption != null) {
                val label = CAPTION_LABEL.find(html)?.groupValues?.get(1)?.trim().orEmpty()
                    .ifEmpty { "Türkçe" }
                if (Settings.subtitleAllowed(label)) subtitleCallback.invoke(SubtitleFile(label, caption))
            }

            val stream = fixUrlNull(SOURCE.find(html)?.groupValues?.get(1)?.trim()) ?: continue
            // Çözülemeyen özel şemalar (dramabox:, shortmax:, ...) oynatıcıya verilemez.
            if (!stream.startsWith("http")) continue
            val type = TYPE.find(html)?.groupValues?.get(1).orEmpty()
            val isHls = type.contains("mpegurl", true) || stream.contains(".m3u8") || stream.contains("hls_proxy")
            val quality = qualityOf(stream)
            found[stream] = Stream(stream, quality, isHls)
        }
        if (found.isEmpty()) throw ErrorLoadingException("$name: oynatılabilir kaynak bulunamadı. $REPORT_HINT")

        val streams = found.values.toList()
        val chosen = streams.filter { Settings.qualityAllowed(it.quality) }
            .ifEmpty { listOfNotNull(streams.minByOrNull { it.quality }) }
        chosen.forEach { stream ->
            callback.invoke(
                newExtractorLink(
                    name,
                    if (stream.quality > 0) "${stream.quality}p" else "Otomatik",
                    stream.url,
                    if (stream.isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    this.referer = "$mainUrl/"
                    this.headers = mapOf("User-Agent" to UA)
                    this.quality = stream.quality
                }
            )
        }
        return true
    }

    // ---------------------------------------------------------------- listeler

    private fun Document.cards(): List<SearchResponse> = select(CARD)
        .mapNotNull { it.toSearchCard() }
        .distinctBy { it.url }

    private fun Element.toSearchCard(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        if (!href.contains("/dizi/")) return null
        val title = selectFirst("h1, h2, h3")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: selectFirst("p[title]")?.attr("title")?.trim()?.takeIf { it.isNotEmpty() }
            ?: selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = fixUrlNull(selectFirst("img")?.posterSrcLazy())
        }
    }

    /** Katalog ve arama listelerinde "Sonraki" bağlantısı sayfalamayı belirler. */
    private fun Document.hasNextPage(): Boolean = selectFirst("a[rel=next]") != null

    // ---------------------------------------------------------------- künye

    /** `div.wp-infobox` tablosu: Tür, Kategoriler, Eklenme Tarihi, Sezon Sayısı, Toplam Bölüm, Dil. */
    private fun Document.infoTable(): Map<String, Element> =
        select("div.wp-infobox tr").mapNotNull { row ->
            val head = row.selectFirst("th")?.text()?.trim() ?: return@mapNotNull null
            val cell = row.selectFirst("td") ?: return@mapNotNull null
            head to cell
        }.toMap()

    // ---------------------------------------------------------------- oynatıcı

    /** Kalite ipucu kaynak adresinde: `_1080/main.m3u8`, `?q=720p`, `.1080p.mp4`. */
    private fun qualityOf(stream: String): Int {
        val decoded = runCatching { URLDecoder.decode(stream, "UTF-8") }.getOrDefault(stream)
        val height = QUALITY.find(decoded)?.groupValues?.get(1)?.toIntOrNull()
            ?: return Qualities.Unknown.value
        return getQualityFromName("${height}p")
    }

    private data class Stream(val url: String, val quality: Int, val isHls: Boolean)

    companion object {
        /** Homepage rows in display order; every entry maps to a live listing page. */
        val ROWS = listOf(
            "Son Eklenen",
            "Popüler",
            "Türkçe Dublaj",
            "DramaBox",
            "ReelShort",
            "ShortMax",
            "DramaWave",
            "GoodShort",
        )

        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"

        /** Katalog kartı, platform hub kartı ve arama satırı. */
        private const val CARD = "a.video-item-card, a.drama-card, a.video-link"
        private val SCORE = Regex("""(\d+(?:[.,]\d+)?)""")
        private val QUALITY = Regex("""(?:[_.]|q=)(\d{3,4})p?(?=[/&.]|$)""")
        private val SOURCE = Regex("""let source\s*=\s*"([^"]*)"""")
        private val TYPE = Regex("""let type\s*=\s*"([^"]*)"""")
        private val CAPTION = Regex("""window\._captionUrl\s*=\s*"([^"]*)"""")
        private val CAPTION_LABEL = Regex("""window\._captionLabel\s*=\s*"([^"]*)"""")
        private val MELOLO_KEY = Regex("""window\._meloloKey\s*=\s*"[0-9a-fA-F]{8,}"""")
    }
}