package com.canlitv

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

/**
 * CanliTV — tek canlı TV sağlayıcısı. Seçilmiş HLS adresleri (STATIC) ile iptv-org Türkiye M3U
 * listesi aynı havuzda birleşir; yeni canlı kanal bu dosyaya eklenir.
 */
class CanliTVProvider : MainAPI() {
    override var mainUrl = "https://iptv-org.github.io/iptv/countries/tr.m3u"
    override var name = "CanliTV"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    override val mainPage get() = ROWS.mapNotNull { (label, group) ->
        if (!Settings.rowEnabled(label)) return@mapNotNull null
        MainPageData(label, group)
    }

    private var channels: List<Channel>? = null

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = getChannels()
            .filter { request.data in it.groups }
            .map { it.toSearchResponse() }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        return getChannels()
            .filter { it.name.contains(term, ignoreCase = true) }
            .map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val channel = getChannels().firstOrNull { it.url == url } ?: return null
        return newLiveStreamLoadResponse(channel.name, channel.url, channel.url) {
            this.posterUrl = channel.logo
        }
    }

    /** Seçilmiş kanallar önce gelir; iptv-org aynı adı taşıyan kaydı ezmez. */
    private suspend fun getChannels(): List<Channel> {
        channels?.let { return it }
        val playlist = runCatching { app.get(mainUrl).text }.getOrNull()
        val dynamic = playlist?.let { parsePlaylist(it) }.orEmpty()
        if (dynamic.isEmpty() && STATIC.isEmpty()) {
            throw ErrorLoadingException("$name: kanal listesi alınamadı. $REPORT_HINT")
        }
        val merged = LinkedHashMap<String, Channel>()
        (STATIC + dynamic).forEach { merged.putIfAbsent(it.key, it) }
        val live = merged.values.mapNotNull { ch ->
            if (ch.key in SKIP) return@mapNotNull null
            OVERRIDES[ch.key]?.let { ch.copy(url = it, referer = null) } ?: ch
        }
        // Uzak liste alınamadıysa önbelleğe alma; sonraki satır isteği yeniden dener.
        return live.also { if (dynamic.isNotEmpty()) channels = it }
    }

    /** #EXTINF attributes followed by one stream line; #EXTVLCOPT and other tags are skipped. */
    private fun parsePlaylist(text: String): List<Channel> {
        val found = LinkedHashMap<String, Channel>()
        var pending: Pair<String, Map<String, String>>? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF") -> {
                    val attrs = ATTR.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
                    pending = line.substringAfterLast(',', "").trim() to attrs
                }
                line.startsWith("#") -> Unit
                else -> {
                    val (channelName, attrs) = pending ?: continue
                    if (channelName.isNotEmpty()) {
                        // .m3u8, .mpd and .ts all play through the live player; only the URL matters.
                        Channel(
                            name = channelName,
                            groups = attrs["group-title"].orEmpty().split(';').map { it.trim() },
                            logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() } ?: DEFAULT_LOGO,
                            url = line,
                            referer = IPTV_REFERER,
                        ).let { found.putIfAbsent(it.key, it) }
                    }
                    pending = null
                }
            }
        }
        return found.values.toList()
    }

    private fun Channel.toSearchResponse() = newLiveSearchResponse(name, url, TvType.Live) {
        this.posterUrl = logo
    }

    private data class Channel(
        val name: String,
        val groups: List<String>,
        val logo: String?,
        val url: String,
        val referer: String? = null,
    ) {
        /**
         * Kalite ve etiket ekleri ("(720p)", "[Not 24/7]") ile Türkçe harf farkı atılır:
         * "TRT 1 (720p)", "TRT Kurdî" ve "TRT Kurdi" aynı kanaldır.
         */
        val key get() = buildString {
            for (c in name.replace(QUALITY, "").replace(LABEL, "").trim().lowercase()) {
                if (c != '\u0307') append(TR_LETTERS[c] ?: c)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!data.startsWith("http")) throw ErrorLoadingException("$name: geçersiz kanal adresi")
        val referer = (channels ?: STATIC).firstOrNull { it.url == data }?.referer
        callback.invoke(
            newExtractorLink(name, "Canlı", data, ExtractorLinkType.M3U8) {
                if (referer != null) this.referer = referer
            }
        )
        return true
    }

    companion object {
        private const val DEFAULT_LOGO = "https://www.tabii.com/favicon.ico"
        private const val STATIC_REFERER = "https://www.tabii.com/"
        private const val IPTV_REFERER = "https://iptv-org.github.io/"
        private const val SHOW_REFERER = "https://www.showtv.com.tr/"
        private val QUALITY = Regex("""\s*\([^)]*\)""")
        private val LABEL = Regex("""\s*\[[^]]*]""")
        private val TR_LETTERS = mapOf(
            'ç' to 'c', 'ğ' to 'g', 'ı' to 'i', 'ö' to 'o', 'ş' to 's', 'ü' to 'u',
            'â' to 'a', 'î' to 'i', 'û' to 'u',
        )
        private val ATTR = Regex("""([\w-]+)="([^"]*)"""")
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"

        /** Ana sayfa satırları: etiket -> M3U group-title. */
        val ROWS = listOf(
            "Ulusal" to "General",
            "Haber" to "News",
            "Spor" to "Sports",
            "Çocuk" to "Kids",
            "Müzik" to "Music",
            "Belgesel" to "Documentary",
            "Dini" to "Religious",
        )

        /** iptv-org'da adresi ölü olan kanallar için cihazda doğrulanmış yedek adresler. */
        private val OVERRIDES = mapOf(
            "atv" to "https://rnttwmjcin.turknet.ercdn.net/lcpmvefbyo/atv/atv.m3u8",
            "now" to "https://uycyyuuzyh.turknet.ercdn.net/nphindgytw/nowtv/nowtv.m3u8",
            "kanal 7" to "https://kanal7-live.daioncdn.net/kanal7/kanal7.m3u8?app=web",
            "altas tv" to "http://edge1.socialsmart.tv/altastv/bant1/playlist.m3u8",
            "benguturk tv" to "https://tv.ensonhaber.com/benguturk/benguturk.m3u8",
            "cay tv" to "http://edge1.socialsmart.tv/caytv/bant1/playlist.m3u8",
            "etv manisa" to "http://edge1.socialsmart.tv/manisaetv/bant1/playlist.m3u8",
            "fortuna tv" to "http://edge1.socialsmart.tv/ftvturk/bant1/playlist.m3u8",
            "guneydogu tv" to "http://edge1.socialsmart.tv/gtv/bant1/playlist.m3u8",
            "hunat tv" to "https://live.artidijitalmedya.com/artidijital_hunattv/hunattv/playlist.m3u8",
            "icel tv" to "http://edge1.socialsmart.tv/iceltv/bant1/playlist.m3u8",
            "kanal 33" to "http://edge1.socialsmart.tv/kanal33/bant1/playlist.m3u8",
            "line tv" to "http://edge1.socialsmart.tv/linetv/bant1/playlist.m3u8",
            "mturk tv" to "http://edge1.socialsmart.tv/muglaturk/bant1/playlist.m3u8",
            "national geographic" to "https://saran-live.ercdn.net/natgeohd/index.m3u8",
            "national geographic wild" to "https://saran-live.ercdn.net/natgeowild/index.m3u8",
            "turkhaber tv" to "http://edge1.socialsmart.tv/turkhaber/bant1/playlist.m3u8",
            "tv 1" to "http://edge1.socialsmart.tv/tv1/bant1/playlist.m3u8",
            "tv 41" to "https://live.artidijitalmedya.com/artidijital_tv41/tv41/playlist.m3u8",
            "tv 52" to "http://edge1.socialsmart.tv/tv52/bant1/playlist.m3u8",
        )

        /** Cihazda hiçbir adresi yanıt vermeyen kayıtlar (2026-11-12 taraması). */
        private val SKIP = setOf(
            "al-rafidain tv", "atv avrupa", "bbc earth turkiye", "beyaz tv", "bir tv", "bursa tv",
            "cekmekoy tv", "eurosport 1 turkiye", "eurosport 2 turkiye", "gzt", "kanal 3",
            "kanal 32", "kanal 34", "kanal hayat", "meltem tv", "moviesmart turk", "natural tv",
            "on4 tv", "qaf tv", "s sport 2", "show turk", "tele 1", "turksat tanitim", "tv 42",
            "ulke tv", "viasat history",
        )

        private val STATIC = listOf(
            ch("TVNET", "News", "https://tvnet-live.lg.mncdn.com/tvnet-live/tvnet/playlist.m3u8"),
            ch("TGRT Haber", "News", "https://canli.tgrthaber.com/tgrt.m3u8"),
            ch("A Haber", "News", "https://trkvz-live.ercdn.net/ahaberhd/ahaberhd.m3u8"),
            ch("A Spor", "Sports", "https://trkvz-live.ercdn.net/asporhd/asporhd.m3u8"),
            ch("HT Spor", "Sports", "https://ciner.daioncdn.net/ht-spor/ht-spor.m3u8?app=web"),
            ch("Kanal D", "General", "https://demiroren.daioncdn.net/kanald/kanald.m3u8?app=kanald_web&ce=3"),
            ch("TV8", "General", "https://tv8.daioncdn.net/tv8/tv8.m3u8?app=tv8_web&ce=3"),
            ch("Star TV", "General", "https://dogus.daioncdn.net/startv/startv.m3u8?app=startv_web&ce=3"),
            ch("TV8,5", "General", "https://tv8.daioncdn.net/tv8bucuk/tv8bucuk.m3u8?app=tv8bucuk_web&ce=3"),
            ch("TRT 1", "General", "https://tv-trt1.medya.trt.com.tr/master.m3u8"),
            ch("TRT 2", "General", "https://tv-trt2.medya.trt.com.tr/master.m3u8"),
            ch("TRT Spor", "Sports", "https://tv-trtspor1.medya.trt.com.tr/master.m3u8"),
            ch("TRT Belgesel", "Documentary", "https://tv-trtbelgesel.medya.trt.com.tr/master.m3u8"),
            ch("TRT Haber", "News", "https://tv-trthaber.medya.trt.com.tr/master.m3u8"),
            ch("TRT Çocuk", "Kids", "https://tv-trtcocuk.medya.trt.com.tr/master.m3u8"),
            ch("TRT Müzik", "Music", "https://tv-trtmuzik.medya.trt.com.tr/master.m3u8"),
            ch("TRT World", "News", "https://tv-trtworld.medya.trt.com.tr/master.m3u8"),
            ch("TRT Avaz", "General", "https://tv-trtavaz.medya.trt.com.tr/master.m3u8"),
            ch("TRT Kurdî", "General", "https://tv-trtkurdi.medya.trt.com.tr/master.m3u8"),
            ch("TRT Arabi", "General", "https://tv-trtarabi.medya.trt.com.tr/master.m3u8"),
            ch("TRT Diyanet Çocuk", "Kids", "https://tv-trtdiyanetcocuk.medya.trt.com.tr/master.m3u8"),
            ch("TRT Türk", "General", "https://tv-trtturk.medya.trt.com.tr/master.m3u8"),
            ch("Show TV", "General", "https://ciner.daioncdn.net/showtv/showtv.m3u8?app=showtv_web&ce=3", SHOW_REFERER),
            ch("24 TV", "News", "https://turkmedya-live.ercdn.net/tv24/tv24.m3u8"),
            ch("360", "General", "https://turkmedya-live.ercdn.net/tv360/tv360.m3u8"),
            ch("TV4", "General", "https://turkmedya-live.ercdn.net/tv4/tv4.m3u8"),
            ch("Haber Global", "News", "https://tv.ensonhaber.com/haberglobal/haberglobal.m3u8"),
            ch("Habertürk TV", "News", "https://tv.ensonhaber.com/haberturk/haberturk.m3u8"),
            ch("NTV", "News", "https://dogus.daioncdn.net/ntv/ntv.m3u8?app=ntv_web"),
            ch("Bloomberg HT", "News", "https://ciner.daioncdn.net/bloomberght/bloomberght.m3u8?app=web"),
            ch("Flash Haber", "News", "https://b01c02nl.mediatriple.net/videoonlylive/mtyycglqauzjhlive/broadcast_67c053c48829f.smil/playlist.m3u8"),
            ch("TV Den", "General", "https://canli.tvden.com.tr/hls/live.m3u8"),
            ch("TBMM TV", "News", "https://meclistv-live.ercdn.net/meclistv/meclistv.m3u8"),
            ch("TJK TV", "Sports", "https://tjktv-live.tjk.org/tjktv.m3u8"),
            ch("Power TV", "Music", "https://livetv.powerapp.com.tr/powerTV/powerhd.smil/playlist.m3u8"),
            ch("Kral Pop TV", "Music", "https://dogus-live.daioncdn.net/kralpoptv/playlist.m3u8"),
            ch("minikaGO", "Kids", "https://rnttwmjcin.turknet.ercdn.net/lcpmvefbyo/minikago/minikago.m3u8"),
            ch("minika Çocuk", "Kids", "https://rnttwmjcin.turknet.ercdn.net/lcpmvefbyo/minikago_cocuk/minikago_cocuk.m3u8"),
            ch("TV264", "General", "https://b01c02nl.mediatriple.net/videoonlylive/mtdxkkitgbrckilive/broadcast_5ee244263fd6d.smil/playlist.m3u8"),
            ch("İlke TV", "General", "https://stream.ilketv.com.tr/hls/ilkecanli.m3u8"),
            ch("Edessa TV", "General", "https://canli.edessatv.com/hls/stream.m3u8"),
            ch("Sercem TV", "General", "https://canli.sercemtv.com.tr/hls/0/stream.m3u8"),
            ch("YOL TV", "General", "https://live.yoltv.com/hls/stream.m3u8"),
            ch("Zarok TV", "Kids", "https://zindikurmanci.zaroktv.com.tr/hls/stream.m3u8"),
            ch("4U TV", "General", "https://hls.4utv.live/hls/stream.m3u8"),
        )

        private fun ch(name: String, group: String, url: String, referer: String = STATIC_REFERER) =
            Channel(name, listOf(group), DEFAULT_LOGO, url, referer)
    }
}
