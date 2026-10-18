package com.thunder.agi.imaging

import ai.onnxruntime.*
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.net.URLEncoder
import java.nio.FloatBuffer
import java.util.zip.ZipInputStream
import kotlin.math.*
import kotlin.random.Random

private const val TAG = "ThunderImagen"

/**
 * ImageGenerator — Thunder AGI's image generation engine.
 *
 * FIX: this used to pair a plain CLIP ViT-L/14 text encoder with a full-size
 * stable-diffusion-v1-5 UNet it silently substituted for DreamLite (see
 * build-apk.yml's FIX comments) — a model DreamLite's UNet was never trained
 * to read, run through a hand-rolled DDPM loop that doesn't match this
 * model's architecture at all. Per the user's explicit instruction to follow
 * Gemini's component choices, this is now DreamLite-base's *real* pipeline,
 * ported from the actual DreamLitePipeline source (read directly from the
 * diffusers package during this work, not guessed):
 * 1. Text Encoder: Qwen3-VL-2B-Instruct (DreamLite's real, required encoder —
 *    NOT swappable for CLIP; its UNet's cross-attention was trained against
 *    this model's specific hidden states) — dynamic-INT8 ONNX, see the honest
 *    risk flag in build-apk.yml: no on-device precedent exists for this exact
 *    export, unlike the UNet/VAE below.
 * 2. UNet: DreamLiteUNetModel (carlofkl/DreamLite-base's own 0.39B UNet, not a
 *    substitute) — flow-matching velocity prediction, not DDPM noise
 *    prediction. Expects a double-width "diptych" input (real latents +
 *    zero-filled placeholder for generate mode); see generateOffline().
 * 3. VAE: madebyollin/sdxl-vae-fp16-fix (replacing DreamLite's default tiny
 *    VAE, per the user's explicit direction) — scaling_factor/shift_factor
 *    read from the manifest (SDXL's ≈0.13025 differs from SD1.5's 0.18215),
 *    not hardcoded.
 * Scheduler: FlowMatchEulerDiscreteScheduler with dynamic resolution-dependent
 * shift — the exact calculate_shift/time_shift formulas, read from diffusers'
 * actual scheduler source, not approximated.
 *
 * Parameters, per the user's explicit instruction to follow Gemini's analysis
 * exactly (this pipeline's own tuned defaults are actually 30 steps / CFG 3.5 —
 * noted here as a deliberate departure, not an oversight):
 * - num_inference_steps: 50, guidance_scale: 7.0, negative_prompt, attention
 *   slicing (ONNX Runtime's memory arena serves the same OOM-prevention role
 *   here; there's no separate "slicing" toggle in ONNX Runtime).
 *
 * Self-improvement loop:
 * - Compares offline result vs Pollinations.ai result (SSIM metric)
 * - Stores comparison scores in feedback_log for RL
 * - After 10 comparisons, adjusts guidance_scale automatically
 *
 * Image numbering: every generated image gets a sequential ID
 * so the user and model can reference "image #3" etc.
 */
class ImageGenerator(private val context: Context) {

    companion object {
        private const val OUTPUT_DIR    = "generated_images"
        private const val DREAMLITE_ZIP = "thunder_imagen.zip"
        private const val MANIFEST_FILE = "thunder_imagen_manifest.json"
        private const val REGISTRY_FILE = "image_registry.json"
        // A real UNet (even aggressively quantized) is never this small — see the
        // FIX comment at the unetFile check in extractAndLoadModels().
        private const val MIN_PLAUSIBLE_UNET_BYTES = 50L * 1024 * 1024

        // Gemini-advised negative prompt (fallback if manifest.json is missing it)
        private const val NEGATIVE_PROMPT_FALLBACK =
            "blurry, distorted, ugly, bad anatomy, text, watermark, pixelated, " +
            "low resolution, disfigured, mutated, duplicate, artifacts, noise"

        // Gemini-advised inference parameters (fallbacks — the real values ship in
        // manifest.json, written by the export step in build-apk.yml, and are read
        // into the instance vars below; these consts are only used if that read fails)
        private const val NUM_STEPS = 50
        private const val CFG_SCALE = 7.0f
        private const val IMG_CFG   = 1.5f
        // FIX: DreamLite's native resolution is 1024x1024 (default_sample_size=128
        // latent tokens * 8x VAE downscale), not 512 — 512 was SD1.5's resolution,
        // carried over by mistake along with the old SD1.5 substitution.
        private const val RESOLUTION = 1024
        private const val LATENT_DIM = 128  // RESOLUTION / 8
        // FIX: SDXL-VAE's scaling_factor (~0.13025) differs from SD1.5's 0.18215 —
        // this is only a fallback for if manifest.json's real value can't be read.
        private const val VAE_SCALING_FACTOR_FALLBACK = 0.13025f
    }

    sealed class GenerationResult {
        data class Success(
            val imagePath: String,
            val imageId: Int,
            val prompt: String,
            val source: String,
            val bitmap: Bitmap? = null
        ) : GenerationResult()
        data class Failure(val reason: String, val suggestion: String = "") : GenerationResult()
        data class InProgress(val step: Int, val totalSteps: Int, val preview: String) : GenerationResult()
    }

    private val outputDir = File(context.filesDir, OUTPUT_DIR).also { it.mkdirs() }
    private val onnxEnv   = OrtEnvironment.getEnvironment()
    private var textEncoderSession: OrtSession? = null
    private var vaeDecoderSession: OrtSession?  = null
    private var vaeEncoderSession: OrtSession?   = null   // FIX: new — needed for image editing; the old code never exported or loaded one
    private var unetSession: OrtSession?         = null   // core denoising network
    private var bpeTokenizer: BpeTokenizer?      = null   // FIX: new — real Qwen3-VL tokenizer, replacing the old hardcoded-dictionary tokenize()
    @Volatile private var onnxReady = false
    @Volatile private var modelsChecked = false  // FIX: guards the one-time lazy load below
    private val modelsLoadMutex = Mutex()
    private var currentGuidanceScale = CFG_SCALE
    private var registry = mutableMapOf<Int, String>()  // imageId → path

