package com.bigbull.common

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CryptoTest {
    @Test
    fun hexToBytesDecodesPairs() {
        assertArrayEquals(byteArrayOf(0x00, 0x0f, 0xff.toByte()), "000fff".hexToBytes())
    }

    @Test
    fun aesGcmRoundTrip() {
        val key = "i4C7R2fXGocdYgFLzCbDlsJjukf8G58b".toByteArray(Charsets.UTF_8)
        val iv = ByteArray(12) { (it + 1).toByte() }
        val plain = "{version}q=naruto".toByteArray(Charsets.UTF_8)
        val sealed = aesGcmEncrypt(plain, key, iv)
        assertEquals("{version}q=naruto", String(aesGcmDecrypt(sealed, key, iv), Charsets.UTF_8))
    }

    @Test
    fun aesCbcRoundTrip() {
        val key = "0123456789abcdef0123456789abcdef".toByteArray(Charsets.ISO_8859_1)
        val iv = "abcdef9876543210".toByteArray(Charsets.ISO_8859_1)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
        val ct = cipher.doFinal("https://vidrame.pro/embed/1".toByteArray(Charsets.UTF_8))
        assertEquals("https://vidrame.pro/embed/1", String(aesCbcDecrypt(ct, key, iv), Charsets.UTF_8))
    }

    @Test
    fun pbkdf2AesCbcDecryptsAndRejectsWrongPassphrase() {
        val saltHex = "00112233445566778899aabbccddeeff"
        val ivHex = "fedcba9876543210fedcba9876543210"
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            .generateSecret(PBEKeySpec("secret-k".toCharArray(), saltHex.hexToBytes(), 999, 256))
            .encoded
        val ct = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ivHex.hexToBytes()))
            doFinal("https://four.pichive.online/iframe.php?v=abc".toByteArray(Charsets.UTF_8))
        }
        assertEquals(
            "https://four.pichive.online/iframe.php?v=abc",
            pbkdf2AesCbcDecrypt(ct, "secret-k", saltHex, ivHex),
        )
        assertNull(pbkdf2AesCbcDecrypt(ct, "wrong-k", saltHex, ivHex))
    }

    @Test
    fun evpMd5AesCbcMatchesBePlayerDerivation() {
        val saltHex = "0102030405060708"
        val ivHex = "101112131415161718191a1b1c1d1e1f"
        val pw = "bePlayerHash".toByteArray(Charsets.UTF_8)
        val salt = saltHex.hexToBytes()
        fun md5(vararg p: ByteArray) = MessageDigest.getInstance("MD5").apply { p.forEach(::update) }.digest()
        val f = md5(pw, salt)
        val key = (f + md5(f, pw, salt)).copyOf(32)
        val ct = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ivHex.hexToBytes()))
            doFinal("""{"video_location":"https://cdn.example/master.m3u8"}""".toByteArray(Charsets.UTF_8))
        }
        assertEquals(
            """{"video_location":"https://cdn.example/master.m3u8"}""",
            evpMd5AesCbcDecrypt(ct, "bePlayerHash", saltHex, ivHex),
        )
    }

    @Test
    fun parseHlsVariantsSkipsDemuxedAudioMasters() {
        val muxed = "#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=1280x720\n720/index.m3u8"
        assertEquals(listOf(720 to "https://cdn.example/720/index.m3u8"), parseHlsVariants("https://cdn.example/master.m3u8", muxed))
        val demuxed = "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",URI=\"a.m3u8\"\n#EXT-X-STREAM-INF:RESOLUTION=1280x720,AUDIO=\"a\"\nv.m3u8"
        assertEquals(emptyList<Pair<Int, String>>(), parseHlsVariants("https://cdn.example/master.m3u8", demuxed))
    }
}
