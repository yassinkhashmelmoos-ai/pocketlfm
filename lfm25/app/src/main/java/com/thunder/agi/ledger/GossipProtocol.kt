package com.thunder.agi.ledger

import com.thunder.agi.ledger.ThunderABI.OP_AUX
import com.thunder.agi.ledger.ThunderABI.OP_FRONTIER
import com.thunder.agi.ledger.ThunderABI.OP_HELLO
import com.thunder.agi.ledger.ThunderABI.OP_IDENT
import com.thunder.agi.ledger.ThunderABI.OP_REQUEST
import com.thunder.agi.ledger.ThunderABI.OP_TXS
import com.thunder.agi.util.TLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
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
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "Gossip"

/**
 * Makes every phone a full ledger node.
 *
 * Fixed vs. the old version:
 *  - NO DEADLOCK: every message has exactly one writer at a time. The side that connects (initiator)
 *    and the side that accepts (responder) follow mirrored, strictly alternating scripts:
 *        I: HELLO > | < HELLO  | IDENT > | < IDENT | FRONTIER > | < FRONTIER | REQUEST > | < REQUEST | TXS > | < TXS | AUX > | < AUX
 *  - SYNC BY SENDER CHAIN, not "most recent 500 ids": the frontier (sender, last nonce, last txid) tells each
 *    side exactly which suffix of which chain the other lacks, so a new phone can fetch the whole history,
 *    in order, over several rounds. Same nonce but different txid in a frontier = fork check.
 *  - PUBLIC DATA, UNTRUSTED PEERS: nothing here needs trust; every transaction is verified by LedgerEngine.
 *  - BOUNDED: hard caps on frame size, list sizes, concurrent sessions (6 total, 2 per IP), socket timeouts.
 */
