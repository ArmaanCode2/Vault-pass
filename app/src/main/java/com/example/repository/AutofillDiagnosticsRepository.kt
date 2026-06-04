package com.example.repository

import android.util.Log

class AutofillDiagnosticsRepository {
    
    private val TAG = "AutofillDiagnostics"

    fun log(message: String) {
        Log.d(TAG, message)
    }

    fun logError(error: String) {
        Log.e(TAG, error)
    }

    fun updateRequestStart() {
        Log.d(TAG, "--- New Autofill Request Started ---")
    }

    fun updatePackageAndDomain(packageName: String?, webDomain: String?) {
        Log.d(TAG, "Package: ${packageName ?: "None"}, WebDomain: ${webDomain ?: "None"}")
    }

    fun updateMatches(matched: Int, returned: Int) {
        Log.d(TAG, "Matches found: $matched, Datasets returned: $returned")
    }
}
