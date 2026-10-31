package com.inatbox

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
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val REPORT_HINT = "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"

private const val TOKEN_PREFIX = "inatbox:v1"
private const val MODE_ITEM = "item"
private const val MODE_LIST = "list"
private const val MODE_ROW = "row"
private const val MODE_EMBED = "embed"

// Keys of the Inat Android client: the signing key is XOR-derived from the encoded
// key material and the certificate hex digest, one byte per position.
private const val CERTIFICATE_SHA256 = "7cc771973665660f0653fce2e9490e48b92be8432a88d74f91703a716f6b4692"
private const val ENCODED_KEY_HEX = "4c50054a43216168797045480a046734372e298080c489a2f8a4b8b5819a9f93efb3bbf0f2525a4ca6"
private const val RAW_RESPONSE_KEY = "a4osa8x1yl4w3vrk"

private const val BROWSER_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
private const val LIST_TTL = 5 * 60_000L
// The 4K catalogues are larger than 10 MB; the phone client's 2 MB cap cannot hold them.
private const val RESPONSE_LIMIT = 32 * 1024 * 1024
// The 4K lists hold 17k rows, so keep the cache small instead of counting 128 large lists.
private const val CACHE_LIMIT = 16

private val SIGNING_KEY: ByteArray = run {
    val encoded = hexToBytes(ENCODED_KEY_HEX)
    ByteArray(40) { index ->
        ((encoded[index].toInt() and 0xFF) xor
            (encoded[40].toInt() and 0xFF) xor
            ((index * 7) and 0xFF) xor
            0xA5 xor
            CERTIFICATE_SHA256[index].code).toByte()
    }
}

private val API_HEADERS = mapOf(
    "user-agent" to "speedrestapi",
    "referer" to "https://speedrestapi.com/",
    "x-requested-with" to "com.bp.box",
    "cache-control" to "no-cache",
)

private val HEX_64 = Regex("^[a-fA-F0-9]{64}$")
private val BASE64_BODY = Regex("^[A-Za-z0-9+/]+={0,2}$")
private val SERVER_TIME = Regex("^\\d{1,12}$")
private val HEADER_NAME = Regex("^[!#$%&'*+.^_`|~0-9a-z-]+$")
private val HEADER_VALUE_BAD = Regex("[^\\t\\x20-\\x7e\\x80-\\xff]")
private val EPISODE_NAME = Regex("""(?:bölüm|bolum|episode)""", RegexOption.IGNORE_CASE)
private val SEASON_NAME = Regex("""(?:sezon|season)""", RegexOption.IGNORE_CASE)
private val EPISODE_NUMBER = Regex("""(\d+)\s*[.\-]?\s*(?:bölüm|bolum|episode)""", RegexOption.IGNORE_CASE)
private val MEDIA_URL = Regex("""\.(?:m3u8|mp4|webm|ts|mkv)(?:[?#].*)?$""", RegexOption.IGNORE_CASE)
private val FORBIDDEN_HEADERS = setOf(
    "host", "connection", "content-length", "transfer-encoding", "proxy-authorization",
    "proxy-connection", "x-ts", "x-nc", "x-sg", "x-st",
)
private val FALLBACK_URL_PATTERNS = listOf(
    Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""", RegexOption.IGNORE_CASE),
    Regex("""https?://[^\s"'<>]+\.(?:mp4|webm|ts|mkv)[^\s"'<>]*""", RegexOption.IGNORE_CASE),
    Regex("""file:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
    Regex("""source:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
    Regex("""src:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
)
private val RESOLVER_URL_PATTERNS = listOf(
    Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""", RegexOption.IGNORE_CASE),
    Regex("""https?://[^\s"'<>]+\.(?:mp4|webm|ts|mkv)[^\s"'<>]*""", RegexOption.IGNORE_CASE),
    Regex("""["'](https?:\\/\\/[^\s"'<>]+\.m3u8[^\s"'<>]*)["']""", RegexOption.IGNORE_CASE),
)

private fun hexToBytes(value: String): ByteArray = ByteArray(value.length / 2) {
    value.substring(it * 2, it * 2 + 2).toInt(16).toByte()
}

private fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    val result = StringBuilder(size * 2)
    for (byte in this) {
        result.append(digits[(byte.toInt() shr 4) and 0xF])
        result.append(digits[byte.toInt() and 0xF])
    }
    return result.toString()
}

private fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).toHex()

private fun hmacSha256(key: ByteArray, value: String): ByteArray =
    Mac.getInstance("HmacSHA256")
        .apply { init(SecretKeySpec(key, "HmacSHA256")) }
        .doFinal(value.toByteArray(Charsets.UTF_8))

private fun base64Decode(value: String): ByteArray {
    val padded = value + "=".repeat((4 - value.length % 4) % 4)
    return try {
        Base64.decode(padded, Base64.DEFAULT)
    } catch (e: IllegalArgumentException) {
        throw InatDecodeException()
    }
}

private fun base64UrlEncode(value: String): String =
    Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

