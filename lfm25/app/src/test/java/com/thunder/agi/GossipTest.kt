package com.thunder.agi

import com.thunder.agi.ledger.GossipProtocol
import com.thunder.agi.ledger.LedgerEngine
import com.thunder.agi.ledger.MintAuthority
import com.thunder.agi.ledger.ThunderABI
import com.thunder.agi.ledger.TxWire
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

class GossipTest {
    private val issuer = TestWallet()
    private val w = List(5) { TestWallet() }
    private val started = CopyOnWriteArrayList<GossipProtocol>()

    @Before fun setUp() { MintAuthority.clearForTest(); MintAuthority.addAuthorityForTest(issuer.identity.publicKey) }
    @After fun tearDown() { started.forEach { it.stop() }; MintAuthority.clearForTest() }

    private class Node(val engine: LedgerEngine, val gossip: GossipProtocol, val seen: MutableList<String>)

    private fun ident(t: TestWallet, name: String, breakSig: Boolean = false): ThunderABI.Ident {
        val sig = t.identity.sign(ThunderABI.identMessage(t.address, name, t.kyber.publicKey))
        if (breakSig) sig[5] = (sig[5].toInt() xor 1).toByte()
        return ThunderABI.Ident(t.address, name, t.kyber.publicKey, t.identity.publicKey, sig)
    }

    private fun node(breakIdent: Boolean = false): Node {
        val e = LedgerEngine(InMemoryLedgerStore())
        val me = TestWallet()
        val seen = CopyOnWriteArrayList<String>()
        val g = GossipProtocol(e, { ident(me, "node", breakIdent) }, { host, id -> seen.add(id.name) }, requestedPort = 0)
        g.start(); started.add(g)
        return Node(e, g, seen)
    }

    private suspend fun populate(e: LedgerEngine, transfers: Int, seed: Int = 1) {
        for (i in w.indices) e.createGenesis(issuer.identity, issuer.kyber.publicKey, w[i].address, 10_000_000, "R$i-$seed").getOrThrow()
        val rnd = Random(seed)
        repeat(transfers) {
            val a = rnd.nextInt(w.size); var b = rnd.nextInt(w.size); if (a == b) b = (b + 1) % w.size
            e.createTransfer(w[a].identity, w[a].kyber.publicKey, w[b].address, 1 + rnd.nextLong(100), "t$it").getOrThrow()
        }
    }

    private suspend fun assertSame(a: LedgerEngine, b: LedgerEngine) {
        assertEquals(a.count(), b.count())
        for (x in w) assertEquals(a.balance(x.address), b.balance(x.address))
    }

    @Test fun aNewPhoneDownloadsTheWholeHistory() = runBlocking {
        val a = node(); val b = node()
        populate(a.engine, 120)
        val got = b.gossip.syncWith("127.0.0.1", a.gossip.boundPort)
        assertEquals(a.engine.count(), got)
        assertSame(a.engine, b.engine)
        assertTrue(b.seen.isNotEmpty() && a.seen.isNotEmpty())     // both learned each other's verified identity
    }

    @Test fun longChainsNeedSeveralRoundsAndStillArriveInOrder() = runBlocking {
        val a = node(); val b = node()
        a.engine.createGenesis(issuer.identity, issuer.kyber.publicKey, w[0].address, 100_000_000, "BIG").getOrThrow()
        repeat(900) { a.engine.createTransfer(w[0].identity, w[0].kyber.publicKey, w[1].address, 10, "").getOrThrow() }
        b.gossip.syncWith("127.0.0.1", a.gossip.boundPort)
        assertEquals(901, b.engine.count())
        assertEquals(a.engine.balance(w[1].address), b.engine.balance(w[1].address))
        assertEquals(0, b.engine.pendingList().size)
    }

