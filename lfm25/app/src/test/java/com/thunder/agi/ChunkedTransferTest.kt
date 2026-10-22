package com.thunder.agi

import com.thunder.agi.transfer.ChunkedTransferProtocol
import com.thunder.agi.transfer.SessionStore
import com.thunder.agi.transfer.TransferSession
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.random.Random

class ChunkedTransferTest {
    private val P = ChunkedTransferProtocol

    private fun sample(n: Int, seed: Int = 7): ByteArray {
        val r = Random(seed); val b = ByteArray(n)
        for (i in b.indices) b[i] = if (i % 5 == 0) r.nextInt(256).toByte() else (i % 17).toByte()   // partly compressible
        return b
    }

    @Test fun anyOrderWithDuplicatesReassemblesExactly() {
        val data = sample(200_000)
        val chunks = P.split(data, 2700)
        assertTrue(chunks.size > 5)
        val msgs = chunks.map { P.encodeText(it) }
        for (m in msgs) assertTrue("each message must fit WhatsApp's 4096 chars", m.length < 4096)
        val session = TransferSession(chunks[0].sessionId, chunks[0].total)
        for (m in (msgs + msgs.take(3)).shuffled(java.util.Random(3))) assertTrue(session.add(P.decodeText("hi there $m")!!))
        assertTrue(session.isComplete()); assertEquals(100, session.progressPercent())
        assertArrayEquals(data, P.reassemble(session))
    }

    @Test fun corruptionIsDetectedAndOnlyMissingChunksAreRetransmitted() {
        val data = sample(60_000)
        val outgoing = TransferSession("00000000", 1, outgoing = true)
        val chunks = P.split(data, 2700)
        val sent = TransferSession(chunks[0].sessionId, chunks[0].total, outgoing = true).also { s -> chunks.forEach { s.add(it) } }
        val rx = TransferSession(chunks[0].sessionId, chunks[0].total)
        val texts = chunks.map { P.encodeText(it) }.toMutableList()
        texts[3] = texts[3].replaceRange(texts[3].length - 12, texts[3].length - 11, "#")        // flip a character
        texts[7] = texts[7].substring(0, texts[7].length / 2)                                    // truncated by WhatsApp
        var rejected = 0
        for (t in texts) { val c = P.decodeText(t); if (c == null) rejected++ else rx.add(c) }
        assertEquals(2, rejected)
        assertEquals(listOf(3, 7), rx.missing())
        assertNull(P.reassemble(rx))
        val ack = P.encodeAck(rx)
        val (sid, missing) = P.decodeAck("someone: $ack")!!
        assertEquals(chunks[0].sessionId, sid); assertEquals(listOf(3, 7), missing)
        for (i in missing) rx.add(P.decodeText(P.encodeText(sent.chunk(i)!!))!!)                 // sender resends ONLY those
        assertArrayEquals(data, P.reassemble(rx))
        outgoing.chunks.size
    }

    @Test fun decompressionBombIsRefused() {
        val bomb = P.deflate(ByteArray(12 * 1024 * 1024))
        assertTrue("12 MB of zeros compresses to a tiny payload", bomb.size < 40_000)
        try { P.inflate(bomb); fail("must refuse to inflate beyond the limit") } catch (e: SecurityException) {}
        val s = TransferSession("aaaaaaaa", 1); s.add(ChunkedTransferProtocol.Chunk("aaaaaaaa", 0, 1, bomb))
        assertNull(P.reassemble(s))
        try { P.inflate(byteArrayOf(1, 2, 3, 4, 5)); fail() } catch (e: Exception) {}
    }

    @Test fun malformedFramesAreRejected() {
        val good = P.encodeText(P.split(ByteArray(100) { it.toByte() })[0])
        assertNotNull(P.decodeText(good))
        for (bad in listOf("", "⚡T:", "⚡T:1:zzzz:0/1:AAAA:0000", "⚡T:1:abcdef12:5/3:AAAA:0000", "⚡T:1:abcdef12:0/0:AAAA:0000",
            "⚡T:1:abcdef12:0/99999:AAAA:0000", "⚡T:1:abcdef12:0/1:!!!!:0000", "⚡T:1:abcdef12:0/1:AAAA:ffff", "⚡T:1:abcdef12:0/1")) assertNull(bad, P.decodeText(bad))
        assertNull(P.decodeAck("⚡ACK:xyz:1,2")); assertEquals(emptyList<Int>(), P.decodeAck("⚡ACK:abcdef12:")!!.second)
    }

    @Test fun oneTransactionNeedsOneMessage() {
        assertEquals(1, P.split(ByteArray(226) { it.toByte() }).size)
    }

    @Test fun binaryFramesRoundTrip() {
        val c = P.split(sample(5000), 200)[4]
        val d = P.decodeBinary(P.encodeBinary(c))!!
        assertEquals(c.sessionId, d.sessionId); assertEquals(c.index, d.index); assertEquals(c.total, d.total); assertArrayEquals(c.data, d.data)
        val bad = P.encodeBinary(c).also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertNull(P.decodeBinary(bad)); assertNull(P.decodeBinary(ByteArray(3)))
    }

    @Test fun interruptedTransfersResumeAfterARestart() {
        val dir = File.createTempFile("tsess", "").also { it.delete(); it.mkdirs() }
        try {
            val data = sample(80_000)
            val chunks = P.split(data, 2700)
            val s1 = TransferSession(chunks[0].sessionId, chunks[0].total)
            chunks.take(chunks.size / 2).forEach { s1.add(it) }
            SessionStore(dir).save(s1)                                          // "app killed here"
            val resumed = SessionStore(dir).loadAllIncomplete().single()        // new process
            assertEquals(s1.missing(), resumed.missing())
            chunks.drop(chunks.size / 2).forEach { resumed.add(it) }
            assertArrayEquals(data, P.reassemble(resumed))
            SessionStore(dir).delete(resumed.sessionId)
            assertTrue(SessionStore(dir).loadAllIncomplete().isEmpty())
            try { SessionStore(dir).load("../../etc/passwd"); fail("path traversal must be impossible") } catch (e: IllegalArgumentException) {}
        } finally { dir.deleteRecursively() }
    }
}