private fun base64UrlDecode(value: String): String = try {
    String(Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
} catch (e: IllegalArgumentException) {
    ""
}

private fun text(value: Any?): String {
    val raw = when (value) {
        null, JSONObject.NULL -> ""
        is String -> value
        else -> value.toString()
    }.trim()
    return if (raw.equals("null", ignoreCase = true)) "" else raw
}

private fun JSONObject.firstOf(vararg keys: String): Any? {
    for (key in keys) {
        if (!has(key)) continue
        val value = opt(key)
        if (value == null || value == JSONObject.NULL) continue
        return value
    }
    return null
}

private fun parseValue(value: String): Any? = try {
    JSONTokener(value).nextValue()
} catch (e: Exception) {
    null
}

/** Keeps a valid HMAC trailer when present and returns the raw body otherwise. */
private fun optionalPayload(value: String): String {
    if (value.length < 64) return value
    val trailer = value.takeLast(64)
    if (!HEX_64.matches(trailer)) return value
    val payload = value.dropLast(64)
    val expected = hmacSha256(SIGNING_KEY, payload)
    val actual = runCatching { hexToBytes(trailer.lowercase()) }.getOrNull() ?: return value
    return if (MessageDigest.isEqual(expected, actual)) payload else value
}

private fun decodeLayer(value: String, keyText: String): String {
    val parts = value.trim().removePrefix("\uFEFF").split(":")
    if (parts.size != 2 || parts.any { !BASE64_BODY.matches(it) }) throw InatDecodeException()
    val encrypted = base64Decode(parts[0])
    val iv = base64Decode(parts[1])
    if (iv.size != 16 || encrypted.isEmpty() || encrypted.size % 16 != 0) throw InatDecodeException()
    val key = (keyText + "0000000000000000").take(16).toByteArray(Charsets.ISO_8859_1)
    return try {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        String(cipher.doFinal(encrypted), Charsets.UTF_8).removePrefix("\uFEFF")
    } catch (e: Exception) {
        throw InatDecodeException()
    }
}

private fun decodeJson(value: String): JSONObject? {
    val trimmed = value.trim()
    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return null
    for (candidate in listOf(optionalPayload(trimmed), trimmed)) {
        val parsed = parseValue(candidate) ?: continue
        if (parsed is JSONObject) return parsed
    }
    return null
}

private fun matchUrl(value: String, patterns: List<Regex>): String? {
    for (pattern in patterns) {
        val match = pattern.find(value) ?: continue
        val found = match.groupValues.getOrNull(1)?.ifEmpty { match.value } ?: match.value
        return found.replace("\\/", "/").trim('\'', '"')
    }
    return null
}

private fun targetOf(resolved: JSONObject): String? {
    for (key in listOf("chUrl", "url", "stream", "file", "src", "link", "video", "hls")) {
        text(resolved.opt(key)).takeIf { it.isNotEmpty() }?.let { return it }
    }
    val data = resolved.optJSONObject("data") ?: return null
    for (key in listOf("url", "stream")) {
        text(data.opt(key)).takeIf { it.isNotEmpty() }?.let { return it }
    }
    return null
}

private fun decodeResolver(value: String, configuration: Map<String, String>): JSONObject {
    val trimmed = value.trim()
    decodeJson(trimmed)?.let { return it }
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        return JSONObject().put("chUrl", trimmed)
    }
    matchUrl(trimmed, RESOLVER_URL_PATTERNS)?.let { return JSONObject().put("chUrl", it) }

    val keys = listOf(
        configuration["Regex1"],
        configuration["Regex2"],
        configuration["Regex2p"],
        RAW_RESPONSE_KEY,
    ).map { it?.trim().orEmpty() }.filter { it.isNotEmpty() }.distinct()

    for (key in keys) {
        val inner = runCatching { decodeLayer(trimmed, key) }.getOrNull()?.trim() ?: continue
        decodeJson(inner)?.let { return it }
        if (inner.startsWith("http://") || inner.startsWith("https://")) return JSONObject().put("chUrl", inner)
        for (secondKey in keys) {
            val second = runCatching { decodeLayer(inner, secondKey) }.getOrNull()?.trim() ?: continue
            decodeJson(second)?.let { return it }
        }
    }
    throw ErrorLoadingException("Inat: kaynak yanıtı çözülemedi. $REPORT_HINT")
}

private fun literalPattern(value: String): String {
    val result = StringBuilder()
    var index = 0
    while (index < value.length) {
        val character = value[index]
        if (character == '\\') {
            index++
            if (index >= value.length || value[index].isLetterOrDigit()) throw InatDecodeException()
            result.append(value[index])
        } else {
            if (character in ".*+?^\${}()|[]\\") throw InatDecodeException()
            result.append(character)
        }
        index++
    }
    return result.toString()
}

private fun captureFirst(body: String, pattern: String): String {
    if (pattern.isEmpty() || pattern.length > 512) throw InatDecodeException()
    val marker = if (pattern.contains("(.*?)")) "(.*?)" else "(.*)"
    val index = pattern.indexOf(marker)
    if (index < 0) throw InatDecodeException()
    val prefix = literalPattern(pattern.substring(0, index))
    val suffix = literalPattern(pattern.substring(index + marker.length))
    val greedy = marker == "(.*)"
    for (line in body.split('\r', '\n', '\u2028', '\u2029')) {
        val matchStart = if (prefix.isEmpty()) 0 else line.indexOf(prefix)
        if (matchStart < 0) continue
        val start = matchStart + prefix.length
        val end = when {
            suffix.isEmpty() -> if (greedy) line.length else start
            greedy -> line.lastIndexOf(suffix)
            else -> line.indexOf(suffix, start)
        }
        if (end >= start) return line.substring(start, end)
    }
    throw InatDecodeException()
}

private fun decodeCapturedUrl(value: String): String {
    var result = value.trim()
    if (result.contains('\\')) {
        result = parseValue("\"$result\"") as? String ?: throw InatDecodeException()
    }
    return result
}

private fun captureTarget(body: String, pattern: String?, replace: Pair<String, String>? = null): String? {
    if (!pattern.isNullOrBlank()) {
        val captured = runCatching { decodeCapturedUrl(captureFirst(body, pattern)) }.getOrNull()
        if (!captured.isNullOrBlank()) {
            val value = if (replace != null && replace.first.isNotEmpty()) {
                captured.replace(replace.first, replace.second)
            } else {
                captured
            }
            if (value.isNotBlank()) return value
        }
    }
    return matchUrl(body, FALLBACK_URL_PATTERNS)
}

private fun httpUri(value: String): URI {
    val uri = try {
        URI(value.trim())
    } catch (e: Exception) {
        throw ErrorLoadingException("Inat: geçersiz adres. $REPORT_HINT")
    }
    val scheme = uri.scheme?.lowercase()
    if ((scheme != "http" && scheme != "https") || uri.host.isNullOrEmpty()) {
        throw ErrorLoadingException("Inat: geçersiz adres. $REPORT_HINT")
    }
    return uri
}

private fun originOf(value: String): String {
    val uri = httpUri(value)
    val port = if (uri.port > 0) ":${uri.port}" else ""
    return "${uri.scheme}://${uri.host}$port"
}

