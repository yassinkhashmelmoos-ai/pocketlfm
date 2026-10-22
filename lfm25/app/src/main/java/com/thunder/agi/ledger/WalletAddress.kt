package com.thunder.agi.ledger

import com.thunder.agi.util.sha256
import java.math.BigInteger

/**
 * Wallet address = Base58Check( 0x3F || H("THUNDER-ADDR-V2" | ecPub33 | H(kyberPub)[0..16])[0..20) ).
 *
 * The address commits to BOTH the signing key and the Kyber key, so anyone holding an address can later
 * verify (a) that a transaction's signing key belongs to it and (b) that an announced Kyber key is genuine.
 * Version byte 0x3F gives a stable first character 'S' and 34 characters (checked in tests).
 */
object WalletAddress {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    const val VERSION = 0x3F
    const val PAYLOAD_LEN = 20
    private const val RAW_LEN = 1 + PAYLOAD_LEN + 4
    private val DOMAIN = "THUNDER-ADDR-V2".toByteArray()
    private val B58 = BigInteger.valueOf(58)

    fun kyberHash16(kyberPublicKey: ByteArray): ByteArray = sha256(kyberPublicKey).copyOf(16)

    fun payload(ecPub33: ByteArray, kyberHash16: ByteArray): ByteArray {
        require(ecPub33.size == 33 && kyberHash16.size == 16) { "bad key sizes" }
        return sha256(DOMAIN, ecPub33, kyberHash16).copyOf(PAYLOAD_LEN)
    }

    fun derive(ecPub33: ByteArray, kyberPublicKey: ByteArray): String =
        fromPayload(payload(ecPub33, kyberHash16(kyberPublicKey)))

    fun fromPayload(p20: ByteArray): String {
        require(p20.size == PAYLOAD_LEN)
        val body = byteArrayOf(VERSION.toByte()) + p20
        val check = sha256(sha256(body)).copyOf(4)
        return base58(body + check)
    }

    /** Returns the 20-byte payload, or null if the string is not a valid Thunder address. */
    fun toPayload(address: String): ByteArray? {
        if (address.length !in 30..36) return null
        val raw = unbase58(address) ?: return null
        if (raw.size != RAW_LEN || raw[0] != VERSION.toByte()) return null
        val body = raw.copyOfRange(0, 1 + PAYLOAD_LEN)
        val check = sha256(sha256(body)).copyOf(4)
        if (!check.contentEquals(raw.copyOfRange(1 + PAYLOAD_LEN, RAW_LEN))) return null
        return raw.copyOfRange(1, 1 + PAYLOAD_LEN)
    }

    fun isValid(address: String) = toPayload(address) != null

    fun matches(address: String, ecPub33: ByteArray, kyberHash16: ByteArray): Boolean {
        val p = toPayload(address) ?: return false
        if (ecPub33.size != 33 || kyberHash16.size != 16) return false
        return p.contentEquals(payload(ecPub33, kyberHash16))
    }

    fun shortForm(address: String) =
        if (address.length > 14) "${address.take(6)}…${address.takeLast(4)}" else address

    private fun base58(input: ByteArray): String {
        var n = BigInteger(1, input)
        val sb = StringBuilder()
        while (n.signum() > 0) {
            val qr = n.divideAndRemainder(B58)
            sb.append(ALPHABET[qr[1].toInt()])
            n = qr[0]
        }
        for (b in input) if (b == 0.toByte()) sb.append(ALPHABET[0]) else break
        return sb.reverse().toString()
    }

    private fun unbase58(s: String): ByteArray? {
        var n = BigInteger.ZERO
        for (c in s) {
            val d = ALPHABET.indexOf(c)
            if (d < 0) return null
            n = n.multiply(B58).add(BigInteger.valueOf(d.toLong()))
        }
        val raw = n.toByteArray()
        val trimmed = if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
        val leadingZeros = s.takeWhile { it == ALPHABET[0] }.length
        val out = ByteArray(leadingZeros + trimmed.size)
        System.arraycopy(trimmed, 0, out, leadingZeros, trimmed.size)
        return if (out.size > RAW_LEN) null else if (out.size < RAW_LEN) ByteArray(RAW_LEN - out.size) + out else out
    }
}
