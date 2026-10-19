package com.thunder.agi.imaging

import android.graphics.Bitmap
import android.graphics.Color

/**
 * FIX: this class is new — needed for true diptych-conditioned editing (the user's
 * explicit request), since DreamLite's edit template embeds
 * "<|vision_start|><|image_pad|><|vision_end|>", meaning Qwen3-VL must actually see
 * the source image, not just text.
 *
 * HONEST FLAG — this is the single least-verified piece in this whole pipeline. The
 * patch-flattening order below is transliterated directly, axis-by-axis, from
 * Qwen2VLImageProcessor._preprocess's exact reshape/transpose sequence (read from its
 * real source during this work — there is no separate Qwen3-VL image processor file,
 * confirming it's reused), which IS real, read, grounded logic, not guesswork. What
 * is *not* independently confirmed is that Qwen3-VL-2B-Instruct's own checkpoint uses
 * these exact same patch_size=14 / temporal_patch_size=2 / merge_size=2 values (this
 * is extremely likely — standard across the whole Qwen-VL family — but unlike the
 * generate-mode text path, nobody has verified this against reference output here.
 * Getting the axis order wrong wouldn't throw an error — it would silently scramble
 * every patch's position. Test this specifically before trusting edit-mode output.
 */
object QwenVisionPreprocessor {
    private const val PATCH_SIZE = 14
    private const val TEMPORAL_PATCH_SIZE = 2
    private const val MERGE_SIZE = 2
    // OPENAI_CLIP_MEAN / OPENAI_CLIP_STD — confirmed as this shared processor's
    // defaults in its actual source, not assumed.
    private val MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
    private val STD  = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)

    class PatchResult(val pixelValues: FloatArray, val gridT: Int, val gridH: Int, val gridW: Int)

    /** DreamLitePipeline resizes the edit-conditioning image to a fixed 512x512
     *  (separate from the actual output resolution) before encoding — see
     *  encode_prompt's edit branch. 504 is the nearest multiple of patch*merge (28)
     *  at or below 512; the real smart_resize algorithm is more aspect-ratio-aware
     *  than this simple round-down, another part of the honest flag above. */
    fun patchify(bitmap: Bitmap, targetSize: Int = 504): PatchResult {
        val unit = PATCH_SIZE * MERGE_SIZE
        val h = (targetSize / unit) * unit
        val w = (targetSize / unit) * unit
        val resized = Bitmap.createScaledBitmap(bitmap, w, h, true)

        val gridH = h / PATCH_SIZE
        val gridW = w / PATCH_SIZE
        val gridT = 1  // a static image, internally duplicated to fill temporal_patch_size — see below

        // Normalized [C][H*W] planes, matching do_rescale (1/255) + do_normalize(mean,std)
        val channels = Array(3) { FloatArray(h * w) }
        for (y in 0 until h) for (x in 0 until w) {
            val p = resized.getPixel(x, y)
            val idx = y * w + x
            channels[0][idx] = (Color.red(p)   / 255f - MEAN[0]) / STD[0]
            channels[1][idx] = (Color.green(p) / 255f - MEAN[1]) / STD[1]
            channels[2][idx] = (Color.blue(p)  / 255f - MEAN[2]) / STD[2]
        }

        val hWindows = gridH / MERGE_SIZE
        val wWindows = gridW / MERGE_SIZE
        val numPatches = gridT * gridH * gridW
        val patchDim = 3 * TEMPORAL_PATCH_SIZE * PATCH_SIZE * PATCH_SIZE
        val out = FloatArray(numPatches * patchDim)

        // FIX: this exact 5-level nesting order (t, hWindow, wWindow, hInWindow,
        // wInWindow) is the "outer" half of transpose(0,3,6,4,7,2,1,5,8) applied to
        // the 9D reshape [grid_t, temporal, channel, hWin, merge, patch, wWin, merge,
        // patch] — i.e. new axis order [t, hWin, wWin, mergeRow, mergeCol, channel,
        // temporal, patchRow, patchCol]. Patches are grouped into merge_size x
        // merge_size windows (for the model's token-merging step), NOT plain
        // raster-scan order — that grouping is the entire point of this specific
        // index order, not an arbitrary choice.
        var patchIdx = 0
        for (t in 0 until gridT) {
            for (hw in 0 until hWindows) {
                for (ww in 0 until wWindows) {
                    for (hi in 0 until MERGE_SIZE) {
                        for (wi in 0 until MERGE_SIZE) {
                            val patchRow = hw * MERGE_SIZE + hi
                            val patchCol = ww * MERGE_SIZE + wi
                            var o = patchIdx * patchDim
                            // Inner (per-patch content) order: channel -> temporal -> row -> col(fastest).
                            // A static image has no real second temporal frame; the
                            // Python processor pads by repeating the single frame to
                            // fill temporal_patch_size, so both temporal slots here
                            // use the same pixel data.
                            for (c in 0 until 3) {
                                for (tp in 0 until TEMPORAL_PATCH_SIZE) {
                                    for (pr in 0 until PATCH_SIZE) {
                                        val srcRowBase = (patchRow * PATCH_SIZE + pr) * w + patchCol * PATCH_SIZE
                                        for (pc in 0 until PATCH_SIZE) {
                                            out[o++] = channels[c][srcRowBase + pc]
                                        }
                                    }
                                }
                            }
                            patchIdx++
                        }
                    }
                }
            }
        }
        return PatchResult(out, gridT, gridH, gridW)
    }
}
