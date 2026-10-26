package com.thunder.agi.subscription

import android.content.Context
import com.thunder.agi.license.LicenseValidator
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * "May the model run right now?", asked at the moment the user presses send.
 *
 * The app opens for everyone and the chat history is always readable; only RUNNING the model needs access.
 * Access is any of: a valid license key, a verified-receipt month, a reset-key month, or admin.
 *
 *  - Subscription state is read live from SharedPreferences (cheap), so a month that ends while the app is
 *    open is noticed on the very next send.
 *  - LicenseValidator.check() is NOT called here: it rebuilds a MasterKey + EncryptedSharedPreferences every
 *    time (a slow Keystore operation) and has no error handling, which is not something to do on the main
 *    thread on every tap. MainActivity runs it, and hands the RESULT over through [updateLicense].
 */
object AccessGate {
    private const val DAY_MS = 24L * 60 * 60 * 1000

    @Volatile private var licenseValidUntil = 0L

    /** Call whenever LicenseValidator.check()/activate() produces a new result. */
    fun updateLicense(info: LicenseValidator.LicenseInfo) {
        licenseValidUntil =
            if (info.status == LicenseValidator.Status.VALID) licenseValidUntilMillis(info.expiryDate) else 0L
    }

    /** Does the cached license result allow use at [now]? (Pure, so it can be unit-tested.) */
    internal fun licenseAllows(now: Long): Boolean = now < licenseValidUntil

    fun isUnlocked(context: Context, now: Long = System.currentTimeMillis()): Boolean =
        licenseAllows(now) || SubscriptionManager(context).isActive()

    /**
     * A license "expiring on YYYY-MM-DD" works through the whole of that day. This is exactly how
     * LicenseValidator itself decides VALID (days >= 0 means before the NEXT UTC midnight), so the
     * cached answer cannot disagree with it. 0 = unknown / malformed, i.e. not valid.
     */
    internal fun licenseValidUntilMillis(expiryDate: String): Long {
        return try {
            val f = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }
            val t = f.parse(expiryDate)?.time ?: return 0L
            t + DAY_MS
        } catch (e: Exception) {
            0L
        }
    }
}
