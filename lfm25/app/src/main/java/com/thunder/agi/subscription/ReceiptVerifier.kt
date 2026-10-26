package com.thunder.agi.subscription

import java.security.MessageDigest
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

/**
 * ReceiptVerifier: decides whether the TEXT read from a receipt screenshot proves a payment.
 *
 * Pure Kotlin on purpose (no Android imports) so every rule here is unit-testable on the JVM.
 * The Android side (Bitmap decoding, Tesseract) lives in ReceiptOcr.kt / SubscriptionManager.kt.
 *
 * WHAT IS CHECKED, from the picture itself (nothing the user types can satisfy these):
 *   1. the "الى حساب" (To account) row equals the app's account, exactly (all 16 digits)
 *   2. the recipient name row matches the account holder
 *   3. the amount row is readable and at least the minimum
 *   4. the date and time are readable, not in the future, and not older than maxAgeDays
 *   5. a typed process number, if any, equals the one printed on the receipt (when visible)
 *   6. the receipt was not used before (fingerprint of date/time to the minute + account)
 *
 * WHAT CANNOT BE CHECKED OFFLINE: a screenshot is just pixels. Someone who edits the picture
 * (or fabricates one) can make it say anything, and no on-device code can tell. Only the bank
 * (or the owner looking at the bank app) can confirm that money really moved.
 */

// ── Policy ────────────────────────────────────────────────────────────────────

/** What a receipt MUST say for the payment to count. */
data class ReceiptPolicy(
    /** Full account as printed on the receipt ("0323 0258 7655 0001"; spaces are ignored). "" = check [accountCore] only. */
    val accountFull: String,
    /** The short number shown to users in the app ("2587655"). */
    val accountCore: String,
    /** Recipient name exactly as the bank prints it. */
    val recipientName: String,
    val minAmount: Double = 1.0,
    val maxAgeDays: Long = 30,
    val futureToleranceMinutes: Long = 15,
    /** Receipts print local Sudan time. */
    val zone: ZoneId = ZoneId.of("Africa/Khartoum"),
    /** true = a receipt that does not show "رقم العملية" is rejected. */
    val requireProcessNumberOnReceipt: Boolean = false
) {
    val fullDigits: String get() = accountFull.filter { it in '0'..'9' }
    val coreDigits: String get() = accountCore.filter { it in '0'..'9' }
}

// ── Parsed fields ─────────────────────────────────────────────────────────────

/** Everything read from the receipt. Empty / null = not found or unreadable. */
data class ReceiptFields(
    val dateTime: LocalDateTime? = null,
    /** Candidate 16-digit strings for the "من حساب" row (OCR may flip the digit-group order, so up to 2+). */
    val fromAccounts: List<String> = emptyList(),
    /** Candidate 16-digit strings for the "الى حساب" row. */
    val toAccounts: List<String> = emptyList(),
    val names: List<String> = emptyList(),
    val amount: Double? = null,
    val processNumber: String? = null
) {
    /** Combine two OCR passes over the SAME image: lists are unioned, scalars keep the first value found. */
    fun mergedWith(o: ReceiptFields) = ReceiptFields(
        dateTime      = dateTime ?: o.dateTime,
        fromAccounts  = (fromAccounts + o.fromAccounts).distinct(),
        toAccounts    = (toAccounts + o.toAccounts).distinct(),
        names         = (names + o.names).distinct(),
        amount        = amount ?: o.amount,
        processNumber = processNumber ?: o.processNumber
    )
}

// ── Verdicts ──────────────────────────────────────────────────────────────────

enum class RejectCode {
    UNREADABLE_ACCOUNT, WRONG_ACCOUNT, UNREADABLE_NAME, WRONG_NAME,
    UNREADABLE_AMOUNT, LOW_AMOUNT, UNREADABLE_DATE, FUTURE_DATE, OLD_RECEIPT,
    PROCESS_FORMAT, PROCESS_MISMATCH, PROCESS_NOT_ON_RECEIPT,
    DUPLICATE, ENGINE_ERROR
}