class GossipProtocol(
    private val engine: LedgerEngine,
    private val localIdent: () -> ThunderABI.Ident,
    private val onPeerIdent: (peerHost: String, ident: ThunderABI.Ident) -> Unit = { _, _ -> },
    private val onAccepted: (count: Int) -> Unit = {},
    private val requestedPort: Int = DEFAULT_PORT
) {
    companion object {
        const val DEFAULT_PORT = 47834
        private const val SOCKET_TIMEOUT_MS = 15_000
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val PERIODIC_MS = 30_000L
        private const val MAX_ROUNDS = 30
        private const val CHAIN_PAGE = 200
    }

    var boundPort: Int = 0
        private set

    private val peers = ConcurrentHashMap<String, Int>()            // host -> gossip port
    private val failures = ConcurrentHashMap<String, Int>()
    private val busy = ConcurrentHashMap.newKeySet<String>()
    private val ipActive = ConcurrentHashMap<String, AtomicInteger>()
    private val inboundSlots = Semaphore(6)
    private val peerCountFlow = MutableStateFlow(0)
    val peerCount: StateFlow<Int> = peerCountFlow

    private var scope: CoroutineScope? = null
    private var server: ServerSocket? = null
    private val kicks = Channel<Unit>(Channel.CONFLATED)
    private val rng = SecureRandom()

    // ── lifecycle ────────────────────────────────────────────────────────────

    fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(requestedPort))
            server = ss
            boundPort = ss.localPort
            s.launch { acceptLoop(ss) }
        } catch (e: Exception) {
            TLog.w(TAG, "cannot listen on $requestedPort: ${e.message} (outgoing sync still works)")
        }
        s.launch { for (k in kicks) { delay(300); syncAll() } }
        s.launch { while (isActive) { delay(PERIODIC_MS); syncAll() } }
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
        scope?.cancel()
        scope = null
    }

    fun addPeer(host: String, port: Int = DEFAULT_PORT) {
        if (host.isBlank()) return
        peers[host] = port
        failures.remove(host)
        peerCountFlow.value = peers.size
        scope?.launch { syncWith(host, port) }
    }

    fun removePeer(host: String) { peers.remove(host); peerCountFlow.value = peers.size }
    fun knownPeers(): Map<String, Int> = peers.toMap()

    /** Ask for a sync with every known peer soon (debounced). Call after creating/receiving a transaction. */
    fun kick() { kicks.trySend(Unit) }

    suspend fun syncAll() {
        for ((host, port) in peers.entries.toList()) syncWith(host, port)
    }

    // ── initiator ────────────────────────────────────────────────────────────

    /** Returns the number of transactions newly accepted from this peer (all rounds). */
    suspend fun syncWith(host: String, port: Int = DEFAULT_PORT): Int {
        if (!busy.add("$host:$port")) return 0
        var total = 0
        try {
            var rounds = 0
            while (rounds++ < MAX_ROUNDS) {
                val got = withContext(Dispatchers.IO) { initiatorRound(host, port) }
                total += got
                failures.remove(host)
                if (got == 0) break                               // nothing new this round: caught up
            }
        } catch (e: Exception) {
            TLog.d(TAG, "sync $host:$port failed: ${e.javaClass.simpleName} ${e.message}")
            val n = (failures[host] ?: 0) + 1
            failures[host] = n
            if (n >= 5) removePeer(host)
        } finally {
            busy.remove("$host:$port")
        }
        if (total > 0) onAccepted(total)
        return total
    }

    private suspend fun initiatorRound(host: String, port: Int): Int {
        Socket().use { sock ->
            sock.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            sock.soTimeout = SOCKET_TIMEOUT_MS
            val out = DataOutputStream(BufferedOutputStream(sock.getOutputStream()))
            val inp = DataInputStream(BufferedInputStream(sock.getInputStream()))
            val myNonce = ByteArray(8).also { rng.nextBytes(it) }

            ThunderABI.write(out, OP_HELLO, ThunderABI.encodeHello(ThunderABI.Hello(ThunderABI.VERSION, myNonce, boundPort)))
            val hello = ThunderABI.decodeHello(ThunderABI.read(inp, OP_HELLO))
            if (hello.nodeNonce.contentEquals(myNonce)) throw ThunderABI.ProtocolException("connected to myself")

            ThunderABI.write(out, OP_IDENT, ThunderABI.encodeIdent(localIdent()))
            onPeerIdent(host, ThunderABI.decodeIdent(ThunderABI.read(inp, OP_IDENT)))

            ThunderABI.write(out, OP_FRONTIER, ThunderABI.encodeFrontiers(engine.frontiers(ThunderABI.MAX_FRONTIERS)))
            val theirFrontier = ThunderABI.decodeFrontiers(ThunderABI.read(inp, OP_FRONTIER))

            ThunderABI.write(out, OP_REQUEST, ThunderABI.encodeRequests(computeRequests(theirFrontier)))
            val theirRequests = ThunderABI.decodeRequests(ThunderABI.read(inp, OP_REQUEST))

            ThunderABI.write(out, OP_TXS, ThunderABI.encodeTxs(serve(theirRequests)))
            val incoming = ThunderABI.decodeTxs(ThunderABI.read(inp, OP_TXS))
            val result = engine.receiveMany(incoming)

            ThunderABI.write(out, OP_AUX, ThunderABI.encodeAux(engine.frauds()))
            for ((a, b) in ThunderABI.decodeAux(ThunderABI.read(inp, OP_AUX))) engine.receiveFraudProof(a, b)
            return result.accepted
        }
    }

    // ── responder ────────────────────────────────────────────────────────────

    private suspend fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val client = try { ss.accept() } catch (e: Exception) { break }
            val ip = client.inetAddress?.hostAddress ?: "?"
            val active = ipActive.getOrPut(ip) { AtomicInteger(0) }
            if (!inboundSlots.tryAcquire()) {                       // too many sessions overall
                try { client.close() } catch (_: Exception) {}
                continue
            }
            if (active.incrementAndGet() > 2) {                     // too many from one address
                active.decrementAndGet()
                inboundSlots.release()
                try { client.close() } catch (_: Exception) {}
                continue
            }
            scope?.launch {
                try { client.soTimeout = SOCKET_TIMEOUT_MS; responderSession(client, ip) }
                catch (e: Exception) { TLog.d(TAG, "inbound $ip: ${e.javaClass.simpleName} ${e.message}") }
                finally {
                    active.decrementAndGet(); inboundSlots.release()
                    try { client.close() } catch (_: Exception) {}
                }
            } ?: run { active.decrementAndGet(); inboundSlots.release(); client.close() }
        }
    }

    private suspend fun responderSession(sock: Socket, ip: String) {
        val out = DataOutputStream(BufferedOutputStream(sock.getOutputStream()))
        val inp = DataInputStream(BufferedInputStream(sock.getInputStream()))
        val myNonce = ByteArray(8).also { rng.nextBytes(it) }

        val hello = ThunderABI.decodeHello(ThunderABI.read(inp, OP_HELLO))
        ThunderABI.write(out, OP_HELLO, ThunderABI.encodeHello(ThunderABI.Hello(ThunderABI.VERSION, myNonce, boundPort)))
        if (hello.nodeNonce.contentEquals(myNonce)) return

        val ident = ThunderABI.decodeIdent(ThunderABI.read(inp, OP_IDENT))
        ThunderABI.write(out, OP_IDENT, ThunderABI.encodeIdent(localIdent()))
        onPeerIdent(ip, ident)
        if (hello.listenPort != 0 && peers.putIfAbsent(ip, hello.listenPort) == null) peerCountFlow.value = peers.size

        val theirFrontier = ThunderABI.decodeFrontiers(ThunderABI.read(inp, OP_FRONTIER))
        ThunderABI.write(out, OP_FRONTIER, ThunderABI.encodeFrontiers(engine.frontiers(ThunderABI.MAX_FRONTIERS)))

        val theirRequests = ThunderABI.decodeRequests(ThunderABI.read(inp, OP_REQUEST))
        ThunderABI.write(out, OP_REQUEST, ThunderABI.encodeRequests(computeRequests(theirFrontier)))

        val incoming = ThunderABI.decodeTxs(ThunderABI.read(inp, OP_TXS))
        ThunderABI.write(out, OP_TXS, ThunderABI.encodeTxs(serve(theirRequests)))
        val result = engine.receiveMany(incoming)

        val frauds = ThunderABI.decodeAux(ThunderABI.read(inp, OP_AUX))
        ThunderABI.write(out, OP_AUX, ThunderABI.encodeAux(engine.frauds()))
        for ((a, b) in frauds) engine.receiveFraudProof(a, b)
        if (result.accepted > 0) onAccepted(result.accepted)
    }

    // ── shared logic ─────────────────────────────────────────────────────────

    /** What do I need from the peer, given its chain heads? */
    private suspend fun computeRequests(theirs: List<Frontier>): List<Pair<String, Long>> {
        val req = ArrayList<Pair<String, Long>>()
        for (f in theirs) {
            if (req.size >= ThunderABI.MAX_REQUESTS) break
            if (engine.isFrozen(f.address)) continue
            val local = engine.lastFrom(f.address)
            when {
                local == null -> req.add(f.address to 1L)
                local.nonce < f.nonce -> req.add(f.address to local.nonce + 1)
                local.nonce == f.nonce && !local.txid.startsWith(f.lastTxId.take(16)) -> req.add(f.address to f.nonce) // fork check
            }
        }
        return req
    }

    /** Answer a REQUEST with chain suffixes, capped. */
    private suspend fun serve(requests: List<Pair<String, Long>>): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        for ((addr, from) in requests) {
            if (out.size >= ThunderABI.MAX_TXS) break
            if (!WalletAddress.isValid(addr)) continue
            for (tx in engine.chain(addr, from, minOf(CHAIN_PAGE, ThunderABI.MAX_TXS - out.size))) out.add(tx.raw)
        }
        return out
    }
}