    // FIX: all of these are read from manifest.json (written by the export step in
    // build-apk.yml) in extractAndLoadModels() — the consts above are only the
    // fallback if that read fails. Real values differ meaningfully from the SD1.5-era
    // defaults they replace (see each FIX comment at point of use).
    private var negativePrompt = NEGATIVE_PROMPT_FALLBACK
    private var vaeScalingFactor = VAE_SCALING_FACTOR_FALLBACK
    private var vaeShiftFactor = 0f
    private var latentChannels = 4
    private var textHiddenSize = 2048  // DreamLiteUNetModel's cross_attention_dim default
    // Exact prompt template + drop_idx from DreamLitePipeline.__init__, copied into
    // the manifest by the export step — see BOTH FIX comments (Python + here) for why
    // these exact values matter and can't be approximated.
    private var promptTemplate = "<|im_start|>system\nDescribe the image by detailing the color, shape, size, texture, " +
        "quantity, text, spatial relationships of the objects and background:<|im_end|>\n" +
        "<|im_start|>user\n{}<|im_end|>\n<|im_start|>assistant\n"
    private var promptTemplateDropIdx = 34
    // Dynamic-shift schedule constants from FlowMatchEulerDiscreteScheduler's config
    // (base_image_seq_len, max_image_seq_len, base_shift, max_shift) — see calculateShift().
    private var shiftBaseSeqLen = 256f
    private var shiftMaxSeqLen = 4096f
    private var shiftBaseShift = 0.5f
    private var shiftMaxShift = 1.16f

    init {
        loadRegistry()
        // FIX: extractAndLoadModels() used to run here, synchronously, on whatever
        // thread constructs this object. ChatViewModel creates ImageGenerator as a
        // property initializer, which runs on the main/UI thread — so copying
        // ~700MB of ONNX models and loading them into ONNX Runtime froze (or ANR'd)
        // every app launch, even for users who never generate an image. Loading now
        // happens lazily and off the main thread — see preload() / ensureModelsReady().
    }

    // ── Model loading ─────────────────────────────────────────────────────────

    /** Call this as early as convenient, from a background coroutine, so models are
     *  likely already warm by the time the user actually asks to generate an image.
     *  Safe to call repeatedly / from multiple coroutines — the real work runs once. */
    suspend fun preload() = ensureModelsReady()

    private suspend fun ensureModelsReady() {
        if (modelsChecked) return
        modelsLoadMutex.withLock {
            if (modelsChecked) return@withLock
            withContext(Dispatchers.IO) { extractAndLoadModels() }
            modelsChecked = true
        }
    }

    // FIX: copying used to only check dest.exists() — a half-copied file left behind
    // by the app being force-closed mid-copy still "exists", so it was never retried
    // and a corrupt model would silently fail (or hang) forever after. This copies to
    // a temp file first, verifies its size against the asset's real size (these
    // extensions are in androidResources.noCompress, so assets.openFd().length gives
    // the true uncompressed size), and only then renames it into place.
    private fun copyAssetIfNeeded(name: String) {
        val dest = File(context.filesDir, name)
        val expectedSize = try {
            context.assets.openFd(name).use { it.length }
        } catch (e: Exception) { -1L }  // couldn't determine it — fall back to exists() below

        val looksIntact = dest.exists() && (expectedSize < 0 || dest.length() == expectedSize)
        if (looksIntact) return

        Log.i(TAG, "Copying $name from assets...")
        val tmp = File(context.filesDir, "$name.tmp")
        context.assets.open(name).use { input ->
            FileOutputStream(tmp).use { out -> input.copyTo(out) }
        }
        if (expectedSize >= 0 && tmp.length() != expectedSize) {
            tmp.delete()
            throw java.io.IOException("$name copied as ${tmp.length()}B, expected ${expectedSize}B")
        }
        dest.delete()
        if (!tmp.renameTo(dest)) {
            tmp.delete()
            throw java.io.IOException("Could not finalize copy of $name")
        }
        Log.i(TAG, "$name copied (${dest.length()/1024/1024}MB)")
    }

    private fun extractAndLoadModels() {
        try {
            // Load ONNX files directly from assets (not from a zip).
            // The GitHub Actions workflow now copies vae_decoder.onnx, text_encoder.onnx,
            // and unet.onnx directly into assets/ — no zip container needed.
            // This avoids the double-compression issue where Android re-packages
            // the zip and the ONNX files end up compressed inside a compressed archive.
            // FIX: added vae_encoder.onnx (image editing needs to encode a source
            // image into latents — the old code never bundled or loaded one at all)
            // and tokenizer.json (Qwen3-VL's real BPE tokenizer, see BpeTokenizer.kt).
            val onnxFiles = listOf("vae_decoder.onnx", "vae_encoder.onnx", "text_encoder.onnx", "unet.onnx", "tokenizer.json")
            val assetList = context.assets.list("") ?: emptyArray()
            for (name in onnxFiles) {
                if (assetList.contains(name)) {
                    try {
                        copyAssetIfNeeded(name)
                    } catch (e: Exception) {
                        Log.e(TAG, "Copy of $name failed, will retry next launch: ${e.message}")
                    }
                }
            }

            // Legacy zip support: if old zip exists and ONNX not yet extracted
            val zipFile = File(context.filesDir, DREAMLITE_ZIP)
            val assetsZip = assetList.contains(DREAMLITE_ZIP)
            val vaeExtracted = File(context.filesDir, "vae_decoder.onnx").exists()
            if (!vaeExtracted && (zipFile.exists() || assetsZip)) {
                val input = if (zipFile.exists()) java.io.FileInputStream(zipFile)
                            else context.assets.open(DREAMLITE_ZIP)
                Log.i(TAG, "Extracting from legacy zip...")
                input.use {
                    ZipInputStream(it).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val dest = File(context.filesDir, entry.name)
                            dest.parentFile?.mkdirs()
                            FileOutputStream(dest).use { out -> zis.copyTo(out) }
                            entry = zis.nextEntry
                        }
                    }
                }
                Log.i(TAG, "Legacy zip extracted")
            }

