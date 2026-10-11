package com.thunder.agi.p2p

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.*
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

private const val TAG = "ThunderAGI_P2P"
private const val SYNC_PORT = 47832
private const val SYNC_MAGIC = "THUNDER_AGI_SYNC_V1"

/**
 * PeerSync — WiFi Direct peer-to-peer fine-tune data exchange.
 *
 * When two devices running Thunder AGI are nearby and on the same WiFi
 * or connected via hotspot, they can exchange:
 * - feedback_log.jsonl (thumbs up/down training data)
 * - soft_prompt_cache.txt (nightly training results)
 * - channel_finetune.jsonl (WhatsApp channel data)
 *
 * This is like Feem — works without internet, just local network.
 * Each device learns from the other's interactions.
 *
 * REQUIREMENTS: ACCESS_FINE_LOCATION, CHANGE_WIFI_STATE, ACCESS_WIFI_STATE permissions.
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

    private val manager: WifiP2pManager? by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }
    private var channel: WifiP2pManager.Channel? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverJob: Job? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    manager?.requestPeers(channel) { peers ->
                        _state.value = _state.value.copy(
                            peers = peers.deviceList.toList()
                        )
                        Log.i(TAG, "Found ${peers.deviceList.size} peers")
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = if (Build.VERSION.SDK_INT >= 29)
                        intent.getParcelableExtra<android.net.NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    else
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)

                    if (networkInfo?.isConnected == true) {
                        manager?.requestConnectionInfo(channel) { info ->
                            if (info.groupFormed) {
                                _state.value = _state.value.copy(isConnected = true)
                                if (info.isGroupOwner) startServer()
                                else scope.launch { connectToOwner(info.groupOwnerAddress?.hostAddress ?: return@launch) }
                            }
                        }
                    } else {
                        _state.value = _state.value.copy(isConnected = false, connectedDevice = null)
                    }
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {}
            }
        }
    }

    fun register() {
        channel = manager?.initialize(context, context.mainLooper, null)
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        context.registerReceiver(receiver, filter)
    }

    fun unregister() {
        try { context.unregisterReceiver(receiver) } catch (e: Exception) {}
        scope.cancel()
    }

    fun discoverPeers() {
        manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _state.value = _state.value.copy(isDiscovering = true, error = null)
                Log.i(TAG, "Discovery started")
            }
            override fun onFailure(reason: Int) {
                _state.value = _state.value.copy(
                    isDiscovering = false,
                    error = "Discovery failed (${reasonStr(reason)}). Make sure WiFi is on."
                )
            }
        })
    }

    fun connectToPeer(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        manager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _state.value = _state.value.copy(connectedDevice = device.deviceName)
                Log.i(TAG, "Connecting to ${device.deviceName}")
            }
            override fun onFailure(reason: Int) {
                _state.value = _state.value.copy(error = "Connect failed: ${reasonStr(reason)}")
            }
        })
    }

    fun disconnect() {
        manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { _state.value = SyncState() }
            override fun onFailure(reason: Int) {}
        })
    }

    // ── Server (group owner) ──────────────────────────────────────────────────

    private fun startServer() {
        serverJob?.cancel()
        serverJob = scope.launch {
            var server: ServerSocket? = null
            try {
                server = ServerSocket(SYNC_PORT)
                server.soTimeout = 120_000   // stop waiting after 2 minutes idle
                Log.i(TAG, "Server listening on $SYNC_PORT")

                // Accept multiple peers one after another without restarting the app
                while (isActive) {
                    _state.value = _state.value.copy(transferProgress = "Waiting for peer…")
                    try {
                        val client = server.accept()
                        handleConnection(client, isServer = true)
                    } catch (e: java.net.SocketTimeoutException) {
                        Log.i(TAG, "Server timeout — no more peers expected")
                        break
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Server error: ${e.message}")
                    _state.value = _state.value.copy(error = "Sync error: ${e.message}")
                }
            } finally {
                server?.close()
                _state.value = _state.value.copy(transferProgress = null)
            }
        }
    }

    // ── Client (connects to group owner) ─────────────────────────────────────

    private suspend fun connectToOwner(ownerIp: String) {
        delay(1000) // wait for server to be ready
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(ownerIp, SYNC_PORT), 5000)
            handleConnection(socket, isServer = false)
        } catch (e: Exception) {
            Log.e(TAG, "Client error: ${e.message}")
            _state.value = _state.value.copy(error = "Could not connect: ${e.message}")
        }
    }

    // ── Data exchange ─────────────────────────────────────────────────────────

    private suspend fun handleConnection(socket: Socket, isServer: Boolean) = withContext(Dispatchers.IO) {
        try {
            val out = DataOutputStream(socket.getOutputStream())
            val inp = DataInputStream(socket.getInputStream())

            // Handshake
            out.writeUTF(SYNC_MAGIC)
            val magic = inp.readUTF()
            if (magic != SYNC_MAGIC) { socket.close(); return@withContext }

            _state.value = _state.value.copy(transferProgress = "Exchanging data…")

            // Both sides send their data files
            val filesToSend = listOf("feedback_log.jsonl", "soft_prompt_cache.txt", "channel_finetune.jsonl")
            val filesDir = context.filesDir

            // Send our files
            out.writeInt(filesToSend.size)
            for (name in filesToSend) {
                val file = File(filesDir, name)
                out.writeUTF(name)
                if (file.exists()) {
                    val bytes = file.readBytes()
                    out.writeInt(bytes.size)
                    out.write(bytes)
                } else {
                    out.writeInt(0)
                }
            }
            out.flush()

            // Receive peer's files and merge intelligently
            val count = inp.readInt()
            var received = 0
            for (i in 0 until count) {
                val name = inp.readUTF()
                val size = inp.readInt()
                if (size > 0) {
                    val bytes = ByteArray(size)
                    inp.readFully(bytes)
                    val dest = File(filesDir, name)  // same name, no peer_ prefix

                    when {
                        // JSONL: append only new lines, skip duplicates
                        name.endsWith(".jsonl") -> {
                            val existing = if (dest.exists())
                                dest.readLines().toHashSet()
                            else
                                hashSetOf()
                            val incoming = String(bytes, Charsets.UTF_8)
                                .lines()
                                .filter { it.isNotBlank() && it !in existing }
                            if (incoming.isNotEmpty()) {
                                dest.appendText(incoming.joinToString("\n") + "\n")
                                Log.i(TAG, "Merged ${incoming.size} new lines into $name")
                            }
                        }
                        // TXT: overwrite only if peer's version is newer/larger
                        name.endsWith(".txt") -> {
                            if (!dest.exists() || bytes.size > dest.length()) {
                                dest.writeBytes(bytes)
                                Log.i(TAG, "Updated $name from peer")
                            }
                        }
                        // Anything else: write directly
                        else -> {
                            dest.writeBytes(bytes)
                            Log.i(TAG, "Received $name from peer")
                        }
                    }
                    received++
                }
            }

            socket.close()
            _state.value = _state.value.copy(
                transferProgress = null,
                lastSyncResult = "Sync complete! Received $received files from peer. Restart app to apply."
            )
            Log.i(TAG, "Sync complete")
        } catch (e: Exception) {
            socket.close()
            _state.value = _state.value.copy(
                transferProgress = null,
                error = "Sync failed: ${e.message}"
            )
        }
    }

    private fun reasonStr(r: Int) = when(r) {
        WifiP2pManager.P2P_UNSUPPORTED -> "WiFi Direct not supported"
        WifiP2pManager.BUSY -> "System busy"
        WifiP2pManager.ERROR -> "Internal error"
        else -> "Error $r"
    }
}