private fun entryObject(value: Any?): JSONObject {
    var current = value
    var depth = 0
    while (current is String && depth < 2) {
        if (text(current).isEmpty()) return JSONObject()
        current = parseValue(current) ?: return JSONObject()
        depth++
    }
    if (current == null || current == JSONObject.NULL) return JSONObject()
    val rows = if (current is JSONArray) {
        (0 until current.length()).map { current.opt(it) }
    } else {
        listOf(current)
    }
    val merged = JSONObject()
    for (row in rows) {
        val obj = row as? JSONObject ?: continue
        for (key in obj.keys()) {
            if (key == "__proto__" || key == "constructor" || key == "prototype") continue
            merged.put(key, obj.opt(key))
        }
    }
    return merged
}

private fun normalizeHeaders(value: Any?): Map<String, String> {
    val source = entryObject(value)
    val result = LinkedHashMap<String, String>()
    for (name in source.keys()) {
        val normalized = when (name.lowercase().replace("-", "")) {
            "useragent" -> "user-agent"
            "xrequestedwith" -> "x-requested-with"
            "referrer" -> "referer"
            else -> name.lowercase()
        }
        if (normalized in FORBIDDEN_HEADERS) continue
        val header = text(source.opt(name))
        if (header.isEmpty()) continue
        if (!HEADER_NAME.matches(normalized) || HEADER_VALUE_BAD.containsMatchIn(header)) continue
        result[normalized] = header
    }
    return result
}

private fun stringMap(value: JSONObject): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    for (key in value.keys()) {
        val item = text(value.opt(key))
        if (item.isNotEmpty()) result[key] = item
    }
    return result
}

private class InatDecodeException : Exception()

/**
 * One Inat Baks list. Kept in this file (rather than a helper file) so the local
 * JVM test runner, which only copies *Provider.kt, sees the same configuration.
 */
data class InatCfg(
    val id: String,
    val name: String,
    val group: String,
    val type: String,
    val dataType: String,
    val url: String,
    val host: String,
    val sha: String,
) {
    val displayName: String get() = "Inat — $name"
    val isLiveList: Boolean get() = type == "tv_mode"
    val isEmbedOnly: Boolean get() = type == "link_mode" || type == "web_mode"
}

object InatCfgRegistry {
    private const val DIZIBOX = "diziboxen.help"
    private const val DIZIBOX_SHA = "sha256/LwJtYGEjL1fpZQHDsjX5Hb1G1cmszajwrB1Dv/BVGVU="
    private const val SPRBOX = "sprboxs.bar"
    private const val SPRBOX_SHA = "sha256/JFjoNaa3b0S9jWfpaRtapv8CvczX+y7ibxXcVOdagUQ="
    private const val FOURK = "4k.filmizleeeee.cfd"
    private const val FOURK_SHA = "sha256/NVrd940rwzL1bav4hRkzjaIDJTqVyo4TixmgNqG7NYY="