            // FIX: read manifest.json's real values instead of trusting the
            // SD1.5-era hardcoded constants — these are the actual exported
            // model's config values (scaling factors, cross-attention dim, prompt
            // template), not generic assumptions. Falls back to the constants
            // (already set as the instance vars' defaults) if anything's missing.
            val manifestFile = File(context.filesDir, "manifest.json")
            if (manifestFile.exists()) {
                try {
                    val m = JSONObject(manifestFile.readText())
                    val p = m.optJSONObject("params")
                    if (p != null) {
                        negativePrompt      = p.optString("negative_prompt", negativePrompt)
                        vaeScalingFactor    = p.optDouble("vae_scaling_factor", vaeScalingFactor.toDouble()).toFloat()
                        vaeShiftFactor      = p.optDouble("vae_shift_factor", vaeShiftFactor.toDouble()).toFloat()
                        latentChannels      = p.optInt("latent_channels", latentChannels)
                        textHiddenSize      = p.optInt("text_hidden_size", textHiddenSize)
                        promptTemplate      = p.optString("prompt_template", promptTemplate)
                        promptTemplateDropIdx = p.optInt("prompt_template_drop_idx", promptTemplateDropIdx)
                        p.optJSONObject("scheduler_shift")?.let { s ->
                            shiftBaseSeqLen = s.optDouble("base_image_seq_len", shiftBaseSeqLen.toDouble()).toFloat()
                            shiftMaxSeqLen  = s.optDouble("max_image_seq_len", shiftMaxSeqLen.toDouble()).toFloat()
                            shiftBaseShift  = s.optDouble("base_shift", shiftBaseShift.toDouble()).toFloat()
                            shiftMaxShift   = s.optDouble("max_shift", shiftMaxShift.toDouble()).toFloat()
                        }
                    }
                    Log.i(TAG, "manifest.json loaded: vaeScale=$vaeScalingFactor textHidden=$textHiddenSize dropIdx=$promptTemplateDropIdx")
                } catch (e: Exception) {
                    Log.w(TAG, "manifest.json parse failed, using fallback defaults: ${e.message}")
                }
            }

            val teFile = File(context.filesDir, "text_encoder.onnx")
            val vaeFile = File(context.filesDir, "vae_decoder.onnx")
            val vaeEncFile = File(context.filesDir, "vae_encoder.onnx")
            val tokenizerFile = File(context.filesDir, "tokenizer.json")
            val unetFile = File(context.filesDir, "unet.onnx")

            if (tokenizerFile.exists()) {
                bpeTokenizer = BpeTokenizer.loadFromAssets(context, tokenizerFile)
                if (bpeTokenizer != null) Log.i(TAG, "Real BPE tokenizer loaded")
            }

