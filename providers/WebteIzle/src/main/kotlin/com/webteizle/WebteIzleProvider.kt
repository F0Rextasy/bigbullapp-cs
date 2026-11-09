package com.webteizle

import com.bigbull.common.browserHeaders
import com.bigbull.common.posterSrcLazy
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * WebteIzle — webteizle.info. Site Cloudflare arkasında (CloudflareKiller) ve sayfaları
 * windows-1254 kodlamalı; arama `?a=` parametresini de bu kodlamada bekler. Film detayı
 * `#wip[data-id]` film kimliğini taşır; oynatıcılar `dataAlternatif3.asp` (dil 0 = dublaj,
 * 1 = altyazı) üzerinden satır satır gelir. `dataEmbed.asp` Turnstile kapısına düşebildiği
 * için satırlar önce kapısız `/player/<tur>.asp?v=<id>` uçlarından çözülür.
 */
class WebteIzleProvider : MainAPI() {
    override var mainUrl = "https://webteizle.info"
    private val uaHeaders get() = browserHeaders
    private val killer = CloudflareKiller()
    private val posterHdrs get() = uaHeaders + mapOf("Referer" to "$mainUrl/") + killer.getCookieHeaders(mainUrl).toMap()
    private val ajaxHeaders get() = browserHeaders + mapOf(
        "X-Requested-With" to "XMLHttpRequest",
        "Origin" to mainUrl,
    )
    override var name = "WebteIzle"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage get() = ROW_BASES.keys.mapNotNull { row ->
        if (!Settings.rowEnabled(row)) return@mapNotNull null
        MainPageData(row, "$mainUrl${ROW_BASES.getValue(row)}")
    }

    private suspend fun getDoc(url: String, referer: String? = null) = runCatching {
        app.get(url, headers = uaHeaders, referer = referer ?: "$mainUrl/", interceptor = killer).document
    }.getOrNull()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = ROW_BASES[request.name] ?: request.data.removePrefix(mainUrl)
        val doc = getDoc("$mainUrl$base/$page")
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val items = doc.select(CARD).mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a.image[href]") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        val title = selectFirst("div.filmname")?.text()?.trim().orEmpty()
            .ifBlank { anchor.attr("title").substringBefore("1080p").trim() }
        if (title.isBlank()) return null
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = fixUrlNull(selectFirst("img")?.posterSrcLazy())
            this.posterHeaders = posterHdrs
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        val doc = getDoc("$mainUrl/filtre?a=${encodeWin1254(q)}") ?: return emptyList()
        return doc.select(CARD).mapNotNull { it.toSearchResult() }.distinctBy { it.url }
            .sortedBy {
                when {
                    it.name.equals(q, ignoreCase = true) -> 0
                    it.name.startsWith(q, ignoreCase = true) -> 1
                    else -> 2
                }
            }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = getDoc(url) ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val filmId = doc.selectFirst("#wip[data-id]")?.attr("data-id")?.trim().orEmpty()
        val title = cleanTitle(doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty())
            .ifBlank { cleanTitle(doc.selectFirst("h1")?.text().orEmpty()) }
            .ifBlank { throw ErrorLoadingException("$name: başlık bulunamadı. $REPORT_HINT") }
        val year = YEAR.find(doc.selectFirst("h1")?.text().orEmpty())?.groupValues?.get(1)?.toIntOrNull()

