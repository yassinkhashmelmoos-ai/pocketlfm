package com.thunder.agi.ledger

import com.thunder.agi.crypto.EcCodec
import com.thunder.agi.util.concat
import com.thunder.agi.util.toHex
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * ThunderABI: the binary wire protocol between Thunder nodes (our equivalent of Ethereum's ABI).
 * Frame = [u32 length][u8 opcode][payload]. Every list has a hard cap, every length is validated
 * before allocation, so a hostile peer cannot make us allocate or loop unboundedly.
 */
object ThunderABI {
    const val OP_HELLO = 0x01
    const val OP_IDENT = 0x02
    const val OP_FRONTIER = 0x10
    const val OP_REQUEST = 0x11
    const val OP_TXS = 0x12
    const val OP_AUX = 0x13

    const val VERSION = 1
    const val MAX_FRAME = 512 * 1024
    const val MAX_FRONTIERS = 2000
    const val MAX_REQUESTS = 300
    const val MAX_TXS = 400
    const val MAX_FRAUDS = 50
    const val MAX_NAME_BYTES = 24
    private val ID_DOMAIN = "THUNDER-ID-V1".toByteArray()

    class ProtocolException(msg: String) : IOException(msg)

    fun write(out: DataOutputStream, op: Int, payload: ByteArray) {
        out.writeInt(payload.size + 1)
        out.writeByte(op)
        out.write(payload)
        out.flush()
    }

    fun read(inp: DataInputStream, expectOp: Int): ByteArray {
        val len = inp.readInt()
        if (len < 1 || len > MAX_FRAME) throw ProtocolException("bad frame length $len")
        val op = inp.readUnsignedByte()
        if (op != expectOp) throw ProtocolException("unexpected opcode $op, expected $expectOp")
        return ByteArray(len - 1).also { inp.readFully(it) }
    }

    // ── HELLO ────────────────────────────────────────────────────────────────
    class Hello(val version: Int, val nodeNonce: ByteArray, val listenPort: Int)

    fun encodeHello(h: Hello): ByteArray =
        ByteBuffer.allocate(11).put(h.version.toByte()).put(h.nodeNonce).putShort(h.listenPort.toShort()).array()

    fun decodeHello(b: ByteArray): Hello {
        if (b.size != 11) throw ProtocolException("bad hello")
        val bb = ByteBuffer.wrap(b)
        val v = bb.get().toInt() and 0xFF
        val nonce = ByteArray(8).also { bb.get(it) }
        return Hello(v, nonce, bb.short.toInt() and 0xFFFF)
    }

    // ── IDENT (signed identity card: address, name, Kyber key) ───────────────
    class Ident(val address: String, val name: String, val kyberPublicKey: ByteArray,
                val ecPublicKey: ByteArray, val signature: ByteArray)

    fun identMessage(address: String, name: String, kyberPublicKey: ByteArray): ByteArray =
        concat(ID_DOMAIN, WalletAddress.toPayload(address) ?: ByteArray(20), name.toByteArray(Charsets.UTF_8), kyberPublicKey)

    fun encodeIdent(i: Ident): ByteArray {
        val name = i.name.toByteArray(Charsets.UTF_8)
        require(name.size <= MAX_NAME_BYTES)
        return concat(
            WalletAddress.toPayload(i.address)!!, byteArrayOf(name.size.toByte()), name,
            i.kyberPublicKey, i.ecPublicKey, i.signature
        )
    }

    /** Decodes AND verifies (address binding + signature). Throws ProtocolException if anything is wrong. */
    fun decodeIdent(b: ByteArray): Ident {
        val min = 20 + 1 + 1184 + 33 + 64
        if (b.size < min) throw ProtocolException("short ident")
        val bb = ByteBuffer.wrap(b)
        val addrP = ByteArray(20).also { bb.get(it) }
        val nameLen = bb.get().toInt() and 0xFF
        if (nameLen > MAX_NAME_BYTES || b.size != min + nameLen) throw ProtocolException("bad ident length")
        val name = String(ByteArray(nameLen).also { bb.get(it) }, Charsets.UTF_8)
        val kyber = ByteArray(1184).also { bb.get(it) }
        val ec = ByteArray(33).also { bb.get(it) }
        val sig = ByteArray(64).also { bb.get(it) }
        val address = WalletAddress.fromPayload(addrP)
        if (!WalletAddress.matches(address, ec, WalletAddress.kyberHash16(kyber)))
            throw ProtocolException("ident key does not match address")
        if (!EcCodec.verify(ec, identMessage(address, name, kyber), sig))
            throw ProtocolException("ident signature invalid")
        return Ident(address, name, kyber, ec, sig)
    }

    // ── FRONTIER: [u16 n] n x (addr20, nonce u32, lastId8) ───────────────────
    fun encodeFrontiers(list: List<Frontier>): ByteArray {
        val l = list.take(MAX_FRONTIERS)
        val bb = ByteBuffer.allocate(2 + l.size * 32)
        bb.putShort(l.size.toShort())
        for (f in l) {
            bb.put(WalletAddress.toPayload(f.address) ?: ByteArray(20))
            bb.putInt(f.nonce.toInt())
            bb.put(f.lastTxId.take(16).padEnd(16, '0').let { hex -> ByteArray(8) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() } })
        }
        return bb.array()
    }

    fun decodeFrontiers(b: ByteArray): List<Frontier> {
        if (b.size < 2) throw ProtocolException("bad frontier")
        val bb = ByteBuffer.wrap(b)
        val n = bb.short.toInt() and 0xFFFF
        if (n > MAX_FRONTIERS || b.size != 2 + n * 32) throw ProtocolException("bad frontier size")
        return List(n) {
            val a = ByteArray(20).also { x -> bb.get(x) }
            val nonce = bb.int.toLong() and 0xFFFFFFFFL
            val id8 = ByteArray(8).also { x -> bb.get(x) }
            Frontier(WalletAddress.fromPayload(a), nonce, id8.toHex())
        }
    }

    // ── REQUEST: [u16 n] n x (addr20, fromNonce u32) ─────────────────────────
    fun encodeRequests(list: List<Pair<String, Long>>): ByteArray {
        val l = list.take(MAX_REQUESTS)
        val bb = ByteBuffer.allocate(2 + l.size * 24)
        bb.putShort(l.size.toShort())
        for ((a, n) in l) { bb.put(WalletAddress.toPayload(a) ?: ByteArray(20)); bb.putInt(n.toInt()) }
        return bb.array()
    }

    fun decodeRequests(b: ByteArray): List<Pair<String, Long>> {
        if (b.size < 2) throw ProtocolException("bad request")
        val bb = ByteBuffer.wrap(b)
        val n = bb.short.toInt() and 0xFFFF
        if (n > MAX_REQUESTS || b.size != 2 + n * 24) throw ProtocolException("bad request size")
        return List(n) {
            val a = ByteArray(20).also { x -> bb.get(x) }
            WalletAddress.fromPayload(a) to (bb.int.toLong() and 0xFFFFFFFFL)
        }
    }

    // ── TXS / AUX ────────────────────────────────────────────────────────────
    fun encodeTxs(raws: List<ByteArray>): ByteArray = TxBundle.encode(raws.take(MAX_TXS))
    fun decodeTxs(b: ByteArray): List<ByteArray> = TxBundle.decode(b, MAX_TXS)

    fun encodeAux(frauds: List<FraudProof>): ByteArray =
        TxBundle.encode(frauds.take(MAX_FRAUDS).flatMap { listOf(it.rawA, it.rawB) })

    fun decodeAux(b: ByteArray): List<Pair<ByteArray, ByteArray>> =
        TxBundle.decode(b, MAX_FRAUDS * 2).chunked(2).filter { it.size == 2 }.map { it[0] to it[1] }
}
