package com.bigbull.common

// Auto-synced from autogen.common_deobfuscator_kt() (H4 single source of truth)
internal fun _commonDeobfB64(input: String): ByteArray? {
        val clean = Regex("""\s+""").replace(input.trim(), "").replace('-', '+').replace('_', '/').let { s ->
            val rem = s.length % 4
            if (rem == 0) s else s + "=".repeat(4 - rem)
        }
        if (clean.length < 4) return null
        val aBytes = runCatching { android.util.Base64.decode(clean, android.util.Base64.DEFAULT) }.getOrNull()
        if (aBytes != null && aBytes.isNotEmpty()) return aBytes
        return runCatching { java.util.Base64.getDecoder().decode(clean) }.getOrNull()
    }

internal fun _commonDeobfB64Str(input: String): String {
        val bytes = _commonDeobfB64(input) ?: return ""
        return runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
            ?: runCatching { String(bytes, Charsets.ISO_8859_1) }.getOrNull().orEmpty()
    }

internal fun _commonDeobfUnescapePacker(raw: String): String =
        Regex("""\\([\\'"nrt])""").replace(raw) { mr ->
            when (val ch = mr.groupValues[1]) {
                "n" -> "\n"
                "r" -> "\r"
                "t" -> "\t"
                else -> ch
            }
        }

