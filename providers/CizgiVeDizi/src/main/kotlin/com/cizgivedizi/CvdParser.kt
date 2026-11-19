package com.cizgivedizi

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import java.io.Reader
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

internal data class CatalogEntry(
    val id: String,
    val type: String,
    val title: String,
    val englishTitle: String,
    val hay: String,
    val href: String,
    val poster: String,
    val genres: List<String>,
    val channel: String,
)

internal object CvdParser {
    private val jsonFactory = JsonFactory()

    fun extractBridgeKey(embedUrl: String?): String? {
        if (embedUrl.isNullOrBlank()) return null
        val raw = Regex("""[?&]k=([^&#]+)""").find(embedUrl)?.groupValues?.get(1) ?: return null
        return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw).takeIf { it.isNotBlank() }
    }

    fun extractPathId(url: String, prefix: String): String? {
        val idx = url.indexOf(prefix)
        if (idx < 0) return null
        val raw = url.substring(idx + prefix.length).substringBefore('/').substringBefore('?')
        return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw).takeIf { it.isNotBlank() }
    }

    fun encodeSeriesData(baseUrl: String, showId: String, overallKey: String): String =
        "$baseUrl/#cvd-dizi/${URLEncoder.encode(showId, "UTF-8")}/${URLEncoder.encode(overallKey, "UTF-8")}"

    fun decodeSeriesData(data: String): Pair<String, String>? {
        val tail = data.substringAfter("#cvd-dizi/", "")
        if (tail.isEmpty()) return null
        val parts = tail.split('/', limit = 2)
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return null
        val showId = runCatching { URLDecoder.decode(parts[0], "UTF-8") }.getOrNull() ?: return null
        val key = runCatching { URLDecoder.decode(parts[1], "UTF-8") }.getOrNull() ?: return null
        return showId to key
    }

    fun advancePastMarker(reader: Reader, marker: String): Boolean {
        var matched = 0
        while (true) {
            val ch = reader.read()
            if (ch < 0) return false
            val c = ch.toChar()
            if (c == marker[matched]) {
                matched++
                if (matched == marker.length) return true
            } else if (matched > 0) {
                matched = if (c == marker[0]) 1 else 0
            }
        }
    }

    fun parsePoolData(reader: Reader): List<CatalogEntry> {
        val out = ArrayList<CatalogEntry>(2600)
        jsonFactory.createParser(reader).use { p ->
            if (p.nextToken() != JsonToken.START_ARRAY) return out
            while (p.nextToken() == JsonToken.START_OBJECT) {
                var id = ""
                var type = ""
                var name = ""
                var hay = ""
                var hayEn = ""
                var genresRaw = ""
                var kanalText = ""
                var href = ""
                var poster = ""
                var label = ""

                while (p.nextToken() != JsonToken.END_OBJECT) {
                    val field = p.currentName()
                    p.nextToken()
                    when (field) {
                        "dataset" -> {
                            if (p.currentToken == JsonToken.START_OBJECT) {
                                while (p.nextToken() != JsonToken.END_OBJECT) {
                                    val df = p.currentName()
                                    p.nextToken()
                                    when (df) {
                                        "id" -> id = p.valueAsString.orEmpty()
                                        "type" -> type = p.valueAsString.orEmpty()
                                        "name" -> name = p.valueAsString.orEmpty()
                                        "hay" -> hay = p.valueAsString.orEmpty()
                                        "hayEn" -> hayEn = p.valueAsString.orEmpty()
                                        "genresraw" -> genresRaw = p.valueAsString.orEmpty()
                                        "kanaltext" -> kanalText = p.valueAsString.orEmpty()
                                        else -> p.skipChildren()
                                    }
                                }
                            } else {
                                p.skipChildren()
                            }
                        }
                        "href" -> href = p.valueAsString.orEmpty()
                        "poster" -> poster = p.valueAsString.orEmpty()
                        "label" -> label = p.valueAsString.orEmpty()
                        else -> p.skipChildren()
                    }
                }

                if (id.isNotBlank() && href.isNotBlank() && type != "other") {
                    val displayTitle = label.ifBlank { name }.trim()
                    if (displayTitle.isNotBlank()) {
                        out.add(
                            CatalogEntry(
                                id = id,
                                type = type,
                                title = displayTitle,
                                englishTitle = extractEnglishTitle(id, hayEn),
                                hay = hay,
                                href = href,
                                poster = poster,
                                genres = genresRaw.split('|').map { it.trim() }.filter { it.isNotEmpty() },
                                channel = kanalText.trim(),
                            ),
                        )
                    }
                }
            }
        }
        return out
    }

    fun extractEnglishTitle(id: String, hayEn: String): String {
        val trimmed = hayEn.trim()
        if (trimmed.isEmpty() || id.isBlank()) return ""
        val lower = trimmed.lowercase(Locale.ROOT)
        val lowerId = id.lowercase(Locale.ROOT)
        val prefix = "$lowerId "
        if (!lower.startsWith(prefix)) return ""
        val rest = trimmed.substring(prefix.length)
        val restLower = lower.substring(prefix.length)
        val asciiId = toAsciiTr(lowerId)
        val idx1 = restLower.indexOf(" $lowerId ")
        val idx2 = if (asciiId != lowerId) restLower.indexOf(" $asciiId ") else -1
        val cut = listOf(idx1, idx2).filter { it > 0 }.minOrNull() ?: return ""
        return rest.substring(0, cut).trim()
    }

    fun toAsciiTr(value: String): String = value
        .replace('ı', 'i')
        .replace('ğ', 'g')
        .replace('ü', 'u')
        .replace('ş', 's')
        .replace('ö', 'o')
        .replace('ç', 'c')

    fun normalizeSearch(value: String): String =
        toAsciiTr(value.lowercase(Locale("tr")))
            .replace('İ', 'i')
            .replace('I', 'i')
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
}
