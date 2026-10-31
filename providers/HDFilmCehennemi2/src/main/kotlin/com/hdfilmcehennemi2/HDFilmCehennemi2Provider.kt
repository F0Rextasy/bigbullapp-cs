package com.hdfilmcehennemi2

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

private fun asciiFold(value: String): String = value
    .replace('ı', 'i').replace('İ', 'I')
    .replace('ş', 's').replace('Ş', 'S')
    .replace('ğ', 'g').replace('Ğ', 'G')
    .replace('ü', 'u').replace('Ü', 'U')
    .replace('ö', 'o').replace('Ö', 'O')
    .replace('ç', 'c').replace('Ç', 'C')

private fun cleanTitle(raw: String?): String = (raw ?: "")
    .replace(Regex("""\s*\|.*$"""), "")
    .replace(Regex("""\s*[-–]\s*(?:HDFilm|Film|Dizi|Full).*$""", RegexOption.IGNORE_CASE), "")
    .replace(Regex("""\s+izle\b.*$""", RegexOption.IGNORE_CASE), "")
    .replace(Regex("""\s*\(\d{4}\).*$"""), "")
    .replace(Regex("""\s+(?:Türkçe|Dublaj|Altyazılı)\b.*$""", RegexOption.IGNORE_CASE), "")
    .trim().trim('-', '–', '|', ',')

