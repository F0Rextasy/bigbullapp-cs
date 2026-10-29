package android.util

import java.util.Base64

object Base64 {
    const val DEFAULT = 0
    const val NO_WRAP = 2

    fun decode(str: String, flags: Int): ByteArray = Base64.getDecoder().decode(str)
    fun encodeToString(bytes: ByteArray, flags: Int): String = Base64.getEncoder().encodeToString(bytes)
}
