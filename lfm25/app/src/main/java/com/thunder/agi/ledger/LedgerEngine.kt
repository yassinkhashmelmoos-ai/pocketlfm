package com.thunder.agi.ledger

import com.thunder.agi.crypto.EcCodec
import com.thunder.agi.crypto.Identity
import com.thunder.agi.util.TLog
import com.thunder.agi.util.hexToBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "LedgerEngine"

/**
 * The ledger rules. Every transaction, whether created locally or received from any transport, passes
 * through [process]; no node trusts any other node.
 *
 *  1. structure & limits  - canonical format, amount 1..1e12 milli-SDG, memo <= 48 bytes, timestamp not in the future
 *  2. sender binding      - the signing key + Kyber-hash in the tx must hash to the `from` address
 *                           (the old code only checked the signature against a key the attacker supplied!)
 *  3. signature           - ECDSA-P256 over the canonical body
 *  4. mint authority      - GENESIS is accepted only from an authorized issuer key
 *  5. frozen senders      - addresses with a double-spend proof can no longer move money
 *  6. strict chain        - nonce == last+1 and prevId8 == last txid (first 8 bytes); a second valid tx
 *                           with an already-used nonce is a FORK = proof of double-spend -> sender frozen
 *  7. balance             - sender balance >= amount (else held PENDING until incoming funds sync)
 *
 * All state changes happen under one mutex (the old code checked balance and inserted non-atomically).
 *
 * HONEST LIMIT: without a global consensus (like a blockchain) an offline double-spend cannot be PREVENTED,
 * only DETECTED, proven and punished. Treat an offline payment as provisional until your wallet has synced
 * with other devices (and, for large amounts, until the issuer confirms it).
 */
