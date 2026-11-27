package com.thunder.agi

import com.thunder.agi.license.createWithSingleReset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.GeneralSecurityException

/**
 * createWithSingleReset() is what stops an unreadable EncryptedSharedPreferences (restored backup / wiped Keystore key)
 * from killing the app on launch: reset the storage once and try again, instead of throwing on the main thread.
 */
class SafeInitTest {

    @Test fun a_healthy_store_is_created_without_any_reset() {
        var resets = 0
        val v = createWithSingleReset(create = { "prefs" }, reset = { resets++ })
        assertEquals("prefs", v)
        assertEquals(0, resets)
    }

    @Test fun an_unreadable_store_is_reset_once_and_then_created() {
        var attempts = 0
        var resets = 0
        var reported: Exception? = null
        val v = createWithSingleReset(
            create = { if (attempts++ == 0) throw GeneralSecurityException("AEADBadTagException: key gone") else "fresh prefs" },
            reset = { resets++ },
            onFirstFailure = { reported = it }
        )
        assertEquals("fresh prefs", v)
        assertEquals(1, resets)
        assertEquals(2, attempts)
        assertTrue(reported is GeneralSecurityException)
    }

    @Test fun a_store_that_stays_broken_fails_after_exactly_one_reset() {
        var attempts = 0
        var resets = 0
        try {
            createWithSingleReset<String>(create = { attempts++; throw IllegalStateException("still broken") }, reset = { resets++ })
            fail("expected the second failure to propagate")
        } catch (e: IllegalStateException) {
            assertEquals("still broken", e.message)
        }
        assertEquals(2, attempts)
        assertEquals("no reset loop", 1, resets)
    }
}
