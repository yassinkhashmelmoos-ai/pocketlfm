package com.thunder.agi.transfer

import com.thunder.agi.util.sha256
import com.thunder.agi.util.toHex
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Base64
import java.util.TreeMap
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Resumable chunked transfer over any text channel (WhatsApp, SMS) or binary link.
 *
 * IDM technique : every chunk carries (session, index, total) and the receiver persists its state after
 *                 each chunk, so an interrupted transfer continues instead of restarting.
 * Feem technique: chunks may arrive in ANY order; the receiver answers with an ACK naming only the MISSING
 *                 indices and the sender retransmits just those.
 *
 * Text frame : "⚡T:1:<session8hex>:<index>/<total>:<base64url>:<crc16hex4>"
 * ACK frame  : "⚡ACK:<session8hex>:<missing,indices,csv>"
 *
 * With the compact transaction format a single payment fits in ONE message and needs no chunking at all;
 * this protocol is for bulk ledger bundles (new device catching up) and any large payload.
 */
object ChunkedTransferProtocol {
    const val PREFIX = "⚡T:"
    const val ACK_PREFIX = "⚡ACK:"
    const val CHUNK_TEXT_BYTES = 2700            // raw bytes -> ~3600 base64 chars: safely inside WhatsApp's 4096
    const val MAX_CHUNKS = 4000
    const val MAX_INFLATED_BYTES = 10 * 1024 * 1024
    private val ID_RE = Regex("[0-9a-f]{8}")
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()

    class Chunk(val sessionId: String, val index: Int, val total: Int, val data: ByteArray)

    /** Compress, then split into numbered chunks. */
    fun split(data: ByteArray, chunkSize: Int = CHUNK_TEXT_BYTES): List<Chunk> {
        require(chunkSize in 16..65_000)
        val compressed = deflate(data)
        val session = sha256(data, System.nanoTime().toString().toByteArray()).copyOf(4).toHex()
        val total = maxOf(1, (compressed.size + chunkSize - 1) / chunkSize)
        require(total <= MAX_CHUNKS) { "payload too large" }
        return List(total) { i ->
            Chunk(session, i, total, compressed.copyOfRange(i * chunkSize, minOf(compressed.size, (i + 1) * chunkSize)))
        }
    }

    fun encodeText(c: Chunk): String =
        "$PREFIX1${c.sessionId}:${c.index}/${c.total}:${enc.encodeToString(c.data)}:${crc16(c.data).toString(16).padStart(4, '0')}"

    private const val PREFIX1 = "$PREFIX" + "1:"

    /** Null for anything malformed, truncated or corrupted (CRC mismatch) -> the chunk will be re-requested. */
    fun decodeText(msg: String): Chunk? {
        val start = msg.indexOf(PREFIX1)
        if (start < 0) return null
        val parts = msg.substring(start + PREFIX1.length).trim().split(":")
        if (parts.size != 4) return null
        val session = parts[0]
        if (!ID_RE.matches(session)) return null
        val ij = parts[1].split("/")
        if (ij.size != 2) return null
        val idx = ij[0].toIntOrNull() ?: return null
        val total = ij[1].toIntOrNull() ?: return null
        if (total !in 1..MAX_CHUNKS || idx !in 0 until total) return null
        val data = try { dec.decode(parts[2]) } catch (e: IllegalArgumentException) { return null }
        val crc = parts[3].toIntOrNull(16) ?: return null
        if (crc16(data) != crc) return null
        return Chunk(session, idx, total, data)
    }

