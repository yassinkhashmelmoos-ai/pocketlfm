package com.thunder.agi.util

import java.security.MessageDigest

private val HEX = "0123456789abcdef".toCharArray()

fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        out[i * 2] = HEX[v ushr 4]
        out[i * 2 + 1] = HEX[v and 0x0F]
    }
    return String(out)
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd hex length" }
    return ByteArray(length / 2) { i ->
        val hi = Character.digit(this[i * 2], 16)
        val lo = Character.digit(this[i * 2 + 1], 16)
        require(hi >= 0 && lo >= 0) { "bad hex" }
        ((hi shl 4) or lo).toByte()
    }
}

fun sha256(vararg parts: ByteArray): ByteArray {
    val md = MessageDigest.getInstance("SHA-256")
    for (p in parts) md.update(p)
    return md.digest()
}

fun concat(vararg parts: ByteArray): ByteArray {
    var n = 0
    for (p in parts) n += p.size
    val out = ByteArray(n)
    var o = 0
    for (p in parts) { System.arraycopy(p, 0, out, o, p.size); o += p.size }
    return out
}

fun ByteArray.ctEquals(other: ByteArray): Boolean = MessageDigest.isEqual(this, other)

/** Logging that also works in plain-JVM unit tests (android.util.Log is absent or stubbed there). */
object TLog {
    fun d(tag: String, msg: String) { try { android.util.Log.d(tag, msg) } catch (_: Throwable) {} }
    fun i(tag: String, msg: String) { try { android.util.Log.i(tag, msg) } catch (_: Throwable) {} }
    fun w(tag: String, msg: String) { try { android.util.Log.w(tag, msg) } catch (_: Throwable) {} }
    fun e(tag: String, msg: String) { try { android.util.Log.e(tag, msg) } catch (_: Throwable) {} }
}
