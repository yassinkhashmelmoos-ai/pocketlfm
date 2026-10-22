package com.thunder.agi.crypto

import com.thunder.agi.util.concat
import com.thunder.agi.util.sha256
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.SecureRandom

/**
 * Mutually authenticated, post-quantum-keyed, encrypted stream.
 *
 *   C -> S : MAGIC | nonceC | ecPubC
 *   S -> C : nonceS | ecPubS | kyberPub(ephemeral) | Sig_S( "SRV" | H(helloC) | nonceS | ecPubS | kyberPub )
 *   C -> S : kyberCiphertext | Sig_C( "CLI" | H(helloC) | H(helloS) | kyberCiphertext )
 *   keys   : HKDF-SHA256(kyberSecret, salt = nonceC|nonceS, info = H(transcript)) -> c2s key, s2c key
 *
 * Both sides sign the transcript with their long-term identity key, so a man-in-the-middle cannot
 * substitute the Kyber key (the old version exchanged it unauthenticated). Frames are AES-256-GCM with
 * a strict per-direction counter in the nonce and AAD, so replay, reordering and truncation fail.
 *
 * The caller learns [peerPublicKey] (proven, not just claimed) and must bind it to a wallet address.
 */
class SecureChannel private constructor(
    private val inp: DataInputStream,
    private val out: DataOutputStream,
    private val sendKey: ByteArray,
    private val recvKey: ByteArray,
    val peerPublicKey: ByteArray
) {
    private var sendCtr = 0L
    private var recvCtr = 0L

    @Synchronized
    fun send(data: ByteArray) {
        val ct = Aead.sealWithNonce(sendKey, nonce(sendCtr), data, aad(sendCtr))
        sendCtr++
        val frame = ByteArray(4 + ct.size)                        // one write = one TCP segment for small frames
        frame[0] = (ct.size ushr 24).toByte(); frame[1] = (ct.size ushr 16).toByte()
        frame[2] = (ct.size ushr 8).toByte(); frame[3] = ct.size.toByte()
        System.arraycopy(ct, 0, frame, 4, ct.size)
        out.write(frame)
        out.flush()
    }

    fun receive(maxPlain: Int = DEFAULT_MAX): ByteArray {
        val n = inp.readInt()
        if (n < 16 || n > maxPlain + 16) throw IOException("bad frame length $n")
        val ct = ByteArray(n)
        inp.readFully(ct)
        val pt = Aead.openWithNonce(recvKey, nonce(recvCtr), ct, aad(recvCtr))   // throws on tamper / replay / reorder
        recvCtr++
        return pt
    }

    private fun nonce(ctr: Long): ByteArray {
        val n = ByteArray(12)
        for (i in 0 until 8) n[11 - i] = (ctr ushr (8 * i)).toByte()
        return n
    }

    private fun aad(ctr: Long): ByteArray = nonce(ctr).copyOfRange(4, 12)

    companion object {
        private val MAGIC = "THDR-SC1".toByteArray()
        private val DOM_SRV = "THUNDER-SC1-SRV".toByteArray()
        private val DOM_CLI = "THUNDER-SC1-CLI".toByteArray()
        private val KEYS = "THUNDER-SC1-KEYS".toByteArray()
        private const val DEFAULT_MAX = 8 * 1024 * 1024
        private val rng = SecureRandom()

        private const val HELLO_C = 8 + 32 + 33
        private const val HELLO_S = 32 + 33 + Kem.PUBLIC_KEY_BYTES + 64
        private const val FINISH_C = Kem.CIPHERTEXT_BYTES + 64

        private fun writeFrame(out: DataOutputStream, b: ByteArray) {
            out.writeInt(b.size); out.write(b); out.flush()
        }

        private fun readFrame(inp: DataInputStream, exact: Int): ByteArray {
            val n = inp.readInt()
            if (n != exact) throw IOException("bad handshake frame length $n (expected $exact)")
            return ByteArray(n).also { inp.readFully(it) }
        }

        private fun random32() = ByteArray(32).also { rng.nextBytes(it) }

        private class Keys(val c2s: ByteArray, val s2c: ByteArray)

        private fun derive(secret: ByteArray, nonceC: ByteArray, nonceS: ByteArray,
                           helloC: ByteArray, helloS: ByteArray, ct: ByteArray): Keys {
            val okm = Hkdf.derive(secret, concat(nonceC, nonceS), concat(KEYS, sha256(helloC, helloS, ct)), 64)
            return Keys(okm.copyOfRange(0, 32), okm.copyOfRange(32, 64))
        }

        fun client(inp: DataInputStream, out: DataOutputStream, me: Identity): SecureChannel {
            val nonceC = random32()
            val helloC = concat(MAGIC, nonceC, me.publicKey)
            writeFrame(out, helloC)

            val helloS = readFrame(inp, HELLO_S)
            val nonceS = helloS.copyOfRange(0, 32)
            val pubS = helloS.copyOfRange(32, 65)
            val kyberPub = helloS.copyOfRange(65, 65 + Kem.PUBLIC_KEY_BYTES)
            val sigS = helloS.copyOfRange(65 + Kem.PUBLIC_KEY_BYTES, HELLO_S)
            val srvMsg = concat(DOM_SRV, sha256(helloC), nonceS, pubS, kyberPub)
            if (!EcCodec.verify(pubS, srvMsg, sigS)) throw SecurityException("server authentication failed")

            val enc = Kem.encapsulate(kyberPub)
            val cliMsg = concat(DOM_CLI, sha256(helloC), sha256(helloS), enc.cipherText)
            writeFrame(out, concat(enc.cipherText, me.sign(cliMsg)))

            val k = derive(enc.sharedSecret, nonceC, nonceS, helloC, helloS, enc.cipherText)
            return SecureChannel(inp, out, k.c2s, k.s2c, pubS)
        }

        fun server(inp: DataInputStream, out: DataOutputStream, me: Identity): SecureChannel {
            val helloC = readFrame(inp, HELLO_C)
            if (!helloC.copyOfRange(0, 8).contentEquals(MAGIC)) throw IOException("bad magic")
            val nonceC = helloC.copyOfRange(8, 40)
            val pubC = helloC.copyOfRange(40, 73)

            val ephemeral = Kem.generate()
            val nonceS = random32()
            val srvMsg = concat(DOM_SRV, sha256(helloC), nonceS, me.publicKey, ephemeral.publicKey)
            val helloS = concat(nonceS, me.publicKey, ephemeral.publicKey, me.sign(srvMsg))
            writeFrame(out, helloS)

            val fin = readFrame(inp, FINISH_C)
            val ct = fin.copyOfRange(0, Kem.CIPHERTEXT_BYTES)
            val sigC = fin.copyOfRange(Kem.CIPHERTEXT_BYTES, FINISH_C)
            val cliMsg = concat(DOM_CLI, sha256(helloC), sha256(helloS), ct)
            if (!EcCodec.verify(pubC, cliMsg, sigC)) throw SecurityException("client authentication failed")

            val secret = Kem.decapsulate(ct, ephemeral.privateKey)
            val k = derive(secret, nonceC, nonceS, helloC, helloS, ct)
            return SecureChannel(inp, out, k.s2c, k.c2s, pubC)
        }
    }
}
