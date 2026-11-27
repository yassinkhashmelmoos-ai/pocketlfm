package com.thunder.agi.llama

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Identifies this install of the app. Changes with every install/update of the APK, so crash evidence recorded by an
 * older build (see LoadAttemptTracker) never blocks a new one.
 */
@Suppress("DEPRECATION")
fun appBuildTag(context: Context): String = try {
    val pi = context.packageManager.getPackageInfo(context.packageName, 0)
    "${pi.versionCode}-${pi.lastUpdateTime}"
} catch (e: Exception) {
    ""
}

/** [AssetSource] backed by the app's [AssetManager]. */
class AndroidAssetSource(private val assets: AssetManager) : AssetSource {
    private val names: Set<String> by lazy { (assets.list("") ?: emptyArray<String>()).toSet() }

    override fun exists(name: String): Boolean = name in names

    // openFd() only works for assets stored uncompressed (the model is - see noCompress in build.gradle.kts);
    // for a compressed one there is no fd and the size is unknown.
    override fun sizeOf(name: String): Long =
        try { assets.openFd(name).use { it.length } } catch (e: Exception) { -1L }

    override fun open(name: String): InputStream = assets.open(name, AssetManager.ACCESS_STREAMING)
}

class ThunderModel private constructor() {

    companion object {
        private const val TAG = "ThunderModel"
        private const val MODEL_FILENAME = "model.gguf"

        const val DEFAULT_TEMPERATURE  = 0.7f
        const val DEFAULT_TOP_P        = 0.9f
        const val DEFAULT_TOP_K        = 40
        const val DEFAULT_REPEAT_PENALTY = 1.1f
        const val DEFAULT_MAX_TOKENS   = 512

        // Return codes of nativeLoadModel() - keep in sync with thunder::LoadResult in cpp/load_profile.h
        const val LOAD_OK             = 0
        const val LOAD_MODEL_FAILED   = 1
        const val LOAD_CONTEXT_FAILED = 2
        const val LOAD_EXCEPTION      = 3

        @Volatile private var instance: ThunderModel? = null
        fun getInstance() = instance ?: synchronized(this) {
            instance ?: ThunderModel().also { instance = it }
        }

        // `profile` (0 normal, 1 conservative, 2 minimal) and `tracePath` (breadcrumb file) are chosen by LoadAttemptTracker.
        @JvmStatic external fun nativeLoadModel(modelPath: String, profile: Int, tracePath: String): Int
        @JvmStatic external fun nativeGenerate(prompt: String, temperature: Float, topP: Float, topK: Int, repeatPenalty: Float, maxTokens: Int): String
        @JvmStatic external fun nativeResetContext()
        @JvmStatic external fun nativeUnloadModel()
        @JvmStatic external fun nativeGetSystemInfo(): String

        @Volatile private var nativeLibraryLoaded = false

        // The library used to be loaded in a static initializer, so any failure to load it (wrong ABI, missing
        // dependency, ...) was an UnsatisfiedLinkError thrown while the first screen was still being composed: the app
        // died before it could even show the activation window. Loading it here makes that a normal, reported failure.
        @Throws(UnsatisfiedLinkError::class)
        private fun ensureNativeLibrary() {
            if (nativeLibraryLoaded) return
            synchronized(this) {
                if (!nativeLibraryLoaded) {
                    System.loadLibrary("thunder-engine")
                    nativeLibraryLoaded = true
                }
            }
        }
    }

    // Track loaded model path so we NEVER reload the same file twice across ViewModel lifetimes
    @Volatile private var isModelLoaded = false
    @Volatile private var loadedModelPath: String? = null

    /** Why the most recent [loadModel] returned false - shown on the error screen. null = nothing has failed. */
    @Volatile var lastError: String? = null
        private set

    // One load at a time. A second caller (e.g. a re-created ViewModel while the first load is still running) waits
    // here and then simply finds the model loaded, instead of copying/mmapping the same file concurrently.
    private val loadLock = Mutex()

    /**
     * Loads the model (copying it out of the APK on first run). Safe to call repeatedly and concurrently.
     * Returns false on failure; the reason is in [lastError]. [force] = the user pressed Retry: lifts the
     * "crashed 3 times in a row" block for one more attempt.
     */
    suspend fun loadModel(context: Context, force: Boolean = false): Boolean {
        val app = context.applicationContext
        return loadModel(app.filesDir, AndroidAssetSource(app.assets), force, appBuildTag(app))
    }

    /** Same, with the file system and asset source injected (this is what the desktop tests call). */
    suspend fun loadModel(filesDir: File, assets: AssetSource, force: Boolean = false, buildTag: String = ""): Boolean =
        withContext(Dispatchers.IO) {
            loadLock.withLock { loadModelLocked(filesDir, assets, force, buildTag) }
        }

