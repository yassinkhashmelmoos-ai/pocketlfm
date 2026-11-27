package com.thunder.agi

import android.app.Application
import com.thunder.agi.agent.CrashReporter

/**
 * Process entry point. Exists for one reason: CrashReporter was written (and imported by ChatViewModel) but never
 * installed, because the app had no Application class - so uncaught Java exceptions were never recorded and the
 * in-app "Error Report" dialog could never have anything to show.
 */
class ThunderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            CrashReporter.install(this)
        } catch (t: Throwable) {
            // diagnostics must never be the reason the app fails to start
        }
    }
}
