package com.lowdistraction.launcher

import android.app.Application

/**
 * Starts diagnostics as early as possible so the crash handler is installed
 * before any screen is created — a crash on the very first launch is exactly
 * the kind of thing the log needs to catch.
 */
class LowDistractionApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Diag.install(this)
    }
}
