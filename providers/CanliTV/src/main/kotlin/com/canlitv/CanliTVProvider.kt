package com.canlitv

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.newLiveStreamLoadResponse

/** CanliTV — iptv-org Türkiye M3U listesi (canlı kanallar). */
class CanliTVProvider : MainAPI() {
    override var mainUrl = "https://iptv-org.github.io/iptv/countries/tr.m3u"
    override var name = "CanliTV"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    override val mainPage get() = ROWS.mapNotNull { name ->
        if (!Settings.rowEnabled(name)) return@mapNotNull null
        when (name) {
            "Ulusal" -> MainPageData(name, "General")
            "Haber" -> MainPageData(name, "News")
            "Spor" -> MainPageData(name, "Sports")
            "Çocuk" -> MainPageData(name, "Kids")
            "Müzik" -> MainPageData(name, "Music")
            else -> null
        }
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

    private suspend fun getChannels(): List<Channel> {
        channels?.let { return it }
        val playlist = runCatching { app.get(mainUrl).text }.getOrNull()
            ?: throw ErrorLoadingException("$name: kanal listesi alınamadı. $REPORT_HINT")
        val parsed = parsePlaylist(playlist)
        if (parsed.isEmpty()) {
            throw ErrorLoadingException("$name: kanal listesi boş. $REPORT_HINT")
        }
        return parsed.also { channels = it }
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
                        found.putIfAbsent(
                            channelName.lowercase(),
                            Channel(
                                name = channelName,
                                groups = attrs["group-title"].orEmpty().split(';').map { it.trim() },
                                logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
                                url = line,
                            ),
                        )
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
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!data.startsWith("http")) throw ErrorLoadingException("$name: geçersiz kanal adresi")
        callback.invoke(
            newExtractorLink(name, "Canlı", data, ExtractorLinkType.M3U8) {
                this.referer = "https://iptv-org.github.io/"
            }
        )
        return true
    }

    companion object {
        /** Homepage rows in display order; each maps to a playlist group-title. */
        val ROWS = listOf("Ulusal", "Haber", "Spor", "Çocuk", "Müzik")

        private val ATTR = Regex("""([\w-]+)="([^"]*)"""")
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}
