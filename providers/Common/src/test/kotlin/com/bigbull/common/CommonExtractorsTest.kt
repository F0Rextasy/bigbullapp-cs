package com.bigbull.common

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommonExtractorsTest {
    @Test
    fun decodeVixolityStreamDecodesPayload() {
        val jsonPayload = """{"kaynak":"https:\/\/cdn.vixolity.com\/hls\/master.m3u8"}"""
        val b64 = Base64.getEncoder().encodeToString(jsonPayload.toByteArray(Charsets.UTF_8))
        val shifted = buildString { for (ch in b64) append((ch.code + 3).toChar()) }
        val step1 = Base64.getEncoder().encodeToString(shifted.toByteArray(Charsets.ISO_8859_1)).reversed()
        val hex = step1.toByteArray(Charsets.ISO_8859_1).joinToString("") { "%02x".format(it) }
        val html = """<script>var data="$hex";</script>"""
        assertEquals("https://cdn.vixolity.com/hls/master.m3u8", decodeVixolityStream(html))
    }

    @Test
    fun extractVideoParkStreamSupportsVipAndApollo() {
        val vipHtml = """<script>const VIDEO_ID = "abc123"; const WORKER_URL = "https://w.videopark.top/";</script>"""
        val vip = extractVideoParkStream("https://videopark.top/v/abc123", vipHtml)
        assertNotNull(vip)
        assertEquals("https://w.videopark.top/v/abc123?q=1080", vip!!.url)
        assertEquals("VIP", vip.label)

        val apolloHtml = """<script>const VIDEO_ID = "xyz9";</script>"""
        val apollo = extractVideoParkStream("https://videopark.top/apollo/xyz9", apolloHtml)
        assertNotNull(apollo)
        assertEquals("https://videopark.top/apollo/get_video.php?id=xyz9", apollo!!.url)
        assertTrue(apollo.isM3u8)
    }

    @Test
    fun extractVidmolyStreamsFindsEscapedPlaylist() {
        val html = """sources: [{file:"https:\/\/v1.vidmoly.net\/hls\/play.m3u8?t=1"}]"""
        assertEquals(listOf("https://v1.vidmoly.net/hls/play.m3u8?t=1"), extractVidmolyStreams(html))
    }

    @Test
    fun extractDirectM3u8StreamsSignsPichiveGate() {
        val nonce = "testgate"
        val md = MessageDigest.getInstance("SHA-256")
        var solved = -1
        for (i in 0 until 100_000) {
            val d = md.digest("$nonce:$i".toByteArray(Charsets.UTF_8))
            val w0 = ((d[0].toLong() and 0xff) shl 24) or ((d[1].toLong() and 0xff) shl 16) or
                ((d[2].toLong() and 0xff) shl 8) or (d[3].toLong() and 0xff)
            if (w0 ushr (32 - 8) == 0L) {
                solved = i
                break
            }
        }
        assertTrue(solved >= 0)
        val html = """{"gate":{"n":"$nonce","b":8}} var u="https:\/\/cdn.pichive.online\/nm.php?id=9&e=.m3u8";"""
        assertEquals(
            listOf("https://cdn.pichive.online/nm.php?id=9&e=.m3u8&p=$solved"),
            extractDirectM3u8Streams(html),
        )
    }

    @Test
    fun unpackJsPackerRestoresStreamUrl() {
        val packed = "eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\\\b'+c.toString(a)+'\\\\b','g'),k[c]);return p}('0 1=\"2://3.4/5.6\";',10,7,'var|src|https|cdn|test|master|m3u8'.split('|'),0,{}))"
        val unpacked = unpackJsPacker(packed)
        assertEquals("var src=\"https://cdn.test/master.m3u8\";", unpacked)
    }

    @Test
    fun parseFirePlayerJsonExtractsSecuredLink() {
        val json = """{"hls":true,"videoSource":"https:\/\/cdn.fire.example\/hls\/x.m3u8?t=1"}"""
        val res = parseFirePlayerJson(json)
        assertNotNull(res)
        assertEquals("https://cdn.fire.example/hls/x.m3u8?t=1", res!!.url)
        assertTrue(res.isM3u8)
    }

    @Test
    fun decryptCryptoJsJsonDecodesBePlayerEnvelope() {
        val saltHex = "0102030405060708"
        val ivHex = "101112131415161718191a1b1c1d1e1f"
        val pw = "secretKey"
        val md = MessageDigest.getInstance("MD5")
        val f = md.apply { update(pw.toByteArray(Charsets.UTF_8)); update(saltHex.hexToBytes()) }.digest()
        md.reset()
        val k = (f + md.apply { update(f); update(pw.toByteArray(Charsets.UTF_8)); update(saltHex.hexToBytes()) }.digest()).copyOf(32)
        val ct = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), IvParameterSpec(ivHex.hexToBytes()))
            doFinal("""{"video_location":"https://cdn.beplayer.example/master.m3u8"}""".toByteArray(Charsets.UTF_8))
        }
        val ctB64 = Base64.getEncoder().encodeToString(ct)
        val html = """<script>bePlayer('$pw', '{"ct":"$ctB64","iv":"$ivHex","s":"$saltHex"}');</script>"""
        val dec = decryptCryptoJsJson(html)
        assertNotNull(dec)
        assertTrue(dec!!.contains("https://cdn.beplayer.example/master.m3u8"))
    }

    @Test
    fun decodeRapidVidAvDecodesJvmSafely() {
        val expectedUrl = "https://cdn.rapid.example/hls/test.m3u8"
        val innerB64 = Base64.getEncoder().encodeToString(expectedUrl.toByteArray(Charsets.UTF_8))
        val salt = "K9L"
        val shifted = buildString(innerB64.length) {
            innerB64.forEachIndexed { i, ch -> append((ch.code + (salt[i % salt.length].code % 5 + 1)).toChar()) }
        }
        val outer = Base64.getEncoder().encodeToString(shifted.toByteArray(Charsets.ISO_8859_1)).reversed()
        assertEquals(expectedUrl, decodeRapidVidAv("""<script>av('$outer');</script>"""))
    }

    @Test
    fun extractPageEmbedCandidatesCollectsSrcdocAtobAndDataAttrs() {
        val encodedIframe = Base64.getEncoder().encodeToString("""<iframe src="https://vidmoly.net/embed-abc.html"></iframe>""".toByteArray(Charsets.UTF_8))
        val atobUrl = Base64.getEncoder().encodeToString("https://ok.ru/videoembed/12345".toByteArray(Charsets.UTF_8))
        val html = """
            <div data-embed="//player.example/e/1"></div>
            <div data-frame="$encodedIframe"></div>
            <iframe srcdoc='<iframe src="https://vk.com/video_ext.php?oid=1&id=2"></iframe>'></iframe>
            <script>var u = atob('$atobUrl');</script>
        """.trimIndent()
        val found = extractPageEmbedCandidates(html, "https://site.example/ep/1")
        assertTrue(found.contains("https://player.example/e/1"))
        assertTrue(found.contains("https://vidmoly.net/embed-abc.html"))
        assertTrue(found.contains("https://vk.com/video_ext.php?oid=1&id=2"))
        assertTrue(found.contains("https://ok.ru/videoembed/12345"))
    }
}
