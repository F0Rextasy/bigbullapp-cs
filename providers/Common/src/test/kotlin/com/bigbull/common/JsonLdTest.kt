package com.bigbull.common

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonLdTest {
    private fun doc(html: String) = Jsoup.parse(html)

    @Test
    fun readsSeriesDescriptionAndUnescapesHtml() {
        val html = """
            <script type="application/ld+json">
            {"@type":"TVSeries","description":"Bir &quot;test&quot; dizisi &amp; özeti."}
            </script>
        """.trimIndent()
        assertEquals("Bir \"test\" dizisi & özeti.", doc(html).jsonLdDescription())
    }

    @Test
    fun prefersFaqQuestionOverGenericDescription() {
        val html = """
            <script type="application/ld+json">
            {"@graph":[
              {"@type":"FAQPage","mainEntity":[
                {"@type":"Question","name":"Aşk ve Taht konusu nedir?",
                 "acceptedAnswer":{"@type":"Answer","text":"Gerçek konu metni."}}
              ]}
            ]}
            </script>
        """.trimIndent()
        assertEquals("Gerçek konu metni.", doc(html).jsonLdDescription())
    }

    @Test
    fun readsDescriptionFromArrayRoot() {
        val html = """
            <script type="application/ld+json">
            [{"@type":"Movie","description":"Film özeti."}]
            </script>
        """.trimIndent()
        assertEquals("Film özeti.", doc(html).jsonLdDescription())
    }

    @Test
    fun returnsNullWhenNoJsonLdDescriptionExists() {
        assertNull(doc("<html><head></head><body></body></html>").jsonLdDescription())
    }
}
