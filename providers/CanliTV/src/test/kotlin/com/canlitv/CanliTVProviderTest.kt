package com.canlitv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanliTVProviderTest {
    @Test
    fun parsePlaylistExtractsAttributesGroupsAndSkipsVlcOpts() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-logo="https://img.example/trt1.png" group-title="General;News",TRT 1
            #EXTVLCOPT:http-user-agent=CustomUA
            https://cdn.example/trt1.m3u8

            https://orphan.example/ignored.m3u8
            #EXTINF:-1 group-title="Kids",
            https://nameless.example/ignored.m3u8

            #EXTINF:-1 group-title="Sports",TRT Spor
            https://cdn.example/trtspor.m3u8
        """.trimIndent()

        val channels = parseM3uPlaylist(m3u)
        assertEquals(2, channels.size)
        assertEquals("TRT 1", channels[0].name)
        assertEquals(listOf("General", "News"), channels[0].groups)
        assertEquals("https://img.example/trt1.png", channels[0].logo)
        assertEquals("https://cdn.example/trt1.m3u8", channels[0].url)

        assertEquals("TRT Spor", channels[1].name)
        assertFalse(channels[1].logo.isNullOrBlank())
    }

    @Test
    fun parsePlaylistDeduplicatesQualityVariantsAndTurkishAccents() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 group-title="General",TRT 1 (720p)
            https://first.example/trt1-720.m3u8
            #EXTINF:-1 group-title="General",TRT 1 [Not 24/7]
            https://dup.example/trt1-alt.m3u8
            #EXTINF:-1 group-title="General",TRT Kurdî
            https://first.example/kurdi.m3u8
            #EXTINF:-1 group-title="General",TRT Kurdi
            https://dup.example/kurdi-2.m3u8
        """.trimIndent()

        val channels = parseM3uPlaylist(m3u)
        assertEquals(2, channels.size)
        assertEquals("https://first.example/trt1-720.m3u8", channels[0].url)
        assertEquals("https://first.example/kurdi.m3u8", channels[1].url)
        assertTrue(channels.all { it.url.startsWith("https://first.example/") })
    }
}
