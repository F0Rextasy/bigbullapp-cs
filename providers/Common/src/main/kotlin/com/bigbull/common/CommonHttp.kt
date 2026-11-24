package com.bigbull.common

import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request

/**
 * Android OkHttp bazı Cloudflare sitelerinde IPv6'yı önce deneyip h2 stream'inde takılıyor.
 * Bu istemci IPv4'ü önce dener ve HTTP/1.1 kullanır; yalnız sayfa/API HTML'i için.
 */
val http11Client: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .dns { hostname ->
            val all = InetAddress.getAllByName(hostname)
            all.filterIsInstance<Inet4Address>().ifEmpty { all.toList() }
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
}

fun http11Get(
    url: String,
    headers: Map<String, String> = emptyMap(),
    referer: String? = null,
): String {
    val builder = Request.Builder().url(url).get()
    headers.forEach { (key, value) -> builder.header(key, value) }
    if (!referer.isNullOrBlank()) builder.header("Referer", referer)
    http11Client.newCall(builder.build()).execute().use { response ->
        if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}: $url")
        return response.body?.string().orEmpty()
    }
}
