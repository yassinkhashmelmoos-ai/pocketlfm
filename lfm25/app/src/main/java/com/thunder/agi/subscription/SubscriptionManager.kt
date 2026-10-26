package com.thunder.agi.subscription

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

private const val TAG   = "SubscriptionManager"
private const val PREFS = "thunder_agi_subscription"

class SubscriptionManager(private val context: Context) {

    companion object {
        private val ADMIN_USERNAMES = setOf(
            "thunder_admin_yas", "thunder_admin_dev", "thunder_admin_root"
        )
        private const val RESET_KEY            = "thunder_reset_key_2026"
        private const val BANKAK_RECEIVER      = "2587655"

        // What a receipt must show. Copy these from a REAL receipt of the account.
        private const val RECEIVER_ACCOUNT_FULL = "0323 0258 7655 0001"   // the "الى حساب" row (spaces are ignored; "" = check only BANKAK_RECEIVER)
        private const val RECEIVER_NAME         = "ياسين احمد حسن مهدى"        // the "إسم المرسل اليه" row
        private const val MIN_AMOUNT_SDG        = 1.0                          // the in-app text says "any amount"; raise this to enforce a price
        private const val TRIAL_DAYS           = 30L
        private const val KEY_USERNAME         = "username"
        private const val KEY_TRIAL_START      = "trial_start"
        private const val KEY_PAID_UNTIL       = "paid_until"
        private const val KEY_IS_ADMIN         = "is_admin"
        private const val KEY_USED_RECEIPTS    = "used_receipts"
        private const val KEY_FIRST_LAUNCH     = "first_launch"
        private const val KEY_INSTALL_VERSION  = "install_version"

        private const val DUPLICATE_MESSAGE =
            "هذا الإيصال مستخدم مسبقاً — لا يمكن استخدام نفس الإيصال مرتين\nThis receipt has already been used"

        private val RECEIPT_POLICY = ReceiptPolicy(
            accountFull   = RECEIVER_ACCOUNT_FULL,
            accountCore   = BANKAK_RECEIVER,
            recipientName = RECEIVER_NAME,
            minAmount     = MIN_AMOUNT_SDG,
            maxAgeDays    = TRIAL_DAYS
        )
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

    // ── Receipt verification ─────────────────────────────────────────────────
    // BEFORE: an image that was >12% green + any 8-15 digit number + a typed date activated the app,
    // because nothing printed on the receipt was ever read (and the date had to be typed as YYYY-MM-DD
    // although the receipt prints 21-Oct-2026). NOW: the picture itself is read (on-device OCR) and must
    // show a transfer TO this account, TO this name, with a readable amount and a recent date, that has
    // not been used before. Rules, limits and tests: ReceiptVerifier.kt / ReceiptVerifierTest.kt.

    private val verifyLock = Mutex()

    /**
     * @param processNumberManual optional; if given, it must equal the number printed on the receipt.
     * Heavy (decode + OCR): suspends and runs off the main thread. One verification at a time, so two
     * quick taps cannot both pass the "already used" check.
     */
    suspend fun verifyReceipt(imageUri: Uri, processNumberManual: String): ReceiptVerificationResult =
        withContext(Dispatchers.Default) {
            verifyLock.withLock {
                try {
                    verifyReceiptLocked(imageUri, processNumberManual)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ReceiptOcr.OcrUnavailableException) {
                    Log.e(TAG, "OCR unavailable", e)
                    ReceiptVerificationResult(false, ReceiptVerifier.ENGINE_ERROR.message)
                } catch (e: Throwable) {
                    Log.e(TAG, "Receipt error", e)
                    ReceiptVerificationResult(false,
                        "تعذّر فحص الإيصال. حاول مرة أخرى بصورة أوضح أو أصغر.\n" +
                        "Could not check the receipt. Try again with a clearer or smaller image.")
                }
            }
        }

    private fun verifyReceiptLocked(imageUri: Uri, processNumberManual: String): ReceiptVerificationResult {
        val bitmap = decodeReceipt(imageUri)
            ?: return ReceiptVerificationResult(false, "تعذّر فتح الصورة\nCould not open the image")
        val outcome = try {
            ReceiptOcr.open(context).use { ocr ->
                ReceiptVerifier.verify(ocr.passes(bitmap), processNumberManual, RECEIPT_POLICY, Instant.now())
            }
        } finally {
            bitmap.recycle()
        }
        return when (val v = outcome.verdict) {
            is ReceiptVerdict.Rejected -> {
                Log.i(TAG, "Receipt rejected: ${v.code} (${outcome.passesRun} OCR pass(es))")
                ReceiptVerificationResult(false, v.message)
            }
            is ReceiptVerdict.Accepted -> activate(v, processNumberManual, outcome.passesRun)
        }
    }

    /** Only reached for a receipt that passed every content check. Refuses a receipt used before. */
    private fun activate(v: ReceiptVerdict.Accepted, typedProcessNumber: String, passes: Int): ReceiptVerificationResult {
        val used = prefs.getStringSet(KEY_USED_RECEIPTS, emptySet()) ?: emptySet()
        if (ReceiptLedger.isUsed(used, v, typedProcessNumber)) {
            Log.i(TAG, "Receipt rejected: DUPLICATE")
            return ReceiptVerificationResult(false, DUPLICATE_MESSAGE)
        }

        val now       = System.currentTimeMillis()
        val newExpiry = now + TimeUnit.DAYS.toMillis(TRIAL_DAYS)
        val editor = prefs.edit()
            .putStringSet(KEY_USED_RECEIPTS, ReceiptLedger.record(used, v))
            .putLong(KEY_PAID_UNTIL, newExpiry)
        // also set FIRST_LAUNCH + TRIAL_START if not already set, so getState() is PAID_ACTIVE immediately
        if (!prefs.contains(KEY_FIRST_LAUNCH)) {
            editor.putBoolean(KEY_FIRST_LAUNCH, true).putLong(KEY_TRIAL_START, now)
        }
        editor.commit()   // synchronous: the "already used" record must survive a crash right after activation

        Log.i(TAG, "Payment verified (${passes} OCR pass(es)); active until ${Date(newExpiry)}")   // no account/name in the log
        val f = v.fields
        val read = "${ReceiptValidator.fmt(f.amount ?: 0.0)} — ${f.dateTime.toString().replace('T', ' ')}"
        return ReceiptVerificationResult(true,
            "✅ تم التحقق بنجاح! التطبيق مفعّل لمدة شهر كامل.\n" +
            "(المبلغ والتاريخ كما قُرئا من الإيصال: $read)\n" +
            "Activated for one month!", v.processKey ?: "")
    }

    /** Decode no larger than needed: OCR works on ~1152px width, so skip decoding a 12-megapixel photo in full. */
    private fun decodeReceipt(uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val probe = resolver.openInputStream(uri) ?: return null
        probe.use { BitmapFactory.decodeStream(it, null, bounds) }          // fills bounds; returns null by design
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= ReceiptImagePrep.PRIMARY_WIDTH) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val stream = resolver.openInputStream(uri) ?: return null
        return stream.use { BitmapFactory.decodeStream(it, null, opts) }
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
