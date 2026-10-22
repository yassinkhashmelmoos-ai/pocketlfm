package com.thunder.agi

import com.thunder.agi.crypto.EcCodec
import com.thunder.agi.crypto.Identity
import com.thunder.agi.crypto.Kem
import com.thunder.agi.ledger.FraudProof
import com.thunder.agi.ledger.Frontier
import com.thunder.agi.ledger.LedgerStore
import com.thunder.agi.ledger.PendingTx
import com.thunder.agi.ledger.StoredTx
import com.thunder.agi.ledger.Transaction
import com.thunder.agi.ledger.TxWire
import com.thunder.agi.ledger.WalletAddress
import com.thunder.agi.util.hexToBytes
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

class TestIdentity(private val kp: KeyPair) : Identity {
    override val publicKey: ByteArray = EcCodec.compress(kp.public as ECPublicKey)
    override fun sign(message: ByteArray): ByteArray {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(kp.private)
        s.update(message)
        return EcCodec.derToRaw(s.sign())
    }
    companion object {
        fun generate(): TestIdentity {
            val g = KeyPairGenerator.getInstance("EC")
            g.initialize(ECGenParameterSpec("secp256r1"))
            return TestIdentity(g.generateKeyPair())
        }
    }
}

/** A complete test wallet: signing identity + Kyber key + derived address. */
class TestWallet(val identity: TestIdentity = TestIdentity.generate(), val kyber: Kem.KeyPair = Kem.generate()) {
    val address: String = WalletAddress.derive(identity.publicKey, kyber.publicKey)
    val kyberHash: ByteArray = WalletAddress.kyberHash16(kyber.publicKey)

    /** Craft a signed transaction by hand (so tests can build forks, forgeries and out-of-order chains). */
    fun craft(
        to: String, amountMilli: Long, nonce: Long, prevTxId: String?,
        type: Transaction.Type = Transaction.Type.TRANSFER, memo: String = "", tsSec: Long = System.currentTimeMillis() / 1000,
        claimFrom: String = address
    ): ByteArray {
        val prev8 = prevTxId?.hexToBytes()?.copyOf(8) ?: ByteArray(8)
        val body = TxWire.buildBody(type, WalletAddress.toPayload(claimFrom)!!, WalletAddress.toPayload(to)!!,
            amountMilli, nonce, tsSec, prev8, memo.toByteArray())
        return TxWire.assemble(body, identity.sign(TxWire.signingMessage(body)), identity.publicKey, kyberHash)
    }
}

class InMemoryLedgerStore : LedgerStore {
    private val lock = Any()
    private val txs = LinkedHashMap<String, StoredTx>()
    private val byNonce = HashMap<Pair<String, Long>, String>()
    private val pend = LinkedHashMap<String, PendingTx>()
    private val fraud = LinkedHashMap<String, FraudProof>()

    override suspend fun insert(tx: Transaction, receivedAtMs: Long): Boolean = synchronized(lock) {
        if (txs.containsKey(tx.txid) || byNonce.containsKey(tx.from to tx.nonce)) return false
        txs[tx.txid] = StoredTx(tx, receivedAtMs)
        byNonce[tx.from to tx.nonce] = tx.txid
        true
    }
    override suspend fun get(txid: String) = synchronized(lock) { txs[txid]?.tx }
    override suspend fun getByFromNonce(from: String, nonce: Long) = synchronized(lock) { byNonce[from to nonce]?.let { txs[it]?.tx } }
    override suspend fun lastFrom(from: String) = synchronized(lock) { txs.values.map { it.tx }.filter { it.from == from }.maxByOrNull { it.nonce } }
    override suspend fun balanceMilli(address: String) = synchronized(lock) {
        var b = 0L
        for (s in txs.values) {
            if (s.tx.to == address) b += s.tx.amountMilli
            if (s.tx.from == address && s.tx.type == Transaction.Type.TRANSFER) b -= s.tx.amountMilli
        }
        b
    }
    override suspend fun history(address: String, limit: Int) = synchronized(lock) {
        txs.values.filter { it.tx.from == address || it.tx.to == address }.sortedByDescending { it.tx.timestampSec }.take(limit)
    }
    override suspend fun frontiers(limit: Int) = synchronized(lock) {
        txs.values.map { it.tx }.groupBy { it.from }.map { (a, l) -> l.maxByOrNull { it.nonce }!!.let { Frontier(a, it.nonce, it.txid) } }.take(limit)
    }
    override suspend fun chain(from: String, minNonce: Long, limit: Int) = synchronized(lock) {
        txs.values.map { it.tx }.filter { it.from == from && it.nonce >= minNonce }.sortedBy { it.nonce }.take(limit)
    }
    override suspend fun count() = synchronized(lock) { txs.size }
    override suspend fun hasMemoFrom(from: String, memo: String) = synchronized(lock) { txs.values.any { it.tx.from == from && it.tx.memo == memo } }

    override suspend fun putPending(p: PendingTx) { synchronized(lock) { pend[p.txid] = p } }
    override suspend fun pending() = synchronized(lock) { pend.values.toList() }
    override suspend fun pendingFrom(address: String) = synchronized(lock) { pend.values.filter { it.from == address } }
    override suspend fun pendingCount() = synchronized(lock) { pend.size }
    override suspend fun pendingCountFrom(address: String) = synchronized(lock) { pend.values.count { it.from == address } }
    override suspend fun removePending(txid: String) { synchronized(lock) { pend.remove(txid) } }
    override suspend fun dropOldestPending() { synchronized(lock) { pend.values.minByOrNull { it.receivedAtMs }?.let { pend.remove(it.txid) } } }
    override suspend fun pendingIncomingMilli(address: String) = synchronized(lock) { pend.values.filter { it.to == address }.sumOf { it.amountMilli } }
    override suspend fun pruneBefore(cutoffMs: Long) { synchronized(lock) { pend.values.removeAll { it.receivedAtMs < cutoffMs } } }

    override suspend fun isFrozen(address: String) = synchronized(lock) { fraud.containsKey(address) }
    override suspend fun putFraud(p: FraudProof) { synchronized(lock) { fraud.putIfAbsent(p.address, p) } }
    override suspend fun frauds() = synchronized(lock) { fraud.values.toList() }
}
