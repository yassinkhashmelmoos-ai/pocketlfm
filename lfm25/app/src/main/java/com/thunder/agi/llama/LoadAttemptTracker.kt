package com.thunder.agi.llama

import java.io.File
import java.io.IOException

/**
 * Remembers whether the NATIVE model load survived - across process deaths.
 *
 * Why this exists: when native code aborts (GGML_ASSERT -> abort(), SIGSEGV, SIGKILL by the low-memory killer) no Java
 * handler runs and nothing is logged anywhere the user can reach. The old app therefore "just closed" on every launch,
 * forever, with no way to tell why. Now:
 *
 *  1. [begin] writes a small marker file right BEFORE the native call and [finished] deletes it right after. A marker
 *     that is still there at the next start means the previous attempt never came back - i.e. the process died inside
 *     native code. [plan] counts that as a crash.
 *  2. The native side appends a breadcrumb line to [tracePath] before each loading step, so we also know HOW FAR it got
 *     ("context create begin" = died while building the compute graph).
 *  3. After a crash the next attempt uses a gentler load profile (see load_profile.h): 0 normal -> 1 conservative -> 2
 *     minimal. This helps when the cause is memory or a CPU/driver quirk.
 *  4. After [MAX_CONSECUTIVE_CRASHES] crashes in a row loading is NOT retried automatically any more (a crash loop is the
 *     worst outcome: the app can never even show its error screen). The user gets the diagnosis and a Retry button.
 *
 * Plain JVM code (java.io only) so it can be unit-tested without a device.
 */
