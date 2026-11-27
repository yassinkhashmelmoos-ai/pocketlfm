package com.thunder.agi.llama

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Where bundled files come from. The app uses [AndroidAssetSource] (LlamaModel.kt); unit tests use an in-memory fake. */
interface AssetSource {
    fun exists(name: String): Boolean

    /** Exact (uncompressed) size in bytes, or -1 when it cannot be known (e.g. the asset is stored compressed). */
    fun sizeOf(name: String): Long

    fun open(name: String): InputStream
}

/** Installing the file failed; [message] is already a sentence the user can read. */
class ModelInstallException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Copies one bundled asset into [dir] so native code can mmap it, and guarantees that
 *
 *  - the final file is NEVER visible half-written: the copy goes to `NAME.partial`, is verified, flushed to disk and
 *    only then renamed (atomic on the same filesystem) to `NAME`;
 *  - a file left behind by an interrupted copy (process killed, storage full, ...) is detected and rebuilt - the old
 *    code accepted anything over 50 MB, so a copy cut off at 23 % was treated as "done" forever;
 *  - callers in this process are serialised by one lock, so two concurrent loads copy the file once and the second
 *    caller simply finds the finished file;
 *  - running out of storage is reported BEFORE the copy starts, with the numbers.
 *
 * Plain JVM code (java.io only) so it can be tested without a device.
 */
class ModelFileInstaller(
    private val dir: File,
    private val assets: AssetSource,
    /** Free bytes available in a directory. Injectable so tests can simulate a full disk. */
    private val freeSpace: (File) -> Long = { it.usableSpace },
    /** Extra check on a finished file (the app verifies the GGUF magic). Must be cheap. */
    private val isValid: (File) -> Boolean = { true },
    private val bufferSize: Int = 1 shl 20
) {
    companion object {
        /** One lock for the whole process: two installers (e.g. two ViewModels) must never copy the same file at once. */
        private val LOCK = Any()

        const val SAFETY_MARGIN = 64L * 1024 * 1024
        const val PARTIAL_SUFFIX = ".partial"
        const val MARKER_SUFFIX = ".ok"
    }

    /** Returns the installed, verified file. Blocking (large copy): call from an IO thread. */
    @Throws(ModelInstallException::class)
    fun ensureInstalled(name: String): File = synchronized(LOCK) {
        if (!assets.exists(name)) {
            throw ModelInstallException("$name is missing from the app package. Please reinstall the app.")
        }
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw ModelInstallException("Cannot create the storage folder ${dir.path}.")
        }

        val dest = File(dir, name)
        val partial = File(dir, name + PARTIAL_SUFFIX)
        val marker = File(dir, name + MARKER_SUFFIX)
        val expected = assets.sizeOf(name)

        if (isIntact(dest, marker, expected)) {
            partial.delete()
            return dest
        }

        // Whatever is there is stale or half-written: remove it all and start clean.
        partial.delete()
        marker.delete()
        if (dest.exists() && !dest.delete()) {
            throw ModelInstallException("Cannot replace the damaged file ${dest.name}. Clear the app's storage and retry.")
        }

        if (expected >= 0) {
            val free = freeSpace(dir)
            val needed = expected + SAFETY_MARGIN
            if (free < needed) {
                throw ModelInstallException(
                    "لا توجد مساحة تخزين كافية لتجهيز النموذج (يلزم ${mb(needed)} م.ب، المتاح ${mb(free)} م.ب).\n" +
                    "Not enough free storage to prepare the AI model: need ${mb(needed)} MB, only ${mb(free)} MB free."
                )
            }
        }

        try {
            val copied: Long = assets.open(name).use { input ->
                FileOutputStream(partial).use { out ->
                    val n = input.copyTo(out, bufferSize)
                    out.flush()
                    try { out.fd.sync() } catch (e: IOException) { /* best effort: some filesystems refuse fsync */ }
                    n
                }
            }
            if (expected >= 0 && copied != expected) {
                throw ModelInstallException("Copy of $name was cut short ($copied of $expected bytes).")
            }
            if (partial.length() != copied) {
                throw ModelInstallException("Copy of $name is inconsistent on disk (${partial.length()} vs $copied bytes).")
            }
            if (!isValid(partial)) {
                throw ModelInstallException("The bundled $name is not a valid model file.")
            }
            if (!partial.renameTo(dest)) {
                Files.move(partial.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            marker.writeText(dest.length().toString())
            return dest
        } catch (e: ModelInstallException) {
            partial.delete()
            throw e
        } catch (e: Exception) {
            partial.delete()
            throw ModelInstallException("Copying $name failed: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    private fun isIntact(dest: File, marker: File, expected: Long): Boolean {
        if (!dest.isFile) return false
        val len = dest.length()
        if (len <= 0L) return false
        val sizeOk = if (expected >= 0) {
            len == expected                                    // the asset's real size is the truth
        } else {
            // size of the asset unknown: only trust a copy that finished and recorded its final size
            marker.isFile && marker.readText().trim() == len.toString()
        }
        return sizeOk && isValid(dest)
    }

    private fun mb(bytes: Long) = bytes / (1024 * 1024)
}
