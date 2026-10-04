package com.thunder.agi.intelligence

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * NightlyTrainer — schedules on-device self-supervised fine-tuning.
 *
 * What it actually does (no GPU training required):
 * 1. Reads feedback_log.jsonl (thumbs up/down pairs)
 * 2. Extracts good (prompt, response) pairs
 * 3. Builds a LoRA-style "soft prompt" file — a set of prefix tokens
 *    derived from the good examples that biases the model toward better outputs
 * 4. Saves this as a prompt cache file loaded at startup
 * 5. Exports the day's feedback log to a WhatsApp-readable format
 *
 * Real weight-level fine-tuning would need llama.cpp's train_text functionality
 * compiled in — that requires significantly more RAM and compute.
 * This is the realistic on-device equivalent.
 */
class NightlyTrainer(private val context: Context) {

    companion object {
        private const val TAG = "NightlyTrainer"
        private const val WORK_NAME = "thunder_agi_nightly"
        private const val CHANNEL_ID = "thunder_agi_training"
    }

    fun schedule() {
        val constraints = Constraints.Builder()
            .setRequiresCharging(true)           // only when plugged in
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)  // fully offline
            .build()

        val request = PeriodicWorkRequestBuilder<TrainingWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setInitialDelay(calculateDelayToMidnight(), TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
        Log.i(TAG, "Nightly training scheduled")
    }

    fun cancelSchedule() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    private fun calculateDelayToMidnight(): Long {
        val now = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            add(java.util.Calendar.DAY_OF_MONTH, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 2)   // 2 AM
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
        }
        return maxOf(cal.timeInMillis - now, 0L)
    }

    class TrainingWorker(context: Context, params: WorkerParameters) :
        CoroutineWorker(context, params) {

        override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
            Log.i(TAG, "Nightly training started")
            try {
                val filesDir = applicationContext.filesDir

                // 1. Read feedback log
                val feedbackFile = File(filesDir, "feedback_log.jsonl")
                if (!feedbackFile.exists() || feedbackFile.length() == 0L) {
                    Log.i(TAG, "No feedback data yet")
                    return@withContext Result.success()
                }

                val goodPairs = mutableListOf<Pair<String, String>>()
                feedbackFile.forEachLine { line ->
                    if (line.contains("\"good\":true")) {
                        val prompt   = extractJsonField(line, "prompt")
                        val response = extractJsonField(line, "response")
                        if (prompt.isNotBlank() && response.isNotBlank())
                            goodPairs.add(Pair(prompt, response))
                    }
                }
                Log.i(TAG, "Found ${goodPairs.size} good training pairs")

                // 2. Build soft-prompt cache from good examples
                // This is a prefix context file that gets prepended to each conversation.
                // It effectively "shows" the model examples of good behavior before each conversation.
                if (goodPairs.isNotEmpty()) {
                    val softPrompt = buildSoftPrompt(goodPairs.takeLast(5))
                    File(filesDir, "soft_prompt_cache.txt").writeText(softPrompt)
                    Log.i(TAG, "Soft prompt cache updated with ${goodPairs.size} examples")
                }

                // 3. Prepare daily export (for WhatsApp sync channel)
                val exportFile = prepareExport(filesDir, goodPairs.size)
                Log.i(TAG, "Export ready: ${exportFile.name}")

                // 4. Archive processed feedback, keep last 500 lines
                archiveFeedback(feedbackFile)

                // 5. Update training stats
                File(filesDir, "training_stats.json").writeText(
                    """{"last_run":${System.currentTimeMillis()},"good_pairs":${goodPairs.size},"status":"ok"}"""
                )

                Result.success()
            } catch (e: Exception) {
                Log.e(TAG, "Training failed", e)
                Result.retry()
            }
        }

        private fun buildSoftPrompt(pairs: List<Pair<String, String>>): String {
            val sb = StringBuilder()
            sb.append("<!-- Thunder AGI learned examples -->\n")
            pairs.forEach { (prompt, response) ->
                sb.append("<|user|>\n$prompt<|endoftext|>\n")
                sb.append("<|assistant|>\n$response<|endoftext|>\n")
            }
            return sb.toString()
        }

        private fun prepareExport(filesDir: File, pairCount: Int): File {
            val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Date())
            val exportFile = File(filesDir, "daily_export_$date.txt")
            exportFile.writeText(
                "Thunder AGI Daily Report $date\n" +
                "Good feedback pairs collected: $pairCount\n" +
                "Device: ${android.os.Build.MODEL}\n" +
                "Status: Ready for sync"
            )
            return exportFile
        }

        private fun archiveFeedback(feedbackFile: File) {
            val lines = feedbackFile.readLines()
            if (lines.size > 500) {
                feedbackFile.writeText(lines.takeLast(500).joinToString("\n") + "\n")
            }
        }

        private fun extractJsonField(json: String, field: String): String {
            val pattern = Regex(""""$field"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            return pattern.find(json)?.groupValues?.get(1)
                ?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: ""
        }
    }
}
