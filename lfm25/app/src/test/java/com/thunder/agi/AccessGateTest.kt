package com.thunder.agi

import com.thunder.agi.license.LicenseValidator
import com.thunder.agi.subscription.AccessGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AccessGateTest {

    private fun license(status: LicenseValidator.Status, expiry: String) =
        LicenseValidator.LicenseInfo(status = status, username = "u", expiryDate = expiry, daysRemaining = 5)

    @Test fun aLicenseWorksThroughTheWholeExpiryDayInUtc() {
        // "expires 2026-11-01" means usable until the NEXT UTC midnight, exactly like LicenseValidator.check()
        assertEquals(Instant.parse("2026-11-02T00:00:00Z").toEpochMilli(), AccessGate.licenseValidUntilMillis("2026-11-01"))
    }

    @Test fun malformedOrImpossibleDatesNeverUnlock() {
        for (d in listOf("", "soon", "2026-13-01", "2026-02-30", "01-11-2026", "2026/11/01"))
            assertEquals("\"$d\"", 0L, AccessGate.licenseValidUntilMillis(d))
    }

    @Test fun onlyAValidLicenseResultOpensTheGate() {
        val endOfDay = Instant.parse("2026-11-02T00:00:00Z").toEpochMilli()
        AccessGate.updateLicense(license(LicenseValidator.Status.VALID, "2026-11-01"))
        assertTrue(AccessGate.licenseAllows(endOfDay - 1))
        assertFalse(AccessGate.licenseAllows(endOfDay))                    // the moment it ends, it ends

        for (bad in listOf(LicenseValidator.Status.EXPIRED, LicenseValidator.Status.INVALID, LicenseValidator.Status.NOT_ACTIVATED)) {
            AccessGate.updateLicense(license(LicenseValidator.Status.VALID, "2026-11-01"))   // open it first ...
            AccessGate.updateLicense(license(bad, "2026-11-01"))                              // ... then a newer result closes it
            assertFalse("$bad", AccessGate.licenseAllows(endOfDay - 1))
        }

        AccessGate.updateLicense(license(LicenseValidator.Status.VALID, "garbage"))          // valid status but unreadable date
        assertFalse(AccessGate.licenseAllows(0L))
    }
}
