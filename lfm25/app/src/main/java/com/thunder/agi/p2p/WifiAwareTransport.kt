package com.thunder.agi.p2p

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.Handler
import android.os.Looper
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

private const val TAG = "WifiAware"

/**
 * WiFi Aware (NAN) = the "unconnected WiFi signal" idea: phones discover each other and exchange a short
 * message (<= 255 bytes) at the WiFi radio level with NO access point, NO password and NO IP connection.
 * The compact signed transaction (<= 229 bytes with our frame header) fits in that one message.
 *
 * The sender PUBLISHES a service whose service-specific-info is the frame; any nearby phone that SUBSCRIBES
 * receives it in onServiceDiscovered(). Neither side can see the other's traffic, internet or files, because
 * no data link is ever created. The ledger verifies the signature, so senders are not trusted.
 *
 * Requires Android 8.0+ and hardware support (check [isSupported]). Needs NEARBY_WIFI_DEVICES (Android 13+) or
 * ACCESS_FINE_LOCATION (<= 12). Range is typically 30-100 m indoors.
 */
class WifiAwareTransport(
    private val context: Context,
    private val onFrame: suspend (BeaconCodec.Frame) -> Unit
) {
    companion object {
        const val SERVICE = "thunder-tx-v1"
        private const val PUBLISH_MS = 8_000L
        private const val LIFETIME_MS = 40_000L
        private const val MAX_QUEUE = 12

        fun isSupported(ctx: Context): Boolean =
            Build.VERSION.SDK_INT >= 26 && ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
    }

    private class Item(val raw: ByteArray, val frame: ByteArray, val expiresAt: Long)

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val handler = Handler(Looper.getMainLooper())
    private val mgr: WifiAwareManager? = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
    private var session: WifiAwareSession? = null
    private var subscription: SubscribeDiscoverySession? = null
    private var publication: PublishDiscoverySession? = null
    private var stateReceiver: BroadcastReceiver? = null
    @Volatile private var attaching = false
    private var pubJob: Job? = null
    private val queue = ArrayDeque<Item>()
    private val seen = object : LinkedHashMap<Long, Boolean>(128, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, Boolean>?) = size > 300
    }

    private fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    fun hasPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.NEARBY_WIFI_DEVICES)
        else granted(Manifest.permission.ACCESS_FINE_LOCATION)

    val isActive: Boolean get() = session != null

    // ── lifecycle ────────────────────────────────────────────────────────────

    fun start() {
        if (mgr == null || !hasPermission()) return
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        if (stateReceiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) { if (mgr.isAvailable) attach() else teardown() }
            }
            stateReceiver = r
            val f = IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else context.registerReceiver(r, f)
        }
        attach()
    }

    fun stop() {
        stateReceiver?.let { try { context.unregisterReceiver(it) } catch (_: Exception) {} }
        stateReceiver = null
        teardown()
        scope.cancel()
    }

    private fun attach() {
        val m = mgr ?: return
        if (!m.isAvailable || session != null || attaching) return
        attaching = true
        try {
            m.attach(object : AttachCallback() {
                override fun onAttached(s: WifiAwareSession?) { attaching = false; session = s; subscribe(); if (hasQueued()) ensurePublishing() }
                override fun onAttachFailed() { attaching = false; TLog.w(TAG, "attach failed") }
            }, handler)
        } catch (e: Exception) { attaching = false; TLog.w(TAG, "attach: ${e.message}") }
    }

    private fun teardown() {
        pubJob?.cancel(); pubJob = null
        try { publication?.close() } catch (_: Exception) {}
        try { subscription?.close() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        publication = null; subscription = null; session = null; attaching = false
    }

    // ── receive ──────────────────────────────────────────────────────────────

    private fun subscribe() {
        val s = session ?: return
        try {
            s.subscribe(
                SubscribeConfig.Builder().setServiceName(SERVICE).setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE).build(),
                object : DiscoverySessionCallback() {
                    override fun onSubscribeStarted(sub: SubscribeDiscoverySession) { subscription = sub; TLog.i(TAG, "subscribed (no connection)") }
                    override fun onServiceDiscovered(peerHandle: PeerHandle?, serviceSpecificInfo: ByteArray?, matchFilter: MutableList<ByteArray>?) {
                        serviceSpecificInfo?.let { handle(it) }
                    }
                    override fun onSessionTerminated() { subscription = null }
                }, handler
            )
        } catch (e: Exception) { TLog.w(TAG, "subscribe: ${e.message}") }
    }

    private fun handle(info: ByteArray) {
        val key = ByteBuffer.wrap(sha256(info)).long
        synchronized(seen) { if (seen.put(key, true) != null) return }
        val frame = BeaconCodec.decode(info) ?: return
        scope.launch { try { onFrame(frame) } catch (e: Exception) { TLog.w(TAG, "frame handler: ${e.message}") } }
    }

    // ── send ─────────────────────────────────────────────────────────────────

    fun enqueue(raw: ByteArray, hops: Int = 0) {
        if (mgr == null || !hasPermission() || hops > BeaconCodec.MAX_HOPS) return
        val frame = try { BeaconCodec.encodeTx(raw, hops) } catch (e: IllegalArgumentException) { return }
        synchronized(queue) {
            queue.removeAll { it.raw.contentEquals(raw) }
            queue.addLast(Item(raw, frame, System.currentTimeMillis() + LIFETIME_MS))
            while (queue.size > MAX_QUEUE) queue.removeFirst()
        }
        if (session == null) attach() else ensurePublishing()
    }

    private fun hasQueued() = synchronized(queue) { queue.isNotEmpty() }

    private fun maxInfoLength(): Int =
        if (Build.VERSION.SDK_INT >= 29) mgr?.characteristics?.maxServiceSpecificInfoLength ?: 255 else 255

    private fun ensurePublishing() {
        if (pubJob?.isActive == true) return
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        pubJob = scope.launch {
            while (isActive) {
                val item = synchronized(queue) {
                    val now = System.currentTimeMillis()
                    queue.removeAll { it.expiresAt < now }
                    queue.removeFirstOrNull()?.also { queue.addLast(it) }
                } ?: break
                val s = session ?: break
                if (item.frame.size <= maxInfoLength()) {
                    handler.post { publish(s, item.frame) }
                    delay(PUBLISH_MS)
                    handler.post { try { publication?.close() } catch (_: Exception) {}; publication = null }
                    delay(200)
                }
            }
        }
    }

    private fun publish(s: WifiAwareSession, frame: ByteArray) {
        try {
            try { publication?.close() } catch (_: Exception) {}
            s.publish(
                PublishConfig.Builder().setServiceName(SERVICE).setServiceSpecificInfo(frame)
                    .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED).build(),
                object : DiscoverySessionCallback() {
                    override fun onPublishStarted(pub: PublishDiscoverySession) { publication = pub }
                    override fun onSessionTerminated() { publication = null }
                }, handler
            )
        } catch (e: Exception) { TLog.w(TAG, "publish: ${e.message}") }
    }
}