sealed class ReceiptVerdict {
    data class Accepted(
        val fields: ReceiptFields,
        /** Stable id of this payment; store it to stop the same receipt being used twice. */
        val fingerprint: String,
        /** The receipt's own process number if it was visible (also stored against reuse). */
        val processKey: String?
    ) : ReceiptVerdict()

    data class Rejected(val code: RejectCode, val messageAr: String, val messageEn: String) : ReceiptVerdict() {
        val message: String get() = "$messageAr\n$messageEn"
    }
}

// ── Text helpers ──────────────────────────────────────────────────────────────

internal object TextNorm {
    // zero-width, bidi marks/embeddings/isolates, Arabic letter mark, BOM, soft hyphen
    private val INVISIBLE = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\u061C\\uFEFF\\u00AD]")
    // harakat, Quranic marks, superscript alef, tatweel
    private val DIACRITICS = Regex("[\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u06D6-\\u06ED\\u0640]")
    private val SPACES = Regex("\\s+")

    /** Arabic-Indic (٠-٩) and Persian (۰-۹) digits to 0-9. */
    fun asciiDigits(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) sb.append(
            when (ch) {
                in '\u0660'..'\u0669' -> '0' + (ch - '\u0660')
                in '\u06F0'..'\u06F9' -> '0' + (ch - '\u06F0')
                else -> ch
            }
        )
        return sb.toString()
    }

    /** One OCR line made safe to parse: invisible marks removed, ASCII digits, single spaces. */
    fun clean(s: String): String =
        SPACES.replace(asciiDigits(INVISIBLE.replace(s, "")).replace('\u00A0', ' '), " ").trim()

    /** Comparison key: look-alike Arabic letters unified, diacritics dropped. Letters and spaces only. */
    fun arabicKey(s: String): String {
        val sb = StringBuilder(s.length)
        for (c0 in DIACRITICS.replace(s, "")) {
            val c = when (c0) {
                '\u0623', '\u0625', '\u0622', '\u0671' -> '\u0627'   // أ إ آ ٱ -> ا
                '\u0649', '\u06CC', '\u0626'           -> '\u064A'   // ى ی ئ   -> ي
                '\u0629'                               -> '\u0647'   // ة       -> ه
                '\u0624'                               -> '\u0648'   // ؤ       -> و
                '\u06A9'                               -> '\u0643'   // ک       -> ك
                else -> c0
            }
            if (c.isLetter() || c == ' ') sb.append(c.lowercaseChar())
        }
        return SPACES.replace(sb.toString(), " ").trim()
    }

    fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

// ── Parser ────────────────────────────────────────────────────────────────────

object ReceiptParser {

    private val DATE_DMY  = Regex("(?<!\\d)(\\d{1,2})\\s*[-/.]\\s*([A-Za-z0-9]{1,3})\\s*[-/.]\\s*(\\d{4})(?!\\d)")
    private val DATE_YMD  = Regex("(?<!\\d)(\\d{4})\\s*[-/.]\\s*(\\d{1,2})\\s*[-/.]\\s*(\\d{1,2})(?!\\d)")
    private val TIME      = Regex("(?<!\\d)(\\d{1,2})\\s*:\\s*(\\d{2})(?:\\s*:\\s*(\\d{2}))?(?!\\d)")
    private val DIGIT_RUN = Regex("\\d+")
    private val MONEY_FMT  = Regex("^\\d{1,3}(,\\d{3})*\\.\\d{2}$")      // 30,000.00 (the bank always prints 2 decimals)
    private val MONEY_LIKE = Regex("^[0-9OoIl][0-9OoIl,.]*$")
    private val SEPARATED_NUMBER = Regex("\\d[,.]\\d")
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    // Row labels, already in TextNorm.arabicKey form.
    private val NAME_LABELS = listOf("اسم", "المرسل", "اليه")
    private const val AMOUNT_LABEL = "المبلغ"
    private val PROCESS_LABELS = listOf("العمليه", "عمليه")
    private const val LABEL_TO = "الي"      // الى / إلى after normalisation
    private const val LABEL_FROM = "من"