    // Lists come from this static catalogue instead of the ironsdk bootstrap, whose certificate
    // pin no longer matches upstream, so a broken bootstrap cannot hide the live lists.
    // Hosts/pins are advisory only: the shared Cloudstream client cannot pin per host, and the
    // source itself downgrades an unmatched pin to a warning. Unknown hosts stay "NULL".
    val ALL: List<InatCfg> = listOf(
        InatCfg("plat_hklZHuRuuNFJu_hnQLNFtxDX", "Liste 1 - TR", "CANLI TV KANALLARI", "tv_mode", "tv_mode", "https://diziboxen.help/CDN/001/002/dizibox/v2/tv/list1.php", DIZIBOX, DIZIBOX_SHA),
        InatCfg("plat_-0X2wFp7VAyL4BiETr3DGzxp", "Liste 2 - GLB", "CANLI TV KANALLARI", "tv_mode", "tv_mode", "https://diziboxen.help/CDN/001/002/dizibox/v2/tv/list2.php", DIZIBOX, DIZIBOX_SHA),
        InatCfg("plat_X_-78W4CyMj4kge0pzB5pBxa", "Liste 3 - TR", "CANLI TV KANALLARI", "tv_mode", "raw_mode", "https://diziboxen.help/CDN/001/002/dizibox/v2/tv/list3.php", DIZIBOX, DIZIBOX_SHA),
        InatCfg("plat_KDLoyZzhSK8eO3SP7pl-lVah", "Spor", "CANLI YAYIN & SPOR", "tv_mode", "tv_mode", "https://sprboxs.bar/CDN/001/SPR/v2/spor_v3.php", SPRBOX, SPRBOX_SHA),
        InatCfg("plat_x1xrQpb1cnjXNQlhHJJh-d-7", "Derbiler", "CANLI YAYIN & SPOR", "dizi_mode", "tv_mode", "https://sprboxs.bar/CDN/001/SPR/v2/derbiler.php", SPRBOX, SPRBOX_SHA),
        InatCfg("plat_XbOO87izu6ge2wsD9dQda4gF", "Premium", "DİĞER", "link_mode", "", "https://inattv257.cfd/premium/", "NULL", "NULL"),
        InatCfg("plat_5BtmCy7tc7RE19th4-8ZI645", "Sinema", "SİNEMA & FİLMLER", "tv_mode", "tv_mode", "https://diziboxen.help/CDN/001/002/dizibox/v2/tv/sinema.php", DIZIBOX, DIZIBOX_SHA),
        InatCfg("plat_IPwgYtD4Iaa-f1mu1G603Cwy", "Belgesel", "TEMATİK & TV", "tv_mode", "tv_mode", "https://diziboxen.help/CDN/001/002/dizibox/v2/tv/belgesel.php", DIZIBOX, DIZIBOX_SHA),
        InatCfg("plat_QyP4YpaqWR_fPnSL3FXk1t3p", "Ulusal", "CANLI TV KANALLARI", "tv_mode", "tv_mode", "https://diziboxen.help/CDN/001/002/dizibox/v2/tv/ulusal.php", DIZIBOX, DIZIBOX_SHA),
        InatCfg("plat_MZOJ3eZThBuLqmehbmefruZ4", "Haber", "CANLI TV KANALLARI", "tv_mode", "tv_mode", "https://diziboxen.help/CDN/001/002/dizibox/v2/tv/haber.php", DIZIBOX, DIZIBOX_SHA),
        InatCfg("plat_B159Mech2izHkFn7NQD6AlOE", "EBA TV", "DİĞER", "tv_mode", "tv_mode", "https://diziboxen.help/CDN/001/002/dizibox/v2/tv/eba.php", DIZIBOX, DIZIBOX_SHA),
        InatCfg("plat_B03_bguqxzvRzyvPeiExd_xO", "Çocuk", "TEMATİK & TV", "tv_mode", "tv_mode", "https://diziboxen.help/CDN/001/002/dizibox/v2/tv/cocuk.php", DIZIBOX, DIZIBOX_SHA),
        InatCfg("plat_1p78-p683RKRQmhQj_bhfaHO", "Dini", "DİĞER", "tv_mode", "tv_mode", "https://diziboxen.help/CDN/001/002/dizibox/v2/tv/dini.php", DIZIBOX, DIZIBOX_SHA),
        InatCfg("plat_BSwGQS-pp65t7fK5csiC9l2g", "EXXEN", "YAYIN PLATFORMLARI", "dizi_mode", "tv_mode", "https://diziboxen.help/CDN/001/002/dizibox/v2/ex/index.php", DIZIBOX, DIZIBOX_SHA),
        InatCfg("plat_jz59NDtYLsawgRseigIswAEf", "Gain", "YAYIN PLATFORMLARI", "dizi_mode", "tv_mode", "https://diziboxen.help/CDN/001/002/dizibox/v2/ga/index.php", DIZIBOX, DIZIBOX_SHA),
        InatCfg("plat_b6c0fgN5DT1TlQLs3X4DFEUC", "Netflix", "YAYIN PLATFORMLARI", "dizi_mode", "tv_mode", "https://diziboxen.help/CDN/001/002/dizibox/v2/nf/index.php", DIZIBOX, DIZIBOX_SHA),
        InatCfg("plat_0vOwWIPu3PDT0Jivm4ICbCYO", "HBO Max - (BLUTV)", "YAYIN PLATFORMLARI", "dizi_mode", "tv_mode", "https://diziboxen.help/CDN/001/002/dizibox/v2/hb/index.php", DIZIBOX, DIZIBOX_SHA),
        InatCfg("plat_nKFKpIsaKwC_hjUOBLcrYJrI", "Disney+", "YAYIN PLATFORMLARI", "dizi_mode", "tv_mode", "https://diziboxen.help/CDN/001/002/dizibox/v2/dsny/index.php", DIZIBOX, DIZIBOX_SHA),
        InatCfg("plat_htbHFdgXWa6QAiUxh2m_iFAc", "Amazon Prime", "YAYIN PLATFORMLARI", "dizi_mode", "tv_mode", "https://diziboxen.help/CDN/001/002/dizibox/v2/amz/index.php", DIZIBOX, DIZIBOX_SHA),
        InatCfg("plat_W9cS1zuzIl3eLwopo6DWoRtH", "Tabii", "YAYIN PLATFORMLARI", "dizi_mode", "tv_mode", "https://diziboxen.help/CDN/001/002/dizibox/v2/tbi/index.php", DIZIBOX, DIZIBOX_SHA),
        InatCfg("plat_pfMNTPEDYNErCM1RafOgADx-", "Mubi", "YAYIN PLATFORMLARI", "tv_mode", "tv_mode", "https://diziboxen.help/CDN/001/002/dizibox/v2/film/mubi.php", DIZIBOX, DIZIBOX_SHA),
        InatCfg("plat_QVYYoekBIvGw1KFR0wdQE8gu", "TOD", "YAYIN PLATFORMLARI", "dizi_mode", "tv_mode", "https://sprboxs.bar/CDN/001/SPR/v2/ccc/a/index.php", SPRBOX, SPRBOX_SHA),
        InatCfg("plat_Eezq0PIaOgfuROrP97RMrve8", "Yabancı Diziler", "DİZİLER", "dizi_mode", "tv_mode", "https://diziboxen.help/CDN/001/002/dizibox/v2/yabanci-dizi/index.php", DIZIBOX, DIZIBOX_SHA),
        InatCfg("plat__LKyrd8ppH12JNFYVgUX1rJO", "Yerli Diziler", "DİZİLER", "dizi_mode", "tv_mode", "https://diziboxen.help/CDN/001/002/dizibox/v2/yerli-dizi/index.php", DIZIBOX, DIZIBOX_SHA),
        InatCfg("plat_Q9tlKwxINorm6gHWwjkJigM9", "Yerli Filmler", "SİNEMA & FİLMLER", "tv_mode", "tv_mode", "https://diziboxen.help/CDN/001/002/dizibox/v2/film/yerli-filmler.php", DIZIBOX, DIZIBOX_SHA),
        InatCfg("plat_OzD_GAgae020FLn469Yovxu6", "4K Film 01 | Exo", "SİNEMA & FİLMLER", "tv_mode", "tv_mode", "https://4k.filmizleeeee.cfd/4k/01/public/catalog-exo.php", FOURK, FOURK_SHA),
        InatCfg("plat_wxEuxB5TlyyVLJArm_5c3DBM", "4K Film 01 | Web", "SİNEMA & FİLMLER", "tv_mode", "tv_mode", "https://4k.filmizleeeee.cfd/4k/01/public/catalog-web.php", FOURK, FOURK_SHA),
        InatCfg("plat_ieEMdam3q5hzCPiZLQaAgEeF", "4K Film 02 | Web", "SİNEMA & FİLMLER", "tv_mode", "tv_mode", "https://4k.filmizleeeee.cfd/4k/02/public/catalog-web.php", FOURK, FOURK_SHA),
        InatCfg("plat_Y4HmB7sMjvY66BrS0pt6gGGY", "4K Film 03 | Exo", "SİNEMA & FİLMLER", "tv_mode", "tv_mode", "https://4k.filmizleeeee.cfd/4k/03/public/catalog-exo.php", FOURK, FOURK_SHA),
        InatCfg("plat_MZnH69UAsd7FH44OlNWkGgv8", "4K Film 03 | Web", "SİNEMA & FİLMLER", "tv_mode", "tv_mode", "https://4k.filmizleeeee.cfd/4k/03/public/catalog-web.php", FOURK, FOURK_SHA),
        InatCfg("plat_t7JZ3C-uIcJSLkkhYwXYzgxO", "4K Film 04 | Exo", "SİNEMA & FİLMLER", "tv_mode", "tv_mode", "https://4k.filmizleeeee.cfd/4k/04/public/catalog-exo.php", FOURK, FOURK_SHA),
        InatCfg("plat_BLrJGGw4JpbX2e5aXlrYd57y", "4K Film 04 | Web", "SİNEMA & FİLMLER", "tv_mode", "tv_mode", "https://4k.filmizleeeee.cfd/4k/04/public/catalog-web.php", FOURK, FOURK_SHA),
        InatCfg("plat_a058YtcuPSN0YdqLdc-_b6YU", "4K Film 06 | Exo", "SİNEMA & FİLMLER", "tv_mode", "tv_mode", "https://4k.filmizleeeee.cfd/4k/06/public/catalog-exo.php", FOURK, FOURK_SHA),
        InatCfg("plat_k2jK8cUekQuL1CcWqMAzuE2d", "4K Film 06 | Web", "SİNEMA & FİLMLER", "tv_mode", "tv_mode", "https://4k.filmizleeeee.cfd/4k/06/public/catalog-web.php", FOURK, FOURK_SHA),
        InatCfg("plat_LILDAcQf-pW0mx2p_ZhFL2XS", "4K Film 07 | Web", "SİNEMA & FİLMLER", "tv_mode", "tv_mode", "https://4k.filmizleeeee.cfd/4k/07/public/catalog-web.php", FOURK, FOURK_SHA),
    )
}

