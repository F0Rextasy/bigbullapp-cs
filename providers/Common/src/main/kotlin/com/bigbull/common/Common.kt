package com.bigbull.common

import java.net.URI
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

// Tüm modüllerin kopyaladığı tarayıcı kimliği; UA rulajı tek yerden yapılır.
// NiceHttp app.defaultHeaders küçük harfli "user-agent" kullandığı için anahtarlar küçük harflidir.
val browserHeaders: Map<String, String> = java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER).apply {
    put("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
    put("accept-language", "tr-TR,tr;q=0.9")
}

fun Element.posterSrcLazy(): String? =
    posterSrcLazyImpl()

private val PLACEHOLDER_IMG = Regex(
    """^(data:|about:|javascript:)|/(pixel|blank|lazy|placeholder|empty|spacer|transparent|altyazi|dublaj|tr-flag|en-flag)\.(png|gif|jpg|jpeg|svg|webp)""",
    RegexOption.IGNORE_CASE,
)
private val BG_URL = Regex("""url\(['"]?([^'")\s]+)['"]?\)""", RegexOption.IGNORE_CASE)

private fun Element.posterSrcLazyImpl(): String? {
    for (attrName in listOf("data-src", "data-original", "data-lazy-src", "data-wpfc-original-src", "data-lazy", "data-bg", "data-background", "data-image", "data-poster", "src")) {
        val v = attr(attrName).trim()
        if (v.isNotEmpty() && !PLACEHOLDER_IMG.containsMatchIn(v)) return v
    }
    for (attrName in listOf("data-srcset", "srcset")) {
        val v = attr(attrName).trim().substringBefore(',').trim().substringBefore(' ').trim()
        if (v.isNotEmpty() && !PLACEHOLDER_IMG.containsMatchIn(v)) return v
    }
    val style = attr("style").trim()
    if (style.isNotEmpty()) {
        val bg = BG_URL.find(style)?.groupValues?.get(1)?.trim().orEmpty()
        if (bg.isNotEmpty() && !PLACEHOLDER_IMG.containsMatchIn(bg)) return bg
    }
    return null
}

fun String.hexToBytes(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()

fun aesCbcDecrypt(cipherBytes: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
    Cipher.getInstance("AES/CBC/PKCS5Padding").run {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        doFinal(cipherBytes)
    }

fun aesGcmDecrypt(cipherBytes: ByteArray, key: ByteArray, iv: ByteArray, tagBits: Int = 128): ByteArray =
    Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(tagBits, iv))
        doFinal(cipherBytes)
    }

fun aesGcmEncrypt(plainBytes: ByteArray, key: ByteArray, iv: ByteArray, tagBits: Int = 128): ByteArray =
    Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(tagBits, iv))
        doFinal(plainBytes)
    }

/** DiziPal/SelcukFlix RSC `enc` bloğu: PBKDF2-HMAC-SHA512(999, 256) + AES-256-CBC. */
fun pbkdf2AesCbcDecrypt(
    cipherBytes: ByteArray,
    passphrase: String,
    saltHex: String,
    ivHex: String,
    rounds: Int = 999,
    keyBits: Int = 256,
): String? = runCatching {
    val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        .generateSecret(PBEKeySpec(passphrase.toCharArray(), saltHex.hexToBytes(), rounds, keyBits))
        .encoded
    String(aesCbcDecrypt(cipherBytes, key, ivHex.hexToBytes()), Charsets.UTF_8)
}.getOrNull()