    fun parse(rawText: String): ReceiptFields {
        val lines = rawText.lineSequence().map { TextNorm.clean(it) }.filter { it.isNotEmpty() }.toList()

        val dateHit = findDate(lines)
        val time = findTime(lines, dateHit?.second ?: -1)
        val dateTime = if (dateHit != null && time != null) LocalDateTime.of(dateHit.first, time) else null

        val (toLine, fromLine) = resolveAccountRows(accountLines(lines))

        return ReceiptFields(
            dateTime      = dateTime,
            fromAccounts  = fromLine?.candidates ?: emptyList(),
            toAccounts    = toLine?.candidates ?: emptyList(),
            names         = findNames(lines),
            amount        = findAmount(lines),
            processNumber = findProcessNumber(lines)
        )
    }

    // ── date / time ──

    private fun monthFrom(tok: String): Int? {
        if (tok.all { it in '0'..'9' }) return tok.toIntOrNull()?.takeIf { it in 1..12 }
        // OCR often reads "Oct" as "0ct", "Sep" as "5ep", "Jul" as "Ju1"
        val t = tok.lowercase().map { when (it) { '0' -> 'o'; '1' -> 'l'; '5' -> 's'; else -> it } }.joinToString("")
        val exact = MONTHS.indexOf(t)
        if (exact >= 0) return exact + 1
        if (t.length != 3) return null
        val near = MONTHS.withIndex().filter { TextNorm.editDistance(t, it.value) <= 1 }
        return if (near.size == 1) near[0].index + 1 else null
    }

    private fun tryDate(y: Int, m: Int, d: Int): LocalDate? =
        try { if (y in 2000..2100) LocalDate.of(y, m, d) else null } catch (e: DateTimeException) { null }