        val infoRows = doc.select("table tr")
        val duration = infoRows.firstOrNull { it.selectFirst("td")?.text()?.contains("Süre") == true }
            ?.select("td")?.getOrNull(1)?.text()
            ?.let { MINUTES.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val tags = infoRows.firstOrNull { it.selectFirst("td")?.text()?.contains("Tür") == true }
            ?.select("a[href*=filtre]")?.map { it.text().trim() }?.filter { it.isNotBlank() } ?: emptyList()
        val actors = doc.select("div[data-tab=oyuncular] a.image").mapNotNull { link ->
            val actor = link.selectFirst("span.content")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: link.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            actor?.let { ActorData(Actor(it)) }
        }.distinctBy { it.actor.name }
        val trailerId = doc.selectFirst("div.ui.embed[data-id]")?.attr("data-id")?.trim().orEmpty()

        return newMovieLoadResponse(title, url, TvType.Movie, "$url|$filmId") {
            this.posterUrl = fixUrlNull(doc.selectFirst(".afis img[data-src]")?.posterSrcLazy())
            this.plot = doc.selectFirst(".filmkonu blockquote")?.text()?.trim()
            this.year = year
            this.duration = duration
            if (tags.isNotEmpty()) this.tags = tags
            if (actors.isNotEmpty()) this.actors = actors
            if (trailerId.isNotBlank()) addTrailer("https://www.youtube.com/embed/$trailerId")
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
        val detailUrl = data.substringBefore("|")
        val filmId = data.substringAfter("|", "").trim().ifBlank {
            getDoc(detailUrl)?.selectFirst("#wip[data-id]")?.attr("data-id")?.trim().orEmpty()
        }
        if (filmId.isBlank()) throw ErrorLoadingException("$name: film kimliği bulunamadı. $REPORT_HINT")

        var found = false
        for ((dil, tag) in DILLER) {
            val body = runCatching {
                app.post(
                    "$mainUrl/ajax/dataAlternatif3.asp",
                    data = mapOf(
                        "filmid" to filmId,
                        "dil" to dil.toString(),
                        "s" to "",
                        "b" to "",
                        "bot" to "0",
                    ),
                    headers = ajaxHeaders,
                    referer = detailUrl,
                    interceptor = killer,
                ).text
            }.getOrNull() ?: continue
            val rows = runCatching { JSONObject(body).optJSONArray("data") }.getOrNull() ?: continue

            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val rowId = row.optString("id").trim()
                val provider = row.optString("baslik").trim()
                if (rowId.isBlank() || provider.isBlank()) continue
                if (resolve(rowId, provider, detailUrl, "$tag · $provider", subtitleFilter, linkCallback)) {
                    found = true
                }
            }
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir akış yok. $REPORT_HINT")
        return found
    }

    /** Satır -> önce Turnstile'sız `/player/<tur>.asp`, olmazsa `dataEmbed.asp` markup'ı. */
    private suspend fun resolve(
        rowId: String,
        provider: String,
        referer: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // dataEmbed gerçek sağlayıcı kodunu verir (ör. vidmoly('gyhxhqf516jj')); Turnstile
        // kapısına düşerse satırın kapısız `/player/<tur>.asp` sarmalayıcısı denenir.
        val embed = runCatching {
            app.post(
                "$mainUrl/ajax/dataEmbed.asp",
                data = mapOf("id" to rowId),
                headers = ajaxHeaders,
                referer = referer,
                interceptor = killer,
            ).text
        }.getOrNull()
        if (embed != null && !embed.contains(CAPTCHA_MARK) &&
            handleMarkup(embed, label, referer, subtitleCallback, callback)
        ) {
            return true
        }

        PLAYER_PATHS[provider.lowercase()]?.let { path ->
            val html = runCatching {
                app.get("$mainUrl$path$rowId", headers = uaHeaders, referer = referer, interceptor = killer).text
            }.getOrNull()
            if (html != null && handleMarkup(html, label, referer, subtitleCallback, callback)) return true
        }
        return false
    }

    private suspend fun handleMarkup(
        html: String,
        label: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        IFRAME.find(html)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotBlank() && !it.contains(CAPTCHA_MARK) }
            ?.let { raw ->
                // Site protokol-göreli iframe yazıyor ("//vidmoly.net/embed-x.html").
                val src = if (raw.startsWith("//")) "https:$raw" else raw
                if (src.contains("vidmoly") && resolveVidmoly(src, label, callback)) return true
                return loadExtractor(src, referer, subtitleCallback, callback)
            }

        VID_URL.find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }?.let { url ->
            callback(
                newExtractorLink(name, label, url, ExtractorLinkType.VIDEO) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }

        val call = FUNCTION_CALL.find(html) ?: return false
        val arg = call.groupValues[2]
        // Pixel kodu "indirmeId|ek" biçiminde; doğrudan dosya ucu oynatılabilir.
        if (call.groupValues[1] == "pixel") {
            val pixelUrl = "https://pixeldrain.com/api/file/${arg.substringBefore('|')}"
            val code = runCatching { app.get(pixelUrl, headers = uaHeaders + mapOf("Range" to "bytes=0-0")).code }.getOrDefault(0)
            if (code !in 200..299) return false
            callback(
                newExtractorLink(name, label, pixelUrl,
                    ExtractorLinkType.VIDEO) {
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }
        if (call.groupValues[1] == "vidmoly") {
            return resolveVidmoly("https://vidmoly.net/embed-$arg.html", label, callback)
        }
        val url = when (call.groupValues[1]) {
            "okru" -> "https://ok.ru/videoembed/$arg"
            "filemoon" -> "https://bysezoxexe.com/e/$arg"
            "sruby" -> "https://streamruby.com/$arg.html"
            else -> return false
        }
        return loadExtractor(url, referer, subtitleCallback, callback)
    }

    private suspend fun resolveVidmoly(embed: String, label: String, callback: (ExtractorLink) -> Unit): Boolean {
        val target = embed.replace(Regex("""vidmoly\.(?:to|me|biz)"""), "vidmoly.net")
        val html = runCatching {
            app.get(target, referer = "$mainUrl/", headers = uaHeaders + mapOf("Sec-Fetch-Dest" to "iframe")).text
        }.getOrNull() ?: return false
        val m3u8 = Regex("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(html)?.groupValues?.get(1)
            ?: return false
        callback(
            newExtractorLink(name, label, m3u8, ExtractorLinkType.M3U8) {
                this.referer = "https://vidmoly.net/"
                this.quality = Qualities.Unknown.value
                this.headers = uaHeaders
            }
        )
        return true
    }

    /** `?a=` değeri windows-1254 baytlarıyla yüzde kodlanır; UTF-8 arama boş döner. */
    private fun encodeWin1254(value: String): String =
        URLEncoder.encode(value, "windows-1254").replace("+", "%20")

    /** "X izle (2026)" / "X izle" -> "X". */
    private fun cleanTitle(raw: String): String = raw.trim()
        .replace(IZLE_SUFFIX, "")
        .replace(YEAR_SUFFIX, "")
        .trim()

    companion object {
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
        private const val CAPTCHA_MARK = "reCAPTCHADATA"

        private val ROW_BASES = linkedMapOf(
            "Yeni Filmler" to "/film-izle",
            "Türkçe Dublaj" to "/tr-dublaj-filmler",
            "4K Filmler" to "/4k-film-izle",
        )
        val ROWS: List<String> = ROW_BASES.keys.toList()

        private const val CARD = "div.golgever"
        private val DILLER = listOf(0 to "Dublaj", 1 to "Altyazı")

        /** dataAlternatif3 `baslik` -> kapısız oynatıcı ucu. */
        private val PLAYER_PATHS = mapOf(
            "vidmoly" to "/player/vidmoly.asp?v=",
            "ok.ru" to "/player/ok.ru.asp?v=",
            "okru" to "/player/ok.ru.asp?v=",
            "pixel" to "/player/pixel.asp?v=",
            "dzen" to "/player/dzen.asp?v=",
            "mail.ru" to "/player/video-mail-ru.asp?v=",
            "mailru" to "/player/video-mail-ru.asp?v=",
        )

        private val IFRAME = Regex("""<iframe[^>]*\ssrc="([^"]*)"""")
        private val VID_URL = Regex("""vidUrl\s*=\s*"([^"]+)"""")
        private val FUNCTION_CALL = Regex("""(vidmoly|okru|filemoon|pixel|sruby|mailru)\(\s*'([^']+)'""")
        private val IZLE_SUFFIX = Regex("""\s+izle\b.*$""")
        private val YEAR_SUFFIX = Regex("""\s*\(\d{4}\)\s*$""")
        private val YEAR = Regex("""\((\d{4})\)""")
        private val MINUTES = Regex("""(\d+)\s*dakika""")
    }
}
