package com.example

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import androidx.test.core.app.ApplicationProvider
import com.example.service.AutofillCredentialMatcher
import com.example.service.PackageManagerSigners
import com.example.service.PrivilegedBrowserList
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSigningInfo
import java.security.MessageDigest

/** The bundled privileged-apps list and the PackageManager certificate source. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivilegedBrowsersAssetTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun bundledList_isTheUnmodifiedDownload() {
        val bytes = context.assets.open(PrivilegedBrowserList.ASSET_NAME).use { it.readBytes() }
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("0891ee2507894999ead3eb4f42e17936a2e8ed8f8fd95592535c9811ef319481", sha256)
    }

    @Test
    fun bundledList_loadsOnce_withChromeReleaseCertificate_andNoUserdebugKeys() {
        val list = PrivilegedBrowserList.get(context)
        assertSame(list, PrivilegedBrowserList.get(context))

        assertEquals(
            setOf("F0FD6C5B410F25CB25C3B53346C8972FAE30F8EE7411DF910480AD6B2D60DB83"),
            list.fingerprints("com.android.chrome")
        )
        assertTrue("Only a userdebug key is listed", list.fingerprints("com.google.android.apps.chrome").isEmpty())
        // Browsers kept from the former allowlist are in Google's list...
        for (name in listOf(
            "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary", "com.brave.browser",
            "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.focus", "org.mozilla.fenix",
            "com.microsoft.emmx", "com.sec.android.app.sbrowser", "com.duckduckgo.mobile.android",
            "com.opera.browser", "com.opera.mini.native", "com.vivaldi.browser"
        )) {
            assertTrue(name, list.fingerprints(name).isNotEmpty())
            assertTrue("Never linked: $name", AutofillCredentialMatcher.isBrowserPackageName(name))
        }
        // ...the dropped ones are not (their webDomain is no longer trusted), but they are still never linked.
        for (name in listOf("com.kiwibrowser.browser", "org.cromite.cromite", "us.spotco.fennec_dos", "org.torproject.torbrowser")) {
            assertTrue(name, list.fingerprints(name).isEmpty())
            assertTrue(name, AutofillCredentialMatcher.isBrowserPackageName(name))
        }
    }

    private fun install(packageName: String, vararg certs: ByteArray) {
        val info = PackageInfo().apply {
            this.packageName = packageName
            firstInstallTime = 10L
            lastUpdateTime = 20L
            @Suppress("DEPRECATION")
            signatures = certs.map { Signature(it) }.toTypedArray()
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                signingInfo = Shadow.newInstanceOf(SigningInfo::class.java).also {
                    Shadow.extract<ShadowSigningInfo>(it).setSignatures(certs.map { c -> Signature(c) }.toTypedArray())
                }
            }
        }
        shadowOf(context.packageManager).installPackage(info)
    }

    @Test
    fun packageManagerSigners_api34_readsCurrentSigners() {
        val cert = byteArrayOf(1, 2, 3, 4)
        install("com.example.browser", cert)
        val signers = PackageManagerSigners(context.packageManager)

        assertEquals(10L to 20L, signers.installStamp("com.example.browser"))
        assertArrayEquals(cert, signers.currentSigningCertificates("com.example.browser").single())
        assertNull(signers.installStamp("com.example.missing"))
    }

    @Test
    @Config(sdk = [27])
    fun packageManagerSigners_api27_readsSignatures() {
        val cert = byteArrayOf(5, 6, 7)
        install("com.example.browser", cert)
        val signers = PackageManagerSigners(context.packageManager)

        assertArrayEquals(cert, signers.currentSigningCertificates("com.example.browser").single())
    }
}
