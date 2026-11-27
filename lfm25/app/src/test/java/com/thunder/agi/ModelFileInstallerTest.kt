package com.thunder.agi

import com.thunder.agi.llama.AssetSource
import com.thunder.agi.llama.ModelFileInstaller
import com.thunder.agi.llama.ModelInstallException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * ModelFileInstaller replaces the old ThunderModel.resolveModelFile(), which
 *   - wrote straight into the final file and accepted ANY file over 50 MB as "complete" (so a copy interrupted at 23 %
 *     was loaded forever), and
 *   - had no lock, so two concurrent loads copied the same file at once.
 */
class ModelFileInstallerTest {

    private lateinit var dir: File

    @Before fun setUp() { dir = Files.createTempDirectory("installer-test").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    // ── fake assets ───────────────────────────────────────────────────────────────────────────────────────────────
    private class FakeAssets(
        private val name: String,
        val data: ByteArray,
        private val reportedSize: Long = data.size.toLong(),
        private val failAfterBytes: Long = -1L,      // throw IOException once this many bytes were served
        private val sleepPerReadMs: Long = 0L        // widen race windows
    ) : AssetSource {
        val opens = AtomicInteger(0)
        override fun exists(name: String) = name == this.name
        override fun sizeOf(name: String) = reportedSize
        override fun open(name: String): InputStream {
            opens.incrementAndGet()
            return object : InputStream() {
                private var pos = 0
                override fun read(): Int = if (pos >= data.size) -1 else data[pos++].toInt() and 0xff
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (sleepPerReadMs > 0) Thread.sleep(sleepPerReadMs)
                    if (failAfterBytes in 0..pos.toLong()) throw IOException("simulated read failure at $pos")
                    if (pos >= data.size) return -1
                    val n = minOf(len, 64 * 1024, data.size - pos)
                    System.arraycopy(data, pos, b, off, n)
                    pos += n
                    return n
                }
            }
        }
    }

    private fun bytes(n: Int) = ByteArray(n) { (it * 31 + 7).toByte() }
    private fun installer(a: AssetSource, free: Long = Long.MAX_VALUE / 2, valid: (File) -> Boolean = { true }) =
        ModelFileInstaller(dir, a, freeSpace = { free }, isValid = valid, bufferSize = 64 * 1024)

    // ── tests ─────────────────────────────────────────────────────────────────────────────────────────────────────
    @Test fun installs_the_file_and_the_second_call_does_no_copy() {
        val assets = FakeAssets("model.gguf", bytes(3_000_000))
        val inst = installer(assets)
        val f = inst.ensureInstalled("model.gguf")
        assertArrayEquals(assets.data, f.readBytes())
        assertEquals(1, assets.opens.get())
        inst.ensureInstalled("model.gguf")
        assertEquals("a finished file must not be copied again", 1, assets.opens.get())
        assertFalse("no .partial may remain", File(dir, "model.gguf.partial").exists())
    }

    @Test fun a_copy_cut_off_at_40_percent_is_detected_and_rebuilt() {
        // This is the old bug: "length > 50 MB" (23 % of the real model) counted as complete.
        val assets = FakeAssets("model.gguf", bytes(5_000_000))
        File(dir, "model.gguf").writeBytes(assets.data.copyOf(2_000_000))
        val f = installer(assets).ensureInstalled("model.gguf")
        assertEquals(5_000_000L, f.length())
        assertArrayEquals(assets.data, f.readBytes())
        assertEquals("the damaged file must have been replaced by a fresh copy", 1, assets.opens.get())
    }

    @Test fun a_stale_partial_file_is_discarded() {
        val assets = FakeAssets("model.gguf", bytes(1_000_000))
        File(dir, "model.gguf.partial").writeBytes(ByteArray(123))
        val f = installer(assets).ensureInstalled("model.gguf")
        assertArrayEquals(assets.data, f.readBytes())
        assertFalse(File(dir, "model.gguf.partial").exists())
    }

