package com.thunder.agi

import com.thunder.agi.subscription.ReceiptImagePrep
import com.thunder.agi.subscription.ReceiptLedger
import com.thunder.agi.subscription.ReceiptParser
import com.thunder.agi.subscription.ReceiptPolicy
import com.thunder.agi.subscription.ReceiptValidator
import com.thunder.agi.subscription.ReceiptVerdict
import com.thunder.agi.subscription.ReceiptVerifier
import com.thunder.agi.subscription.RejectCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

// Arabic text as \u escapes so editors cannot reorder it.
private const val AR_NAME = "\u064A\u0627\u0633\u064A\u0646 \u0627\u062D\u0645\u062F \u062D\u0633\u0646 \u0645\u0647\u062F\u0649"
private const val AR_YASEEN = "\u064A\u0627\u0633\u064A\u0646"
private const val AR_AHMED = "\u0627\u062D\u0645\u062F"
private const val AR_MAHDI = "\u0645\u0647\u062F\u0649"
private const val AR_OTHER = "\u0645\u062D\u0645\u0648\u062F \u0639\u0644\u064A \u0639\u062B\u0645\u0627\u0646 \u0627\u0628\u0631\u0627\u0647\u064A\u0645"
private const val AR_LBL_PROC = "\u0631\u0642\u0645 \u0627\u0644\u0639\u0645\u0644\u064A\u0629"
private const val AR_LBL_TO = "\u0627\u0644\u0649 \u062D\u0633\u0627\u0628"
private const val AR_LBL_FROM = "\u0645\u0646 \u062D\u0633\u0627\u0628"
private const val AR_LBL_AMT = "\u0627\u0644\u0645\u0628\u0644\u063A"

private val POLICY = ReceiptPolicy(accountFull = "0323 0258 7655 0001", accountCore = "2587655", recipientName = AR_NAME)
private val RECEIPT_AT: Instant = LocalDateTime.of(2026, 10, 21, 11, 49, 50).atZone(ZoneId.of("Africa/Khartoum")).toInstant()
private val NOW: Instant = RECEIPT_AT.plus(Duration.ofDays(3))

private fun judge(text: String, typed: String = "", now: Instant = NOW, policy: ReceiptPolicy = POLICY) =
    ReceiptValidator.validate(ReceiptParser.parse(text), typed, policy, now)

private fun assertAccepted(v: ReceiptVerdict): ReceiptVerdict.Accepted =
    v as? ReceiptVerdict.Accepted ?: throw AssertionError("expected the receipt to be ACCEPTED but got $v")

private fun assertRejected(code: RejectCode, v: ReceiptVerdict) {
    val r = v as? ReceiptVerdict.Rejected ?: throw AssertionError("expected $code but the receipt was ACCEPTED")
    assertEquals(code, r.code)
}

class ReceiptVerifierTest {

    // ── the real receipt, as real OCR sees it ─────────────────────────────────

    @Test fun realReceiptIsAcceptedAndEveryFieldComesFromThePicture() {
        val f = ReceiptParser.parse(ReceiptFixtures.ORIGINAL_PASS1)
        assertEquals(LocalDateTime.of(2026, 10, 21, 11, 49, 50), f.dateTime)
        assertEquals(30000.0, f.amount!!, 0.001)
        assertTrue(f.toAccounts.contains("0323025876550001"))
        assertTrue(f.names.any { ReceiptValidator.nameMatches(AR_NAME, it) })
        assertAccepted(judge(ReceiptFixtures.ORIGINAL_PASS1))
    }

    @Test fun reversedDigitGroupsDroppedZerosAndGarbledLabelsStillValidate() {
        for (t in listOf(ReceiptFixtures.ORIGINAL_PASS1, ReceiptFixtures.ORIGINAL_RAW, ReceiptFixtures.JPEG35_RAW,
                         ReceiptFixtures.UP15_RAW, ReceiptFixtures.DIM_PASS1, ReceiptFixtures.BLUR_RAW))
            assertAccepted(judge(t))
    }

    @Test fun naturalOrderDigitGroupsAreRecognisedToo() {
        assertTrue(ReceiptParser.parse(ReceiptFixtures.BLUR_PASS1).toAccounts.contains("0323025876550001"))
    }