    private fun findDate(lines: List<String>): Pair<LocalDate, Int>? {
        for ((i, l) in lines.withIndex()) {
            DATE_DMY.find(l)?.let { m ->
                val mo = monthFrom(m.groupValues[2])
                if (mo != null) tryDate(m.groupValues[3].toInt(), mo, m.groupValues[1].toInt())?.let { return it to i }
            }
            DATE_YMD.find(l)?.let { m ->
                tryDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())?.let { return it to i }
            }
        }
        return null
    }

    private fun findTime(lines: List<String>, dateLine: Int): LocalTime? {
        val order = (listOf(dateLine, dateLine - 1, dateLine + 1) + lines.indices).filter { it in lines.indices }.distinct()
        for (i in order) {
            val m = TIME.find(lines[i]) ?: continue
            val h = m.groupValues[1].toInt()
            val mi = m.groupValues[2].toInt()
            val s = m.groupValues[3].ifEmpty { "0" }.toInt()
            if (h in 0..23 && mi in 0..59 && s in 0..59) return LocalTime.of(h, mi, s)
        }
        return null
    }

    // ── accounts ──

    internal class AccountLine(val index: Int, val candidates: List<String>, val isTo: Boolean, val isFrom: Boolean)

    /**
     * 16-digit candidates for a row of digit groups. Observed in real OCR of a Bankak receipt:
     *  - the 4-digit groups come out in REVERSED order ("0001 1633 0287 0393" for 0393 0287 1633 0001)
     *  - leading zeros are dropped ("1" for "0001")
     * so groups are left-padded to 4 and both orders are offered; the caller compares to the expected value.
     */
    internal fun accountCandidates(groups: List<String>): List<String> {
        val sets: List<List<String>> = when {
            groups.size == 4 -> listOf(groups)
            groups.size == 5 -> listOf(groups.drop(1), groups.dropLast(1))      // one stray digit group of OCR noise
            groups.size == 1 && groups[0].length == 16 -> listOf(groups[0].chunked(4))
            else -> emptyList()
        }
        return sets.flatMap { g ->
            if (g.any { it.length > 4 }) emptyList()
            else {
                val padded = g.map { it.padStart(4, '0') }
                listOf(padded.joinToString(""), padded.reversed().joinToString(""))
            }
        }.distinct()
    }

    private fun accountLines(lines: List<String>): List<AccountLine> = lines.mapIndexedNotNull { i, l ->
        // never a date/time row, never a money row ("1,234.50")
        if (l.contains(':') || SEPARATED_NUMBER.containsMatchIn(l) ||
            DATE_DMY.containsMatchIn(l) || DATE_YMD.containsMatchIn(l)) return@mapIndexedNotNull null
        val cands = accountCandidates(DIGIT_RUN.findAll(l).map { it.value }.toList())
        if (cands.isEmpty()) return@mapIndexedNotNull null
        val toks = TextNorm.arabicKey(DIGIT_RUN.replace(l, " ")).split(' ').filter { it.isNotEmpty() }
        AccountLine(i, cands, isTo = toks.any { it == LABEL_TO }, isFrom = toks.any { it == LABEL_FROM })
    }

    /**
     * Which row is "To" and which is "From". Labels are used when readable; when OCR garbles a label
     * ("yo حساب", "ll حساب" were both seen) the fixed layout decides: From is above To.
     * Direction matters: a receipt for money that LEFT the owner's account must never pass.
     */
    private fun resolveAccountRows(acct: List<AccountLine>): Pair<AccountLine?, AccountLine?> {
        val labelledTo   = acct.firstOrNull { it.isTo && !it.isFrom }
        val labelledFrom = acct.firstOrNull { it.isFrom && !it.isTo }
        val to = labelledTo ?: when {
            acct.size == 2 && labelledFrom != null -> acct.first { it !== labelledFrom }
            acct.size == 2                         -> acct[1]
            else                                   -> null
        }
        val from = labelledFrom ?: acct.firstOrNull { it !== to }
        return to to from
    }

    // ── labelled rows ──

    private fun isLabel(key: String, word: String) =
        key == word || (key.length >= 4 && word.length >= 4 && TextNorm.editDistance(key, word) <= 1)

    private fun isNameLabel(key: String) = NAME_LABELS.any { isLabel(key, it) }

    private fun findNames(lines: List<String>): List<String> {
        val out = ArrayList<String>()
        for (l in lines) {
            val toks = l.split(' ').filter { it.isNotEmpty() }
            val keys = toks.map { TextNorm.arabicKey(it) }
            if (keys.none { isNameLabel(it) }) continue
            val nameToks = toks.filterIndexed { i, _ -> !isNameLabel(keys[i]) && keys[i].any { c -> c in '\u0621'..'\u064A' } }
            if (nameToks.isNotEmpty()) out.add(nameToks.joinToString(" "))
        }
        return out
    }

    internal fun amountOf(token: String): Double? {
        val t = token.trim().trim(',', '.')
        if (t.isEmpty() || t.none { it in '0'..'9' }) return null
        val dot = t.lastIndexOf('.')
        val comma = t.lastIndexOf(',')
        val dec: Char? = when {
            dot >= 0 && comma >= 0 -> if (dot > comma) '.' else ','
            dot >= 0   -> if (t.count { it == '.' } == 1 && t.length - dot - 1 in 1..2) '.' else null
            comma >= 0 -> if (t.count { it == ',' } == 1 && t.length - comma - 1 in 1..2) ',' else null
            else -> null
        }
        val normalised = if (dec == null) t.filter { it in '0'..'9' }
        else {
            val idx = t.lastIndexOf(dec)
            t.substring(0, idx).filter { it in '0'..'9' } + "." + t.substring(idx + 1).filter { it in '0'..'9' }
        }
        return normalised.toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }
    }

    /** Money-shaped tokens on a line, after repairing O/0 and l/1 confusions. Loose numbers ("20") are NOT money. */
    private fun moneyTokens(line: String): List<String> =
        line.split(' ')
            .filter { MONEY_LIKE.matches(it) && it.any { c -> c in '0'..'9' } }
            .map { t -> t.map { c -> when (c) { 'O', 'o' -> '0'; 'I', 'l' -> '1'; else -> c } }.joinToString("") }
            .filter { MONEY_FMT.matches(it) }

    /**
     * Strict on purpose. Real OCR of a blurred copy returned just "20" for "30,000.00"; trusting that
     * would show and compare the wrong amount. An unreadable amount lets the next OCR pass try again.
     */
    private fun findAmount(lines: List<String>): Double? {
        for (l in lines) {
            if (l.split(' ').none { isLabel(TextNorm.arabicKey(it), AMOUNT_LABEL) }) continue
            moneyTokens(l).firstOrNull()?.let { tok -> amountOf(tok)?.let { return it } }
        }
        // label unreadable: accept ONE unambiguous money-formatted number anywhere
        val cands = lines.flatMap { moneyTokens(it) }.distinct()
        return if (cands.size == 1) amountOf(cands[0]) else null
    }

    private fun findProcessNumber(lines: List<String>): String? {
        for (l in lines) {
            val keys = l.split(' ').map { TextNorm.arabicKey(it) }
            if (keys.none { k -> PROCESS_LABELS.any { isLabel(k, it) } }) continue
            DIGIT_RUN.findAll(l).map { it.value }.filter { it.length in 6..20 }.maxByOrNull { it.length }?.let { return it }
        }
        return null
    }
}

