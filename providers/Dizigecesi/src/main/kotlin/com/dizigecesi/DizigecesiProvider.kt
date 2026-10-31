package com.dizigecesi

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
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URI
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Dizigecesi — dizigecesi.com film arşivi.
 *
 * Detaydaki `.btn-service[data-embed]` sunucuları `/ajax/embed` ile bir
 * `dizigecesi.com/video/<hash>` sarmalayıcısı döner; sarmalayıcıdaki gerçek
 * iframe (Filemoon/Vidmoly/UPNS/P2P) core extractor'a verilir.
 */
class DizigecesiProvider : MainAPI() {
    override var mainUrl = "https://dizigecesi.com"
    override var name = "Dizigecesi"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9",
    )

    override val mainPage get() = listOf(
        MainPageData("Filmler", "$mainUrl/filmler"),
    )

    private fun Element.toCard(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        if (!href.contains("/film/")) return null
        val title = selectFirst(".card-series__content-title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = fixUrlNull(selectFirst("img")?.attr("src"))
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
        val doc = app.get(request.data, headers = headers).document
        return newHomePageResponse(request.name, doc.select("a.card-series[href*=/film/]").mapNotNull { it.toCard() }, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/arama/${query.trim()}", headers = headers).document
        return doc.select("a.card-series[href*=/film/]").mapNotNull { it.toCard() }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = fixUrl(url)
        val doc = app.get(pageUrl, headers = headers).document
        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" İzle")?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("$name: başlık okunamadı. $REPORT_HINT")
        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?.takeIf { it.isNotEmpty() }
        return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageUrl = fixUrl(data)
        val page = app.get(pageUrl, headers = headers).document
        val ids = page.select("[data-embed]").mapNotNull { it.attr("data-embed").takeIf(String::isNotBlank) }
            .distinct()
        if (ids.isEmpty()) throw ErrorLoadingException("$name: oynatıcı kaynağı bulunamadı. $REPORT_HINT")

        var found = false
        for (id in ids) {
            val embed = app.post(
                "$mainUrl/ajax/embed",
                data = mapOf("id" to id),
                headers = headers,
                referer = pageUrl,
            ).text
            val wrapper = Regex("""<iframe[^>]+src=["']([^"']+)""")
                .find(embed)?.groupValues?.get(1)?.let(::fixUrl) ?: continue
            val wrapperHtml = runCatching { app.get(wrapper, headers = headers, referer = pageUrl).text }
                .getOrNull() ?: continue
            val inner = Regex("""<iframe[^>]+src=["']([^"']+)""")
                .find(wrapperHtml)?.groupValues?.get(1)?.let(::fixUrl) ?: wrapper
            val handled = if (inner.contains("filemoon")) {
                resolveFilemoon(inner, pageUrl, subtitleCallback, callback)
            } else {
                loadExtractor(inner, pageUrl, subtitleCallback, callback)
            }
            if (handled) found = true
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir akış yok. $REPORT_HINT")
        return true
    }

    private suspend fun resolveFilemoon(
        embed: String,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val code = Regex("""/e/([A-Za-z0-9]+)""").find(embed)?.groupValues?.get(1) ?: return false
        val fmBase = "https://${fmHost(embed)}"
        val details = runCatching {
            JSONObject(
                app.get(
                    "$fmBase/api/videos/$code/embed/details",
                    headers = fmHeaders(pageUrl, embed, embed, fmBase),
                ).text,
            )
        }.getOrNull() ?: return false
        val frame = details.optString("embed_frame_url").takeIf { it.startsWith("http") } ?: return false
        val frameBase = "https://${fmHost(frame)}"
        val frameHeaders = fmHeaders(pageUrl, embed, frame, frameBase)
        val settings = runCatching {
            JSONObject(app.get("$frameBase/api/videos/$code/embed/settings", headers = frameHeaders).text)
        }.getOrNull() ?: return false

        val fingerprint = if (settings.optBoolean("captcha_required", false)) {
            fmAttest(frameBase, frameHeaders) ?: return false
        } else {
            null
        }
        val body = if (fingerprint == null) JSONObject() else JSONObject().put("fingerprint", fingerprint)
        val captcha = runCatching {
            JSONObject(app.post("$frameBase/api/videos/$code/embed/captcha", json = body, headers = frameHeaders).text)
        }.getOrNull() ?: return false
        val solution = fmSolvePow(captcha.optString("pow_nonce"), captcha.optInt("pow_difficulty")) ?: return false
        val verifyBody = JSONObject().put("pow_token", captcha.optString("pow_token")).put("solution", solution)
        fingerprint?.let { verifyBody.put("fingerprint", it) }
        val verify = runCatching {
            JSONObject(
                app.post(
                    "$frameBase/api/videos/$code/embed/captcha/verify",
                    json = verifyBody,
                    headers = frameHeaders,
                ).text,
            )
        }.getOrNull() ?: return false
        if (verify.optString("status") != "ok") return false
        val captchaToken = verify.optString("token")
        val playbackBody = if (fingerprint == null) JSONObject() else JSONObject().put("fingerprint", fingerprint)
        val playback = runCatching {
            JSONObject(
                app.post(
                    "$frameBase/api/videos/$code/embed/playback",
                    json = playbackBody,
                    headers = frameHeaders + mapOf("X-Captcha-Token" to captchaToken),
                ).text,
            ).optJSONObject("playback")
        }.getOrNull() ?: return false
        val plain = fmDecrypt(playback) ?: return false
        val sources = plain.optJSONArray("sources") ?: return false
        var found = false
        for (index in 0 until sources.length()) {
            val item = sources.optJSONObject(index) ?: continue
            val stream = item.optString("url")
            if (!stream.startsWith("http")) continue
            val label = item.optString("label")
            val mime = item.optString("mime_type")
            val type = if (stream.contains(".m3u8") || mime.contains("mpegurl")) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }
            val quality = Regex("""(\d{3,4})p""").find(label)?.groupValues?.get(1)?.toIntOrNull()
                ?: Qualities.Unknown.value
            if (!Settings.qualityAllowed(quality)) continue
             callback(
                newExtractorLink(name, "Filemoon ${label}".trim(), stream, type) {
                    this.referer = frame
                    this.quality = quality
                    this.headers = headers
                },
            )
            found = true
        }
        return found
    }

    private fun fmHeaders(pageUrl: String, parent: String, referer: String, origin: String) = headers + mapOf(
        "Accept" to "application/json, text/plain, */*",
        "Origin" to origin,
        "Referer" to referer,
        "X-Embed-Origin" to fmHost(pageUrl),
        "X-Embed-Referer" to pageUrl,
        "X-Embed-Parent" to parent,
    )

    private suspend fun fmAttest(base: String, requestHeaders: Map<String, String>): JSONObject? {
        val challenge = runCatching {
            JSONObject(
                app.post(
                    "$base/api/videos/access/challenge",
                    json = JSONObject(),
                    headers = requestHeaders,
                ).text,
            )
        }.getOrNull() ?: return null
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val publicKey = keyPair.public as ECPublicKey
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(challenge.optString("nonce").toByteArray())
            fmDerToRaw(sign())
        }
        val jwk = JSONObject()
            .put("kty", "EC")
            .put("crv", "P-256")
            .put("x", fmB64(fmFixed(publicKey.w.affineX.toByteArray())))
            .put("y", fmB64(fmFixed(publicKey.w.affineY.toByteArray())))
        val body = JSONObject()
            .put("viewer_id", "")
            .put("device_id", "")
            .put("challenge_id", challenge.optString("challenge_id"))
            .put("nonce", challenge.optString("nonce"))
            .put("signature", fmB64(signature))
            .put("public_key", jwk)
            .put("client", JSONObject().put("user_agent", headers["User-Agent"]))
            .put("storage", JSONObject())
            .put("attributes", JSONObject().put("entropy", "low"))
        val attest = runCatching {
            JSONObject(app.post("$base/api/videos/access/attest", json = body, headers = requestHeaders).text)
        }.getOrNull() ?: return null
        if (attest.optString("token").isBlank()) return null
        return JSONObject()
            .put("token", attest.optString("token"))
            .put("viewer_id", attest.optString("viewer_id"))
            .put("device_id", attest.optString("device_id"))
            .put("confidence", attest.optDouble("confidence", 0.0))
    }

    private fun fmFixed(value: ByteArray): ByteArray {
        if (value.size == 32) return value
        if (value.size > 32) return value.copyOfRange(value.size - 32, value.size)
        return ByteArray(32 - value.size) + value
    }

    private fun fmDerToRaw(der: ByteArray): ByteArray {
        var pos = 0
        require(der[pos++] == 0x30.toByte())
        val (_, seqStart) = fmReadLength(der, pos)
        pos = seqStart
        val out = ByteArray(64)
        repeat(2) { part ->
            require(der[pos++] == 0x02.toByte())
            val (length, valueStart) = fmReadLength(der, pos)
            pos = valueStart
            var start = pos
            val end = pos + length
            while (start < end && der[start] == 0.toByte()) start++
            val bytes = der.copyOfRange(start, end)
            bytes.copyInto(out, if (part == 0) 32 - bytes.size else 64 - bytes.size)
            pos = end
        }
        return out
    }

    private fun fmReadLength(data: ByteArray, offset: Int): Pair<Int, Int> {
        var pos = offset
        var length = data[pos++].toInt() and 0xff
        if (length and 0x80 != 0) {
            val count = length and 0x7f
            length = 0
            repeat(count) { length = (length shl 8) or (data[pos++].toInt() and 0xff) }
        }
        return length to pos
    }

    private fun fmDecrypt(playback: JSONObject): JSONObject? = runCatching {
        val parts = playback.optJSONArray("key_parts") ?: return@runCatching null
        val version = playback.optString("version").toIntOrNull() ?: 0
        val selected = if (version in 1..20 && version <= parts.length() && 31 - version <= parts.length()) {
            listOf(version, 31 - version)
        } else {
            (1..parts.length()).toList()
        }
        val key = selected.map { fmB64d(parts.getString(it - 1)) }.reduce { acc, bytes -> acc + bytes }
        if (key.size != 32) return@runCatching null
        val iv = fmB64d(playback.optString("iv"))
        val payload = fmB64d(playback.optString("payload"))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        JSONObject(String(cipher.doFinal(payload), Charsets.UTF_8))
    }.getOrNull()

    private fun fmSolvePow(nonce: String, difficulty: Int): String? {
        if (difficulty <= 0) return "0"
        val prefix = "$nonce:"
        val deadline = System.currentTimeMillis() + 25_000
        var index = 0
        while (System.currentTimeMillis() < deadline) {
            repeat(1024) {
                if (fmLeading(fmGr(prefix + index)) >= difficulty) return index.toString()
                index++
            }
        }
        return null
    }

    private fun fmGr(input: String): IntArray {
        val words = input.toByteArray(Charsets.UTF_8)
        val state = intArrayOf(
            0x6A09E667,
            0xBB67AE85.toInt(),
            0x3C6EF372,
            0xA54FF53A.toInt(),
        )
        words.forEach { byte ->
            state[0] += byte.toInt() and 0xff
            state[0] = Integer.rotateLeft(state[0], 7)
            fmYe(state)
        }
        repeat(8) { fmYe(state) }
        val lane = IntArray(512)
        for (index in lane.indices) {
            fmYe(state)
            lane[index] = state[0] xor state[2]
        }
        repeat(2) {
            for (index in lane.indices) {
                val source = lane[index] and 511
                var value = lane[index] + lane[source]
                value = Integer.rotateLeft(value, 13)
                value = value xor (lane[(index + 1) and 511] * -1640531535)
                lane[index] = value
                state[0] = state[0] xor value
                fmYe(state)
            }
        }
        val digest = IntArray(8)
        for (index in 0 until 8) {
            fmYe(state)
            var value = state[0]
            for (offset in 0 until 64) {
                val word = lane[index * 64 + offset]
                value += word
                value = Integer.rotateLeft(value, 5)
                value = value xor (word * -2048144777)
            }
            digest[index] = value xor state[2]
        }
        return digest
    }

    private fun fmYe(state: IntArray) {
        state[0] += state[1]
        state[3] = Integer.rotateLeft(state[3] xor state[0], 16)
        state[2] += state[3]
        state[1] = Integer.rotateLeft(state[1] xor state[2], 12)
        state[0] += state[1]
        state[3] = Integer.rotateLeft(state[3] xor state[0], 8)
        state[2] += state[3]
        state[1] = Integer.rotateLeft(state[1] xor state[2], 7)
    }

    private fun fmLeading(words: IntArray): Int {
        var count = 0
        for (word in words) {
            if (word == 0) {
                count += 32
                continue
            }
            return count + Integer.numberOfLeadingZeros(word)
        }
        return count
    }

    private fun fmHost(url: String): String = runCatching { URI(url).host.orEmpty() }.getOrDefault("")

    private fun fmB64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun fmB64d(value: String): ByteArray =
        Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    companion object {
        val ROWS = listOf("Filmler")
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}
