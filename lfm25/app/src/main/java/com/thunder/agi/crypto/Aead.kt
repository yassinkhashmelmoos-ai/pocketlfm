package com.thunder.agi.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-256-GCM helpers. seal/open layout: iv(12) || ciphertext || tag(16). */
object Aead {
    private val rng = SecureRandom()
    private val EMPTY = ByteArray(0)

    fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray = EMPTY): ByteArray {
        require(key.size == 32) { "AES-256 key required" }
        val iv = ByteArray(12).also { rng.nextBytes(it) }
        return iv + gcm(Cipher.ENCRYPT_MODE, key, iv, plaintext, aad)
    }

    fun open(key: ByteArray, blob: ByteArray, aad: ByteArray = EMPTY): ByteArray {
        require(key.size == 32 && blob.size >= 28) { "bad input" }
        return gcm(Cipher.DECRYPT_MODE, key, blob.copyOfRange(0, 12), blob.copyOfRange(12, blob.size), aad)
    }

    /** Caller-supplied 12-byte nonce (SecureChannel uses strict counters). No IV prefix in the output. */
    fun sealWithNonce(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray =
        gcm(Cipher.ENCRYPT_MODE, key, nonce, plaintext, aad)

    fun openWithNonce(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray =
        gcm(Cipher.DECRYPT_MODE, key, nonce, ciphertext, aad)

    private fun gcm(mode: Int, key: ByteArray, iv: ByteArray, data: ByteArray, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(aad)
        return c.doFinal(data)
    }
}

/** HKDF-SHA256 (RFC 5869). */
object Hkdf {
    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }
}