internal fun _commonDeobfUnpackPacker(text: String, maxDepth: Int = 3): List<String> {
        if (!text.contains("eval", ignoreCase = true)) return emptyList()
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val rxSq = Regex(
            """eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*[dr]\s*\)\s*\{[\s\S]*?\}\s*\(\s*'((?:[^'\\]++|\\.)*+)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'((?:[^'\\]++|\\.)*+)'\.split\s*\(\s*'\|'\s*\)""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val rxDq = Regex(
            """eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*[dr]\s*\)\s*\{[\s\S]*?\}\s*\(\s*"((?:[^"\\]++|\\.)*+)"\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*"((?:[^"\\]++|\\.)*+)"\.split\s*\(\s*['"]\|['"]\s*\)""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val wordRe = Regex("""\b[0-9a-zA-Z]+\b""")
        fun encTok(value: Int, base: Int): String {
            if (value == 0) return "0"
            var n = value
            val sb = StringBuilder()
            while (n > 0) {
                sb.append(alphabet[n % base])
                n /= base
            }
            return sb.reverse().toString()
        }
        val out = ArrayList<String>()
        var frontier = listOf(text)
        for (depthIdx in 0 until maxOf(1, maxDepth)) {
            val nextFrontier = ArrayList<String>()
            for (chunk in frontier) {
                for (rx in listOf(rxSq, rxDq)) {
                    for (m in rx.findAll(chunk)) {
                        val base = m.groupValues[2].toIntOrNull() ?: continue
                        if (base !in 2..alphabet.length) continue
                        val p = _commonDeobfUnescapePacker(m.groupValues[1])
                        val keys = _commonDeobfUnescapePacker(m.groupValues[4]).split("|")
                        val table = HashMap<String, String>(keys.size * 2)
                        for (i in keys.indices) {
                            val tok = encTok(i, base)
                            table[tok] = keys[i].ifEmpty { tok }
                        }
                        val unpacked = wordRe.replace(p) { mr -> table[mr.value] ?: mr.value }
                        if (unpacked.isNotEmpty() && unpacked !in out) {
                            out += unpacked
                            if (unpacked.contains("eval", ignoreCase = true)) nextFrontier += unpacked
                        }
                    }
                }
            }
            if (nextFrontier.isEmpty()) break
            frontier = nextFrontier
        }
        return out
    }

internal fun _commonDeobfJsEscapes(text: String): String {
        if (text.isEmpty()) return ""
        var s = Regex("""\\x([0-9a-fA-F]{2})""").replace(text) { m ->
            m.groupValues[1].toInt(16).toChar().toString()
        }
        s = Regex("""\\u([0-9a-fA-F]{4})""").replace(s) { m ->
            m.groupValues[1].toInt(16).toChar().toString()
        }
        return s.replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }

internal fun _commonDeobfInteresting(txt: String): Boolean {
        if (txt.length < 8) return false
        val low = txt.lowercase()
        if (low.contains("http://") || low.contains("https://") || low.contains(".m3u8") ||
            low.contains(".mp4") || low.contains("<iframe") || low.contains("atob(") || low.contains("fromcharcode")
        ) return true
        return txt.trimStart().startsWith("{") && txt.contains("}") &&
            (low.contains("url") || low.contains("file") || low.contains("src") || low.contains("hls") || low.contains("kaynak"))
    }

fun deobfuscateCommonJs(rawInput: String, maxPasses: Int = 3): Pair<List<String>, List<String>> {
        if (rawInput.isBlank()) return emptyList<String>() to emptyList()
        val texts = ArrayList<String>()
        val seenTexts = HashSet<String>()
        seenTexts.add(rawInput)
        var frontier = listOf(rawInput)
        val b64Check = Regex("""^[A-Za-z0-9+/=\s_-]{16,}$""")
        val revAtobRe = Regex("""atob\s*\(\s*(['"])([A-Za-z0-9+/=_-]{16,})\1\s*\.split\s*\(\s*['"]{2}\s*\)\s*\.reverse\s*\(\s*\)\s*\.join\s*\(\s*['"]{2}\s*\)\s*\)""")
        val atobRe = Regex("""atob\s*\(\s*(['"])([A-Za-z0-9+/=_-]{16,})\1\s*\)""")
        val pjs2Re = Regex("""['"]#2([A-Za-z0-9+/=_/\-\s]{20,})['"]""")
        val blobRe = Regex("""['"]([A-Za-z0-9+/=_-]{24,})['"]""")
        val hexRe = Regex("""['"]([0-9a-fA-F]{32,})['"]""")
        val fromCharRe = Regex("""String\.fromCharCode\s*\(\s*([0-9a-fA-FxX,\s]+)\)""")
        val pctRe = Regex("""(?:unescape|decodeURIComponent)\s*\(\s*(['"])([^'"]{12,})\1\s*\)""")
        val arr0xRe = Regex("""(?:var|let|const)\s+_0x[a-fA-F0-9]+\s*=\s*\[([\s\S]{10,8000}?)\]\s*;""")
        val fnArr0xRe = Regex("""function\s+_0x[a-fA-F0-9]+\s*\(\s*\)\s*\u007b\s*(?:var|let|const)\s+[a-zA-Z0-9_$]+\s*=\s*\[([\s\S]{10,16000}?)\]\s*;""")
        val elemRe = Regex("""(['"])((?:[^\\]++|\\.)*?)\1""")
        val joinArrRe = Regex("""\[\s*((?:['"][^'"\r\n]{1,120}['"]\s*,\s*){1,15}['"][^'"\r\n]{1,120}['"])\s*\]\s*\.join\s*\(\s*['"]{2}\s*\)""")
        val concatRe = Regex("""(?:['"](?:[^'"\\\r\n]|\\.){2,120}['"]\s*\+\s*){1,11}['"](?:[^'"\\\r\n]|\\.){2,120}['"]""")
        val xorKeyRe = Regex("""(?:^|[;,\s\u007b(])(?:var|let|const)?\s*(?:key|secret|pass|xorKey|xkey|k)\s*=\s*(['"])([^'"\\\r\n]{3,32})\1""", RegexOption.IGNORE_CASE)
        val intArrRe = Regex("""\[\s*((?:\d{1,3}\s*,\s*){11,}\d{1,3})\s*\]""")

        fun addLayer(valStr: String, nextQ: MutableList<String>) {
            if (valStr.isNotEmpty() && seenTexts.add(valStr)) {
                texts += valStr
                nextQ += valStr
            }
        }

        for (passIdx in 0 until maxOf(1, maxPasses)) {
            val nextQ = ArrayList<String>()
            for (chunk in frontier) {
                for (up in _commonDeobfUnpackPacker(chunk, maxPasses)) addLayer(up, nextQ)
                val esc = _commonDeobfJsEscapes(chunk)
                if (esc != chunk) addLayer(esc, nextQ)

                for (fc in fromCharRe.findAll(chunk)) {
                    val nums = fc.groupValues[1].split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    if (nums.size < 8) continue
                    val sb = StringBuilder()
                    var ok = true
                    for (tok in nums) {
                        val v = if (tok.startsWith("0x", ignoreCase = true)) tok.substring(2).toIntOrNull(16) else tok.toIntOrNull()
                        if (v == null || v <= 0 || v > 0xFFFF) { ok = false; break }
                        sb.append(v.toChar())
                    }
                    if (ok && sb.isNotEmpty()) addLayer(sb.toString(), nextQ)
                }

                for (pm in pctRe.findAll(chunk)) {
                    val rawP = pm.groupValues[2]
                    if (!rawP.contains('%')) continue
                    val u16 = Regex("""%u([0-9a-fA-F]{4})""").replace(rawP) { hm -> hm.groupValues[1].toInt(16).toChar().toString() }
                    val dec = runCatching { java.net.URLDecoder.decode(u16.replace("+", "%2B"), "UTF-8") }.getOrNull().orEmpty()
                    if (dec.isNotEmpty() && dec != rawP) addLayer(dec, nextQ)
                }

                for (rm in revAtobRe.findAll(chunk)) {
                    val dec = _commonDeobfB64Str(rm.groupValues[2].reversed())
                    if (dec.isNotEmpty()) {
                        addLayer(dec, nextQ)
                        val dec2 = _commonDeobfB64Str(dec)
                        if (_commonDeobfInteresting(dec2)) addLayer(dec2, nextQ)
                    }
                }

                for (am in atobRe.findAll(chunk)) {
                    val blob = am.groupValues[2]
                    for (cand in listOf(blob, blob.reversed())) {
                        val dec = _commonDeobfB64Str(cand)
                        if (_commonDeobfInteresting(dec)) addLayer(dec, nextQ)
                        else if (b64Check.matches(dec.trim())) {
                            for (dec2 in listOf(_commonDeobfB64Str(dec), _commonDeobfB64Str(dec.trim().reversed()))) {
                                if (_commonDeobfInteresting(dec2)) addLayer(dec2, nextQ)
                            }
                        }
                    }
                }

                for (bm in blobRe.findAll(chunk)) {
                    val blob = bm.groupValues[1]
                    for (cand in listOf(blob, blob.reversed())) {
                        val dec = _commonDeobfB64Str(cand)
                        if (_commonDeobfInteresting(dec)) addLayer(dec, nextQ)
                        else if (b64Check.matches(dec.trim())) {
                            for (dec2 in listOf(_commonDeobfB64Str(dec), _commonDeobfB64Str(dec.trim().reversed()))) {
                                if (_commonDeobfInteresting(dec2)) addLayer(dec2, nextQ)
                            }
                        }
                    }
                }

                val standaloneB64 = chunk.trim()
                if (standaloneB64.length >= 24 && Regex("""^[A-Za-z0-9+/=_-]{24,}$""").matches(standaloneB64)) {
                    for (cand in listOf(standaloneB64, standaloneB64.reversed())) {
                        val dec = _commonDeobfB64Str(cand)
                        if (_commonDeobfInteresting(dec)) addLayer(dec, nextQ)
                    }
                }

                for (p2m in pjs2Re.findAll(chunk)) {
                    val cleaned2 = Regex("""[/\s]+""").replace(p2m.groupValues[1], "")
                    val dec2 = _commonDeobfB64Str(cleaned2)
                    if (_commonDeobfInteresting(dec2)) addLayer(dec2, nextQ)
                }

                for (hm in hexRe.findAll(chunk)) {
                    val hb = hm.groupValues[1]
                    if (hb.length % 2 != 0) continue
                    val bytes = runCatching {
                        ByteArray(hb.length / 2) { idx -> hb.substring(idx * 2, idx * 2 + 2).toInt(16).toByte() }
                    }.getOrNull() ?: continue
                    val plain = String(bytes, Charsets.UTF_8)
                    if (_commonDeobfInteresting(plain)) {
                        addLayer(plain, nextQ)
                    } else {
                        val step2 = _commonDeobfB64Str(String(bytes, Charsets.ISO_8859_1).reversed())
                        if (step2.isNotEmpty()) {
                            for (sh in listOf(3, 1, 2, 4, 5, 6, 7)) {
                                val shifted = step2.map { ch -> ((ch.code - sh) and 0xFF).toChar() }.joinToString("")
                                val dec = _commonDeobfB64Str(shifted)
                                if (_commonDeobfInteresting(dec)) {
                                    addLayer(dec, nextQ)
                                    break
                                }
                            }
                        }
                    }
                }

                for (rx0x in listOf(arr0xRe, fnArr0xRe)) {
                    for (ax in rx0x.findAll(chunk)) {
                        val decElems = elemRe.findAll(ax.groupValues[1]).map { _commonDeobfJsEscapes(it.groupValues[2]) }.toList()
                        if (decElems.isEmpty()) continue
                        addLayer(decElems.joinToString("\n"), nextQ)
                        val joined = decElems.joinToString("")
                        if (_commonDeobfInteresting(joined)) addLayer(joined, nextQ)
                        for (de in decElems) {
                            if (de.length >= 16 && b64Check.matches(de)) {
                                for (cand in listOf(de, de.reversed())) {
                                    val bdec = _commonDeobfB64Str(cand)
                                    if (_commonDeobfInteresting(bdec)) addLayer(bdec, nextQ)
                                }
                            }
                        }
                        val ring = decElems + decElems.take(6)
                        for (idx in decElems.indices) {
                            val lowEl = decElems[idx].lowercase()
                            if (lowEl.startsWith("http://") || lowEl.startsWith("https://") || lowEl.contains("://")) {
                                for (span in 2..minOf(6, ring.size - idx)) {
                                    val cand = ring.subList(idx, idx + span).joinToString("")
                                    if (_commonDeobfInteresting(cand)) {
                                        addLayer(cand, nextQ)
                                        break
                                    }
                                }
                            }
                        }
                    }
                }

                for (jm in joinArrRe.findAll(chunk)) {
                    val parts = elemRe.findAll(jm.groupValues[1]).map { _commonDeobfJsEscapes(it.groupValues[2]) }.joinToString("")
                    if (_commonDeobfInteresting(parts) || (chunk.contains("atob", ignoreCase = true) && parts.length >= 24 && b64Check.matches(parts.trim()))) addLayer(parts, nextQ)
                }
                for (cm in concatRe.findAll(chunk)) {
                    val elems = elemRe.findAll(cm.value).map { _commonDeobfJsEscapes(it.groupValues[2]) }.toList()
                    if (elems.size >= 2) {
                        val parts = elems.joinToString("")
                        if (_commonDeobfInteresting(parts) || (chunk.contains("atob", ignoreCase = true) && parts.length >= 24 && b64Check.matches(parts.trim()))) addLayer(parts, nextQ)
                    }
                }

                val lowChunk = chunk.lowercase()
                val varBindRe = Regex("""(?:var|let|const)\s+([A-Za-z_$][\w$]{0,31})\s*=\s*(['"])((?:[^'"\\\r\n]|\\.){1,200})\2""")
                val varMap = LinkedHashMap<String, String>()
                for (vm in varBindRe.findAll(chunk)) {
                    varMap[vm.groupValues[1]] = _commonDeobfJsEscapes(vm.groupValues[3])
                    if (varMap.size >= 40) break
                }
                if (varMap.size >= 2) {
                    val idPlusRe = Regex("""\b[A-Za-z_$][\w$]{0,31}(?:\s*\+\s*[A-Za-z_$][\w$]{0,31}){1,7}\b""")
                    for (pm in idPlusRe.findAll(chunk)) {
                        val pIds = pm.value.split("+").map { it.trim() }
                        if (pIds.size >= 2 && pIds.all { varMap.containsKey(it) }) {
                            val vJoin = pIds.joinToString("") { varMap[it] ?: "" }
                            val stJoin = vJoin.trim()
                            if (_commonDeobfInteresting(vJoin) || (lowChunk.contains("atob") && stJoin.length >= 24 && Regex("""^[A-Za-z0-9+/=_-]+$""").matches(stJoin))) {
                                addLayer(vJoin, nextQ)
                            }
                        }
                    }
                }
                if (lowChunk.contains("rot13") || (lowChunk.contains("charcodeat") && chunk.contains("13")) || Regex("""replace\s*\(\s*/\[a-zA-Z\]/g""").containsMatchIn(chunk)) {
                    val r13Re = Regex("""['"]([A-Za-z0-9:/._?=&%+\-]{16,})['"]""")
                    for (rm in r13Re.findAll(chunk)) {
                        val rDec = buildString {
                            for (ch13 in rm.groupValues[1]) {
                                append(when (ch13) {
                                    in 'A'..'Z' -> ('A'.code + (ch13.code - 'A'.code + 13) % 26).toChar()
                                    in 'a'..'z' -> ('a'.code + (ch13.code - 'a'.code + 13) % 26).toChar()
                                    else -> ch13
                                })
                            }
                        }
                        if (_commonDeobfInteresting(rDec)) addLayer(rDec, nextQ)
                    }
                }
                if (lowChunk.contains("charcodeat") && chunk.contains("^") && (chunk.contains("%") || lowChunk.contains(".length"))) {
                    val strKeys = xorKeyRe.findAll(chunk).map { it.groupValues[2] }.distinct().take(6).toList()
                    if (strKeys.isNotEmpty()) {
                        val payloads = ArrayList<ByteArray>()
                        for (bm in blobRe.findAll(chunk).take(20)) {
                            _commonDeobfB64(bm.groupValues[1])?.let { payloads += it }
                        }
                        for (hm in hexRe.findAll(chunk).take(12)) {
                            val hb = hm.groupValues[1]
                            if (hb.length % 2 == 0) {
                                runCatching {
                                    ByteArray(hb.length / 2) { idx -> hb.substring(idx * 2, idx * 2 + 2).toInt(16).toByte() }
                                }.getOrNull()?.let { payloads += it }
                            }
                        }
                        for (am in intArrRe.findAll(chunk).take(12)) {
                            val vals = am.groupValues[1].split(",").mapNotNull { it.trim().toIntOrNull() }
                            if (vals.isNotEmpty() && vals.all { it in 0..255 }) {
                                payloads += ByteArray(vals.size) { i -> vals[i].toByte() }
                            }
                        }
                        for (rawBytes in payloads) {
                            for (sk in strKeys) {
                                val kb = sk.toByteArray(Charsets.ISO_8859_1)
                                if (kb.isEmpty()) continue
                                val outBytes = ByteArray(rawBytes.size) { i ->
                                    (rawBytes[i].toInt() xor kb[i % kb.size].toInt()).toByte()
                                }
                                val dec = String(outBytes, Charsets.UTF_8)
                                if (_commonDeobfInteresting(dec)) {
                                    addLayer(dec, nextQ)
                                    break
                                }
                            }
                        }
                    }
                }
            }
            if (nextQ.isEmpty()) break
            frontier = nextQ
        }

        val urls = LinkedHashSet<String>()
        val mediaRe = Regex("""https?://[^"'\s<>\\]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>\\]*)?""")
        val vsMediaRe = Regex("""<(?:video|source)\b[^>]*?\b(?:src|data-src)\s*=\s*["'](https?://[^"']+?(?:\.(?:m3u8|mp4)|/hls/|playlist|master)[^"']*)["']""", RegexOption.IGNORE_CASE)
        val hlsTypeRe1 = Regex(""""(?:file|src|url)"\s*:\s*"(https?://[^"]+)"[^\u007b\u007d]{0,160}"type"\s*:\s*"(?:application/x-mpegurl|application/vnd\.apple\.mpegurl|hls)"""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val hlsTypeRe2 = Regex(""""type"\s*:\s*"(?:application/x-mpegurl|application/vnd\.apple\.mpegurl|hls)"[^\u007b\u007d]{0,160}"(?:file|src|url)"\s*:\s*"(https?://[^"]+)"""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val iframeRe = Regex("""<iframe\b[^>]*?\b(?:src|data-src|vdo-src)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        for (cand in texts + rawInput) {
            val cleanC = cand.replace("\\/", "/")
            for (mm in mediaRe.findAll(cleanC)) urls.add(mm.value.trimEnd('\'', '"', ')', ';', ','))
            for (vm in vsMediaRe.findAll(cleanC)) urls.add(vm.groupValues[1].trim())
            for (ht1 in hlsTypeRe1.findAll(cleanC)) urls.add(ht1.groupValues[1].trim())
            for (ht2 in hlsTypeRe2.findAll(cleanC)) urls.add(ht2.groupValues[1].trim())
            for (im in iframeRe.findAll(cleanC)) {
                val u = im.groupValues[1].trim()
                if (!u.isNullOrBlank()) urls.add(u)
            }
        }
        return texts to urls.toList()
    }
