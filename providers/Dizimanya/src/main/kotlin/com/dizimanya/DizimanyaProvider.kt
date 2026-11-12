package com.dizimanya

import com.bigbull.common.browserHeaders
import com.bigbull.common.posterSrcLazy
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Dizimanya — dizimanya.com (WordPress "dizimanya" teması, Türkçe altyazılı yabancı dizi).
 * Bölüm sayfası yalnızca iframe taşır: `ksdpictures.site/dzembed.php?id=...`; gerçek akış
 * o sayfadaki `window.NP_CONFIG` JSON'unda (`sources[].master` m3u8 + `subtitles[].url` vtt).
 */
class DizimanyaProvider : MainAPI() {
    override var mainUrl = "https://dizimanya.com"
    override var name = "Dizimanya"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    private val uaHeaders get() = browserHeaders

    override val mainPage get() = ROWS.mapNotNull { row ->
        if (!Settings.rowEnabled(row)) return@mapNotNull null
        val url = when (row) {
            "Son Eklenen Bölümler" -> "$mainUrl/tum-bolumler/"
            "Son Eklenen Diziler" -> "$mainUrl/diziler/?filtrele=tarih&sirala=DESC"
            "Popüler Diziler" -> "$mainUrl/diziler/?filtrele=imdb&sirala=DESC"
            else -> "$mainUrl/diziler/?tur=" + URLEncoder.encode(row, "UTF-8")
        }
        MainPageData(row, url)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data
        val isEpisodeRow = data.contains("tum-bolumler")
        val base = data.substringBefore('?')
        val query = data.substringAfter('?', "").takeIf { it.isNotBlank() }
        val url = buildString {
            append(base)
            append('?')
            if (query != null) append(query).append('&')
            append("sayfa=").append(page)
        }
        val doc = app.get(url, headers = uaHeaders).document
        val items = if (isEpisodeRow) {
            doc.select("a[href*='-bolum/']").mapNotNull { anchor ->
                val episodeUrl = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
                val slug = EPISODE_SLUG.find(episodeUrl)?.groupValues?.get(1) ?: return@mapNotNull null
                newTvSeriesSearchResponse(slug.replace('-', ' '), "$mainUrl/dizi/$slug/", TvType.TvSeries) {
                    this.posterUrl = anchor.selectFirst("img")?.posterSrcLazy()
                }
            }
        } else {
            doc.select("a.r-card[href]").mapNotNull { card ->
                val link = fixUrlNull(card.attr("href")) ?: return@mapNotNull null
                if (!link.contains("/dizi/")) return@mapNotNull null
                val title = card.selectFirst("img[alt]")?.attr("alt")
                    ?.removeSuffix(" izle")?.removeSuffix(" İzle")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                    this.posterUrl = card.selectFirst("img")?.posterSrcLazy()
                    this.score = SCORE.find(card.selectFirst("span.r-imdb")?.text().orEmpty())?.value?.replace(',', '.')?.toDoubleOrNull()?.let { Score.from10(it) }
                }
            }
        }.distinctBy { it.url }
        val hasNext = doc.select("a[href*='sayfa=${page + 1}']").isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }
    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val doc = app.get("$mainUrl/?s=$encoded", headers = uaHeaders).document
        return doc.select("div.single-item").mapNotNull { it.toSearchCard() }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url, headers = uaHeaders).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: dizi sayfası yüklenemedi. $REPORT_HINT")
        val title = doc.selectFirst("div#content h1, h1")?.text()?.trim()
            ?.replace(TITLE_SUFFIX, "")?.removeSuffix(" izle")?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("$name: dizi bilgisi okunamadı. $REPORT_HINT")

        // Bölüm bağlantıları `/<dizi>-<S>-sezon-<E>-bolum/` biçiminde; sezon listesi varsa
        // hepsi, yoksa künyedeki "İlk/Son Bölüm" düğmeleri gelir.
        val episodes = doc.select("a[href*='-bolum/']").mapNotNull { anchor ->
            val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            if (!EPISODE_SLUG.containsMatchIn(href)) return@mapNotNull null
            val season = SEASON_IN_URL.find(href)?.groupValues?.get(1)?.toIntOrNull()
            val number = EPISODE_IN_URL.find(href)?.groupValues?.get(1)?.toIntOrNull()
            val label = anchor.text().trim().takeIf { it.isNotEmpty() }
            newEpisode(href) {
                this.name = label
                this.season = season
                this.episode = number
            }
        }.distinctBy { it.data }.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
        if (episodes.isEmpty()) {
            // Bölüm adresi doğrudan açılırsa dizi sayfasına indirgenir.
            val slug = EPISODE_SLUG.find(url)?.groupValues?.get(1)
            if (slug != null) return load("$mainUrl/dizi/$slug/")
            throw ErrorLoadingException("$name: bölüm listesi bulunamadı. $REPORT_HINT")
        }
        val meta = doc.selectFirst("div.r-meta")?.text().orEmpty().ifBlank {
            doc.selectFirst("div#icerikcat2")?.text().orEmpty()
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = doc.selectFirst("div.r-dposter img, div.category_image img")?.posterSrcLazy()
            this.posterHeaders = uaHeaders
            this.plot = doc.selectFirst("div.r-dinfo p, div.category_desc")?.text()?.trim()
                ?.takeIf { it.isNotEmpty() }
            this.year = YEAR.find(meta)?.value?.toIntOrNull()
            this.score = scoreOf(meta)
            this.tags = doc.select("div.r-meta a[href*='/tur/'], div.genres a")
                .map { it.text().trim() }.filter { it.isNotEmpty() }
        }
    }
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val doc = runCatching { app.get(data, headers = uaHeaders).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: bölüm sayfası yüklenemedi. $REPORT_HINT")

        // Oynatıcı bazen iframe yerine satır içi script olarak geliyor.
        val players = (
            doc.select("div.video-container iframe[src], iframe[src]")
                .mapNotNull { fixUrlNull(it.attr("src")) } +
                EMBED_IN_HTML.findAll(doc.html()).map { it.value }
            ).filter { it.startsWith("http") }.distinct()

        val seenLinks = mutableSetOf<String>()
        val seenSubs = mutableSetOf<String>()
        var found = false

        suspend fun emit(stream: String, label: String, referer: String) {
            if (!stream.startsWith("http") || !seenLinks.add(stream)) return
            val quality = Qualities.Unknown.value
            if (!Settings.qualityAllowed(quality)) return
            val type = if (stream.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback.invoke(newExtractorLink(name, label, stream, type) {
                this.referer = referer
                this.quality = quality
            })
            found = true
        }

        for (player in players) {
            val html = runCatching { app.get(player, headers = uaHeaders, referer = data).text }
                .getOrNull() ?: continue
            val config = jsonAfter(html, "NP_CONFIG")

            // dzembed.php: m3u8 master + vtt altyazılar
            val sources = config?.optJSONArray("sources")
            for (i in 0 until (sources?.length() ?: 0)) {
                val source = sources?.optJSONObject(i) ?: continue
                val stream = source.optString("master").ifBlank { source.optString("file") }
                emit(stream, "$name • " + source.optString("title").ifBlank { "Orijinal" }, player)
            }

            val subtitles = config?.optJSONArray("subtitles")
            for (i in 0 until (subtitles?.length() ?: 0)) {
                val sub = subtitles?.optJSONObject(i) ?: continue
                val subUrl = sub.optString("url").takeIf { it.startsWith("http") } ?: continue
                val language = sub.optString("lang").ifBlank { sub.optString("label") }
                if (!seenSubs.add(subUrl) || !Settings.subtitleAllowed(language)) continue
                subtitleCallback.invoke(newSubtitleFile(language, subUrl) {
                    this.headers = mapOf("Referer" to player, "user-agent" to UA)
                })
            }

            // psource/<uuid>: parçalar ayrı JSON'da (Twitter HLS gibi doğrudan m3u8/mp4)
            val psource = jsonAfter(html, "PSOURCE_CONFIG")
            val parts = psource?.optJSONArray("parts")
            for (i in 0 until (parts?.length() ?: 0)) {
                val part = parts?.optJSONObject(i) ?: continue
                emit(part.optString("url"), "$name • " + part.optString("label").ifBlank { "Kaynak" }, player)
            }

            // İki yapılandırma da yoksa: sayfadaki çıplak m3u8/mp4 adresleri.
            if (config == null && psource == null) {
                BARE_STREAM.findAll(html).forEach { match -> emit(match.value, name, player) }
            }
        }

        if (!found) throw ErrorLoadingException("$name: oynatılabilir akış bulunamadı. $REPORT_HINT")
        return true
    }

    // ------------------------------------------------------------------ kartlar

    /** `/diziler/` ve arama sonuç kartları (`div.single-item`). */
    private fun Element.toSearchCard(): SearchResponse? {
        val link = fixUrlNull(selectFirst("div.cat-img a[href]")?.attr("href")) ?: return null
        if (!link.contains("/dizi/")) return null
        val title = selectFirst("div.categorytitle a")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        return newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
            this.posterUrl = selectFirst("div.cat-img img")?.posterSrcLazy()
            this.year = metaValue("Yapım Yılı")?.let { YEAR.find(it)?.value?.toIntOrNull() }
            this.score = scoreOf(selectFirst("div.imdbp")?.text())
        }
    }

    /**
     * `/tum-bolumler/` bölüm kartları dizi adı vermez, yalnızca bölüm adresi:
     * `/<dizi-slug>-<sezon>-sezon-<bölüm>-bolum/` → dizi sayfasına indirgenir.
     */
    private fun Element.toEpisodeSeries(): SearchResponse? {
        val episodeUrl = fixUrlNull(selectFirst("a[href]")?.attr("href")) ?: return null
        val slug = EPISODE_SLUG.find(episodeUrl)?.groupValues?.get(1) ?: return null
        val title = selectFirst("div.serie-name a")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() } ?: slug
        return newTvSeriesSearchResponse(title, "$mainUrl/dizi/$slug/", TvType.TvSeries) {
            this.posterUrl = selectFirst("img")?.posterSrcLazy()
        }
    }

    /** `span.dizimeta` etiketli meta satırının değeri ("Yapım Yılı : 2026"). */
    private fun Element.metaValue(label: String): String? =
        select("span.dizimeta").firstOrNull { it.text().contains(label) }
            ?.parent()?.text()?.substringAfter(':')?.trim()?.ifBlank { null }

    private fun scoreOf(text: String?): Score? =
        SCORE.find(text.orEmpty())?.value?.replace(',', '.')?.toDoubleOrNull()
            ?.takeIf { it in 0.0..10.0 }?.let { Score.from10(it) }

    /**
     * `window.NP_CONFIG = {...};` gövdesi yuvalanmış olduğu için süslü parantez sayılır.
     * shortcut: başlık/açıklama içinde `{` geçerse sınır şaşar; öyle bir veri görülürse
     * JSON kütüphanesiyle tam tarama yapılmalı.
     */
    private fun jsonAfter(html: String, marker: String): JSONObject? {
        val start = html.indexOf(marker).takeIf { it >= 0 } ?: return null
        val open = html.indexOf('{', start).takeIf { it >= 0 } ?: return null
        var depth = 0
        for (i in open until html.length) {
            when (html[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) {
                    return runCatching { JSONObject(html.substring(open, i + 1)) }.getOrNull()
                }
            }
        }
        return null
    }

    companion object {
        private val GENRES = listOf(
            "Dram", "Komedi", "Aksiyon", "Gerilim", "Romantik", "Suç",
            "Gizem", "Bilim Kurgu", "Korku", "Fantastik", "Macera", "Tarih",
        )

        /** Ana sayfa satırları; ayarlardan kapatılabilir. */
        val ROWS = listOf("Son Eklenen Diziler", "Popüler Diziler")

        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"

        private val TITLE_SUFFIX = Regex("""\s*izle\s*-\s*Dizimanya\s*$""", RegexOption.IGNORE_CASE)
        private val YEAR = Regex("""\b(19|20)\d{2}\b""")
        private val SCORE = Regex("""\d+(?:[.,]\d+)?""")
        private val SEASON_IN_TEXT = Regex("""(\d+)\.\s*Sezon""")
        private val EPISODE_IN_TEXT = Regex("""(\d+)\.\s*Bölüm""")
        private val SEASON_IN_URL = Regex("""-(\d+)-sezon""")
        private val EPISODE_IN_URL = Regex("""-(\d+)-bolum""")
        private val EPISODE_SLUG = Regex("""/([^/]+?)-\d+-sezon-\d+-bolum/?$""")
        private val EMBED_IN_HTML = Regex("""https?://[^"'\s]+dzembed\.php\?id=[\w-]+""")
        private val BARE_STREAM = Regex("""https?://[^"'\s<>]+\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?""")

    }
}
