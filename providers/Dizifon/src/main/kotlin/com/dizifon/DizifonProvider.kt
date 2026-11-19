package com.dizifon

import com.bigbull.common.browserHeaders
import com.bigbull.common.decodeVixolityStream
import com.bigbull.common.posterSrcLazy
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

class DizifonProvider : MainAPI() {
    private val uaHeaders get() = browserHeaders

    override var mainUrl = "https://www.dizifon.cc"
    override var name = "Dizifon"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val hasMainPage = true
    override val mainPage get() = ROWS.mapNotNull { rowName ->
        if (!Settings.rowEnabled(rowName)) return@mapNotNull null
        MainPageData(rowName, when (rowName) {
            "Diziler" -> "$mainUrl/tum-diziler/"
            "Dram" -> "$mainUrl/diziler/dram/"
            "Fantastik" -> "$mainUrl/diziler/fantastik/"
            "Gençlik" -> "$mainUrl/diziler/genclik/"
            "Komedi" -> "$mainUrl/diziler/komedi/"
            "Okul" -> "$mainUrl/diziler/okul/"
            "Romantik" -> "$mainUrl/diziler/romantik/"
            "Tarihi" -> "$mainUrl/diziler/tarihi/"
            "Yaşam" -> "$mainUrl/diziler/yasam/"
            "Anime" -> "$mainUrl/diziler/anime/"
            "Çin Dizileri" -> "$mainUrl/diziler/cin-dizileri/"
            "Japon Dizileri" -> "$mainUrl/diziler/japon-dizileri/"
            "Kore Dizileri" -> "$mainUrl/diziler/kore-dizileri/"
            "Tayland Dizileri" -> "$mainUrl/diziler/tayland-dizileri/"
            "Aksiyon" -> "$mainUrl/diziler/aksiyon/"
            "Arkadaşlık" -> "$mainUrl/diziler/arkadaslik/"
            "Doğaüstü" -> "$mainUrl/diziler/dogaustu/"
            "Gerilim" -> "$mainUrl/diziler/gerilim/"
            "Gizem" -> "$mainUrl/diziler/gizem/"
            "Hukuk" -> "$mainUrl/diziler/hukuk/"
            "İş" -> "$mainUrl/diziler/is/"
            "Korku" -> "$mainUrl/diziler/korku/"
            "Medikal" -> "$mainUrl/diziler/medikal/"
            "Müzik" -> "$mainUrl/diziler/muzik/"
            "Spor" -> "$mainUrl/diziler/spor/"
            "Suç" -> "$mainUrl/diziler/suc/"
            else -> mainUrl
        })
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

            if (request.name in listOf("Diziler", "Dram", "Fantastik", "Gençlik", "Komedi", "Okul", "Romantik", "Tarihi", "Yaşam", "Anime", "Çin Dizileri", "Japon Dizileri", "Kore Dizileri", "Tayland Dizileri", "Aksiyon", "Arkadaşlık", "Doğaüstü", "Gerilim", "Gizem", "Hukuk", "İş", "Korku", "Medikal", "Müzik", "Spor", "Suç")) {
                if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
                val doc = app.get(request.data, headers = uaHeaders).document
                return newHomePageResponse(request.name, cards(doc), hasNext = false)
            }
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
    }

    companion object {
        val ROWS = listOf("Diziler", "Dram", "Fantastik", "Gençlik", "Komedi", "Okul", "Romantik", "Tarihi", "Yaşam", "Anime", "Çin Dizileri", "Japon Dizileri", "Kore Dizileri", "Tayland Dizileri", "Aksiyon", "Arkadaşlık", "Doğaüstü", "Gerilim", "Gizem", "Hukuk", "İş", "Korku", "Medikal", "Müzik", "Spor", "Suç")
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = asciiFold(query).trim()
        val all = cards(app.get("$mainUrl/?s=" + java.net.URLEncoder.encode(q, "UTF-8"), headers = uaHeaders).document)
        if (q.isBlank()) return all
        return all.filter { asciiFold(it.name).contains(q, ignoreCase = true) }
    }