    @Test fun twoPhonesWithDifferentHistoriesMergeInOneSession() = runBlocking {
        val a = node(); val b = node()
        val funding = w.indices.map { i -> a.engine.createGenesis(issuer.identity, issuer.kyber.publicKey, w[i].address, 10_000_000, "R$i").getOrThrow() }
        for (f in funding) b.engine.receive(f.raw)
        a.engine.createTransfer(w[0].identity, w[0].kyber.publicKey, w[1].address, 5_000, "from A side").getOrThrow()
        b.engine.createTransfer(w[2].identity, w[2].kyber.publicKey, w[3].address, 7_000, "from B side").getOrThrow()
        a.gossip.syncWith("127.0.0.1", b.gossip.boundPort)
        assertSame(a.engine, b.engine)
        assertEquals(7, a.engine.count())
    }

    @Test fun doubleSpendProofsSpreadBetweenPhones() = runBlocking {
        val a = node(); val b = node()
        val g = a.engine.createGenesis(issuer.identity, issuer.kyber.publicKey, w[0].address, 1_000_000, "R").getOrThrow()
        b.engine.receive(g.raw)
        val t1 = w[0].craft(w[1].address, 400_000, 1, null)
        val t2 = w[0].craft(w[2].address, 400_000, 1, null)
        a.engine.receive(t1); a.engine.receive(t2)              // A sees both -> fork -> frozen
        b.engine.receive(t1)                                    // B only saw the first
        assertTrue(a.engine.isFrozen(w[0].address)); assertFalse(b.engine.isFrozen(w[0].address))
        b.gossip.syncWith("127.0.0.1", a.gossip.boundPort)
        assertTrue("proof must reach B", b.engine.isFrozen(w[0].address))
    }

    @Test fun peerWithForgedIdentityIsRefused() = runBlocking {
        val good = node(); val liar = node(breakIdent = true)
        populate(liar.engine, 10)
        val got = good.gossip.syncWith("127.0.0.1", liar.gossip.boundPort)
        assertEquals(0, got); assertEquals(0, good.engine.count())
    }

    @Test fun hostilePeersCannotCrashOrStarveTheServer() = runBlocking {
        val a = node(); val b = node()
        populate(a.engine, 20)
        val port = a.gossip.boundPort
        fun hit(block: (DataOutputStream) -> Unit) { try { Socket("127.0.0.1", port).use { s -> block(DataOutputStream(s.getOutputStream())); s.getOutputStream().flush(); Thread.sleep(50) } } catch (_: Exception) {} }
        hit { it.write(ByteArray(500) { i -> i.toByte() }) }                          // garbage
        hit { it.writeInt(Int.MAX_VALUE); it.writeByte(1) }                            // absurd frame length
        hit { it.writeInt(-5) }                                                         // negative length
        hit { it.writeInt(12); it.writeByte(ThunderABI.OP_FRONTIER); it.write(ByteArray(11)) }  // wrong opcode first
        hit { it.writeInt(12); it.writeByte(ThunderABI.OP_HELLO); it.write(ByteArray(11)) }     // hello then silence
        hit { }                                                                         // connect and close
        val got = b.gossip.syncWith("127.0.0.1", port)                                  // honest peer still served
        assertEquals(a.engine.count(), got)
    }

    @Test fun oversizedAndMalformedListsAreRejectedByTheCodec() {
        try { ThunderABI.decodeFrontiers(byteArrayOf(0x7F, 0xFF.toByte())); throw AssertionError("must fail") } catch (e: ThunderABI.ProtocolException) {}
        try { ThunderABI.decodeRequests(ByteArray(5)); throw AssertionError("must fail") } catch (e: ThunderABI.ProtocolException) {}
        try { ThunderABI.decodeHello(ByteArray(3)); throw AssertionError("must fail") } catch (e: ThunderABI.ProtocolException) {}
        try { ThunderABI.decodeIdent(ByteArray(2000)); throw AssertionError("must fail") } catch (e: ThunderABI.ProtocolException) {}
        assertEquals(0, ThunderABI.decodeTxs(ByteArray(7) { 0xFF.toByte() }).size)
        assertTrue(TxWire.MAX_WIRE < ThunderABI.MAX_FRAME)
    }
}
