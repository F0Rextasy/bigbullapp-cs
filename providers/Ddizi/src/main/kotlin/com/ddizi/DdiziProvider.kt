package com.ddizi

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

/** DDizi — ddizi.im, güncel adres www.ddizi.tel (oynatıcı: /player/oynat/<hash>, jwplayer HLS/MP4) */
class DdiziProvider : MainAPI() {
    override var mainUrl = "https://www.ddizi.tel"
    override var name = "Ddizi"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/yabanci-dizi-izle" to "Yabancı Diziler",
        "$mainUrl/eski.diziler" to "Eski Diziler",
    )

    private val uaHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Arşiv listeleri tek sayfada geliyor; sayfa 2+ boş.
        val items = if (page > 1) {
            emptyList()
        } else {
            app.get(request.data, headers = uaHeaders).document
                .select("div.dizi-boxpost")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
        }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        app.post("$mainUrl/arama/", data = mapOf("arama" to query), headers = uaHeaders).document
            .select("div.dizi-boxpost-cat")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url, headers = uaHeaders).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")

        // Dizi sayfası bölümleri 10'ar 10'ar "/sayfa-N" olarak veriyor.
        val base = url.substringBefore("/sayfa-").trimEnd('/')
        val episodes = doc.select("div.dizi-boxpost-cat").mapNotNull { it.toEpisode() }.toMutableList()
        val lastPage = Regex("""sayfa-(\d+)""")
            .findAll(doc.select("ul.pagination a[href]").joinToString(" ") { it.attr("href") })
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .maxOrNull() ?: 0
        for (page in 1..lastPage) {
            val pageDoc = runCatching { app.get("$base/sayfa-$page", headers = uaHeaders).document }
                .getOrNull() ?: continue
            episodes += pageDoc.select("div.dizi-boxpost-cat").mapNotNull { it.toEpisode() }
        }
        val unique = episodes.distinctBy { it.data }
        if (unique.isEmpty()) throw ErrorLoadingException("$name: bölüm bulunamadı. $REPORT_HINT")

        val title = doc.select("ul.breadcrumbX li").lastOrNull()?.text()
            ?.substringBefore(" son bölüm izle")?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.title().substringBefore(" izle").trim().takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val poster = doc.selectFirst("div.dizi-boxpost-cat img")?.let { imageAttr(it) }
        val plot = doc.selectFirst("meta[name=DESCRIPTION]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, unique.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val linkCallback: (ExtractorLink) -> Unit = { if (Settings.qualityAllowed(it.quality)) callback(it) }
        val subtitleFilter: (SubtitleFile) -> Unit = { if (Settings.subtitleAllowed(it.lang)) subtitleCallback(it) }
        val doc = runCatching { app.get(data, headers = uaHeaders).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: kaynak bulunamadı. $REPORT_HINT")

        // "parts" bloğu aynı bölümün dublaj/altyazı varyantları: aktif olan data, diğerleri ".../2", ".../3".
        // Bazı sayfalarda buraya "Önceki Bölüm" gibi gezinme bağlantısı düşüyor; başka bölüme atlamayı engelle.
        val variants = doc.select("div.parts li a").mapNotNull { anchor ->
            val href = anchor.attr("href").trim()
            val pageUrl = if (href.isBlank()) data else fixUrl(href)
            if (href.isNotBlank() && !pageUrl.startsWith(data)) return@mapNotNull null
            pageUrl to anchor.text().trim().takeUnless { it in NAV_LABELS }.orEmpty()
        }.distinctBy { it.first }.ifEmpty { listOf(data to "") }

        var found = false
        for ((pageUrl, label) in variants) {
            val pageDoc = if (pageUrl == data) {
                doc
            } else {
                runCatching { app.get(pageUrl, headers = uaHeaders).document }.getOrNull() ?: continue
            }
            val frames = pageDoc.select("div.video-g iframe[src]")
                .map { fixUrl(it.attr("src")) }
                .filter { it.startsWith("http") }
                .distinct()
            for (frame in frames) {
                val ok = if (frame.contains("/player/")) {
                    resolvePlayer(frame, pageUrl, label, subtitleFilter, linkCallback)
                } else {
                    resolveEmbed(frame, pageUrl, subtitleFilter, linkCallback)
                }
                if (ok) found = true
            }
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir akış yok. $REPORT_HINT")
        return found
    }

    /** /player/oynat/<hash> — jwplayer setup'ı doğrudan HLS veya MP4 kaynağı taşıyor. */
    private suspend fun resolvePlayer(
        playerUrl: String,
        referer: String,
        label: String,
        subtitleFilter: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = runCatching { app.get(playerUrl, referer = referer, headers = uaHeaders).text }
            .getOrNull() ?: return false
        // Oynatıcı "tracks:" verirse altyazı olarak ilet (şu anki sayfalarda yok, jwplayer desteği için).
        Regex("""tracks\s*:\s*\[([\s\S]*?)]""").find(html)?.groupValues?.get(1)?.let { block ->
            Regex("""file\s*:\s*"([^"]+)"(?:[^}]*?label\s*:\s*"([^"]*)")?""")
                .findAll(block)
                .forEach { match ->
                    val track = match.groupValues[1].replace("\\/", "/")
                    if (track.isNotBlank()) {
                        subtitleFilter.invoke(
                            SubtitleFile(match.groupValues[2].ifBlank { "Türkçe" }, fixUrl(track))
                        )
                    }
                }
        }
        val sources = Regex("""sources\s*:\s*\[([\s\S]*?)]""").find(html)?.groupValues?.get(1).orEmpty()
        val linkName = if (label.isBlank()) name else "$name · $label"
        var found = false
        Regex("""file\s*:\s*"([^"]+)"(?:\s*,\s*label\s*:\s*"([^"]*)")?(?:[^}]*?type\s*:\s*"([^"]*)")?""")
            .findAll(sources)
            .forEach { match ->
                val stream = match.groupValues[1].replace("\\/", "/")
                if (stream.isBlank()) return@forEach
                val type = if (
                    match.groupValues[3].contains("hls", true) ||
                    stream.contains(".m3u8", true) || stream.contains("/hls/", true)
                ) {
                    ExtractorLinkType.M3U8
                } else {
                    ExtractorLinkType.VIDEO
                }
                callback.invoke(newExtractorLink(name, linkName, stream, type) {
                    // streambox referans istiyor; twimg/yandex disk Referer başlığı gelince 403 veriyor.
                    this.referer = if (stream.contains("streambox.")) referer else ""
                    this.quality = getQualityFromName(match.groupValues[2])
                })
                found = true
            }
        return found
    }

    /** /player/telif/index.php (YouTube sarmalayıcı) ve doğrudan YouTube gömmeleri. */
    private suspend fun resolveEmbed(
        embedUrl: String,
        referer: String,
        subtitleFilter: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val target = if (embedUrl.contains("/player/telif/")) {
            val html = runCatching { app.get(embedUrl, referer = referer, headers = uaHeaders).text }
                .getOrNull() ?: return false
            val id = YOUTUBE_ID.find(html)?.groupValues?.get(1) ?: return false
            "https://www.youtube.com/watch?v=$id"
        } else {
            val id = YOUTUBE_ID.find(embedUrl)?.groupValues?.get(1)
            if (id != null) "https://www.youtube.com/watch?v=$id" else embedUrl
        }
        return runCatching { loadExtractor(target, referer, subtitleFilter, callback) }.getOrDefault(false)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a[href*='/diziler/']") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        val title = anchor.text().trim().ifBlank { anchor.attr("title").removeSuffix(" izle").trim() }
        if (title.isBlank()) return null
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = selectFirst("img")?.let { imageAttr(it) }
        }
    }

    private fun Element.toEpisode() = selectFirst("a[href*='/izle/']")?.let { anchor ->
        val href = fixUrlNull(anchor.attr("href")) ?: return@let null
        val label = anchor.text().trim().ifBlank { anchor.attr("title").trim() }.removeSuffix(" izle").trim()
        newEpisode(href) {
            this.name = label.ifBlank { null }
            this.season = SEASON.find(label)?.let { it.groupValues[1].toIntOrNull() } ?: 1
            this.episode = EPISODE.find(label)?.let { it.groupValues[1].toIntOrNull() }
                ?: EPISODE_FROM_URL.find(href)?.let { it.groupValues[1].toIntOrNull() }
        }
    }

    /** Kart resimleri lazyload: gerçek adres data-src'de. */
    private fun imageAttr(img: Element): String? =
        img.attr("data-src").ifBlank { img.attr("src") }.trim().takeIf { it.isNotBlank() }?.let { fixUrl(it) }

    companion object {
        private val YOUTUBE_ID = Regex("""(?:[?&]v=|youtu\.be/|/embed/|/shorts/)([\w-]{11})""")
        private val SEASON = Regex("""(\d+)\.\s*Sezon""")
        private val EPISODE = Regex("""(\d+)\.\s*Bölüm""")
        private val EPISODE_FROM_URL = Regex("""-(\d+)-bolum""")
        private val NAV_LABELS = setOf("Önceki Bölüm", "Sonraki Bölüm", "Önceki", "Sonraki")
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}