class LedgerEngine(
    private val store: LedgerStore,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    sealed class Outcome {
        data class Accepted(val tx: Transaction) : Outcome()
        data class Pending(val tx: Transaction, val reason: String) : Outcome()
        data class Rejected(val reason: String) : Outcome()
        data class Fork(val address: String) : Outcome()
        object Duplicate : Outcome()
    }

    data class BatchResult(val accepted: Int, val pending: Int, val rejected: Int, val forks: Int, val duplicates: Int) {
        val changed get() = accepted + pending + forks > 0
    }

    companion object {
        const val MAX_FUTURE_SEC = 24 * 3600L
        const val PENDING_TTL_MS = 3 * 24 * 3600 * 1000L
        const val MAX_PENDING = 500
        const val MAX_PENDING_PER_SENDER = 25
    }

    /** Called (non-suspending, must return fast) for every transaction that enters the ledger, from any source. */
    @Volatile var onAccepted: ((Transaction) -> Unit)? = null

    private val mutex = Mutex()
    private val _version = MutableStateFlow(0L)
    /** Increments on every ledger change; the UI recomputes balances when it changes. */
    val version: StateFlow<Long> = _version
    private fun bump() { _version.value = _version.value + 1 }

    // ── receiving ────────────────────────────────────────────────────────────

    suspend fun receive(raw: ByteArray): Outcome = mutex.withLock {
        store.pruneBefore(clock() - PENDING_TTL_MS)
        val o = process(raw, fromRetry = false)
        if (o is Outcome.Accepted) drain(listOf(o.tx))
        if (o is Outcome.Accepted || o is Outcome.Pending || o is Outcome.Fork) bump()
        o
    }

    suspend fun receiveMany(raws: List<ByteArray>): BatchResult = mutex.withLock {
        store.pruneBefore(clock() - PENDING_TTL_MS)
        val parsed = raws.mapNotNull { r -> TxWire.decode(r)?.let { it to r } }
        var accepted = 0; var pending = 0; var rejected = raws.size - parsed.size; var forks = 0; var dups = 0
        val acceptedTxs = ArrayList<Transaction>()
        // apply older transactions first so chains and credits arrive in a natural order
        for ((_, raw) in parsed.sortedWith(compareBy({ it.first.timestampSec }, { it.first.nonce }))) {
            when (val o = process(raw, fromRetry = false)) {
                is Outcome.Accepted -> { accepted++; acceptedTxs.add(o.tx) }
                is Outcome.Pending -> pending++
                is Outcome.Rejected -> rejected++
                is Outcome.Fork -> forks++
                Outcome.Duplicate -> dups++
            }
        }
        accepted += drain(acceptedTxs)                       // include transactions released from the pending pool
        val r = BatchResult(accepted, pending, rejected, forks, dups)
        if (r.changed) bump()
        r
    }

    /** A peer tells us "address X double-spent": we verify both transactions ourselves before freezing. */
    suspend fun receiveFraudProof(rawA: ByteArray, rawB: ByteArray): Boolean = mutex.withLock {
        val a = TxWire.decode(rawA) ?: return@withLock false
        val b = TxWire.decode(rawB) ?: return@withLock false
        if (a.from != b.from || a.nonce != b.nonce || a.txid == b.txid) return@withLock false
        if (verifyAuth(a) != null || verifyAuth(b) != null) return@withLock false
        if (store.isFrozen(a.from)) return@withLock false
        store.putFraud(FraudProof(a.from, rawA, rawB, clock()))
        TLog.w(TAG, "Address ${a.from} frozen: valid fraud proof received")
        bump()
        true
    }

    // ── creating ─────────────────────────────────────────────────────────────

    suspend fun createTransfer(
        identity: Identity, kyberPublicKey: ByteArray, to: String, amountMilli: Long, memo: String
    ): Result<Transaction> = mutex.withLock {
        try {
            val me = WalletAddress.derive(identity.publicKey, kyberPublicKey)
            val toPayload = WalletAddress.toPayload(to) ?: throw IllegalArgumentException("Invalid recipient address")
            require(to != me) { "You cannot send money to yourself" }
            require(amountMilli in 1..TxWire.MAX_AMOUNT_MILLI) { "Amount is out of range" }
            val memoBytes = memo.trim().toByteArray(Charsets.UTF_8)
            require(memoBytes.size <= TxWire.MAX_MEMO_BYTES) { "Note is too long (max ${TxWire.MAX_MEMO_BYTES} bytes)" }
            check(!store.isFrozen(me)) { "This wallet is frozen" }
            val balance = store.balanceMilli(me)
            check(balance >= amountMilli) { "Insufficient balance: you have ${balance.fmtSDG()}" }
            Result.success(signAndAccept(identity, kyberPublicKey, me, Transaction.Type.TRANSFER, toPayload, amountMilli, memoBytes))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
    }

    /** Issuer only: create money after verifying a real bank receipt. [receiptRef] makes double-minting impossible. */
    suspend fun createGenesis(
        identity: Identity, kyberPublicKey: ByteArray, to: String, amountMilli: Long, receiptRef: String
    ): Result<Transaction> = mutex.withLock {
        try {
            check(MintAuthority.canMint(identity.publicKey)) { "This device is not an authorized issuer" }
            val me = WalletAddress.derive(identity.publicKey, kyberPublicKey)
            val toPayload = WalletAddress.toPayload(to) ?: throw IllegalArgumentException("Invalid recipient address")
            require(amountMilli in 1..TxWire.MAX_AMOUNT_MILLI) { "Amount is out of range" }
            val ref = receiptRef.trim()
            require(ref.isNotEmpty()) { "Receipt reference is required" }
            val memo = "DEP:$ref".toByteArray(Charsets.UTF_8)
            require(memo.size <= TxWire.MAX_MEMO_BYTES) { "Receipt reference too long" }
            check(!store.hasMemoFrom(me, "DEP:$ref")) { "This receipt was already deposited" }
            Result.success(signAndAccept(identity, kyberPublicKey, me, Transaction.Type.GENESIS, toPayload, amountMilli, memo))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
    }

    private suspend fun signAndAccept(
        identity: Identity, kyberPub: ByteArray, me: String, type: Transaction.Type,
        toPayload: ByteArray, amountMilli: Long, memoBytes: ByteArray
    ): Transaction {
        val last = store.lastFrom(me)
        val nonce = (last?.nonce ?: 0L) + 1
        val prev8 = last?.txid?.hexToBytes()?.copyOf(8) ?: ByteArray(8)
        val body = TxWire.buildBody(
            type, WalletAddress.toPayload(me)!!, toPayload, amountMilli, nonce, clock() / 1000, prev8, memoBytes
        )
        val sig = identity.sign(TxWire.signingMessage(body))
        val raw = TxWire.assemble(body, sig, identity.publicKey, WalletAddress.kyberHash16(kyberPub))
        val tx = TxWire.decode(raw) ?: error("internal: cannot parse own transaction")
        store.pruneBefore(clock() - PENDING_TTL_MS)
        val outcome = process(raw, fromRetry = false)
        check(outcome is Outcome.Accepted) { "internal self-check failed: $outcome" }
        drain(listOf(tx))
        bump()
        return tx
    }

    // ── the single rule pipeline ─────────────────────────────────────────────

    private fun verifyAuth(tx: Transaction): String? {
        if (!WalletAddress.matches(tx.from, tx.senderPubKey, tx.senderKyberHash))
            return "signing key does not belong to the sender address"
        if (!EcCodec.verify(tx.senderPubKey, TxWire.signingMessage(tx.body), tx.signature))
            return "invalid signature"
        if (tx.type == Transaction.Type.GENESIS && !MintAuthority.canMint(tx.senderPubKey))
            return "not signed by an authorized issuer"
        return null
    }

    private suspend fun process(raw: ByteArray, fromRetry: Boolean): Outcome {
        val tx = TxWire.decode(raw) ?: return Outcome.Rejected("malformed transaction")
        if (tx.amountMilli !in 1..TxWire.MAX_AMOUNT_MILLI) return Outcome.Rejected("amount out of range")
        if (tx.timestampSec > clock() / 1000 + MAX_FUTURE_SEC) return Outcome.Rejected("timestamp is in the future")
        if (tx.type == Transaction.Type.TRANSFER && tx.from == tx.to) return Outcome.Rejected("self transfer")

        if (store.get(tx.txid) != null) return Outcome.Duplicate
        if (!fromRetry) verifyAuth(tx)?.let { return Outcome.Rejected(it) }   // pending items were verified when stashed
        if (store.isFrozen(tx.from)) return Outcome.Rejected("sender is frozen (double-spend proof)")

        val last = store.lastFrom(tx.from)
        val expected = (last?.nonce ?: 0L) + 1
        if (tx.nonce < expected) {
            val existing = store.getByFromNonce(tx.from, tx.nonce)
            if (existing != null && existing.txid != tx.txid) {
                store.putFraud(FraudProof(tx.from, existing.raw, tx.raw, clock()))
                TLog.w(TAG, "FORK by ${tx.from} at nonce ${tx.nonce}: sender frozen")
                return Outcome.Fork(tx.from)
            }
            return Outcome.Rejected("stale transaction")
        }
        if (tx.nonce > expected) return stash(tx, "waiting for earlier transactions from this sender", fromRetry)

        val wantPrev = last?.txid?.hexToBytes()?.copyOf(8) ?: ByteArray(8)
        if (!tx.prevId8.contentEquals(wantPrev)) return Outcome.Rejected("broken chain link")

        if (tx.type == Transaction.Type.TRANSFER && store.balanceMilli(tx.from) < tx.amountMilli)
            return stash(tx, "sender balance not proven yet (waiting for incoming funds to sync)", fromRetry)

        if (!store.insert(tx, clock())) return Outcome.Rejected("nonce already used")
        store.removePending(tx.txid)
        try { onAccepted?.invoke(tx) } catch (e: Exception) { TLog.w(TAG, "onAccepted listener failed: ${e.message}") }
        return Outcome.Accepted(tx)
    }

    private suspend fun stash(tx: Transaction, reason: String, fromRetry: Boolean): Outcome {
        if (!fromRetry) {
            if (store.pendingCountFrom(tx.from) >= MAX_PENDING_PER_SENDER) return Outcome.Rejected("too many pending from sender")
            if (store.pendingCount() >= MAX_PENDING) store.dropOldestPending()
            store.putPending(PendingTx(tx.txid, tx.raw, tx.from, tx.to, tx.amountMilli, reason, clock()))
        }
        return Outcome.Pending(tx, reason)
    }

    /**
     * Releases held transactions that were waiting for something that just arrived. A pending transaction can only
     * become acceptable when (a) the sender's previous transaction is accepted, or (b) the sender receives money.
     * So after accepting T we re-check only pending transactions FROM T.from and FROM T.to. Each accepted
     * transaction is processed once, so the work is bounded and no long dependency chain can get stuck.
     * Returns the number of transactions released.
     */
    private suspend fun drain(seed: List<Transaction>): Int {
        var released = 0
        val queue = ArrayDeque(seed)
        var budget = 20_000
        while (queue.isNotEmpty() && budget-- > 0) {
            val t = queue.removeFirst()
            val candidates = (store.pendingFrom(t.from) + store.pendingFrom(t.to)).distinctBy { it.txid }
            for (p in candidates) {
                when (val o = process(p.raw, fromRetry = true)) {
                    is Outcome.Accepted -> { released++; queue.addLast(o.tx) }
                    is Outcome.Pending -> {}
                    else -> store.removePending(p.txid)
                }
            }
        }
        return released
    }

    // ── reads ────────────────────────────────────────────────────────────────

    suspend fun balance(address: String): Long = store.balanceMilli(address)
    suspend fun pendingIncoming(address: String): Long = store.pendingIncomingMilli(address)
    suspend fun history(address: String, limit: Int = 200): List<StoredTx> = store.history(address, limit)
    suspend fun count(): Int = store.count()
    suspend fun isFrozen(address: String): Boolean = store.isFrozen(address)
    suspend fun frontiers(limit: Int): List<Frontier> = store.frontiers(limit)
    suspend fun chain(from: String, minNonce: Long, limit: Int): List<Transaction> = store.chain(from, minNonce, limit)
    suspend fun lastFrom(from: String): Transaction? = store.lastFrom(from)
    suspend fun frauds(): List<FraudProof> = store.frauds()
    suspend fun pendingList(): List<PendingTx> = store.pending()
}