internal data class InatEntry(
    val name: String,
    val url: String,
    val type: String,
    val img: String,
    val plot: String,
    val headers: Map<String, String>,
    val params: Map<String, String>,
    val host: String,
    val pin: String,
    val dataType: String,
    val season: Boolean,
    val episode: Boolean,
)

internal sealed class InatStream {
    data class Direct(val url: String, val headers: Map<String, String>) : InatStream()
    data class Embed(val url: String, val headers: Map<String, String>) : InatStream()
}

/**
 * Signed Inat protocol: x-ts/x-nc/x-sg on every tier except the tekli_regex page reads,
 * double AES-128-CBC response layers, and the resolver/event handlers.
 */
internal object InatClient {
    private val random = SecureRandom()
    private val listCache = ConcurrentHashMap<String, Pair<Long, List<InatEntry>>>()

    @Volatile
    private var clockOffset = 0L

    suspend fun entries(url: String, host: String, pin: String, dataType: String): List<InatEntry> {
        val key = "$url|$dataType|$host"
        listCache[key]?.let { (at, rows) -> if (System.currentTimeMillis() - at < LIST_TTL) return rows }
        httpUri(url)
        val parsed = if (dataType == "raw_mode") rawJson(url) else postJson(url)
        val list = parsed as? JSONArray
            ?: throw ErrorLoadingException("Inat: liste yanıtı desteklenmiyor. $REPORT_HINT")
        val rows = (0 until list.length()).mapNotNull { index ->
            (list.opt(index) as? JSONObject)?.let { parseEntry(it, host, pin, dataType) }
        }
        if (listCache.size > CACHE_LIMIT) listCache.clear()
        listCache[key] = System.currentTimeMillis() to rows
        return rows
    }

    suspend fun resolve(entry: InatEntry): InatStream {
        if (entry.url.isEmpty()) throw ErrorLoadingException("Inat: ${entry.name} için yayın adresi yok. $REPORT_HINT")
        val headers = entry.headers + ("cache-control" to "no-cache")
        val handler = entry.type.replace(Regex("_(u|s)_mode$"), "_mode")
        return when {
            handler == "web_mode" || handler == "link_mode" -> {
                httpUri(entry.url)
                InatStream.Embed(entry.url, entry.headers)
            }
            handler == "SSprDrm_event" -> {
                val body = request(
                    entry.url,
                    "GET",
                    entry.headers + mapOf("content-type" to "application/json", "accept" to "application/json"),
                    null,
                    signed = true,
                )
                val target = try {
                    decodeCapturedUrl(captureFirst(body, entry.params["Regex1"].orEmpty()))
                } catch (e: InatDecodeException) {
                    throw ErrorLoadingException("Inat: ${entry.name} etkinlik adresi çözülemedi. $REPORT_HINT")
                }
                httpUri(target)
                InatStream.Direct(target, entry.headers)
            }
            handler.startsWith("SSprDrm") ->
                throw ErrorLoadingException(
                    "Inat: ${entry.name} bir etkinlik listesi; oynatmak için bir etkinlik seçin. $REPORT_HINT",
                )
            handler == "tekli_regex_mode" || handler == "tekli_regex_no_sh_mode" ||
                handler == "tekli_regex_replace_mode" -> {
                // Replace mode swaps the literal chReg Regex2 with Regex3 on the captured value.
                // shortcut: the upstream JS port has no replace step, so this swap is unverified
                // against a live replace row; drop it if Show TV/Show Türk stop resolving.
                val replacement = if (handler == "tekli_regex_replace_mode") {
                    entry.params["Regex2"].orEmpty() to entry.params["Regex3"].orEmpty()
                } else {
                    null
                }
                val body = request(entry.url, "GET", headers, null, signed = false)
                val target = captureTarget(body, entry.params["Regex1"], replacement)
                    ?: return InatStream.Embed(entry.url, entry.headers)
                httpUri(target)
                InatStream.Direct(target, entry.headers)
            }
            handler.contains("regex_lb") || entry.params["Regex1"].orEmpty().isNotEmpty() -> {
                val encoded = request(entry.url, "GET", headers, null, signed = true)
                val resolved = try {
                    decodeResolver(encoded, entry.params)
                } catch (e: ErrorLoadingException) {
                    return InatStream.Embed(entry.url, entry.headers)
                }
                val target = targetOf(resolved) ?: return InatStream.Embed(entry.url, entry.headers)
                httpUri(target)
                val targetHeaders = entry.headers + normalizeHeaders(
                    resolved.firstOf("chHeaders", "headers", "playHeaders"),
                )
                val mode = text(resolved.opt("chType"))
                    .ifEmpty { text(resolved.opt("type")) }
                    .ifEmpty { text(resolved.opt("mode")) }
                if (mode == "web_mode" || target.contains("/embed/") || target.contains("player.php")) {
                    InatStream.Embed(target, targetHeaders)
                } else {
                    InatStream.Direct(target, targetHeaders)
                }
            }
            MEDIA_URL.containsMatchIn(entry.url) -> {
                httpUri(entry.url)
                InatStream.Direct(entry.url, entry.headers)
            }
            else -> throw ErrorLoadingException(
                "Inat: ${entry.name} için desteklenmeyen kaynak türü: ${entry.type.ifEmpty { "bilinmiyor" }}. $REPORT_HINT",
            )
        }
    }

