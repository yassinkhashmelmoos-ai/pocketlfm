package com.thunder.agi.crypto

import org.bouncycastle.pqc.crypto.crystals.kyber.KyberKEMExtractor
import org.bouncycastle.pqc.crypto.crystals.kyber.KyberKEMGenerator
import org.bouncycastle.pqc.crypto.crystals.kyber.KyberKeyGenerationParameters
import org.bouncycastle.pqc.crypto.crystals.kyber.KyberKeyPairGenerator
import org.bouncycastle.pqc.crypto.crystals.kyber.KyberParameters
import org.bouncycastle.pqc.crypto.crystals.kyber.KyberPrivateKeyParameters
import org.bouncycastle.pqc.crypto.crystals.kyber.KyberPublicKeyParameters
import java.security.SecureRandom

/**
 * Post-quantum key encapsulation: CRYSTALS-Kyber-768 (BouncyCastle round-3 variant).
 *
 * NOTE: this is NOT the final FIPS-203 "ML-KEM". To move to ML-KEM, bump BouncyCastle to >= 1.79
 * and change only the imports / class names in this one file (org.bouncycastle.pqc.crypto.mlkem.*).
 */
object Kem {
    const val PUBLIC_KEY_BYTES = 1184
    const val CIPHERTEXT_BYTES = 1088
    private val PARAMS = KyberParameters.kyber768

    class KeyPair(val publicKey: ByteArray, val privateKey: ByteArray)
    class Encapsulated(val cipherText: ByteArray, val sharedSecret: ByteArray)

    fun generate(random: SecureRandom = SecureRandom()): KeyPair {
        val gen = KyberKeyPairGenerator()
        gen.init(KyberKeyGenerationParameters(random, PARAMS))
        val kp = gen.generateKeyPair()
        return KeyPair(
            (kp.public as KyberPublicKeyParameters).encoded,
            (kp.private as KyberPrivateKeyParameters).encoded
        )
    }

    fun encapsulate(publicKey: ByteArray): Encapsulated {
        require(publicKey.size == PUBLIC_KEY_BYTES) { "bad Kyber public key length" }
        val r = KyberKEMGenerator(SecureRandom()).generateEncapsulated(KyberPublicKeyParameters(PARAMS, publicKey))
        return Encapsulated(r.encapsulation, r.secret)
    }

    fun decapsulate(cipherText: ByteArray, privateKey: ByteArray): ByteArray {
        require(cipherText.size == CIPHERTEXT_BYTES) { "bad Kyber ciphertext length" }
        return KyberKEMExtractor(KyberPrivateKeyParameters(PARAMS, privateKey)).extractSecret(cipherText)
    }
}
