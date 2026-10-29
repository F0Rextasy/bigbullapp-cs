package com.sezonlukdizi

import android.util.Base64
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** SezonlukDizi — sezonlukdizi.cc (yabancı diziler, altyazı/dublaj) */
class SezonlukdiziProvider : MainAPI() {
    override var mainUrl = "https://sezonlukdizi.cc"
    override var name = "SezonlukDizi"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val ajaxHeaders get() = mapOf(
        "User-Agent" to ua,
        "X-Requested-With" to "XMLHttpRequest",
    )

    override val mainPage get() = ROWS.mapNotNull { name ->
        if (!Settings.rowEnabled(name)) return@mapNotNull null
        when (name) {
            "Son Eklenenler" -> MainPageData(name, "$mainUrl/diziler.asp?siralama_tipi=id")
            "IMDb" -> MainPageData(name, "$mainUrl/diziler.asp")
            "Bilim Kurgu" -> MainPageData(name, "$mainUrl/diziler.asp?tur=bilimkurgu")
            "Korku" -> MainPageData(name, "$mainUrl/diziler.asp?tur=korku")
            "Aksiyon" -> MainPageData(name, "$mainUrl/diziler.asp?tur=aksiyon")
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val separator = if (request.data.contains('?')) "&" else "?"
        val doc = app.get("${request.data}${separator}s=$page").document
        val items = doc.select("div.ui.segment.bottom.attached.afis a[href]").mapNotNull { anchor ->
            val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            if (!href.contains("/diziler/")) return@mapNotNull null
            val title = anchor.attr("title").substringBefore(" izle").trim()
                .ifBlank { anchor.selectFirst("img")?.attr("alt").orEmpty().trim() }
            if (title.isBlank()) return@mapNotNull null
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = fixUrlNull(anchor.selectFirst("img")?.attr("data-src"))
                year = anchor.selectFirst("span.year")?.text()?.trim()?.toIntOrNull()
            }
        }.distinctBy { it.url }
        // Paginator ends with ">>"; on the last page it is rendered .disabled.
        // Past the last page the site serves zero cards but keeps ">>" enabled.
        val hasNext = items.isNotEmpty() && doc.select("#filtresayfalama a.item").lastOrNull()
            ?.let { !it.hasClass("disabled") && it.text().contains(">") } == true
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val body = runCatching {
            app.post(
                "$mainUrl/ajax/arama.asp",
                data = mapOf("q" to query),
                headers = ajaxHeaders,
                referer = "$mainUrl/",
            ).text
        }.getOrNull() ?: return emptyList()

        val results = runCatching {
            JSONObject(body).optJSONObject("results")?.optJSONObject("diziler")?.optJSONArray("results")
        }.getOrNull() ?: return emptyList()

        return (0 until results.length()).mapNotNull { index ->
            val item = results.optJSONObject(index) ?: return@mapNotNull null
            val href = fixUrlNull(item.optString("url")) ?: return@mapNotNull null
            if (!href.contains("/diziler/")) return@mapNotNull null
            val title = item.optString("title").trim()
            if (title.isBlank()) return@mapNotNull null
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = fixUrlNull(item.optString("image"))
                year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.get(1)?.toIntOrNull()
                score = item.optDouble("imdb").takeIf { it > 0 }?.let { Score.from10(it) }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val title = doc.selectFirst("#dizibilgisi .card .content .header")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" izle")?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")

        val slug = url.substringAfterLast("/").removeSuffix(".html")
        val episodeDoc = runCatching { app.get("$mainUrl/bolumler/$slug.html").document }.getOrNull()
            ?: throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")
        val episodes = episodeDoc.select("div.bolumler table tbody tr").mapNotNull { row ->
            val cells = row.select("td")
            val href = cells.getOrNull(2)?.selectFirst("a[href]")?.attr("href")?.let { fixUrlNull(it) }
                ?: return@mapNotNull null
            val season = cells.getOrNull(1)?.text()
                ?.let { Regex("""(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 1
            val number = cells.getOrNull(2)?.text()
                ?.let { Regex("""(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            val label = cells.getOrNull(3)?.text()?.trim().orEmpty()
            newEpisode(href) {
                this.season = season
                this.episode = number
                this.name = label.ifBlank { "Bölüm ${number ?: "?"}" }
            }
        }.distinctBy { it.data }

        if (episodes.isEmpty()) throw ErrorLoadingException("$name: içerik yüklenemedi. $REPORT_HINT")

        val cast = runCatching { app.get("$mainUrl/oyuncular/$slug.html").document }.getOrNull()
            ?.select("#dizidetay .card")
            ?.mapNotNull { card ->
                val actorName = card.selectFirst(".header")?.text()?.trim() ?: return@mapNotNull null
                ActorData(Actor(actorName, fixUrlNull(card.selectFirst("img")?.attr("src"))))
            }
            .orEmpty()

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
            this.plot = doc.selectFirst("#dizidetay div[data-tab=konu] p")?.text()?.trim()
            this.year = doc.selectFirst("#dizibilgisi .extra.content .right.floated")?.text()?.trim()?.toIntOrNull()
            this.tags = doc.select("#dizibilgisi a[href^='/diziler.asp?tur=']")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            this.duration = doc.selectFirst("#dizibilgisi span.ui.orange.label")?.text()
                ?.let { Regex("""(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            this.score = doc.selectFirst("#dizidetay a.ui.label.imdb .detail")?.text()?.trim()
                ?.replace(',', '.')?.toDoubleOrNull()?.let { Score.from10(it) }
            this.actors = cast
            doc.selectFirst("div.fragmanizle[data-yt]")?.attr("data-yt")
                ?.takeIf { it.isNotBlank() }
                ?.let { addTrailer("https://www.youtube.com/embed/$it") }
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
        val doc = runCatching { app.get(data).document }
            .getOrNull() ?: throw ErrorLoadingException("$name: kaynak bulunamadı. $REPORT_HINT")
        val sourceId = doc.selectFirst("#dilsec")?.attr("data-id")?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: kaynak bulunamadı. $REPORT_HINT")

        var found = false
        val seenEmbeds = HashSet<String>()
        // 1 = altyazı, 0 = dublaj; the episode lists both, so both are emitted
        for (audio in listOf(1, 0)) {
            val audioLabel = if (audio == 1) "Altyazı" else "Dublaj"
            val alternates = runCatching {
                JSONObject(
                    app.post(
                        "$mainUrl/ajax/dataAlternatif22.asp",
                        data = mapOf("bid" to sourceId, "dil" to audio.toString()),
                        headers = ajaxHeaders,
                        referer = data,
                    ).text
                ).optJSONArray("data")
            }.getOrNull() ?: continue

            for (index in 0 until alternates.length()) {
                val id = alternates.optJSONObject(index)?.optInt("id") ?: continue
                val html = runCatching {
                    app.post(
                        "$mainUrl/ajax/dataEmbed22.asp",
                        data = mapOf("id" to id.toString()),
                        headers = ajaxHeaders,
                        referer = data,
                    ).text
                }.getOrNull() ?: continue

                val embed = Regex("""src="([^"]+)"""").find(html)?.groupValues?.get(1) ?: continue
                // Relative src = the site's Cloudflare Turnstile gate; not solvable outside a browser
                if (embed.startsWith("/")) continue
                val embedUrl = if (embed.startsWith("//")) "https:$embed" else embed
                if (!seenEmbeds.add(embedUrl)) continue

                var ok = false
                if (Regex("""/e/[A-Za-z0-9]+/?$""").containsMatchIn(embedUrl)) {
                    ok = loadFilemoon(embedUrl, audioLabel, linkCallback, subtitleFilter)
                }
                if (!ok) {
                    ok = loadExtractor(embedUrl, "$mainUrl/", subtitleFilter, linkCallback)
                }
                if (ok) found = true
            }
        }
        if (!found) throw ErrorLoadingException("$name: oynatılabilir akış yok. $REPORT_HINT")
        return found
    }

    private suspend fun postJson(
        url: String,
        body: JSONObject,
        headers: Map<String, String>,
        referer: String,
    ): JSONObject? = runCatching {
        JSONObject(app.post(url, json = body, headers = headers, referer = referer).text)
    }.getOrNull()

    /**
     * Newer Filemoon frontend ("Byse"): the embed is a JS app whose player needs an
     * ECDSA-attested viewer token, a proof-of-work token and an AES-256-GCM playback blob.
     * All of that is plain HTTP + crypto, so it can be reproduced without a browser.
     */
    private suspend fun loadFilemoon(
        embedUrl: String,
        audioLabel: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit,
    ): Boolean {
        val code = embedUrl.trimEnd('/').substringAfterLast('/')
        val embedOrigin = Regex("""^(https?://[^/]+)""").find(embedUrl)?.groupValues?.get(1) ?: return false
        val headers = mapOf(
            "User-Agent" to ua,
            "Accept" to "*/*",
            "Referer" to "$mainUrl/",
            "x-embed-origin" to "sezonlukdizi.cc",
            "x-embed-parent" to embedUrl,
            "x-embed-referer" to "$mainUrl/",
        )

        val details = runCatching {
            JSONObject(app.get("$embedOrigin/api/videos/$code/embed/details", headers = headers, referer = "$mainUrl/").text)
        }.getOrNull() ?: return false
        val apiOrigin = Regex("""^(https?://[^/]+)""")
            .find(details.optString("embed_frame_url"))
            ?.groupValues?.get(1) ?: embedOrigin
        val api = "$apiOrigin/api/videos/$code/embed"

        val challenge = postJson("$apiOrigin/api/videos/access/challenge", JSONObject(), headers, "$mainUrl/")
            ?: return false
        val attestation = attest(apiOrigin, challenge, headers) ?: return false

        val captcha = postJson("$api/captcha", JSONObject(), headers, "$mainUrl/") ?: return false
        val solution = solvePow(captcha.optString("pow_nonce"), captcha.optInt("pow_difficulty"))
            ?: return false
        val verified = postJson(
            "$api/captcha/verify",
            JSONObject().put("pow_token", captcha.optString("pow_token")).put("solution", solution),
            headers,
            "$mainUrl/",
        ) ?: return false
        if (verified.optString("status") != "ok") return false

        val playback = postJson(
            "$api/playback",
            JSONObject().put("fingerprint", attestation),
            headers + mapOf("X-Captcha-Token" to verified.optString("token")),
            "$mainUrl/",
        )?.optJSONObject("playback") ?: return false

        val payload = decryptPlayback(playback) ?: return false
        val sources = payload.optJSONArray("sources") ?: return false
        val trackLabel = audioLabel.let { if (it.isBlank()) "" else " $it" }
        var found = false
        for (index in 0 until sources.length()) {
            val source = sources.optJSONObject(index) ?: continue
            val link = source.optString("url").takeIf { it.isNotBlank() } ?: continue
            val label = source.optString("label").ifBlank {
                source.optInt("height").takeIf { it > 0 }?.let { "${it}p" }.orEmpty()
            }
            callback.invoke(
                newExtractorLink(
                    name,
                    "Filemoon$trackLabel${if (label.isBlank()) "" else " $label"}",
                    link,
                    if (source.optString("mime_type").contains("mpegurl")) ExtractorLinkType.M3U8 else INFER_TYPE,
                ) {
                    this.referer = "$apiOrigin/"
                    this.quality = getQualityFromName(label.uppercase())
                }
            )
            found = true
        }

        // The same payload carries the player's subtitle tracks next to the video sources.
        val tracks = payload.optJSONArray("tracks")
        if (tracks != null) {
            for (index in 0 until tracks.length()) {
                val track = tracks.optJSONObject(index) ?: continue
                val trackUrl = track.optString("url").takeIf { it.isNotBlank() } ?: continue
                val language = track.optString("title").ifBlank { track.optString("language") }
                subtitleCallback.invoke(
                    newSubtitleFile(language.ifBlank { "Türkçe" }, trackUrl) {
                        this.headers = mapOf("Referer" to "$apiOrigin/")
                    }
                )
            }
        }
        return found
    }

    private suspend fun attest(
        apiOrigin: String,
        challenge: JSONObject,
        headers: Map<String, String>,
    ): JSONObject? {
        val nonce = challenge.optString("nonce").takeIf { it.isNotBlank() } ?: return null
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val signer = Signature.getInstance("SHA256withECDSA").apply {
            initSign(keyPair.private)
            update(nonce.toByteArray(Charsets.UTF_8))
        }
        val signature = derToRaw(signer.sign())
        val publicKey = keyPair.public as ECPublicKey

        val client = JSONObject()
            .put("user_agent", ua)
            .put("architecture", "x86")
            .put("bitness", "64")
            .put("platform", "Windows")
            .put("platform_version", "10.0.0")
            .put("model", "")
            .put("ua_full_version", "124.0.0.0")
            .put("brand_full_versions", JSONArray())
            .put("pixel_ratio", 1)
            .put("screen_width", 1920)
            .put("screen_height", 1080)
            .put("color_depth", 24)
            .put("languages", JSONArray(listOf("tr-TR", "tr")))
            .put("timezone", "Europe/Istanbul")
            .put("hardware_concurrency", Runtime.getRuntime().availableProcessors())
            .put("device_memory", 8)
            .put("touch_points", 0)
            .put("webgl_vendor", "Google Inc. (NVIDIA)")
            .put(
                "webgl_renderer",
                "ANGLE (NVIDIA, NVIDIA GeForce GTX 1050 Direct3D11 vs_5_0 ps_5_0, D3D11)",
            )
            .put("canvas_hash", randomBase64Url(32))
            .put("audio_hash", randomBase64Url(32))
            .put("webgl_params_hash", randomBase64Url(32))
            .put("fonts_hash", randomBase64Url(32))
            .put("codecs_hash", randomBase64Url(32))
            .put("media_devices", "ai0ao1vi0")
            .put("pointer_type", "fine,hover")
            .put(
                "extra",
                JSONObject().put("vendor", "Google Inc.").put("appVersion", ua.removePrefix("Mozilla/")),
            )

        val body = JSONObject()
            .put("viewer_id", randomBase64Url(16))
            .put("device_id", randomBase64Url(16))
            .put("challenge_id", challenge.optString("challenge_id"))
            .put("nonce", nonce)
            .put("signature", base64Url(signature))
            .put(
                "public_key",
                JSONObject()
                    .put("crv", "P-256")
                    .put("ext", true)
                    .put("key_ops", JSONArray(listOf("verify")))
                    .put("kty", "EC")
                    .put("x", base64Url(publicKey.w.affineX.toFixed32()))
                    .put("y", base64Url(publicKey.w.affineY.toFixed32())),
            )
            .put("client", client)
            .put(
                "storage",
                JSONObject()
                    .put("cookie", "")
                    .put("local_storage", "")
                    .put("indexed_db", "")
                    .put("cache_storage", ""),
            )
            .put("attributes", JSONObject().put("entropy", "medium"))

        val response = postJson("$apiOrigin/api/videos/access/attest", body, headers, "$mainUrl/")
            ?: return null

        // The token is a signed JWT-ish blob whose payload carries the ids the server assigned
        val claims = response.optString("token").substringBefore('.').let {
            runCatching { JSONObject(String(base64UrlDecode(it), Charsets.UTF_8)) }.getOrNull()
        }
        val payload = claims ?: JSONObject()
            .put("viewer_id", response.optString("viewer_id"))
            .put("device_id", response.optString("device_id"))
            .put("confidence", response.optDouble("confidence"))

        return JSONObject()
            .put("token", response.optString("token"))
            .put("viewer_id", payload.optString("viewer_id"))
            .put("device_id", payload.optString("device_id"))
            .put("confidence", payload.optDouble("confidence", 1.0))
    }

    private fun decryptPlayback(playback: JSONObject): JSONObject? {
        val parts = playback.optJSONArray("key_parts") ?: return null
        val version = playback.optString("version").toIntOrNull() ?: 1
        val first = (version - 1).coerceIn(0, parts.length() - 1)
        val second = (30 - version).coerceIn(0, parts.length() - 1)
        val key = base64UrlDecode(parts.getString(first)) + base64UrlDecode(parts.getString(second))
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    GCMParameterSpec(128, base64UrlDecode(playback.getString("iv"))),
                )
            }
            val plain = cipher.doFinal(base64UrlDecode(playback.getString("payload")))
            JSONObject(String(plain, Charsets.UTF_8))
        }.getOrNull()
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun base64UrlDecode(value: String): ByteArray = Base64.decode(
        value.replace('-', '+').replace('_', '/'),
        Base64.NO_WRAP or Base64.NO_PADDING,
    )

    private fun randomBase64Url(size: Int): String =
        ByteArray(size).also { SecureRandom().nextBytes(it) }.let(::base64Url)

    private fun BigInteger.toFixed32(): ByteArray {
        val bytes = toByteArray()
        return when {
            bytes.size == 32 -> bytes
            bytes.size > 32 -> bytes.copyOfRange(bytes.size - 32, bytes.size)
            else -> ByteArray(32 - bytes.size) + bytes
        }
    }

    /** WebCrypto emits raw r||s; JCA emits DER. */
    private fun derToRaw(der: ByteArray): ByteArray {
        if (der.size < 8 || der[0] != 0x30.toByte()) return der
        var offset = 1
        offset += if (der[offset].toInt() and 0x80 != 0) 1 + (der[offset].toInt() and 0x7f) else 1
        val raw = ByteArray(64)
        for (part in 0..1) {
            if (offset + 1 >= der.size || der[offset] != 0x02.toByte()) return der
            offset++
            var length = der[offset++].toInt() and 0xff
            if (length and 0x80 != 0) {
                val count = length and 0x7f
                length = 0
                repeat(count) { length = (length shl 8) or (der[offset++].toInt() and 0xff) }
            }
            var start = offset
            var size = length
            while (size > 32 && der[start] == 0.toByte()) {
                start++
                size--
            }
            val copied = size.coerceAtMost(32)
            System.arraycopy(der, start + size - copied, raw, part * 32 + 32 - copied, copied)
            offset += length
        }
        return raw
    }

    /**
     * The site labels the proof-of-work "sha256-leading-zero-bits", but it is a custom
     * sponge hash; ported 1:1 from the player bundle. Input is "<nonce>:<counter>".
     */
    private fun solvePow(nonce: String, difficulty: Int): String? {
        if (nonce.isBlank()) return null
        if (difficulty <= 0) return "0"
        val prefix = (nonce + ":").toByteArray(Charsets.US_ASCII)
        val state = IntArray(4)
        val lanes = IntArray(512)
        val digest = IntArray(8)
        var attempt = 0
        while (attempt < MAX_POW_ATTEMPTS) {
            val digits = attempt.toString().toByteArray(Charsets.US_ASCII)
            val input = ByteArray(prefix.size + digits.size)
            System.arraycopy(prefix, 0, input, 0, prefix.size)
            System.arraycopy(digits, 0, input, prefix.size, digits.size)
            powDigest(input, state, lanes, digest)
            if (leadingZeroBits(digest) >= difficulty) return attempt.toString()
            attempt++
        }
        return null
    }

    private fun powDigest(input: ByteArray, state: IntArray, lanes: IntArray, out: IntArray) {
        state[0] = 1779033703
        state[1] = 3144134277.toInt()
        state[2] = 1013904242
        state[3] = 2773480762.toInt()
        for (byte in input) {
            state[0] += byte.toInt() and 0xff
            state[0] = Integer.rotateLeft(state[0], 7)
            mix(state)
        }
        repeat(8) { mix(state) }
        for (index in 0 until 512) {
            mix(state)
            lanes[index] = state[0] xor state[2]
        }
        repeat(2) {
            for (slot in 0 until 512) {
                var value = lanes[slot] + lanes[lanes[slot] and 511]
                value = Integer.rotateLeft(value, 13)
                value = value xor (lanes[(slot + 1) and 511] * 2654435761.toInt())
                lanes[slot] = value
                state[0] = state[0] xor value
                mix(state)
            }
        }
        for (index in 0 until 8) {
            mix(state)
            var value = state[0]
            for (offset in 0 until 64) {
                val lane = lanes[index * 64 + offset]
                value += lane
                value = Integer.rotateLeft(value, 5)
                value = value xor (lane * 2246822519.toInt())
            }
            out[index] = value xor state[2]
        }
    }

    private fun mix(state: IntArray) {
        state[0] += state[1]
        state[3] = Integer.rotateLeft(state[3] xor state[0], 16)
        state[2] += state[3]
        state[1] = Integer.rotateLeft(state[1] xor state[2], 12)
        state[0] += state[1]
        state[3] = Integer.rotateLeft(state[3] xor state[0], 8)
        state[2] += state[3]
        state[1] = Integer.rotateLeft(state[1] xor state[2], 7)
    }

    private fun leadingZeroBits(digest: IntArray): Int {
        var total = 0
        for (word in digest) {
            if (word == 0) {
                total += 32
                continue
            }
            return total + Integer.numberOfLeadingZeros(word)
        }
        return total
    }

    companion object {
        /** Homepage row labels, in display order; each maps to a live genre filter above. */
        val ROWS = listOf("Son Eklenenler", "IMDb", "Bilim Kurgu", "Korku", "Aksiyon")
        const val MAX_POW_ATTEMPTS = 4_000_000
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}