    private suspend fun postJson(url: String): Any? {
        val nonce = randomAlphanumeric(16)
        val form = linkedMapOf("1" to nonce, "0" to nonce)
        val encoded = request(
            url,
            "POST",
            API_HEADERS + ("content-type" to "application/x-www-form-urlencoded; charset=UTF-8"),
            form,
            signed = true,
        )
        return parseJson(optionalPayload(decodeLayer(decodeLayer(encoded, nonce), nonce)))
    }

    private suspend fun rawJson(url: String): Any? {
        val encoded = request(url, "GET", API_HEADERS, null, signed = true)
        return parseJson(optionalPayload(decodeLayer(decodeLayer(encoded, RAW_RESPONSE_KEY), RAW_RESPONSE_KEY)))
    }

    private suspend fun request(
        url: String,
        method: String,
        headers: Map<String, String>,
        form: Map<String, String>?,
        signed: Boolean,
    ): String {
        val uri = httpUri(url)
        val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        val body = form?.entries?.joinToString("&") { "${it.key}=${it.value}" } ?: ""
        var attempt = 0
        while (true) {
            val requestHeaders = LinkedHashMap(headers)
            if (signed) {
                val timestamp = (System.currentTimeMillis() / 1000 + clockOffset).toString()
                val nonce = randomHex(16)
                requestHeaders["x-ts"] = timestamp
                requestHeaders["x-nc"] = nonce
                requestHeaders["x-sg"] = hmacSha256(
                    SIGNING_KEY,
                    "$method\n$path\n$timestamp\n$nonce\n${sha256Hex(body)}",
                ).toHex()
            }
            val response = try {
                if (method == "POST") {
                    app.post(url, data = form ?: emptyMap(), headers = requestHeaders)
                } else {
                    app.get(url, headers = requestHeaders)
                }
            } catch (e: ErrorLoadingException) {
                throw e
            } catch (e: Exception) {
                throw ErrorLoadingException("Inat: istek başarısız (${e.javaClass.simpleName}: ${e.message}). $REPORT_HINT")
            }
            // shortcut: a 403 thrown by the client never reaches here, so clock skew is only
            // corrected from responses the client returns without throwing.
            val serverTime = response.headers["x-st"]
            val corrected = if (signed && serverTime != null && SERVER_TIME.matches(serverTime)) {
                val offset = serverTime.toLong() - System.currentTimeMillis() / 1000
                val changed = kotlin.math.abs(offset - clockOffset) > 2
                if (changed) clockOffset = offset
                changed
            } else {
                false
            }
            if (response.code == 403 && signed && corrected && attempt == 0) {
                attempt++
                continue
            }
            if (response.code !in 200..299) {
                throw ErrorLoadingException("Inat: upstream HTTP ${response.code}. $REPORT_HINT")
            }
            val text = response.text
            if (text.length > RESPONSE_LIMIT) {
                throw ErrorLoadingException("Inat: upstream yanıtı çok büyük. $REPORT_HINT")
            }
            return text
        }
    }

    private fun parseJson(value: String): Any? {
        val parsed = parseValue(value.trim())
        if (parsed == null || parsed == JSONObject.NULL) {
            throw ErrorLoadingException("Inat: yanıt çözülemedi. $REPORT_HINT")
        }
        return parsed
    }

    private fun parseEntry(row: JSONObject, host: String, pin: String, dataType: String): InatEntry {
        val label = text(row.firstOf("sezonName", "diziName", "bolumName", "title", "name", "chName", "filmName"))
        val type = text(row.firstOf("chType", "bolumType", "filmType", "diziType", "type"))
        val rowType = text(row.opt("diziType")).ifEmpty { text(row.opt("chType")).ifEmpty { text(row.opt("catType")) } }
        val episode = row.has("bolumName") || row.has("bolumUrl") || rowType == "bolum_mode" ||
            EPISODE_NAME.containsMatchIn(label)
        val season = !episode && (row.has("sezonName") || row.has("sezonUrl") || rowType == "sezon_mode" ||
            SEASON_NAME.containsMatchIn(label))
        return InatEntry(
            name = text(row.firstOf("chName", "bolumName", "filmName", "diziName", "title", "name")),
            url = text(row.firstOf("chUrl", "bolumUrl", "filmUrl", "diziUrl", "url")),
            type = type,
            img = text(row.firstOf("chImg", "chResim", "filmImg", "filmResim", "diziImg", "diziResim", "img", "resim", "poster")),
            plot = text(row.firstOf("diziDetay", "filmDetay", "chDetay", "detay", "plot", "description")),
            headers = normalizeHeaders(row.firstOf("chHeaders", "bolumHeaders", "filmHeaders", "diziHeaders", "headers")),
            params = stringMap(entryObject(row.firstOf("chReg", "bolumReg", "filmReg", "diziReg", "reg"))),
            host = text(row.firstOf("chHost", "filmHost", "bolumHost", "diziHost", "host")).ifEmpty { host },
            pin = text(row.firstOf("chSha", "filmSha", "bolumSha", "diziSha", "sha")).ifEmpty { pin },
            dataType = text(row.firstOf("filmDataType", "diziDataType", "chDataType")).ifEmpty { dataType },
            season = season,
            episode = episode,
        )
    }

