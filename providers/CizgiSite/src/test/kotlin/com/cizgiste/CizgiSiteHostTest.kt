package com.cizgiste

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CizgiSiteHostTest {
    @Test
    fun isCizgiSiteHostAllowsOnlyExactSiteAndWww() {
        assertTrue(CizgiSiteProvider.isCizgiSiteHost("https://cizgi.site/dizi/x"))
        assertTrue(CizgiSiteProvider.isCizgiSiteHost("http://www.cizgi.site/"))
        assertTrue(CizgiSiteProvider.isCizgiSiteHost("HTTPS://WWW.CIZGI.SITE/test"))

        assertFalse(CizgiSiteProvider.isCizgiSiteHost("https://evil-cizgi.site/x"))
        assertFalse(CizgiSiteProvider.isCizgiSiteHost("https://cizgi.site.evil.com/x"))
        assertFalse(CizgiSiteProvider.isCizgiSiteHost("https://cdn.example.com/video.m3u8"))
        assertFalse(CizgiSiteProvider.isCizgiSiteHost("not a url"))
        assertFalse(CizgiSiteProvider.isCizgiSiteHost(""))
    }
}
