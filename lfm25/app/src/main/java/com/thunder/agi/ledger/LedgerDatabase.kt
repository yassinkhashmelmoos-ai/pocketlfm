package com.thunder.agi.ledger

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Accepted transactions. The UNIQUE index on (fromAddr, nonce) means that even a bug in the engine
 * cannot store two different transactions with the same sender and nonce: the database refuses the second.
 */
@Entity(
    tableName = "txs",
    indices = [
        Index(value = ["fromAddr", "nonce"], unique = true),
        Index(value = ["toAddr"]),
        Index(value = ["timestampSec"])
    ]
)
data class TxEntity(
    @PrimaryKey val txid: String,
    val fromAddr: String,
    val toAddr: String,
    val nonce: Long,
    val amount: Long,
    val type: Int,
    val memo: String,
    val timestampSec: Long,
    val receivedAt: Long,
    val raw: ByteArray
)

@Entity(tableName = "pending", indices = [Index(value = ["fromAddr"]), Index(value = ["toAddr"])])
data class PendingEntity(
    @PrimaryKey val txid: String,
    val fromAddr: String,
    val toAddr: String,
    val amount: Long,
    val reason: String,
    val receivedAt: Long,
    val raw: ByteArray
)

@Entity(tableName = "fraud")
data class FraudEntity(
    @PrimaryKey val address: String,
    val rawA: ByteArray,
    val rawB: ByteArray,
    val detectedAt: Long
)

data class FrontierRow(val fromAddr: String, val nonce: Long, val txid: String)

@Dao
interface LedgerDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTx(e: TxEntity): Long
    @Query("SELECT * FROM txs WHERE txid = :id LIMIT 1") suspend fun byId(id: String): TxEntity?
    @Query("SELECT * FROM txs WHERE fromAddr = :a AND nonce = :n LIMIT 1") suspend fun byFromNonce(a: String, n: Long): TxEntity?
    @Query("SELECT * FROM txs WHERE fromAddr = :a ORDER BY nonce DESC LIMIT 1") suspend fun lastFrom(a: String): TxEntity?

    @Query(
        "SELECT COALESCE(SUM(CASE WHEN toAddr = :a THEN amount ELSE 0 END), 0) - " +
        "COALESCE(SUM(CASE WHEN fromAddr = :a AND type = 1 THEN amount ELSE 0 END), 0) " +
        "FROM txs WHERE toAddr = :a OR fromAddr = :a"
    )
    suspend fun balance(a: String): Long

    @Query("SELECT * FROM txs WHERE fromAddr = :a OR toAddr = :a ORDER BY timestampSec DESC, nonce DESC LIMIT :limit")
    suspend fun history(a: String, limit: Int): List<TxEntity>

    @Query(
        "SELECT t.fromAddr AS fromAddr, t.nonce AS nonce, t.txid AS txid FROM txs t " +
        "JOIN (SELECT fromAddr AS fa, MAX(nonce) AS mn FROM txs GROUP BY fromAddr) m " +
        "ON t.fromAddr = m.fa AND t.nonce = m.mn ORDER BY t.receivedAt DESC LIMIT :limit"
    )
    suspend fun frontiers(limit: Int): List<FrontierRow>

    @Query("SELECT * FROM txs WHERE fromAddr = :a AND nonce >= :min ORDER BY nonce ASC LIMIT :limit")
    suspend fun chain(a: String, min: Long, limit: Int): List<TxEntity>

    @Query("SELECT COUNT(*) FROM txs") suspend fun count(): Int
    @Query("SELECT COUNT(*) FROM txs WHERE fromAddr = :a AND memo = :memo") suspend fun countMemo(a: String, memo: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPending(e: PendingEntity)
    @Query("SELECT * FROM pending ORDER BY receivedAt ASC") suspend fun pending(): List<PendingEntity>
    @Query("SELECT * FROM pending WHERE fromAddr = :a") suspend fun pendingFrom(a: String): List<PendingEntity>
    @Query("SELECT COUNT(*) FROM pending") suspend fun pendingCount(): Int
    @Query("SELECT COUNT(*) FROM pending WHERE fromAddr = :a") suspend fun pendingCountFrom(a: String): Int
    @Query("DELETE FROM pending WHERE txid = :id") suspend fun removePending(id: String)
    @Query("DELETE FROM pending WHERE txid = (SELECT txid FROM pending ORDER BY receivedAt ASC LIMIT 1)") suspend fun dropOldestPending()
    @Query("SELECT COALESCE(SUM(amount), 0) FROM pending WHERE toAddr = :a") suspend fun pendingIncoming(a: String): Long
    @Query("DELETE FROM pending WHERE receivedAt < :cutoff") suspend fun pruneBefore(cutoff: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun putFraud(e: FraudEntity)
    @Query("SELECT COUNT(*) FROM fraud WHERE address = :a") suspend fun fraudCount(a: String): Int
    @Query("SELECT * FROM fraud") suspend fun frauds(): List<FraudEntity>
}

@Database(entities = [TxEntity::class, PendingEntity::class, FraudEntity::class], version = 1, exportSchema = false)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun dao(): LedgerDao

    companion object {
        @Volatile private var instance: LedgerDatabase? = null

        // No fallbackToDestructiveMigration on purpose: silently wiping a money ledger on a schema change
        // is worse than failing loudly. Add a real Migration when the schema changes.
        fun get(context: Context): LedgerDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LedgerDatabase::class.java, "thunder_ledger.db")
                .build().also { instance = it }
        }
    }
}

