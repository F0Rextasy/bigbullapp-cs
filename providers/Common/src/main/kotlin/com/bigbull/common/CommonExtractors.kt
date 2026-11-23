package com.bigbull.common

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

private val VIXOLITY_HEX_RE = Regex(""""([0-9a-fA-F]{80,})"""")
private val VIXOLITY_CALL_RE = Regex("""\}\)\('([0-9a-fA-F]{16,})',\s*(\d+)\)""")
private val VIXOLITY_URL_RE = Regex(""""(?:kaynak|file|src)"\s*:\s*"([^"]+)"""")
private val DEFAULT_VIXOLITY_SHIFTS = listOf(3, 1, 2, 4, 5, 6, 7)
private val PACKER_ESC_RE = Regex("""\\(.)""")
private val PACKER_WORD_RE = Regex("""\b[0-9a-zA-Z]+\b""")
private val VIDEOPARK_ID_RE = Regex("""VIDEO_ID\s*=\s*"([^"]+)"""")
private val VIDEOPARK_WORKER_RE = Regex("""WORKER_URL\s*=\s*"([^"]+)"""")
private val PLAYERX_FILE_RE = Regex(""""file"\s*:\s*"([^"]+)"""")
private val VIDMOLY_M3U8_RE = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
private val DAILYMOTION_ID_RE = Regex("""/(?:embed/)?video/([\w-]+)""")
private val DIRECT_M3U8_RE = Regex("""https?://[^"'\s<>]+\.m3u8(?:\?[^"'\s<>]*)?""")
private val DIRECT_MP4_RE = Regex("""https?://[^"'\s<>]+\.mp4(?:\?[^"'\s<>]*)?""")
private val PACKER_RE = Regex(
    """eval\(function\(p,a,c,k,e,[dr]\)\{[\s\S]*?\}\('((?:[^'\\]++|\\.)*+)',\s*(\d+),\s*(\d+),\s*'((?:[^'\\]++|\\.)*+)'\.split\(['"]\|['"]\)""",
    RegexOption.DOT_MATCHES_ALL,
)
private val BEPLAYER_RE = Regex("""bePlayer\s*\(\s*['"]([^'"]+)['"]\s*,\s*['"](\{[^}]+\})['"]\s*\)""")
private val CRYPTOJS_CT_RE = Regex("""\{[^{}]*"ct"\s*:\s*"([^"]+)"[^{}]*"iv"\s*:\s*"([0-9a-fA-F]+)"[^{}]*"s"\s*:\s*"([0-9a-fA-F]+)"[^{}]*\}""")
private val RAPIDVID_AV_RE = Regex("""av\s*\(\s*['"]([A-Za-z0-9+/=]{16,})['"]\s*\)""")
private val ATOB_RE = Regex("""atob\s*\(\s*['"]([A-Za-z0-9+/=]{16,})['"]\s*\)""")
private val IFRAME_SRC_RE = Regex("""<iframe[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
private val SIBNET_SRC_RE = Regex("""player\.src\(\[\s*\{\s*src\s*:\s*["']([^"']+\.mp4[^"']*)["']""")
private val OKRU_HLS_RE = Regex(""""(?:hlsManifestUrl|ondemandHls)"\s*:\s*"([^"]+)"""")
private val VK_HLS_RE = Regex(""""(?:hls|url\d+)"\s*:\s*"([^"]+\.(?:m3u8|mp4)[^"]*)"""")

private val EMBED_UA: Map<String, String> = mapOf(
    "User-Agent" to (
        browserHeaders["user-agent"]
            ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    ),
)

private val FIREFOX_UA: Map<String, String> = mapOf(
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0",
    "Accept-Language" to "tr-TR,tr;q=0.9",
)

internal fun decodeBase64Bytes(input: String): ByteArray? {
    val clean = input.trim().replace('-', '+').replace('_', '/').let { s ->
        val rem = s.length % 4
        if (rem == 0) s else s + "=".repeat(4 - rem)
    }
    val androidBytes: ByteArray? = runCatching {
        android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
    }.getOrNull()
    if (androidBytes != null && (androidBytes.isNotEmpty() || clean.isEmpty())) {
        return androidBytes
    }
    return runCatching {
        java.util.Base64.getDecoder().decode(clean)
    }.getOrNull()
}

private fun unescapePackerText(raw: String): String =
    PACKER_ESC_RE.replace(raw) { mr ->
        when (val ch = mr.groupValues[1]) {
            "n" -> "\n"
            "r" -> "\r"
            "t" -> "\t"
            else -> ch
        }
    }

private fun decodePackerMatch(m: MatchResult): String? {
    val payload = unescapePackerText(m.groupValues[1])
    val base = m.groupValues[2].toIntOrNull() ?: return null
    val keys = unescapePackerText(m.groupValues[4]).split("|")
    val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    if (base !in 2..alphabet.length) return null

    fun encodeToken(value: Int): String {
        if (value == 0) return "0"
        var n = value
        val sb = StringBuilder()
        while (n > 0) {
            sb.append(alphabet[n % base])
            n /= base
        }
        return sb.reverse().toString()
    }

    val table = HashMap<String, String>(keys.size * 2)
    for (i in keys.indices) {
        val tok = encodeToken(i)
        table[tok] = keys[i].ifEmpty { tok }
    }
    return PACKER_WORD_RE.replace(payload) { mr -> table[mr.value] ?: mr.value }
}

/** Dean Edwards `eval(function(p,a,c,k,e,d)...)` paketlenmiş JS kodunu açar. */
fun unpackJsPacker(script: String): String? = PACKER_RE.find(script)?.let(::decodePackerMatch)

/** Sayfadaki tüm Dean Edwards packed bloklarını açıp orijinal HTML'in sonuna ekler. */
fun unpackAllJsPacker(html: String): String {
    val matches = PACKER_RE.findAll(html).toList()
    if (matches.isEmpty()) return html
    val out = StringBuilder(html)
    for (m in matches) {
        decodePackerMatch(m)?.let { out.append('\n').append(it) }
    }
    return out.toString()
}

data class FirePlayerStream(
    val url: String,
    val label: String,
    val isM3u8: Boolean,
)

/** FirePlayer (`do=getVideo`) JSON yanıtından akış URL'sini çıkarır. */
fun parseFirePlayerJson(jsonText: String): FirePlayerStream? {
    val obj = runCatching { JSONObject(jsonText.trim()) }.getOrNull() ?: return null
    val rawUrl = obj.optString("securedLink").ifBlank { obj.optString("videoSource") }.replace("\\/", "/").trim()
    if (!rawUrl.startsWith("http")) return null
    val isHls = rawUrl.contains(".m3u8", ignoreCase = true) ||
        obj.optString("hls").equals("1", ignoreCase = true) ||
        obj.optBoolean("hls", false)
    return FirePlayerStream(url = rawUrl, label = "FirePlayer", isM3u8 = isHls)
}

/** `bePlayer('sifre', '{"ct":...,"iv":...,"s":...}')` veya CryptoJS AES-CBC zarfını çözer. */
fun decryptCryptoJsJson(text: String, passphrases: List<String> = emptyList()): String? {
    for (m in BEPLAYER_RE.findAll(text)) {
        val pw = m.groupValues[1]
        val ctMatch = CRYPTOJS_CT_RE.find(m.groupValues[2]) ?: continue
        val ctBytes = decodeBase64Bytes(ctMatch.groupValues[1]) ?: continue
        val dec = runCatching {
            evpMd5AesCbcDecrypt(ctBytes, pw, ctMatch.groupValues[3], ctMatch.groupValues[2])
        }.getOrNull()
        if (!dec.isNullOrBlank() && (dec.contains("http") || dec.contains(".m3u8") || dec.contains(".mp4"))) {
            return dec
        }
    }
    for (ctMatch in CRYPTOJS_CT_RE.findAll(text)) {
        val ctBytes = decodeBase64Bytes(ctMatch.groupValues[1]) ?: continue
        for (pw in passphrases) {
            if (pw.isBlank()) continue
            val dec = runCatching {
                evpMd5AesCbcDecrypt(ctBytes, pw, ctMatch.groupValues[3], ctMatch.groupValues[2])
            }.getOrNull()
            if (!dec.isNullOrBlank() && (dec.contains("http") || dec.contains(".m3u8") || dec.contains(".mp4"))) {
                return dec
            }
        }
    }
    return null
}

/** rapidvid `av('...')` gömülü kaynağını (Android bağımlılığı olmadan JVM/JUnit'te de çalışarak) çözer. */
fun decodeRapidVidAv(html: String): String? {
    for (m in RAPIDVID_AV_RE.findAll(html)) {
        val decoded = rapidVidAvDecode(m.groupValues[1])?.replace("\\/", "/") ?: continue
        if (decoded.contains("http")) return decoded
    }
    return null
}

private fun decodeVixolityHex(hex: String, shifts: List<Int>): String? {
    if (hex.length % 2 != 0) return null
    val step1 = runCatching { hex.hexToBytes().toString(Charsets.ISO_8859_1) }.getOrNull() ?: return null
    val step2Bytes = decodeBase64Bytes(step1.reversed()) ?: return null
    val step2 = runCatching { String(step2Bytes, Charsets.ISO_8859_1) }.getOrNull() ?: return null
    for (shift in shifts) {
        val shifted = buildString(step2.length) {
            for (ch in step2) append((ch.code - shift).toChar())
        }
        val decBytes = decodeBase64Bytes(shifted) ?: continue
        val dec = runCatching { String(decBytes, Charsets.UTF_8) }.getOrNull() ?: continue
        val km = VIXOLITY_URL_RE.find(dec) ?: continue
        val url = km.groupValues[1].replace("\\/", "/")
        if (url.startsWith("http") || url.contains(".m3u8")) return url
    }
    return null
}

/** Vixolity gömülü hex -> ters base64 -> Caesar kaydırma -> base64 akışını çözer. */
fun decodeVixolityStream(html: String): String? {
    for (m in VIXOLITY_CALL_RE.findAll(html)) {
        val explicit = m.groupValues[2].toIntOrNull()
        val shifts = if (explicit != null) {
            listOf(explicit) + DEFAULT_VIXOLITY_SHIFTS.filter { it != explicit }
        } else {
            DEFAULT_VIXOLITY_SHIFTS
        }
        decodeVixolityHex(m.groupValues[1], shifts)?.let { return it }
    }
    for (m in VIXOLITY_HEX_RE.findAll(html)) {
        decodeVixolityHex(m.groupValues[1], DEFAULT_VIXOLITY_SHIFTS)?.let { return it }
    }
    return null
}

data class VideoParkStream(
    val url: String,
    val referer: String,
    val label: String,
    val isM3u8: Boolean,
    val quality: Int,
)

/** VideoPark sayfalarından VIP MP4 veya Apollo HLS akışını çıkarır. */
fun extractVideoParkStream(embedUrl: String, html: String): VideoParkStream? {
    val vid = VIDEOPARK_ID_RE.find(html)?.groupValues?.get(1).orEmpty()
    if (vid.isEmpty()) return null
    val worker = VIDEOPARK_WORKER_RE.find(html)?.groupValues?.get(1).orEmpty()
    if (worker.isNotEmpty()) {
        val base = worker.trimEnd('/')
        return VideoParkStream(
            url = "$base/v/$vid?q=1080",
            referer = "$base/",
            label = "VIP",
            isM3u8 = false,
            quality = 1080,
        )
    }
    if (embedUrl.contains("/apollo/") || embedUrl.contains("/go/")) {
        return VideoParkStream(
            url = "https://videopark.top/apollo/get_video.php?id=$vid",
            referer = "https://videopark.top/",
            label = "Apollo",
            isM3u8 = true,
            quality = 0,
        )
    }
    return null
}

/** Vidmoly HTML içindeki `.m3u8` kaynak adreslerini çıkarır. */
fun extractVidmolyStreams(html: String): List<String> =
    VIDMOLY_M3U8_RE.findAll(html)
        .map { it.groupValues[1].replace("\\/", "/") }
        .filter { it.startsWith("http") }
        .distinct()
        .toList()

internal fun uriOrigin(url: String): String? = runCatching {
    val u = URI(url.trim())
    val scheme = u.scheme?.takeIf { it.isNotBlank() } ?: return null
    val host = u.host?.takeIf { it.isNotBlank() } ?: return null
    if (u.port > 0) "$scheme://$host:${u.port}" else "$scheme://$host"
}.getOrNull()

/** Sayfa HTML içindeki doğrudan `.m3u8` bağlantılarının listesini (varsa pichive imzalı) döner. */
fun extractDirectM3u8Streams(html: String): List<String> {
    val clean = html.replace("\\/", "/")
    val rawUrls = DIRECT_M3U8_RE.findAll(clean).map { it.value }.distinct().toList()
    if (rawUrls.isEmpty()) return emptyList()
    val nonce = if (rawUrls.any {
            (it.contains("/nm.php") || it.contains("/nsub.php")) && !it.contains("&p=") && !it.contains("?p=")
        }
    ) pichiveGateNonce(clean) else null
    return rawUrls.map { pichiveSign(clean, it, nonce) }.distinct()
}

internal fun resolveCandidateUrl(raw: String, baseUrl: String, out: MutableSet<String>) {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("javascript:") || trimmed.startsWith("data:")) return
    when {
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> out.add(trimmed)
        trimmed.startsWith("//") -> out.add("https:$trimmed")
        trimmed.startsWith("/") -> uriOrigin(baseUrl)?.let { out.add("$it$trimmed") }
    }
}

private fun decodePotentialEmbedText(raw: String, baseUrl: String, out: MutableSet<String>) {
    val bytes = decodeBase64Bytes(raw) ?: return
    val decoded = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()?.replace("\\/", "/")?.trim().orEmpty()
    if (decoded.isEmpty()) return
    if (decoded.startsWith("http://") || decoded.startsWith("https://") || decoded.startsWith("//")) {
        resolveCandidateUrl(decoded, baseUrl, out)
        return
    }
    for (m in IFRAME_SRC_RE.findAll(decoded)) {
        resolveCandidateUrl(m.groupValues[1], baseUrl, out)
    }
}

/** DOM `iframe`, `video source`, `data-*`, `<iframe srcdoc>` ve Base64/`atob` kaynak adreslerini çıkarır. */
fun extractPageEmbedCandidates(html: String, baseUrl: String): List<String> {
    val out = LinkedHashSet<String>()
    val doc = Jsoup.parse(html, baseUrl)
    val attrs = listOf(
        "data-src", "data-lazy-src", "data-litespeed-src",
        "data-embed", "data-iframe", "data-player", "data-frame", "data-url", "src",
    )
    val selector = "iframe, video source, [data-embed], [data-iframe], [data-player], [data-frame], [data-url]"
    for (el in doc.select(selector)) {
        for (attr in attrs) {
            val v = el.attr(attr).trim()
            if (v.isEmpty()) continue
            if (v.startsWith("http") || v.startsWith("//") || v.startsWith("/")) {
                resolveCandidateUrl(v, baseUrl, out)
            } else if (v.length >= 16 && Regex("""^[A-Za-z0-9+/=_-]+$""").matches(v)) {
                decodePotentialEmbedText(v, baseUrl, out)
            }
        }
        val srcdoc = el.attr("srcdoc").trim()
        if (srcdoc.isNotEmpty()) {
            for (m in IFRAME_SRC_RE.findAll(srcdoc)) {
                resolveCandidateUrl(m.groupValues[1], baseUrl, out)
            }
        }
    }
    for (m in ATOB_RE.findAll(html)) {
        decodePotentialEmbedText(m.groupValues[1], baseUrl, out)
    }
    return out.filter {
        val low = it.lowercase()
        !low.endsWith(".jpg") && !low.endsWith(".png") && !low.endsWith(".webp") && !low.contains("googletagmanager")
    }
}

suspend fun loadCommonEmbed(
    sourceName: String,
    mainUrl: String,
    url: String,
    referer: String? = null,
    noRefererHosts: List<String> = listOf("video.twimg.com", "twimg.com"),
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean = loadCommonEmbedWithHints(
    sourceName, mainUrl, url, referer, noRefererHosts, emptyList(), emptyList(), subtitleCallback, callback,
)

/** Host-duyarlı başlık ipuçlarıyla genişletilmiş ortak embed çözücüsü (`H6` + `H7`). */
suspend fun loadCommonEmbedWithHints(
    sourceName: String,
    mainUrl: String,
    url: String,
    referer: String? = null,
    noRefererHosts: List<String> = listOf("video.twimg.com", "twimg.com"),
    originHosts: List<String> = emptyList(),
    firefoxUaHosts: List<String> = emptyList(),
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean {
    val u = if (url.startsWith("//")) "https:$url" else url
    val ref = referer ?: "${mainUrl.trimEnd('/')}/"
    val useFirefox = firefoxUaHosts.any { u.contains(it, ignoreCase = true) }
    val reqHeaders = if (useFirefox) FIREFOX_UA else EMBED_UA
    val originOf: (String) -> String = { target ->
        uriOrigin(target) ?: mainUrl.trimEnd('/')
    }

    if (u.contains("vixolity.com", ignoreCase = true)) {
        val html = runCatching { app.get(u, referer = ref, headers = reqHeaders).text }.getOrNull().orEmpty()
        val streamUrl = decodeVixolityStream(html)
        if (!streamUrl.isNullOrBlank()) {
            val origin = originOf(u)
            callback(
                newExtractorLink(sourceName, "$sourceName [Vixolity]", streamUrl, ExtractorLinkType.M3U8) {
                    this.referer = "$origin/"
                    this.quality = Qualities.Unknown.value
                    this.headers = reqHeaders + mapOf("Origin" to origin)
                },
            )
            return true
        }
    }

    if (u.contains("videopark.top", ignoreCase = true)) {
        val html = runCatching { app.get(u, referer = ref, headers = reqHeaders).text }.getOrNull().orEmpty()
        val vp = extractVideoParkStream(u, html)
        if (vp != null) {
            val linkType = if (vp.isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback(
                newExtractorLink(sourceName, "$sourceName [${vp.label}]", vp.url, linkType) {
                    this.referer = vp.referer
                    this.quality = if (vp.quality > 0) vp.quality else Qualities.Unknown.value
                    this.headers = reqHeaders
                },
            )
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
                    headers = reqHeaders + mapOf("X-Requested-With" to "XMLHttpRequest"),
                    data = mapOf("r" to ref, "d" to "playerx.info"),
                ).text
            }.getOrNull().orEmpty()
            var found = false
            for (m in PLAYERX_FILE_RE.findAll(json)) {
                val f = m.groupValues[1].replace("\\/", "/")
                if (f.startsWith("http")) {
                    val linkType = if (f.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback(
                        newExtractorLink(sourceName, "$sourceName [PlayerX]", f, linkType) {
                            this.referer = "https://playerx.info/"
                            this.quality = Qualities.Unknown.value
                            this.headers = reqHeaders
                        },
                    )
                    found = true
                }
            }
            if (found) return true
        }
    }

    if (u.contains("/player/index.php?data=", ignoreCase = true)) {
        val hash = u.substringAfter("data=").substringBefore("&").trim()
        val origin = originOf(u)
        if (hash.isNotEmpty()) {
            val jsonText = runCatching {
                app.post(
                    "$origin/player/index.php?data=$hash&do=getVideo",
                    referer = u,
                    headers = reqHeaders + mapOf("X-Requested-With" to "XMLHttpRequest", "Origin" to origin),
                    data = mapOf("hash" to hash, "r" to ref),
                ).text
            }.getOrNull().orEmpty()
            val fp = parseFirePlayerJson(jsonText)
            if (fp != null) {
                val linkType = if (fp.isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback(
                    newExtractorLink(sourceName, "$sourceName [${fp.label}]", fp.url, linkType) {
                        this.referer = "$origin/"
                        this.quality = Qualities.Unknown.value
                        this.headers = reqHeaders
                    },
                )
                return true
            }
        }
    }

    if (u.contains("ok.ru", ignoreCase = true) || u.contains("odnoklassniki.ru", ignoreCase = true)) {
        val okHtml = runCatching { app.get(u, referer = ref, headers = reqHeaders).text }.getOrNull().orEmpty()
            .replace("&quot;", "\"")
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
        val hls = OKRU_HLS_RE.find(okHtml)?.groupValues?.get(1).orEmpty()
        if (hls.startsWith("http")) {
            callback(
                newExtractorLink(sourceName, "$sourceName [Ok.ru]", hls, ExtractorLinkType.M3U8) {
                    this.referer = "https://ok.ru/"
                    this.quality = Qualities.Unknown.value
                    this.headers = reqHeaders
                },
            )
            return true
        }
    }

    var emitted = false
    val trackedCallback: (ExtractorLink) -> Unit = {
        emitted = true
        callback(it)
    }
    if (loadExtractor(u, ref, subtitleCallback, trackedCallback) && emitted) return true

    if (u.contains("vidmoly.", ignoreCase = true)) {
        val vmTarget = u.replace(Regex("""vidmoly\.(?:to|me|biz)"""), "vidmoly.net")
        val vmHtml = runCatching {
            app.get(vmTarget, referer = ref, headers = reqHeaders + mapOf("Sec-Fetch-Dest" to "iframe")).text
        }.getOrNull().orEmpty()
        val vmStreams = extractVidmolyStreams(vmHtml)
        for (m3u8 in vmStreams) {
            callback(
                newExtractorLink(sourceName, "$sourceName [Vidmoly]", m3u8, ExtractorLinkType.M3U8) {
                    this.referer = "https://vidmoly.net/"
                    this.quality = Qualities.Unknown.value
                    this.headers = reqHeaders
                },
            )
        }
        if (vmStreams.isNotEmpty()) return true
    }

    if (u.contains("sibnet.ru", ignoreCase = true)) {
        val sibHtml = runCatching { app.get(u, referer = ref, headers = reqHeaders).text }.getOrNull().orEmpty()
        val rawPath = SIBNET_SRC_RE.find(sibHtml)?.groupValues?.get(1)?.trim().orEmpty()
        if (rawPath.isNotEmpty()) {
            val mp4 = if (rawPath.startsWith("http")) rawPath else "https://video.sibnet.ru/${rawPath.trimStart('/')}"
            callback(
                newExtractorLink(sourceName, "$sourceName [Sibnet]", mp4, ExtractorLinkType.VIDEO) {
                    this.referer = u
                    this.quality = Qualities.Unknown.value
                    this.headers = reqHeaders
                },
            )
            return true
        }
    }

    if (u.contains("vk.com/video", ignoreCase = true) || u.contains("vkvideo.ru", ignoreCase = true)) {
        val vkHtml = runCatching { app.get(u, referer = ref, headers = reqHeaders).text }.getOrNull().orEmpty()
            .replace("\\/", "/")
        val vkUrl = VK_HLS_RE.find(vkHtml)?.groupValues?.get(1).orEmpty()
        if (vkUrl.startsWith("http")) {
            val isHls = vkUrl.contains(".m3u8", ignoreCase = true)
            callback(
                newExtractorLink(sourceName, "$sourceName [VK]", vkUrl, if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.referer = "https://vk.com/"
                    this.quality = Qualities.Unknown.value
                    this.headers = reqHeaders
                },
            )
            return true
        }
    }

    if (u.contains("dailymotion.com", ignoreCase = true)) {
        val dmId = DAILYMOTION_ID_RE.find(u)?.groupValues?.get(1).orEmpty()
        if (dmId.isNotEmpty()) {
            val dmResp = runCatching {
                app.get(
                    "https://www.dailymotion.com/player/metadata/video/$dmId",
                    referer = "https://www.dailymotion.com/",
                    headers = reqHeaders,
                )
            }.getOrNull()
            val dmUrl = dmResp?.let {
                runCatching {
                    JSONObject(it.text)
                        .optJSONObject("qualities")
                        ?.optJSONArray("auto")
                        ?.optJSONObject(0)
                        ?.optString("url")
                }.getOrNull()
            }.orEmpty()
            if (dmUrl.startsWith("http") && dmResp != null) {
                val ck = dmResp.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                val dmHdr = reqHeaders + mapOf("Origin" to "https://www.dailymotion.com") +
                    if (ck.isNotBlank()) mapOf("Cookie" to ck) else emptyMap()
                callback(
                    newExtractorLink(sourceName, "$sourceName [Dailymotion]", dmUrl, ExtractorLinkType.M3U8) {
                        this.referer = "https://www.dailymotion.com/"
                        this.quality = Qualities.Unknown.value
                        this.headers = dmHdr
                    },
                )
                return true
            }
        }
    }

    val rawHtml = runCatching { app.get(u, referer = ref, headers = reqHeaders).text }.getOrNull().orEmpty()
    val unpacked = runCatching { unpackAllJsPacker(rawHtml) }.getOrNull().orEmpty()
    val cryptoDec = decryptCryptoJsJson(rawHtml).orEmpty()
    val rapidDec = decodeRapidVidAv(rawHtml).orEmpty()
    val searchPool = buildString {
        append(rawHtml)
        if (unpacked.isNotEmpty()) append('\n').append(unpacked)
        if (cryptoDec.isNotEmpty()) append('\n').append(cryptoDec)
        if (rapidDec.isNotEmpty()) append('\n').append(rapidDec)
    }
    val directM3u8 = extractDirectM3u8Streams(searchPool)
    if (directM3u8.isNotEmpty()) {
        for (m3u8 in directM3u8) {
            val noRef = noRefererHosts.any { m3u8.contains(it, ignoreCase = true) }
            val useOrig = originHosts.any { m3u8.contains(it, ignoreCase = true) }
            val orig = originOf(u)
            callback(
                newExtractorLink(sourceName, sourceName, m3u8, ExtractorLinkType.M3U8) {
                    this.referer = if (noRef) "" else if (useOrig) "$orig/" else u
                    this.quality = Qualities.Unknown.value
                    this.headers = if (useOrig) reqHeaders + mapOf("Origin" to orig) else reqHeaders
                },
            )
        }
        return true
    }
    val cleanPool = searchPool.replace("\\/", "/")
    val directMp4 = DIRECT_MP4_RE.findAll(cleanPool).map { it.value }.distinct().toList()
    for (mp4 in directMp4) {
        val noRef = noRefererHosts.any { mp4.contains(it, ignoreCase = true) }
        callback(
            newExtractorLink(sourceName, sourceName, mp4, ExtractorLinkType.VIDEO) {
                this.referer = if (noRef) "" else u
                this.quality = Qualities.Unknown.value
                this.headers = reqHeaders
            },
        )
    }
    return directMp4.isNotEmpty()
}

private val OKRU_ID_RE = Regex("""videoembed/(\d+)""")
private const val OKRU_UA = "Mozilla/5.0 (Linux; Android 13)"
private const val DYG_API = "https://dygvideo.dygdigital.com/api/video_info"
const val DYG_DEFAULT_SECRET = "NtvApiSecret2014*"

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
    val videos = meta.optJSONArray("videos") ?: return false
    var found = false
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
                headers?.let { this.headers = it }
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
