package com.thunder.agi.node

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.thunder.agi.crypto.QuantumKeyManager
import com.thunder.agi.ledger.GossipProtocol
import com.thunder.agi.ledger.LedgerDatabase
import com.thunder.agi.ledger.LedgerEngine
import com.thunder.agi.ledger.LedgerEngine.Outcome
import com.thunder.agi.ledger.MintAuthority
import com.thunder.agi.ledger.RoomLedgerStore
import com.thunder.agi.ledger.StoredTx
import com.thunder.agi.ledger.ThunderABI
import com.thunder.agi.ledger.Transaction
import com.thunder.agi.ledger.TxBundle
import com.thunder.agi.ledger.TxText
import com.thunder.agi.ledger.fmtSDG
import com.thunder.agi.p2p.BeaconCodec
import com.thunder.agi.p2p.BleBeaconTransport
import com.thunder.agi.p2p.WifiAwareTransport
import com.thunder.agi.transfer.ChunkedTransferProtocol
import com.thunder.agi.transfer.SessionStore
import com.thunder.agi.transfer.TransferSession
import com.thunder.agi.util.TLog
import com.thunder.agi.util.toHex
import com.thunder.agi.wallet.WalletManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "ThunderRuntime"

sealed class NodeEvent {
    class PaymentReceived(val tx: Transaction) : NodeEvent()
    /** A phone without Bluetooth-5 broadcast a short notice: a payment for you exists, sync to receive it. */
    class PaymentNotice(val amountMilli: Long) : NodeEvent()
    class Info(val text: String) : NodeEvent()
}

data class NodeStatus(
    val running: Boolean = false,
    val bleScanning: Boolean = false,
    val awareSupported: Boolean = false,
    val awareActive: Boolean = false,
    val gossipPeers: Int = 0,
    val gossipPort: Int = 0,
    val hardwareKey: Boolean = false,
    val isIssuer: Boolean = false
)

data class ImportResult(val found: Int, val accepted: Int, val pending: Int, val rejected: Int)

data class WalletSnapshot(
    val balanceMilli: Long, val pendingIncomingMilli: Long, val history: List<StoredTx>,
    val frozen: Boolean, val ledgerSize: Int, val pendingCount: Int
)

/**
 * The one node object for the whole process (UI, foreground service and notification listener all share it).
 * Money path:  create -> sign in hardware -> verify -> store -> broadcast on every channel at once
 *              (BLE advertisement, WiFi-Aware, gossip to LAN/WiFi-Direct peers).
 * Receive path: any channel -> LedgerEngine (verifies everything itself) -> notification.
 */
class ThunderRuntime private constructor(context: Context) {
    companion object {
        @Volatile private var instance: ThunderRuntime? = null
        fun get(context: Context): ThunderRuntime = instance ?: synchronized(this) {
            instance ?: ThunderRuntime(context.applicationContext).also { instance = it }
        }
    }

    private val app = context.applicationContext
    val wallet = WalletManager(app)
    val engine = LedgerEngine(RoomLedgerStore(LedgerDatabase.get(app).dao()))
    private val sessions = SessionStore(File(app.filesDir, "transfers"))
    private val relayedLock = Any()
    private val relayed = LinkedHashSet<String>()
    private fun markRelayed(id: String): Boolean = synchronized(relayedLock) {
        val added = relayed.add(id)
        if (relayed.size > 1000) relayed.remove(relayed.first())
        added
    }
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val events = MutableSharedFlow<NodeEvent>(extraBufferCapacity = 32)
    private val statusFlow = MutableStateFlow(NodeStatus())
    val status: StateFlow<NodeStatus> = statusFlow
    /** Bumps whenever the ledger changes; the UI recomputes its numbers from this. */
    val ledgerVersion: StateFlow<Long> get() = engine.version

    val gossip = GossipProtocol(
        engine,
        localIdent = { wallet.identCard() },
        onPeerIdent = { _, ident -> wallet.remember(ident) },
        onAccepted = { refreshStatus() }
    )
    private val ble = BleBeaconTransport(app) { frame -> onBeacon(frame) }
    private val aware: WifiAwareTransport? = if (WifiAwareTransport.isSupported(app)) WifiAwareTransport(app) { frame -> onBeacon(frame) } else null
    private val lan = LanDiscovery(app) { host, port -> gossip.addPeer(host, port) }
    private val notifier = Notifier(app)
    @Volatile private var running = false