// ── Validator ─────────────────────────────────────────────────────────────────

object ReceiptValidator {

    /** Does the name read from the receipt match the account holder? At most ONE character may differ in total. */
    fun nameMatches(expected: String, read: String): Boolean {
        val exp = TextNorm.arabicKey(expected).split(' ').filter { it.isNotEmpty() }
        val got = TextNorm.arabicKey(read).split(' ').filter { it.isNotEmpty() }.toMutableList()
        if (exp.isEmpty() || got.size > exp.size + 1) return false
        var total = 0
        for (e in exp) {
            val tol = if (e.length >= 4) 1 else 0
            var bestIdx = -1
            var bestD = Int.MAX_VALUE
            for ((i, g) in got.withIndex()) {
                val d = TextNorm.editDistance(g, e)
                if (d < bestD) { bestD = d; bestIdx = i }
            }
            if (bestIdx < 0 || bestD > tol) return false
            total += bestD
            got.removeAt(bestIdx)
        }
        return total <= 1
    }

    private fun accountMatches(candidate: String, p: ReceiptPolicy) =
        if (p.fullDigits.isNotEmpty()) candidate == p.fullDigits else candidate.contains(p.coreDigits)

    private fun rej(code: RejectCode, ar: String, en: String) = ReceiptVerdict.Rejected(code, ar, en)

    /**
     * Identity of one payment. Minute resolution because a re-screenshot of the same transfer can OCR the
     * seconds differently; the amount is deliberately NOT included because it is the noisiest field (a blurred
     * copy of a real receipt read "30,000.00" as "20"). Two different payments to the account within the same
     * minute therefore collide, and the second is refused: rare, and support can resolve it.
     */
    fun fingerprint(dateTime: LocalDateTime, p: ReceiptPolicy): String {
        val minute = dateTime.withSecond(0).withNano(0)
        val acct = p.fullDigits.ifEmpty { p.coreDigits }
        return TextNorm.sha256Hex("rcpt2|$minute|$acct")
    }

