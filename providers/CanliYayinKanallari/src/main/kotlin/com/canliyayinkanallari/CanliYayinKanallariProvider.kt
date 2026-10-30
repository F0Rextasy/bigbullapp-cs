package com.canliyayinkanallari

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
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

/** Live channels with verified public HLS endpoints; no scraping at runtime. */
class CanliYayinKanallariProvider : MainAPI() {
    override var mainUrl = "https://www.tabii.com"
    override var name = "CanliYayinKanallari"
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
        if (data.contains("youtube.com") || data.contains("youtu.be")) {
            return loadExtractor(data, mainUrl + "/", subtitleCallback, callback)
        }
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
            "TV100" to "https://www.youtube.com/watch?v=4WSvLRk83-c",
        "TVNET" to "https://tvnet-live.lg.mncdn.com/tvnet-live/tvnet/playlist.m3u8",
        "TGRT Haber" to "https://canli.tgrthaber.com/tgrt.m3u8",
        "A Haber" to "https://trkvz-live.ercdn.net/ahaberhd/ahaberhd.m3u8",
        "A Spor" to "https://trkvz-live.ercdn.net/asporhd/asporhd.m3u8",
        "HT Spor" to "https://ciner.daioncdn.net/ht-spor/ht-spor.m3u8?app=web",
        "Kanal D" to "https://demiroren.daioncdn.net/kanald/kanald.m3u8?app=kanald_web&ce=3",
        "TV8" to "https://tv8.daioncdn.net/tv8/tv8.m3u8?app=7ddc255a-ef47-4e81-ab14-c0e5f2949788&ce=3",
        "Star TV" to "https://dogus.daioncdn.net/startv/startv.m3u8?ce=3&app=a20ac41e-bdc3-4aa1-934d-26b484480ac9",
        "TV8,5" to "https://tv8.daioncdn.net/tv8bucuk/tv8bucuk.m3u8?app=tv8bucuk_web&ce=3",
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
        "ATV" to "https://trkvz.daioncdn.net/atv/atv.m3u8?ce=3&app=d1ce2d40-5256-4550-b02e-e73c185a314e&st=qqKm4uw0BVrwQRBT6PBpLg&e=1793376710&ppid=3425abf4972d113c601e35252a4f14e4",
        "Show TV" to "https://ciner.daioncdn.net/showtv/showtv.m3u8?ce=3&app=4bc856ef-4c68-4a94-bc87-37dfaaa66558&st=RBzhSuGauna0OGld-DJUVA&e=1664766175&tv=1",
        "NOW TV" to "https://nowtv.daioncdn.net/nowtv/nowtv.m3u8?ce=3&app=65fa8997-2b2f-4529-85ea-84318628a608&st=-Kwllq29-XrUqzYpigMoQA&e=1793345536",
        "CNN Türk" to "https://live.duhnet.tv//S2/HLS_LIVE/cnnturknp/playlist.m3u8?&live=true&app=com.cnnturk&st=EChkYDMXXDBgY01SvbCfmw&e=1793342468",
        "A Para" to "https://trkvz.daioncdn.net/apara/apara.m3u8?app=web&st=Ep_kdjMbJqd3mk_gP-v-mA&e=1793377077&ppid=3425abf4972d113c601e35252a4f14e4",
        "VAV TV" to "https://trkvz-live.ercdn.net/vavtv/vavtv.m3u8?st=mvZ7XVOZMrxGl-ZvIaY7Eg&e=1793376981",
        "24 TV" to "https://turkmedya-live.ercdn.net/tv24/tv24.m3u8",
        "360" to "https://turkmedya-live.ercdn.net/tv360/tv360.m3u8",
        "TV4" to "https://turkmedya-live.ercdn.net/tv4/tv4.m3u8",
        "Tele1" to "https://tele1-live.ercdn.net/tele1/tele1.m3u8",
        "Haber Global" to "https://tv.ensonhaber.com/haberglobal/haberglobal.m3u8",
        "Habertürk TV" to "https://tv.ensonhaber.com/haberturk/haberturk.m3u8",
        "NTV" to "https://dogus.daioncdn.net/ntv/ntv.m3u8?app=ntv_web",
        "Bloomberg HT" to "https://ciner.daioncdn.net/bloomberght/bloomberght.m3u8?app=web",
        "Flash Haber" to "https://b01c02nl.mediatriple.net/videoonlylive/mtyycglqauzjhlive/broadcast_67c053c48829f.smil/playlist.m3u8",
        "TV Den" to "https://canli.tvden.com.tr/hls/live.m3u8",
        "TBMM TV" to "https://meclistv-live.ercdn.net/meclistv/meclistv.m3u8",
        "TJK TV" to "https://tjktv-live.tjk.org/tjktv.m3u8",
        "Power TV" to "https://livetv.powerapp.com.tr/powerTV/powerhd.smil/playlist.m3u8",
        "Kral Pop TV" to "https://dogus-live.daioncdn.net/kralpoptv/playlist.m3u8",
        "minikaGO" to "https://rnttwmjcin.turknet.ercdn.net/lcpmvefbyo/minikago/minikago.m3u8",
        "minika Çocuk" to "https://rnttwmjcin.turknet.ercdn.net/lcpmvefbyo/minikago_cocuk/minikago_cocuk.m3u8",
        "Kanal 3" to "https://live.artidijitalmedya.com/artidijital_kanal3/kanal3/playlist.m3u8",
        "TV264" to "https://b01c02nl.mediatriple.net/videoonlylive/mtdxkkitgbrckilive/broadcast_5ee244263fd6d.smil/playlist.m3u8",
        "İlke TV" to "https://stream.ilketv.com.tr/hls/ilkecanli.m3u8",
        "Edessa TV" to "https://canli.edessatv.com/hls/stream.m3u8",
        "Sercem TV" to "https://canli.sercemtv.com.tr/hls/0/stream.m3u8",
        "YOL TV" to "https://live.yoltv.com/hls/stream.m3u8",
        "Zarok TV" to "https://zindikurmanci.zaroktv.com.tr/hls/stream.m3u8",
        "4U TV" to "https://hls.4utv.live/hls/stream.m3u8",
        )
    }
}
