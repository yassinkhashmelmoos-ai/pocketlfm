package com.thunder.agi.subscription

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

private const val TAG   = "SubscriptionManager"
private const val PREFS = "thunder_agi_subscription"

class SubscriptionManager(private val context: Context) {

    companion object {
        private val ADMIN_USERNAMES = setOf(
            "thunder_admin_yas", "thunder_admin_dev", "thunder_admin_root"
        )
        private const val RESET_KEY            = "thunder_reset_key_2026"
        private const val BANKAK_RECEIVER      = "2587655"
        private const val TRIAL_DAYS           = 30L
        private const val KEY_USERNAME         = "username"
        private const val KEY_TRIAL_START      = "trial_start"
        private const val KEY_PAID_UNTIL       = "paid_until"
        private const val KEY_IS_ADMIN         = "is_admin"
        private const val KEY_USED_RECEIPTS    = "used_receipts"
        private const val KEY_FIRST_LAUNCH     = "first_launch"
        private const val KEY_INSTALL_VERSION  = "install_version"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    enum class SubscriptionState {
        FIRST_LAUNCH, TRIAL_ACTIVE, TRIAL_EXPIRED, PAID_ACTIVE, ADMIN
    }

    data class ReceiptVerificationResult(
        val valid: Boolean,
        val reason: String,
        val processNumber: String = ""
    )

    fun getState(): SubscriptionState {
        if (!prefs.contains(KEY_FIRST_LAUNCH)) return SubscriptionState.FIRST_LAUNCH
        if (prefs.getBoolean(KEY_IS_ADMIN, false)) return SubscriptionState.ADMIN
        val paidUntil = prefs.getLong(KEY_PAID_UNTIL, 0L)
        if (paidUntil > System.currentTimeMillis()) return SubscriptionState.PAID_ACTIVE
        val trialStart = prefs.getLong(KEY_TRIAL_START, 0L)
        val trialEnd   = trialStart + TimeUnit.DAYS.toMillis(TRIAL_DAYS)
        return if (System.currentTimeMillis() < trialEnd)
            SubscriptionState.TRIAL_ACTIVE else SubscriptionState.TRIAL_EXPIRED
    }

    fun isActive() = getState() in setOf(
        SubscriptionState.TRIAL_ACTIVE,
        SubscriptionState.PAID_ACTIVE,
        SubscriptionState.ADMIN
    )

    fun getDaysRemaining(): Long {
        val trialStart = prefs.getLong(KEY_TRIAL_START, System.currentTimeMillis())
        val trialEnd   = trialStart + TimeUnit.DAYS.toMillis(TRIAL_DAYS)
        return maxOf(0L, TimeUnit.MILLISECONDS.toDays(trialEnd - System.currentTimeMillis()))
    }

    fun getUsername(): String = prefs.getString(KEY_USERNAME, "") ?: ""

    // FIX: registerUser now properly sets FIRST_LAUNCH flag so TRIAL_ACTIVE is reachable
    fun registerUser(username: String): Boolean {
        val clean = username.trim().lowercase()
        if (clean.isBlank()) return false
        prefs.edit().apply {
            putString(KEY_USERNAME, username.trim())
            putBoolean(KEY_FIRST_LAUNCH, true)    // mark as having been launched
            putLong(KEY_TRIAL_START, System.currentTimeMillis())
            when {
                ADMIN_USERNAMES.contains(clean) -> {
                    putBoolean(KEY_IS_ADMIN, true)
                }
                clean == RESET_KEY.lowercase() -> {
                    putLong(KEY_TRIAL_START, System.currentTimeMillis())
                    putLong(KEY_PAID_UNTIL, 0L)
                    putBoolean(KEY_IS_ADMIN, false)
                }
                else -> {
                    putBoolean(KEY_IS_ADMIN, false)
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
        return true
    }

    // FIX: receipt verification — any process number of the right length used to pass.
    // Now requires: green/white Bankak image + unused process number + valid date.
    // Also starts trial if it hasn't been started yet (handles first-time payment).
    fun verifyReceipt(
        imageUri: Uri,
        processNumberManual: String,
        receiptDateManual: String
    ): ReceiptVerificationResult {
        return try {
            val bitmap = context.contentResolver.openInputStream(imageUri)?.use {
                BitmapFactory.decodeStream(it)
            } ?: return ReceiptVerificationResult(false, "Could not read image")

            // Check 1: image must look like a Bankak receipt (green/white color scheme)
            if (!detectBankakReceipt(bitmap)) {
                return ReceiptVerificationResult(false,
                    "الصورة لا تبدو إيصال بنكك — يرجى تحميل الإيصال الصحيح\n" +
                    "Image does not look like a Bankak receipt")
            }

            // Check 2: process number must not already have been used
            val usedReceipts = prefs.getStringSet(KEY_USED_RECEIPTS, emptySet()) ?: emptySet()
            val cleanProcess = processNumberManual.trim().replace(" ", "")
            if (usedReceipts.contains(cleanProcess)) {
                return ReceiptVerificationResult(false,
                    "رقم العملية هذا مستخدم مسبقاً — لا يمكن استخدام نفس الإيصال مرتين\n" +
                    "This receipt has already been used")
            }

            // Check 3: date must be within last 30 days
            if (!verifyReceiptDate(receiptDateManual)) {
                return ReceiptVerificationResult(false,
                    "تاريخ الإيصال قديم — يجب أن يكون الدفع خلال آخر 30 يوم\n" +
                    "Receipt is older than 30 days")
            }

            // Check 4: process number format — must be all digits, 8-15 chars
            if (cleanProcess.length < 8 || cleanProcess.length > 15 || !cleanProcess.all { it.isDigit() }) {
                return ReceiptVerificationResult(false,
                    "رقم العملية غير صحيح — يجب أن يكون أرقاماً فقط\n" +
                    "Process number must be 8-15 digits")
            }

            // All checks passed — activate
            val newUsed    = usedReceipts.toMutableSet().also { it.add(cleanProcess) }
            val newExpiry  = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(TRIAL_DAYS)

            prefs.edit()
                .putStringSet(KEY_USED_RECEIPTS, newUsed)
                .putLong(KEY_PAID_UNTIL, newExpiry)
                // FIX: also set FIRST_LAUNCH + TRIAL_START if not already set,
                // so getState() can return PAID_ACTIVE immediately after verification
                .also { ed ->
                    if (!prefs.contains(KEY_FIRST_LAUNCH)) {
                        ed.putBoolean(KEY_FIRST_LAUNCH, true)
                        ed.putLong(KEY_TRIAL_START, System.currentTimeMillis())
                    }
                }
                .apply()

            Log.i(TAG, "Payment verified! Process: $cleanProcess until: ${Date(newExpiry)}")
            ReceiptVerificationResult(true,
                "✅ تم التحقق بنجاح! التطبيق مفعّل لمدة شهر كامل.\n" +
                "Activated for one month!", cleanProcess)

        } catch (e: Exception) {
            Log.e(TAG, "Receipt error", e)
            ReceiptVerificationResult(false, "خطأ في التحقق: ${e.message}")
        }
    }

    private fun detectBankakReceipt(bitmap: android.graphics.Bitmap): Boolean {
        val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, 100, 100, false)
        var greenPx = 0; var redPx = 0; var whitePx = 0
        for (y in 0 until 100) for (x in 0 until 100) {
            val p = scaled.getPixel(x, y)
            val r = android.graphics.Color.red(p)
            val g = android.graphics.Color.green(p)
            val b = android.graphics.Color.blue(p)
            if (g > 120 && r < 100 && b < 100) greenPx++
            if (r > 150 && g < 80  && b < 80)  redPx++
            if (r > 200 && g > 200 && b > 200)  whitePx++
        }
        val total     = 10000f
        val greenRatio = greenPx / total
        val redRatio   = redPx   / total
        val whiteRatio = whitePx / total
        Log.d(TAG, "Receipt: green=$greenRatio red=$redRatio white=$whiteRatio")
        // Green screen (success confirmation) OR white background with red branding
        return greenRatio > 0.12f || (redRatio > 0.02f && whiteRatio > 0.3f)
    }

    private fun verifyReceiptDate(dateStr: String): Boolean {
        return try {
            val formats = listOf(
                "dd-MMM-yyyy", "dd/MM/yyyy", "yyyy-MM-dd",
                "dd-MM-yyyy", "d-MMM-yyyy HH:mm:ss", "yyyy-MM-dd HH:mm:ss"
            )
            var parsed: Date? = null
            for (fmt in formats) {
                try { parsed = SimpleDateFormat(fmt, Locale.ENGLISH).parse(dateStr.trim()); if (parsed != null) break }
                catch (e: Exception) { continue }
            }
            if (parsed == null) return false
            val ageMs = System.currentTimeMillis() - parsed.time
            // Must be 0..30 days old (not future, not older than trial period)
            ageMs in 0..TimeUnit.DAYS.toMillis(TRIAL_DAYS)
        } catch (e: Exception) { false }
    }

    fun getInstallVersion(): Int = prefs.getInt(KEY_INSTALL_VERSION, 1)
    fun setInstallVersion(v: Int) = prefs.edit().putInt(KEY_INSTALL_VERSION, v).apply()

    fun getPaymentMessage(): String = """
رسالة تجديد الاشتراك

لقد انتهت فترة الاستخدام المجانية لتطبيق الرَّعد للذكاء العام المصطنع.

لمواصلة الاستخدام، يرجى تحويل أي مبلغ تختاره إلى:
• بنك الخرطوم — بنكك
• رقم الحساب: $BANKAK_RECEIVER

بعد التحويل، ارفع صورة الإيصال في صفحة الدفع لتفعيل اشتراكك مجدداً لمدة شهر كامل.

شكراً لاستخدامك تطبيق الرَّعد! ⚡
    """.trimIndent()
}