    fun validate(f: ReceiptFields, typedProcessNumber: String, p: ReceiptPolicy, now: Instant): ReceiptVerdict {
        // 1) money went TO our account
        if (f.toAccounts.isEmpty()) return rej(RejectCode.UNREADABLE_ACCOUNT,
            "تعذّر قراءة الحساب المستلم في الصورة. ارفع لقطة شاشة كاملة وواضحة للإيصال (يجب أن يظهر سطر «الى حساب»).",
            "Could not read the receiving account. Upload a full, clear screenshot showing the “To account” row.")
        if (f.toAccounts.none { accountMatches(it, p) }) return rej(RejectCode.WRONG_ACCOUNT,
            "هذا الإيصال ليس لتحويل إلى حساب التطبيق (${p.accountCore}) — أو أن الصورة غير واضحة بما يكفي للتأكد.",
            "This receipt is not for a transfer to the app's account (${p.accountCore}), or the image is too unclear to confirm it.")

        // 2) ... and to the right person
        if (f.names.isEmpty()) return rej(RejectCode.UNREADABLE_NAME,
            "تعذّر قراءة اسم المستلم في الإيصال. ارفع لقطة كاملة وواضحة (يجب أن يظهر سطر «إسم المرسل اليه»).",
            "Could not read the recipient name. Upload a full, clear screenshot showing the recipient-name row.")
        if (f.names.none { nameMatches(p.recipientName, it) }) return rej(RejectCode.WRONG_NAME,
            "اسم المستلم في الإيصال لا يطابق صاحب الحساب — أو أن الصورة غير واضحة بما يكفي لقراءته.",
            "The recipient name on the receipt does not match the account holder, or the image is too unclear to read it.")

        // 3) amount
        val amount = f.amount ?: return rej(RejectCode.UNREADABLE_AMOUNT,
            "تعذّر قراءة المبلغ في الإيصال. ارفع لقطة كاملة وواضحة (يجب أن يظهر سطر «المبلغ»).",
            "Could not read the amount. Upload a full, clear screenshot showing the amount row.")
        if (amount < p.minAmount) return rej(RejectCode.LOW_AMOUNT,
            "المبلغ في الإيصال (${fmt(amount)}) أقل من الحد الأدنى (${fmt(p.minAmount)}).",
            "The amount on the receipt (${fmt(amount)}) is below the minimum (${fmt(p.minAmount)}).")

        // 4) date and time
        val dt = f.dateTime ?: return rej(RejectCode.UNREADABLE_DATE,
            "تعذّر قراءة تاريخ ووقت الإيصال. ارفع لقطة كاملة وواضحة (يجب أن يظهر سطر «التاريخ و الزمن»).",
            "Could not read the receipt date and time. Upload a full, clear screenshot showing the date row.")
        val at = dt.atZone(p.zone).toInstant()
        if (at.isAfter(now.plusSeconds(p.futureToleranceMinutes * 60))) return rej(RejectCode.FUTURE_DATE,
            "تاريخ الإيصال في المستقبل. تأكد من ضبط تاريخ ووقت هاتفك.",
            "The receipt date is in the future. Check your phone's date and time.")
        if (at.isBefore(now.minusSeconds(p.maxAgeDays * 86_400))) return rej(RejectCode.OLD_RECEIPT,
            "الإيصال قديم — يجب أن يكون الدفع خلال آخر ${p.maxAgeDays} يوماً.",
            "The receipt is older than ${p.maxAgeDays} days.")

        // 5) typed process number: optional, but if given it must match what the receipt shows
        val typed = TextNorm.asciiDigits(typedProcessNumber).filter { it in '0'..'9' }
        if (typedProcessNumber.isNotBlank()) {
            if (typed.length !in 6..20) return rej(RejectCode.PROCESS_FORMAT,
                "رقم العملية يجب أن يكون أرقاماً فقط (من 6 إلى 20 رقماً)، أو اتركه فارغاً.",
                "The process number must be 6–20 digits, or leave it empty.")
            if (f.processNumber != null && f.processNumber != typed) return rej(RejectCode.PROCESS_MISMATCH,
                "رقم العملية المكتوب لا يطابق الرقم الظاهر في الإيصال. صحّحه أو اتركه فارغاً.",
                "The process number you typed does not match the one on the receipt. Fix it or leave it empty.")
        }
        if (p.requireProcessNumberOnReceipt && f.processNumber == null) return rej(RejectCode.PROCESS_NOT_ON_RECEIPT,
            "رقم العملية غير ظاهر في الصورة. ارفع لقطة تظهر فيها «رقم العملية».",
            "The process number is not visible in the image. Upload a screenshot that shows it.")

        return ReceiptVerdict.Accepted(f, fingerprint(dt, p), f.processNumber)
    }

    internal fun fmt(v: Double): String =
        if (Math.floor(v) == v) "%,d".format(java.util.Locale.US, v.toLong()) else "%,.2f".format(java.util.Locale.US, v)
}

// ── Orchestrator ──────────────────────────────────────────────────────────────

object ReceiptVerifier {