    @Test fun aLooseAmountLikeTwentyIsNeverTrustedAndTheNextPassFillsItIn() {
        // pass 1 of a blurred copy returned just "20" for "30,000.00"
        assertNull(ReceiptParser.parse(ReceiptFixtures.BLUR_PASS1).amount)
        assertRejected(RejectCode.UNREADABLE_AMOUNT, judge(ReceiptFixtures.BLUR_PASS1))
        val o = ReceiptVerifier.verify(listOf({ ReceiptFixtures.BLUR_PASS1 }, { ReceiptFixtures.BLUR_RAW }), "", POLICY, NOW)
        assertEquals(2, o.passesRun)
        assertEquals(30000.0, assertAccepted(o.verdict).fields.amount!!, 0.001)
    }

    @Test fun aBarelyReadableCopyIsRejectedNotGuessed() {
        // at 0.5x OCR still read the account digits correctly but turned the name into
        // "\u0628\u0627\u0633\u064A\u0646 ... \u0645\u0647\u0641\u062A" (3 letters wrong) and lost the amount row: refused, not guessed
        assertRejected(RejectCode.WRONG_NAME, judge(ReceiptFixtures.DOWN05_RAW))
    }

    @Test fun allCopiesOfOneReceiptShareOneFingerprint() {
        val fps = listOf(ReceiptFixtures.ORIGINAL_PASS1, ReceiptFixtures.ORIGINAL_RAW, ReceiptFixtures.JPEG35_RAW,
                         ReceiptFixtures.UP15_RAW, ReceiptFixtures.DIM_PASS1, ReceiptFixtures.BLUR_RAW)
            .map { assertAccepted(judge(it)).fingerprint }.toSet()
        assertEquals(1, fps.size)
    }

    @Test fun fingerprintIgnoresSecondsButNotTheMinuteOrTheAccount() {
        val a = ReceiptValidator.fingerprint(LocalDateTime.of(2026, 10, 21, 11, 49, 50), POLICY)
        assertEquals(a, ReceiptValidator.fingerprint(LocalDateTime.of(2026, 10, 21, 11, 49, 3), POLICY))
        assertFalse(a == ReceiptValidator.fingerprint(LocalDateTime.of(2026, 10, 21, 11, 50, 50), POLICY))
        assertFalse(a == ReceiptValidator.fingerprint(LocalDateTime.of(2026, 10, 21, 11, 49, 50), POLICY.copy(accountFull = "0323 0258 7655 0002")))
    }

    // ── the reported bug: nothing on the picture, any process number ──────────

    @Test fun textThatIsNotTheReceiptNeverActivatesAnything() {
        for (t in listOf("", "   ", "hello world", "12345678901", "21-Oct-2026 11:49:50",
                         "Transfer successful\nAmount 30,000.00\nTo 0323 0258 7655 0001"))
            assertTrue(judge(t, typed = "12345678901") is ReceiptVerdict.Rejected)
        assertRejected(RejectCode.UNREADABLE_ACCOUNT, judge("", typed = "12345678901"))
    }

    // ── accounts ──────────────────────────────────────────────────────────────

    @Test fun oneWrongDigitInTheAccountIsRejected() =
        assertRejected(RejectCode.WRONG_ACCOUNT, judge(ReceiptFixtures.ORIGINAL_PASS1.replace("7655", "7656")))

    @Test fun aReceiptForMoneyLeavingTheOwnersAccountIsRejected() {
        val swapped = ReceiptFixtures.ORIGINAL_PASS1
            .replace("0001 0333 0222 0111", "@@FROM@@")
            .replace("1 7655 0258 0323", "0001 0333 0222 0111")
            .replace("@@FROM@@", "1 7655 0258 0323")
        assertTrue("swap did not apply", swapped != ReceiptFixtures.ORIGINAL_PASS1)
        assertRejected(RejectCode.WRONG_ACCOUNT, judge(swapped))
    }

