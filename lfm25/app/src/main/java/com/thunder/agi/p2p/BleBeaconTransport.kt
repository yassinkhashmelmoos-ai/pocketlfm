package com.thunder.agi.p2p

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.thunder.agi.ledger.TxWire
import com.thunder.agi.util.TLog
import com.thunder.agi.util.sha256
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

private const val TAG = "BleBeacon"

/**
 * Connectionless payments over Bluetooth Low Energy advertisements: no pairing, no connection, no password.
 *
 *  SEND   Bluetooth-5 extended advertising carries the whole signed transaction (<= 229 bytes) in ONE broadcast.
 *         Phones without extended advertising can only send a 17-byte NOTICE ("a payment for you exists").
 *  RECEIVE A passive scanner (filtered on our manufacturer-data magic) hands every frame to [onFrame]; the ledger
 *         verifies the signature itself, so the sender is never trusted.
 *
 * Company id 0xFFFF is the Bluetooth SIG "reserved for testing" value; frames are recognised by their 'T','X' /
 * 'T','N' magic. Before a public release, register a company id (or move to a 16-bit service UUID) to avoid
 * collisions with other apps that also use 0xFFFF.
 *
 * Needs (Android 12+): BLUETOOTH_SCAN + BLUETOOTH_ADVERTISE; (Android <=11): ACCESS_FINE_LOCATION for scanning.
 */