    init { engine.onAccepted = { tx -> handleAccepted(tx) } }

    // ── lifecycle ────────────────────────────────────────────────────────────

    @Synchronized
    fun start() {
        if (running) { housekeeping(); return }
        if (!wallet.isUsable()) { events.tryEmit(NodeEvent.Info("Wallet keys are not usable on this phone")); return }
        running = true
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // Debug builds only: anyone may mint, so the whole flow can be tested. Release builds need a listed issuer key.
        MintAuthority.devAllowSelfMint = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        gossip.start()
        lan.start(gossip.boundPort)
        ble.start()
        aware?.start()
        scope.launch { while (isActive) { delay(20_000); housekeeping() } }
        refreshStatus()
        TLog.i(TAG, "node started: ${wallet.address}")
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        lan.stop(); gossip.stop(); ble.stop(); aware?.stop()
        scope.cancel()
        refreshStatus()
    }

    /** Call after permissions were granted or Bluetooth/WiFi was switched on. */
    fun housekeeping() {
        if (!running) return
        ble.startScan()
        aware?.start()
        refreshStatus()
    }

    private fun refreshStatus() {
        statusFlow.value = NodeStatus(
            running = running, bleScanning = ble.isScanning, awareSupported = aware != null,
            awareActive = aware?.isActive == true, gossipPeers = gossip.knownPeers().size, gossipPort = gossip.boundPort,
            hardwareKey = QuantumKeyManager.isHardwareBacked(),
            isIssuer = try { MintAuthority.canMint(wallet.identity.publicKey) } catch (e: Exception) { false }
        )
    }

    // ── sending ──────────────────────────────────────────────────────────────

    suspend fun sendTransfer(to: String, amountMilli: Long, memo: String): Result<Transaction> = withContext(Dispatchers.Default) {
        start()
        val r = engine.createTransfer(wallet.identity, wallet.kyber().publicKey, to, amountMilli, memo)   // signs in hardware
        r.onSuccess { broadcast(it) }
        r
    }

    /** Issuer only (see MintAuthority): create money for a verified bank receipt. */
    suspend fun mint(to: String, amountMilli: Long, receiptRef: String): Result<Transaction> = withContext(Dispatchers.Default) {
        start()
        val r = engine.createGenesis(wallet.identity, wallet.kyber().publicKey, to, amountMilli, receiptRef)
        r.onSuccess { broadcast(it) }
        r
    }

    /** Push a transaction out on every channel at once. Recipients de-duplicate by txid, so redundancy is free. */
    fun broadcast(tx: Transaction) {
        markRelayed(tx.txid)
        ble.enqueue(tx.raw, 0)
        aware?.enqueue(tx.raw, 0)
        gossip.kick()
    }

    // ── receiving ────────────────────────────────────────────────────────────

    private suspend fun onBeacon(frame: BeaconCodec.Frame) {
        when (frame) {
            is BeaconCodec.Frame.Tx -> {
                val o = engine.receive(frame.raw)
                if (o is Outcome.Accepted && frame.hops + 1 <= BeaconCodec.MAX_HOPS && markRelayed(o.tx.txid)) {
                    ble.enqueue(frame.raw, frame.hops + 1)         // store-carry-forward: spread to phones out of the sender's range
                    aware?.enqueue(frame.raw, frame.hops + 1)
                }
            }
            is BeaconCodec.Frame.Notice -> if (BeaconCodec.noticeIsFor(frame, wallet.address)) {
                events.tryEmit(NodeEvent.PaymentNotice(frame.amountMilli))
                gossip.kick()
            }
        }
    }

    private fun handleAccepted(tx: Transaction) {
        val me = wallet.address
        if (tx.to == me && tx.from != me) {
            events.tryEmit(NodeEvent.PaymentReceived(tx))
            notifier.paymentReceived(wallet.resolveName(tx.from), tx.amountMilli, tx.txid)
        }
        val fresh = System.currentTimeMillis() / 1000 - tx.timestampSec < 600
        if (fresh && tx.from != me && markRelayed(tx.txid)) { ble.enqueue(tx.raw, 1); aware?.enqueue(tx.raw, 1) }
    }

