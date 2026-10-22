package com.thunder.agi

import com.thunder.agi.crypto.Aead
import com.thunder.agi.crypto.EcCodec
import com.thunder.agi.crypto.Hkdf
import com.thunder.agi.crypto.Identity
import com.thunder.agi.crypto.Kem
import com.thunder.agi.crypto.SecureChannel
import com.thunder.agi.util.hexToBytes
import com.thunder.agi.util.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class CryptoTest {
    private val rnd = SecureRandom()

    @Test fun compressedKeyRoundTrips() {
        repeat(60) {
            val id = TestIdentity.generate()
            val key = EcCodec.decompress(id.publicKey)
            assertNotNull(key)
            assertArrayEquals(id.publicKey, EcCodec.compress(key as ECPublicKey))
        }
    }

    @Test fun signaturesVerifyAndTamperingFails() {
        val id = TestIdentity.generate()
        repeat(40) {
            val msg = ByteArray(1 + rnd.nextInt(200)).also { rnd.nextBytes(it) }
            val sig = id.sign(msg)
            assertEquals(64, sig.size)
            assertTrue(EcCodec.verify(id.publicKey, msg, sig))
            val bad = msg.clone(); bad[0] = (bad[0].toInt() xor 1).toByte()
            assertFalse(EcCodec.verify(id.publicKey, bad, sig))
            val badSig = sig.clone(); badSig[10] = (badSig[10].toInt() xor 0x40).toByte()
            assertFalse(EcCodec.verify(id.publicKey, msg, badSig))
            assertFalse(EcCodec.verify(TestIdentity.generate().publicKey, msg, sig))
        }
    }

    @Test fun invalidPointsAreRejected() {
        assertNull(EcCodec.decompress(ByteArray(33)))                                  // wrong prefix
        assertNull(EcCodec.decompress(ByteArray(32)))                                  // wrong size
        var rejected = 0
        repeat(100) {
            val b = ByteArray(33).also { rnd.nextBytes(it) }; b[0] = 2
            if (EcCodec.decompress(b) == null) rejected++
        }
        assertTrue("about half of random x values are not on the curve", rejected in 20..80)
    }

    @Test fun kyberRoundTripAndSizes() {
        val kp = Kem.generate()
        assertEquals(Kem.PUBLIC_KEY_BYTES, kp.publicKey.size)
        val enc = Kem.encapsulate(kp.publicKey)
        assertEquals(Kem.CIPHERTEXT_BYTES, enc.cipherText.size)
        assertEquals(32, enc.sharedSecret.size)
        assertArrayEquals(enc.sharedSecret, Kem.decapsulate(enc.cipherText, kp.privateKey))
        val other = Kem.generate()
        assertFalse(enc.sharedSecret.contentEquals(Kem.decapsulate(enc.cipherText, other.privateKey)))
        try { Kem.encapsulate(ByteArray(10)); fail() } catch (e: IllegalArgumentException) {}
    }

    @Test fun hkdfMatchesRfc5869Vector() {
        val okm = Hkdf.derive(
            ByteArray(22) { 0x0b }, "000102030405060708090a0b0c".hexToBytes(), "f0f1f2f3f4f5f6f7f8f9".hexToBytes(), 42
        )
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", okm.toHex())
    }

    @Test fun aeadDetectsTampering() {
        val key = ByteArray(32).also { rnd.nextBytes(it) }
        val blob = Aead.seal(key, "hello".toByteArray(), "aad".toByteArray())
        assertEquals("hello", String(Aead.open(key, blob, "aad".toByteArray())))
        val bad = blob.clone(); bad[20] = (bad[20].toInt() xor 1).toByte()
        try { Aead.open(key, bad, "aad".toByteArray()); fail() } catch (e: Exception) {}
        try { Aead.open(key, blob, "other".toByteArray()); fail() } catch (e: Exception) {}
    }

    // ── SecureChannel ────────────────────────────────────────────────────────

    private class Pair2(val client: SecureChannel, val server: SecureChannel)

    private fun connect(clientId: Identity, serverId: Identity): Pair2 {
        val ss = ServerSocket(0)
        val serverCh = AtomicReference<SecureChannel>()
        val err = AtomicReference<Throwable>()
        val t = thread { try {
            val s = ss.accept()
            serverCh.set(SecureChannel.server(DataInputStream(s.getInputStream()), DataOutputStream(s.getOutputStream()), serverId))
        } catch (e: Throwable) { err.set(e) } }
        val c = Socket("127.0.0.1", ss.localPort)
        val clientCh = SecureChannel.client(DataInputStream(c.getInputStream()), DataOutputStream(c.getOutputStream()), clientId)
        t.join(10_000)
        err.get()?.let { throw it }
        return Pair2(clientCh, serverCh.get())
    }

    @Test fun channelAuthenticatesBothSidesAndCarriesData() {
        val a = TestIdentity.generate(); val b = TestIdentity.generate()
        val p = connect(a, b)
        assertArrayEquals(b.publicKey, p.client.peerPublicKey)
        assertArrayEquals(a.publicKey, p.server.peerPublicKey)
        p.client.send("ping".toByteArray())
        assertEquals("ping", String(p.server.receive()))
        p.server.send(ByteArray(100_000) { it.toByte() })
        assertEquals(100_000, p.client.receive().size)
    }

    @Test fun channelRejectsServerThatSignsWithAnotherKey() {
        val victimServer = TestIdentity.generate()
        val attacker = TestIdentity.generate()
        val impostor = object : Identity {              // claims the victim's public key, signs with its own
            override val publicKey = victimServer.publicKey
            override fun sign(message: ByteArray) = attacker.sign(message)
        }
        try { connect(TestIdentity.generate(), impostor); fail("client must reject an impostor server") }
        catch (e: SecurityException) { /* expected */ }
        catch (e: Throwable) { if (e.cause !is SecurityException && e !is java.io.IOException) throw e }
    }

    /** Copies bytes both ways; mutate() may corrupt the client->server stream after the handshake. */
    private fun proxy(serverPort: Int, mutate: (ByteArray, Int) -> List<ByteArray>): Int {
        val ps = ServerSocket(0)
        thread(isDaemon = true) {
            val c = ps.accept(); val s = Socket("127.0.0.1", serverPort)
            thread(isDaemon = true) { try { s.getInputStream().copyTo(c.getOutputStream()) } catch (_: Exception) {} }
            var passed = 0
            val buf = ByteArray(4096)
            try { while (true) {
                val n = c.getInputStream().read(buf); if (n < 0) break
                for (piece in mutate(buf.copyOf(n), passed)) s.getOutputStream().write(piece)
                s.getOutputStream().flush(); passed += n
            } } catch (_: Exception) {}
        }
        return ps.localPort
    }

    private fun connectViaProxy(mutate: (ByteArray, Int) -> List<ByteArray>): Pair2 {
        val a = TestIdentity.generate(); val b = TestIdentity.generate()
        val ss = ServerSocket(0)
        val serverCh = AtomicReference<SecureChannel>()
        val t = thread { try {
            val s = ss.accept()
            serverCh.set(SecureChannel.server(DataInputStream(s.getInputStream()), DataOutputStream(s.getOutputStream()), b))
        } catch (_: Throwable) {} }
        val c = Socket("127.0.0.1", proxy(ss.localPort, mutate))
        val clientCh = SecureChannel.client(DataInputStream(c.getInputStream()), DataOutputStream(c.getOutputStream()), a)
        t.join(10_000)
        return Pair2(clientCh, serverCh.get())
    }

    private val handshakeC2S = (4 + 73) + (4 + Kem.CIPHERTEXT_BYTES + 64)

    @Test fun channelDetectsTamperedFrame() {
        val p = connectViaProxy { data, passed ->
            if (passed >= handshakeC2S) { val d = data.clone(); d[d.size - 1] = (d[d.size - 1].toInt() xor 1).toByte(); listOf(d) } else listOf(data)
        }
        p.client.send("pay 100".toByteArray())
        try { p.server.receive(); fail("tampered frame must not decrypt") } catch (e: Exception) { /* expected */ }
    }

    @Test fun channelDetectsReplayedFrame() {
        var frame: ByteArray? = null
        val p = connectViaProxy { data, passed ->
            if (passed >= handshakeC2S && frame == null) { frame = data.clone(); listOf(data, data) } else listOf(data) // duplicate frame 1
        }
        p.client.send("pay 100".toByteArray())
        assertEquals("pay 100", String(p.server.receive()))
        try { p.server.receive(); fail("a replayed frame must be rejected") } catch (e: Exception) { /* expected */ }
    }
}