    @Test fun accountCandidatesCopeWithOcrNoise() {
        val full = "0323025876550001"
        assertTrue(ReceiptParser.accountCandidates(listOf("0323", "0258", "7655", "0001")).contains(full))
        assertTrue(ReceiptParser.accountCandidates(listOf("1", "7655", "0258", "0323")).contains(full))
        assertTrue(ReceiptParser.accountCandidates(listOf("9", "0323", "0258", "7655", "0001")).contains(full))
        assertTrue(ReceiptParser.accountCandidates(listOf("0323", "0258", "7655", "0001", "7")).contains(full))
        assertTrue(ReceiptParser.accountCandidates(listOf(full)).contains(full))
        assertTrue(ReceiptParser.accountCandidates(listOf("0323", "0258", "7655")).isEmpty())
        assertTrue(ReceiptParser.accountCandidates(listOf("03230", "0258", "7655", "0001")).isEmpty())
    }

    @Test fun policyIgnoresSpacesAndSupportsCoreOnlyMode() {
        assertEquals("0323025876550001", POLICY.fullDigits)
        assertEquals("2587655", POLICY.coreDigits)
        assertAccepted(judge(ReceiptFixtures.ORIGINAL_PASS1, policy = POLICY.copy(accountFull = "")))
        assertRejected(RejectCode.WRONG_ACCOUNT, judge(ReceiptFixtures.ORIGINAL_PASS1, policy = POLICY.copy(accountFull = "", accountCore = "2587656")))
    }

    // ── names ─────────────────────────────────────────────────────────────────

    @Test fun anotherPersonsNameIsRejected() {
        val other = ReceiptFixtures.ORIGINAL_PASS1.replace(AR_YASEEN, "\u0645\u062D\u0645\u0648\u062F").replace(AR_AHMED, "\u0639\u0644\u064A")
        assertRejected(RejectCode.WRONG_NAME, judge(other))
    }

    @Test fun nameToleranceIsOneCharacterInTotal() {
        assertTrue(ReceiptValidator.nameMatches(AR_NAME, AR_NAME.replace('\u0649', '\u064A')))                  // alef maksura vs ya
        assertTrue(ReceiptValidator.nameMatches(AR_NAME, AR_NAME.replace(AR_AHMED, "\u0627\u062D\u0645\u0631")))   // one letter off
        assertFalse(ReceiptValidator.nameMatches(AR_NAME,
            AR_NAME.replace(AR_AHMED, "\u0627\u062D\u0645\u0631").replace(AR_YASEEN, "\u064A\u0627\u0633\u064A\u0631")))  // two letters off
        assertFalse(ReceiptValidator.nameMatches(AR_NAME, AR_OTHER))
        assertFalse(ReceiptValidator.nameMatches(AR_NAME, "$AR_NAME $AR_NAME"))
        assertFalse(ReceiptValidator.nameMatches(AR_NAME, "$AR_YASEEN $AR_AHMED"))
    }

    // ── dates ─────────────────────────────────────────────────────────────────

    @Test fun oldFutureAndBoundaryDates() {
        val t = ReceiptFixtures.ORIGINAL_PASS1
        assertRejected(RejectCode.OLD_RECEIPT, judge(t, now = RECEIPT_AT.plus(Duration.ofDays(31))))
        assertAccepted(judge(t, now = RECEIPT_AT.plus(Duration.ofDays(29)).plus(Duration.ofHours(23))))
        assertRejected(RejectCode.OLD_RECEIPT, judge(t, now = RECEIPT_AT.plus(Duration.ofDays(30)).plus(Duration.ofHours(1))))
        assertRejected(RejectCode.FUTURE_DATE, judge(t, now = RECEIPT_AT.minus(Duration.ofHours(1))))
        assertAccepted(judge(t, now = RECEIPT_AT.minus(Duration.ofMinutes(10))))
    }

    @Test fun monthMisreadsAndOtherDateFormatsAreHandled() {
        val t = ReceiptFixtures.ORIGINAL_PASS1
        assertAccepted(judge(t.replace("Oct", "0ct")))
        assertAccepted(judge(t.replace("21-Oct-2026", "21/10/2026")))
        assertAccepted(judge(t.replace("21-Oct-2026", "2026-10-21")))
        assertAccepted(judge(t.replace("21-Oct-2026", "\u0662\u0661-Oct-\u0662\u0660\u0662\u0666")))
        assertRejected(RejectCode.UNREADABLE_DATE, judge(t.replace("21-Oct-2026", "21-Xyz-2026")))
        assertRejected(RejectCode.UNREADABLE_DATE, judge(t.replace("21-Oct-2026", "31-Nov-2026")))
        assertRejected(RejectCode.UNREADABLE_DATE, judge(t.replace("11:49:50", "25:49:50")))
    }

