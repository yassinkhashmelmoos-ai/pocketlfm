package com.thunder.agi.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import com.thunder.agi.util.TLog
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val TAG = "KeyManager"
private const val ANDROID_KEYSTORE = "AndroidKeyStore"

/**
 * The device's signing identity: an ECDSA P-256 key generated INSIDE the Android Keystore
 * (StrongBox secure chip when the phone has one, otherwise the TEE). The private key can never be
 * exported, not even by root, so a stolen backup or a cloned APK cannot spend the money.
 *
 * Honest note: ECDSA is not quantum-resistant. Post-quantum protection in this app covers CONFIDENTIALITY
 * (Kyber-768 + AES-256-GCM channel) and the address commits to a Kyber key. For post-quantum SIGNATURES a
 * hybrid scheme (e.g. ML-DSA) would have to be added; see AUDIT.md.
 */
object QuantumKeyManager {
    private const val IDENTITY_ALIAS = "thunder_identity_v3"

    val identity: Identity by lazy { KeystoreIdentity(IDENTITY_ALIAS) }

    /** True if the signing key lives in secure hardware (TEE or StrongBox). */
    fun isHardwareBacked(): Boolean = try {
        val priv = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.getKey(IDENTITY_ALIAS, null) as? PrivateKey
        if (priv == null) false else {
            val info = KeyFactory.getInstance(priv.algorithm, ANDROID_KEYSTORE).getKeySpec(priv, KeyInfo::class.java)
            if (Build.VERSION.SDK_INT >= 31) info.securityLevel != KeyProperties.SECURITY_LEVEL_SOFTWARE
            else @Suppress("DEPRECATION") info.isInsideSecureHardware
        }
    } catch (e: Exception) { false }
}

private class KeystoreIdentity(private val alias: String) : Identity {
    private val keyPair: KeyPair by lazy { loadOrCreate() }
    override val publicKey: ByteArray by lazy { EcCodec.compress(keyPair.public as ECPublicKey) }

    override fun sign(message: ByteArray): ByteArray {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(keyPair.private)
        s.update(message)
        return EcCodec.derToRaw(s.sign())
    }

    private fun loadOrCreate(): KeyPair {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!ks.containsAlias(alias)) generate()
        val priv = ks.getKey(alias, null) as PrivateKey
        val pub = ks.getCertificate(alias).publicKey
        return KeyPair(pub, priv)
    }

    private fun generate() {
        fun spec(strongBox: Boolean): KeyGenParameterSpec {
            val b = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
            if (strongBox && Build.VERSION.SDK_INT >= 28) b.setIsStrongBoxBacked(true)
            return b.build()
        }
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        try {
            kpg.initialize(spec(true)); kpg.generateKeyPair()
            TLog.i(TAG, "identity key created in StrongBox")
        } catch (e: Exception) {                       // no StrongBox on this phone -> TEE
            kpg.initialize(spec(false)); kpg.generateKeyPair()
            TLog.i(TAG, "identity key created in TEE/Keystore")
        }
    }
}

/**
 * Encrypts small secrets (the Kyber private key, operator API tokens) with an AES-256-GCM key that lives in the
 * Keystore. The previous version derived its "storage key" from the device PUBLIC key, which the app broadcasts,
 * so anyone with the file could decrypt it. This key is non-exportable.
 */
class SecureStore(@Suppress("UNUSED_PARAMETER") context: Context) {
    private companion object { const val ALIAS = "thunder_storage_v3" }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        kg.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    /** Output: iv(12) || ciphertext+tag. */
    fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(plain)
    }

    fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > 28) { "blob too short" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        return c.doFinal(blob.copyOfRange(12, blob.size))
    }
}
