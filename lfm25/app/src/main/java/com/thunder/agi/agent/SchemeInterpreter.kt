package com.thunder.agi.agent

import android.util.Log

/**
 * SchemeInterpreter — JNI wrapper for the embedded Scheme evaluator.
 *
 * The AI can write Scheme scripts to modify its own parameters,
 * test logic, or perform self-improvement calculations.
 *
 * Examples of what Thunder AGI can do with this:
 *   (define temperature 0.4)
 *   (if (> feedback-score 0.8) (define temperature 0.3) (define temperature 0.6))
 *   (display (string-append "New temp: " (number->string temperature)))
 */
object SchemeInterpreter {

    private const val TAG = "ThunderScheme"
    private var loaded = false

    init {
        try {
            System.loadLibrary("thunder-engine") // same .so, different JNI functions
            loaded = true
            Log.i(TAG, "Scheme interpreter ready")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load Scheme interpreter", e)
        }
    }

    @JvmStatic external fun nativeEval(code: String): String
    @JvmStatic external fun nativeDefine(name: String, value: String)
    @JvmStatic external fun nativeReset()

    fun eval(code: String): String {
        if (!loaded) return "Scheme interpreter not available"
        return try {
            nativeEval(code)
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    fun define(name: String, value: String) {
        if (!loaded) return
        try { nativeDefine(name, value) } catch (e: Exception) {}
    }

    fun reset() {
        if (!loaded) return
        try { nativeReset() } catch (e: Exception) {}
    }

    /** Run a self-modification script and return any output */
    fun runSelfModScript(script: String): String {
        Log.i(TAG, "Running self-mod script: ${script.take(100)}")
        return eval(script)
    }

    /** Predefined scripts for common self-modifications */
    fun adjustTemperature(feedbackScore: Float): String {
        return eval("""
            (define score $feedbackScore)
            (define new-temp
              (if (> score 0.8) 0.3
                (if (> score 0.5) 0.4
                  (if (> score 0.3) 0.55
                    0.7))))
            (display (string-append "temperature:" (number->string new-temp)))
        """.trimIndent())
    }

    fun calculateRepPenalty(repetitionCount: Int): String {
        return eval("""
            (define reps $repetitionCount)
            (define penalty (+ 1.1 (* reps 0.05)))
            (define capped (if (> penalty 1.5) 1.5 penalty))
            (display (string-append "rep_penalty:" (number->string capped)))
        """.trimIndent())
    }
}