    // ── amounts ───────────────────────────────────────────────────────────────

    @Test fun amountsMustBeStrictMoneyAndLowOrMissingOnesAreRejected() {
        val t = ReceiptFixtures.ORIGINAL_PASS1
        assertRejected(RejectCode.LOW_AMOUNT, judge(t.replace("30,000.00", "0.50")))
        assertRejected(RejectCode.UNREADABLE_AMOUNT, judge(t.replace("30,000.00", "")))
        assertRejected(RejectCode.UNREADABLE_AMOUNT, judge(t.replace("30,000.00", "30000")))
        assertAccepted(judge(t.replace("30,000.00", "30,OOO.OO")))
        assertRejected(RejectCode.LOW_AMOUNT, judge(t, policy = POLICY.copy(minAmount = 50_000.0)))
        assertEquals(30000.0, ReceiptParser.amountOf("30,000.00")!!, 0.001)
        assertEquals(30000.0, ReceiptParser.amountOf("30.000,00")!!, 0.001)
        assertEquals(30000.0, ReceiptParser.amountOf("30,000")!!, 0.001)
        assertEquals(1234567.89, ReceiptParser.amountOf("1,234,567.89")!!, 0.001)
        assertNull(ReceiptParser.amountOf("abc"))
        assertNull(ReceiptParser.amountOf("0.00"))
    }

    // ── typed process number ──────────────────────────────────────────────────

    @Test fun typedProcessNumberMustMatchTheReceiptWhenItIsVisible() {
        val shown = ReceiptFixtures.ORIGINAL_PASS1 + "\n$AR_LBL_PROC 20229627131\n"
        assertEquals("20229627131", assertAccepted(judge(shown, typed = "20229627131")).processKey)
        assertAccepted(judge(shown, typed = "\u0662\u0660\u0662\u0662\u0669\u0666\u0662\u0667\u0661\u0663\u0661"))   // Arabic-Indic digits
        assertAccepted(judge(shown, typed = "2022 9627 131"))
        assertRejected(RejectCode.PROCESS_MISMATCH, judge(shown, typed = "20229627132"))
        assertRejected(RejectCode.PROCESS_FORMAT, judge(shown, typed = "123"))
        assertRejected(RejectCode.PROCESS_FORMAT, judge(shown, typed = "abcdefghij"))
    }

    @Test fun aCroppedReceiptCannotBeBlockedOrUnlockedByATypedNumber() {
        // the owner's sample screenshot is cropped above the date row, so the number is not on the picture
        val v = assertAccepted(judge(ReceiptFixtures.ORIGINAL_PASS1, typed = "20229627131"))
        assertNull(v.processKey)
        assertRejected(RejectCode.PROCESS_NOT_ON_RECEIPT,
            judge(ReceiptFixtures.ORIGINAL_PASS1, policy = POLICY.copy(requireProcessNumberOnReceipt = true)))
    }

    // ── orchestration ─────────────────────────────────────────────────────────

    @Test fun verifierStopsAtTheFirstPassThatValidates() {
        var calls = 0
        val o = ReceiptVerifier.verify(listOf({ calls++; ReceiptFixtures.ORIGINAL_PASS1 }, { calls++; ReceiptFixtures.BLUR_RAW }), "", POLICY, NOW)
        assertEquals(1, calls)
        assertEquals(1, o.passesRun)
        assertAccepted(o.verdict)
    }

    @Test fun anEngineThatCannotRunIsReportedAsSuchButABlankImageIsJustUnreadable() {
        val boom = ReceiptVerifier.verify(listOf({ throw IllegalStateException("no tessdata") }, { throw IllegalStateException("again") }), "", POLICY, NOW)
        assertRejected(RejectCode.ENGINE_ERROR, boom.verdict)
        assertNotNull(boom.engineError)
        val blank = ReceiptVerifier.verify(listOf({ "" }), "", POLICY, NOW)
        assertRejected(RejectCode.UNREADABLE_ACCOUNT, blank.verdict)
        assertNull(blank.engineError)
    }

