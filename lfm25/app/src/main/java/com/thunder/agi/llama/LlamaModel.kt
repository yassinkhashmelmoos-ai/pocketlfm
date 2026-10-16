package com.thunder.agi.llama

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class ThunderModel private constructor() {

    companion object {
        private const val TAG = "ThunderModel"
        private const val MODEL_FILENAME = "model.gguf"

        const val DEFAULT_TEMPERATURE  = 0.7f
        const val DEFAULT_TOP_P        = 0.9f
        const val DEFAULT_TOP_K        = 40
        const val DEFAULT_REPEAT_PENALTY = 1.1f
        const val DEFAULT_MAX_TOKENS   = 512

        @Volatile private var instance: ThunderModel? = null
        fun getInstance() = instance ?: synchronized(this) {
            instance ?: ThunderModel().also { instance = it }
        }

        @JvmStatic external fun nativeLoadModel(modelPath: String): Boolean
        @JvmStatic external fun nativeGenerate(prompt: String, temperature: Float, topP: Float, topK: Int, repeatPenalty: Float, maxTokens: Int): String
        @JvmStatic external fun nativeResetContext()
        @JvmStatic external fun nativeUnloadModel()
        @JvmStatic external fun nativeGetSystemInfo(): String

        init {
            System.loadLibrary("thunder-engine")
        }
    }

    // Track loaded model path so we NEVER reload the same file twice across ViewModel lifetimes
    private var isModelLoaded = false
    private var loadedModelPath: String? = null

    suspend fun loadModel(context: Context): Boolean = withContext(Dispatchers.IO) {
        // Model persists in native memory — skip if already loaded
        if (isModelLoaded) { Log.i(TAG, "Model already loaded, skipping"); return@withContext true }

        val modelFile = resolveModelFile(context) ?: return@withContext false
        Log.i(TAG, "Loading model from ${modelFile.absolutePath} (${modelFile.length()/1024/1024} MB)")

        val ok = nativeLoadModel(modelFile.absolutePath)
        if (ok) { isModelLoaded = true; loadedModelPath = modelFile.absolutePath }
        ok
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

    private fun resolveModelFile(context: Context): File? {
        // 1. Already extracted to internal storage?
        val internal = File(context.filesDir, MODEL_FILENAME)
        if (internal.exists() && internal.length() > 50_000_000L) return internal

        // 2. In assets?
        val assets = context.assets.list("") ?: emptyArray()
        if (MODEL_FILENAME !in assets) {
            Log.e(TAG, "model.gguf not found in assets")
            return null
        }

        // 3. Copy from assets (only once)
        Log.i(TAG, "Copying model from assets…")
        return try {
            context.assets.open(MODEL_FILENAME).use { input ->
                FileOutputStream(internal).use { input.copyTo(it) }
            }
            internal
        } catch (e: Exception) {
            Log.e(TAG, "Copy failed", e)
            internal.delete()
            null
        }
    }
}