    private fun randomAlphanumeric(length: Int): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun randomHex(bytes: Int): String = ByteArray(bytes).also { random.nextBytes(it) }.toHex()
}

open class InatPlatformProvider(private val cfg: InatCfg) : MainAPI() {
    override var mainUrl = originOf(cfg.url)
    override var name = cfg.displayName
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live, TvType.Movie, TvType.TvSeries)

    override val mainPage get() = listOf(MainPageData(cfg.name, cfg.url))

    private val hasList: Boolean get() = cfg.dataType.isNotEmpty() && !cfg.isEmbedOnly

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
        if (!hasList) return newHomePageResponse(request.name, listOf(embedSearch()), false)
        val items = rows().mapIndexedNotNull { index, entry -> entry.toSearch(index) }
        return newHomePageResponse(request.name, items, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        if (!hasList) return if (cfg.name.contains(term, ignoreCase = true)) listOf(embedSearch()) else emptyList()
        return rows().mapIndexedNotNull { index, entry ->
            if (entry.name.contains(term, ignoreCase = true)) entry.toSearch(index) else null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val ref = parseToken(url) ?: throw ErrorLoadingException("$name: içerik bağlantısı okunamadı. $REPORT_HINT")
        return when (ref.mode) {
            MODE_EMBED -> newLiveStreamLoadResponse(ref.name.ifBlank { cfg.name }, url, url)
            MODE_ITEM -> {
                val entry = pick(rows(), ref)
                    ?: throw ErrorLoadingException("$name: ${ref.name} bulunamadı. $REPORT_HINT")
                if (cfg.isLiveList) liveLoad(entry, url) else catalogueLoad(entry, url)
            }
            else -> {
                val entry = pick(rows(ref.url, ref.host, ref.pin, ref.dataType), ref)
                    ?: throw ErrorLoadingException("$name: ${ref.name} bulunamadı. $REPORT_HINT")
                newMovieLoadResponse(entry.name.ifBlank { cfg.name }, url, TvType.Movie, url) {
                    this.posterUrl = entry.img.ifBlank { null }
                    this.plot = entry.plot.ifBlank { null }
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val ref = parseToken(data) ?: throw ErrorLoadingException("$name: kaynak bağlantısı okunamadı. $REPORT_HINT")
        val targets: List<Pair<String, InatEntry>> = when (ref.mode) {
            MODE_EMBED -> listOf(cfg.name to embedEntry(ref.url))
            MODE_LIST -> rows(ref.url, ref.host, ref.pin, ref.dataType)
                .filter { !it.season && it.url.isNotEmpty() }
                .mapIndexed { index, entry -> entry.name.ifBlank { "Kaynak ${index + 1}" } to entry }
            else -> {
                val entry = pick(rows(ref.url, ref.host, ref.pin, ref.dataType), ref)
                    ?: throw ErrorLoadingException("$name: ${ref.name} bulunamadı. $REPORT_HINT")
                listOf(entry.name.ifBlank { "Kaynak" } to entry)
            }
        }
        var sent = 0
        var failure: String? = null
        for ((label, entry) in targets) {
            try {
                when (val stream = InatClient.resolve(entry)) {
                    is InatStream.Embed -> {
                        if (loadExtractor(stream.url, refererOf(stream), subtitleCallback, callback)) {
                            sent++
                        } else {
                            failure = failure ?: "$name: $label oynatıcısı çözülemedi."
                        }
                    }
                    is InatStream.Direct -> {
                        val quality = qualityOf(label)
                        if (!Settings.qualityAllowed(quality)) continue
                        callback.invoke(newExtractorLink(name, label, stream.url, linkTypeOf(stream.url)) {
                            this.referer = refererOf(stream)
                            this.headers = mediaHeaders(stream.headers)
                            this.quality = quality
                        })
                        sent++
                    }
                }
            } catch (e: ErrorLoadingException) {
                failure = failure ?: e.message
            } catch (e: Exception) {
                failure = failure ?: "$name: $label kaynağı çözülemedi. $REPORT_HINT"
            }
        }
        if (sent == 0) throw ErrorLoadingException(failure ?: "$name: oynatılabilir kaynak bulunamadı. $REPORT_HINT")
        return true
    }

    private suspend fun rows(): List<InatEntry> = InatClient.entries(cfg.url, cfg.host, cfg.sha, cfg.dataType)

    private suspend fun rows(entry: InatEntry): List<InatEntry> =
        InatClient.entries(entry.url, entry.host, entry.pin, entry.dataType)

    private suspend fun rows(url: String, host: String, pin: String, dataType: String): List<InatEntry> =
        InatClient.entries(url, host, pin, dataType)

    private fun embedSearch(): SearchResponse =
        newLiveSearchResponse(cfg.name, token(MODE_EMBED, cfg.url, 0, cfg.name), TvType.Live)

    private fun embedEntry(url: String) = InatEntry(
        name = cfg.name,
        url = url,
        type = "web_mode",
        img = "",
        plot = "",
        headers = emptyMap(),
        params = emptyMap(),
        host = cfg.host,
        pin = cfg.sha,
        dataType = cfg.dataType,
        season = false,
        episode = false,
    )

    private fun InatEntry.toSearch(index: Int): SearchResponse? {
        val title = this.name
        if (title.isBlank()) return null
        if (season || episode) return null
        val target = token(MODE_ITEM, cfg.url, index, title)
        val image = img.ifBlank { null }
        return when {
            cfg.isLiveList -> newLiveSearchResponse(title, target, TvType.Live) { posterUrl = image }
            isSeriesRow(this) -> newTvSeriesSearchResponse(title, target, TvType.TvSeries) { posterUrl = image }
            else -> newMovieSearchResponse(title, target, TvType.Movie) { posterUrl = image }
        }
    }

    private fun isSeriesRow(entry: InatEntry): Boolean = when (entry.type) {
        "sezon_mode", "dizi_mode", "sezonlar" -> true
        "film_mode" -> false
        else -> cfg.name.contains("dizi", ignoreCase = true) && !cfg.name.contains("film", ignoreCase = true)
    }

    private suspend fun liveLoad(entry: InatEntry, url: String): LoadResponse =
        newLiveStreamLoadResponse(entry.name.ifBlank { cfg.name }, url, url) {
            this.posterUrl = entry.img.ifBlank { null }
        }

    private suspend fun catalogueLoad(entry: InatEntry, url: String): LoadResponse {
        if (entry.url.isBlank()) throw ErrorLoadingException("$name: ${entry.name} için içerik adresi yok. $REPORT_HINT")
        val children = rows(entry)
        if (children.isEmpty()) throw ErrorLoadingException("$name: ${entry.name} için kaynak bulunamadı. $REPORT_HINT")
        val poster = entry.img.ifBlank { children.firstNotNullOfOrNull { it.img.ifBlank { null } }.orEmpty() }
        val plot = entry.plot.ifBlank { children.firstNotNullOfOrNull { it.plot.ifBlank { null } }.orEmpty() }

        if (children.any { it.season }) {
            val seasons = children.mapIndexedNotNull { index, season ->
                if (season.season && season.url.isNotBlank()) (index + 1) to season else null
            }
            if (seasons.isEmpty()) {
                throw ErrorLoadingException("$name: ${entry.name} için sezon adresi yok. $REPORT_HINT")
            }
            val episodes = seasons.flatMap { (seasonNumber, season) ->
                rows(season).mapIndexed { index, row ->
                    newEpisode(token(MODE_ROW, season.url, index, row.name, season.host, season.pin, season.dataType)) {
                        this.name = row.name.ifBlank { "${index + 1}. Bölüm" }
                        this.season = seasonNumber
                        this.episode = episodeNumber(row.name) ?: (index + 1)
                        this.posterUrl = row.img.ifBlank { poster }.ifBlank { null }
                    }
                }
            }
            if (episodes.isEmpty()) throw ErrorLoadingException("$name: ${entry.name} için bölüm bulunamadı. $REPORT_HINT")
            return newTvSeriesLoadResponse(entry.name, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster.ifBlank { null }
                this.plot = plot.ifBlank { null }
            }
        }

        if (isSeriesRow(entry) || children.any { it.episode }) {
            val episodes = children.mapIndexed { index, row ->
                newEpisode(token(MODE_ROW, entry.url, index, row.name, entry.host, entry.pin, entry.dataType)) {
                    this.name = row.name.ifBlank { "${index + 1}. Bölüm" }
                    this.season = 1
                    this.episode = episodeNumber(row.name) ?: (index + 1)
                    this.posterUrl = row.img.ifBlank { null }
                }
            }
            return newTvSeriesLoadResponse(entry.name, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster.ifBlank { null }
                this.plot = plot.ifBlank { null }
            }
        }

        return newMovieLoadResponse(
            entry.name,
            url,
            TvType.Movie,
            token(MODE_LIST, entry.url, 0, entry.name, entry.host, entry.pin, entry.dataType),
        ) {
            this.posterUrl = poster.ifBlank { null }
            this.plot = plot.ifBlank { null }
        }
    }

    private fun episodeNumber(value: String): Int? =
        EPISODE_NUMBER.find(value)?.groupValues?.get(1)?.toIntOrNull()

    private fun qualityOf(label: String): Int {
        val text = label.lowercase()
        return when {
            text.contains("2160") || text.contains("4k") -> Qualities.P2160.value
            text.contains("1080") -> Qualities.P1080.value
            text.contains("720") -> Qualities.P720.value
            text.contains("480") -> Qualities.P480.value
            text.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun linkTypeOf(url: String): ExtractorLinkType =
        if (url.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

    private fun refererOf(stream: InatStream): String {
        val headers = when (stream) {
            is InatStream.Direct -> stream.headers
            is InatStream.Embed -> stream.headers
        }
        return headers["referer"] ?: "$mainUrl/"
    }

    private fun mediaHeaders(headers: Map<String, String>): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for ((key, value) in headers) {
            if (key == "authorization" || key == "cookie" || key == "accept-encoding") continue
            result[key] = value
        }
        val userAgent = result["user-agent"]
        if (userAgent.isNullOrEmpty() || userAgent == "speedrestapi") result["user-agent"] = BROWSER_UA
        if (result["referer"] == "https://speedrestapi.com/") result.remove("referer")
        return result
    }

    private fun token(
        mode: String,
        url: String,
        index: Int,
        name: String,
        host: String = cfg.host,
        pin: String = cfg.sha,
        dataType: String = cfg.dataType,
    ): String = listOf(
        TOKEN_PREFIX,
        mode,
        url,
        index.toString(),
        base64UrlEncode(name),
        host,
        pin,
        dataType,
    ).joinToString("|")

    private fun parseToken(data: String): ItemRef? {
        if (!data.startsWith("$TOKEN_PREFIX|")) return null
        val parts = data.split("|")
        if (parts.size != 8) return null
        return ItemRef(
            mode = parts[1],
            url = parts[2],
            index = parts[3].toIntOrNull() ?: return null,
            name = base64UrlDecode(parts[4]),
            host = parts[5],
            pin = parts[6],
            dataType = parts[7],
        )
    }

    private fun pick(rows: List<InatEntry>, ref: ItemRef): InatEntry? {
        val indexed = rows.getOrNull(ref.index)
        if (indexed != null && (ref.name.isEmpty() || indexed.name == ref.name)) return indexed
        return rows.firstOrNull { it.name == ref.name } ?: indexed
    }

    private data class ItemRef(
        val mode: String,
        val url: String,
        val index: Int,
        val name: String,
        val host: String,
        val pin: String,
        val dataType: String,
    )
}

/**
 * No-argument entry point for the local runner; the plugin registers one
 * [InatPlatformProvider] per list through [ALL].
 */
class InatProvider : InatPlatformProvider(InatCfgRegistry.ALL.first()) {
    companion object {
        val ALL: List<InatPlatformProvider> = InatCfgRegistry.ALL.map { InatPlatformProvider(it) }
    }
}
