package com.trtlive

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
class TRTLiveProvider : MainAPI() {
    override var mainUrl = "https://www.tabii.com"
    override var name = "TRTLive"
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
        "TRT 1" to "https://tv-trt1.medya.trt.com.tr/master.m3u8",
        "TRT 2" to "https://tv-trt2.medya.trt.com.tr/master.m3u8",
        "TRT Spor" to "https://tv-trtspor1.medya.trt.com.tr/master.m3u8",
        "TRT Spor Yıldız" to "https://tv-trtsporyildiz.medya.trt.com.tr/master.m3u8",
        "TRT Belgesel" to "https://tv-trtbelgesel.medya.trt.com.tr/master.m3u8",
        "TRT Haber" to "https://tv-trthaber.medya.trt.com.tr/master.m3u8",
        "TRT Çocuk" to "https://tv-trtcocuk.medya.trt.com.tr/master.m3u8",
        "TRT Müzik" to "https://tv-trtmuzik.medya.trt.com.tr/master.m3u8",
        "TRT World" to "https://tv-trtworld.medya.trt.com.tr/master.m3u8",
        "TRT Avaz" to "https://tv-trtavaz.medya.trt.com.tr/master.m3u8",
        "TRT Kurdî" to "https://tv-trtkurdi.medya.trt.com.tr/master.m3u8",
        "TRT Arabi" to "https://tv-trtarabi.medya.trt.com.tr/master.m3u8",
        "TRT Diyanet Çocuk" to "https://tv-trtdiyanetcocuk.medya.trt.com.tr/master.m3u8",
        "TRT Türk" to "https://tv-trtturk.medya.trt.com.tr/master.m3u8",
        )
    }
}
