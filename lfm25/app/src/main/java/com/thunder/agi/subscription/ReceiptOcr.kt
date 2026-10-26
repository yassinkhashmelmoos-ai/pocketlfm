package com.thunder.agi.subscription

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

/**
 * On-device OCR for receipt screenshots: Tesseract 5 through Tesseract4Android, fully offline.
 * The two small language models (assets/tessdata/ara + eng, the "tessdata_fast" set) are copied to
 * filesDir on first use because Tesseract needs real files. Arabic is needed for the labels and the
 * name; English is needed for the digits, the month ("Oct") and "N/A", Arabic alone reads none of them.
 *
 * Not thread-safe: one instance, used from one coroutine. Always close() it.
 */
class ReceiptOcr private constructor(private val tess: TessBaseAPI) : AutoCloseable {

    /** The OCR engine itself could not start (missing models, native load failure). Not the user's fault. */
    class OcrUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

    companion object {
        private const val LANGS = "ara+eng"
        /** Bump when the .traineddata files in assets change so installed copies are refreshed. */
        private const val DATA_VERSION = 1
        private val MODELS = listOf("ara.traineddata", "eng.traineddata")

        fun open(context: Context): ReceiptOcr {
            val root = try {
                ensureModels(context)
            } catch (e: Exception) {
                throw OcrUnavailableException("OCR models could not be prepared", e)
            }
            val api = TessBaseAPI()
            val started = try {
                api.init(root.absolutePath, LANGS)
            } catch (e: Exception) {
                api.recycle()
                throw OcrUnavailableException("OCR engine failed to start", e)
            }
            if (!started) {
                api.recycle()
                throw OcrUnavailableException("OCR engine could not load $LANGS")
            }
            // single uniform block: the page mode the layout tests were run with
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
            return ReceiptOcr(api)
        }

        private fun ensureModels(context: Context): File {
            val root = File(context.filesDir, "tesseract")
            val dir = File(root, "tessdata").apply { mkdirs() }
            val stamp = File(dir, "version")
            val installed = if (stamp.exists()) stamp.readText().trim() else ""
            if (installed != DATA_VERSION.toString() || MODELS.any { !File(dir, it).exists() }) {
                for (name in MODELS) {
                    val tmp = File(dir, "$name.tmp")
                    context.assets.open("tessdata/$name").use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    check(tmp.renameTo(File(dir, name))) { "could not install $name" }
                }
                stamp.writeText(DATA_VERSION.toString())
            }
            return root
        }
    }

    /**
     * OCR attempts, most reliable first, for ReceiptVerifier to run until the receipt validates.
     * Measured on a real Bankak receipt and 9 degraded copies (JPEG q20, 0.5x-1.5x resizes, dimmed,
     * low contrast, blurred): the first pass alone read 9 of 10 and the fallbacks recovered the rest.
     */
    fun passes(source: Bitmap): List<() -> String> = listOf(
        { recognizePrepared(source, ReceiptImagePrep.PRIMARY_WIDTH) },
        { recognize(source) },
        { recognizePrepared(source, ReceiptImagePrep.FALLBACK_WIDTH) }
    )

    private fun recognize(bitmap: Bitmap): String {
        tess.setImage(bitmap)
        return tess.getUTF8Text() ?: ""
    }

    private fun recognizePrepared(source: Bitmap, targetWidth: Int): String {
        val prepared = prepared(source, targetWidth)
        try {
            return recognize(prepared)
        } finally {
            prepared.recycle()
        }
    }

    /** Scale to a fixed width (so text is the same pixel size whatever phone took the screenshot), then gray + auto-contrast. */
    private fun prepared(source: Bitmap, targetWidth: Int): Bitmap {
        val h = ReceiptImagePrep.scaledHeight(source.width, source.height, targetWidth)
        val scaled = if (source.width == targetWidth) source else Bitmap.createScaledBitmap(source, targetWidth, h, true)
        val px = IntArray(targetWidth * h)
        scaled.getPixels(px, 0, targetWidth, 0, 0, targetWidth, h)
        if (scaled !== source) scaled.recycle()
        ReceiptImagePrep.grayAutoContrastInPlace(px)
        return Bitmap.createBitmap(px, targetWidth, h, Bitmap.Config.ARGB_8888)
    }

    override fun close() {
        tess.recycle()
    }
}
