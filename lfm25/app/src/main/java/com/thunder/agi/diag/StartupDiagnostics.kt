package com.thunder.agi.diag

import android.annotation.TargetApi
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.StatFs
import android.util.Log
import com.thunder.agi.llama.LoadAttemptTracker
import com.thunder.agi.llama.appBuildTag
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Why did the app close last time?" - answered at the next start, in the app, with text the user can copy.
 *
 * Until now a native abort (GGML_ASSERT -> abort(), SIGSEGV, OOM kill) left no trace anywhere the user could reach:
 * no Java handler runs for it, and nothing was installed for Java crashes either. This gathers three independent
 * sources, each reported at most once and only if it belongs to THIS install of the app:
 *
 *  1. the engine's own breadcrumb ("died while loading the model, last step: context create begin"),
 *  2. Android's record of how the process ended (ApplicationExitInfo, API 30+): native crash + signal, low-memory kill,
 *     ANR, ... including how much memory the process held,
 *  3. the last uncaught Java exception saved by CrashReporter.
 *
 * Blocking file/system reads: call from a background thread.
 */
object StartupDiagnostics {
    private const val TAG = "StartupDiagnostics"
    private const val PREFS = "thunder_diag"
    private const val KEY_SHOWN_UNTIL = "shown_until_ms"
    private const val KEY_ENGINE_SHOWN = "engine_started_shown"
    private const val MAX_JAVA_CRASH_CHARS = 1800

    /** The report to show, or null when the previous run ended normally / was already reported. Never throws. */
    fun collect(context: Context): String? {
        val app = context.applicationContext
        return try { collectUnsafe(app) } catch (t: Throwable) { Log.w(TAG, "diagnostics failed", t); null }
    }

    private fun collectUnsafe(app: Context): String? {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val since = maxOf(prefs.getLong(KEY_SHOWN_UNTIL, 0L), installTime(app))   // only events of THIS install, once
        val sections = ArrayList<String>()
        val editor = prefs.edit()

        // 1. the engine's breadcrumb: a native death while loading the model
        val engine = LoadAttemptTracker(app.filesDir, appBuildTag(app)).previousCrashInfo()
        if (engine != null && engine.startedMs > prefs.getLong(KEY_ENGINE_SHOWN, 0L)) {
            sections += "[Engine] ${engine.text}"
            editor.putLong(KEY_ENGINE_SHOWN, engine.startedMs)
        }

        // 2. how Android says the process ended (ApplicationExitInfo exists from API 30)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) sections += abnormalExits(app, since)

        // 3. an uncaught Java exception saved by CrashReporter
        val latest = File(app.filesDir, "crash_reports").listFiles()?.maxByOrNull { it.lastModified() }
        if (latest != null && latest.lastModified() > since) {
            sections += "[Java crash]\n" + latest.readText().trim().take(MAX_JAVA_CRASH_CHARS)
        }

        if (sections.isEmpty()) return null
        editor.putLong(KEY_SHOWN_UNTIL, now).apply()
        return header(app) + "\n\n" + sections.joinToString("\n\n")
    }

    // ── Android's own record of how the previous process ended (API 30+) ───────────────────────────────────────────
    @TargetApi(Build.VERSION_CODES.R)
    private fun abnormalExits(app: Context, since: Long): List<String> {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return emptyList()
        val infos = am.getHistoricalProcessExitReasons(app.packageName, 0, 5)
        return infos.filter { it.timestamp > since && isAbnormal(it.reason) }.map { describe(it) }
    }

    @TargetApi(Build.VERSION_CODES.R)
    private fun isAbnormal(reason: Int): Boolean = when (reason) {
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> true
        else -> false
    }

    @TargetApi(Build.VERSION_CODES.R)
    private fun describe(i: ApplicationExitInfo): String {
        val name = when (i.reason) {
            ApplicationExitInfo.REASON_CRASH -> "CRASH (uncaught Java exception)"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            ApplicationExitInfo.REASON_ANR -> "ANR (app not responding)"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY (killed by the system)"
            ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
            else -> "reason ${i.reason}"
        }
        val signal = if (i.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || i.reason == ApplicationExitInfo.REASON_SIGNALED)
            " · signal ${i.status} (${signalName(i.status)})" else ""
        val mem = " · rss ${i.rss / 1024} MB · pss ${i.pss / 1024} MB"
        val desc = i.description?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
        return "[Process exit] ${time(i.timestamp)} · $name$signal$mem$desc"
    }

    private fun signalName(n: Int): String = when (n) {
        4 -> "SIGILL"; 6 -> "SIGABRT"; 7 -> "SIGBUS"; 8 -> "SIGFPE"; 9 -> "SIGKILL"; 11 -> "SIGSEGV"; 15 -> "SIGTERM"
        else -> "?"
    }

    // ── header ───────────────────────────────────────────────────────────────────────────────────────────────────
    @Suppress("DEPRECATION")
    private fun header(app: Context): String {
        val pi = try { app.packageManager.getPackageInfo(app.packageName, 0) } catch (e: Exception) { null }
        val free = try { StatFs(app.filesDir.absolutePath).availableBytes / (1024 * 1024) } catch (e: Exception) { -1L }
        return "=== Thunder AGI startup report ===\n" +
            "App ${pi?.versionName ?: "?"} (${pi?.versionCode ?: "?"}) · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
            "${Build.MANUFACTURER} ${Build.MODEL} · ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}\n" +
            "Free storage: ${if (free >= 0) "$free MB" else "unknown"}"
    }

    private fun installTime(app: Context): Long = try {
        app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime
    } catch (e: Exception) { 0L }

    private fun time(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))
}
