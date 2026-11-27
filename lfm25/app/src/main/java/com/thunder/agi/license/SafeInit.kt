package com.thunder.agi.license

/**
 * Runs [create]; if it throws, calls [onFirstFailure] and [reset] once and tries again. A second failure propagates.
 *
 * For storage the OS can leave in an unreadable state (a restored backup whose Keystore key does not exist on this
 * install, a wiped Keystore, a buggy OEM Keystore): the right response is to start clean, not to crash. Pure Kotlin so
 * it can be unit-tested without a device.
 */
internal fun <T> createWithSingleReset(
    create: () -> T,
    reset: () -> Unit,
    onFirstFailure: (Exception) -> Unit = {}
): T = try {
    create()
} catch (first: Exception) {
    onFirstFailure(first)
    reset()
    create()
}