    @Test fun a_failing_stream_leaves_no_final_file_and_a_retry_succeeds() {
        val good = bytes(4_000_000)
        val broken = FakeAssets("model.gguf", good, failAfterBytes = 2_500_000)
        try { installer(broken).ensureInstalled("model.gguf"); fail("expected ModelInstallException") }
        catch (e: ModelInstallException) { assertTrue(e.message!!.contains("model.gguf")) }
        assertFalse("a failed copy must not leave a final file", File(dir, "model.gguf").exists())
        assertFalse("a failed copy must clean its .partial", File(dir, "model.gguf.partial").exists())

        val f = installer(FakeAssets("model.gguf", good)).ensureInstalled("model.gguf")
        assertArrayEquals(good, f.readBytes())
    }

    @Test fun a_stream_that_ends_early_is_reported_not_accepted() {
        // asset claims 4 MB but only delivers 3 MB
        val assets = FakeAssets("model.gguf", bytes(3_000_000), reportedSize = 4_000_000)
        try { installer(assets).ensureInstalled("model.gguf"); fail("expected ModelInstallException") }
        catch (e: ModelInstallException) { assertTrue(e.message!!.contains("cut short")) }
        assertFalse(File(dir, "model.gguf").exists())
    }

    @Test fun concurrent_callers_copy_once_and_nobody_ever_sees_a_partial_file() {
        val assets = FakeAssets("model.gguf", bytes(8_000_000), sleepPerReadMs = 2)   // ~250 ms copy
        val inst = installer(assets)
        val dest = File(dir, "model.gguf")
        val expected = assets.data.size.toLong()

        val start = CountDownLatch(1)
        val stopWatching = AtomicBoolean(false)
        val sawPartial = AtomicBoolean(false)
        val watcher = Thread {
            while (!stopWatching.get()) {
                // exists() FIRST: once the atomic rename has happened the file can only ever be complete
                if (dest.exists() && dest.length() != expected) sawPartial.set(true)
            }
        }.also { it.start() }

        val results = java.util.Collections.synchronizedList(mutableListOf<File>())
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val threads = (1..8).map {
            Thread {
                try { start.await(); results.add(inst.ensureInstalled("model.gguf")) } catch (t: Throwable) { errors.add(t) }
            }.also { t -> t.start() }
        }
        start.countDown()
        threads.forEach { it.join() }
        stopWatching.set(true); watcher.join()

        assertTrue("errors: $errors", errors.isEmpty())
        assertEquals(8, results.size)
        assertEquals("8 concurrent loads must copy exactly once", 1, assets.opens.get())
        assertFalse("the final path must never exist half-written", sawPartial.get())
        assertArrayEquals(assets.data, dest.readBytes())
    }

    @Test fun not_enough_free_space_is_reported_before_any_copy() {
        val assets = FakeAssets("model.gguf", bytes(2_000_000))
        try { installer(assets, free = 1_000_000L).ensureInstalled("model.gguf"); fail("expected ModelInstallException") }
        catch (e: ModelInstallException) { assertTrue(e.message!!.contains("Not enough free storage")) }
        assertEquals("must not even open the asset", 0, assets.opens.get())
    }

    @Test fun unknown_asset_size_trusts_only_a_finished_copy_with_a_marker() {
        val assets = FakeAssets("model.gguf", bytes(1_500_000), reportedSize = -1L)
        val inst = installer(assets)
        inst.ensureInstalled("model.gguf")
        inst.ensureInstalled("model.gguf")
        assertEquals("marker present -> no second copy", 1, assets.opens.get())

        File(dir, "model.gguf.ok").delete()           // e.g. an older install without a marker: cannot be verified
        inst.ensureInstalled("model.gguf")
        assertEquals("no marker -> rebuilt", 2, assets.opens.get())
    }

    @Test fun invalid_content_is_rejected() {
        val assets = FakeAssets("model.gguf", bytes(1_000_000))
        try { installer(assets, valid = { false }).ensureInstalled("model.gguf"); fail("expected ModelInstallException") }
        catch (e: ModelInstallException) { assertTrue(e.message!!.contains("not a valid")) }
        assertFalse(File(dir, "model.gguf").exists())
    }

    @Test fun a_missing_asset_gives_a_clear_error() {
        try { installer(FakeAssets("other.bin", bytes(10))).ensureInstalled("model.gguf"); fail("expected ModelInstallException") }
        catch (e: ModelInstallException) { assertTrue(e.message!!.contains("missing from the app package")) }
    }
}
