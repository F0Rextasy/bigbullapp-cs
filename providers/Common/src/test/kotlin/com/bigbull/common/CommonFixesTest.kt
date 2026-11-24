package com.bigbull.common

import android.content.SharedPreferences
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.Qualities
import java.lang.reflect.Proxy
import java.util.Base64
import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommonFixesTest {
    private fun inMemorySettings(): BaseSettings {
        val store = mutableMapOf<String, Any?>()
        val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "putInt" -> {
                    store[args[0] as String] = args[1]
                    proxy
                }
                "putString" -> {
                    store[args[0] as String] = args[1]
                    proxy
                }
                "putStringSet" -> {
                    store[args[0] as String] = args[1]
                    proxy
                }
                "remove" -> {
                    store.remove(args[0] as String)
                    proxy
                }
                "clear" -> {
                    store.clear()
                    proxy
                }
                "commit" -> true
                "apply" -> Unit
                else -> proxy
            }
        } as SharedPreferences.Editor

        val prefs = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getInt" -> (store[args[0] as String] as? Int) ?: (args[1] as Int)
                "getString" -> (store[args[0] as String] as? String) ?: (args[1] as? String)
                "getStringSet" -> (store[args[0] as String] as? Set<*>) ?: args[1]
                "contains" -> store.containsKey(args[0] as String)
                "edit" -> editor
                else -> null
            }
        } as SharedPreferences

        val settings = BaseSettings("test_prefs")
        BaseSettings::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(settings, prefs)
        return settings
    }

    @Suppress("DEPRECATION")
    @Test
    fun baseSettingsFilterLinksAndSubtitlesRespectCaps() {
        val settings = inMemorySettings()
        settings.qualityCap = 720
        assertEquals(false, settings.qualityAllowed(Qualities.P1080.value))
        assertTrue(settings.qualityAllowed(Qualities.P720.value))
        assertTrue(settings.qualityAllowed(Qualities.Unknown.value))
        settings.qualityCap = 0
        assertTrue(settings.qualityAllowed(Qualities.P1080.value))

        settings.subtitleLang = "tr"
        val emittedSubs = mutableListOf<String>()
        val wrappedSub = settings.filterSubtitles { emittedSubs += it.lang }
        wrappedSub(SubtitleFile("Türkçe", "https://cdn.example.com/tr.vtt"))
        wrappedSub(SubtitleFile("English", "https://cdn.example.com/en.vtt"))
        assertEquals(listOf("Türkçe"), emittedSubs)

        settings.subtitleLang = "off"
        emittedSubs.clear()
        wrappedSub(SubtitleFile("Türkçe", "https://cdn.example.com/tr.vtt"))
        assertTrue(emittedSubs.isEmpty())
    }

    @Test
    fun pichiveSignUsesQuestionMarkAndPreservesExistingP() {
        val html = "<html></html>"
        assertEquals(
            "https://cdn.pichive.online/nm.php?p=99",
            pichiveSign(html, "https://cdn.pichive.online/nm.php", precomputedNonce = "99"),
        )
        assertEquals(
            "https://cdn.pichive.online/nsub.php?v=abc&p=99",
            pichiveSign(html, "https://cdn.pichive.online/nsub.php?v=abc", precomputedNonce = "99"),
        )
        assertEquals(
            "https://cdn.pichive.online/nm.php?p=12",
            pichiveSign(html, "https://cdn.pichive.online/nm.php?p=12", precomputedNonce = "99"),
        )
        assertEquals(
            "https://cdn.pichive.online/master.m3u8",
            pichiveSign(html, "https://cdn.pichive.online/master.m3u8", precomputedNonce = "99"),
        )
    }

    @Test
    fun extractYearAvoidsBodyUploadNumbersAndReadsTitleOrJsonLd() {
        val bodyNoiseDoc = Jsoup.parse(
            """<html><body><img src="/uploads/2025/01/poster.jpg"><p>2025 güncellendi</p></body></html>"""
        )
        assertNull(bodyNoiseDoc.extractYear("Matrix"))
        assertEquals(1999, bodyNoiseDoc.extractYear("The Matrix (1999)"))

        val ldDoc = Jsoup.parse(
            """<html><head><script type="application/ld+json">{"@type":"Movie","datePublished":"2014-11-07"}</script></head><body>/2025/</body></html>"""
        )
        assertEquals(2014, ldDoc.extractYear("Interstellar"))
    }

    @Test
    fun rapidVidAvDecodeWorksOnPureJvmWithoutAndroidBase64() {
        val target = "https://cdn.rapidvid.test/hls/master.m3u8"
        val innerB64 = Base64.getEncoder().encodeToString(target.toByteArray(Charsets.UTF_8))
        val salt = "K9L"
        val unshifted = buildString(innerB64.length) {
            innerB64.forEachIndexed { idx, ch ->
                append((ch.code + (salt[idx % salt.length].code % 5 + 1)).toChar())
            }
        }
        val encoded = Base64.getEncoder().encodeToString(unshifted.toByteArray(Charsets.ISO_8859_1)).reversed()

        assertEquals(target, rapidVidAvDecode(encoded))
        assertEquals(target, decodeRapidVidAv("""av('$encoded');"""))

        val p8Json = """{"cm":"https://cdn.example/master.m3u8","tm":"https://cdn.example/alt.m3u8","ct":[{"label":"Türkçe","file":"https://subs.example/1.vtt"}]}"""
        val p8Inner = Base64.getEncoder().encodeToString(p8Json.toByteArray(Charsets.UTF_8))
        val p8Unshifted = buildString(p8Inner.length) {
            p8Inner.forEachIndexed { idx, ch ->
                append((ch.code + (salt[idx % salt.length].code % 5 + 1)).toChar())
            }
        }
        val p8Encoded = Base64.getEncoder().encodeToString(p8Unshifted.toByteArray(Charsets.ISO_8859_1)).reversed()
        val p8Decoded = org.json.JSONObject(rapidVidAvDecode(p8Encoded)!!)
        assertEquals("https://cdn.example/master.m3u8", p8Decoded.optString("cm"))
        assertEquals("https://subs.example/1.vtt", p8Decoded.optJSONArray("ct").optJSONObject(0).optString("file"))
    }

    @Test
    fun uriOriginAndResolveCandidateUrlAvoidNullSchemeAndKeepPort() {
        assertEquals("https://cdn.example.com:8443", uriOrigin("https://cdn.example.com:8443/path/a.html"))
        assertNull(uriOrigin("/relative/only"))
        assertNull(uriOrigin("invalid url"))

        val out = linkedSetOf<String>()
        resolveCandidateUrl("/embed/123", "https://site.test:8080/watch", out)
        resolveCandidateUrl("/embed/456", "/bad/relative/base", out)
        assertEquals(setOf("https://site.test:8080/embed/123"), out)
    }

    @Test
    fun openPlayerExtractorsDecodeStreamTapeMixdropVoePixeldrainAndDood() {
        val stHtml = """<div id="norobotlink"></div><script>document.getElementById('norobotlink').innerHTML = '//streamtape.com/get_video?id=' + ('xxxxabc123&token=tok').substring(4);</script>"""
        assertEquals("https://streamtape.com/get_video?id=abc123&token=tok", extractStreamTapeUrl(stHtml))

        val mdHtml = """<script>MDCore.wurl="//cdn.mixdrop.ag/v/demo.mp4?s=1";</script>"""
        assertEquals("https://cdn.mixdrop.ag/v/demo.mp4?s=1", extractMixdropUrl(mdHtml))

        val voeB64 = Base64.getEncoder().encodeToString("https://cdn.voe.test/master.m3u8".toByteArray(Charsets.UTF_8))
        assertEquals("https://cdn.voe.test/master.m3u8", extractVoeUrl("""var sources = {'hls': '$voeB64'};"""))

        assertEquals("https://pixeldrain.com/api/file/XyZ987", extractPixeldrainUrl("https://pixeldrain.com/u/XyZ987"))

        val doodHtml = """$.get('/pass_md5/12345-hash/xyz', function(data){ return data + '?token=tok99&expiry='; });"""
        assertEquals("/pass_md5/12345-hash/xyz" to "tok99", extractDoodPassMd5Path(doodHtml))
    }

    @Test
    fun okRuHlsUrlPrefersFailoverHostAndFallsBackToRaw() {
        val withFailover = JSONObject("""{"hlsManifestUrl":"https://cdn1.okcdn.ru/hls/master.m3u8?k=1","failoverHosts":["cdn2.okcdn.ru"]}""")
        assertEquals("https://cdn2.okcdn.ru/hls/master.m3u8?k=1", okRuHlsUrl(withFailover))

        val withoutFailover = JSONObject("""{"hlsManifestUrl":"https://cdn1.okcdn.ru/hls/master.m3u8?k=1","failoverHosts":[]}""")
        assertEquals("https://cdn1.okcdn.ru/hls/master.m3u8?k=1", okRuHlsUrl(withoutFailover))

        assertNull(okRuHlsUrl(JSONObject("""{"failoverHosts":["cdn2.okcdn.ru"]}""")))
    }
}
