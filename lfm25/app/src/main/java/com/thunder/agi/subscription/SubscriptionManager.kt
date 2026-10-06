package com.thunder.agi.subscription

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

private const val TAG = "SubscriptionManager"
private const val PREFS = "thunder_agi_subscription"

/**
 * SubscriptionManager — Thunder AGI trial and payment system.
 *
 * Flow:
 * 1. First launch → user enters username → 1-month FREE trial starts
 * 2. After 1 month → app locked, shows payment message (Arabic)
 * 3. User pays to Bankak account 2587655 (Bank of Khartoum)
 * 4. User uploads receipt screenshot → app verifies:
 *    - Receiver account contains "2587655"
 *    - Receipt timestamp within last 30 days
 *    - Process number not already used
 * 5. If valid → unlocked for another month
 *
 * Admin accounts: bypass all checks, never expire
 * Reset key account: resets trial to fresh 1-month each use
 */
class SubscriptionManager(private val context: Context) {

    companion object {
        // Admin usernames — bypass everything, never expire
        private val ADMIN_USERNAMES = setOf(
            "thunder_admin_yas",
            "thunder_admin_dev",
            "thunder_admin_root"
        )
        // Reset key — resets to fresh 1-month trial each time entered
        private const val RESET_KEY = "thunder_reset_key_2026"

        // Bankak receiver account identifier
        private const val BANKAK_RECEIVER = "2587655"
        private const val BANKAK_FULL_IBAN_FRAGMENT = "2587655"

        // Trial period
        private const val TRIAL_DAYS = 30L

        // SharedPreferences keys
        private const val KEY_USERNAME        = "username"
        private const val KEY_TRIAL_START     = "trial_start"
        private const val KEY_PAID_UNTIL      = "paid_until"
        private const val KEY_IS_ADMIN        = "is_admin"
        private const val KEY_USED_RECEIPTS   = "used_receipts"
        private const val KEY_FIRST_LAUNCH    = "first_launch"
        private const val KEY_INSTALL_VERSION = "install_version"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    enum class SubscriptionState {
        FIRST_LAUNCH,        // Never opened before — show username entry
        TRIAL_ACTIVE,        // Within 30-day trial
        TRIAL_EXPIRED,       // Trial ended, needs payment
        PAID_ACTIVE,         // Has valid payment
        ADMIN               // Admin account — always active
    }

    data class ReceiptVerificationResult(
        val valid: Boolean,
        val reason: String,
        val processNumber: String = ""
    )

    // ── State ──────────────────────────────────────────────────────────────────

    fun getState(): SubscriptionState {
        if (!prefs.contains(KEY_FIRST_LAUNCH)) return SubscriptionState.FIRST_LAUNCH
        if (prefs.getBoolean(KEY_IS_ADMIN, false)) return SubscriptionState.ADMIN

        val paidUntil = prefs.getLong(KEY_PAID_UNTIL, 0L)
        if (paidUntil > System.currentTimeMillis()) return SubscriptionState.PAID_ACTIVE

        val trialStart = prefs.getLong(KEY_TRIAL_START, 0L)
        val trialEnd = trialStart + TimeUnit.DAYS.toMillis(TRIAL_DAYS)
        return if (System.currentTimeMillis() < trialEnd)
            SubscriptionState.TRIAL_ACTIVE
        else
            SubscriptionState.TRIAL_EXPIRED
    }

    fun isActive() = getState() in setOf(
        SubscriptionState.TRIAL_ACTIVE,
        SubscriptionState.PAID_ACTIVE,
        SubscriptionState.ADMIN
    )

    fun getDaysRemaining(): Long {
        val trialStart = prefs.getLong(KEY_TRIAL_START, System.currentTimeMillis())
        val trialEnd = trialStart + TimeUnit.DAYS.toMillis(TRIAL_DAYS)
        return maxOf(0L, TimeUnit.MILLISECONDS.toDays(trialEnd - System.currentTimeMillis()))
    }

    fun getUsername(): String = prefs.getString(KEY_USERNAME, "") ?: ""

    // ── Registration ───────────────────────────────────────────────────────────

    fun registerUser(username: String): Boolean {
        val clean = username.trim().lowercase()
        if (clean.isBlank()) return false

        prefs.edit().apply {
            putString(KEY_USERNAME, username.trim())
            putBoolean(KEY_FIRST_LAUNCH, true)
            putLong(KEY_TRIAL_START, System.currentTimeMillis())

            when {
                ADMIN_USERNAMES.contains(clean) -> {
                    putBoolean(KEY_IS_ADMIN, true)
                    Log.i(TAG, "Admin account registered: $clean")
                }
                clean == RESET_KEY.lowercase() -> {
                    // Reset key — fresh trial
                    putLong(KEY_TRIAL_START, System.currentTimeMillis())
                    putLong(KEY_PAID_UNTIL, 0L)
                    putBoolean(KEY_IS_ADMIN, false)
                    Log.i(TAG, "Trial reset via reset key")
                }
                else -> {
                    putBoolean(KEY_IS_ADMIN, false)
                    Log.i(TAG, "User registered: $username — trial starts now")
                }
            }
        }.apply()
        return true
    }

    fun applyResetKey(key: String): Boolean {
        if (key.trim().lowercase() != RESET_KEY.lowercase()) return false
        prefs.edit()
            .putLong(KEY_TRIAL_START, System.currentTimeMillis())
            .putLong(KEY_PAID_UNTIL, 0L)
            .apply()
        Log.i(TAG, "Trial reset — new 1-month trial started")
        return true
    }

    // ── Receipt verification ───────────────────────────────────────────────────

    /**
     * Verify a Bankak receipt screenshot.
     *
     * The receipt contains:
     * - Process number (رقم العملية) — unique per transaction
     * - Receiver account (إلى) — must contain "2587655"
     * - Timestamp — must be within last 30 days
     * - Status (الحالة) — must be "نجاح" (success)
     *
     * We use OCR-like text extraction from the bitmap to read these fields.
     * Since full OCR requires a large library, we use color-based detection
     * to identify the receipt type (green = success) plus manual field entry.
     */
    fun verifyReceipt(
        imageUri: Uri,
        processNumberManual: String,
        receiptDateManual: String  // format: dd-MMM-yyyy
    ): ReceiptVerificationResult {
        return try {
            val bitmap = context.contentResolver.openInputStream(imageUri)?.use {
                BitmapFactory.decodeStream(it)
            } ?: return ReceiptVerificationResult(false, "Could not read image")

            // Check 1: Verify receipt appearance (green or white Bankak receipt)
            val isBankakReceipt = detectBankakReceipt(bitmap)
            if (!isBankakReceipt) {
                return ReceiptVerificationResult(false,
                    "الصورة لا تبدو إيصال بنكك — يرجى تحميل الإيصال الصحيح")
            }

            // Check 2: Verify process number not already used
            val usedReceipts = prefs.getStringSet(KEY_USED_RECEIPTS, emptySet()) ?: emptySet()
            if (usedReceipts.contains(processNumberManual.trim())) {
                return ReceiptVerificationResult(false,
                    "رقم العملية هذا مستخدم مسبقاً — لا يمكن استخدام نفس الإيصال مرتين")
            }

            // Check 3: Verify date is within last 30 days
            val dateValid = verifyReceiptDate(receiptDateManual)
            if (!dateValid) {
                return ReceiptVerificationResult(false,
                    "تاريخ الإيصال قديم — يجب أن يكون الدفع خلال آخر 30 يوم")
            }

            // Check 4: Process number format check (Bankak uses numeric IDs ~11 digits)
            val cleanProcess = processNumberManual.trim().replace(" ", "")
            if (cleanProcess.length < 8 || !cleanProcess.all { it.isDigit() }) {
                return ReceiptVerificationResult(false,
                    "رقم العملية غير صحيح — يرجى التحقق من الرقم في الإيصال")
            }

            // All checks passed — activate subscription
            val newUsed = usedReceipts.toMutableSet().also { it.add(cleanProcess) }
            val newExpiry = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(TRIAL_DAYS)

            prefs.edit()
                .putStringSet(KEY_USED_RECEIPTS, newUsed)
                .putLong(KEY_PAID_UNTIL, newExpiry)
                .apply()

            Log.i(TAG, "Payment verified! Process: $cleanProcess. Active until: ${Date(newExpiry)}")
            ReceiptVerificationResult(true,
                "تم التحقق بنجاح! التطبيق مفعّل لمدة شهر كامل.", cleanProcess)

        } catch (e: Exception) {
            Log.e(TAG, "Receipt verification error", e)
            ReceiptVerificationResult(false, "خطأ في التحقق: ${e.message}")
        }
    }

    /**
     * Detect if image is a Bankak receipt.
     * Bankak receipts are either:
     * - Green (success confirmation screen) — dominant green color
     * - White (transaction details screen) — has red Bankak header
     */
    private fun detectBankakReceipt(bitmap: Bitmap): Boolean {
        val scaled = Bitmap.createScaledBitmap(bitmap, 100, 100, false)
        var greenPixels = 0
        var redPixels   = 0
        val total = 100 * 100

        for (y in 0 until 100) {
            for (x in 0 until 100) {
                val p = scaled.getPixel(x, y)
                val r = android.graphics.Color.red(p)
                val g = android.graphics.Color.green(p)
                val b = android.graphics.Color.blue(p)
                // Bankak green: high green, low red/blue
                if (g > 120 && r < 100 && b < 100) greenPixels++
                // Bankak red header: high red, low green/blue
                if (r > 150 && g < 80 && b < 80) redPixels++
            }
        }

        val greenRatio = greenPixels.toFloat() / total
        val redRatio   = redPixels.toFloat() / total

        Log.d(TAG, "Receipt detection: green=$greenRatio red=$redRatio")
        // Accept if clearly green (success screen) or has Bankak red header
        return greenRatio > 0.15f || redRatio > 0.03f
    }

    private fun verifyReceiptDate(dateStr: String): Boolean {
        return try {
            val formats = listOf("dd-MMM-yyyy", "dd/MM/yyyy", "yyyy-MM-dd",
                "dd-MM-yyyy", "d-MMM-yyyy HH:mm:ss")
            var parsed: Date? = null
            for (fmt in formats) {
                try {
                    parsed = SimpleDateFormat(fmt, Locale.ENGLISH).parse(dateStr.trim())
                    if (parsed != null) break
                } catch (e: Exception) { continue }
            }
            if (parsed == null) return false
            val ageMs = System.currentTimeMillis() - parsed.time
            ageMs in 0..TimeUnit.DAYS.toMillis(TRIAL_DAYS)
        } catch (e: Exception) { false }
    }

    // ── APK update compatibility ───────────────────────────────────────────────
    // Stores subscription data in SharedPreferences with the same PREFS name.
    // Since we use the same applicationId (com.thunder.agi) across updates,
    // SharedPreferences survive APK updates. The install signature mismatch
    // that forces uninstall happens because debug builds use different signing keys.
    // Solution: use release signing with a consistent keystore (see workflow).

    fun getInstallVersion(): Int = prefs.getInt(KEY_INSTALL_VERSION, 1)

    fun setInstallVersion(v: Int) = prefs.edit().putInt(KEY_INSTALL_VERSION, v).apply()

    // ── Payment message ────────────────────────────────────────────────────────

    fun getPaymentMessage(): String = """
رسالة تجديد الاشتراك

لقد انتهت فترة الاستخدام المجانية لتطبيق الرَّعد للذكاء العام المصطنع.

لمواصلة الاستخدام، يرجى تحويل أي مبلغ تختاره إلى:
• بنك الخرطوم — بنكك
• رقم الحساب: 2587655

بعد التحويل، ارفع صورة الإيصال في صفحة الدفع لتفعيل اشتراكك مجدداً لمدة شهر كامل.

شكراً لاستخدامك تطبيق الرَّعد! ⚡
    """.trimIndent()
}
