package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.SubtitleFile

enum class ExtractorLinkType { M3U8, VIDEO, DASH }

data class ExtractorLink(
    var source: String,
    var name: String,
    var url: String,
    var type: ExtractorLinkType,
) {
    var referer: String? = null
    var quality: Int = Qualities.Unknown.value
    var headers: Map<String, String>? = null
}

enum class Qualities(val value: Int) {
    Unknown(0), P360(360), P480(480), P720(720), P1080(1080), P2160(2160)
}

suspend fun newExtractorLink(
    source: String,
    name: String,
    url: String,
    type: ExtractorLinkType,
    init: suspend ExtractorLink.() -> Unit = {},
): ExtractorLink = ExtractorLink(source, name, url, type).apply { init() }

fun loadExtractor(
    url: String,
    referer: String? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean = false
