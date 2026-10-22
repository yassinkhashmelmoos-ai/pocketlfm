package com.thunder.agi.ledger

import com.thunder.agi.util.concat
import com.thunder.agi.util.sha256
import com.thunder.agi.util.toHex
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64

fun Long.toSDG(): Double = this / 1000.0
fun Long.fmtSDG(): String = "SDG %.3f".format(this.toSDG())
fun Double.toMilliSDG(): Long = Math.round(this * 1000.0)

/**
 * A signed transaction, parsed from its canonical wire form [raw].
 * Parsing only checks structure; [LedgerEngine] checks signatures, chain rules and balances.
 */
class Transaction internal constructor(
    val txid: String,
    val type: Type,
    val from: String,
    val to: String,
    val amountMilli: Long,
    val nonce: Long,
    val timestampSec: Long,
    val prevId8: ByteArray,
    val memo: String,
    val signature: ByteArray,
    val senderPubKey: ByteArray,
    val senderKyberHash: ByteArray,
    val raw: ByteArray,
    internal val bodyLen: Int
) {
    enum class Type(val code: Int) {
        TRANSFER(1), GENESIS(2);
        companion object { fun of(code: Int) = values().firstOrNull { it.code == code } }
    }

    /** The exact bytes that were signed. */
    val body: ByteArray get() = raw.copyOfRange(0, bodyLen)
    val isGenesis get() = type == Type.GENESIS
    override fun equals(other: Any?) = other is Transaction && other.txid == txid
    override fun hashCode() = txid.hashCode()
}

/**
 * Canonical binary transaction format (every transport uses these exact bytes):
 *
 *   body (signed):
 *     1  version (=1)          1  type (1 transfer, 2 genesis)
 *    20  from (address payload)  20  to (address payload)
 *     6  amount, milli-SDG (uint48)   4  nonce (uint32, per-sender sequence starting at 1)
 *     4  timestamp, seconds (uint32)  8  prevId8 = first 8 bytes of the sender's previous txid (0 for nonce 1)
 *     1  memoLen (<= 48)         m  memo (UTF-8, PUBLIC)
 *   trailer:
 *    64  signature (raw r||s)    33  signer's compressed P-256 public key    16  H(kyberPub)[0..16]
 *
 *   => 178 + memo bytes: at most 226 bytes. Fits a WiFi-Aware message (255) and one BLE extended advertisement.
 *   txid = SHA-256(body) (independent of the signature, so signature malleability cannot create duplicates).
 */
object TxWire {
    const val VERSION = 1
    const val MAX_MEMO_BYTES = 48
    const val BODY_FIXED = 65
    const val SIG_LEN = 64
    const val PUB_LEN = 33
    const val KH_LEN = 16
    const val TRAILER = SIG_LEN + PUB_LEN + KH_LEN
    const val MIN_WIRE = BODY_FIXED + TRAILER
    const val MAX_WIRE = MIN_WIRE + MAX_MEMO_BYTES
    const val MAX_AMOUNT_MILLI = 1_000_000_000_000L          // 1 billion SDG per transaction hard cap
    private const val UINT48_MAX = (1L shl 48) - 1
    private const val UINT32_MAX = 0xFFFFFFFFL
    private val DOMAIN = "THUNDER-TX-V1".toByteArray()

    fun signingMessage(body: ByteArray): ByteArray = concat(DOMAIN, body)
    fun txidOf(body: ByteArray): String = sha256(body).toHex()

    fun buildBody(
        type: Transaction.Type, fromPayload: ByteArray, toPayload: ByteArray, amountMilli: Long,
        nonce: Long, timestampSec: Long, prevId8: ByteArray, memoUtf8: ByteArray
    ): ByteArray {
        require(fromPayload.size == WalletAddress.PAYLOAD_LEN && toPayload.size == WalletAddress.PAYLOAD_LEN)
        require(amountMilli in 1..MAX_AMOUNT_MILLI) { "amount out of range" }
        require(nonce in 1..UINT32_MAX && timestampSec in 0..UINT32_MAX) { "nonce/timestamp out of range" }
        require(prevId8.size == 8) { "prevId8 must be 8 bytes" }
        require(memoUtf8.size <= MAX_MEMO_BYTES) { "memo too long" }
        val bb = ByteBuffer.allocate(BODY_FIXED + memoUtf8.size)
        bb.put(VERSION.toByte()).put(type.code.toByte())
        bb.put(fromPayload).put(toPayload)
        bb.put(((amountMilli ushr 40) and 0xFF).toByte()).put(((amountMilli ushr 32) and 0xFF).toByte())
        bb.putInt(amountMilli.toInt())
        bb.putInt(nonce.toInt()).putInt(timestampSec.toInt())
        bb.put(prevId8).put(memoUtf8.size.toByte()).put(memoUtf8)
        return bb.array()
    }

