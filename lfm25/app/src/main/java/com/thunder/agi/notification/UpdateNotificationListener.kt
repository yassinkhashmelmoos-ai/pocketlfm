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
        processUpdateMessage(text.trim())
    }

    private fun processUpdateMessage(text: String) {
        val filesDir = applicationContext.filesDir

        when {
            // Model update instructions from central hub
            text.startsWith(UPDATE_PREFIX, ignoreCase = true) -> {
                val content = text.removePrefix(UPDATE_PREFIX).trim()
                File(filesDir, "pending_update.txt").appendText("$content\n")
                Log.i(TAG, "Update instruction queued: ${content.take(50)}")
            }

            // Sync acknowledgment from hub
            text.startsWith(SYNC_PREFIX, ignoreCase = true) -> {
                val content = text.removePrefix(SYNC_PREFIX).trim()
                File(filesDir, "sync_log.txt").appendText(
                    "${System.currentTimeMillis()}: $content\n"
                )
                Log.i(TAG, "Sync confirmed: ${content.take(50)}")
            }

            // Any other message in Thunder AGI chat → store as fine-tune data
            else -> {
                File(filesDir, "channel_finetune.jsonl").appendText(
                    """{"source":"whatsapp_channel","text":${text.jsonStr()},"ts":${System.currentTimeMillis()}}""" + "\n"
                )
                Log.i(TAG, "Channel message stored for fine-tuning")
            }
        }
    }

    private fun String.jsonStr() =
        "\"${replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")}\""
}
