package com.thunder.agi

import com.thunder.agi.ledger.LedgerEngine
import com.thunder.agi.ledger.LedgerEngine.Outcome
import com.thunder.agi.ledger.MintAuthority
import com.thunder.agi.ledger.Transaction
import com.thunder.agi.ledger.TxWire
import com.thunder.agi.ledger.toMilliSDG
import com.thunder.agi.util.toHex
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LedgerEngineTest {
    private val issuer = TestWallet()
    private val alice = TestWallet()
    private val bob = TestWallet()
    private val carol = TestWallet()

    @Before fun setUp() { MintAuthority.clearForTest(); MintAuthority.addAuthorityForTest(issuer.identity.publicKey) }
    @After fun tearDown() { MintAuthority.clearForTest() }

    private fun newEngine(clock: () -> Long = { System.currentTimeMillis() }) = LedgerEngine(InMemoryLedgerStore(), clock)
    private suspend fun LedgerEngine.fund(w: TestWallet, milli: Long, ref: String) =
        createGenesis(issuer.identity, issuer.kyber.publicKey, w.address, milli, ref).getOrThrow()
    private suspend fun LedgerEngine.send(from: TestWallet, to: TestWallet, milli: Long, memo: String = "") =
        createTransfer(from.identity, from.kyber.publicKey, to.address, milli, memo).getOrThrow()
    private fun rejected(o: Outcome, part: String) { assertTrue("expected Rejected($part) but was $o", o is Outcome.Rejected && o.reason.contains(part)) }

    // ── the two holes that made the old ledger worthless ─────────────────────

    @Test fun nobodyExceptTheIssuerCanCreateMoney() = runBlocking {
        val e = newEngine(); val rogue = TestWallet()
        assertTrue(e.createGenesis(rogue.identity, rogue.kyber.publicKey, alice.address, 5_000_000, "X").isFailure)
        // a hand-made GENESIS arriving from the network
        rejected(e.receive(rogue.craft(alice.address, 9_000_000_000, 1, null, type = Transaction.Type.GENESIS)), "authorized issuer")
        assertEquals(0L, e.balance(alice.address))
        MintAuthority.devAllowSelfMint = true     // debug-only switch
        assertTrue(e.createGenesis(rogue.identity, rogue.kyber.publicKey, alice.address, 5_000_000, "X").isSuccess)
    }

    @Test fun cannotSpendFromSomeoneElsesAddress() = runBlocking {
        val e = newEngine(); e.fund(alice, 1_000_000, "R1")
        val attacker = TestWallet()
        // 1) claims alice's address but is signed by the attacker's own key (old code ACCEPTED this)
        rejected(e.receive(attacker.craft(attacker.address, 900_000, 1, null, claimFrom = alice.address)), "does not belong")
        // 2) puts alice's real public key in the trailer but cannot produce her signature
        val body = TxWire.buildBody(Transaction.Type.TRANSFER, com.thunder.agi.ledger.WalletAddress.toPayload(alice.address)!!,
            com.thunder.agi.ledger.WalletAddress.toPayload(attacker.address)!!, 900_000, 1, System.currentTimeMillis() / 1000, ByteArray(8), ByteArray(0))
        val forged = TxWire.assemble(body, attacker.identity.sign(TxWire.signingMessage(body)), alice.identity.publicKey, alice.kyberHash)
        rejected(e.receive(forged), "invalid signature")
        assertEquals(1_000_000L, e.balance(alice.address)); assertEquals(0L, e.balance(attacker.address))
    }

    @Test fun tamperedTransactionIsRejected() = runBlocking {
        val e = newEngine(); e.fund(alice, 1_000_000, "R1")
        val raw = alice.craft(bob.address, 1_000, 1, null)
        raw[47] = (raw[47].toInt() xor 0x01).toByte()            // change the amount
        rejected(e.receive(raw), "invalid signature")
    }

    // ── normal operation ─────────────────────────────────────────────────────

    @Test fun spendingMovesMoneyAndKeepsBalancesExact() = runBlocking {
        val e = newEngine()
        e.fund(alice, 1_000.0.toMilliSDG(), "R1")
        e.send(alice, bob, 250.5.toMilliSDG(), "rent")
        e.send(bob, carol, 100.0.toMilliSDG())
        assertEquals(749_500L, e.balance(alice.address))
        assertEquals(150_500L, e.balance(bob.address))
        assertEquals(100_000L, e.balance(carol.address))
        assertEquals(2, e.history(bob.address).size)
        val r = e.createTransfer(bob.identity, bob.kyber.publicKey, carol.address, 999_000, "")
        assertTrue(r.isFailure && r.exceptionOrNull()!!.message!!.contains("Insufficient"))
    }

    @Test fun badInputsAreRefused() = runBlocking {
        val e = newEngine(); e.fund(alice, 5_000_000, "R1")
        fun fail(to: String, amt: Long, memo: String = "") = runBlocking { e.createTransfer(alice.identity, alice.kyber.publicKey, to, amt, memo).isFailure }
        assertTrue(fail(alice.address, 1000))
        assertTrue(fail(bob.address, 0)); assertTrue(fail(bob.address, -5))
        assertTrue(fail(bob.address, TxWire.MAX_AMOUNT_MILLI + 1))
        assertTrue(fail(bob.address, 1000, "x".repeat(49))); assertFalse(fail(bob.address, 1000, "x".repeat(48)))
        assertTrue(fail("notAnAddress", 1000)); assertTrue(fail(bob.address.dropLast(1) + "1", 1000))
    }

    @Test fun duplicatesAreHarmless() = runBlocking {
        val e = newEngine(); e.fund(alice, 1_000_000, "R1")
        val raw = alice.craft(bob.address, 1_000, 1, null)
        assertTrue(e.receive(raw) is Outcome.Accepted)
        assertTrue(e.receive(raw) is Outcome.Duplicate)
        assertEquals(1_000L, e.balance(bob.address))
    }

    @Test fun sameBankReceiptCannotBeDepositedTwice() = runBlocking {
        val e = newEngine(); e.fund(alice, 1_000_000, "BOK-123")
        assertTrue(e.createGenesis(issuer.identity, issuer.kyber.publicKey, bob.address, 1_000_000, "BOK-123").isFailure)
        assertTrue(e.createGenesis(issuer.identity, issuer.kyber.publicKey, bob.address, 1_000_000, "BOK-124").isSuccess)
    }

    @Test fun limitsAreEnforcedOnIncomingTransactions() = runBlocking {
        val e = newEngine(); e.fund(alice, 1_000_000, "R1")
        val future = System.currentTimeMillis() / 1000 + 3 * 24 * 3600
        rejected(e.receive(alice.craft(bob.address, 1000, 1, null, tsSec = future)), "future")
        rejected(e.receive(alice.craft(alice.address, 10, 1, null)), "self")
    }

    // ── ordering, pending, and convergence ───────────────────────────────────

    private suspend fun allRaws(e: LedgerEngine, vararg ws: TestWallet): List<ByteArray> =
        ws.flatMap { e.history(it.address) }.map { it.tx }.distinctBy { it.txid }.map { it.raw }

    @Test fun anyDeliveryOrderConvergesToTheSameLedger() = runBlocking {
        val src = newEngine()
        src.fund(alice, 1_000_000, "R1"); src.fund(bob, 500_000, "R2")
        repeat(3) { src.send(alice, bob, 10_000) }
        repeat(2) { src.send(bob, carol, 25_000) }
        src.send(carol, alice, 5_000)
        val raws = allRaws(src, alice, bob, carol, issuer)
        assertEquals(src.count(), raws.size)
        for (order in listOf(raws.reversed(), raws.shuffled(), raws.sortedBy { TxWire.decode(it)!!.nonce }.reversed())) {
            val e = newEngine()
            for (r in order) e.receive(r)
            assertEquals(src.count(), e.count())
            for (w in listOf(alice, bob, carol)) assertEquals(src.balance(w.address), e.balance(w.address))
            assertEquals(0, e.pendingList().size)
        }
        val batch = newEngine(); val res = batch.receiveMany(raws.shuffled())
        assertEquals(raws.size, res.accepted); assertEquals(src.balance(carol.address), batch.balance(carol.address))
    }

    @Test fun paymentWaitsPendingUntilTheSenderIsProvenFunded() = runBlocking {
        val src = newEngine(); val g = src.fund(alice, 100_000, "R1"); val t = src.send(alice, bob, 40_000)
        val e = newEngine()
        assertTrue(e.receive(t.raw) is Outcome.Pending)             // sender's funds not known here yet
        assertEquals(0L, e.balance(bob.address))                    // never counted as money
        assertEquals(40_000L, e.pendingIncoming(bob.address))       // but shown as "incoming, unverified"
        assertTrue(e.receive(g.raw) is Outcome.Accepted)            // the funding arrives -> payment completes
        assertEquals(40_000L, e.balance(bob.address)); assertEquals(0L, e.pendingIncoming(bob.address))
    }

    @Test fun pendingPoolIsBoundedAndExpires() = runBlocking {
        var now = System.currentTimeMillis()
        val e = newEngine { now }
        val spammer = TestWallet()
        val results = (2..31).map { n -> e.receive(spammer.craft(bob.address, 1000, n.toLong(), null, tsSec = now / 1000)) }
        assertEquals(25, results.count { it is Outcome.Pending }); assertEquals(5, results.count { it is Outcome.Rejected })
        now += 4 * 24 * 3600 * 1000L                                 // 4 days later
        e.fund(carol, 1000, "R9")                                    // any accepted tx triggers housekeeping
        assertEquals(0, e.pendingList().size)
    }

    // ── double spending ──────────────────────────────────────────────────────

    @Test fun doubleSpendIsDetectedProvenAndFreezesTheSender() = runBlocking {
        val e = newEngine(); e.fund(alice, 1_000_000, "R1")
        val toBob = alice.craft(bob.address, 600_000, 1, null)
        val toCarol = alice.craft(carol.address, 600_000, 1, null)        // same nonce, different recipient
        assertTrue(e.receive(toBob) is Outcome.Accepted)
        assertTrue(e.receive(toCarol) is Outcome.Fork)
        assertTrue(e.isFrozen(alice.address)); assertEquals(1, e.frauds().size)
        rejected(e.receive(alice.craft(bob.address, 1, 2, TxWire.decode(toBob)!!.txid)), "frozen")
        assertEquals(0L, e.balance(carol.address))                          // first-seen wins locally
        // another node learns it from the proof alone and verifies it itself
        val other = newEngine()
        val p = e.frauds()[0]
        assertTrue(other.receiveFraudProof(p.rawA, p.rawB)); assertTrue(other.isFrozen(alice.address))
        // forged proofs are refused
        val clean = newEngine()
        assertFalse(clean.receiveFraudProof(toBob, toBob))                                         // same tx twice
        assertFalse(clean.receiveFraudProof(toBob, alice.craft(carol.address, 1, 2, null)))         // different nonce
        val badSig = toCarol.clone().also { it[100] = (it[100].toInt() xor 1).toByte() }
        assertFalse(clean.receiveFraudProof(toBob, badSig))                                         // invalid signature
        assertFalse(clean.receiveFraudProof(toBob, bob.craft(carol.address, 600_000, 1, null)))      // different senders
        assertFalse(clean.isFrozen(alice.address))
    }

    @Test fun honestSendersNeverForkEvenUnderConcurrency() = runBlocking {
        val e = newEngine(); e.fund(alice, 100_000, "R1")
        val rs = (1..20).map { async { e.createTransfer(alice.identity, alice.kyber.publicKey, bob.address, 1_000, "") } }.awaitAll()
        assertTrue(rs.all { it.isSuccess })
        val chain = e.chain(alice.address, 1, 100)
        assertEquals((1L..20L).toList(), chain.map { it.nonce })
        for (i in 1 until chain.size) assertEquals(chain[i - 1].txid.take(16), chain[i].prevId8.toHex())
        assertEquals(80_000L, e.balance(alice.address)); assertEquals(20_000L, e.balance(bob.address)); assertTrue(e.frauds().isEmpty())
    }

    @Test fun mintedMoneyCannotExceedWhatTheIssuerCreated() = runBlocking {
        val e = newEngine()
        e.fund(alice, 300_000, "R1")
        e.send(alice, bob, 300_000); e.send(bob, carol, 300_000)
        var supply = 0L
        for (w in listOf(alice, bob, carol, issuer)) supply += e.balance(w.address)
        assertEquals(300_000L, supply)
    }

    /** Property test: random mints/transfers, replicated to other nodes in random order with duplicates. */
    @Test fun randomActivityReplicatesExactlyAndConservesMoney() = runBlocking {
        val ws = listOf(alice, bob, carol, TestWallet())
        for (seed in 1..6) {
            val rnd = java.util.Random(seed.toLong())
            val src = newEngine(); var minted = 0L
            repeat(150) { i ->
                val a = ws[rnd.nextInt(ws.size)]; val b = ws[rnd.nextInt(ws.size)]
                if (rnd.nextInt(6) == 0) { val amt = 1_000L + rnd.nextInt(50_000); src.createGenesis(issuer.identity, issuer.kyber.publicKey, a.address, amt, "S$seed-$i").getOrThrow(); minted += amt }
                else if (a !== b) src.createTransfer(a.identity, a.kyber.publicKey, b.address, 1L + rnd.nextInt(20_000), "").getOrNull()   // some fail: insufficient funds
            }
            val raws = (ws + issuer).flatMap { src.history(it.address, 10_000) }.map { it.tx }.distinctBy { it.txid }.map { it.raw }
            var supply = 0L
            for (w in ws) { val bal = src.balance(w.address); assertTrue("no negative balances", bal >= 0); supply += bal }
            assertEquals("money is conserved", minted, supply)
            for (replica in 1..3) {
                val e = newEngine()
                // Real networks retransmit: a transaction dropped because too many of its sender's earlier ones were
                // still missing (bounded pending pool) is simply offered again on the next gossip round.
                repeat(2) {
                    val feed = (raws + raws.shuffled(rnd).take(20)).shuffled(rnd)
                    if (replica == 1) feed.forEach { e.receive(it) } else feed.chunked(17).forEach { e.receiveMany(it) }
                }
                assertEquals(src.count(), e.count()); assertEquals(0, e.pendingList().size); assertTrue(e.frauds().isEmpty())
                for (w in ws) assertEquals(src.balance(w.address), e.balance(w.address))
            }
        }
    }
}
