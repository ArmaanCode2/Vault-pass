package com.example

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.service.ChromeAutofillSetup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Reading Chrome's "Autofill using another service" switch and opening its settings page. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChromeAutofillSetupTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Chrome's provider: one row, one column, 1 = another service fills, 0 = Chrome's own autofill. */
    class FakeChromeProvider : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
            if (uri.path != "/autofill_third_party_mode") return null
            return MatrixCursor(arrayOf(ChromeAutofillSetup.STATE_COLUMN)).apply { addRow(arrayOf(state)) }
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

        companion object {
            var state = 0
        }
    }

    @Test
    fun state_readsChromesProvider_unknownWithoutOne() {
        Robolectric.setupContentProvider(FakeChromeProvider::class.java, "com.android.chrome.AutofillThirdPartyModeContentProvider")

        FakeChromeProvider.state = 1
        assertEquals(ChromeAutofillSetup.State.ON, ChromeAutofillSetup.state(context, "com.android.chrome"))
        FakeChromeProvider.state = 0
        assertEquals(ChromeAutofillSetup.State.OFF, ChromeAutofillSetup.state(context, "com.android.chrome"))
        // Chrome Beta has its own provider; none here, as with a Chrome older than 135.
        assertEquals(ChromeAutofillSetup.State.UNKNOWN, ChromeAutofillSetup.state(context, "com.chrome.beta"))
    }

    @Test
    fun providerUri_isPerChannel() {
        assertEquals(
            "content://com.chrome.beta.AutofillThirdPartyModeContentProvider/autofill_third_party_mode",
            ChromeAutofillSetup.providerUri("com.chrome.beta").toString()
        )
    }

    @Test
    fun settingsIntent_matchesChromesIntentFilter() {
        val intent = ChromeAutofillSetup.settingsIntent("com.android.chrome")
        assertEquals(Intent.ACTION_APPLICATION_PREFERENCES, intent.action)
        assertEquals(setOf(Intent.CATEGORY_DEFAULT, Intent.CATEGORY_APP_BROWSER, Intent.CATEGORY_PREFERENCE), intent.categories)
        assertEquals("com.android.chrome", intent.`package`)
    }

    @Test
    fun installedChannels_onlyChromesThatAreInstalled() {
        assertTrue(ChromeAutofillSetup.installedChannels(context).isEmpty())
        shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = "com.chrome.beta" })
        assertEquals(listOf("com.chrome.beta"), ChromeAutofillSetup.installedChannels(context))
    }
}