class LoadAttemptTracker(
    private val dir: File,
    /**
     * Identifies this build of the app (the app passes versionCode + lastUpdateTime). Evidence written by a DIFFERENT
     * build says nothing about this one - after installing a fix the app must get a clean chance, not stay blocked by
     * the crashes of the build it just replaced.
     */
    private val buildTag: String = "",
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    /** [profile] is what to pass to the native loader. [blockedReason] != null means: do not try, show this. */
    data class Plan(val profile: Int, val blockedReason: String?, val previousCrashNote: String?)

    /** A previous attempt that never finished. [startedMs] lets the startup report avoid showing the same crash twice. */
    data class CrashInfo(val text: String, val startedMs: Long)

    companion object {
        const val MAX_PROFILE = 2
        const val MAX_CONSECUTIVE_CRASHES = 3

        const val ATTEMPT_FILE = "engine_attempt.txt"
        const val STATE_FILE = "engine_state.txt"
        const val TRACE_FILE = "engine_trace.log"
    }

    private val attemptFile = File(dir, ATTEMPT_FILE)
    private val stateFile = File(dir, STATE_FILE)
    private val traceFile = File(dir, TRACE_FILE)

    /** Passed to native code; it appends one breadcrumb line per loading step. */
    val tracePath: String get() = traceFile.absolutePath

    private data class State(val crashes: Int, val goodProfile: Int, val note: String?, val build: String? = null)

    /** Written by a different build of the app? (A file without a build tag is never considered stale.) */
    private fun isStale(storedBuild: String?) = storedBuild != null && storedBuild != buildTag

    /**
     * Non-destructive: describes a previous attempt that never finished, or null. Used by the startup report so the
     * user sees the diagnosis even before anything tries to load.
     */
    fun peekPreviousCrash(): String? = previousCrashInfo()?.text

    fun previousCrashInfo(): CrashInfo? {
        if (!attemptFile.exists()) return null
        val kv = readKv(attemptFile)
        if (isStale(kv["build"])) return null
        return CrashInfo(describeCrash(kv["profile"]?.toIntOrNull() ?: 0), kv["started"]?.toLongOrNull() ?: 0L)
    }

    /** The note stored for the most recent counted crash (survives until a load succeeds). */
    fun lastCrashNote(): String? = readState().note

    fun consecutiveCrashes(): Int = readState().crashes

    /**
     * Decide how (and whether) to load. Call once per load attempt, before [begin].
     * [force] = the user explicitly pressed Retry: lift a crash-loop block (one more attempt at the gentlest profile).
     */
    fun plan(force: Boolean = false): Plan {
        var st = readState()
        var crashNote: String? = null

        // Evidence from another build of the app is not about this one: start clean.
        if (isStale(st.build)) {
            st = State(0, 0, null, buildTag)
            writeState(st)
        }

        if (attemptFile.exists()) {
            val marker = readKv(attemptFile)
            if (isStale(marker["build"])) {
                attemptFile.delete()
            } else {
                // The previous attempt never reached finished(): the process died inside the native load.
                val profile = marker["profile"]?.toIntOrNull() ?: 0
                crashNote = describeCrash(profile)
                st = State(st.crashes + 1, st.goodProfile, crashNote, buildTag)
                attemptFile.delete()
                writeState(st)
            }
        }

        if (force && st.crashes >= MAX_CONSECUTIVE_CRASHES) {
            st = State(MAX_CONSECUTIVE_CRASHES - 1, st.goodProfile, st.note, buildTag)
            writeState(st)
        }

        if (st.crashes >= MAX_CONSECUTIVE_CRASHES) {
            return Plan(
                profile = MAX_PROFILE,
                blockedReason = "The AI engine crashed ${st.crashes} times in a row while loading the model, so it will not " +
                    "start automatically any more (to avoid a crash loop).\n" +
                    (st.note?.let { "$it\n" } ?: "") +
                    "Tap Retry to try once more, or send this report to the developer.",
                previousCrashNote = crashNote ?: st.note
            )
        }

        val profile = minOf(maxOf(st.goodProfile, st.crashes), MAX_PROFILE)
        return Plan(profile, null, crashNote)
    }

    /** Call immediately BEFORE the native load. Truncates the breadcrumb file so its lines belong to this attempt only. */
    fun begin(profile: Int) {
        try { traceFile.writeText("") } catch (e: IOException) { /* tracing is best effort */ }
        // If this write fails we simply cannot detect a crash this time - never fail the load because of it.
        try {
            writeKvAtomic(attemptFile, mapOf("profile" to profile.toString(), "started" to now().toString(), "build" to buildTag))
        } catch (e: IOException) { }
    }

    /** Call immediately AFTER the native load returned (success or a normal failure code): the process survived. */
    fun finished(success: Boolean, profile: Int) {
        attemptFile.delete()
        if (success) writeState(State(crashes = 0, goodProfile = profile, note = null, build = buildTag))
    }

    /** Last breadcrumb lines (oldest first), without the leading timestamps. */
    fun traceTail(n: Int = 6): List<String> {
        if (!traceFile.exists()) return emptyList()
        return try {
            traceFile.readLines().filter { it.isNotBlank() }.takeLast(n).map { it.substringAfter(' ', it) }
        } catch (e: IOException) {
            emptyList()
        }
    }

    /** Forget everything (also used by tests). */
    fun reset() {
        attemptFile.delete(); stateFile.delete(); traceFile.delete()
    }

    private fun describeCrash(profile: Int): String {
        val stage = traceTail(1).firstOrNull()
        return "The previous start died while loading the model (safe-mode level $profile" +
            (if (stage != null) ", last step reached: \"$stage\"" else "") + ")."
    }

    // ── tiny key=value persistence (atomic write) ────────────────────────────────────────────────────────────────
    private fun readState(): State {
        val kv = readKv(stateFile)
        // Garbage must never push a phone into safe mode: out-of-range / unparsable values fall back to the defaults.
        val good = kv["good"]?.toIntOrNull()
        return State(
            crashes = (kv["crashes"]?.toIntOrNull() ?: 0).coerceAtLeast(0),
            goodProfile = if (good != null && good in 0..MAX_PROFILE) good else 0,
            note = kv["note"]?.takeIf { it.isNotBlank() },
            build = kv["build"]
        )
    }

    private fun writeState(s: State) {
        try {
            writeKvAtomic(stateFile, mapOf(
                "crashes" to s.crashes.toString(),
                "good" to s.goodProfile.toString(),
                "note" to (s.note ?: "").replace('\n', ' '),
                "build" to (s.build ?: buildTag)
            ))
        } catch (e: IOException) { /* best effort */ }
    }

    private fun readKv(f: File): Map<String, String> {
        if (!f.exists()) return emptyMap()
        return try {
            f.readLines().mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
            }.toMap()
        } catch (e: IOException) {
            emptyMap()
        }
    }

    @Throws(IOException::class)
    private fun writeKvAtomic(f: File, kv: Map<String, String>) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(kv.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n")
        if (!tmp.renameTo(f)) {
            f.delete()
            if (!tmp.renameTo(f)) throw IOException("Cannot write ${f.name}")
        }
    }
}