    private fun loadModelLocked(filesDir: File, assets: AssetSource, force: Boolean, buildTag: String): Boolean {
        // Model persists in native memory — skip if already loaded
        if (isModelLoaded) { Log.i(TAG, "Model already loaded, skipping"); return true }
        lastError = null

        // 0. Did the previous start die inside native code? Then be gentler this time - or stop (no crash loops).
        val tracker = LoadAttemptTracker(filesDir, buildTag)
        val plan = tracker.plan(force)
        plan.previousCrashNote?.let { Log.w(TAG, it) }
        plan.blockedReason?.let { Log.e(TAG, it); lastError = it; return false }

        // 1. The model file: complete, verified, written atomically (see ModelFileInstaller)
        val modelFile = try {
            ModelFileInstaller(filesDir, assets, isValid = ::looksLikeGguf).ensureInstalled(MODEL_FILENAME)
        } catch (e: ModelInstallException) {
            Log.e(TAG, "Model install failed", e)
            lastError = e.message
            return false
        }
        Log.i(TAG, "Loading model from ${modelFile.absolutePath} (${modelFile.length() / 1024 / 1024} MB)")

        // 2. The native engine library
        try {
            ensureNativeLibrary()
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Cannot load libthunder-engine.so", e)
            lastError = "The AI engine library could not be loaded on this device (${e.message})."
            return false
        }

        // 3. The native load. A normal failure (memory, bad file) comes back as a code and we try gentler settings right
        //    away; a native ABORT does not come back at all - the tracker notices that on the next start.
        var profile = plan.profile
        var code: Int
        while (true) {
            tracker.begin(profile)
            code = try {
                nativeLoadModel(modelFile.absolutePath, profile, tracker.tracePath)
            } catch (e: UnsatisfiedLinkError) {
                tracker.finished(false, profile)
                Log.e(TAG, "nativeLoadModel does not match the native library", e)
                lastError = "The AI engine library does not match this app version (${e.message}). Please reinstall the app."
                return false
            } catch (e: Throwable) {
                Log.e(TAG, "nativeLoadModel threw", e)
                LOAD_EXCEPTION
            }
            tracker.finished(code == LOAD_OK, profile)

            val retryable = code == LOAD_CONTEXT_FAILED || code == LOAD_EXCEPTION
            if (code == LOAD_OK || !retryable || profile >= LoadAttemptTracker.MAX_PROFILE) break
            profile++
            Log.w(TAG, "Load failed (code $code); retrying with safe-mode level $profile")
        }

        if (code == LOAD_OK) {
            isModelLoaded = true
            loadedModelPath = modelFile.absolutePath
            return true
        }

        if (code == LOAD_MODEL_FAILED) removeInstalledCopy(filesDir)   // the next Retry extracts a fresh copy
        lastError = describeFailure(code, tracker)
        Log.e(TAG, "Model load failed: $lastError")
        return false
    }

    suspend fun generate(
        prompt: String,
        temperature: Float = DEFAULT_TEMPERATURE,
        topP: Float = DEFAULT_TOP_P,
        topK: Int = DEFAULT_TOP_K,
        repeatPenalty: Float = DEFAULT_REPEAT_PENALTY,
        maxTokens: Int = DEFAULT_MAX_TOKENS
    ): String = withContext(Dispatchers.Default) {
        if (!isModelLoaded) return@withContext "Error: model not loaded."
        try {
            nativeGenerate(prompt, temperature, topP, topK, repeatPenalty, maxTokens)
        } catch (e: Exception) {
            Log.e(TAG, "Generate error", e)
            "Error: ${e.message}"
        }
    }

    // FIX: this crosses into native code and blocks on the same mutex nativeGenerate()
    // holds for the whole duration of a reply. It used to be a plain function, so it
    // ran on whatever thread called it — which, at both call sites in ChatViewModel,
    // was the main/UI thread, freezing the app if a generation was still in flight.
    // Dispatching here (matching generate()'s own Dispatchers.Default above) fixes it
    // for every current and future caller in one place.
    suspend fun resetContext() = withContext(Dispatchers.Default) {
        if (isModelLoaded) nativeResetContext()
    }

    // Only called when truly shutting down the app process
    fun unload() {
        if (isModelLoaded) {
            nativeUnloadModel()
            isModelLoaded = false
            loadedModelPath = null
        }
    }

    fun isLoaded() = isModelLoaded

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────────────
    private fun describeFailure(code: Int, tracker: LoadAttemptTracker): String {
        val what = when (code) {
            LOAD_MODEL_FAILED   -> "The model file could not be read (damaged or not supported by this engine). It will be extracted again on Retry."
            LOAD_CONTEXT_FAILED -> "The engine could not allocate memory for the model, even in safe mode. Close other apps and tap Retry."
            else                -> "The AI engine hit an internal error while loading the model."
        }
        val steps = tracker.traceTail(4).joinToString(" → ")
        return if (steps.isEmpty()) what else "$what\n[$steps]"
    }

    private fun removeInstalledCopy(filesDir: File) {
        File(filesDir, MODEL_FILENAME).delete()
        File(filesDir, MODEL_FILENAME + ModelFileInstaller.MARKER_SUFFIX).delete()
    }

    /** True if the file starts with the GGUF magic. Cheap (4 bytes), catches HTML error pages / wrong files. */
    private fun looksLikeGguf(f: File): Boolean = try {
        RandomAccessFile(f, "r").use { raf ->
            val magic = ByteArray(4)
            raf.readFully(magic)
            String(magic, Charsets.US_ASCII) == "GGUF"
        }
    } catch (e: Exception) {
        false
    }
}
