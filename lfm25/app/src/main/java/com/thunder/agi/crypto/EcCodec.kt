package com.thunder.agi.crypto

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec

/**
 * A signing identity. Production: hardware-backed Android Keystore key.
 * Tests: a software key. Signatures are ECDSA-P256-SHA256 in raw 64-byte (r||s) form.
 */
interface Identity {
    /** 33-byte SEC1-compressed P-256 public key. */
    val publicKey: ByteArray
    fun sign(message: ByteArray): ByteArray
}

/** P-256 helpers: compact key encoding and raw signatures (saves ~70 bytes per transaction vs X.509/DER). */
object EcCodec {
    private val P = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16)
    private val B = BigInteger("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B", 16)
    private val THREE = BigInteger.valueOf(3)

    private val params: ECParameterSpec by lazy {
        val ap = AlgorithmParameters.getInstance("EC")
        ap.init(ECGenParameterSpec("secp256r1"))
        ap.getParameterSpec(ECParameterSpec::class.java)
    }

    private val keyCache = object : LinkedHashMap<String, PublicKey>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PublicKey>?) = size > 256
    }

    fun fixed(v: BigInteger, len: Int): ByteArray {
        val b = v.toByteArray()
        return when {
            b.size == len -> b
            b.size > len -> {
                require(b.size == len + 1 && b[0] == 0.toByte()) { "value too large" }
                b.copyOfRange(1, b.size)
            }
            else -> ByteArray(len).also { System.arraycopy(b, 0, it, len - b.size, b.size) }
        }
    }

    fun compress(pub: ECPublicKey): ByteArray {
        val out = ByteArray(33)
        out[0] = if (pub.w.affineY.testBit(0)) 3 else 2
        System.arraycopy(fixed(pub.w.affineX, 32), 0, out, 1, 32)
        return out
    }

    /** Returns null if the bytes are not a valid point on P-256. */
    fun decompress(p33: ByteArray): PublicKey? {
        if (p33.size != 33 || (p33[0] != 2.toByte() && p33[0] != 3.toByte())) return null
        val cacheKey = p33.joinToString("") { "%02x".format(it) }
        synchronized(keyCache) { keyCache[cacheKey]?.let { return it } }
        val x = BigInteger(1, p33.copyOfRange(1, 33))
        if (x >= P) return null
        val rhs = (x.pow(3) - THREE * x + B).mod(P)
        val y0 = rhs.modPow(P.add(BigInteger.ONE).shiftRight(2), P)
        if (y0.multiply(y0).mod(P) != rhs) return null            // not on the curve
        val wantOdd = p33[0] == 3.toByte()
        val y = if (y0.testBit(0) == wantOdd) y0 else P.subtract(y0)
        val key = try {
            KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
        } catch (e: Exception) {
            return null
        }
        synchronized(keyCache) { keyCache[cacheKey] = key }
        return key
    }

    fun derToRaw(der: ByteArray): ByteArray {
        var i = 0
        require(der[i++] == 0x30.toByte()) { "bad DER" }
        var len = der[i++].toInt() and 0xFF
        if (len and 0x80 != 0) {
            val n = len and 0x7F
            len = 0
            repeat(n) { len = (len shl 8) or (der[i++].toInt() and 0xFF) }
        }
        require(der[i++] == 0x02.toByte()) { "bad DER r" }
        val rl = der[i++].toInt() and 0xFF
        val r = der.copyOfRange(i, i + rl); i += rl
        require(der[i++] == 0x02.toByte()) { "bad DER s" }
        val sl = der[i++].toInt() and 0xFF
        val s = der.copyOfRange(i, i + sl)
        return fixed(BigInteger(1, r), 32) + fixed(BigInteger(1, s), 32)
    }

    fun rawToDer(raw: ByteArray): ByteArray {
        require(raw.size == 64)
        val r = BigInteger(1, raw.copyOfRange(0, 32)).toByteArray()
        val s = BigInteger(1, raw.copyOfRange(32, 64)).toByteArray()
        val body = byteArrayOf(0x02, r.size.toByte()) + r + byteArrayOf(0x02, s.size.toByte()) + s
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    fun verify(pub33: ByteArray, message: ByteArray, sig64: ByteArray): Boolean {
        if (sig64.size != 64) return false
        val key = decompress(pub33) ?: return false
        return try {
            val s = Signature.getInstance("SHA256withECDSA")
            s.initVerify(key)
            s.update(message)
            s.verify(rawToDer(sig64))
        } catch (e: Exception) {
            false
        }
    }
}
