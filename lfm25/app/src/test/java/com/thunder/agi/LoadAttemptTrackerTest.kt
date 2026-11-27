package com.thunder.agi

import com.thunder.agi.llama.LoadAttemptTracker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * LoadAttemptTracker turns "the app silently closed during loading" (a native abort / OOM kill, which no Java handler
 * can see) into something the next start can detect, explain, and survive without a crash loop.
 *
 * A "process death" is simulated the way it really happens: begin() was called, finished() never was, and the next
 * process builds a NEW tracker on the same directory.
 */
class LoadAttemptTrackerTest {

    private lateinit var dir: File

    @Before fun setUp() { dir = Files.createTempDirectory("tracker-test").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun tracker() = LoadAttemptTracker(dir)               // a "new process"

    /** What the native side leaves behind: one breadcrumb per step, flushed before the step runs. */
    private fun writeBreadcrumbs(vararg steps: String) =
        File(dir, LoadAttemptTracker.TRACE_FILE).writeText(steps.mapIndexed { i, s -> "${1000 + i} $s" }.joinToString("\n") + "\n")

    @Test fun fresh_install_starts_at_the_normal_profile() {
        val plan = tracker().plan()
        assertEquals(0, plan.profile)
        assertNull(plan.blockedReason)
        assertNull(plan.previousCrashNote)
    }

    @Test fun a_load_that_comes_back_leaves_no_trace_of_a_crash() {
        val t = tracker()
        t.plan(); t.begin(0); t.finished(success = true, profile = 0)
        assertNull(tracker().peekPreviousCrash())
        assertEquals(0, tracker().consecutiveCrashes())
        assertEquals(0, tracker().plan().profile)
    }

    @Test fun a_process_death_during_load_is_detected_and_the_next_start_uses_a_gentler_profile() {
        run {   // process #1 dies inside native code
            val t = tracker(); t.plan(); t.begin(0)
            writeBreadcrumbs("load begin profile=0", "model loaded", "context create begin")
        }
        val t2 = tracker()
        val peek = t2.peekPreviousCrash()
        assertNotNull(peek)
        assertTrue("diagnosis names the last step: $peek", peek!!.contains("context create begin"))

        val plan = t2.plan()
        assertEquals("one crash -> conservative profile", 1, plan.profile)
        assertNull(plan.blockedReason)
        assertTrue(plan.previousCrashNote!!.contains("context create begin"))
        assertEquals(1, t2.consecutiveCrashes())
    }

    @Test fun peeking_does_not_consume_the_evidence() {
        run { val t = tracker(); t.plan(); t.begin(0); writeBreadcrumbs("context create begin") }
        val t2 = tracker()
        t2.peekPreviousCrash(); t2.peekPreviousCrash()
        assertEquals("still counted exactly once by plan()", 1, t2.run { plan(); consecutiveCrashes() })
    }

    @Test fun three_deaths_in_a_row_stop_the_automatic_loading_instead_of_crash_looping() {
        // death at level 0
        run { val t = tracker(); assertEquals(0, t.plan().profile); t.begin(0); writeBreadcrumbs("context create begin") }
        // death at level 1
        run { val t = tracker(); assertEquals(1, t.plan().profile); t.begin(1); writeBreadcrumbs("context create begin") }
        // death at level 2
        run { val t = tracker(); assertEquals(2, t.plan().profile); t.begin(2); writeBreadcrumbs("context create begin") }

        val plan = tracker().plan()
        assertNotNull("after 3 crashes the loader must refuse to run by itself", plan.blockedReason)
        val reason = plan.blockedReason ?: ""
        assertTrue(reason.contains("3 times in a row"))
        assertTrue(reason.contains("context create begin"))
        assertTrue(reason.contains("Retry"))

        // and it keeps refusing until the user acts
        assertNotNull(tracker().plan().blockedReason)
    }

    @Test fun the_user_pressing_retry_lifts_the_block_for_exactly_one_more_attempt() {
        repeat(3) { i -> val t = tracker(); t.plan(force = false); t.begin(minOf(i, 2)); writeBreadcrumbs("context create begin") }
        assertNotNull(tracker().plan().blockedReason)

        val retry = tracker().plan(force = true)
        assertNull(retry.blockedReason)
        assertEquals("retry uses the gentlest profile", 2, retry.profile)

        // that attempt dies as well -> blocked again, no loop
        val t = tracker(); t.begin(2); writeBreadcrumbs("context create begin")
        assertNotNull(tracker().plan().blockedReason)
    }

    @Test fun a_success_clears_the_crash_count_and_remembers_the_profile_that_worked() {
        run { val t = tracker(); t.plan(); t.begin(0); writeBreadcrumbs("context create begin") }   // died at 0
        val t2 = tracker(); val p = t2.plan(); assertEquals(1, p.profile)
        t2.begin(p.profile); t2.finished(success = true, profile = p.profile)                       // 1 worked

        val t3 = tracker()
        assertEquals(0, t3.consecutiveCrashes())
        assertEquals("next start begins at the level that worked", 1, t3.plan().profile)
        assertNull(t3.lastCrashNote())
    }

    @Test fun a_normal_failure_code_is_not_counted_as_a_crash() {
        val t = tracker(); t.plan(); t.begin(0)
        t.finished(success = false, profile = 0)          // native returned an error code: the process survived
        assertEquals(0, tracker().consecutiveCrashes())
        assertNull(tracker().peekPreviousCrash())
        assertEquals(0, tracker().plan().profile)
    }

    @Test fun a_damaged_state_file_is_ignored_not_fatal() {
        File(dir, LoadAttemptTracker.STATE_FILE).writeText("\u0000\u0000garbage \n=\n=== crashes=notanumber\ngood=99")
        File(dir, LoadAttemptTracker.ATTEMPT_FILE).delete()
        val plan = tracker().plan()
        assertEquals(0, plan.profile)
        assertNull(plan.blockedReason)
    }

    @Test fun the_trace_tail_returns_the_last_steps_without_timestamps() {
        writeBreadcrumbs("load begin profile=0", "backend ready", "model loaded", "context create begin")
        assertEquals(listOf("model loaded", "context create begin"), tracker().traceTail(2))
        assertFalse(tracker().traceTail().any { it.matches(Regex("^\\d+ .*")) })
    }

    @Test fun begin_truncates_the_breadcrumbs_so_each_attempt_has_its_own() {
        writeBreadcrumbs("old line from an earlier attempt")
        val t = tracker(); t.begin(0)
        assertTrue(t.traceTail().isEmpty())
    }

    @Test fun installing_a_new_build_wipes_the_crash_evidence_of_the_old_one() {
        // build "A" crashed three times and is blocked ...
        repeat(3) { i -> val a = LoadAttemptTracker(dir, "build-A"); a.plan(); a.begin(minOf(i, 2)); writeBreadcrumbs("context create begin") }
        assertNotNull(LoadAttemptTracker(dir, "build-A").plan().blockedReason)

        // ... the user installs the fixed APK over it: build "B" must get a clean chance, not stay blocked
        val b = LoadAttemptTracker(dir, "build-B")
        assertNull("old crash evidence must not be reported for the new build", b.peekPreviousCrash())
        val plan = b.plan()
        assertNull(plan.blockedReason)
        assertEquals(0, plan.profile)
        assertEquals(0, b.consecutiveCrashes())
    }

    @Test fun a_marker_left_by_the_same_build_is_still_counted() {
        run { val a = LoadAttemptTracker(dir, "build-A"); a.plan(); a.begin(0); writeBreadcrumbs("context create begin") }
        val again = LoadAttemptTracker(dir, "build-A")
        assertNotNull(again.peekPreviousCrash())
        assertEquals(1, again.plan().profile)
    }
}
