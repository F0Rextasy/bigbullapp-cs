package com.selcuksports

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
import java.net.URLEncoder

/**
 * SelcukSports — current portal + embedded player.
 *
 * The portal is intentionally domain-hopping; [mainUrl] is the currently reachable portal.
 * Channel rows carry `data-url` pointing at the player iframe. Some player builds use a
 * WebRTC/P2P backend that does not expose HLS to Cloudstream; [loadLinks] reports that as
 * a real loading error instead of inventing a media URL.
 */
class SelcukSportsProvider : MainAPI() {
    override var mainUrl = "https://www.selcuksportshd14e17ba2ad.xyz"
    override var name = "SelcukSports"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Live)

    private val uaHeaders = mapOf(
        "User-Agent" to UA,
        "Accept-Language" to "tr-TR,tr;q=0.9",
    )
    private var cachedChannels: List<Channel>? = null

    override val mainPage get() = ROWS.mapNotNull { row ->
        if (Settings.rowEnabled(row)) MainPageData(row, mainUrl) else null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val items = getChannels().map { it.toSearchResponse() }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim()
        if (term.isBlank()) return emptyList()
        return getChannels()
            .filter { it.name.contains(term, ignoreCase = true) }
            .map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val channel = getChannels().firstOrNull { it.data == url } ?: return null
        return newLiveStreamLoadResponse(channel.name, channel.data, channel.playerUrl) {
            this.posterUrl = channel.poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val playerUrl = data.substringBefore("#bb=")
        val page = runCatching {
            app.get(playerUrl, headers = uaHeaders, referer = mainUrl).text
        }.getOrNull() ?: throw ErrorLoadingException("$name: oynatıcı sayfası alınamadı")
        var found = false
        val media = Regex("""https?://[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)""")
            .findAll(page)
        for (match in media) {
            callback.invoke(
                newExtractorLink(name, "Canlı", match.value, ExtractorLinkType.M3U8) {
                    this.referer = playerUrl
                }
            )
            found = true
        }
        if (!found) {
            found = runCatching {
                loadExtractor(playerUrl, mainUrl, subtitleCallback, callback)
            }.getOrDefault(false)
        }
        if (!found) {
            throw ErrorLoadingException(
                "$name: oynatıcı P2P/WebRTC kaynağı doğrudan medya vermiyor; kanal backlog'da"
            )
        }
        return true
    }

    private suspend fun getChannels(): List<Channel> {
        cachedChannels?.let { return it }
        val doc = app.get(mainUrl, headers = uaHeaders).document
        val channels = doc.select("a[data-url]").mapNotNull { anchor ->
            val raw = anchor.attr("data-url").trim()
            val playerUrl = raw.substringBefore('#').takeIf {
                it.contains("uxsyplayer") && it.contains("id=")
            } ?: return@mapNotNull null
            val title = anchor.selectFirst("div.name")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: anchor.attr("title").trim().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val poster = Regex("""[?&]poster=([^&]+)""").find(raw)?.groupValues?.get(1)
            Channel(
                name = title,
                playerUrl = playerUrl,
                data = "$playerUrl#bb=${URLEncoder.encode(title, "UTF-8")}",
                poster = poster,
            )
        }.distinctBy { it.data }.sortedBy { it.name.lowercase() }
        if (channels.isEmpty()) throw ErrorLoadingException("$name: kanal listesi bulunamadı")
        return channels.also { cachedChannels = it }
    }

    private fun Channel.toSearchResponse(): SearchResponse =
        newLiveSearchResponse(name, data, TvType.Live) {
            this.posterUrl = poster
        }

    private data class Channel(
        val name: String,
        val playerUrl: String,
        val data: String,
        val poster: String?,
    )

    companion object {
        val ROWS = listOf("Tüm Kanallar")
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    }
}