class HDFilmCehennemi2Provider : MainAPI() {
    private val uaHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9",
    )

    override var mainUrl = "https://www.hdfilmcehennemi2.biz"
    override var name = "HDFilmCehennemi2"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val hasMainPage = true
    override val mainPage get() = ROWS.mapNotNull { rowName ->
        if (!Settings.rowEnabled(rowName)) return@mapNotNull null
        MainPageData(rowName, when (rowName) {
            "Filmler" -> "https://www.hdfilmcehennemi2.biz/filmler/"
            "Diziler" -> "https://www.hdfilmcehennemi2.biz/dizi-izle/"
            "Aile Filmleri" -> "https://www.hdfilmcehennemi2.biz/tur/aile-filmleri/"
            "Aksiyon Filmleri" -> "https://www.hdfilmcehennemi2.biz/tur/aksiyon-filmleri-izle/"
            "Animasyon Filmleri" -> "https://www.hdfilmcehennemi2.biz/tur/animasyon-film-izle/"
            "Belgesel" -> "https://www.hdfilmcehennemi2.biz/tur/belgesel-filmleri/"
            "Bilim Kurgu Filmleri" -> "https://www.hdfilmcehennemi2.biz/tur/bilim-kurgu-filmleri-izle/"
            "Biyografi Filmleri" -> "https://www.hdfilmcehennemi2.biz/tur/biyografi-filmleri/"
            "Dram Filmleri" -> "https://www.hdfilmcehennemi2.biz/tur/dram-filmleri/"
            "Fantastik Filmleri" -> "https://www.hdfilmcehennemi2.biz/tur/fantastik-filmleri/"
            "Gerilim Filmleri" -> "https://www.hdfilmcehennemi2.biz/tur/gerilim-filmleri/"
            "Gizem Filmleri" -> "https://www.hdfilmcehennemi2.biz/tur/gizem-filmleri/"
            "Komedi Filmleri" -> "https://www.hdfilmcehennemi2.biz/tur/komedi-filmleri/"
            "Korku Filmleri" -> "https://www.hdfilmcehennemi2.biz/tur/korku-filmleri/"
            "Macera Filmleri" -> "https://www.hdfilmcehennemi2.biz/tur/macera-filmleri/"
            "Müzik Filmleri" -> "https://www.hdfilmcehennemi2.biz/tur/muzik-filmleri/"
            "Romantik Filmleri" -> "https://www.hdfilmcehennemi2.biz/tur/romantik-filmler/"
            "Savaş Filmleri" -> "https://www.hdfilmcehennemi2.biz/tur/savas-filmleri/"
            "Spor Filmleri" -> "https://www.hdfilmcehennemi2.biz/tur/spor-filmleri/"
            "Suç Filmleri" -> "https://www.hdfilmcehennemi2.biz/tur/suc-filmleri/"
            "Tarih Filmleri" -> "https://www.hdfilmcehennemi2.biz/tur/tarih-filmleri/"
            "Western Filmleri" -> "https://www.hdfilmcehennemi2.biz/tur/western-filmleri/"
            "2026 Filmleri" -> "https://www.hdfilmcehennemi2.biz/yil/2026/"
            "2025 Filmleri" -> "https://www.hdfilmcehennemi2.biz/yil/2025/"
            "2024 Filmleri" -> "https://www.hdfilmcehennemi2.biz/yil/2024/"
            "2023 Filmleri" -> "https://www.hdfilmcehennemi2.biz/yil/2023/"
            "2022 Filmleri" -> "https://www.hdfilmcehennemi2.biz/yil/2022/"
            "2021 Filmleri" -> "https://www.hdfilmcehennemi2.biz/yil/2021/"
            "2020 Filmleri" -> "https://www.hdfilmcehennemi2.biz/yil/2020/"
            "Tavsiye Filmler" -> "https://www.hdfilmcehennemi2.biz/kategori/tavsiye-filmleri-izle/"
            else -> mainUrl
        })
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

            if (request.name in listOf("Filmler", "Diziler", "Aile Filmleri", "Aksiyon Filmleri", "Animasyon Filmleri", "Belgesel", "Bilim Kurgu Filmleri", "Biyografi Filmleri", "Dram Filmleri", "Fantastik Filmleri", "Gerilim Filmleri", "Gizem Filmleri", "Komedi Filmleri", "Korku Filmleri", "Macera Filmleri", "Müzik Filmleri", "Romantik Filmleri", "Savaş Filmleri", "Spor Filmleri", "Suç Filmleri", "Tarih Filmleri", "Western Filmleri", "2026 Filmleri", "2025 Filmleri", "2024 Filmleri", "2023 Filmleri", "2022 Filmleri", "2021 Filmleri", "2020 Filmleri", "Tavsiye Filmler")) {
                if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
                val doc = app.get(request.data, headers = uaHeaders).document
                return newHomePageResponse(request.name, cards(doc), hasNext = false)
            }
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
    }

    companion object {
        val ROWS = listOf("Filmler", "Diziler", "Aile Filmleri", "Aksiyon Filmleri", "Animasyon Filmleri", "Belgesel", "Bilim Kurgu Filmleri", "Biyografi Filmleri", "Dram Filmleri", "Fantastik Filmleri", "Gerilim Filmleri", "Gizem Filmleri", "Komedi Filmleri", "Korku Filmleri", "Macera Filmleri", "Müzik Filmleri", "Romantik Filmleri", "Savaş Filmleri", "Spor Filmleri", "Suç Filmleri", "Tarih Filmleri", "Western Filmleri", "2026 Filmleri", "2025 Filmleri", "2024 Filmleri", "2023 Filmleri", "2022 Filmleri", "2021 Filmleri", "2020 Filmleri", "Tavsiye Filmler")
    }

    private val pathPrefixes = listOf("/", "/dizi/", "/tur/")

    override suspend fun search(query: String): List<SearchResponse> {
        val q = if (false) asciiFold(query) else query
        val url = "https://www.hdfilmcehennemi2.biz".replace("{q}", java.net.URLEncoder.encode(q, "UTF-8"))
        return cards(app.get(url, headers = uaHeaders).document, q)
    }

    private fun cards(doc: Document, query: String? = null): List<SearchResponse> = doc.select("a[href]").mapNotNull { card ->
        val href = fixUrlNull(card.attr("href")) ?: return@mapNotNull null
        val path = card.attr("href").substringAfter(mainUrl, card.attr("href")).substringBefore('?')
        if (listOf("/", "/dizi/", "/tur/").none { path.startsWith(it) }) return@mapNotNull null
        if (!path.trimEnd('/').endsWith("-izle")) return@mapNotNull null
        if (path == "/" || path.contains("login") || path.contains("uye-ol") ||
            path.contains("/giris") || href.contains("?ara=") || href.contains("sirala=")) return@mapNotNull null
        val img = card.selectFirst("img")
        val title = card.attr("title").trim()
            .ifBlank { card.text().trim() }
            .ifBlank { img?.attr("alt")?.trim().orEmpty() }
            .takeIf { it.isNotBlank() } ?: return@mapNotNull null
        if (query != null && !title.contains(query, ignoreCase = true) &&
            !href.contains(query, ignoreCase = true)) return@mapNotNull null
        val hrefLower = href.lowercase()
        val isSeries = hrefLower.contains("/dizi/") || hrefLower.contains("/seri/") ||
            hrefLower.contains("/anime/") || hrefLower.contains("/series/")
        if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = fixUrlNull(img?.posterSrcLazy())
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = fixUrlNull(img?.posterSrcLazy())
            }
        }
    }.distinctBy { it.url }

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
            val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            val path = href.substringAfter(mainUrl).substringBefore('?')
            if (path.contains("/admin/") || path.contains("/wp-admin/")) return@mapNotNull null
            if (!Regex("""(?:bolum|episode)""", RegexOption.IGNORE_CASE).containsMatchIn(path)) return@mapNotNull null
            val number = Regex("""(?:sezon|season)[-_/]?(\d+).*?(?:bolum|episode)[-_/]?([0-9]*)""")
                .find(path)?.groupValues?.getOrNull(2)?.toIntOrNull()
                ?: Regex("""(?:bolum|episode)[-_/]?(\d+)""").find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""(\d+)[-_]bolum""").find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
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
            val type = if (stream.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            val quality = getQualityFromName(label)
            if (!Settings.qualityAllowed(quality)) return
            callback.invoke(newExtractorLink(name, label, stream, type) {
                this.referer = referer
                this.quality = quality
            })
            found = true
        }
        
        
        
        loadAlpine(data, subtitleCallback) { stream, label, referer -> emit(stream, label, referer) }

        val known = listOf<String>()
        for (u in known) emit(u, "Site", if (u.contains("rumble.com")) "https://rumble.com/" else data)
        val media = Regex("""https?://[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)""").findAll(text)
        for (m in media) emit(m.value, "Direct", data)
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
            val innerMedia = Regex("""https?://[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)""").findAll(inner)
            for (m in innerMedia) emit(m.value, "Embed", absolute)
        }
        val frames = resp.document.select("iframe").mapNotNull {
            val raw = it.attr("src").ifBlank { it.attr("data-src") }.ifBlank { it.attr("vdo-src") }
            fixUrlNull(raw)
        }.distinct()
        for (frame in frames) {
            if (frame.contains(".mp4") || frame.contains(".m3u8")) {
                emit(frame, "Embed", data)
                continue
            }
            val inner = runCatching { app.get(frame, headers = uaHeaders, referer = data).text }.getOrNull()
            if (inner != null) {
                val innerText = inner.replace("\\/", "/")
                for (m in Regex("""https?://[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)""").findAll(innerText)) {
                    emit(m.value, "Embed", frame)
                }
                for (sub in Regex("""<iframe[^>]+(?:src|data-src)="([^"]+)""").findAll(innerText)) {
                    val subUrl = fixUrl(sub.groupValues[1])
                    if (loadExtractor(subUrl, frame, subtitleCallback, callback)) found = true
                }
            }
            if (loadExtractor(frame, "$mainUrl/", subtitleCallback, callback)) found = true
        }
        val subs = Regex("""https?://[^"'\s<>]+?\.(?:vtt|srt)(?:\?[^"'\s<>]*)""").findAll(text)
        for (m in subs) {
            if (Settings.subtitleAllowed("tr")) subtitleCallback.invoke(SubtitleFile("tr", m.value))
        }
        if (!found && frames.isEmpty() && embeds.isEmpty() && media.none()) {
            throw ErrorLoadingException("$name: oynatılabilir akış yok")
        }
        return found
    }




    private suspend fun loadAlpine(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        emit: suspend (String, String, String) -> Unit,
    ) {
        val page = runCatching { app.get(data, headers = uaHeaders).text }.getOrNull() ?: return
        val raw = Regex("""videoPlayerData\(JSON\.parse\('(.*?)'\)""", RegexOption.DOT_MATCHES_ALL)
            .find(page)?.groupValues?.get(1) ?: return
        val json = raw.replace("\\\\/", "/").replace("\\/", "/").replace("\\u0022", "\"")
            .replace("\\u0027", "'").replace("\\'", "'")
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return
        val keys = root.keys()
        while (keys.hasNext()) {
            val arr = root.optJSONArray(keys.next()) ?: continue
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val link = item.optString("link")
                val template = item.optString("template")
                if (link.isBlank() || template.isBlank()) continue
                val decoded = runCatching {
                    String(android.util.Base64.decode(template, android.util.Base64.DEFAULT), Charsets.UTF_8)
                }.getOrNull() ?: continue
                val src = Regex("""data-src="([^"]+)""").find(decoded)?.groupValues?.get(1)
                    ?.replace("{url}", link) ?: continue
                val absolute = if (src.startsWith("//")) "https:$src" else src
                val inner = runCatching {
                    app.get(absolute, headers = uaHeaders, referer = data).text
                        .replace("\\\\/", "/").replace("\\/", "/").replace("\\u0022", "\"")
                }.getOrNull() ?: continue
                val file = Regex("""["']file["']\s*:\s*["'](https?://[^"']+?\.m3u8[^"']*)""")
                    .find(inner)?.groupValues?.get(1) ?: continue
                emit(file, item.optString("quality").ifBlank { "HDF" }, data)
                Regex("""["']file["']\s*:\s*["'](https?://[^"']+?\.(?:vtt|srt)[^"']*)["'][^}]*?["']label["']\s*:\s*["']([^"']+)""")
                    .findAll(inner).forEach { track ->
                        if (Settings.subtitleAllowed(track.groupValues[2])) {
                            subtitleCallback.invoke(newSubtitleFile(track.groupValues[2], track.groupValues[1]))
                        }
                    }
            }
        }
    }


}
