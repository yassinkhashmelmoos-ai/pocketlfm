package com.thunder.agi.wallet

import android.content.Context
import com.thunder.agi.crypto.Identity
import com.thunder.agi.crypto.Kem
import com.thunder.agi.crypto.QuantumKeyManager
import com.thunder.agi.crypto.SecureStore
import com.thunder.agi.ledger.ThunderABI
import com.thunder.agi.ledger.WalletAddress
import com.thunder.agi.util.TLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "Wallet"
private const val PREFS = "thunder_wallet_v3"

/** Thrown when the stored Kyber key cannot be decrypted (e.g. after a restore onto another phone). */
class WalletKeyLostException : Exception(
    "The wallet keys on this phone cannot be opened. Funds of the old address cannot be moved from here."
)

/**
 * This phone's wallet identity.
 *
 *  - signing key  : hardware-backed, non-exportable (Android Keystore)
 *  - Kyber key    : generated once; the private half is stored encrypted by a Keystore AES key
 *  - address      : derived from both public keys (never stored, so it can never drift out of sync with the keys)
 *
 * The Kyber key is NEVER silently regenerated: that would change the address and orphan the funds
 * (the old code did exactly that on any decryption error).
 */
class WalletManager(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val secure = SecureStore(app)
    private val b64e = Base64.getEncoder()
    private val b64d = Base64.getDecoder()

    val identity: Identity get() = QuantumKeyManager.identity

    @Volatile private var kyberCache: Kem.KeyPair? = null

    @Synchronized
    fun kyber(): Kem.KeyPair {
        kyberCache?.let { return it }
        val pub = prefs.getString("kyber_pub", null)
        val priv = prefs.getString("kyber_priv", null)
        val kp = if (pub != null && priv != null) {
            val privBytes = try { secure.decrypt(b64d.decode(priv)) } catch (e: Exception) {
                TLog.e(TAG, "cannot decrypt Kyber key: ${e.javaClass.simpleName}")
                throw WalletKeyLostException()
            }
            Kem.KeyPair(b64d.decode(pub), privBytes)
        } else {
            val k = Kem.generate()
            val ok = prefs.edit()
                .putString("kyber_pub", b64e.encodeToString(k.publicKey))
                .putString("kyber_priv", b64e.encodeToString(secure.encrypt(k.privateKey)))
                .commit()
            check(ok) { "could not persist wallet keys" }
            k
        }
        kyberCache = kp
        return kp
    }

    val address: String by lazy { WalletAddress.derive(identity.publicKey, kyber().publicKey) }

    /** True if wallet keys are usable on this device. */
    fun isUsable(): Boolean = try { kyber(); address.isNotEmpty() } catch (e: Exception) { false }

    var displayName: String
        get() = prefs.getString("name", "") ?: ""
        set(v) { prefs.edit().putString("name", clampName(v)).apply() }

    private fun clampName(v: String): String {
        var t = v.trim()
        while (t.isNotEmpty() && t.toByteArray(Charsets.UTF_8).size > ThunderABI.MAX_NAME_BYTES) t = t.dropLast(1)
        return t
    }

    @Volatile private var cardCache: Pair<String, ThunderABI.Ident>? = null

    /** Signed identity card sent to peers (address + name + Kyber key); cached until the name changes. */
    fun identCard(): ThunderABI.Ident {
        val name = displayName
        cardCache?.let { if (it.first == name) return it.second }
        val k = kyber()
        val sig = identity.sign(ThunderABI.identMessage(address, name, k.publicKey))
        return ThunderABI.Ident(address, name, k.publicKey, identity.publicKey, sig).also { cardCache = name to it }
    }

    // ── address book (names learned from verified peer identity cards) ───────

    private val book = ConcurrentHashMap<String, String>()
    private val bookSize = MutableStateFlow(0)
    val knownPeers: StateFlow<Int> = bookSize

    init {
        prefs.getString("book", "")?.lineSequence()?.forEach { line ->
            val i = line.indexOf('=')
            if (i > 0) book[line.substring(0, i)] = line.substring(i + 1)
        }
        bookSize.value = book.size
    }

    fun remember(ident: ThunderABI.Ident) {
        if (ident.address == address) return
        if (book.put(ident.address, ident.name) != ident.name) {
            bookSize.value = book.size
            prefs.edit().putString("book", book.entries.joinToString("\n") { "${it.key}=${it.value.replace('\n', ' ')}" }).apply()
        }
    }

    fun resolveName(address: String): String =
        if (address == this.address) "You" else book[address]?.takeIf { it.isNotBlank() } ?: WalletAddress.shortForm(address)

    fun nameOrNull(address: String): String? = book[address]?.takeIf { it.isNotBlank() }
    fun peers(): Map<String, String> = book.toMap()
}