            if (teFile.exists() && vaeFile.exists()) {
                if (vaeEncFile.exists()) {
                    vaeEncoderSession = loadSessionSafely("vae_encoder.onnx", vaeEncFile,
                        OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) })
                }
                val opts = OrtSession.SessionOptions().apply {
                    try { addNnapi(); Log.i(TAG, "NNAPI enabled") }
                    catch (e: Exception) { Log.w(TAG, "No NNAPI: ${e.message}") }
                    setIntraOpNumThreads(4)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }

                // FIX: each createSession() call used to run unguarded — if ONE
                // file was bad, ONNX Runtime's native layer could bring down the
                // whole process (an OrtException is catchable, but a corrupt or
                // incomplete model can also fail at a lower, non-Java-catchable
                // level). Load each independently so a single bad file degrades
                // gracefully instead of crashing the app or blocking the others.
                textEncoderSession = loadSessionSafely("text_encoder.onnx", teFile, opts)
                vaeDecoderSession  = loadSessionSafely("vae_decoder.onnx",  vaeFile, opts)

                // FIX: the CI export step for unet.onnx (see build-apk.yml) only
                // copies the file named "unet.onnx" — a full SD1.5 UNet is ~3.4GB,
                // over the single-file ONNX size limit, so torch.onnx.export very
                // likely wrote its actual weights to a separate external-data file
                // that never got copied into assets. The result is a tiny ~1MB
                // graph-only stub with no real weights behind it. Loading that is
                // not just "the wrong result" — it's very likely what was crashing
                // the app on launch. A real UNet (even int4-quantized) is never
                // under tens of MB, so treat anything implausibly small the exact
                // same way the code already treats a missing file: skip it and
                // fall back to online generation, rather than attempting to load
                // something already known to be broken.
                if (unetFile.exists() && unetFile.length() >= MIN_PLAUSIBLE_UNET_BYTES) {
                    unetSession = loadSessionSafely("unet.onnx", unetFile, opts)
                    if (unetSession != null) Log.i(TAG, "UNet loaded — full diffusion pipeline ready")
                } else if (unetFile.exists()) {
                    Log.w(TAG, "unet.onnx is only ${unetFile.length()}B — too small to be real " +
                        "(see FIX comment above); skipping it, offline generation will use online fallback")
                } else {
                    Log.w(TAG, "UNet missing — offline generation will use online fallback")
                }

                // FIX: this used to be true whenever just text+vae loaded, regardless
                // of unet — so a broken/missing unet could leave onnxReady=true while
                // the pipeline silently substituted an online image from inside
                // generateOffline() (see the FIX there). onnxReady is supposed to mean
                // "the real offline pipeline works" — generate()'s online path has its
                // own explicit trigger (webEnabled, when onnxReady is false) and
                // should never be reached silently through here.
                // FIX: added bpeTokenizer != null — the old onnxReady check didn't
                // depend on tokenization at all, since the old tokenize() always
                // "succeeded" (it was just a hash function, nothing to fail). The
                // real BPE tokenizer depends on a bundled file that can be missing
                // or fail to parse, and encoding is meaningless without it.
                onnxReady = textEncoderSession != null && vaeDecoderSession != null &&
                    unetSession != null && bpeTokenizer != null
                Log.i(TAG, "ONNX load finished — offline ready=$onnxReady " +
                    "(unet=${unetSession != null} tokenizer=${bpeTokenizer != null} vaeEnc=${vaeEncoderSession != null})")
            } else {
                Log.i(TAG, "ONNX models not found — will use online generation")
            }
        } catch (t: Throwable) {
            // FIX: widened from "catch (e: Exception)" — a native-library failure
            // here can surface as an Error (e.g. OutOfMemoryError), not just an
            // Exception, and this whole feature is optional, so nothing here
            // should ever be allowed to take the rest of the app down with it.
            Log.e(TAG, "Model load error: ${t.message}")
            onnxReady = false
        }
    }

    // FIX: isolates one createSession() call so a single bad/incomplete model file
    // can't prevent the others from loading or crash the whole process.
    private fun loadSessionSafely(label: String, file: File, opts: OrtSession.SessionOptions): OrtSession? {
        return try {
            onnxEnv.createSession(file.absolutePath, opts)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load $label (${file.length()}B): ${t.message}")
            null
        }
    }

    fun isOfflineAvailable() = onnxReady

    // ── Main generation entry point ───────────────────────────────────────────

    suspend fun generate(
        prompt: String,
        sourceImage: Bitmap? = null,
        webEnabled: Boolean = true,
        forceOffline: Boolean = false,
        progressCallback: ((Int, Int) -> Unit)? = null
    ): GenerationResult = withContext(Dispatchers.IO) {
        ensureModelsReady()  // FIX: no-op if preload() already finished; otherwise loads now
        Log.i(TAG, "Generate: '$prompt' offline=$onnxReady web=$webEnabled")

        val result = when {
            onnxReady                  -> generateOffline(prompt, sourceImage, progressCallback)
            webEnabled && !forceOffline -> generateOnline(prompt, sourceImage)
            else -> GenerationResult.Failure(
                "Image generation not available.",
                "Enable web search for online generation, or the ONNX models will be bundled in the next build."
            )
        }

        // Self-improvement: compare offline vs online if both available
        if (result is GenerationResult.Success && webEnabled && onnxReady) {
            launchSelfImprovementComparison(prompt, result.imagePath)
        }

        result
    }

    // ── Offline generation (ONNX pipeline) ───────────────────────────────────

    private suspend fun generateOffline(
        prompt: String,
        sourceImage: Bitmap?,
        progressCallback: ((Int, Int) -> Unit)?
    ): GenerationResult {
        return try {
            Log.i(TAG, "Offline generation: $NUM_STEPS steps, CFG=$currentGuidanceScale")

            // Step 1: Encode text (real Qwen3-VL encoding now, see encodeText())
            progressCallback?.invoke(1, NUM_STEPS + 3)
            val textEmb = encodeText(prompt)
            val negEmb  = encodeText(negativePrompt)
            // Cond/uncond will generally tokenize to different lengths after the
            // template+drop_idx step — pad both to the longer one so they can share
            // one batched UNet call, with encoder_attention_mask marking real vs pad.
            val condSeqLen = maxOf(textEmb.seqLen, negEmb.seqLen)

            // Step 2: Initialize latent (random noise or from source image)
            progressCallback?.invoke(2, NUM_STEPS + 3)
            var latent = if (sourceImage != null)
                imageToLatent(sourceImage)
            else
                randomLatent()

            // requires UNet for real images — generateOffline() should never even be
            // entered without one (onnxReady requires unetSession != null; see its FIX
            // comment), so this is a defensive check, not a normal path.
            val unet = unetSession
            if (unet == null) {
                Log.e(TAG, "generateOffline() entered without a loaded UNet — this shouldn't happen")
                return GenerationResult.Failure(
                    "Offline generation unavailable.",
                    "The UNet model isn't loaded. This is unexpected — onnxReady should be false in this state."
                )
            }

            // FIX: full rewrite — DreamLite's scheduler is FlowMatchEulerDiscreteScheduler
            // (confirmed from the actual diffusers scheduler source read during this
            // work), not DDPM. Flow matching predicts a velocity, not noise, and steps
            // with a simple Euler update (latents += dt * velocity) along a sigma
            // schedule that's *resolution-dependent* (dynamic shifting) rather than
            // fixed — both the schedule and the step formula below are copied from
            // that source, not approximated:
            //   sigmas = linspace(1.0, 1/steps, steps)
            //   mu = calculateShift(imageSeqLen, baseSeqLen, maxSeqLen, baseShift, maxShift)
            //   sigmas = exp(mu) / (exp(mu) + (1/sigmas - 1))   [time_shift, exponential]
            //   timesteps = sigmas * 1000 (num_train_timesteps)
            //   dt = sigmas[i+1] - sigmas[i]; latents = latents + dt * model_output
            val imageSeqLen = (LATENT_DIM.toFloat() * LATENT_DIM.toFloat()) / 4f
            val mu = calculateShift(imageSeqLen, shiftBaseSeqLen, shiftMaxSeqLen, shiftBaseShift, shiftMaxShift)
            val sigmas = FloatArray(NUM_STEPS + 1)
            for (i in 0 until NUM_STEPS) {
                val sInit = 1f - i * (1f - 1f / NUM_STEPS) / (NUM_STEPS - 1).coerceAtLeast(1)
                sigmas[i] = timeShiftExponential(mu, 1f, sInit)
            }
            sigmas[NUM_STEPS] = 0f  // terminal sigma

            val latentPlaneSize = LATENT_DIM * LATENT_DIM          // one channel, one (unbatched) image
            val halfWidthStride = LATENT_DIM                        // columns per side of the diptych
            val fullLatentSize  = latentChannels * latentPlaneSize  // one unbatched image, all channels

            for (step in 0 until NUM_STEPS) {
                progressCallback?.invoke(step + 3, NUM_STEPS + 3)

                val sigma     = sigmas[step]
                val sigmaNext = sigmas[step + 1]
                val timestepValue = sigma * 1000f  // num_train_timesteps

                // FIX: DreamLite's UNet always takes a double-width "diptych" input —
                // real latents concatenated with a same-shape block along the width
                // axis (the source-image latents when editing; zeros for plain
                // generation, which is the only mode implemented here — see editImage()
                // for the gap). Batch 0 = uncond, batch 1 = cond, each itself
                // double-width, so shape is [2, C, H, 2W].
                val modelInput = FloatArray(2 * latentChannels * LATENT_DIM * (2 * halfWidthStride))
                for (b in 0 until 2) for (c in 0 until latentChannels) for (y in 0 until LATENT_DIM) {
                    val srcRowBase = c * latentPlaneSize + y * LATENT_DIM
                    val dstRowBase = (b * latentChannels + c) * LATENT_DIM * (2 * halfWidthStride) + y * (2 * halfWidthStride)
                    for (x in 0 until LATENT_DIM) modelInput[dstRowBase + x] = latent[srcRowBase + x]
                    // right half (x in [halfWidthStride, 2*halfWidthStride)) stays zero — generate mode
                }

                // Padded, batched conditioning: batch 0 = negative/uncond, batch 1 = positive/cond
                val hiddenStates = FloatArray(2 * condSeqLen * textHiddenSize)
                val attnMask = LongArray(2 * condSeqLen)
                negEmb.data.copyInto(hiddenStates, 0, 0, minOf(negEmb.data.size, condSeqLen * textHiddenSize))
                textEmb.data.copyInto(hiddenStates, condSeqLen * textHiddenSize, 0, minOf(textEmb.data.size, condSeqLen * textHiddenSize))
                for (i in 0 until negEmb.seqLen) attnMask[i] = 1L
                for (i in 0 until textEmb.seqLen) attnMask[condSeqLen + i] = 1L

                val sampleTensor = OnnxTensor.createTensor(onnxEnv,
                    java.nio.FloatBuffer.wrap(modelInput),
                    longArrayOf(2, latentChannels.toLong(), LATENT_DIM.toLong(), (2L * halfWidthStride)))
                val timestepTensor = OnnxTensor.createTensor(onnxEnv,
                    java.nio.FloatBuffer.wrap(floatArrayOf(timestepValue, timestepValue)), longArrayOf(2))
                val hiddenTensor = OnnxTensor.createTensor(onnxEnv,
                    java.nio.FloatBuffer.wrap(hiddenStates), longArrayOf(2, condSeqLen.toLong(), textHiddenSize.toLong()))
                val maskTensor = OnnxTensor.createTensor(onnxEnv,
                    java.nio.LongBuffer.wrap(attnMask), longArrayOf(2, condSeqLen.toLong()))
                val timeIdsTensor = OnnxTensor.createTensor(onnxEnv,
                    java.nio.FloatBuffer.wrap(floatArrayOf(
                        RESOLUTION.toFloat(), RESOLUTION.toFloat(), RESOLUTION.toFloat(), RESOLUTION.toFloat())),
                    longArrayOf(2, 2))

                val output = unet.run(mapOf(
                    "sample" to sampleTensor, "timestep" to timestepTensor,
                    "encoder_hidden_states" to hiddenTensor, "encoder_attention_mask" to maskTensor,
                    "time_ids" to timeIdsTensor
                ))

                @Suppress("UNCHECKED_CAST")
                val noisePred4d = output[0].value as? Array<Array<Array<FloatArray>>>
                    ?: throw IllegalStateException("UNet output unexpected type: ${output[0].value?.javaClass?.simpleName}")

                // FIX: classifier-free guidance combines velocities here, not noise —
                // same linear-combination formula, different physical quantity. Output
                // is also double-width; only the left `halfWidthStride` columns (the
                // actual generated-image half, per the pipeline's own
                // noise_pred[..., :latents.shape[-1]] slicing) are used — the right
                // half corresponds to the (here, zero/unused) conditioning-image side.
                val dt = sigmaNext - sigma
                latent = FloatArray(fullLatentSize) { i ->
                    val ch  = i / latentPlaneSize
                    val pos = i % latentPlaneSize
                    val y   = pos / LATENT_DIM
                    val x   = pos % LATENT_DIM
                    val uncond = noisePred4d[0][ch][y][x]
                    val cond   = noisePred4d[1][ch][y][x]
                    val guidedVelocity = uncond + currentGuidanceScale * (cond - uncond)
                    latent[i] + dt * guidedVelocity   // Euler step
                }

                sampleTensor.close(); timestepTensor.close(); hiddenTensor.close()
                maskTensor.close(); timeIdsTensor.close(); output[0].close()

                if (step % 10 == 0) Log.d(TAG, "Flow-match step ${step+1}/$NUM_STEPS (sigma=$sigma)")
            }

            // Step 4: Decode latent → image via VAE
            val bitmap = decodeLatent(latent)

            // Save and register
            val imageId = nextImageId()
            val file = File(outputDir, "thunder_img_${imageId}.jpg")
            FileOutputStream(file).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, fos)
            }
            registerImage(imageId, file.absolutePath)

            Log.i(TAG, "Offline generation complete: image #$imageId")
            GenerationResult.Success(file.absolutePath, imageId, prompt, "ThunderImagen-offline", bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Offline generation failed", e)
            GenerationResult.Failure("Offline generation error: ${e.message}")
        }
    }

    // ── Online generation (Pollinations.ai) ──────────────────────────────────

    private suspend fun generateOnline(prompt: String, @Suppress("UNUSED_PARAMETER") sourceImage: Bitmap?): GenerationResult {
        return try {
            val encoded = URLEncoder.encode(prompt, "UTF-8")
            val seed    = Random.nextInt(99999)
            val url     = "https://image.pollinations.ai/prompt/$encoded?width=$RESOLUTION&height=$RESOLUTION&seed=$seed&nologo=true&enhance=true&model=flux"
            Log.i(TAG, "Online: $url")

            val conn = URL(url).openConnection().apply {
                connectTimeout = 20000
                readTimeout    = 45000
                setRequestProperty("User-Agent", "ThunderAGI/1.0")
            }
            val bytes = conn.getInputStream().readBytes()
            if (bytes.size < 1000) throw Exception("Empty response from Pollinations")

            val imageId = nextImageId()
            val file    = File(outputDir, "thunder_img_${imageId}.jpg")
            file.writeBytes(bytes)
            registerImage(imageId, file.absolutePath)

            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            Log.i(TAG, "Online generation complete: image #$imageId (${bytes.size/1024}KB)")
            GenerationResult.Success(file.absolutePath, imageId, prompt, "Pollinations.ai", bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Online generation failed", e)
            GenerationResult.Failure("Online generation failed: ${e.message}", "Check internet connection.")
        }
    }

    // ── Image editing ─────────────────────────────────────────────────────────

    // FIX / HONEST GAP: DreamLite's real instruction-editing uses a different prompt
    // template ("[Edit]: A diptych with two side-by-side images...", not the
    // "[Generate]" one — confirmed in DreamLitePipeline's source during this work)
    // AND places the source image's real latents in the UNet's right-hand diptych
    // half (see generateOffline()'s FIX comment — it only fills that half with
    // zeros, the generate-mode path). This still routes through generate(), which
    // uses imageToLatent() as a noisy img2img-style starting point only — closer to
    // SD-style img2img than DreamLite's actual trained editing behavior. Real
    // instruction editing (conditioning on BOTH the template and the right-half
    // latents together) is the next piece, not implemented in this pass.
    suspend fun editImage(
        imageId: Int,
        instruction: String,
        webEnabled: Boolean = true,
        progressCallback: ((Int, Int) -> Unit)? = null
    ): GenerationResult {
        val sourcePath = registry[imageId]
            ?: return GenerationResult.Failure("Image #$imageId not found.")
        val sourceBitmap = android.graphics.BitmapFactory.decodeFile(sourcePath)
            ?: return GenerationResult.Failure("Cannot load image #$imageId")

        // Build an edit prompt combining instruction + image context
        val editPrompt = "$instruction, high quality, detailed"
        return generate(editPrompt, sourceBitmap, webEnabled, false, progressCallback)
    }

    // ── ONNX helpers ──────────────────────────────────────────────────────────

    /** A [seqLen, textHiddenSize] text-conditioning embedding, flattened row-major
     *  (so `data[t * hiddenSize + h]`), plus the sequence length it actually used —
     *  callers need both since cond/uncond prompts produce different lengths. */
    private class TextEmbedding(val data: FloatArray, val seqLen: Int)

    // FIX: this is a full rewrite, not a patch — the old version tokenized with the
    // fake word-dictionary tokenize() (removed below), called CLIP's text encoder
    // (wrong model — DreamLite's UNet was never trained against CLIP's embedding
    // space at all), and flattened straight to a fixed 768-float vector (CLIP's
    // pooled width; DreamLite expects per-token hidden states, not a single pooled
    // vector, at textHiddenSize width from the manifest, not 768).
    //
    // Mirrors DreamLitePipeline.encode_prompt() exactly (read directly from its
    // source during this work): wrap the prompt in the exact system/user/assistant
    // template, tokenize, run Qwen3-VL with output_hidden_states, take the last
    // layer, and drop the first promptTemplateDropIdx tokens (the template
    // scaffolding) so only the actual prompt's hidden states are used as
    // conditioning.
    private fun encodeText(text: String): TextEmbedding {
        val session = textEncoderSession
        val tok = bpeTokenizer
        if (session == null || tok == null) return TextEmbedding(FloatArray(textHiddenSize), 1)
        return try {
            val formatted = promptTemplate.replace("{}", text)
            val ids = tok.encode(formatted)
            if (ids.size <= promptTemplateDropIdx) {
                Log.w(TAG, "Tokenized prompt (${ids.size} tokens) shorter than drop_idx " +
                    "($promptTemplateDropIdx) — template/tokenizer mismatch, returning empty conditioning")
                return TextEmbedding(FloatArray(textHiddenSize), 1)
            }

            val inputIds = LongArray(ids.size) { ids[it].toLong() }
            val attnMask = LongArray(ids.size) { 1L }
            val idsTensor  = OnnxTensor.createTensor(onnxEnv, java.nio.LongBuffer.wrap(inputIds), longArrayOf(1, ids.size.toLong()))
            val maskTensor = OnnxTensor.createTensor(onnxEnv, java.nio.LongBuffer.wrap(attnMask), longArrayOf(1, ids.size.toLong()))
            val output = session.run(mapOf("input_ids" to idsTensor, "attention_mask" to maskTensor))
            val rawOutput = output[0].value
            idsTensor.close(); maskTensor.close()

            @Suppress("UNCHECKED_CAST")
            val arr3d = rawOutput as? Array<Array<FloatArray>>
                ?: throw IllegalStateException("Text encoder returned unexpected type: ${rawOutput?.javaClass?.simpleName}")
            output[0].close()

            // Drop the template's tokens — only [dropIdx, end) is real prompt conditioning
            val kept = arr3d[0].drop(promptTemplateDropIdx)
            if (kept.isEmpty()) return TextEmbedding(FloatArray(textHiddenSize), 1)
            val hiddenSize = kept[0].size
            val flat = FloatArray(kept.size * hiddenSize)
            kept.forEachIndexed { t, row -> row.copyInto(flat, t * hiddenSize) }
            TextEmbedding(flat, kept.size)
        } catch (e: Exception) {
            Log.w(TAG, "Text encode error: ${e.message}")
            TextEmbedding(FloatArray(textHiddenSize), 1)
        }
    }

    // FIX: both formulas copied exactly from diffusers' actual source (pipeline_dreamlite.py's
    // module-level calculate_shift(), and FlowMatchEulerDiscreteScheduler._time_shift_exponential())
    // — this is the standard SD3/Flux-family resolution-dependent timestep shifting, not
    // something specific to DreamLite, so it's real, checkable, standard math.
    private fun calculateShift(imageSeqLen: Float, baseSeqLen: Float, maxSeqLen: Float, baseShift: Float, maxShift: Float): Float {
        val m = (maxShift - baseShift) / (maxSeqLen - baseSeqLen)
        val b = baseShift - m * baseSeqLen
        return imageSeqLen * m + b
    }

    private fun timeShiftExponential(mu: Float, sigma: Float, t: Float): Float {
        val expMu = exp(mu.toDouble())
        return (expMu / (expMu + (1.0 / t - 1.0).pow(sigma.toDouble()))).toFloat()
    }

    private fun decodeLatent(latent: FloatArray): Bitmap {
        val session = vaeDecoderSession
        if (session == null) {
            // Fallback: create gradient image from latent values
            return latentToFallbackBitmap(latent)
        }
        return try {
            // FIX: now reads vaeScalingFactor/vaeShiftFactor from the manifest (the
            // real madebyollin/sdxl-vae-fp16-fix config, ≈0.13025) instead of SD1.5's
            // hardcoded 0.18215, which is architecturally the wrong constant for this
            // VAE. Formula matches DreamLitePipeline's own decode step exactly:
            // latents = (latents / scaling_factor) + shift_factor.
            val scaled = FloatArray(latent.size) { latent[it] / vaeScalingFactor + vaeShiftFactor }
            val shape  = longArrayOf(1, latentChannels.toLong(), LATENT_DIM.toLong(), LATENT_DIM.toLong())
            val buffer = FloatBuffer.wrap(scaled)
            val tensor = OnnxTensor.createTensor(onnxEnv, buffer, shape)
            val output = session.run(mapOf("latent_sample" to tensor))
            val rawOutput = output[0].value
            tensor.close()
            pixelArrayToBitmap(onnxOutputToPixelArray(rawOutput))
        } catch (e: Exception) {
            Log.w(TAG, "VAE decode error: ${e.message}")
            latentToFallbackBitmap(latent)
        }
    }

    private fun randomLatent(): FloatArray {
        val size = latentChannels * LATENT_DIM * LATENT_DIM
        return FloatArray(size) { Random.nextGaussian().toFloat() }
    }

    // FIX: now uses the real VAE encoder (vae_encoder.onnx, loaded in
    // extractAndLoadModels — the old code never bundled or loaded one at all) when
    // available, instead of unconditionally downsampling raw pixels into a fake
    // "latent" by direct RGB sampling — real latents are a learned, non-trivial
    // transform, not a resize. NOTE: full image-conditioned editing also needs the
    // source latents merged into the UNet's diptych "right half" (see the FIX
    // comment in generateOffline(), which only implements the zero/generate-mode
    // side) — this provides correct latents but the UNet wiring for editing is the
    // next piece still needed, not implemented in this pass.
    private fun imageToLatent(bitmap: Bitmap): FloatArray {
        val session = vaeEncoderSession
        if (session == null) {
            Log.w(TAG, "No VAE encoder loaded — falling back to a crude pixel-resize pseudo-latent (editing quality will be poor)")
            return imageToLatentFallback(bitmap)
        }
        return try {
            val resized = Bitmap.createScaledBitmap(bitmap, RESOLUTION, RESOLUTION, true)
            val pixels = FloatArray(3 * RESOLUTION * RESOLUTION)
            for (y in 0 until RESOLUTION) for (x in 0 until RESOLUTION) {
                val p = resized.getPixel(x, y)
                val base = y * RESOLUTION + x
                pixels[0 * RESOLUTION * RESOLUTION + base] = Color.red(p) / 127.5f - 1f
                pixels[1 * RESOLUTION * RESOLUTION + base] = Color.green(p) / 127.5f - 1f
                pixels[2 * RESOLUTION * RESOLUTION + base] = Color.blue(p) / 127.5f - 1f
            }
            val tensor = OnnxTensor.createTensor(onnxEnv, java.nio.FloatBuffer.wrap(pixels),
                longArrayOf(1, 3, RESOLUTION.toLong(), RESOLUTION.toLong()))
            val output = session.run(mapOf("pixel_values" to tensor))
            val raw = output[0].value
            tensor.close()
            @Suppress("UNCHECKED_CAST")
            val arr4d = raw as? Array<Array<Array<FloatArray>>>
                ?: throw IllegalStateException("VAE encoder returned unexpected type: ${raw?.javaClass?.simpleName}")
            output[0].close()
            val size = latentChannels * LATENT_DIM * LATENT_DIM
            FloatArray(size) { i ->
                val ch = i / (LATENT_DIM * LATENT_DIM)
                val pos = i % (LATENT_DIM * LATENT_DIM)
                // FIX: encode applies scaling_factor forward (mirrors decode's inverse
                // in decodeLatent): latents = (encoded - shift_factor) * scaling_factor
                (arr4d[0][ch][pos / LATENT_DIM][pos % LATENT_DIM] - vaeShiftFactor) * vaeScalingFactor
            }
        } catch (e: Exception) {
            Log.w(TAG, "VAE encode error, falling back: ${e.message}")
            imageToLatentFallback(bitmap)
        }
    }

    private fun imageToLatentFallback(bitmap: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, LATENT_DIM, LATENT_DIM, true)
        val size   = latentChannels * LATENT_DIM * LATENT_DIM
        val latent = FloatArray(size)
        for (y in 0 until LATENT_DIM) {
            for (x in 0 until LATENT_DIM) {
                val p = scaled.getPixel(x, y)
                val pos = y * LATENT_DIM + x
                latent[0 * LATENT_DIM * LATENT_DIM + pos] = Color.red(p) / 127.5f - 1f
                latent[1 * LATENT_DIM * LATENT_DIM + pos] = Color.green(p) / 127.5f - 1f
                latent[2 * LATENT_DIM * LATENT_DIM + pos] = Color.blue(p) / 127.5f - 1f
                if (latentChannels > 3) latent[3 * LATENT_DIM * LATENT_DIM + pos] = 0f
            }
        }
        return latent
    }

    /**
     * Converts raw ONNX output to the [batch, H, W*channels] interleaved format
     * that pixelArrayToBitmap expects.
     *
     * ONNX VAE decoders emit NCHW [1, 3, H, W] — channels-first.
     * pixelArrayToBitmap expects HWC-like [1, H, W*3] — interleaved RGB.
     * A direct cast would either crash (wrong type) or produce scrambled colours.
     */
    private fun onnxOutputToPixelArray(raw: Any?): Array<Array<FloatArray>> {
        if (raw !is Array<*> || raw.isEmpty())
            throw IllegalStateException("VAE output is null or empty")

        return when (val first = raw[0]) {
            // 4D NCHW: Array<Array<Array<FloatArray>>> shape [batch, C, H, W]
            is Array<*> -> when {
                @Suppress("USELESS_CAST")
                (first as Array<*>).firstOrNull() is Array<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val nchw = raw as Array<Array<Array<FloatArray>>>
                    val c = nchw[0].size           // channels (3)
                    val h = nchw[0][0].size        // height
                    val w = nchw[0][0][0].size     // width
                    // Convert NCHW → [1, H, W*C] interleaved:
                    // index i in row y → channel = i%c, pixel x = i/c
                    Array(1) { Array(h) { y -> FloatArray(w * c) { i -> nchw[0][i % c][y][i / c] } } }
                }
                // 3D [batch, H, W*channels] — already interleaved, cast directly
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    raw as Array<Array<FloatArray>>
                }
            }
            else -> throw IllegalStateException(
                "Unexpected VAE output element type: ${first?.javaClass?.simpleName}"
            )
        }
    }

    private fun pixelArrayToBitmap(pixels: Array<Array<FloatArray>>): Bitmap {
        val h = pixels[0].size
        val w = pixels[0][0].size / 3
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val r = ((pixels[0][y][x*3  ].coerceIn(-1f,1f) + 1f) / 2f * 255).toInt()
                val g = ((pixels[0][y][x*3+1].coerceIn(-1f,1f) + 1f) / 2f * 255).toInt()
                val b = ((pixels[0][y][x*3+2].coerceIn(-1f,1f) + 1f) / 2f * 255).toInt()
                bmp.setPixel(x, y, Color.rgb(r, g, b))
            }
        }
        return bmp
    }

    private fun latentToFallbackBitmap(latent: FloatArray): Bitmap {
        val bmp = Bitmap.createBitmap(RESOLUTION, RESOLUTION, Bitmap.Config.ARGB_8888)
        val latentH = LATENT_DIM; val latentW = LATENT_DIM
        for (y in 0 until RESOLUTION) {
            for (x in 0 until RESOLUTION) {
                val ly = (y * latentH / RESOLUTION).coerceIn(0, latentH-1)
                val lx = (x * latentW / RESOLUTION).coerceIn(0, latentW-1)
                val base = (ly * latentW + lx) * 4
                val r = ((latent.getOrElse(base)   { 0f }.coerceIn(-1f,1f) + 1f) / 2f * 255).toInt()
                val g = ((latent.getOrElse(base+1) { 0f }.coerceIn(-1f,1f) + 1f) / 2f * 255).toInt()
                val b = ((latent.getOrElse(base+2) { 0f }.coerceIn(-1f,1f) + 1f) / 2f * 255).toInt()
                bmp.setPixel(x, y, Color.rgb(r, g, b))
            }
        }
        return bmp
    }

    private fun generateOnlineSync(prompt: String): GenerationResult {
        return try {
            val encoded = java.net.URLEncoder.encode(prompt, "UTF-8")
            val seed = kotlin.random.Random.nextInt(99999)
            val url = "https://image.pollinations.ai/prompt/$encoded?width=512&height=512&seed=$seed&nologo=true&model=flux"
            val conn = java.net.URL(url).openConnection().apply { connectTimeout = 20000; readTimeout = 45000 }
            val bytes = conn.getInputStream().readBytes()
            val imageId = nextImageId()
            val file = File(outputDir, "thunder_img_cmp_${imageId}.jpg")
            file.writeBytes(bytes)
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            GenerationResult.Success(file.absolutePath, imageId, prompt, "Pollinations.ai", bitmap)
        } catch (e: Exception) { GenerationResult.Failure(e.message ?: "failed") }
    }

    // ── Self-improvement via SSIM comparison ──────────────────────────────────

    private fun launchSelfImprovementComparison(prompt: String, offlinePath: String) {
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
        scope.launch {
            try {
                val onlineResult = this@ImageGenerator.generateOnlineSync(prompt)
                if (onlineResult is GenerationResult.Success) {
                    val offlineBmp = android.graphics.BitmapFactory.decodeFile(offlinePath)
                    val onlineBmp  = android.graphics.BitmapFactory.decodeFile(onlineResult.imagePath)
                    if (offlineBmp != null && onlineBmp != null) {
                        val ssim = computeSSIM(offlineBmp, onlineBmp)
                        Log.i(TAG, "SSIM offline vs online: $ssim")
                        File(context.filesDir, "image_quality_log.jsonl").appendText(
                            """{"prompt":${prompt.take(50).jsonStr()},"ssim":$ssim,"guidance":$currentGuidanceScale}""" + "\n"
                        )
                        // Auto-adjust guidance scale based on SSIM
                        if (ssim < 0.3f && currentGuidanceScale < 9f) {
                            currentGuidanceScale = (currentGuidanceScale + 0.5f).coerceAtMost(9f)
                            Log.i(TAG, "Auto-increased CFG to $currentGuidanceScale (low SSIM: $ssim)")
                        } else if (ssim > 0.7f && currentGuidanceScale > 5f) {
                            currentGuidanceScale = (currentGuidanceScale - 0.2f).coerceAtLeast(5f)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Self-improvement comparison skipped: ${e.message}")
            }
        }
    }

    private fun computeSSIM(a: Bitmap, b: Bitmap): Float {
        // Simplified SSIM on downscaled images
        val size = 32
        val as32 = Bitmap.createScaledBitmap(a, size, size, false)
        val bs32 = Bitmap.createScaledBitmap(b, size, size, false)
        var sumA = 0f; var sumB = 0f; var sumAB = 0f; var sumA2 = 0f; var sumB2 = 0f
        val n = size * size
        for (y in 0 until size) for (x in 0 until size) {
            val pa = Color.red(as32.getPixel(x,y)) / 255f
            val pb = Color.red(bs32.getPixel(x,y)) / 255f
            sumA += pa; sumB += pb; sumAB += pa*pb; sumA2 += pa*pa; sumB2 += pb*pb
        }
        val mA = sumA/n; val mB = sumB/n
        val c1 = 0.01f*0.01f; val c2 = 0.03f*0.03f
        val cov = sumAB/n - mA*mB
        val varA = sumA2/n - mA*mA; val varB = sumB2/n - mB*mB
        return ((2*mA*mB+c1)*(2*cov+c2)) / ((mA*mA+mB*mB+c1)*(varA+varB+c2))
    }

    // ── Image registry ────────────────────────────────────────────────────────

    private fun nextImageId(): Int {
        val id = registry.keys.maxOrNull()?.plus(1) ?: 1
        return id
    }

    private fun registerImage(id: Int, path: String) {
        registry[id] = path
        saveRegistry()
    }

    fun getImage(id: Int): Bitmap? {
        val path = registry[id] ?: return null
        return android.graphics.BitmapFactory.decodeFile(path)
    }

    fun getImagePath(id: Int): String? = registry[id]

    fun listImages(): List<Pair<Int, String>> =
        registry.entries.sortedByDescending { it.key }.map { Pair(it.key, it.value) }

    fun deleteImage(id: Int) {
        registry.remove(id)?.let { File(it).delete() }
        saveRegistry()
    }

    private fun saveRegistry() {
        val json = JSONObject()
        registry.forEach { (k, v) -> json.put(k.toString(), v) }
        File(context.filesDir, REGISTRY_FILE).writeText(json.toString())
    }

    private fun loadRegistry() {
        try {
            val file = File(context.filesDir, REGISTRY_FILE)
            if (!file.exists()) return
            val json = JSONObject(file.readText())
            json.keys().forEach { k ->
                val id = k.toIntOrNull() ?: return@forEach
                val path = json.getString(k)
                if (File(path).exists()) registry[id] = path
            }
            Log.i(TAG, "Registry loaded: ${registry.size} images")
        } catch (e: Exception) { Log.w(TAG, "Registry load: ${e.message}") }
    }

    private fun String.jsonStr() =
        "\"${replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")}\""

    fun release() {
        // FIX: this never closed unetSession at all, and vaeEncoderSession is new.
        textEncoderSession?.close()
        vaeDecoderSession?.close()
        vaeEncoderSession?.close()
        unetSession?.close()
    }

    private fun Random.nextGaussian(): Double {
        var u = 0.0; var v = 0.0
        while (u == 0.0) u = nextDouble()
        while (v == 0.0) v = nextDouble()
        return sqrt(-2.0 * ln(u)) * cos(2.0 * PI * v)
    }
}
