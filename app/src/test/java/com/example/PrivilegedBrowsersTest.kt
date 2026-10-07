package com.example

import com.example.service.BrowserVerifier
import com.example.service.InstalledPackageSigners
import com.example.service.PrivilegedBrowserList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** H2: a webDomain is trusted only from a listed package signed with a listed release certificate. */
class PrivilegedBrowsersTest {

    private val chromeCert = "chrome-release-cert".toByteArray()
    private val chromeDebugCert = "chrome-userdebug-cert".toByteArray()
    private val rotatedCert = "beta-second-cert".toByteArray()
    private val attackerCert = "attacker-cert".toByteArray()

    /** `AB:CD:...` like the published list. */
    private fun colonFingerprint(cert: ByteArray) =
        PrivilegedBrowserList.certificateFingerprint(cert).chunked(2).joinToString(":")

    private val listJson = """
        {
          "apps": [
            {"type": "android", "info": {"package_name": "com.android.chrome", "signatures": [
              {"build": "release", "cert_fingerprint_sha256": "${colonFingerprint(chromeCert)}"},
              {"build": "userdebug", "cert_fingerprint_sha256": "${colonFingerprint(chromeDebugCert)}"}
            ]}},
            {"type": "android", "info": {"package_name": "com.chrome.beta", "signatures": [
              {"build": "release", "cert_fingerprint_sha256": "${colonFingerprint(chromeCert).lowercase()}"},
              {"build": "release", "cert_fingerprint_sha256": "${colonFingerprint(rotatedCert)}"}
            ]}},
            {"type": "android", "info": {"package_name": "com.debug.only", "signatures": [
              {"build": "userdebug", "cert_fingerprint_sha256": "${colonFingerprint(chromeDebugCert)}"}
            ]}},
            {"type": "android", "info": {"package_name": "com.bad.fingerprint", "signatures": [
              {"build": "release", "cert_fingerprint_sha256": "12:34"}
            ]}},
            {"type": "web", "info": {"package_name": "com.not.android", "signatures": [
              {"build": "release", "cert_fingerprint_sha256": "${colonFingerprint(chromeCert)}"}
            ]}}
          ]
        }
    """.trimIndent()

    private val list = PrivilegedBrowserList.parse(listJson.byteInputStream())

    private class FakeSigners : InstalledPackageSigners {
        val installed = mutableMapOf<String, List<ByteArray>>()
        val stamps = mutableMapOf<String, Pair<Long, Long>>()
        var certificateReads = 0
        var failWith: Exception? = null

        override fun installStamp(packageName: String): Pair<Long, Long>? {
            failWith?.let { throw it }
            if (packageName !in installed) return null
            return stamps[packageName] ?: (1L to 1L)
        }

        override fun currentSigningCertificates(packageName: String): List<ByteArray> {
            failWith?.let { throw it }
            certificateReads++
            return installed[packageName].orEmpty()
        }
    }

    private fun verifier(signers: FakeSigners) = BrowserVerifier({ list }, signers)

    @Test
    fun parse_keepsReleaseFingerprintsOfAndroidAppsOnly() {
        assertEquals(setOf("com.android.chrome", "com.chrome.beta"), list.packageNames)
        assertEquals(setOf(PrivilegedBrowserList.certificateFingerprint(chromeCert)), list.fingerprints("com.android.chrome"))
        assertEquals(2, list.fingerprints("com.chrome.beta").size)
        assertTrue("userdebug-only apps are not trusted", list.fingerprints("com.debug.only").isEmpty())
        assertTrue(list.fingerprints("com.not.android").isEmpty())
    }

    @Test
    fun normalizeFingerprint() {
        val hex = "F0FD6C5B410F25CB25C3B53346C8972FAE30F8EE7411DF910480AD6B2D60DB83"
        assertEquals(hex, PrivilegedBrowserList.normalizeFingerprint(hex.chunked(2).joinToString(":").lowercase()))
        assertNull(PrivilegedBrowserList.normalizeFingerprint("12:34"))
        assertNull(PrivilegedBrowserList.normalizeFingerprint(hex.dropLast(2) + "ZZ"))
        assertNull(PrivilegedBrowserList.normalizeFingerprint(null))
    }

