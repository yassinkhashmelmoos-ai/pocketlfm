package com.thunder.agi.p2p

import com.thunder.agi.ledger.Transaction
import com.thunder.agi.ledger.TxWire
import com.thunder.agi.ledger.WalletAddress
import com.thunder.agi.util.concat
import java.nio.ByteBuffer

/**
 * Framing for connectionless transports.
 *
 * WHY THE FULL TRANSACTION, NOT A "NOTIFICATION": a signature alone is 64 bytes, so a signed payment can never
 * fit in a 31-byte legacy BLE advertisement. The compact transaction (<= 226 bytes) DOES fit in
 *   - one Bluetooth-5 extended advertisement (up to ~250 bytes), and
 *   - one WiFi-Aware service message (255 bytes).
 * The receiver verifies the signature itself, so these broadcasts need no connection, no pairing, no password and
 * no trust in the sender. On old phones without extended advertising only a small 17-byte NOTICE can be sent
 * ("a payment for you exists, sync to receive it").
 *
 *   TX frame    : 'T' 'X' hops(1)  rawTransaction(178..226)         (<= 229 bytes)
 *   NOTICE frame: 'T' 'N' toPrefix(6) txidPrefix(5) amountMilli(u32) (= 17 bytes)
 */
object BeaconCodec {
    const val MAX_HOPS = 3
    private const val T = 'T'.code.toByte()
    private const val X = 'X'.code.toByte()
    private const val N = 'N'.code.toByte()
    const val NOTICE_LEN = 17

    sealed class Frame {
        class Tx(val raw: ByteArray, val hops: Int) : Frame()
        class Notice(val toPrefix: ByteArray, val txidPrefix: ByteArray, val amountMilli: Long) : Frame()
    }

    fun encodeTx(raw: ByteArray, hops: Int): ByteArray {
        require(raw.size in TxWire.MIN_WIRE..TxWire.MAX_WIRE)
        return concat(byteArrayOf(T, X, hops.coerceIn(0, 255).toByte()), raw)
    }

    fun encodeNotice(tx: Transaction): ByteArray {
        val toPayload = WalletAddress.toPayload(tx.to) ?: ByteArray(20)
        val txid = ByteArray(5) { i -> tx.txid.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        return ByteBuffer.allocate(NOTICE_LEN).put(T).put(N).put(toPayload, 0, 6).put(txid)
            .putInt((tx.amountMilli and 0xFFFFFFFFL).toInt()).array()
    }

    /** Returns null for anything that is not a well-formed Thunder frame. */
    fun decode(data: ByteArray): Frame? {
        if (data.size < 2 || data[0] != T) return null
        return when (data[1]) {
            X -> {
                if (data.size < 3 + TxWire.MIN_WIRE || data.size > 3 + TxWire.MAX_WIRE) null
                else Frame.Tx(data.copyOfRange(3, data.size), data[2].toInt() and 0xFF)
            }
            N -> {
                if (data.size != NOTICE_LEN) null else {
                    val bb = ByteBuffer.wrap(data, 2, NOTICE_LEN - 2)
                    val to = ByteArray(6).also { bb.get(it) }
                    val id = ByteArray(5).also { bb.get(it) }
                    Frame.Notice(to, id, bb.int.toLong() and 0xFFFFFFFFL)
                }
            }
            else -> null
        }
    }

    fun noticeIsFor(n: Frame.Notice, myAddress: String): Boolean {
        val p = WalletAddress.toPayload(myAddress) ?: return false
        return p.copyOf(6).contentEquals(n.toPrefix)
    }
}
