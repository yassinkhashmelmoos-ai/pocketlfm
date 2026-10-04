package com.thunder.agi.agent

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * CrashReporter — catches all uncaught exceptions and saves them locally.
 * User can view and copy the report from within the app to send to developer.
 * No data is sent anywhere automatically — fully private.
 */
class CrashReporter(private val context: Context) {

    companion object {
        private const val TAG = "CrashReporter"
        private const val MAX_REPORTS = 20

        fun install(context: Context): CrashReporter {
            val reporter = CrashReporter(context)
            reporter.install()
            return reporter
        }
    }

    private val crashDir = File(context.filesDir, "crash_reports").also { it.mkdirs() }

    fun install() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                saveCrash(thread, throwable)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save crash", e)
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
        Log.i(TAG, "Crash reporter installed. Reports saved to: ${crashDir.absolutePath}")
    }

    private fun saveCrash(thread: Thread, throwable: Throwable) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val file = File(crashDir, "crash_$timestamp.txt")

        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))

        val report = buildString {
            appendLine("=== Thunder AGI Crash Report ===")
            appendLine("Time: $timestamp")
            appendLine("Thread: ${thread.name}")
            appendLine()
            appendLine("=== Device ===")
            appendLine("Model: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.firstOrNull()}")
            appendLine()
            appendLine("=== Exception ===")
            appendLine(sw.toString())
        }

        file.writeText(report)
        Log.e(TAG, "Crash saved: ${file.name}")

        // Keep only latest MAX_REPORTS
        val files = crashDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_REPORTS).forEach { it.delete() }
    }

    fun getLatestCrash(): String? {
        val latest = crashDir.listFiles()
            ?.maxByOrNull { it.lastModified() }
            ?: return null
        return latest.readText()
    }

    fun getAllCrashes(): List<Pair<String, String>> {
        return (crashDir.listFiles() ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .take(10)
            .map { Pair(it.name, it.readText()) }
    }

    fun clearAll() {
        crashDir.listFiles()?.forEach { it.delete() }
    }
}
