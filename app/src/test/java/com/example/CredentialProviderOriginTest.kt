package com.example

import android.content.pm.Signature
import androidx.credentials.provider.CallingAppInfo
import com.example.service.CredentialProviderSupport
import com.example.service.PrivilegedBrowserList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The allowlist VaultPass builds is read by androidx's own `CallingAppInfo.getOrigin`: a listed browser's origin comes
 * through, a userdebug-only certificate or an unlisted app that sets an origin does not.
 */
@RunWith(RobolectricTestRunner::class)
// Below Android 9 CallingAppInfo takes a plain signature list (a SigningInfo can't be built in a test before Android 15).
@Config(sdk = [27])
class CredentialProviderOriginTest {

    private val release = "browser-release-cert".toByteArray()
    private val debug = "browser-userdebug-cert".toByteArray()

    private fun colon(cert: ByteArray) = PrivilegedBrowserList.certificateFingerprint(cert).chunked(2).joinToString(":")

    private val allowlist = CredentialProviderSupport.allowlistJson(
        PrivilegedBrowserList.parse(
            """{"apps":[{"type":"android","info":{"package_name":"com.browser","signatures":[
                {"build":"release","cert_fingerprint_sha256":"${colon(release)}"},
                {"build":"userdebug","cert_fingerprint_sha256":"${colon(debug)}"}]}}]}""".byteInputStream()
        )
    )

    private fun expectUntrusted(info: CallingAppInfo) {
        try {
            info.getOrigin(allowlist)
            fail("An origin from an app that isn't listed must not be returned")
        } catch (e: IllegalStateException) {
            // What CredentialProviderSupport.resolveCaller turns into "Untrusted".
        }
    }

    @Test
    fun listedBrowser_getsItsOrigin_othersDoNot() {
        assertEquals(
            "https://example.com",
            CallingAppInfo("com.browser", listOf(Signature(release)), "https://example.com").getOrigin(allowlist)
        )
        expectUntrusted(CallingAppInfo("com.browser", listOf(Signature(debug)), "https://example.com"))
        expectUntrusted(CallingAppInfo("com.other", listOf(Signature(release)), "https://example.com"))
        assertNull("An app that sets no origin asks for itself", CallingAppInfo("com.other", listOf(Signature(release)), null).getOrigin(allowlist))
    }
}
