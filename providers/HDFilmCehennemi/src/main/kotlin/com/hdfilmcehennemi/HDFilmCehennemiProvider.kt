package com.hdfilmcehennemi

import com.bigbull.common.jsonLdDescription
import com.bigbull.common.browserHeaders
import com.bigbull.common.posterSrcLazy
import com.bigbull.common.unpackAllJsPacker
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup

/** HDFilmCehennemi — hdfilmcehennemi.nl (film ve dizi, self-hosted jwplayer embed) */

class HDFilmCehennemiProvider : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi.nl"
    private val uaHeaders get() = browserHeaders
    private val killer = CloudflareKiller()
    private val posterHdrs get() = uaHeaders + mapOf("Referer" to "$mainUrl/") + killer.getCookieHeaders(mainUrl).toMap()
    override var name = "HDFilmCehennemi"
    override var lang = "tr"
    override val hasMainPage = true
    override val mainPage get() = ROWS.mapNotNull { name ->
        if (!Settings.rowEnabled(name)) return@mapNotNull null
        when (name) {
            "Son Eklenen Filmler" -> MainPageData(name, "home")
            "Aile" -> MainPageData(name, "genres/aile-filmleri-izleyin-11")
            "Aksiyon" -> MainPageData(name, "genres/aksiyon-filmleri-izleyin-9")
            "Animasyon" -> MainPageData(name, "genres/animasyon-filmlerini-izleyin-5")
            "Belgesel" -> MainPageData(name, "genres/belgesel-filmlerini-izle-2")
            "Bilim Kurgu" -> MainPageData(name, "genres/bilim-kurgu-filmlerini-izleyin-9")
            "Dram" -> MainPageData(name, "genres/dram-filmlerini-izle-4")
            "Fantastik" -> MainPageData(name, "genres/fantastik-filmlerini-izleyin-9")
            "Gerilim" -> MainPageData(name, "genres/gerilim-filmlerini-izle-5")
            "Gizem" -> MainPageData(name, "genres/gizem-filmleri-izle-9")
            else -> null
        }
    }
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    /**
     * OkHttp TLS parmak izi HTML sayfalarında 403 alırken Android Chromium WebView (BoringSSL)
     * challenge olmadan doğrudan 200 alır ama `cf_clearance` çerezi oluşmadığı için
     * CloudflareKiller 60 sn bekleyip 403 döner. Bu yardımcı önce OkHttp dener, 403 gelirse
     * doğrudan WebView DOM'undan HTML'i (~1.5 sn) okur.
     */
    private fun isValidDoc(d: org.jsoup.nodes.Document): Boolean {
        val h1Text = d.selectFirst("h1")?.text().orEmpty()
        if (h1Text.contains("desteklenmiyor", ignoreCase = true)) return false
        return h1Text.isNotBlank() || d.select("button[data-video]").isNotEmpty()
    }

    private suspend fun fetchDoc(url: String): org.jsoup.nodes.Document? {
        runCatching {
            val res = app.get(url, headers = uaHeaders + mapOf("X-Requested-With" to "XMLHttpRequest"))
            if (res.code in 200..299) {
                val d = res.document
                if (isValidDoc(d)) return d
            }
        }
        webViewHtml(url)?.let { html ->
            val d = Jsoup.parse(html, url)
            if (isValidDoc(d)) return d
        }
        return runCatching {
            app.get(
                url,
                headers = uaHeaders + mapOf("X-Requested-With" to "XMLHttpRequest"),
                interceptor = killer,
            ).document.takeIf(::isValidDoc)
        }.getOrNull()
    }

    private suspend fun webViewHtml(url: String, timeoutMs: Long = 15_000L): String? {
        val ctx = runCatching { com.lagradost.cloudstream3.CloudStreamApp.context }.getOrNull() ?: return null
        val mainLooper = runCatching { android.os.Looper.getMainLooper() }.getOrNull() ?: return null
        val handler = android.os.Handler(mainLooper)
        return kotlin.coroutines.suspendCoroutine { cont ->
            val done = java.util.concurrent.atomic.AtomicBoolean(false)
            var webView: android.webkit.WebView? = null
            fun finish(html: String?) {
                if (!done.compareAndSet(false, true)) return
                handler.post {
                    runCatching {
                        webView?.stopLoading()
                        webView?.destroy()
                        webView = null
                    }
                }
                cont.resumeWith(Result.success(html))
            }
            val jsCheck = """
                (function(){
                    if (window.__bbHtml) return window.__bbHtml;
                    var h1 = document.querySelector('h1');
                    var txt = h1 ? (h1.textContent || '') : '';
                    if (txt.toLowerCase().indexOf('desteklenmiyor') !== -1) {
                        if (!window.__bbFetching) {
                            window.__bbFetching = true;
                            fetch(location.href, { headers: { 'X-Requested-With': 'XMLHttpRequest' } })
                                .then(function(r){ return r.text(); })
                                .then(function(t){ if (t && t.indexOf('desteklenmiyor') === -1) window.__bbHtml = t; })
                                .catch(function(){});
                        }
                        return '';
                    }
                    return (h1 || document.querySelector('button[data-video]')) ? document.documentElement.outerHTML : '';
                })()
            """.trimIndent()
            val pollTask = object : Runnable {
                override fun run() {
                    if (done.get()) return
                    val wv = webView ?: return
                    runCatching {
                        wv.evaluateJavascript(jsCheck) { raw ->
                            if (done.get()) return@evaluateJavascript
                            val decoded = if (!raw.isNullOrBlank() && raw != "null" && raw != "\"\"") {
                                runCatching { org.json.JSONTokener(raw).nextValue()?.toString() }.getOrNull()
                            } else null
                            if (!decoded.isNullOrBlank() && decoded.length > 500) {
                                finish(decoded)
                            } else {
                                handler.postDelayed(this, 500L)
                            }
                        }
                    }.onFailure { finish(null) }
                }
            }
            handler.postDelayed({ finish(null) }, timeoutMs)
            handler.post {
                runCatching {
                    val wv = android.webkit.WebView(ctx)
                    webView = wv
                    wv.settings.javaScriptEnabled = true
                    wv.settings.domStorageEnabled = true
                    wv.webViewClient = object : android.webkit.WebViewClient() {
                        override fun onPageFinished(view: android.webkit.WebView?, loadedUrl: String?) {
                            handler.post(pollTask)
                        }
                    }
                    wv.loadUrl(url, mapOf("Referer" to "$mainUrl/", "X-Requested-With" to "XMLHttpRequest"))
                    handler.postDelayed(pollTask, 1_000L)
                }.onFailure { finish(null) }
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val action = request.data.removePrefix("$mainUrl/").trim('/')
        val apiUrl = "$mainUrl/load/page/$page/$action/"
        val json = runCatching {
            app.get(
                apiUrl,
                headers = uaHeaders + mapOf(
                    "X-Requested-With" to "fetch",
                    "Accept" to "application/json",
                ),
            ).text
        }.getOrNull().orEmpty()
        val htmlFrag = runCatching { JSONObject(json).optString("html") }.getOrNull().orEmpty()
        val doc = if (htmlFrag.isNotBlank()) {
            Jsoup.parse(htmlFrag, mainUrl)
        } else {
            fetchDoc(if (action == "home") "$mainUrl/" else "$mainUrl/$action/")
                ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
        val items = doc.select("a.poster[href]").mapNotNull { card ->
            val href = fixUrlNull(card.attr("href")) ?: return@mapNotNull null
            val title = card.attr("title").removeSuffix(" izle").trim()
                .ifBlank { card.selectFirst("img")?.attr("alt")?.removeSuffix(" izle")?.trim().orEmpty() }
                .takeIf { it.isNotBlank() } ?: return@mapNotNull null
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = fixUrlNull(card.selectFirst("img")?.posterSrcLazy())
                this.posterHeaders = posterHdrs
            }
        }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    /** Site's live-search JSON: results are ready-made HTML fragments. */
    override suspend fun search(query: String): List<SearchResponse> {
        val json = app.get(
            "$mainUrl/search?q=${java.net.URLEncoder.encode(query.trim(), "UTF-8")}",
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
                this.posterHeaders = posterHdrs
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = fetchDoc(url)
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")

        val h1 = doc.selectFirst("h1")
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val rawTitle = h1.ownText().trim().removeSuffix(" izle").trim()
        val title = rawTitle.ifBlank { h1.text().trim().removeSuffix(" izle").trim() }
            .ifBlank { url.substringAfterLast('/').trim('/', '-') }
        val year = h1.selectFirst("small")?.text()?.trim('(', ')')?.trim()?.toIntOrNull()
            ?: Regex("""\((\d{4})\)""").find(h1.text())?.groupValues?.get(1)?.toIntOrNull()

        val posterUrl = fixUrlNull(doc.selectFirst("div.poster img, figure img, img.lazyload")?.posterSrcLazy())
        val plot = doc.jsonLdDescription()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val tags = doc.select("a[href*='/kategori/'], a[href*='/category/']").map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct()
        val score = Score.from10(doc.selectFirst("span.imdb, div.rating, [class*=imdb]")?.text()?.trim())
        val duration = doc.selectFirst("span.sure, div.duration")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        val episodes = doc.select("a[href*='/sezon-'][href*='/bolum-']").mapNotNull { anchor ->
            val href = fixUrlNull(anchor.attr("abs:href").ifBlank { anchor.attr("href") }) ?: return@mapNotNull null
            val seasonNum = Regex("""/sezon-(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
            val episodeNum = Regex("""/bolum-(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
            val epTitle = anchor.text().trim().ifBlank {
                if (seasonNum != null && episodeNum != null) "${seasonNum}. Sezon ${episodeNum}. Bölüm" else "Bölüm"
            }
            newEpisode(href) {
                this.name = epTitle
                this.season = seasonNum
                this.episode = episodeNum
            }
        }.distinctBy { it.data }.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
        if (episodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.posterHeaders = posterHdrs
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = score
            }
        }
        val videoIds = doc.select("button[data-video]").mapNotNull { it.attr("data-video").takeIf(String::isNotBlank) }.distinct()
        val movieData = if (videoIds.isNotEmpty()) "$url|${videoIds.joinToString(",")}" else url
        return newMovieLoadResponse(title, url, TvType.Movie, movieData) {
            this.posterUrl = posterUrl
            this.posterHeaders = posterHdrs
            this.plot = plot
            this.year = year
            this.tags = tags
            this.score = score
            this.duration = duration
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
        val pageUrl = data.substringBefore('|')
        val preIds = data.substringAfter('|', "").split(',').filter(String::isNotBlank)
        val doc = if (preIds.isEmpty()) {
            fetchDoc(pageUrl) ?: throw ErrorLoadingException("$name: kaynak bulunamadı. $REPORT_HINT")
        } else null
        val videoIds = preIds.ifEmpty {
            doc?.select("button[data-video]")?.mapNotNull { it.attr("data-video").takeIf(String::isNotBlank) }?.distinct().orEmpty()
        }

        // Iframe src doğrudan 404 döner: player, /video/<id>/ AJAX yanıtından tazelenir.
        val ajaxEmbeds = videoIds
            .mapNotNull { id ->
                val ajax = "$mainUrl/video/$id/"
                runCatching {
                    val body = app.get(
                        ajax,
                        referer = pageUrl,
                        headers = uaHeaders + mapOf(
                            "X-Requested-With" to "fetch",
                            "Accept" to "application/json",
                        ),
                    ).text
                    val html = Regex(""""html"\s*:\s*"((?:[^"\\]|\\.)*)"""")
                        .find(body)?.groupValues?.get(1)
                        ?.replace("\\/", "/")?.replace("\\\"", "\"")
                        ?: body
                    Regex("""<iframe[^>]*(?:data-src|src)="([^"]+)"""")
                        .find(html)?.groupValues?.get(1)
                        ?.let { fixUrlNull(it) }
                        ?.let { Embed(it, ajax) }
                }.getOrNull()
            }
        val plainEmbeds = doc?.select("div.video-container iframe[data-src], div.video-container iframe[src]")
            ?.mapNotNull { fixUrlNull(it.attr("data-src").ifBlank { it.attr("src") }) }
            ?.map { Embed(it, "$mainUrl/") }
            .orEmpty()
        val embeds = (ajaxEmbeds + plainEmbeds).distinctBy { it.url }
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

    private data class Embed(val url: String, val referer: String)

    /** Kendi barındırdığı embed; doğru Referer + Sec-Fetch-Dest olmadan 404 döner. */
    private suspend fun resolveEmbed(
        embedRef: Embed,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit,
    ): Boolean {
        val embedUrl = embedRef.url
        val embed = app.get(
            embedUrl,
            referer = embedRef.referer,
            headers = uaHeaders + mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Sec-Fetch-Dest" to "iframe",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to if (embedUrl.startsWith(mainUrl, true)) "same-origin" else "cross-site",
            ),
        ).text
        // Yeni oynatıcı: gerçek adres /kn/ POST yanıtında; eski c6rbn çözümü artık yalnızca örnek (decoy) video verir.
        val kn = knStream(embedUrl, embed)
        val stream: String
        if (kn != null) {
            stream = kn.first
            kn.second.forEach { (label, file) -> subtitleCallback.invoke(SubtitleFile(label, file)) }
        } else {
            val host = Regex("""https?://([^/]+)""").find(embedUrl)?.groupValues?.get(1) ?: return false
            val candidates = decodeEmbedCandidates(embed, host)
            stream = candidates.firstOrNull { url ->
                url.startsWith("http") && runCatching {
                    val res = app.get(url, referer = embedUrl, headers = uaHeaders)
                    res.code in 200..299 && res.text.trimStart().startsWith("#EXTM3U")
                }.getOrDefault(false)
            } ?: return false

            // subtitle tracks from jwplayer setup
            Regex("""tracks:\s*\[(.*?)\]\s*,\s*(?:captions|image)""", RegexOption.DOT_MATCHES_ALL).find(embed)?.let { t ->
                Regex("""\{"file":"((?:[^"\\]++|\\.)*+)","kind":"captions","label":"((?:[^"\\]++|\\.)*+)"""").findAll(t.groupValues[1])
                    .forEach { s ->
                        val file = s.groupValues[1].replace("\\/", "/")
                        val label = s.groupValues[2].replace("\\/", "/")
                        subtitleCallback.invoke(SubtitleFile(label, file))
                    }
            }
        }

        callback.invoke(
            newExtractorLink(name, name, stream, ExtractorLinkType.M3U8) {
                this.referer = embedUrl
                this.quality = Qualities.Unknown.value
                this.headers = uaHeaders
            }
        )
        return true
    }

    /**
     * Embed sayfası adresi `POST <embed>/kn/` ile verir: gövde `n`, `j`, `p=knF(n)` alır, yanıt
     * hex + XOR (`knC`) ile çözülür. Çözüm: ilk satır HLS adresi, kalanı altyazı JSON'u.
     */
    private suspend fun knStream(embedUrl: String, html: String): Pair<String, List<Pair<String, String>>>? {
        val origin = Regex("""^(https?://[^/]+)""").find(embedUrl)?.groupValues?.get(1) ?: return null
        val nj = Regex("""var n="([0-9a-f]+)",j="([0-9a-f]+)""").find(html) ?: return null
        val hash = Regex("""hash:"([0-9a-f]+)",s:"g""").find(html)?.groupValues?.get(1) ?: return null
        val path = Regex("""url:"(/video/embed/[^"]+/kn/)""").find(html)?.groupValues?.get(1) ?: return null
        val key = Regex("""var k="([0-9a-f]+)";function f\(""").find(unpackAllJsPacker(html))?.groupValues?.get(1)
            ?: return null
        val n = nj.groupValues[1]
        val p = knHash("$n|$key|[object Window]|[object HTMLDocument]|function")
        val body = runCatching {
            app.post(
                origin + path,
                data = mapOf("hash" to hash, "s" to "g", "n" to n, "j" to nj.groupValues[2], "p" to p, "d" to "1"),
                referer = embedUrl,
                headers = uaHeaders + mapOf(
                    "Origin" to origin,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "*/*",
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "same-origin",
                ),
            ).text
        }.getOrNull() ?: return null
        val hex = body.filter { it in '0'..'9' || it in 'a'..'f' }
        if (hex.length < 16) return null
        val plain = knDecode(hex, n, p)
        val url = plain.substringBefore('\n').trim()
        if (!url.startsWith("http")) return null
        val ok = runCatching {
            val res = app.get(url, referer = embedUrl, headers = uaHeaders)
            res.code in 200..299 && res.text.trimStart().startsWith("#EXTM3U")
        }.getOrDefault(false)
        if (!ok) return null
        val tracks = runCatching { org.json.JSONArray(plain.substringAfter('\n', "")) }.getOrNull()
        val subs = (0 until (tracks?.length() ?: 0)).mapNotNull { i ->
            val t = tracks?.optJSONObject(i) ?: return@mapNotNull null
            val file = t.optString("file").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            t.optString("label").ifBlank { "Altyazı" } to file
        }
        return url to subs
    }

    companion object {
        private fun knHash(s: String): String {
            var h = 2166136261L
            repeat(3) {
                for (ch in s) {
                    h = (h xor ch.code.toLong()) and 0xFFFFFFFFL
                    h = (h + (h shl 1) + (h shl 4) + (h shl 7) + (h shl 8) + (h shl 24)) and 0xFFFFFFFFL
                }
            }
            return h.toString(16).padStart(8, '0').takeLast(8)
        }

        fun knDecode(hex: String, n: String, p: String): String {
            val out = StringBuilder()
            var ks = ""
            var i = 0
            while (i + 1 < hex.length) {
                if (i % 8 == 0) ks = knHash("$n|${i / 8}|$p")
                val b = hex.substring(i, i + 2).toInt(16) xor ks.substring(i % 8, i % 8 + 2).toInt(16)
                out.append(b.toChar())
                i += 2
            }
            return String(out.toString().toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
        }

        /** Homepage row; maps to the live listing page. */
        val ROWS = listOf("Son Eklenen Filmler", "Aile", "Aksiyon", "Animasyon", "Belgesel",
            "Bilim Kurgu", "Dram", "Fantastik", "Gerilim", "Gizem")

        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"

        fun decodeEmbedCandidates(html: String, hostname: String): List<String> {
            val out = mutableListOf<String>()
            val work = unpackAllJsPacker(html)
            val seedHost = hostname + domSeedSuffix(work)
            val fileVar = Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*(\w+)""").find(work)?.groupValues?.get(1)
            val sourcesCallFn = Regex("""sources\s*:\s*\[\s*\{\s*file\s*:[\s\S]{0,400}?\breturn\s+([A-Za-z0-9_$]+)\s*\(""").find(work)?.groupValues?.get(1)
            val escRe = Regex("""\\(.)""")
            val constRe = Regex("""var\s+\w+\s*=\s*"((?:[^"\\]++|\\.)*+)";\s*var\s+\w+\s*=\s*"((?:[^"\\]++|\\.)*+)";""")
            fun decodeCall(fnName: String, parts: List<String>, varName: String) {
                val fnBody = functionBlock(work, fnName)
                val consts = fnBody?.let { constRe.find(it) }
                val decoded = if (consts != null) {
                    decodeObfuscated(consts.groupValues[1], consts.groupValues[2], parts)
                } else {
                    decodeC6rbnCall(fnBody, parts, seedHost)
                } ?: return
                if (decoded.startsWith("http", ignoreCase = true)) {
                    val priority = (fileVar != null && varName == fileVar) || (sourcesCallFn != null && fnName == sourcesCallFn)
                    if (priority) out.add(0, decoded) else out.add(decoded)
                }
            }
            val splitRe = Regex("""(?:(\w+)\s*=\s*)?([A-Za-z0-9_$]+)\(\s*"((?:[^"\\]++|\\.)*+)"\s*\.\s*split\(\s*["']([^"'])["']\s*\)\s*\)""")
            for (m in splitRe.findAll(work)) {
                val token = escRe.replace(m.groupValues[3], "$1")
                decodeCall(m.groupValues[2], token.split(m.groupValues[4]), m.groupValues[1])
            }
            val arrRe = Regex("""(?:(\w+)\s*=\s*)?([A-Za-z0-9_$]+)\(\[((?:"(?:[^"\\]++|\\.)*+"\s*,?\s*)+)\]\)""")
            for (m in arrRe.findAll(work)) {
                val arr = runCatching { org.json.JSONArray("[" + m.groupValues[3] + "]") }.getOrNull() ?: continue
                val parts = (0 until arr.length()).map { arr.getString(it) }
                decodeCall(m.groupValues[2], parts, m.groupValues[1])
            }
            return out.distinct()
        }

        private fun decodeC6rbnCall(fnBody: String?, parts: List<String>, seedHost: String): String? {
            val cM = fnBody?.let { Regex("""(\w+)\s*=\s*\(\1\s*\*\s*(\d+)\s*\+\s*\w+\)\s*%\s*(?!256\b)(\d+)""").find(it) }
            val pM = fnBody?.let { Regex("""(\w+)\s*=\s*\(\1\s*\*\s*(\d+)\s*\+\s*(\d+)\)\s*%\s*65519""").find(it) }
            val mM = fnBody?.let { Regex("""(\w+)\s*=\s*\(\1\s*\*\s*(\d+)\s*\+\s*\w+\)\s*%\s*256""").find(it) }
            val cMul = cM?.groupValues?.get(2)?.toIntOrNull() ?: 37
            val cMod = cM?.groupValues?.get(3)?.toIntOrNull() ?: 241
            val shl = fnBody?.let { Regex("""<<\s*(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 1
            val pMul = pM?.groupValues?.get(2)?.toIntOrNull() ?: 97
            val pAdd = pM?.groupValues?.get(3)?.toIntOrNull() ?: 41
            val mul = mM?.groupValues?.get(2)?.toIntOrNull() ?: 5
            val effectiveSeed = if (fnBody != null && "hostname" !in fnBody) "" else seedHost
            return decodeC6rbn(parts, effectiveSeed, shl, mul, cMul, cMod, pMul, pAdd)
                ?.takeIf { it.startsWith("http", ignoreCase = true) }
                ?: decodeC6rbn(parts, effectiveSeed)
        }

        /** Yeni oynatıcı varyantı: tohum = hostname + #id/[data-*] + createElement('Div').tagName. */
        private fun domSeedSuffix(html: String): String {
            val ref = Regex(
                """(?:getElementById\(\s*['"]([^'"]+)['"]\s*\)|querySelector(?:All)?\(\s*['"](?:#([A-Za-z0-9_-]+)|[^'"]+)['"]\s*\)(?:\[\s*0\s*\])?)\s*\.\s*(?:getAttribute\(\s*['"]([^'"]+)['"]\s*\)|attributes\[\s*['"]([^'"]+)['"]\s*\])""",
            ).find(html) ?: return ""
            val elId = ref.groupValues[1].ifEmpty { ref.groupValues[2] }
            val attr = ref.groupValues[3].ifEmpty { ref.groupValues[4] }
            val tagPattern = if (elId.isNotEmpty()) {
                """<[^>]*\bid=["']${Regex.escape(elId)}["'][^>]*>"""
            } else {
                """<\w+[^>]*\b${Regex.escape(attr)}\s*=\s*["'][^"']*["'][^>]*>"""
            }
            val tag = Regex(tagPattern).find(html) ?: return ""
            val value = Regex("""\b${Regex.escape(attr)}\s*=\s*["']([^"']*)""")
                .find(tag.value)?.groupValues?.get(1) ?: return ""
            val tagName = Regex("""createElement\(\s*['"]([^'"]+)['"]\s*\)\.tagName""")
                .find(html)?.groupValues?.get(1) ?: ""
            return value + tagName.uppercase()
        }

        /**
         * Port of the site's c6rbn() token decoder (verified against a live page).
         * Reverse the interleave, apply per-char operations encoded by the key string,
         * Fisher–Yates-style reshuffle, then XOR stream.
         */
        fun decodeC6rbn(
            parts: List<String>,
            hostname: String,
            shl: Int = 1,
            mul: Int = 5,
            cMul: Int = 37,
            cMod: Int = 241,
            pMul: Int = 97,
            pAdd: Int = 41,
        ): String? {
            return runCatching {
                val arr = parts.toMutableList()
                val cc3 = arr.size - 2
                if (cc3 < 8) return null
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
                    s172 = (s172 * cMul + ro3) % cMod
                    i1q65 = (i1q65 + ((ro3 shl shl) xor v3u)) and 255
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
                                if (c in 'a'..'z' || c in 'A'..'Z') {
                                    val base = if (c in 'A'..'Z') 'A'.code else 'a'.code
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
                    b8o = (b8o * pMul + pAdd) % 65519
                    perm[v3u] = b8o % (v3u + 1)
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
                    pf4 = (pf4 * mul + ei422) % 256
                    out.append(((ro3 xor pf4) and 0xFF).toChar())
                    pf4 = (pf4 + ro3) % 256
                }
                out.toString()
            }.getOrNull()
        }

        fun decodeDynamic(html: String): String? = decodeEmbedCandidates(html, "").firstOrNull()

        private fun functionBlock(html: String, fnName: String? = null): String? {
            val start = if (!fnName.isNullOrBlank()) {
                val esc = Regex.escape(fnName)
                Regex("""(?:function\s+$esc\s*\(|(?:var|let|const)\s+$esc\s*=\s*function\s*\()""")
                    .find(html)?.range?.first
            } else {
                val join = html.indexOf(".join('')")
                if (join < 0) null else html.lastIndexOf("function ", join).takeIf { it >= 0 }
            } ?: return null
            val open = html.indexOf('{', start)
            if (open < 0) return null
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
