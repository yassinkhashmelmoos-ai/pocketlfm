package com.thunder.agi.intelligence

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlin.math.max
import kotlin.math.min

/**
 * TinyRL — a minimal reinforcement learning signal for on-device sampling.
 *
 * Rather than updating weights (which requires backprop + GPU training),
 * TinyRL adjusts the inference hyperparameters (temperature, top-p, rep penalty)
 * based on accumulated thumbs-up / thumbs-down feedback.
 *
 * The reward signal shifts the sampling distribution:
 *  - Good response → slightly lower temp (model was on track, reinforce)
 *  - Bad response  → slightly higher temp + lower rep penalty (explore more)
 *
 * Topic-aware: stores per-topic adjustments so the model behaves differently
 * for coding vs creative writing vs factual questions.
 */
class TinyRL(context: Context) {

    companion object {
        private const val TAG = "TinyRL"
        private const val PREFS = "tinyrl_prefs"

        // Bounds
        private const val TEMP_MIN  = 0.15f
        private const val TEMP_MAX  = 0.95f
        private const val TOP_P_MIN = 0.70f
        private const val TOP_P_MAX = 0.98f
        private const val REP_MIN   = 1.05f
        private const val REP_MAX   = 1.30f

        // Learning rate — small so one bad response doesn't ruin everything
        private const val LR = 0.03f
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class SamplingParams(val temperature: Float, val topP: Float, val repPenalty: Float)

    fun getParams(topic: String = "general"): SamplingParams {
        val key = topic.lowercase().take(32)
        return SamplingParams(
            temperature = prefs.getFloat("${key}_temp", 0.40f),
            topP        = prefs.getFloat("${key}_top_p", 0.92f),
            repPenalty  = prefs.getFloat("${key}_rep",   1.15f)
        )
    }

    /**
     * Apply reward signal.
     * good=true  → lower temp (more focused), raise top_p slightly
     * good=false → raise temp (more exploratory), lower rep penalty
     */
    fun applyFeedback(topic: String, good: Boolean) {
        val key    = topic.lowercase().take(32)
        val params = getParams(topic)

        val newTemp = if (good)
            (params.temperature - LR).coerceIn(TEMP_MIN, TEMP_MAX)
        else
            (params.temperature + LR).coerceIn(TEMP_MIN, TEMP_MAX)

        val newTopP = if (good)
            (params.topP + LR * 0.5f).coerceIn(TOP_P_MIN, TOP_P_MAX)
        else
            (params.topP - LR * 0.5f).coerceIn(TOP_P_MIN, TOP_P_MAX)

        val newRep = if (good)
            (params.repPenalty + LR * 0.3f).coerceIn(REP_MIN, REP_MAX)
        else
            (params.repPenalty - LR * 0.3f).coerceIn(REP_MIN, REP_MAX)

        prefs.edit()
            .putFloat("${key}_temp",  newTemp)
            .putFloat("${key}_top_p", newTopP)
            .putFloat("${key}_rep",   newRep)
            .apply()

        Log.i(TAG, "Feedback[$topic] good=$good → temp=$newTemp top_p=$newTopP rep=$newRep")
    }

    /** Classify the topic from user text for per-topic RL */
    fun classifyTopic(userText: String): String {
        val lower = userText.lowercase()
        return when {
            lower.contains(Regex("code|function|program|bug|kotlin|python|java|script")) -> "coding"
            lower.contains(Regex("write|story|poem|creative|imagine|fiction")) -> "creative"
            lower.contains(Regex("what|who|when|where|define|explain|how does")) -> "factual"
            lower.contains(Regex("translate|arabic|english|ترجم|عربي")) -> "translation"
            lower.contains(Regex("math|calculate|solve|equation|number")) -> "math"
            else -> "general"
        }
    }

    fun resetAll() {
        prefs.edit().clear().apply()
        Log.i(TAG, "RL state reset")
    }
}
