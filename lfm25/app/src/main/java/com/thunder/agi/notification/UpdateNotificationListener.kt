package com.thunder.agi.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.io.File

/**
 * UpdateNotificationListener — reads WhatsApp notifications from the
 * dedicated "Thunder AGI" update channel only.
 *
 * HOW IT WORKS:
 * 1. User creates a WhatsApp group/chat named exactly "Thunder AGI"
 * 2. This service listens ONLY for notifications from that specific chat
 * 3. Messages in that chat are treated as model update instructions or
 *    fine-tuning data synced from the central hub
 * 4. All other WhatsApp chats are completely ignored
 *
 * PERMISSION: Android requires the user to explicitly enable notification
 * access in Settings → Apps → Special app access → Notification access.
 * This cannot be granted silently — the user must do it manually.
 * We direct them there if not granted.
 *
 * PRIVACY: Only "Thunder AGI" chat is processed. All other notifications
 * are immediately discarded without reading.
 */
class UpdateNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "ThunderAGI_Notif"
        private const val WHATSAPP_PKG = "com.whatsapp"
        private const val WHATSAPP_BUSINESS_PKG = "com.whatsapp.w4b"
        private const val THUNDER_CHAT_NAME = "Thunder AGI"
        private const val UPDATE_PREFIX = "[UPDATE]"
        private const val SYNC_PREFIX = "[SYNC]"

        fun isPermissionGranted(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            return flat.contains(context.packageName)
        }

        fun openPermissionSettings(context: Context) {
            context.startActivity(
                Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        // Only process WhatsApp notifications
        if (sbn.packageName != WHATSAPP_PKG && sbn.packageName != WHATSAPP_BUSINESS_PKG) return

        val extras = sbn.notification?.extras ?: return
        val title   = extras.getString("android.title") ?: return
        val text    = extras.getCharSequence("android.text")?.toString() ?: return

        // ONLY process the Thunder AGI dedicated chat — ignore everything else
        if (!title.contains(THUNDER_CHAT_NAME, ignoreCase = true)) return

        Log.i(TAG, "Thunder AGI channel message received")

        // Thunder wallet: payments ("TX1:...") and bulk chunks ("⚡T:...") posted in the Thunder AGI chat
        if (com.thunder.agi.node.NotificationText.importThunderPayments(applicationContext, extras)) return

        processUpdateMessage(text.trim())
    }

    private fun processUpdateMessage(text: String) {
        when {
            // [UPDATE] → TinyRL params. Format: temp=0.72,top_p=0.88,rep=1.13,topic=general
            // These are applied immediately to SharedPreferences — TinyRL reads them on next inference.
            text.startsWith(UPDATE_PREFIX, ignoreCase = true) -> {
                val content = text.removePrefix(UPDATE_PREFIX).trim()
                applyTinyRLParams(content)
            }

            // [SYNC] → base64-gzipped JSONL fine-tune data.
            // Decompressed and appended to channel_finetune.jsonl for NightlyTrainer to read at 2AM.
            text.startsWith(SYNC_PREFIX, ignoreCase = true) -> {
                val content = text.removePrefix(SYNC_PREFIX).trim()
                applyCompressedData(content, applicationContext.filesDir)
            }

            // Plain text messages in the Thunder AGI chat → training data
            // (File-download notifications like "John sent a file" are ignored;
            //  WhatsAppFolderWatcher handles the actual files)
            else -> {
                Log.i(TAG, "Plain message in Thunder AGI chat — ignored (file watcher handles files)")
            }
        }
    }

    /**
     * Parse and immediately apply TinyRL hyperparameters.
     * Format: temp=0.72,top_p=0.88,rep=1.13,topic=general
     * Values are clamped to safe ranges before storing.
     */
    private fun applyTinyRLParams(content: String) {
        try {
            val map = content.split(",").associate {
                val parts = it.trim().split("=")
                parts[0].trim() to parts[1].trim()
            }
            val topic = map["topic"] ?: "general"
            val key   = topic.lowercase().take(32)

            applicationContext
                .getSharedPreferences("tinyrl_prefs", Context.MODE_PRIVATE)
                .edit().apply {
                    map["temp"]?.toFloatOrNull()
                        ?.coerceIn(0.15f, 0.95f)?.let { putFloat("${key}_temp",  it) }
                    map["top_p"]?.toFloatOrNull()
                        ?.coerceIn(0.70f, 0.98f)?.let { putFloat("${key}_top_p", it) }
                    map["rep"]?.toFloatOrNull()
                        ?.coerceIn(1.05f, 1.30f)?.let { putFloat("${key}_rep",   it) }
                }.apply()

            Log.i(TAG, "TinyRL params applied: $content")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply TinyRL params: ${e.message}")
        }
    }

    /**
     * Decompress base64-gzipped JSONL data and append to channel_finetune.jsonl.
     * NightlyTrainer reads this file automatically at 2AM.
     */
    private fun applyCompressedData(b64: String, filesDir: File) {
        try {
            val bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
            val text  = java.util.zip.GZIPInputStream(
                java.io.ByteArrayInputStream(bytes)
            ).bufferedReader(Charsets.UTF_8).readText()

            File(filesDir, "channel_finetune.jsonl").appendText(text + "\n")
            Log.i(TAG, "Sync data applied: ${text.lines().size} lines added to channel_finetune.jsonl")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decompress sync data: ${e.message}")
        }
    }

    private fun String.jsonStr() =
        "\"${replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")}\""
}