    @Test fun cancellationIsNeverSwallowed() {
        try {
            ReceiptVerifier.verify(listOf({ throw CancellationException("stop") }), "", POLICY, NOW)
            fail("cancellation must propagate")
        } catch (e: CancellationException) {
            // expected
        }
    }

    // ── image preparation ─────────────────────────────────────────────────────

    @Test fun autoContrastStretchesGrayAndNeverDividesByZero() {
        val px = intArrayOf(0xFF404040.toInt(), 0xFF808080.toInt(), 0xFFC0C0C0.toInt())
        ReceiptImagePrep.grayAutoContrastInPlace(px)
        assertEquals(0xFF000000.toInt(), px[0])
        assertEquals(0xFFFFFFFF.toInt(), px[2])
        ReceiptImagePrep.grayAutoContrastInPlace(IntArray(4) { 0xFF336699.toInt() })      // flat image: must not throw
        assertEquals(2560, ReceiptImagePrep.scaledHeight(576, 1280, 1152))
    }

    // ── replay protection (the "already used" ledger) ─────────────────────────

    @Test fun theSameReceiptCannotBeUsedTwiceEvenFromADifferentScreenshot() {
        val first = assertAccepted(judge(ReceiptFixtures.ORIGINAL_PASS1))
        val ledger = ReceiptLedger.record(emptySet(), first)
        assertFalse(ReceiptLedger.isUsed(emptySet(), first, ""))
        for (copy in listOf(ReceiptFixtures.ORIGINAL_PASS1, ReceiptFixtures.JPEG35_RAW, ReceiptFixtures.UP15_RAW, ReceiptFixtures.BLUR_RAW))
            assertTrue(ReceiptLedger.isUsed(ledger, assertAccepted(judge(copy)), ""))
    }

    @Test fun aDifferentPaymentIsNotBlocked() {
        val ledger = ReceiptLedger.record(emptySet(), assertAccepted(judge(ReceiptFixtures.ORIGINAL_PASS1)))
        val later = assertAccepted(judge(ReceiptFixtures.ORIGINAL_PASS1.replace("11:49:50", "13:02:10")))
        assertFalse(ReceiptLedger.isUsed(ledger, later, ""))
    }

    @Test fun aPrintedProcessNumberBlocksReuseEvenIfTheMinuteWasMisread() {
        val shown = ReceiptFixtures.ORIGINAL_PASS1 + "\n$AR_LBL_PROC 20229627131\n"
        val ledger = ReceiptLedger.record(emptySet(), assertAccepted(judge(shown)))
        val misread = assertAccepted(judge(shown.replace("11:49:50", "11:48:50")))      // different fingerprint ...
        assertTrue(ReceiptLedger.isUsed(ledger, misread, ""))                             // ... same printed number
    }

    @Test fun plainNumbersFromThePreviousVersionAndArabicDigitsDoNotBypassTheLedger() {
        val v = assertAccepted(judge(ReceiptFixtures.ORIGINAL_PASS1))
        val legacy = setOf("20229627131")                                               // what the old code stored
        assertTrue(ReceiptLedger.isUsed(legacy, v, "20229627131"))
        assertTrue(ReceiptLedger.isUsed(legacy, v, "\u0662\u0660\u0662\u0662\u0669\u0666\u0662\u0667\u0661\u0663\u0661"))   // same digits in Arabic-Indic form
        assertTrue(ReceiptLedger.isUsed(legacy, v, "2022 9627 131"))
        assertFalse(ReceiptLedger.isUsed(legacy, v, "20229627132"))
    }

    @Test fun theLedgerNeverStoresRawNumbersAccountsOrNames() {
        val shown = ReceiptFixtures.ORIGINAL_PASS1 + "\n$AR_LBL_PROC 20229627131\n"
        val stored = ReceiptLedger.record(emptySet(), assertAccepted(judge(shown)))
        for (entry in stored) {
            assertFalse(entry.contains("20229627131"))
            assertFalse(entry.contains("2587655"))
        }
        assertEquals(2, stored.size)
    }
}