/** CryptoJS/OpenSSL EVP_BytesToKey (2x MD5 -> 32 bayt anahtar) + AES-256-CBC (bePlayer/vidmixi). */
fun evpMd5AesCbcDecrypt(cipherBytes: ByteArray, password: String, saltHex: String, ivHex: String): String {
    val pw = password.toByteArray(Charsets.UTF_8)
    val salt = saltHex.hexToBytes()
    fun md5(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("MD5").apply { parts.forEach(::update) }.digest()
    val first = md5(pw, salt)
    val key = (first + md5(first, pw, salt)).copyOf(32)
    return String(aesCbcDecrypt(cipherBytes, key, ivHex.hexToBytes()), Charsets.UTF_8)
}

private val HLS_RES = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)
private val HLS_NAME = Regex("""NAME="(\d{3,4})p?"""", RegexOption.IGNORE_CASE)

/** HLS master playlist içindeki #EXT-X-STREAM-INF kalite varyantlarını (yükseklik px, tam URL) olarak çıkarır. */
fun parseHlsVariants(masterUrl: String, m3u8Text: String): List<Pair<Int, String>> {
    if (!m3u8Text.contains("#EXT-X-STREAM-INF") ||
        m3u8Text.contains("TYPE=AUDIO", ignoreCase = true) ||
        m3u8Text.contains("AUDIO=", ignoreCase = true)
    ) return emptyList()
    val base = runCatching { URI(masterUrl.trim()) }.getOrNull() ?: return emptyList()
    val lines = m3u8Text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    val out = ArrayList<Pair<Int, String>>()
    val seen = HashSet<String>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
            val height = HLS_RES.find(line)?.groupValues?.get(1)?.toIntOrNull()
                ?: HLS_NAME.find(line)?.groupValues?.get(1)?.toIntOrNull()
                ?: 0
            var j = i + 1
            while (j < lines.size && lines[j].startsWith("#")) j++
            if (j < lines.size) {
                val target = runCatching {
                    val raw = lines[j]
                    if (raw.startsWith("http://") || raw.startsWith("https://")) raw
                    else base.resolve(raw).toString()
                }.getOrNull()
                if (!target.isNullOrBlank() && height > 0 && seen.add("$height|$target")) {
                    out.add(height to target)
                }
                i = j
            }
        }
        i++
    }
    return out.sortedByDescending { it.first }
}

/**
 * pichive iframe'i `nm.php`/`nsub.php` adreslerine proof-of-work ister (`&p=<nonce>`), yoksa 404 döner.
 * Sayfadaki `NP_GATE` (önek `String.fromCharCode`, sayaç base36, `& maske`) ya da varsayılan biçim
 * (`sha256("n:i")` ilk `b` biti sıfır) çözülür. shortcut: sayfa bu iki biçimden birini bırakırsa
 * nonce null olur ve adres imzasız kalır; yeni biçimde tekrar çözülmeli.
 */
fun pichiveGateNonce(html: String): String? {
    val gate = Regex(""""gate"\s*:\s*\{\s*"n"\s*:\s*"([^"]+)"(?:\s*,\s*"b"\s*:\s*(\d+))?""").find(html) ?: return null
    val n = gate.groupValues[1]
    val bits = gate.groupValues[2].toIntOrNull() ?: 12
    val md = MessageDigest.getInstance("SHA-256")
    fun wordAt(msg: String, idx: Int): Long {
        val d = md.digest(msg.toByteArray(Charsets.UTF_8))
        val o = (idx.coerceIn(0, 7)) * 4
        return ((d[o].toLong() and 0xff) shl 24) or ((d[o + 1].toLong() and 0xff) shl 16) or
            ((d[o + 2].toLong() and 0xff) shl 8) or (d[o + 3].toLong() and 0xff)
    }
    val fn = Regex("""window\.NP_GATE\s*=\s*function\(\s*(\w+)\s*,\s*(\w+)\s*\)\s*\{([\s\S]*?)\};""").find(html)
    if (fn == null) {
        for (i in 0 until 50_000_000) if (wordAt("$n:$i", 0) ushr (32 - bits) == 0L) return i.toString()
        return null
    }
    val nVar = fn.groupValues[1]
    val body = fn.groupValues[3]
    val cm = Regex("""var\s+(\w+)\s*=\s*String\.fromCharCode\(([\d,\s]+)\)""").find(body) ?: return null
    val saltVar = cm.groupValues[1]
    val salt = cm.groupValues[2].split(',').map { it.trim().toInt().toChar() }.joinToString("")
    val nonceMatch = Regex("""var\s+(\w+)\s*=\s*(?:\w+\.toString\((\d*)\)|String\(\w+\))""").find(body) ?: return null
    val nonceVar = nonceMatch.groupValues[1]
    val radix = nonceMatch.groupValues[2].toIntOrNull()?.coerceIn(2, 36) ?: 10
    val im = Regex("""\(\s*\w+\(([^)]+)\)\[(\d+)\]\s*(>>>|&)\s*(\d+)\s*\)\s*===?\s*0""").find(body) ?: return null
    val parts = im.groupValues[1].split('+').map { it.trim() }
    val wordIdx = im.groupValues[2].toIntOrNull() ?: 0
    val op = im.groupValues[3]
    val rhs = im.groupValues[4].toLongOrNull() ?: return null
    for (i in 0 until 50_000_000) {
        val nonce = java.lang.Long.toString(i.toLong(), radix)
        val msg = buildString {
            for (p in parts) {
                when (p) {
                    nVar -> append(n)
                    saltVar -> append(salt)
                    nonceVar -> append(nonce)
                    else -> append(p.trim('\'', '"'))
                }
            }
        }
        val w = wordAt(msg, wordIdx)
        val ok = if (op == ">>>") (w ushr rhs.toInt()) == 0L else (w and rhs) == 0L
        if (ok) return nonce
    }
    return null
}

