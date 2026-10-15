package dev.xvin.palimpsest.core.crypto

import java.io.InputStream
import java.math.BigInteger
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-128-CTR with an all-zero initial counter, as used by Yandex Music's `encraw` transport.
 * CTR is seekable: to start at [offset] we advance the counter by offset/16 blocks and discard
 * the remainder, so streaming playback can jump anywhere in the file.
 */
object AesCtr {
    fun cipherAt(key: ByteArray, offset: Long): Cipher {
        val block = offset / 16
        val iv = BigInteger.valueOf(block).toByteArray().let { raw ->
            ByteArray(16).also { out ->
                val n = minOf(raw.size, 16)
                System.arraycopy(raw, raw.size - n, out, 16 - n, n)
            }
        }
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val skip = (offset % 16).toInt()
        if (skip > 0) cipher.update(ByteArray(skip))
        return cipher
    }

    /** Wraps [encrypted], whose first byte sits at [offset] of the encrypted file. */
    fun decrypting(encrypted: InputStream, key: ByteArray, offset: Long = 0): InputStream =
        CipherInputStream(encrypted, cipherAt(key, offset))
}
