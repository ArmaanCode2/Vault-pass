package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.autofill.AutofillId
import androidx.test.core.app.ApplicationProvider
import com.example.domain.models.CustomField
import com.example.domain.models.VaultEntry
import com.example.service.AutofillPick
import com.example.service.VaultAutofillService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Non-UI parts of the "Search VaultPass…" pick flow. The list screen and the framework round trip are device-only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutofillPickTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun newAutofillId(): AutofillId = View(context).autofillId

    private fun entry(
        title: String,
        username: String = "",
        password: String = "",
        website: String = "",
        failed: Boolean = false
    ) = VaultEntry(
        id = title.hashCode(),
        title = title,
        username = username,
        password = password,
        website = website,
        customFields = listOf(CustomField(key = "PIN", value = "9999")),
        isDecryptionFailed = failed
    )

    @Test
    fun fillValues_usernameIntoUsernameField_passwordIntoPasswordField() {
        val e = entry("Bank", username = "alice", password = "hunter2")
        assertEquals(listOf("u" to "alice", "p" to "hunter2"), AutofillPick.fillValues(e, "u", "p"))
        assertEquals(listOf("p" to "hunter2"), AutofillPick.fillValues(e, null, "p"))
        assertEquals(listOf("u" to "alice"), AutofillPick.fillValues(e, "u", null))
        assertEquals("Blank values are skipped", listOf("p" to "hunter2"), AutofillPick.fillValues(e.copy(username = " "), "u", "p"))
        assertTrue(AutofillPick.fillValues(entry("Empty"), "u", "p").isEmpty())
        assertTrue(AutofillPick.fillValues(e.copy(isDecryptionFailed = true), "u", "p").isEmpty())
    }

    private fun request(packageName: String, webDomain: String? = null) =
        AutofillPick.Request(packageName, null, null, webDomain)

    @Test
    fun linkDecision_nativeAppsOnly_neverBrowsersWebPagesOrVaultPassItself() {
        val own = context.packageName
        assertTrue(AutofillPick.shouldSaveLink(request("com.example.bankapp"), own))
        assertFalse(AutofillPick.shouldSaveLink(request("com.android.chrome"), own))
        assertFalse(AutofillPick.shouldSaveLink(request("org.mozilla.firefox"), own))
        assertFalse(AutofillPick.shouldSaveLink(request("com.sec.android.app.sbrowser"), own))
        assertFalse("A web page (verified browser) is never linked",
            AutofillPick.shouldSaveLink(request("com.example.otherbrowser", "bank.com"), own))
        assertFalse(AutofillPick.shouldSaveLink(request(own), own))
        assertFalse(AutofillPick.shouldSaveLink(request(""), own))
    }

    @Test
    fun siteMismatch_sameSiteFillsDirectly_otherSiteOrNoWebsiteAsks() {
        val psl = TestPublicSuffixList.list
        assertNull(AutofillPick.siteMismatch(entry("A", website = "https://bank.com"), "www.bank.com", psl))
        assertNull(AutofillPick.siteMismatch(entry("B", website = "login.bank.com"), "accounts.bank.com", psl))
        assertEquals("bank.com", AutofillPick.siteMismatch(entry("C", website = "https://bank.com"), "evil.org", psl))
        assertEquals("a.github.io", AutofillPick.siteMismatch(entry("D", website = "https://a.github.io"), "b.github.io", psl))
        assertEquals("", AutofillPick.siteMismatch(entry("E"), "evil.org", psl))
        assertEquals("", AutofillPick.siteMismatch(entry("F", website = "androidapp://com.bank"), "evil.org", psl))
        // Without the Public Suffix List hosts must be equal.
        assertNull(AutofillPick.siteMismatch(entry("G", website = "https://bank.com"), "bank.com", null))
        assertEquals("login.bank.com", AutofillPick.siteMismatch(entry("H", website = "login.bank.com"), "bank.com", null))
        assertEquals("evil.org", AutofillPick.siteOf("www.evil.org", psl))
    }

    @Test
    fun search_matchesTitleUsernameWebsite_neverSecrets() {
        val entries = listOf(
            entry("GitHub", username = "octo", password = "zebra-secret", website = "https://github.com"),
            entry("Bank", username = "alice", password = "pw"),
            entry("Broken", failed = true)
        )
        assertEquals(listOf("GitHub", "Bank"), AutofillPick.search(entries, "").map { it.title })
        assertEquals(listOf("GitHub"), AutofillPick.search(entries, "git").map { it.title })
        assertEquals(listOf("Bank"), AutofillPick.search(entries, "ALICE").map { it.title })
        assertEquals(listOf("GitHub"), AutofillPick.search(entries, "github.com octo").map { it.title })
        assertTrue("Passwords are not searched", AutofillPick.search(entries, "zebra").isEmpty())
        assertTrue("Custom field values are not searched", AutofillPick.search(entries, "9999").isEmpty())
        assertTrue("Undecryptable entries are not listed", AutofillPick.search(entries, "Broken").isEmpty())
    }

    @Test
    fun previewLine_isUsernameOrHost_neverThePassword() {
        assertEquals("alice", AutofillPick.previewLine(entry("A", username = "alice", password = "pw")))
        assertEquals("example.com", AutofillPick.previewLine(entry("B", password = "pw", website = "https://www.example.com/login")))
        assertEquals("", AutofillPick.previewLine(entry("C", password = "pw")))
    }

    @Test
    fun pickIntent_roundTrips_andTheFillInIntentCannotChangeThePackageOrMode() {
        val usernameId = newAutofillId()
        val passwordId = newAutofillId()
        val intent = AutofillPick.createIntent(context, "com.example.bankapp", null, usernameId, passwordId)
        assertEquals(com.example.ui.AutofillAuthActivity::class.java.name, intent.component?.className)

        val request = AutofillPick.readRequest(intent)!!
        assertEquals("com.example.bankapp", request.packageName)
        assertEquals(usernameId, request.usernameId)
        assertEquals(passwordId, request.passwordId)
        assertNull(request.webDomain)

        // What a PendingIntent does with the fill-in intent supplied when it is sent (flags as created: no FILL_IN_DATA).
        val fillIn = Intent().apply {
            data = Uri.parse("vaultpass-autofill-pick:com.attacker.app")
            putExtra("android.view.autofill.extra.ASSIST_STRUCTURE", "ignored")
        }
        intent.fillIn(fillIn, 0)
        assertEquals("com.example.bankapp", AutofillPick.readRequest(intent)!!.packageName)
        assertNull(AutofillPick.readRequest(intent)!!.webDomain)

        // A web pick carries the page's domain in the data URI too; the fill-in can't change or add it.
        val webIntent = AutofillPick.createIntent(context, "com.android.chrome", "bank.com", usernameId, passwordId)
        assertEquals("bank.com", AutofillPick.readRequest(webIntent)!!.webDomain)
        webIntent.fillIn(Intent().apply { data = Uri.parse("vaultpass-autofill-pick:com.android.chrome#evil.org") }, 0)
        assertEquals("bank.com", AutofillPick.readRequest(webIntent)!!.webDomain)
        assertEquals("com.android.chrome", AutofillPick.readRequest(webIntent)!!.packageName)

        val unlockIntent = AutofillPick.createUnlockIntent(context)
        assertEquals(com.example.ui.AutofillAuthActivity::class.java.name, unlockIntent.component?.className)
        assertNull("The unlock-chip intent is not pick mode", AutofillPick.readRequest(unlockIntent))
        unlockIntent.fillIn(fillIn, 0)
        assertNull("...and a fill-in intent can't make it one", AutofillPick.readRequest(unlockIntent))
        // Without a data URI of its own, an intent could be switched to pick mode by the fill-in.
        val bare = Intent(context, com.example.ui.AutofillAuthActivity::class.java)
        bare.fillIn(fillIn, 0)
        assertEquals("com.attacker.app", AutofillPick.readRequest(bare)?.packageName)
    }

    @Test
    fun pickedEntry_buildsADataset_onlyWhenThereIsSomethingToFill() {
        val usernameId = newAutofillId()
        val passwordId = newAutofillId()
        assertNotNull(VaultAutofillService.buildEntryDataset(context, entry("Bank", "alice", "pw"), usernameId, passwordId))
        assertNotNull(VaultAutofillService.buildEntryDataset(context, entry("PinOnly", password = "1234"), usernameId, passwordId))
        assertNull(VaultAutofillService.buildEntryDataset(context, entry("Empty"), usernameId, passwordId))
        assertNull(VaultAutofillService.buildEntryDataset(context, entry("NoPassword", username = "alice"), null, passwordId))
    }
}
