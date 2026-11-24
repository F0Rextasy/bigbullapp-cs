package com.bigbull.common

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

private val OKRU_ID_RE = Regex("""videoembed/(\d+)""")
private const val OKRU_UA = "Mozilla/5.0 (Linux; Android 13)"
private const val DYG_API = "https://dygvideo.dygdigital.com/api/video_info"
const val DYG_DEFAULT_SECRET = "NtvApiSecret2014*"
private val PLAYERX_FILE_RE = Regex(""""file"\s*:\s*"([^"]+)"""")

suspend fun fetchOkRuMetadata(
    embed: String,
    userAgent: String = OKRU_UA,
    data: Map<String, String> = emptyMap(),
): JSONObject? {
    val id = OKRU_ID_RE.find(embed)?.groupValues?.get(1) ?: return null
    val body = runCatching {
        app.post(
            "https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$id",
            data = data,
            headers = mapOf("user-agent" to userAgent, "Referer" to "https://ok.ru/"),
            referer = "https://ok.ru/videoembed/$id",
        ).text
    }.getOrNull() ?: return null
    return runCatching { JSONObject(body) }.getOrNull()
}

/** ok.ru metadata'sındaki HLS master'ı döndürür; failover host varsa onu kullanır. */
fun okRuHlsUrl(meta: JSONObject): String? {
    val raw = meta.optString("hlsManifestUrl").trim().takeIf { it.startsWith("http") } ?: return null
    val host = meta.optJSONArray("failoverHosts")?.optString(0)?.trim().orEmpty()
    if (host.isEmpty()) return raw
    return runCatching {
        val uri = java.net.URI(raw)
        java.net.URI(uri.scheme, uri.userInfo, host, uri.port, uri.path, uri.query, uri.fragment).toString()
    }.getOrDefault(raw)
}
suspend fun emitOkRuVideos(
    embed: String,
    seen: MutableSet<String>? = null,
    labelPrefix: String = "OkRu",
    userAgent: String = OKRU_UA,
    headers: Map<String, String>? = null,
    qualityAllowed: (Int) -> Boolean = { true },
    callback: (ExtractorLink) -> Unit,
): Boolean {
    val meta = fetchOkRuMetadata(embed, userAgent = userAgent) ?: return false
    var found = false
    val ua = headers?.entries?.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: userAgent
    val linkHeaders = (headers ?: emptyMap()).filterKeys { !it.equals("User-Agent", true) } + ("User-Agent" to ua)
    val hls = okRuHlsUrl(meta)
    if (hls != null && (seen == null || seen.add(hls))) {
        callback(
            newExtractorLink(labelPrefix.ifBlank { "OkRu" }, "$labelPrefix HLS", hls, ExtractorLinkType.M3U8) {
                this.referer = "https://ok.ru/"
                this.quality = Qualities.Unknown.value
                this.headers = linkHeaders
            },
        )
        found = true
    }
    val videos = meta.optJSONArray("videos") ?: return found
    for (i in 0 until videos.length()) {
        val item = videos.optJSONObject(i) ?: continue
        val stream = item.optString("url").takeIf { it.startsWith("http") } ?: continue
        if (seen != null && !seen.add(stream)) continue
        val label = item.optString("name").ifBlank { "OkRu" }
        val q = when (label.lowercase()) {
            "mobile", "lowest" -> 240
            "low" -> 360
            "sd" -> 480
            "hd" -> 720
            "full" -> 1080
            else -> getQualityFromName(label.uppercase())
        }
        if (!qualityAllowed(q)) continue
        val linkName = if (labelPrefix.isBlank()) label else "$labelPrefix $label"
        callback(
            newExtractorLink(labelPrefix.ifBlank { "OkRu" }, linkName, stream, ExtractorLinkType.VIDEO) {
                this.referer = "https://ok.ru/"
                this.quality = q
                this.headers = linkHeaders
            },
        )
        found = true
    }
    return found
}

suspend fun fetchDygVideoInfo(
    publisherId: Int,
    referenceId: String,
    mainUrl: String,
    headers: Map<String, String> = browserHeaders,
    secret: String = DYG_DEFAULT_SECRET,
): JSONObject? {
    val dygUrl = "$DYG_API?akamai=true&PublisherId=$publisherId&ReferenceId=$referenceId&SecretKey=$secret"
    return runCatching {
        JSONObject(app.get(dygUrl, headers = headers, referer = "${mainUrl.trimEnd('/')}/").text)
            .optJSONObject("data")
    }.getOrNull()
}

suspend fun emitMasterOrHlsVariants(
    sourceName: String,
    label: String,
    masterUrl: String,
    referer: String,
    headers: Map<String, String> = browserHeaders,
    settings: BaseSettings? = null,
    fallbackMinIfFiltered: Boolean = false,
    callback: (ExtractorLink) -> Unit,
): Int {
    val playlist = runCatching {
        app.get(masterUrl, headers = headers, referer = referer).text
    }.getOrNull().orEmpty()
    val variants = parseHlsVariants(masterUrl, playlist)
    var emitted = 0
    suspend fun emitOne(url: String, quality: Int) {
        if (settings != null && !settings.qualityAllowed(quality)) return
        val linkLabel = if (quality > 0) "$label ${quality}p" else label
        callback(newExtractorLink(sourceName, linkLabel, url, ExtractorLinkType.M3U8) {
            this.referer = referer
            this.quality = quality
            this.headers = headers
        })
        emitted++
    }
    if (variants.isEmpty()) {
        emitOne(masterUrl, Qualities.Unknown.value)
    } else {
        val allowed = if (settings != null) variants.filter { settings.qualityAllowed(it.first) } else variants
        val toEmit = if (allowed.isNotEmpty() || !fallbackMinIfFiltered) allowed else listOf(variants.minBy { it.first })
        toEmit.forEach { (q, u) -> emitOne(u, q) }
    }
    return emitted
}

/** Deokwave / DiziLife / Sezonlukdizi / TurkceDizi ortak embed çözücüsü (Vixolity, VideoPark, PlayerX, loadExtractor, direkt m3u8). */
suspend fun extractCommonSiteEmbed(
    sourceName: String,
    mainUrl: String,
    url: String,
    referer: String?,
    settings: BaseSettings,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean {
    val u = if (url.startsWith("//")) "https:$url" else url
    val ref = referer ?: "${mainUrl.trimEnd('/')}/"
    val ua = mapOf("User-Agent" to browserHeaders.getValue("user-agent"))
    if (u.contains("vixolity.com", ignoreCase = true)) {
        val html = runCatching { app.get(u, referer = ref, headers = ua).text }.getOrNull().orEmpty()
        decodeVixolityStream(html)?.let { streamUrl ->
            val origin = uriOrigin(u) ?: "https://pl.vixolity.com"
            callback(newExtractorLink(sourceName, "$sourceName [Vixolity]", streamUrl, ExtractorLinkType.M3U8) {
                this.referer = "$origin/"
                this.quality = Qualities.Unknown.value
                this.headers = ua + mapOf("Origin" to origin)
            })
            return true
        }
    }
    if (u.contains("videopark.top", ignoreCase = true)) {
        val html = runCatching { app.get(u, referer = ref, headers = ua).text }.getOrNull().orEmpty()
        extractVideoParkStream(u, html)?.let { vp ->
            callback(newExtractorLink(sourceName, "$sourceName [${vp.label}]", vp.url, if (vp.isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.referer = vp.referer
                this.quality = vp.quality
                this.headers = ua
            })
            return true
        }
    }
    if (u.contains("playerx.info", ignoreCase = true)) {
        val hash = u.substringAfterLast("/").substringBefore("?").trim()
        if (hash.isNotEmpty()) {
            val json = runCatching {
                app.post(
                    "https://playerx.info/api/source/$hash",
                    referer = u,
                    headers = ua + mapOf("X-Requested-With" to "XMLHttpRequest"),
                    data = mapOf("r" to ref, "d" to "playerx.info"),
                ).text
            }.getOrNull().orEmpty()
            var found = false
            for (m in PLAYERX_FILE_RE.findAll(json)) {
                val f = m.groupValues[1].replace("\\/", "/")
                if (f.startsWith("http")) {
                    callback(newExtractorLink(sourceName, "$sourceName [PlayerX]", f, if (f.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                        this.referer = "https://playerx.info/"
                        this.quality = Qualities.Unknown.value
                        this.headers = ua
                    })
                    found = true
                }
            }
            if (found) return true
        }
    }
    if (loadExtractor(u, ref, settings.filterSubtitles(subtitleCallback), settings.filterLinks(callback))) return true
    val html = runCatching { app.get(u, referer = ref, headers = ua).text }.getOrNull().orEmpty().replace("\\/", "/")
    val direct = extractDirectM3u8Streams(html)
    direct.forEach { m3u8 ->
        callback(newExtractorLink(sourceName, sourceName, m3u8, ExtractorLinkType.M3U8) {
            this.referer = u
            this.quality = Qualities.Unknown.value
            this.headers = ua
        })
    }
    return direct.isNotEmpty()
}
