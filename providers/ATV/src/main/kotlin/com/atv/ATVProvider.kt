package com.atv

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
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

/** Live channels with verified public HLS endpoints; no scraping at runtime. */
class ATVProvider : MainAPI() {
    override var mainUrl = "https://www.atv.com.tr"
    override var name = "ATV"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)
    override val mainPage get() = ROWS.filter { Settings.rowEnabled(it) }
        .map { MainPageData(it, mainUrl) }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        return newHomePageResponse(request.name, CHANNELS.map { (n, u) ->
            newLiveSearchResponse(n, u, TvType.Live)
        }, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        CHANNELS.filter { (n, _) -> n.contains(query, ignoreCase = true) }
            .map { (n, u) -> newLiveSearchResponse(n, u, TvType.Live) }

    override suspend fun load(url: String): LoadResponse? {
        val channel = CHANNELS.firstOrNull { it.second == url } ?: return null
        return newLiveStreamLoadResponse(channel.first, channel.second, channel.second)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!data.startsWith("http")) throw ErrorLoadingException("$name: geçersiz kanal")
        callback.invoke(
            newExtractorLink(name, "Canlı", data, ExtractorLinkType.M3U8) {
                this.referer = mainUrl + "/"
            }
        )
        return true
    }

    companion object {
        val ROWS = listOf("Kanallar")
        val CHANNELS = listOf(
        "ATV" to "https://trkvz.daioncdn.net/atv/atv.m3u8?ce=3&app=d1ce2d40-5256-4550-b02e-e73c185a314e&st=qqKm4uw0BVrwQRBT6PBpLg&e=1793376710&ppid=3425abf4972d113c601e35252a4f14e4",
        )
    }
}
