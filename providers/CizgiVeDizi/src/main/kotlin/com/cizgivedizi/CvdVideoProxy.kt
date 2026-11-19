package com.cizgivedizi

import com.bigbull.common.browserHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Local loopback HTTP relay for `video.cizgivedizi.net`.
 *
 * The upstream CDN requires `Sec-Fetch-Dest: video`, rejects non-Range requests with HTTP 500,
 * caps each 206 response at 2 MB, and expires `cvd_video_auth` cookies every 60 seconds.
 * This relay runs exclusively on `127.0.0.1`, translates standard `GET`/`HEAD` (with or without
 * `Range`) into 2 MB upstream slices, and refreshes `yetki.php` auth before expiry.
 */
object CvdVideoProxy {
    private const val VIDEO_ORIGIN = "https://video.cizgivedizi.net"
    private const val SITE_ORIGIN = "https://cizgivedizi.net"
    private const val CHUNK_BYTES = 2_097_152L
    private const val AUTH_REFRESH_MS = 20_000L
    private const val MAX_SESSIONS = 32
    private const val IO_BUFFER_SIZE = 32 * 1024

    // Auth cookies bind an SHA/MD5 hash of User-Agent; every yetki.php and video request must match.
    internal val USER_AGENT: String = browserHeaders.getValue("user-agent")

    internal sealed class SourceRef {
        data class Series(val showId: String, val episodeKey: String, val sourceIndex: Int) : SourceRef()
        data class Film(val filmUrl: String, val sourceIndex: Int) : SourceRef()
    }

    internal data class RangeSpec(val start: Long?, val end: Long?, val suffix: Long?)
    internal data class ContentRangeInfo(val start: Long, val end: Long, val total: Long)

    private class StreamSession(
        val sourceRef: SourceRef,
        @Volatile var bridgeKey: String,
    ) {
        val lock = Any()
        var opaquePath: String? = null
        var sidCookie: String? = null
        var authCookie: String? = null
        var authAtMs: Long = 0L
        var totalSize: Long = -1L
    }