class BleBeaconTransport(
    private val context: Context,
    private val onFrame: suspend (BeaconCodec.Frame) -> Unit
) {
    companion object {
        const val COMPANY_ID = 0xFFFF
        private const val ROTATE_MS = 3_000L
        private const val LIFETIME_MS = 45_000L
        private const val MAX_QUEUE = 20
        private const val MAX_FRAMES_PER_SEC = 40
    }

    private class Item(val raw: ByteArray, val full: ByteArray, val notice: ByteArray?, val expiresAt: Long)

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = ArrayDeque<Item>()
    private var advJob: Job? = null
    private var currentSet: AdvertisingSet? = null
    private var setCallback: AdvertisingSetCallback? = null
    private var legacyCallback: AdvertiseCallback? = null
    private var scanning = false
    private var scanCallback: ScanCallback? = null

    private val seen = object : LinkedHashMap<Long, Boolean>(256, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, Boolean>?) = size > 600
    }
    private var windowStart = 0L
    private var windowCount = 0

    private val adapter: BluetoothAdapter? get() =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    fun canAdvertise() = Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_ADVERTISE)
    fun canScan() = if (Build.VERSION.SDK_INT >= 31) granted(Manifest.permission.BLUETOOTH_SCAN) else granted(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun extendedSupported(): Boolean =
        Build.VERSION.SDK_INT >= 26 && adapter?.isLeExtendedAdvertisingSupported == true

    val isScanning: Boolean get() = scanning

    // ── lifecycle ────────────────────────────────────────────────────────────

    fun start() {
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        startScan()
    }

    fun stop() {
        stopScan()
        stopAdvertising()
        advJob?.cancel(); advJob = null
        scope.cancel()
        synchronized(queue) { queue.clear() }
    }

    // ── sending ──────────────────────────────────────────────────────────────

    /** Queue a transaction for broadcast (45 s, rotated with others). [hops] counts relays; frames stop at MAX_HOPS. */
    fun enqueue(raw: ByteArray, hops: Int = 0) {
        if (hops > BeaconCodec.MAX_HOPS || !canAdvertise()) return
        val tx = TxWire.decode(raw) ?: return
        val item = Item(raw, BeaconCodec.encodeTx(raw, hops), BeaconCodec.encodeNotice(tx), System.currentTimeMillis() + LIFETIME_MS)
        synchronized(queue) {
            queue.removeAll { it.raw.contentEquals(raw) }
            queue.addLast(item)
            while (queue.size > MAX_QUEUE) queue.removeFirst()
        }
        ensureAdvertising()
    }

    private fun ensureAdvertising() {
        if (advJob?.isActive == true) return
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        advJob = scope.launch {
            try {
                while (isActive) {
                    val item = synchronized(queue) {
                        val now = System.currentTimeMillis()
                        queue.removeAll { it.expiresAt < now }
                        queue.removeFirstOrNull()?.also { queue.addLast(it) }
                    } ?: break
                    advertise(item)
                    delay(ROTATE_MS)
                }
            } finally { stopAdvertising() }
        }
    }

    private fun advertise(item: Item) {
        val adv = adapter?.takeIf { it.isEnabled }?.bluetoothLeAdvertiser ?: return
        try {
            if (extendedSupported() && Build.VERSION.SDK_INT >= 26 &&
                (adapter?.leMaximumAdvertisingDataLength ?: 0) >= item.full.size + 4
            ) advertiseExtended(adv, item.full) else item.notice?.let { advertiseLegacy(adv, it) }
        } catch (e: Exception) {
            TLog.w(TAG, "advertise failed: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun data(payload: ByteArray): AdvertiseData = AdvertiseData.Builder()
        .setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
        .addManufacturerData(COMPANY_ID, payload).build()

    private fun advertiseExtended(adv: android.bluetooth.le.BluetoothLeAdvertiser, payload: ByteArray) {
        val ad = data(payload)
        val existing = currentSet
        if (existing != null) { existing.setAdvertisingData(ad); return }
        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(false).setConnectable(false).setScannable(false)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH).build()
        val cb = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
                currentSet = if (status == ADVERTISE_SUCCESS) advertisingSet else null
                if (status != ADVERTISE_SUCCESS) TLog.w(TAG, "extended advertising failed: $status")
            }
            override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) { currentSet = null }
        }
        setCallback = cb
        adv.startAdvertisingSet(params, ad, null, null, null, cb)
    }

    private fun advertiseLegacy(adv: android.bluetooth.le.BluetoothLeAdvertiser, notice: ByteArray) {
        legacyCallback?.let { try { adv.stopAdvertising(it) } catch (_: Exception) {} }
        val cb = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) { TLog.w(TAG, "legacy advertising failed: $errorCode") }
        }
        legacyCallback = cb
        adv.startAdvertising(
            AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(false).setTimeout(0).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).build(),
            data(notice), cb
        )
    }

    private fun stopAdvertising() {
        val adv = try { adapter?.bluetoothLeAdvertiser } catch (e: Exception) { null } ?: return
        try { setCallback?.let { adv.stopAdvertisingSet(it) } } catch (_: Exception) {}
        try { legacyCallback?.let { adv.stopAdvertising(it) } } catch (_: Exception) {}
        setCallback = null; legacyCallback = null; currentSet = null
    }

    // ── receiving ────────────────────────────────────────────────────────────

    fun startScan() {
        if (scanning || !canScan()) return
        val sc = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return
        fun filter(magic: String) = ScanFilter.Builder()
            .setManufacturerData(COMPANY_ID, magic.toByteArray(), byteArrayOf(-1, -1)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).apply {
            if (Build.VERSION.SDK_INT >= 26) setLegacy(false)          // also receive extended advertisements
        }.build()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) { result?.let { handle(it) } }
            override fun onBatchScanResults(results: MutableList<ScanResult>?) { results?.forEach { handle(it) } }
            override fun onScanFailed(errorCode: Int) { TLog.w(TAG, "scan failed: $errorCode"); scanning = false }
        }
        try {
            sc.startScan(listOf(filter("TX"), filter("TN")), settings, cb)
            scanCallback = cb; scanning = true
            TLog.i(TAG, "scanning for Thunder broadcasts (no connection)")
        } catch (e: Exception) { TLog.w(TAG, "startScan: ${e.message}") }
    }

    fun stopScan() {
        val cb = scanCallback ?: return
        try { adapter?.bluetoothLeScanner?.stopScan(cb) } catch (_: Exception) {}
        scanCallback = null; scanning = false
    }

    private fun handle(result: ScanResult) {
        val data = result.scanRecord?.getManufacturerSpecificData(COMPANY_ID) ?: return
        val now = System.currentTimeMillis()
        synchronized(seen) {
            if (now - windowStart > 1000) { windowStart = now; windowCount = 0 }
            if (++windowCount > MAX_FRAMES_PER_SEC) return                        // flood protection
            val key = ByteBuffer.wrap(sha256(data)).long
            if (seen.put(key, true) != null) return                               // already processed
        }
        val frame = BeaconCodec.decode(data) ?: return
        scope.launch { try { onFrame(frame) } catch (e: Exception) { TLog.w(TAG, "frame handler: ${e.message}") } }
    }
}