/** `nm.php`/`nsub.php` adresine [pichiveGateNonce] ile bulunan `&p=` imzasını ekler; diğer adresler değişmez. */
fun pichiveSign(html: String, url: String): String {
    if (!(url.contains("/nm.php") || url.contains("/nsub.php")) || url.contains("&p=")) return url
    val nonce = pichiveGateNonce(html) ?: return url
    return "$url&p=$nonce"
}

/**
 * Sayfadaki JSON-LD `description` alanını okur; FAQ şemasında "... konusu nedir?" cevabını tercih eder.
 * Meta açıklamaları SEO cümlesi olduğu için içerik özeti gereken yerlerde bu yardımcı kullanılır.
 */
fun Document.jsonLdDescription(): String? {
    val nodes = select("script[type=application/ld+json]").flatMap { element ->
        runCatching {
            val raw = element.data().trim()
            if (raw.startsWith("[")) {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            } else {
                listOf(JSONObject(raw))
            }
        }.getOrDefault(emptyList())
    }.flatMap { it.ldNodes() }
    nodes.firstOrNull { it.optString("name").contains("konusu nedir", ignoreCase = true) }
        ?.optJSONObject("acceptedAnswer")?.optString("text")
        ?.takeIf { it.isNotBlank() }?.let { return cleanJsonLd(it) }
    for (type in listOf("TVSeries", "Movie", "VideoObject", "CreativeWork", "Article")) {
        nodes.firstOrNull {
            it.optString("@type").contains(type, ignoreCase = true) && it.optString("description").isNotBlank()
        }?.let { return cleanJsonLd(it.optString("description")) }
    }
    return nodes.firstOrNull { it.optString("description").isNotBlank() }
        ?.let { cleanJsonLd(it.optString("description")) }
}

private fun JSONObject.ldNodes(): List<JSONObject> = buildList {
    add(this@ldNodes)
    for (key in listOf("@graph", "mainEntity")) {
        when (val child = opt(key)) {
            is JSONObject -> addAll(child.ldNodes())
            is JSONArray -> for (i in 0 until child.length()) {
                child.optJSONObject(i)?.let { addAll(it.ldNodes()) }
            }
        }
    }
}

private fun cleanJsonLd(raw: String): String = Jsoup.parse(raw).text().trim()

/** rapidvid `av('...')` kaynağı: ters base64 -> K9L kaydırması -> base64. */
fun rapidVidAvDecode(value: String): String? = runCatching {
    val first = String(
        android.util.Base64.decode(value.reversed(), android.util.Base64.DEFAULT),
        Charsets.ISO_8859_1,
    )
    String(android.util.Base64.decode(rapidVidShift(first), android.util.Base64.DEFAULT), Charsets.UTF_8)
}.getOrNull()

internal fun rapidVidShift(value: String): String = buildString(value.length) {
    val salt = "K9L"
    value.forEachIndexed { index, char ->
        append((char.code - (salt[index % salt.length].code % 5 + 1)).toChar())
    }
}
