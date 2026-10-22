package com.thunder.agi.ledger

/** A transaction accepted into the ledger. */
data class StoredTx(val tx: Transaction, val receivedAtMs: Long)

/** Signature-valid but not yet verifiable (missing earlier transactions of the sender, or funds not synced yet). */
class PendingTx(
    val txid: String, val raw: ByteArray, val from: String, val to: String,
    val amountMilli: Long, val reason: String, val receivedAtMs: Long
)

/** Two different valid transactions signed by one address with the same nonce = provable double-spend. */
class FraudProof(val address: String, val rawA: ByteArray, val rawB: ByteArray, val detectedAtMs: Long)

/** Per-sender chain head: used by gossip to find exactly what a peer is missing. */
data class Frontier(val address: String, val nonce: Long, val lastTxId: String)

interface LedgerStore {
    suspend fun insert(tx: Transaction, receivedAtMs: Long): Boolean
    suspend fun get(txid: String): Transaction?
    suspend fun getByFromNonce(from: String, nonce: Long): Transaction?
    suspend fun lastFrom(from: String): Transaction?
    suspend fun balanceMilli(address: String): Long
    suspend fun history(address: String, limit: Int): List<StoredTx>
    suspend fun frontiers(limit: Int): List<Frontier>
    suspend fun chain(from: String, minNonce: Long, limit: Int): List<Transaction>
    suspend fun count(): Int
    suspend fun hasMemoFrom(from: String, memo: String): Boolean

    suspend fun putPending(p: PendingTx)
    suspend fun pending(): List<PendingTx>
    suspend fun pendingFrom(address: String): List<PendingTx>
    suspend fun pendingCount(): Int
    suspend fun pendingCountFrom(address: String): Int
    suspend fun removePending(txid: String)
    suspend fun dropOldestPending()
    suspend fun pendingIncomingMilli(address: String): Long
    suspend fun pruneBefore(cutoffMs: Long)

    suspend fun isFrozen(address: String): Boolean
    suspend fun putFraud(p: FraudProof)
    suspend fun frauds(): List<FraudProof>
}
