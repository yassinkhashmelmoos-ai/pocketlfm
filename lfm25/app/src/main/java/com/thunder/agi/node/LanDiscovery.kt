package com.thunder.agi.node

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.thunder.agi.util.TLog
import java.security.SecureRandom

private const val TAG = "LanDiscovery"

/**
 * Zero-setup discovery on the same WiFi network or phone hotspot (mDNS / Android NSD): every Thunder phone
 * advertises "_thunder._tcp" and every phone looks for it, then hands the address to the gossip layer.
 * No server, no account, no WiFi-Direct pairing dialog. Works whenever two phones share a network
 * (home WiFi, a phone's hotspot, a shop's router) even without internet.
 */
class LanDiscovery(
    private val context: Context,
    private val onPeer: (host: String, port: Int) -> Unit
) {
    private companion object { const val TYPE = "_thunder._tcp." }

    private val nsd: NsdManager? = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val myName = "Thunder-" + SecureRandom().nextInt(0xFFFFFF).toString(16)
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val toResolve = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    @Volatile private var running = false

    @Synchronized
    fun start(port: Int) {
        val m = nsd ?: return
        if (running || port <= 0) return
        running = true
        try {
            val info = NsdServiceInfo().apply { serviceName = myName; serviceType = TYPE; this.port = port }
            val reg = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo?) { TLog.i(TAG, "advertising ${i?.serviceName} on $port") }
                override fun onRegistrationFailed(i: NsdServiceInfo?, code: Int) { TLog.w(TAG, "registration failed $code") }
                override fun onServiceUnregistered(i: NsdServiceInfo?) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo?, code: Int) {}
            }
            registration = reg
            m.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg)

            val disc = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(t: String?) {}
                override fun onServiceFound(i: NsdServiceInfo?) {
                    if (i == null || i.serviceName == myName) return
                    synchronized(toResolve) { toResolve.addLast(i) }
                    resolveNext()
                }
                override fun onServiceLost(i: NsdServiceInfo?) {}
                override fun onDiscoveryStopped(t: String?) {}
                override fun onStartDiscoveryFailed(t: String?, code: Int) { TLog.w(TAG, "discovery failed $code") }
                override fun onStopDiscoveryFailed(t: String?, code: Int) {}
            }
            discovery = disc
            m.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, disc)
        } catch (e: Exception) {
            TLog.w(TAG, "start: ${e.message}")
            running = false
        }
    }

    @Synchronized
    fun stop() {
        val m = nsd ?: return
        running = false
        try { registration?.let { m.unregisterService(it) } } catch (_: Exception) {}
        try { discovery?.let { m.stopServiceDiscovery(it) } } catch (_: Exception) {}
        registration = null; discovery = null
        synchronized(toResolve) { toResolve.clear(); resolving = false }
    }

    /** NSD can resolve only one service at a time, so resolve sequentially. */
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val m = nsd ?: return
        val next = synchronized(toResolve) {
            if (resolving) return
            val n = toResolve.removeFirstOrNull() ?: return
            resolving = true
            n
        }
        try {
            m.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo?, code: Int) { done() }
                override fun onServiceResolved(i: NsdServiceInfo?) {
                    val host = i?.host?.hostAddress
                    if (host != null && i.port > 0) onPeer(host, i.port)
                    done()
                }
                private fun done() { synchronized(toResolve) { resolving = false }; resolveNext() }
            })
        } catch (e: Exception) {
            synchronized(toResolve) { resolving = false }
            TLog.w(TAG, "resolve: ${e.message}")
        }
    }
}
