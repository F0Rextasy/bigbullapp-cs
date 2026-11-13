package com.hdfilmizlebest

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document

private fun org.jsoup.nodes.Element.posterSrcLazy(): String? =
    attr("data-src").takeIf { it.isNotBlank() } ?: attr("src")

private fun cleanTitle(raw: String?): String = (raw ?: "")
    .replace(Regex("""\s*\|.*$"""), "")
    .replace(Regex("""\s*[-–]\s*(?:HDFilm|Film|Dizi|Full).*$""", RegexOption.IGNORE_CASE), "")
    .replace(Regex("""\s+izle\b.*$""", RegexOption.IGNORE_CASE), "")
    .replace(Regex("""\s*\(\d{4}\).*$"""), "")
    .replace(Regex("""\s+(?:Türkçe|Dublaj|Altyazılı)\b.*$""", RegexOption.IGNORE_CASE), "")
    .trim().trim('-', '–', '|', ',')

class HDFilmizleBestProvider : MainAPI() {
    private val uaHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9",
    )

    override var mainUrl = "https://www.hdfilmizle.best"
    override var name = "HDFilmizleBest"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val hasMainPage = true
    override val mainPage get() = ROWS.mapNotNull { (rowName, path) ->
        if (!Settings.rowEnabled(rowName)) return@mapNotNull null
        MainPageData(rowName, path)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = runCatching {
            app.get(mainUrl + request.data.replace(PAGE, page.toString()), headers = uaHeaders).document
        }.getOrNull() ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val items = cards(doc)
        val hasNext = items.isNotEmpty() && doc.selectFirst(
            "a[href*='page=${page + 1}'], a[href*='?page=${page + 1}']"
        ) != null
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        val doc = app.get(
            "$mainUrl/search/" + java.net.URLEncoder.encode(term, "UTF-8"),
            headers = uaHeaders,
        ).document
        return cards(doc).filter {
            it.name.contains(term, true) || it.url.contains(term.replace(' ', '-'), true)
        }
    }

    /** Liste kartı yalnız `div.hdf-item-relative > a.item`; menü/tür bağlantıları kart sayılmaz. */
    private fun cards(doc: Document): List<SearchResponse> = doc.select("div.hdf-item-relative a.item[href]")
        .mapNotNull { card ->
            val href = fixUrlNull(card.attr("href")) ?: return@mapNotNull null
            val img = card.selectFirst("img.thumbnail") ?: card.selectFirst("img")
            val title = card.attr("data-title").trim()
                .ifBlank { img?.attr("alt")?.trim().orEmpty() }
                .takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val poster = fixUrlNull(img?.posterSrcLazy())
            val year = YEAR_IN_SLUG.find(href)?.groupValues?.get(1)?.toIntOrNull()
            val score = Score.from10(card.attr("data-score"))
            val isSeries = href.contains("/seri-") || href.contains("/dizi/")
            if (isSeries) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    posterUrl = poster
                    this.year = year
                    this.score = score
                }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    posterUrl = poster
                    this.year = year
                    this.score = score
                }
            }
        }.distinctBy { it.url }.sortedBy { if (it.type == TvType.TvSeries) 0 else 1 }

    companion object {
        private const val PAGE = "PAGE"
        private val YEAR_IN_SLUG = Regex("""((?:19|20)\d{2})/?$""")

        /** Tür/kanal listeleri `page/N/`, bölüm listeleri `?page=N` ile sayfalanır (2026-11-13). */
        /** Ayar diyaloğu satır adları; ROWS ile aynı sırada. */
        val rowNames: List<String> get() = ROWS.keys.toList()

        private val ROWS = linkedMapOf(
            "Aksiyon" to "/tur/aksiyon/page/PAGE/",
            "Aile" to "/tur/aile/page/PAGE/",
            "Animasyon" to "/tur/animasyon/page/PAGE/",
            "Bilim Kurgu" to "/tur/bilim-kurgu/page/PAGE/",
            "Biyografi" to "/tur/biyografi/page/PAGE/",
            "Dram" to "/tur/dram/page/PAGE/",
            "Fantastik" to "/tur/fantastik/page/PAGE/",
            "Gerilim" to "/tur/gerilim/page/PAGE/",
            "Komedi" to "/tur/komedi/page/PAGE/",
            "Korku" to "/tur/korku/page/PAGE/",
            "Macera" to "/tur/macera/page/PAGE/",
            "Romantik" to "/tur/romantik/page/PAGE/",
            "Suç" to "/tur/suc/page/PAGE/",
            "Eski Filmler" to "/eski-filmler/?page=PAGE",
            "Yerli Filmler" to "/yerli-filmler/?page=PAGE",
            "Seri Filmler" to "/seri-filmler/?page=PAGE",
            "Netflix" to "/kanal/netflix/page/PAGE/",
            "Prime Video" to "/kanal/prime-video/page/PAGE/",
            "Disney" to "/kanal/disney/page/PAGE/",
            "HBO" to "/kanal/hbo/page/PAGE/",
        )
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = if (url.contains("?")) url else url.trimEnd('/') + "/"
        val resp = app.get(pageUrl, headers = uaHeaders)
        val doc = resp.document
        val raw = resp.text
        val title = cleanTitle(
            doc.selectFirst("h1")?.text()?.takeIf(String::isNotBlank)
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
        ).ifBlank { throw ErrorLoadingException("$name: başlık okunamadı") }
        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val genres = doc.select("[itemprop=genre], a[href*='/tur/'], a[href*='/kategori/']")
            .mapNotNull { it.attr("content").ifBlank { it.text() }.trim().takeIf(String::isNotBlank) }
            .distinct().take(8)
        val year = Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(raw)?.value?.toIntOrNull()
        val score = Score.from10(
            doc.selectFirst("[itemprop=ratingValue]")?.attr("content")
                ?: Regex(""""(?:imdbPuani|ratingValue|imdb)"\s*:\s*"?([0-9.]+)""").find(raw)?.groupValues?.get(1)
        )
        val basePath = url.substringAfter(mainUrl).substringBefore('?').trimEnd('/')
        val slug = basePath.trim('/').split('/').lastOrNull().orEmpty()
        val slugBase = slug.removeSuffix("-izle").removeSuffix("-film-izle")
        val episodes = doc.select("a[href]").mapNotNull { anchor ->
            val rawHref = anchor.attr("href").substringBefore('#').trim()
            val href = fixUrlNull(rawHref) ?: return@mapNotNull null
            val pathAndQuery = href.substringAfter(mainUrl).substringBefore('#')
            val path = pathAndQuery.substringBefore('?')
            if (path.contains("/admin/") || path.contains("/wp-admin/")) return@mapNotNull null
            if (!Regex("""(?:bolum|episode)""", RegexOption.IGNORE_CASE).containsMatchIn(pathAndQuery)) return@mapNotNull null
            val number = Regex("""(?:sezon|season)[-_ /=]?(\d+).*?(?:bolum|episode)[-_ /=]?([0-9]*)""")
                .find(pathAndQuery)?.groupValues?.getOrNull(2)?.toIntOrNull()
                ?: Regex("""(?:bolum|episode)[-_ /=]?(\d+)""").find(pathAndQuery)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""(\d+)[-_]bolum""").find(pathAndQuery)?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(href) {
                this.name = anchor.text().trim().ifBlank { "Bölüm $number" }
                this.episode = number
            }
        }.distinctBy { it.data }.sortedBy { it.episode ?: 0 }
        if (episodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = score
                this.backgroundPosterUrl = poster
                this.tags = genres.ifEmpty { null }
            }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.score = score
            this.backgroundPosterUrl = poster
            this.tags = genres.ifEmpty { null }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val resp = app.get(data, headers = uaHeaders)
        val text = resp.text.replace("\\/", "/").replace("\\\"", "\"")
        var found = false
        val seen = mutableSetOf<String>()
        suspend fun emit(stream: String, label: String, referer: String) {
            if (!stream.startsWith("http") || !seen.add(stream)) return
            val isM3u8 = stream.contains(".m3u8") || stream.contains("/vs/") || stream.endsWith(".gif")
            val type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            val quality = getQualityFromName(label)
            if (!Settings.qualityAllowed(quality)) return
            callback.invoke(newExtractorLink(name, label, stream, type) {
                this.referer = referer
                this.quality = quality
            })
            found = true
        }
        fun decodeVixolity(html: String): String? {
            val m = Regex("""\}\)\('([0-9a-f]+)',\s*(\d+)\)""").find(html) ?: return null
            return runCatching {
                val rev = String(m.groupValues[1].chunked(2).map { it.toInt(16).toByte() }.toByteArray(), Charsets.ISO_8859_1).reversed()
                val shifted = String(java.util.Base64.getDecoder().decode(rev), Charsets.ISO_8859_1)
                    .map { (it.code - m.groupValues[2].toInt()).toChar() }.joinToString("")
                val code = String(java.util.Base64.getDecoder().decode(shifted), Charsets.UTF_8)
                Regex(""""kaynak"\s*:\s*"([^"]+)"""").find(code)?.groupValues?.get(1)?.replace("\\/", "/")
            }.getOrNull()
        }
        
        loadHdf(data, subtitleCallback) { stream, label, referer -> emit(stream, label, referer) }
        
        
        
        
        

        val media = Regex("""https?://[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?""").findAll(text)
        for (m in media) emit(m.value, "Direct", data)
        val openPlayers = Regex("""openPlayer\(\s*['"](https?://[^'"]+)['"]""").findAll(text)
            .map { it.groupValues[1] }.distinct().toList()
        for (op in openPlayers) {
            val vid = Regex("""[?&]v=([A-Za-z0-9_-]+)""").find(op)?.groupValues?.get(1)
            val origin = Regex("""^(https?://[^/]+)""").find(op)?.groupValues?.get(1)
            if (vid != null && origin != null) {
                emit("$origin/source2.php?v=$vid.m3u8", "Player", "$origin/")
            }
            if (loadExtractor(op, data, subtitleCallback, callback)) found = true
        }
        val embeds = Regex(""""(?:embedUrl|contentUrl|embed_url|player_url)"\s*:\s*"([^"]+)""")
            .findAll(text).map { it.groupValues[1] }.toList()
        for (embed in embeds) {
            val absolute = fixUrl(embed)
            if (absolute == data) continue
            if (!absolute.contains("embed") && !absolute.contains(".mp4") && !absolute.contains(".m3u8")) continue
            if (absolute.matches(Regex(""".*\.(?:mp4|m3u8).*"""))) {
                emit(absolute, "Embed", data)
                continue
            }
            val inner = runCatching { app.get(absolute, headers = uaHeaders, referer = data).text }.getOrNull() ?: continue
            val innerMedia = Regex("""https?://[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?""").findAll(inner)
            for (m in innerMedia) emit(m.value, "Embed", absolute)
        }
        val domFrames = resp.document.select("iframe").mapNotNull {
            val raw = it.attr("src").ifBlank { it.attr("data-src") }.ifBlank { it.attr("vdo-src") }
            fixUrlNull(raw)
        }
        val regexFrames = Regex("""<iframe\b[^>]*?\b(?:src|data-src|vdo-src)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(text).mapNotNull { fixUrlNull(it.groupValues[1]) }.toList()
        val frames = (domFrames + regexFrames).distinct()
        for (frame in frames) {
            if (frame.contains(".mp4") || frame.contains(".m3u8")) {
                emit(frame, "Embed", data)
                continue
            }
            val inner = runCatching { app.get(frame, headers = uaHeaders, referer = data).text }.getOrNull()
            if (inner != null) {
                decodeVixolity(inner)?.let { emit(it, "Vixolity", "https://pl.vixolity.com/") }
                val innerText = inner.replace("\\/", "/")
                for (m in Regex("""https?://[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?""").findAll(innerText)) {
                    emit(m.value, "Embed", frame)
                }
                for (sub in Regex("""<iframe[^>]+(?:src|data-src)="([^"]+)""").findAll(innerText)) {
                    val subUrl = fixUrl(sub.groupValues[1])
                    if (loadExtractor(subUrl, frame, subtitleCallback, callback)) found = true
                }
            }
            if (loadExtractor(frame, "$mainUrl/", subtitleCallback, callback)) found = true
        }
        val subs = Regex("""https?://[^"'\s<>]+?\.(?:vtt|srt)(?:\?[^"'\s<>]*)?""").findAll(text)
        for (m in subs) {
            if (Settings.subtitleAllowed("tr")) subtitleCallback.invoke(newSubtitleFile("tr", m.value))
        }
        if (!found && frames.isEmpty() && embeds.isEmpty() && media.none()) {
            throw ErrorLoadingException("$name: oynatılabilir akış yok")
        }
        return found
    }



    private suspend fun loadHdf(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        emit: suspend (String, String, String) -> Unit,
    ) {
        val page = runCatching { app.get(data, headers = uaHeaders).text }.getOrNull() ?: return
        val nonce = Regex("""_hdfNonce_\s*=\s*"([^"]+)""").find(page)?.groupValues?.get(1) ?: return
        val post = Regex("""data-post="(\d+)""").find(page)?.groupValues?.get(1) ?: return
        val body = runCatching {
            app.post(
                fixUrl("/ajax/videosrc/?id=$post&lang=tr&mr=0"),
                data = emptyMap(),
                headers = uaHeaders + mapOf("X-HDF-Nonce" to nonce),
                referer = data,
            ).text
        }.getOrNull() ?: return
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return
        val src = root.optString("src")
        if (src.startsWith("http")) {
            emit(src, "HDF", data)
            root.optJSONArray("tracks")?.let { tracks ->
                for (i in 0 until tracks.length()) {
                    val track = tracks.optJSONObject(i) ?: continue
                    val url = track.optString("src")
                    if (!url.startsWith("http")) continue
                    val label = track.optString("label").ifBlank { track.optString("srclang") }
                    if (Settings.subtitleAllowed(label)) {
                        subtitleCallback.invoke(newSubtitleFile(label, url))
                    }
                }
            }
        }
    }





}