    /** The OCR engine could not run at all (as opposed to a receipt that is wrong or unreadable). */
    val ENGINE_ERROR = ReceiptVerdict.Rejected(RejectCode.ENGINE_ERROR,
        "تعذّر تشغيل قارئ الإيصالات على هذا الجهاز. حاول مرة أخرى أو تواصل مع الدعم.",
        "The receipt reader could not run on this device. Try again or contact support.")

    class Outcome(
        val verdict: ReceiptVerdict,
        val fields: ReceiptFields,
        val passesRun: Int,
        /** Set when NO pass produced any text and at least one pass threw (engine problem, not a bad receipt). */
        val engineError: Throwable?
    )

    /**
     * Runs OCR [passes] in order (cheapest/most reliable first) until the receipt validates.
     * Fields read by different passes of the SAME image are merged, so one pass that reads the account
     * and another that reads the amount still add up. Stops at the first pass that validates; a receipt
     * that never validates goes through every pass before being rejected.
     */
    fun verify(
        passes: List<() -> String>,
        typedProcessNumber: String,
        policy: ReceiptPolicy,
        now: Instant
    ): Outcome {
        var merged = ReceiptFields()
        var verdict: ReceiptVerdict? = null
        var ran = 0
        var firstError: Throwable? = null
        var gotText = false
        for (pass in passes) {
            val text = try {
                pass()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (firstError == null) firstError = e
                continue
            }
            ran++
            if (text.isNotBlank()) gotText = true
            merged = merged.mergedWith(ReceiptParser.parse(text))
            verdict = ReceiptValidator.validate(merged, typedProcessNumber, policy, now)
            if (verdict is ReceiptVerdict.Accepted) break
        }
        return Outcome(verdict ?: ENGINE_ERROR, merged, ran, if (!gotText) firstError else null)
    }
}

// ── Used-receipt ledger (pure functions over the stored set, so they are unit-testable) ──

object ReceiptLedger {
    private fun processKey(number: String) = "pn:" + TextNorm.sha256Hex(number)

    /**
     * Was this payment used before? Three independent keys, any one is enough:
     *  - the fingerprint (date/time to the minute + account), stable across re-screenshots
     *  - the process number printed on the receipt, when visible (stored hashed)
     *  - a typed process number, normalised to 0-9: digits typed as ٢٠٢٢… used to count as a different
     *    number than 2022…, so one receipt could be used twice
     * Plain numbers written by the previous version of the check are still honoured.
     */
    fun isUsed(used: Set<String>, v: ReceiptVerdict.Accepted, typedProcessNumber: String): Boolean {
        val printed = v.processKey
        val typed = TextNorm.asciiDigits(typedProcessNumber).filter { it in '0'..'9' }
        return ("fp:" + v.fingerprint) in used ||
            (printed != null && (processKey(printed) in used || printed in used)) ||
            (typed.isNotEmpty() && typed in used)
    }

    fun record(used: Set<String>, v: ReceiptVerdict.Accepted): Set<String> =
        used.toMutableSet().apply {
            add("fp:" + v.fingerprint)
            v.processKey?.let { add(processKey(it)) }
        }
}

// ── Image preparation (pure pixel maths; Android wraps it around Bitmap) ──────

object ReceiptImagePrep {
    /** OCR works best on this width. Chosen by experiment; see the notes shipped with this change. */
    const val PRIMARY_WIDTH = 1152
    const val FALLBACK_WIDTH = 1300

    fun scaledHeight(w: Int, h: Int, targetW: Int): Int =
        ((h.toLong() * targetW + w / 2) / w).toInt().coerceAtLeast(1)

    /** In place: ARGB pixels -> grayscale stretched to the full 0..255 range (same idea as PIL's autocontrast). */
    fun grayAutoContrastInPlace(px: IntArray) {
        var lo = 255
        var hi = 0
        for (i in px.indices) {
            val p = px[i]
            val l = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
            px[i] = l
            if (l < lo) lo = l
            if (l > hi) hi = l
        }
        val range = hi - lo
        for (i in px.indices) {
            val v = if (range <= 0) px[i] else ((px[i] - lo) * 255) / range
            px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
    }
}