    internal val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val serverLock = Any()
    private var serverSocket: ServerSocket? = null
    private var port: Int = -1
    private val sessionsLock = Any()
    private val sessions = object : LinkedHashMap<String, StreamSession>(MAX_SESSIONS + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StreamSession>?): Boolean =
            size > MAX_SESSIONS
    }

    private val workerPool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "cvd-proxy-worker").apply { isDaemon = true }
    }

    private fun ensureStarted(): Int {
        synchronized(serverLock) {
            val existing = serverSocket
            if (existing != null && !existing.isClosed) return port
            val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
            val socket = ServerSocket(0, 50, loopback)
            serverSocket = socket
            port = socket.localPort
            Thread({
                while (!socket.isClosed) {
                    val clientSocket = try {
                        socket.accept()
                    } catch (_: IOException) {
                        break
                    }
                    workerPool.execute { handleClient(clientSocket) }
                }
            }, "cvd-proxy-accept").apply {
                isDaemon = true
                start()
            }
            return port
        }
    }

    internal fun register(bridgeKey: String, sourceRef: SourceRef): String? {
        if (bridgeKey.isBlank()) return null
        val listenPort = runCatching { ensureStarted() }.getOrNull() ?: return null
        val session = StreamSession(sourceRef, bridgeKey)
        if (!ensureAuth(session, forceNewTicket = true)) return null
        val token = UUID.randomUUID().toString().replace("-", "")
        synchronized(sessionsLock) {
            sessions[token] = session
        }
        return "http://127.0.0.1:$listenPort/v/$token.mp4"
    }

    private fun refreshBridgeKey(sourceRef: SourceRef): String? = runCatching {
        when (sourceRef) {
            is SourceRef.Series -> CizgiVeDiziProvider.fetchSeriesSources(sourceRef.showId, sourceRef.episodeKey)
                .getOrNull(sourceRef.sourceIndex)?.bridgeKey
            is SourceRef.Film -> CizgiVeDiziProvider.fetchFilmBridgeKeys(sourceRef.filmUrl)
                .getOrNull(sourceRef.sourceIndex)
        }
    }.getOrNull()

    private fun ensureAuth(
        session: StreamSession,
        forceNewTicket: Boolean = false,
        forceRefreshKey: Boolean = false,
    ): Boolean {
        synchronized(session.lock) {
            if (forceRefreshKey) {
                val fresh = refreshBridgeKey(session.sourceRef) ?: return false
                session.bridgeKey = fresh
                session.opaquePath = null
                session.authAtMs = 0L
            }
            val now = System.currentTimeMillis()
            if (!forceNewTicket && session.opaquePath != null && (now - session.authAtMs) < AUTH_REFRESH_MS) {
                return true
            }

            var renewOnly = !forceNewTicket && session.opaquePath != null && session.sidCookie != null
            var retriedWithFreshKey = forceRefreshKey
            for (attempt in 0 until 3) {
                val key = session.bridgeKey
                val url = buildString {
                    append(VIDEO_ORIGIN)
                    append("/yetki.php?k=")
                    append(URLEncoder.encode(key, "UTF-8"))
                    if (renewOnly) append("&renew=1")
                }
                val cookieHeader = buildCookieHeader(session.sidCookie, session.authCookie)
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "$SITE_ORIGIN/")
                    .header("Origin", SITE_ORIGIN)
                    .header("Accept", "*/*")
                    .header("Sec-Fetch-Dest", "empty")
                    .header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Site", "same-site")
                    .apply { if (cookieHeader.isNotEmpty()) header("Cookie", cookieHeader) }
                    .build()

                val ok = runCatching {
                    client.newCall(req).execute().use { resp ->
                        val setCookies = resp.headers("Set-Cookie")
                        parseCookie(setCookies, "cvd_video_sid")?.let { session.sidCookie = it }
                        parseCookie(setCookies, "cvd_video_auth")?.let { session.authCookie = it }
                        if (!resp.isSuccessful) return@use resp.code

                        if (!renewOnly) {
                            val opaque = resp.header("X-CVD-Video-URL")?.trim().orEmpty()
                            if (!OPAQUE_PATH_RE.matches(opaque)) return@use -1
                            session.opaquePath = opaque
                        }
                        session.authAtMs = System.currentTimeMillis()
                        200
                    }
                }.getOrDefault(-1)

                if (ok == 200) return true
                if (renewOnly) {
                    renewOnly = false
                    continue
                }
                if (!retriedWithFreshKey && (ok == 403 || ok == 410)) {
                    retriedWithFreshKey = true
                    val fresh = refreshBridgeKey(session.sourceRef) ?: return false
                    session.bridgeKey = fresh
                    session.opaquePath = null
                    continue
                }
                return false
            }
            return false
        }
    }

    private fun openUpstreamChunk(session: StreamSession, start: Long, endInclusive: Long): Response? {
        for (attempt in 0 until 3) {
            val forceTicket = attempt == 1
            val forceKey = attempt == 2
            if (!ensureAuth(session, forceNewTicket = forceTicket, forceRefreshKey = forceKey)) {
                return null
            }
            val opaque: String
            val cookieHeader: String
            synchronized(session.lock) {
                opaque = session.opaquePath ?: return null
                cookieHeader = buildCookieHeader(session.sidCookie, session.authCookie)
            }
            val req = Request.Builder()
                .url(VIDEO_ORIGIN + opaque)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "$SITE_ORIGIN/")
                .header("Sec-Fetch-Dest", "video")
                .header("Sec-Fetch-Mode", "no-cors")
                .header("Sec-Fetch-Site", "same-site")
                .header("Range", "bytes=$start-$endInclusive")
                .apply { if (cookieHeader.isNotEmpty()) header("Cookie", cookieHeader) }
                .build()

            val resp = runCatching { client.newCall(req).execute() }.getOrNull() ?: continue
            if (resp.isSuccessful) {
                parseContentRange(resp.header("Content-Range"))?.let { cr ->
                    synchronized(session.lock) { session.totalSize = cr.total }
                }
                return resp
            }
            val code = resp.code
            resp.close()
            if (code == 403 || code == 410) {
                synchronized(session.lock) {
                    session.authAtMs = 0L
                    if (code == 410) session.opaquePath = null
                }
                continue
            }
            return null
        }
        return null
    }

    private fun resolveTotalSize(session: StreamSession): Long {
        synchronized(session.lock) {
            if (session.totalSize > 0L) return session.totalSize
        }
        openUpstreamChunk(session, 0L, 0L)?.use { resp ->
            val info = parseContentRange(resp.header("Content-Range"))
            if (info != null) {
                synchronized(session.lock) { session.totalSize = info.total }
                return info.total
            }
        }
        return -1L
    }

    private fun handleClient(socket: Socket) {
        socket.use { sock ->
            sock.soTimeout = 30_000
            val input = BufferedInputStream(sock.getInputStream())
            val output = sock.getOutputStream()

            val reqLine = readAsciiLine(input) ?: return
            val parts = reqLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore('?')

            var rangeHeader: String? = null
            while (true) {
                val line = readAsciiLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val headerName = line.substring(0, colon).trim()
                    if (headerName.equals("Range", ignoreCase = true)) {
                        rangeHeader = line.substring(colon + 1).trim()
                    }
                }
            }

            if (method != "GET" && method != "HEAD") {
                writeSimpleResponse(output, 405, "Method Not Allowed")
                return
            }

            val token = PATH_TOKEN_RE.matchEntire(path)?.groupValues?.get(1)
            val session = token?.let { synchronized(sessionsLock) { sessions[it] } }
            if (session == null) {
                writeSimpleResponse(output, 404, "Not Found")
                return
            }

            val rangeSpec = if (rangeHeader != null) parseRange(rangeHeader) else null
            if (rangeHeader != null && rangeSpec == null) {
                writeSimpleResponse(output, 416, "Range Not Satisfiable")
                return
            }

            var reqStart: Long
            var reqEnd: Long
            var firstResp: Response? = null

            if (rangeSpec?.suffix != null) {
                val total = resolveTotalSize(session)
                if (total <= 0L) {
                    writeSimpleResponse(output, 502, "Bad Gateway")
                    return
                }
                reqStart = max(0L, total - rangeSpec.suffix)
                reqEnd = total - 1L
            } else {
                reqStart = rangeSpec?.start ?: 0L
                reqEnd = rangeSpec?.end ?: -1L
            }

            val probeEnd = if (reqEnd >= reqStart) {
                min(reqStart + CHUNK_BYTES - 1L, reqEnd)
            } else {
                reqStart + CHUNK_BYTES - 1L
            }

            firstResp = openUpstreamChunk(session, reqStart, probeEnd)
            if (firstResp == null) {
                writeSimpleResponse(output, 502, "Bad Gateway")
                return
            }

            val totalSize = parseContentRange(firstResp.header("Content-Range"))?.total
                ?: synchronized(session.lock) { session.totalSize }
            if (totalSize <= 0L) {
                firstResp.close()
                writeSimpleResponse(output, 502, "Bad Gateway")
                return
            }
            if (reqStart >= totalSize) {
                firstResp.close()
                writeSimpleResponse(output, 416, "Range Not Satisfiable", "Content-Range: bytes */$totalSize\r\n")
                return
            }

            val finalEnd = if (reqEnd >= 0L) min(reqEnd, totalSize - 1L) else totalSize - 1L
            val contentLength = finalEnd - reqStart + 1L
            val isPartial = rangeSpec != null

            val head = buildString {
                append(if (isPartial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                append("Content-Type: video/mp4\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Length: $contentLength\r\n")
                if (isPartial) append("Content-Range: bytes $reqStart-$finalEnd/$totalSize\r\n")
                append("Connection: close\r\n\r\n")
            }

            try {
                output.write(head.toByteArray(Charsets.US_ASCII))
                output.flush()
                if (method == "HEAD") {
                    firstResp.close()
                    return
                }

                var pos = reqStart
                var currentResp: Response? = firstResp
                var retriesOnUpstreamGap = 0
                val buffer = ByteArray(IO_BUFFER_SIZE)

                while (pos <= finalEnd) {
                    val chunkEnd = min(pos + CHUNK_BYTES - 1L, finalEnd)
                    val resp = currentResp ?: openUpstreamChunk(session, pos, chunkEnd)
                    currentResp = null
                    if (resp == null) break

                    val expectedInChunk = (chunkEnd - pos + 1L)
                    var copied = 0L
                    var upstreamFailed = false
                    resp.use { r ->
                        val bodyStream = r.body?.byteStream()
                        if (bodyStream == null) {
                            upstreamFailed = true
                            return@use
                        }
                        while (copied < expectedInChunk) {
                            val toRead = min(buffer.size.toLong(), expectedInChunk - copied).toInt()
                            val n = try {
                                bodyStream.read(buffer, 0, toRead)
                            } catch (_: IOException) {
                                upstreamFailed = true
                                break
                            }
                            if (n <= 0) break
                            // Client disconnect stops streaming immediately without error.
                            output.write(buffer, 0, n)
                            copied += n
                        }
                        output.flush()
                    }
                    pos += copied
                    if (copied == 0L) {
                        if (upstreamFailed && retriesOnUpstreamGap < 2) {
                            retriesOnUpstreamGap++
                            continue
                        }
                        break
                    }
                    retriesOnUpstreamGap = 0
                }
            } catch (_: IOException) {
                firstResp.close()
            }
        }
    }

    private fun readAsciiLine(input: InputStream): String? {
        val out = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("US-ASCII")
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
        }
        return out.toString("US-ASCII")
    }

    private fun writeSimpleResponse(output: OutputStream, code: Int, message: String, extraHeaders: String = "") {
        val body = message.toByteArray(Charsets.US_ASCII)
        val header = "HTTP/1.1 $code $message\r\nContent-Length: ${body.size}\r\n${extraHeaders}Connection: close\r\n\r\n"
        runCatching {
            output.write(header.toByteArray(Charsets.US_ASCII))
            output.write(body)
            output.flush()
        }
    }

    internal fun buildCookieHeader(sid: String?, auth: String?): String =
        listOfNotNull(
            sid?.takeIf { it.isNotBlank() }?.let { "cvd_video_sid=$it" },
            auth?.takeIf { it.isNotBlank() }?.let { "cvd_video_auth=$it" },
        ).joinToString("; ")

    internal fun parseCookie(setCookieHeaders: List<String>, cookieName: String): String? {
        val prefix = "$cookieName="
        for (raw in setCookieHeaders) {
            val pair = raw.substringBefore(';').trim()
            if (pair.startsWith(prefix)) {
                return pair.substring(prefix.length).trim().takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    internal fun parseRange(header: String?): RangeSpec? {
        val raw = header?.trim() ?: return null
        if (!raw.startsWith("bytes=", ignoreCase = true)) return null
        val spec = raw.substring(6).substringBefore(',').trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val left = spec.substring(0, dash).trim()
        val right = spec.substring(dash + 1).trim()
        return when {
            left.isEmpty() && right.isNotEmpty() -> {
                val suffix = right.toLongOrNull()?.takeIf { it > 0L } ?: return null
                RangeSpec(null, null, suffix)
            }
            left.isNotEmpty() && right.isEmpty() -> {
                val start = left.toLongOrNull()?.takeIf { it >= 0L } ?: return null
                RangeSpec(start, null, null)
            }
            left.isNotEmpty() && right.isNotEmpty() -> {
                val start = left.toLongOrNull()?.takeIf { it >= 0L } ?: return null
                val end = right.toLongOrNull()?.takeIf { it >= start } ?: return null
                RangeSpec(start, end, null)
            }
            else -> null
        }
    }

    internal fun parseContentRange(header: String?): ContentRangeInfo? {
        val match = CONTENT_RANGE_RE.find(header ?: return null) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        if (total <= 0L || end < start) return null
        return ContentRangeInfo(start, end, total)
    }

    private val OPAQUE_PATH_RE = Regex("""^/[A-Za-z0-9_-]{43}$""")
    private val PATH_TOKEN_RE = Regex("""^/v/([A-Za-z0-9]+)\.mp4$""")
    private val CONTENT_RANGE_RE = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)
}
