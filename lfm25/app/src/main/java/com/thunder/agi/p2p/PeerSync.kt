package com.thunder.agi.p2p

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import com.thunder.agi.crypto.QuantumKeyManager
import com.thunder.agi.crypto.SecureChannel
import com.thunder.agi.ledger.ThunderABI
import com.thunder.agi.node.ThunderRuntime
import com.thunder.agi.util.TLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

private const val TAG = "ThunderAGI_P2P"
private const val SYNC_PORT = 47832
private const val MAX_FILE_BYTES = 2 * 1024 * 1024
private val SYNC_FILES = listOf("feedback_log.jsonl", "soft_prompt_cache.txt", "channel_finetune.jsonl")

/**
 * WiFi-Direct peer discovery + private data exchange between two Thunder phones.
 * The public API (state fields, register/unregister/discoverPeers/connectToPeer/disconnect) is unchanged.
 *
 * What changed under the hood:
 *  - The old "THUNDER_AGI_SYNC_V1" handshake had NO authentication and sent files in clear text. Now every session is a
 *    [SecureChannel]: both phones prove their identity key, the session key comes from a Kyber-768 exchange that is
 *    signed by both identities (no man-in-the-middle), and every frame is AES-256-GCM encrypted.
 *  - After the handshake each side sends its signed identity card (wallet address, name, Kyber key). The card is
 *    verified against the key that just authenticated the channel, then the peer's IP is handed to the ledger
 *    gossip so payments sync over the same WiFi-Direct link.
 *  - Received files are size-capped (2 MB) and merged exactly like before (jsonl: new lines only; txt: if larger).
 */
class PeerSync(private val context: Context) {

    data class SyncState(
        val isDiscovering: Boolean = false,
        val peers: List<WifiP2pDevice> = emptyList(),
        val isConnected: Boolean = false,
        val connectedDevice: String? = null,
        val transferProgress: String? = null,
        val lastSyncResult: String? = null,
        val error: String? = null
    )

    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state

