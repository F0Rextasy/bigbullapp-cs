package com.hdfilmcehennemi

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** HDFilmCehennemi — hdfilmcehennemi.nl (movies only, self-hosted jwplayer embed) */
class HDFilmCehennemiProvider : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi.nl"
    private val uaHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9",
    )
    override var name = "HDFilmCehennemi"
    override var lang = "tr"
    override val hasMainPage = false
    override val supportedTypes = setOf(TvType.Movie)

    /** Site's live-search JSON: results are ready-made HTML fragments. */
    override suspend fun search(query: String): List<SearchResponse> {
        val json = app.get(
            "$mainUrl/search?q=${query.trim().replace(" ", "+")}",
            headers = uaHeaders + mapOf(
                "X-Requested-With" to "fetch",
                "Accept" to "application/json",
            ),
        ).text
        val arr = runCatching { JSONObject(json).optJSONArray("results") }.getOrNull()
            ?: throw ErrorLoadingException("$name: arama yanıtı çözümlenemedi. $REPORT_HINT")
        return (0 until arr.length()).mapNotNull { i ->
            val frag = Jsoup.parse(arr.optString(i))
            val a = frag.selectFirst("a.search-result[href]") ?: return@mapNotNull null
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val title = frag.selectFirst(".search-result-content .title, h4.title")?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: href.substringAfterLast('/').trim('/', '-')
            val poster = frag.selectFirst("img")?.attr("src")
            val type = frag.select(" .meta span, span.type").map { it.text().trim() }
                .firstOrNull { it.equals("Dizi", true) }
            val tvType = if (type != null) TvType.TvSeries else TvType.Movie
            newMovieSearchResponse(title, href, tvType) {
                this.posterUrl = fixUrlNull(poster)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url, headers = uaHeaders).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")

        val h1 = doc.selectFirst("h1")
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        // "Finding Harry: The Craft Behind the Magic izle <small>(2026)</small>"
        val rawTitle = h1.ownText().trim().removeSuffix(" izle").trim()
        val title = rawTitle.ifBlank { h1.text().trim().removeSuffix(" izle").trim() }
            .ifBlank { url.substringAfterLast('/').trim('/', '-') }
        val year = h1.selectFirst("small")?.text()?.trim('(', ')')?.trim()?.toIntOrNull()
            ?: Regex("""\((\d{4})\)""").find(h1.text())?.groupValues?.get(1)?.toIntOrNull()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = fixUrlNull(doc.selectFirst("div.poster img, figure img, img.lazyload")?.attr("src"))
            this.plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
            this.year = year
            this.tags = doc.select("a[href*='/kategori/'], a[href*='/category/']").map { it.text().trim() }
                .filter { it.isNotBlank() }.distinct()
            this.score = Score.from10(doc.selectFirst("span.imdb, div.rating, [class*=imdb]")?.text()?.trim())
            this.duration = doc.selectFirst("span.sure, div.duration")?.text()?.filter { it.isDigit() }?.toIntOrNull()
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

        val embeds = doc.select("div.video-container iframe[data-src], div.video-container iframe[src]")
            .mapNotNull { fixUrlNull(it.attr("data-src").ifBlank { it.attr("src") }) }
            .distinct()
        if (embeds.isEmpty()) throw ErrorLoadingException("$name: oynatıcı bulunamadı. $REPORT_HINT")

        var found = false
        for (embed in embeds) {
            runCatching {
                if (resolveEmbed(embed, linkCallback, subtitleFilter)) found = true
            }
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir akış yok. $REPORT_HINT")
        return found
    }

    /** Self-hosted embed (hdfilmcehennemi.mobi) with obfuscated h1lqa token -> jwplayer HLS. */
    private suspend fun resolveEmbed(
        embedUrl: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit,
    ): Boolean {
        val embed = app.get(embedUrl, referer = "$mainUrl/", headers = uaHeaders).text
        val stream = runCatching {
            val host = Regex("""https?://([^/]+)""").find(embedUrl)?.groupValues?.get(1) ?: return false
            val legacy = Regex("""c6rbn\("((?:[^"\\]|\\.)*)"\.split\("\^"\)\)""").find(embed)
            if (legacy != null) decodeC6rbn(legacy.groupValues[1].split("^"), host)
            else decodeDynamic(embed)
        }.getOrNull() ?: return false
        if (!stream.startsWith("http")) return false

        // subtitle tracks from jwplayer setup
        Regex("""tracks:\s*\[(.*?)\]\s*,\s*captions""", RegexOption.DOT_MATCHES_ALL).find(embed)?.let { t ->
            Regex("""\{"file":"((?:[^"\\]|\\.)*)","kind":"captions","label":"((?:[^"\\]|\\.)*)"""").findAll(t.groupValues[1])
                .forEach { s ->
                    val file = s.groupValues[1].replace("\\/", "/")
                    val label = s.groupValues[2].replace("\\/", "/")
                    subtitleCallback.invoke(SubtitleFile(label, file))
                }
        }

        callback.invoke(
            newExtractorLink(name, name, stream, ExtractorLinkType.M3U8) {
                this.referer = embedUrl
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    companion object {
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"

        /**
         * Port of the site's c6rbn() token decoder (verified against a live page).
         * Reverse the interleave, apply per-char operations encoded by the key string,
         * Fisher–Yates-style reshuffle, then XOR stream.
         */
        fun decodeC6rbn(parts: List<String>, hostname: String): String? {
            return runCatching {
                var arr = parts.toMutableList()
                val cc3 = arr.size - 2
                val pq2h = cc3 % 7
                val r8b = 8 + (cc3 % 5)
                val key = arr.removeAt(r8b)
                val seed = arr.removeAt(pq2h) + hostname
                var body = arr.joinToString("")
                if (key.length > 2048) body = body.reversed()
                var s172 = 0
                var i1q65 = 0
                seed.forEachIndexed { v3u, ch ->
                    val ro3 = ch.code
                    s172 = (s172 * 37 + ro3) % 241
                    i1q65 = (i1q65 + ((ro3 shl 1) xor v3u)) and 255
                }
                val g3l9o = (s172 * 3 + i1q65) % 256
                val ei422 = (i1q65 % 11) + 5
                var b8o = ((i1q65 * 251 + s172) % 65519) + 1
                for (kt31r in key.reversed()) {
                    when (kt31r) {
                        '7' -> body = String(java.util.Base64.getDecoder().decode(body), Charsets.ISO_8859_1)
                        '3' -> body = body.reversed()
                        else -> {
                            val shift = (26 - ((kt31r.code - 96) % 26)) % 26
                            body = body.map { c ->
                                if (c.isLetter()) {
                                    val base = if (c.isUpperCase()) 'A'.code else 'a'.code
                                    ((c.code - base + shift) % 26 + base).toChar()
                                } else c
                            }.joinToString("")
                        }
                    }
                }
                if (seed.length > 4096) {
                    body = String(java.util.Base64.getDecoder().decode(body), Charsets.ISO_8859_1)
                }
                val n = body.length
                val perm = IntArray(n)
                for (v3u in n - 1 downTo 1) {
                    b8o = (b8o * 97 + 41) % 65519
                    perm[v3u] = (b8o % (v3u + 1)).toInt()
                }
                val chars = body.toCharArray()
                for (v3u in 1 until n) {
                    val i56 = perm[v3u]
                    val tmp = chars[v3u]
                    chars[v3u] = chars[i56]
                    chars[i56] = tmp
                }
                body = String(chars)
                var pf4 = g3l9o
                val out = StringBuilder(n)
                for (ch in body) {
                    val ro3 = ch.code
                    pf4 = (pf4 * 5 + ei422) % 256
                    out.append(((ro3 xor pf4) and 0xFF).toChar())
                    pf4 = (pf4 + ro3) % 256
                }
                out.toString()
            }.getOrNull()
        }

        /** Newer embeds rename the function per request; constants are read from its body. */
        fun decodeDynamic(html: String): String? {
            val block = functionBlock(html) ?: return null
            val consts = Regex(
                """var\s+\w+\s*=\s*"((?:[^"\\]|\\.)*)";\s*var\s+\w+\s*=\s*"((?:[^"\\]|\\.)*)";"""
            ).find(block) ?: return null
            val call = Regex("""\w+\(\[((?:"(?:[^"\\]|\\.)*"\s*,?\s*)+)\]\)""").find(html)
                ?: return null
            val arr = runCatching {
                org.json.JSONArray("[" + call.groupValues[1] + "]")
            }.getOrNull() ?: return null
            val parts = (0 until arr.length()).map { arr.getString(it) }
            return decodeObfuscated(consts.groupValues[1], consts.groupValues[2], parts)
        }

        private fun functionBlock(html: String): String? {
            val join = html.indexOf(".join('')")
            if (join < 0) return null
            val start = html.lastIndexOf("function ", join)
            if (start < 0) return null
            val open = html.indexOf('{', start)
            var depth = 0
            for (i in open until html.length) {
                when (html[i]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return html.substring(start, i + 1)
                    }
                }
            }
            return null
        }

        fun decodeObfuscated(seed: String, ops: String, parts: List<String>): String? {
            return runCatching {
                var body = parts.joinToString("")
                var a = 0
                var b = 0
                seed.forEachIndexed { i, c ->
                    a = (a * 31 + c.code) % 251
                    b = (b xor (c.code + i)) and 255
                }
                val start = (a + b) % 256
                val step = (a % 13) + 3
                var prng = ((a * 256 + b) % 65521) + 1
                for (op in ops.reversed()) {
                    when (op) {
                        'b' -> body = String(
                            java.util.Base64.getDecoder().decode(body), Charsets.ISO_8859_1
                        )
                        'v' -> body = body.reversed()
                        else -> {
                            val shift = (26 - ((op.code - 64) % 26)) % 26
                            body = body.map { c ->
                                if (c.isLetter()) {
                                    val base = if (c.isUpperCase()) 'A'.code else 'a'.code
                                    ((c.code - base + shift) % 26 + base).toChar()
                                } else c
                            }.joinToString("")
                        }
                    }
                }
                val n = body.length
                val perm = IntArray(n)
                for (i in n - 1 downTo 1) {
                    prng = (prng * 75 + 74) % 65537
                    perm[i] = prng % (i + 1)
                }
                val chars = body.toCharArray()
                for (i in 1 until n) {
                    val j = perm[i]
                    val tmp = chars[i]
                    chars[i] = chars[j]
                    chars[j] = tmp
                }
                var v = start
                val out = StringBuilder(n)
                for (c in chars) {
                    v = (v + step) % 256
                    out.append(((c.code xor v) and 0xFF).toChar())
                    v = (v + c.code) % 256
                }
                out.toString()
            }.getOrNull()
        }
    }
}