class RoomLedgerStore(private val dao: LedgerDao) : LedgerStore {
    private fun TxEntity.tx(): Transaction? = TxWire.decode(raw)
    private fun List<TxEntity>.txs() = mapNotNull { it.tx() }

    override suspend fun insert(tx: Transaction, receivedAtMs: Long): Boolean =
        dao.insertTx(TxEntity(tx.txid, tx.from, tx.to, tx.nonce, tx.amountMilli, tx.type.code, tx.memo, tx.timestampSec, receivedAtMs, tx.raw)) != -1L

    override suspend fun get(txid: String) = dao.byId(txid)?.tx()
    override suspend fun getByFromNonce(from: String, nonce: Long) = dao.byFromNonce(from, nonce)?.tx()
    override suspend fun lastFrom(from: String) = dao.lastFrom(from)?.tx()
    override suspend fun balanceMilli(address: String) = dao.balance(address)
    override suspend fun history(address: String, limit: Int) =
        dao.history(address, limit).mapNotNull { e -> e.tx()?.let { StoredTx(it, e.receivedAt) } }
    override suspend fun frontiers(limit: Int) = dao.frontiers(limit).map { Frontier(it.fromAddr, it.nonce, it.txid) }
    override suspend fun chain(from: String, minNonce: Long, limit: Int) = dao.chain(from, minNonce, limit).txs()
    override suspend fun count() = dao.count()
    override suspend fun hasMemoFrom(from: String, memo: String) = dao.countMemo(from, memo) > 0

    override suspend fun putPending(p: PendingTx) =
        dao.putPending(PendingEntity(p.txid, p.from, p.to, p.amountMilli, p.reason, p.receivedAtMs, p.raw))
    private fun PendingEntity.p() = PendingTx(txid, raw, fromAddr, toAddr, amount, reason, receivedAt)
    override suspend fun pending() = dao.pending().map { it.p() }
    override suspend fun pendingFrom(address: String) = dao.pendingFrom(address).map { it.p() }
    override suspend fun pendingCount() = dao.pendingCount()
    override suspend fun pendingCountFrom(address: String) = dao.pendingCountFrom(address)
    override suspend fun removePending(txid: String) = dao.removePending(txid)
    override suspend fun dropOldestPending() = dao.dropOldestPending()
    override suspend fun pendingIncomingMilli(address: String) = dao.pendingIncoming(address)
    override suspend fun pruneBefore(cutoffMs: Long) = dao.pruneBefore(cutoffMs)

    override suspend fun isFrozen(address: String) = dao.fraudCount(address) > 0
    override suspend fun putFraud(p: FraudProof) = dao.putFraud(FraudEntity(p.address, p.rawA, p.rawB, p.detectedAtMs))
    override suspend fun frauds() = dao.frauds().map { FraudProof(it.address, it.rawA, it.rawB, it.detectedAt) }
}
