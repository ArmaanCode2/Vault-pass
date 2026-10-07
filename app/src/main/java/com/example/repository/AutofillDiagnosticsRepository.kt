package com.example.repository

import android.util.Log

/**
 * Autofill diagnostics for debug builds. Release builds write nothing: [enabled] defaults to BuildConfig.DEBUG.
 * Callers never pass entry titles, usernames, passwords, notes or field values; counts, scores and match
 * reasons only (package names and web domains appear in debug builds only, because nothing is written otherwise).
 */
class AutofillDiagnosticsRepository(
    private val enabled: Boolean = com.example.BuildConfig.DEBUG,
    private val sink: Sink = AndroidLogSink
) {

    /** Where diagnostics go when [enabled]; tests inject their own. */
    fun interface Sink {
        fun write(isError: Boolean, tag: String, message: String)
    }

    private object AndroidLogSink : Sink {
        override fun write(isError: Boolean, tag: String, message: String) {
            if (isError) Log.e(tag, message) else Log.d(tag, message)
        }
    }

    private val TAG = "AutofillDiagnostics"

    fun log(message: String) {
        if (enabled) sink.write(false, TAG, message)
    }

    fun logError(error: String) {
        if (enabled) sink.write(true, TAG, error)
    }

    fun updateRequestStart() {
        log("--- New Autofill Request Started ---")
    }

    fun updatePackageAndDomain(packageName: String?, webDomain: String?) {
        log("Package: ${packageName ?: "None"}, WebDomain: ${webDomain ?: "None"}")
    }

    fun updateMatches(matched: Int, returned: Int) {
        log("Matches found: $matched, Datasets returned: $returned")
    }
}
