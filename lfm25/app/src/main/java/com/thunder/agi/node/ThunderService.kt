package com.thunder.agi.node

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.mutableStateOf

/**
 * Keeps the wallet node alive in the background so payments can arrive while the app is closed.
 * Opt-in (switch in the wallet screen) because Android requires a visible notification for it and it uses
 * battery for Bluetooth scanning. Declared in the manifest with foregroundServiceType="connectedDevice".
 */
class ThunderService : Service() {
    companion object {
        private const val CHANNEL = "thunder_node"
        private const val NOTIF_ID = 4711
        private const val PREF = "thunder_service"

        fun isEnabled(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("enabled", false)

        fun setEnabled(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("enabled", on).apply()
            val i = Intent(ctx, ThunderService::class.java)
            if (on) ctx.startForegroundService(i) else ctx.stopService(i)
        }

        /** Call from the activity (foreground) on launch: restores the service if the user had enabled it. */
        fun restoreIfEnabled(ctx: Context) { if (isEnabled(ctx)) try { ctx.startForegroundService(Intent(ctx, ThunderService::class.java)) } catch (_: Exception) {} }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Thunder wallet (background)", NotificationManager.IMPORTANCE_LOW))
        val open = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Thunder wallet is online")
            .setContentText("Listening for nearby payments")
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(NOTIF_ID, n)
        } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        val appCtx = applicationContext
        Thread { ThunderRuntime.get(appCtx).start() }.start()      // key generation / socket binding off the main thread
        return START_STICKY
    }
}

/** Tiny navigation flag so the existing settings panel can open the wallet without threading callbacks through ChatScreen. */
object ThunderNav {
    val showWallet = mutableStateOf(false)
}

/** Runtime permissions needed for the short-range radios. Everything degrades gracefully if some are denied. */
object Perms {
    fun needed(): Array<String> {
        val l = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) { l.add(Manifest.permission.BLUETOOTH_SCAN); l.add(Manifest.permission.BLUETOOTH_ADVERTISE) }
        if (Build.VERSION.SDK_INT >= 33) { l.add(Manifest.permission.NEARBY_WIFI_DEVICES); l.add(Manifest.permission.POST_NOTIFICATIONS) }
        else l.add(Manifest.permission.ACCESS_FINE_LOCATION)
        return l.toTypedArray()
    }

    fun missing(ctx: Context): Array<String> =
        needed().filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
}
