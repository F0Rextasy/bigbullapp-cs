package com.bigbull.common

import org.jsoup.nodes.Element

// Tüm modüllerin kopyaladığı tarayıcı kimliği; UA rulajı tek yerden yapılır.
val browserHeaders = mapOf(
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
    "Accept-Language" to "tr-TR,tr;q=0.9",
)

fun Element.posterSrcLazy(): String? =
    attr("data-src").takeIf { it.isNotBlank() } ?: attr("src")
