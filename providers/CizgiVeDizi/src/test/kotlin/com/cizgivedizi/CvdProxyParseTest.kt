package com.cizgivedizi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class CvdProxyParseTest {
    @Test
    fun parseRangeHandlesOpenBoundedSuffixAndInvalid() {
        assertEquals(CvdVideoProxy.RangeSpec(0L, null, null), CvdVideoProxy.parseRange("bytes=0-"))
        assertEquals(CvdVideoProxy.RangeSpec(1024L, 2047L, null), CvdVideoProxy.parseRange("bytes=1024-2047"))
        assertEquals(CvdVideoProxy.RangeSpec(null, null, 512L), CvdVideoProxy.parseRange("bytes=-512"))
        assertNull(CvdVideoProxy.parseRange("bytes=500-100"))
        assertNull(CvdVideoProxy.parseRange("items=0-10"))
    }

    @Test
    fun parseContentRangeAndCookies() {
        assertEquals(
            CvdVideoProxy.ContentRangeInfo(0L, 2097151L, 78783012L),
            CvdVideoProxy.parseContentRange("bytes 0-2097151/78783012"),
        )
        assertNull(CvdVideoProxy.parseContentRange("bytes */78783012"))

        val headers = listOf(
            "cvd_video_sid=abc123; Path=/; HttpOnly",
            "cvd_video_auth=eyJ.sig; Max-Age=60; Path=/",
        )
        assertEquals("abc123", CvdVideoProxy.parseCookie(headers, "cvd_video_sid"))
        assertEquals("eyJ.sig", CvdVideoProxy.parseCookie(headers, "cvd_video_auth"))
        assertEquals(
            "cvd_video_sid=abc123; cvd_video_auth=eyJ.sig",
            CvdVideoProxy.buildCookieHeader("abc123", "eyJ.sig"),
        )
    }

    @Test
    fun parsePoolDataSkipsHtmlAndExtractsEnglishTitle() {
        val html = """
            <html><body>
            <script type="application/json" id="poolData">[
              {"dataset":{"type":"cizgi","id":"ek","name":"esrarengiz kasaba","hay":"ek esrarengiz kasaba","hayEn":"ek gravity falls ek gravity falls ekgravityfalls","genresraw":"Gizem|Komedi","kanaltext":"Disney"},"href":"/dizi/ek/esrarengiz_kasaba","poster":"https://cizgivedizi.net/resim/EK.avif","label":"Esrarengiz Kasaba","html":"<a href=\"/dizi/ek/esrarengiz_kasaba\">ignored</a>"},
              {"dataset":{"type":"other","id":"skip","name":"other"},"href":"/other","poster":"","label":"Other","html":""}
            ]</script></body></html>
        """.trimIndent()
        val reader = StringReader(html)
        assertTrue(CvdParser.advancePastMarker(reader, "<script type=\"application/json\" id=\"poolData\">"))
        val items = CvdParser.parsePoolData(reader)
        assertEquals(1, items.size)
        assertEquals("ek", items[0].id)
        assertEquals("Esrarengiz Kasaba", items[0].title)
        assertEquals("gravity falls", items[0].englishTitle)
        assertEquals(listOf("Gizem", "Komedi"), items[0].genres)
        assertEquals("Disney", items[0].channel)
    }

    @Test
    fun extractBridgeKeyDecodesQueryParam() {
        assertEquals(
            "v1.abc.def",
            CvdParser.extractBridgeKey("/diziyeni/player.php?k=v1.abc.def"),
        )
    }

    @Test
    fun encodeAndDecodeSeriesDataPreservesUnicodeKeys() {
        val encoded = CvdParser.encodeSeriesData("https://cizgivedizi.net", "blueykısa", "Özel Bölüm")
        assertTrue(encoded.startsWith("https://cizgivedizi.net/#cvd-dizi/"))
        assertEquals("blueykısa" to "Özel Bölüm", CvdParser.decodeSeriesData(encoded))
        assertNull(CvdParser.decodeSeriesData("https://cizgivedizi.net/film/yetavc/yetenek_avcisi#cvd-film"))
    }
}