    private fun cards(doc: Document): List<SearchResponse> = doc.select("a[href]").mapNotNull { card ->
        val href = fixUrlNull(card.attr("href")) ?: return@mapNotNull null
        val path = card.attr("href").substringAfter(mainUrl, card.attr("href")).substringBefore('?')
        if (!path.startsWith("/dizi/")) return@mapNotNull null
        if (path.contains("login") || path.contains("uye-ol") || path.contains("/giris")) return@mapNotNull null
        val img = card.selectFirst("img") ?: return@mapNotNull null
        val title = card.attr("title").trim()
            .ifBlank { card.text().trim() }
            .ifBlank { img.attr("alt").trim() }
            .takeIf { it.isNotBlank() } ?: return@mapNotNull null
        newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl = fixUrlNull(img.posterSrcLazy())
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
            val rawHref = anchor.attr("href").substringBefore('#').trim()
            val href = fixUrlNull(rawHref) ?: return@mapNotNull null
            val path = href.substringAfter(mainUrl).substringBefore('?')
            if (path.contains("/admin/") || path.contains("/wp-admin/")) return@mapNotNull null
            if (basePath.isNotBlank() && !path.startsWith(basePath) &&
                !path.contains(slug) && !path.contains(slugBase)) return@mapNotNull null
            if (!Regex("""(?:bolum|episode|sezon|season)""", RegexOption.IGNORE_CASE).containsMatchIn(path)) return@mapNotNull null
            val seasonMatch = Regex("""(?:sezon|season)[-_/]?(\d+).*?(?:bolum|episode)[-_/]?([0-9]*)""")
                .find(path)
            val season = seasonMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
            val number = seasonMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
                ?: Regex("""(?:bolum|episode)[-_/]?(\d+)""").find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""(\d+)[-_]bolum""").find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(href) {
                this.name = anchor.text().trim().ifBlank { "Bölüm $number" }
                this.season = season
                this.episode = number
            }
        }.distinctBy { it.data }.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
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
                decodeVixolityStream(inner)?.let { emit(it, "Vixolity", "https://pl.vixolity.com/") }
                val innerText = inner.replace("\\/", "/")
                for (m in Regex("""https?://[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?""").findAll(innerText)) {
                    emit(m.value, "Embed", frame)
                }
                for (sub in Regex("""<iframe[^>]+(?:src|data-src)="([^"]+)""").findAll(innerText)) {
                    val subUrl = fixUrl(sub.groupValues[1])
                    if (loadExtractor(subUrl, frame, subtitleCallback, callback)) found = true
                }
            }
            if (frame.contains("ok.ru") || frame.contains("odnoklassniki")) { if (loadOkRu(frame, callback)) found = true } else if (loadExtractor(frame, "$mainUrl/", subtitleCallback, callback)) found = true
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

    private suspend fun loadOkRu(embed: String, callback: (ExtractorLink) -> Unit): Boolean {
        val id = Regex("""videoembed/(\d+)""").find(embed)?.groupValues?.get(1) ?: return false
        val body = runCatching {
            app.post(
                "https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$id",
                data = emptyMap(),
                headers = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 13)", "Referer" to "https://ok.ru/"),
            ).text
        }.getOrNull() ?: return false
        val videos = runCatching { JSONObject(body).optJSONArray("videos") }.getOrNull() ?: return false
        var found = false
        for (i in 0 until videos.length()) {
            val item = videos.optJSONObject(i) ?: continue
            val stream = item.optString("url").takeIf { it.startsWith("http") } ?: continue
            val label = item.optString("name").ifBlank { "OkRu" }
            callback.invoke(
                newExtractorLink(name, "OkRu $label", stream, ExtractorLinkType.VIDEO) {
                    this.referer = "https://ok.ru/"
                    this.quality = when (label.lowercase()) {
                        "mobile", "lowest" -> 240
                        "low" -> 360
                        "sd" -> 480
                        "hd" -> 720
                        "full" -> 1080
                        else -> getQualityFromName(label.uppercase())
                    }
                }
            )
            found = true
        }
        return found
    }
}
