package com.thunder.agi.ledger

import com.thunder.agi.util.hexToBytes
import com.thunder.agi.util.toHex

/**
 * Who may create money (GENESIS transactions).
 *
 * Money enters this system when real money is paid in (a Bank of Khartoum transfer). That step cannot be
 * verified by a peer-to-peer network, so it needs ONE trusted issuer; everyone else can only move money
 * that the issuer has created. Every node rejects a GENESIS transaction unless it is signed by a key listed here.
 *
 * SETUP (once): run the app on the issuer phone, open Wallet -> Issuer panel, copy "Issuer key", paste it
 * below, rebuild and release. Until a key is listed, NOBODY can mint (fail-closed).
 */
object MintAuthority {
    /** Compressed P-256 public keys (66 hex chars each) allowed to sign GENESIS transactions. */
    val AUTHORITY_KEYS_HEX: List<String> = listOf(
        // "02ab...66 hex chars...",
    )

    /** Debug builds only: lets any key mint so you can test end-to-end. NEVER true in release. */
    @Volatile var devAllowSelfMint: Boolean = false

    private val keys: List<ByteArray> get() = AUTHORITY_KEYS_HEX.mapNotNull {
        try { it.trim().hexToBytes().takeIf { b -> b.size == 33 } } catch (e: IllegalArgumentException) { null }
    }

    private val testKeys = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
    internal fun addAuthorityForTest(k: ByteArray) { testKeys.add(k) }
    internal fun clearForTest() { testKeys.clear(); devAllowSelfMint = false }

    fun canMint(ecPub33: ByteArray): Boolean =
        devAllowSelfMint || keys.any { it.contentEquals(ecPub33) } || testKeys.any { it.contentEquals(ecPub33) }

    fun hex(ecPub33: ByteArray) = ecPub33.toHex()
}