    private val manager: WifiP2pManager? by lazy { context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager }
    private var channel: WifiP2pManager.Channel? = null
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverJob: Job? = null
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    try { manager?.requestPeers(channel) { peers -> _state.value = _state.value.copy(peers = peers.deviceList.toList()) } }
                    catch (e: SecurityException) { _state.value = _state.value.copy(error = "Missing permission for WiFi Direct") }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    try {
                        manager?.requestConnectionInfo(channel) { info ->
                            if (info != null && info.groupFormed) {
                                _state.value = _state.value.copy(isConnected = true)
                                if (info.isGroupOwner) startServer()
                                else info.groupOwnerAddress?.hostAddress?.let { ip -> scope.launch { connectToOwner(ip) } }
                            } else {
                                _state.value = _state.value.copy(isConnected = false, connectedDevice = null)
                            }
                        }
                    } catch (e: SecurityException) { _state.value = _state.value.copy(error = "Missing permission for WiFi Direct") }
                }
            }
        }
    }

    fun register() {
        if (registered) return
        registered = true
        if (!scope.isActive) scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        channel = manager?.initialize(context, context.mainLooper, null)
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)
    }

    fun unregister() {
        if (!registered) return
        registered = false
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        serverJob?.cancel()
        scope.cancel()
    }

    fun discoverPeers() {
        try {
            manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { _state.value = _state.value.copy(isDiscovering = true, error = null) }
                override fun onFailure(reason: Int) {
                    _state.value = _state.value.copy(isDiscovering = false, error = "Discovery failed (${reasonStr(reason)}). Make sure WiFi is on.")
                }
            })
        } catch (e: SecurityException) { _state.value = _state.value.copy(error = "Missing permission for WiFi Direct") }
    }

    fun connectToPeer(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        try {
            manager?.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { _state.value = _state.value.copy(connectedDevice = device.deviceName) }
                override fun onFailure(reason: Int) { _state.value = _state.value.copy(error = "Connect failed: ${reasonStr(reason)}") }
            })
        } catch (e: SecurityException) { _state.value = _state.value.copy(error = "Missing permission for WiFi Direct") }
    }

    fun disconnect() {
        manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { _state.value = SyncState() }
            override fun onFailure(reason: Int) {}
        })
    }

    // ── server (group owner) ─────────────────────────────────────────────────

    private fun startServer() {
        serverJob?.cancel()
        serverJob = scope.launch {
            var server: ServerSocket? = null
            try {
                server = ServerSocket(SYNC_PORT)
                server.soTimeout = 120_000
                while (isActive) {
                    _state.value = _state.value.copy(transferProgress = "Waiting for peer…")
                    try {
                        val client = server.accept()
                        launch { session(client, isServer = true) }
                    } catch (e: java.net.SocketTimeoutException) { break }
                }
            } catch (e: Exception) {
                if (isActive) _state.value = _state.value.copy(error = "Sync error: ${e.message}")
            } finally {
                try { server?.close() } catch (_: Exception) {}
                _state.value = _state.value.copy(transferProgress = null)
            }
        }
    }

    private suspend fun connectToOwner(ownerIp: String) {
        delay(1000)   // give the owner's server a moment to start
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(ownerIp, SYNC_PORT), 5000)
            session(socket, isServer = false)
        } catch (e: Exception) {
            TLog.w(TAG, "client error: ${e.message}")
            _state.value = _state.value.copy(error = "Could not connect: ${e.message}")
        }
    }

    // ── authenticated session ────────────────────────────────────────────────

    private suspend fun session(socket: Socket, isServer: Boolean) = withContext(Dispatchers.IO) {
        try {
            socket.soTimeout = 20_000
            val inp = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            val me = QuantumKeyManager.identity
            val ch = if (isServer) SecureChannel.server(inp, out, me) else SecureChannel.client(inp, out, me)
            _state.value = _state.value.copy(transferProgress = "Secure channel established ✓")

            // 1) identity card, verified against the key that authenticated the channel
            val runtime = ThunderRuntime.get(context)
            ch.send(ThunderABI.encodeIdent(runtime.wallet.identCard()))
            val peer = ThunderABI.decodeIdent(ch.receive(8192))                 // throws if address/key/signature don't match
            if (!peer.ecPublicKey.contentEquals(ch.peerPublicKey)) throw SecurityException("identity card does not match channel key")
            runtime.wallet.remember(peer)
            socket.inetAddress?.hostAddress?.let { host ->
                runtime.start()
                runtime.gossip.addPeer(host)                                     // payments now sync over this link too
            }

            // 2) training files (same merge rules as the original app)
            _state.value = _state.value.copy(transferProgress = "Exchanging encrypted data…")
            val filesDir = context.filesDir
            for (name in SYNC_FILES) {
                val f = File(filesDir, name)
                val bytes = if (f.exists() && f.length() <= MAX_FILE_BYTES) f.readBytes() else ByteArray(0)
                ch.send(name.toByteArray() + byteArrayOf(0) + bytes)
            }
            var received = 0
            repeat(SYNC_FILES.size) {
                val msg = ch.receive(MAX_FILE_BYTES + 256)
                val sep = msg.indexOf(0)
                if (sep <= 0) return@repeat
                val name = String(msg, 0, sep)
                val bytes = msg.copyOfRange(sep + 1, msg.size)
                if (name in SYNC_FILES && bytes.isNotEmpty() && merge(File(filesDir, name), name, bytes)) received++
            }
            try { socket.close() } catch (_: Exception) {}
            _state.value = _state.value.copy(
                transferProgress = null,
                lastSyncResult = "Secure sync complete with ${peer.name.ifBlank { "peer" }}. Received $received files. Restart app to apply."
            )
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
            _state.value = _state.value.copy(transferProgress = null, error = "Sync failed: ${e.message}")
        }
    }

    private fun merge(dest: File, name: String, bytes: ByteArray): Boolean {
        return when {
            name.endsWith(".jsonl") -> {
                val existing = if (dest.exists()) dest.readLines().toHashSet() else hashSetOf()
                val incoming = String(bytes, Charsets.UTF_8).lines().filter { it.isNotBlank() && it !in existing }
                if (incoming.isNotEmpty()) dest.appendText(incoming.joinToString("\n") + "\n")
                true
            }
            name.endsWith(".txt") -> {
                if (!dest.exists() || bytes.size > dest.length()) dest.writeBytes(bytes)
                true
            }
            else -> false
        }
    }

    private fun ByteArray.indexOf(b: Byte): Int { for (i in indices) if (this[i] == b) return i; return -1 }

    private fun reasonStr(r: Int) = when (r) {
        WifiP2pManager.P2P_UNSUPPORTED -> "WiFi Direct not supported"
        WifiP2pManager.BUSY -> "System busy"
        WifiP2pManager.ERROR -> "Internal error"
        else -> "Error $r"
    }
}