    @Test
    fun listedPackage_signedWithListedReleaseCert_isVerified() {
        val signers = FakeSigners().apply {
            installed["com.android.chrome"] = listOf(chromeCert)
            installed["com.chrome.beta"] = listOf(rotatedCert)
        }
        val verifier = verifier(signers)
        assertTrue(verifier.isVerifiedBrowser("com.android.chrome"))
        assertTrue("Any of the listed fingerprints", verifier.isVerifiedBrowser("com.chrome.beta"))
    }

    @Test
    fun sideloadedAppUsingABrowserName_isNotVerified() {
        val signers = FakeSigners().apply { installed["com.android.chrome"] = listOf(attackerCert) }
        assertFalse(verifier(signers).isVerifiedBrowser("com.android.chrome"))
    }

    @Test
    fun userdebugCertificate_isNotTrusted() {
        val signers = FakeSigners().apply {
            installed["com.android.chrome"] = listOf(chromeDebugCert)
            installed["com.debug.only"] = listOf(chromeDebugCert)
        }
        assertFalse(verifier(signers).isVerifiedBrowser("com.android.chrome"))
        assertFalse(verifier(signers).isVerifiedBrowser("com.debug.only"))
    }

    @Test
    fun multipleSigners_allMustBeListed() {
        val signers = FakeSigners().apply { installed["com.android.chrome"] = listOf(chromeCert, attackerCert) }
        assertFalse(verifier(signers).isVerifiedBrowser("com.android.chrome"))
    }

    @Test
    fun unlistedOrNotInstalledOrNoCertificates_isNotVerified() {
        val signers = FakeSigners().apply {
            installed["com.kiwibrowser.browser"] = listOf(chromeCert)
            installed["com.chrome.beta"] = emptyList()
        }
        val verifier = verifier(signers)
        assertFalse("Not in the list", verifier.isVerifiedBrowser("com.kiwibrowser.browser"))
        assertFalse("Listed but not installed", verifier.isVerifiedBrowser("com.android.chrome"))
        assertFalse("No certificates", verifier.isVerifiedBrowser("com.chrome.beta"))
        assertFalse(verifier.isVerifiedBrowser(null))
        assertFalse(verifier.isVerifiedBrowser(""))
    }

    @Test
    fun anyError_meansNotVerified() {
        val signers = FakeSigners().apply {
            installed["com.android.chrome"] = listOf(chromeCert)
            failWith = SecurityException("no access")
        }
        assertFalse(verifier(signers).isVerifiedBrowser("com.android.chrome"))
        val brokenList = BrowserVerifier({ error("asset missing") }, FakeSigners().apply { installed["com.android.chrome"] = listOf(chromeCert) })
        assertFalse(brokenList.isVerifiedBrowser("com.android.chrome"))
    }

    @Test
    fun result_isCachedPerPackage_untilThePackageIsReinstalledOrUpdated() {
        val signers = FakeSigners().apply { installed["com.android.chrome"] = listOf(chromeCert) }
        val verifier = verifier(signers)
        assertTrue(verifier.isVerifiedBrowser("com.android.chrome"))
        assertTrue(verifier.isVerifiedBrowser("com.android.chrome"))
        assertEquals("Certificates read once", 1, signers.certificateReads)

        // Uninstalled and replaced by a lookalike: new install stamp -> checked again.
        signers.installed["com.android.chrome"] = listOf(attackerCert)
        signers.stamps["com.android.chrome"] = 2L to 2L
        assertFalse(verifier.isVerifiedBrowser("com.android.chrome"))
        assertEquals(2, signers.certificateReads)

        // Not installed any more: never trusted from the cache.
        signers.installed.remove("com.android.chrome")
        assertFalse(verifier.isVerifiedBrowser("com.android.chrome"))
    }
}