    /** Import any text (clipboard, WhatsApp notification, shared text): finds every "TX1:" payment inside. */
    suspend fun importText(text: String): ImportResult = withContext(Dispatchers.Default) {
        val raws = TxText.extract(text)
        if (raws.isEmpty()) return@withContext ImportResult(0, 0, 0, 0)
        start()
        val r = engine.receiveMany(raws)
        if (r.accepted > 0) gossip.kick()
        ImportResult(raws.size, r.accepted, r.pending, r.rejected)
    }

    /** Fire-and-forget variant for callers that are not coroutines (e.g. the notification listener). */
    fun importTextAsync(text: String) { scope.launch { try { importText(text) } catch (e: Exception) { TLog.w(TAG, "import: ${e.message}") } } }

    /** A "⚡T:" chunk of a bulk bundle. Persists progress, reassembles when complete. Returns a progress string. */
    suspend fun importChunk(text: String): String? = withContext(Dispatchers.Default) {
        val chunk = ChunkedTransferProtocol.decodeText(text) ?: return@withContext null
        val s = sessions.load(chunk.sessionId) ?: TransferSession(chunk.sessionId, chunk.total)
        if (!s.add(chunk)) return@withContext null
        sessions.save(s)
        if (!s.isComplete()) return@withContext "${s.progressPercent()}%"
        val bytes = ChunkedTransferProtocol.reassemble(s)
        sessions.delete(s.sessionId)
        if (bytes == null) return@withContext "corrupt"
        start()
        val r = engine.receiveMany(TxBundle.decode(bytes))
        if (r.accepted > 0) gossip.kick()
        "done: ${r.accepted} new"
    }

    fun importChunkAsync(text: String) { scope.launch { try { importChunk(text) } catch (e: Exception) { TLog.w(TAG, "chunk: ${e.message}") } } }

    /**
     * Operator tool: the most recent [maxTxs] transactions as "⚡T:" chunk messages (each fits one WhatsApp message),
     * e.g. to post into the Thunder AGI chat so phones that were offline can catch up. Resumable on the receiving side.
     */
    suspend fun exportLedgerChunks(maxTxs: Int = 300): List<String> = withContext(Dispatchers.Default) {
        val all = ArrayList<Transaction>()
        for (f in engine.frontiers(ThunderABI.MAX_FRONTIERS)) all.addAll(engine.chain(f.address, 1, 5000))
        val recent = all.sortedBy { it.timestampSec }.takeLast(maxTxs).map { it.raw }
        ChunkedTransferProtocol.split(TxBundle.encode(recent)).map { ChunkedTransferProtocol.encodeText(it) }
    }

    // ── reads for the UI ─────────────────────────────────────────────────────

    suspend fun snapshot(): WalletSnapshot {
        val me = wallet.address
        return WalletSnapshot(
            balanceMilli = engine.balance(me), pendingIncomingMilli = engine.pendingIncoming(me),
            history = engine.history(me, 200), frozen = engine.isFrozen(me),
            ledgerSize = engine.count(), pendingCount = engine.pendingList().size
        )
    }

    fun isIssuer(): Boolean = try { MintAuthority.canMint(wallet.identity.publicKey) } catch (e: Exception) { false }
    fun issuerKeyHex(): String = wallet.identity.publicKey.toHex()

    private class Notifier(private val app: Context) {
        private val channel = "thunder_payments"
        fun paymentReceived(from: String, amountMilli: Long, txid: String) {
            try {
                if (Build.VERSION.SDK_INT >= 33 &&
                    app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
                val nm = app.getSystemService(NotificationManager::class.java) ?: return
                if (nm.getNotificationChannel(channel) == null)
                    nm.createNotificationChannel(NotificationChannel(channel, "Payments", NotificationManager.IMPORTANCE_HIGH))
                val launch = app.packageManager.getLaunchIntentForPackage(app.packageName) ?: Intent()
                val pi = PendingIntent.getActivity(app, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                val n = Notification.Builder(app, channel)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Payment received")
                    .setContentText("$from sent you ${amountMilli.fmtSDG()}")
                    .setAutoCancel(true).setContentIntent(pi).build()
                nm.notify(txid.hashCode(), n)
            } catch (e: Exception) { TLog.w(TAG, "notify: ${e.message}") }
        }
    }
}
