package com.yerlidiziler

import com.bigbull.common.browserHeaders
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.parser.Parser

/** YerliDiziler — yerlidiziler.com.tr (WordPress REST API + Vidmoly HLS). */
class YerliDizilerProvider : MainAPI() {
    override var mainUrl = "https://yerlidiziler.com.tr"
    override var name = "YerliDiziler"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries)

    private val uaHeaders get() = browserHeaders

    override val mainPage get() = ROWS.mapNotNull { row ->
        if (!Settings.rowEnabled(row)) return@mapNotNull null
        when (row) {
            "Yerli Diziler" -> MainPageData(row, "$mainUrl/wp-json/wp/v2/categories?per_page=100&_fields=id,count,link,name,slug")
            "Son Bölümler" -> MainPageData(row, "$mainUrl/wp-json/wp/v2/posts?per_page=30&_fields=id,link,title,categories,yoast_head_json.og_image")
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.name == "Yerli Diziler") {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
            val cats = fetchCategories()
            val posters = fetchCategoryPosters()
            val items = cats.filter { it.count > 0 && it.slug != "uncategorized" }.map { cat ->
                newTvSeriesSearchResponse(cat.name, cat.link, TvType.TvSeries) {
                    this.posterUrl = posters[cat.id]
                }
            }
            return newHomePageResponse(request.name, items, hasNext = false)
        }
        val raw = runCatching {
            app.get("${request.data}&page=$page", headers = uaHeaders).text
        }.getOrNull() ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val arr = runCatching { JSONArray(raw) }.getOrNull()
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val items = (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val link = obj.optString("link").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val title = decodeHtml(obj.optJSONObject("title")?.optString("rendered").orEmpty())
            if (title.isBlank()) return@mapNotNull null
            newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                this.posterUrl = firstOgImage(obj)
            }
        }
        return newHomePageResponse(request.name, items, hasNext = items.size >= 30)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val needle = fold(query)
        if (needle.isBlank()) return emptyList()
        val cats = fetchCategories()
        val matched = cats.filter {
            it.count > 0 && it.slug != "uncategorized" &&
                (fold(it.name).contains(needle) || fold(it.slug.replace('-', ' ')).contains(needle))
        }
        if (matched.isNotEmpty()) {
            val posters = fetchCategoryPosters()
            return matched.map { cat ->
                newTvSeriesSearchResponse(cat.name, cat.link, TvType.TvSeries) {
                    this.posterUrl = posters[cat.id]
                }
            }
        }
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val raw = runCatching {
            app.get(
                "$mainUrl/wp-json/wp/v2/posts?search=$encoded&per_page=20&_fields=id,link,title,categories,yoast_head_json.og_image",
                headers = uaHeaders,
            ).text
        }.getOrNull() ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val link = obj.optString("link").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val title = decodeHtml(obj.optJSONObject("title")?.optString("rendered").orEmpty())
            if (title.isBlank()) return@mapNotNull null
            newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                this.posterUrl = firstOgImage(obj)
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val cleanUrl = fixUrl(url).trimEnd('/') + "/"
        val slug = cleanUrl.removePrefix(mainUrl).trim('/').substringBefore('/')
        val cats = fetchCategories()
        var cat = cats.firstOrNull { it.link.trimEnd('/') == cleanUrl.trimEnd('/') || it.slug == slug }

        if (cat == null) {
            val postRaw = runCatching {
                app.get(
                    "$mainUrl/wp-json/wp/v2/posts?slug=${URLEncoder.encode(slug, "UTF-8")}&_fields=id,link,title,categories,yoast_head_json.og_image",
                    headers = uaHeaders,
                ).text
            }.getOrNull()
            val postArr = postRaw?.let { runCatching { JSONArray(it) }.getOrNull() }
            val postObj = postArr?.optJSONObject(0)
            val catId = postObj?.optJSONArray("categories")?.optInt(0, 0) ?: 0
            if (catId > 0) {
                cat = cats.firstOrNull { it.id == catId }
            }
            if (cat == null && postObj != null) {
                val epTitle = decodeHtml(postObj.optJSONObject("title")?.optString("rendered").orEmpty())
                    .ifBlank { slug.replace('-', ' ') }
                val epNum = EPISODE_NUM.find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                return newTvSeriesLoadResponse(
                    epTitle.replace(EPISODE_SUFFIX, "").trim().ifBlank { epTitle },
                    cleanUrl,
                    TvType.TvSeries,
                    listOf(
                        newEpisode(cleanUrl) {
                            this.name = epTitle
                            this.season = 1
                            this.episode = epNum
                            this.posterUrl = firstOgImage(postObj)
                        },
                    ),
                ) {
                    this.posterUrl = firstOgImage(postObj)
                }
            }
        }

        val targetCat = cat ?: throw ErrorLoadingException("$name: dizi bulunamadı. $REPORT_HINT")
        val postsRaw = runCatching {
            app.get(
                "$mainUrl/wp-json/wp/v2/posts?categories=${targetCat.id}&per_page=100&_fields=id,link,title,yoast_head_json.og_image",
                headers = uaHeaders,
            ).text
        }.getOrNull() ?: throw ErrorLoadingException("$name: bölümler yüklenemedi. $REPORT_HINT")
        val arr = runCatching { JSONArray(postsRaw) }.getOrNull()
            ?: throw ErrorLoadingException("$name: bölüm listesi okunamadı. $REPORT_HINT")

        var seriesPoster: String? = null
        val episodes = ArrayList<Episode>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val link = obj.optString("link").takeIf { it.startsWith("http") } ?: continue
            val epTitle = decodeHtml(obj.optJSONObject("title")?.optString("rendered").orEmpty())
            val epNum = EPISODE_NUM.find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                ?: EPISODE_URL.find(link)?.groupValues?.get(1)?.toIntOrNull()
            val img = firstOgImage(obj)
            if (seriesPoster == null && img != null) seriesPoster = img
            episodes += newEpisode(link) {
                this.name = epTitle.ifBlank { epNum?.let { "$it. Bölüm" } ?: "Bölüm" }
                this.season = 1
                this.episode = epNum
                this.posterUrl = img
            }
        }
        val sorted = episodes.distinctBy { it.data }.sortedBy { it.episode ?: 0 }
        if (sorted.isEmpty()) throw ErrorLoadingException("$name: bölüm bulunamadı. $REPORT_HINT")
        return newTvSeriesLoadResponse(targetCat.name, targetCat.link, TvType.TvSeries, sorted) {
            this.posterUrl = seriesPoster
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
            ?: throw ErrorLoadingException("$name: bölüm sayfası açılamadı. $REPORT_HINT")
        val embeds = doc.select("iframe").mapNotNull { iframe ->
            val raw = iframe.attr("src").ifBlank { iframe.attr("data-src") }.trim()
            fixUrlNull(raw)
        }.distinct()
        if (embeds.isEmpty()) throw ErrorLoadingException("$name: oynatıcı bulunamadı. $REPORT_HINT")

        var found = false
        val linkCallback: (ExtractorLink) -> Unit = { link ->
            if (Settings.qualityAllowed(link.quality)) {
                callback(link)
                found = true
            }
        }
        val subCallback: (SubtitleFile) -> Unit = { sub ->
            if (Settings.subtitleAllowed(sub.lang)) subtitleCallback(sub)
        }

        for (embed in embeds) {
            if (embed.contains("vidmoly", ignoreCase = true)) {
                if (extractVidmoly(embed, pageUrl, linkCallback)) continue
            }
            if (runCatching { loadExtractor(embed, pageUrl, subCallback, linkCallback) }.getOrDefault(false)) {
                found = true
            }
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir kaynak yok. $REPORT_HINT")
        return true
    }

    private suspend fun extractVidmoly(
        embedUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = runCatching { app.get(embedUrl, referer = referer, headers = uaHeaders).text }.getOrNull()
            ?: return false
        var emitted = false
        for (match in VIDMOLY_FILE.findAll(html)) {
            val stream = match.groupValues[1].replace("\\/", "/")
            if (!stream.startsWith("http")) continue
            val label = match.groupValues[2]
            val isHls = stream.contains(".m3u8", ignoreCase = true)
            callback(newExtractorLink(name, "$name Vidmoly", stream, if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.referer = embedUrl
                this.headers = uaHeaders
                this.quality = if (isHls) Qualities.Unknown.value else getQualityFromName(label)
            })
            emitted = true
        }
        return emitted
    }

    private data class Cat(val id: Int, val count: Int, val link: String, val name: String, val slug: String)

    private suspend fun fetchCategories(): List<Cat> {
        val raw = runCatching {
            app.get("$mainUrl/wp-json/wp/v2/categories?per_page=100&_fields=id,count,link,name,slug", headers = uaHeaders).text
        }.getOrNull() ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optInt("id", 0)
            val link = obj.optString("link").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val title = decodeHtml(obj.optString("name"))
            if (id <= 0 || title.isBlank()) return@mapNotNull null
            Cat(id, obj.optInt("count", 0), link, title, obj.optString("slug"))
        }
    }

    private suspend fun fetchCategoryPosters(): Map<Int, String> {
        val raw = runCatching {
            app.get("$mainUrl/wp-json/wp/v2/posts?per_page=100&_fields=categories,yoast_head_json.og_image", headers = uaHeaders).text
        }.getOrNull() ?: return emptyMap()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyMap()
        val map = HashMap<Int, String>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val img = firstOgImage(obj) ?: continue
            val cats = obj.optJSONArray("categories") ?: continue
            for (j in 0 until cats.length()) {
                val cid = cats.optInt(j, 0)
                if (cid > 0) map.putIfAbsent(cid, img)
            }
        }
        return map
    }

    private fun firstOgImage(obj: JSONObject): String? =
        obj.optJSONObject("yoast_head_json")
            ?.optJSONArray("og_image")
            ?.optJSONObject(0)
            ?.optString("url")
            ?.takeIf { it.startsWith("http") }

    private fun decodeHtml(value: String): String =
        Parser.unescapeEntities(value, false).trim()

    private fun fold(value: String): String = value.lowercase()
        .replace("\u0307", "")
        .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
        .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')
        .replace(NON_ALNUM, " ")
        .trim()

    companion object {
        val ROWS = listOf("Yerli Diziler", "Son Bölümler")
        private val NON_ALNUM = Regex("""[^a-z0-9]+""")
        private val EPISODE_NUM = Regex("""(\d+)\.\s*Bölüm""", RegexOption.IGNORE_CASE)
        private val EPISODE_URL = Regex("""-(\d+)-bolum""", RegexOption.IGNORE_CASE)
        private val EPISODE_SUFFIX = Regex("""\s+\d+\.\s*Bölüm.*$""", RegexOption.IGNORE_CASE)
        private val VIDMOLY_FILE = Regex("""file\s*:\s*["']([^"']+)["'](?:\s*,\s*label\s*:\s*["']([^"']*)["'])?""")
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}