    fun encodeBinary(c: Chunk): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).apply {
            writeByte(0xAE)
            write(c.sessionId.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            writeShort(c.index); writeShort(c.total); writeShort(c.data.size); writeShort(crc16(c.data)); write(c.data)
        }
        return bos.toByteArray()
    }

    fun decodeBinary(bytes: ByteArray): Chunk? = try {
        val inp = DataInputStream(bytes.inputStream())
        if (inp.readUnsignedByte() != 0xAE) null else {
            val sid = ByteArray(4).also { inp.readFully(it) }.toHex()
            val idx = inp.readUnsignedShort(); val total = inp.readUnsignedShort()
            val len = inp.readUnsignedShort(); val crc = inp.readUnsignedShort()
            if (total !in 1..MAX_CHUNKS || idx >= total || len > inp.available()) null else {
                val data = ByteArray(len).also { inp.readFully(it) }
                if (crc16(data) != crc) null else Chunk(sid, idx, total, data)
            }
        }
    } catch (e: Exception) { null }

    fun reassemble(session: TransferSession): ByteArray? {
        if (!session.isComplete()) return null
        val out = ByteArrayOutputStream()
        for (i in 0 until session.total) out.write(session.chunks[i] ?: return null)
        return try { inflate(out.toByteArray()) } catch (e: Exception) { null }
    }

    // ── ACK (Feem) ───────────────────────────────────────────────────────────

    fun encodeAck(session: TransferSession): String =
        "$ACK_PREFIX${session.sessionId}:${session.missing().take(200).joinToString(",")}"

    fun decodeAck(text: String): Pair<String, List<Int>>? {
        val start = text.indexOf(ACK_PREFIX)
        if (start < 0) return null
        val parts = text.substring(start + ACK_PREFIX.length).trim().split(":")
        if (parts.size != 2 || !ID_RE.matches(parts[0])) return null
        val missing = if (parts[1].isBlank()) emptyList() else parts[1].split(",").mapNotNull { it.trim().toIntOrNull() }
        return parts[0] to missing.filter { it in 0 until MAX_CHUNKS }
    }

    // ── compression & integrity ──────────────────────────────────────────────

    fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION)
        d.setInput(data); d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    /** Refuses to expand beyond [MAX_INFLATED_BYTES] (a tiny malicious message could otherwise inflate to gigabytes). */
    fun inflate(compressed: ByteArray): ByteArray {
        val inf = Inflater()
        inf.setInput(compressed)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        try {
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) throw IllegalArgumentException("truncated stream")
                if (out.size() + n > MAX_INFLATED_BYTES) throw SecurityException("inflated size exceeds limit")
                out.write(buf, 0, n)
            }
        } finally { inf.end() }
        return out.toByteArray()
    }

    /** CRC-16/MODBUS, returned as an unsigned 0..65535 Int. */
    fun crc16(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc and 0xFFFF
    }
}

/** State of one multi-chunk transfer (incoming, or outgoing so missing chunks can be retransmitted). */
class TransferSession(val sessionId: String, val total: Int, val outgoing: Boolean = false, val createdMs: Long = System.currentTimeMillis()) {
    val chunks = TreeMap<Int, ByteArray>()

    fun add(c: ChunkedTransferProtocol.Chunk): Boolean {
        if (c.sessionId != sessionId || c.total != total || c.index !in 0 until total) return false
        chunks[c.index] = c.data
        return true
    }

    fun isComplete() = chunks.size == total
    fun missing(): List<Int> = (0 until total).filter { it !in chunks }
    fun progressPercent() = chunks.size * 100 / maxOf(1, total)
    fun chunk(i: Int): ChunkedTransferProtocol.Chunk? = chunks[i]?.let { ChunkedTransferProtocol.Chunk(sessionId, i, total, it) }
}

/** Persists sessions so a killed app / dropped connection resumes where it stopped. Pure java.io: testable. */
class SessionStore(private val dir: File) {
    init { dir.mkdirs() }

    private fun file(id: String): File {
        require(Regex("[0-9a-f]{8}").matches(id)) { "bad session id" }   // blocks path traversal
        return File(dir, "tsession_$id.bin")
    }

    @Synchronized fun save(s: TransferSession) {
        val tmp = File(dir, "tsession_${s.sessionId}.tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { o ->
            o.writeUTF(s.sessionId); o.writeInt(s.total); o.writeBoolean(s.outgoing); o.writeLong(s.createdMs)
            o.writeInt(s.chunks.size)
            for ((i, d) in s.chunks) { o.writeInt(i); o.writeInt(d.size); o.write(d) }
        }
        if (!tmp.renameTo(file(s.sessionId))) { file(s.sessionId).delete(); tmp.renameTo(file(s.sessionId)) }
    }

    @Synchronized fun load(id: String): TransferSession? {
        val f = file(id)
        if (!f.exists()) return null
        return try {
            DataInputStream(f.inputStream().buffered()).use { i ->
                val sid = i.readUTF(); val total = i.readInt(); val out = i.readBoolean(); val created = i.readLong()
                if (total !in 1..ChunkedTransferProtocol.MAX_CHUNKS) return null
                val s = TransferSession(sid, total, out, created)
                repeat(i.readInt().coerceIn(0, total)) {
                    val idx = i.readInt(); val len = i.readInt()
                    if (idx in 0 until total && len in 0..70_000) s.chunks[idx] = ByteArray(len).also { b -> i.readFully(b) }
                }
                s
            }
        } catch (e: Exception) { null }
    }

    @Synchronized fun delete(id: String) { file(id).delete() }

    @Synchronized fun loadAllIncomplete(): List<TransferSession> =
        dir.listFiles { f -> f.name.startsWith("tsession_") && f.name.endsWith(".bin") }
            ?.mapNotNull { load(it.name.removePrefix("tsession_").removeSuffix(".bin")) }
            ?.filter { !it.isComplete() || it.outgoing } ?: emptyList()

    @Synchronized fun prune(maxAgeMs: Long = 24 * 3600 * 1000L) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        dir.listFiles()?.filter { it.name.startsWith("tsession_") && it.lastModified() < cutoff }?.forEach { it.delete() }
    }
}