    fun assemble(body: ByteArray, sig64: ByteArray, pub33: ByteArray, kyberHash16: ByteArray): ByteArray {
        require(sig64.size == SIG_LEN && pub33.size == PUB_LEN && kyberHash16.size == KH_LEN)
        return concat(body, sig64, pub33, kyberHash16)
    }

    /** Structural parse. Returns null for anything malformed. Does NOT verify the signature. */
    fun decode(raw: ByteArray): Transaction? {
        if (raw.size < MIN_WIRE || raw.size > MAX_WIRE) return null
        val bb = ByteBuffer.wrap(raw)
        if (bb.get().toInt() != VERSION) return null
        val type = Transaction.Type.of(bb.get().toInt() and 0xFF) ?: return null
        val fromP = ByteArray(20).also { bb.get(it) }
        val toP = ByteArray(20).also { bb.get(it) }
        val amount = ((bb.get().toLong() and 0xFF) shl 40) or ((bb.get().toLong() and 0xFF) shl 32) or
            (bb.int.toLong() and UINT32_MAX)
        val nonce = bb.int.toLong() and UINT32_MAX
        val ts = bb.int.toLong() and UINT32_MAX
        val prev8 = ByteArray(8).also { bb.get(it) }
        val memoLen = bb.get().toInt() and 0xFF
        if (memoLen > MAX_MEMO_BYTES) return null
        val bodyLen = BODY_FIXED + memoLen
        if (raw.size != bodyLen + TRAILER) return null
        val memoBytes = ByteArray(memoLen).also { bb.get(it) }
        val memo = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(memoBytes)).toString()
        } catch (e: CharacterCodingException) { return null }
        val sig = ByteArray(SIG_LEN).also { bb.get(it) }
        val pub = ByteArray(PUB_LEN).also { bb.get(it) }
        val kh = ByteArray(KH_LEN).also { bb.get(it) }
        if (amount < 1 || amount > UINT48_MAX || nonce < 1) return null
        return Transaction(
            txid = txidOf(raw.copyOfRange(0, bodyLen)), type = type,
            from = WalletAddress.fromPayload(fromP), to = WalletAddress.fromPayload(toP),
            amountMilli = amount, nonce = nonce, timestampSec = ts, prevId8 = prev8, memo = memo,
            signature = sig, senderPubKey = pub, senderKyberHash = kh, raw = raw, bodyLen = bodyLen
        )
    }
}

/**
 * Text armor so a transaction can travel through WhatsApp / SMS / QR: "⚡TX1:<base64url>".
 * A whole signed transaction is ~240-300 characters, i.e. ONE short message.
 */
object TxText {
    const val TAG = "TX1:"
    private val RE = Regex("TX1:([A-Za-z0-9_-]{230,310})")
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()

    fun encode(raw: ByteArray): String = "⚡" + TAG + enc.encodeToString(raw)

    /** Finds every armored transaction inside arbitrary text (a message may contain several). */
    fun extract(text: String): List<ByteArray> =
        RE.findAll(text).mapNotNull { m ->
            try { dec.decode(m.groupValues[1]).takeIf { it.size in TxWire.MIN_WIRE..TxWire.MAX_WIRE } }
            catch (e: IllegalArgumentException) { null }
        }.toList()
}

/** Length-prefixed concatenation of raw transactions, for bulk transfer via ChunkedTransferProtocol. */
object TxBundle {
    fun encode(raws: List<ByteArray>): ByteArray {
        val bb = ByteBuffer.allocate(raws.sumOf { 2 + it.size })
        for (r in raws) { bb.putShort(r.size.toShort()); bb.put(r) }
        return bb.array()
    }

    fun decode(bytes: ByteArray, max: Int = 5000): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val bb = ByteBuffer.wrap(bytes)
        while (bb.remaining() >= 2 && out.size < max) {
            val n = bb.short.toInt() and 0xFFFF
            if (n > TxWire.MAX_WIRE || n > bb.remaining()) break
            out.add(ByteArray(n).also { bb.get(it) })
        }
        return out
    }
}
