package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.autofill.AutofillId
import androidx.test.core.app.ApplicationProvider
import com.example.domain.models.VaultEntry
import com.example.repository.SettingsRepository
import com.example.service.AutofillFieldNode
import com.example.service.AutofillSave
import com.example.service.AutofillSave.SavePlan
import com.example.service.DetectedLoginFields
import com.example.service.LoginFormKind
import com.example.service.VaultAutofillService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Saving logins from autofill: what is watched, what a save carries, new entry or update. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutofillSaveTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun newId(): AutofillId = View(context).autofillId

    // --- What a fill response watches ---

    private fun fields(username: String? = null, password: String? = null, newPassword: String? = null) =
        DetectedLoginFields(username, password, "example.com", "https", LoginFormKind.LOGIN, null, newPassword)

    @Test
    fun saveFields_loginSignupChangePasswordAndTwoSteps() {
        val login = AutofillSave.saveFields(fields("u", "p"), null)!!
        assertEquals("p", login.requiredId)
        assertEquals(listOf("u"), login.optionalIds)
        assertFalse(login.isUsernameStep)

        // A change-password form: the current password is required (also typed on a sign-in form that sits next to
        // a sign-up form), the new one is read when it holds a value.
        val change = AutofillSave.saveFields(fields(password = "current", newPassword = "new"), null)!!
        assertEquals("current", change.requiredId)
        assertEquals(listOf("new"), change.optionalIds)
        assertEquals("new", change.newPasswordId)

        val usernameStep = AutofillSave.saveFields(fields(username = "u"), null)!!
        assertTrue(usernameStep.isUsernameStep)
        assertEquals("u", usernameStep.requiredId)

        val passwordStep = AutofillSave.saveFields(fields(password = "p"), previousUsernameId = "earlierUser")!!
        assertEquals("earlierUser", passwordStep.usernameId)
        assertEquals("p", passwordStep.requiredId)

        // An app reusing one box for the email and then the password: that box is not a username.
        assertNull(AutofillSave.saveFields(fields(password = "box"), previousUsernameId = "box")!!.usernameId)

        assertNull(AutofillSave.saveFields(fields(), null))
    }

    @Test
    fun saveInfo_usernameStepOnlyFromAndroid10_otherwiseAlways() {
        val step = AutofillSave.SaveFields(newId(), null, null)
        assertNull(VaultAutofillService.buildSaveInfo(step, Build.VERSION_CODES.P))
        assertNotNull(VaultAutofillService.buildSaveInfo(step, Build.VERSION_CODES.Q))
        val login = AutofillSave.SaveFields(newId(), newId(), null)
        assertNotNull(VaultAutofillService.buildSaveInfo(login, Build.VERSION_CODES.O))
    }

    @Test
    fun clientState_roundTrips_andTheEarlierUsernameIsReusedOnlyForTheSameAppAndPage() {
        val userId = newId()
        val step = AutofillSave.SaveState("com.android.chrome", "accounts.example.com", "https", AutofillSave.SaveFields(userId, null, null))
        val bundle = AutofillSave.clientState(step)
        assertEquals(step, AutofillSave.readState(bundle))

        val passwordId = newId()
        assertEquals(userId, AutofillSave.previousUsernameId(bundle, "com.android.chrome", "accounts.example.com", passwordId))
        assertNull("Another page", AutofillSave.previousUsernameId(bundle, "com.android.chrome", "evil.example.org", passwordId))
        assertNull("Another app", AutofillSave.previousUsernameId(bundle, "com.other.browser", "accounts.example.com", passwordId))

        // The password page's own state (a later request there, or the response built after unlocking) keeps the
        // username remembered for the same password field, and only for it.
        val passwordPage = AutofillSave.clientState(step.copy(fields = AutofillSave.SaveFields(userId, passwordId, null)))
        assertEquals(userId, AutofillSave.previousUsernameId(passwordPage, "com.android.chrome", "accounts.example.com", passwordId))
        assertNull(AutofillSave.previousUsernameId(passwordPage, "com.android.chrome", "accounts.example.com", newId()))
        assertNull(AutofillSave.readState(null))
    }

    // --- What a save request carries ---

    private class Node(
        override val autofillId: AutofillId?,
        override val textValue: String? = null,
        val children: List<Node> = emptyList()
    ) : AutofillFieldNode<AutofillId> {
        override val autofillHints: Array<String>? = null
        override val className: String? = null
        override val inputType: Int = 0
        override val isFocused: Boolean = false
        override val isFocusable: Boolean = true
        override val isClickable: Boolean = false
        override val isEnabled: Boolean = true
        override val visibility: Int = View.VISIBLE
        override val idEntry: String? = null
        override val hint: String? = null
        override val webDomain: String? = null
        override val childCount: Int get() = children.size
        override fun getChildAt(index: Int): AutofillFieldNode<AutofillId> = children[index]
    }

    private val userId by lazy { newId() }
    private val passId by lazy { newId() }
    private val oldId by lazy { newId() }

    private fun state(pkg: String = "com.android.chrome", domain: String? = "www.bank.example", scheme: String? = "https", old: Boolean = false) =
        AutofillSave.SaveState(pkg, domain, scheme, AutofillSave.SaveFields(userId, passId, if (old) oldId else null))

    private fun structure(user: String?, pass: String?, oldPassword: String? = null) = listOf(
        Node(null, children = listOf(Node(userId, user), Node(passId, pass), Node(oldId, oldPassword)))
    )

    @Test
    fun pendingSave_readsTheValues_withTheFillingTrustRules() {
        val own = context.packageName
        val web = AutofillSave.pendingSave(state(), listOf(structure(" alice@example.com ", "s3cret")), verifiedBrowser = true, ownPackage = own)!!
        assertEquals("www.bank.example", web.webDomain)
        assertEquals("alice@example.com", web.username)
        assertEquals("s3cret", web.password)

        val app = AutofillSave.pendingSave(state(pkg = "com.example.bankapp"), listOf(structure("alice", "pw")), false, own)!!
        assertNull("An app's page domain is never trusted", app.webDomain)
        assertNull(app.webScheme)

        assertNull("Unverified app with a browser's name", AutofillSave.pendingSave(state(pkg = "com.android.chrome"), listOf(structure("a", "b")), false, own))
        assertNull("Verified browser without a page domain", AutofillSave.pendingSave(state(domain = null), listOf(structure("a", "b")), true, own))
        assertNull("VaultPass itself", AutofillSave.pendingSave(state(pkg = own), listOf(structure("a", "b")), false, own))
        assertNull("No password", AutofillSave.pendingSave(state(), listOf(structure("a", "")), true, own))
        assertNull(AutofillSave.pendingSave(null, listOf(structure("a", "b")), true, own))

        val change = AutofillSave.pendingSave(state(old = true), listOf(structure("", "current", "new")), true, own)!!
        assertEquals("new", change.password)
        assertEquals("current", change.oldPassword)
        val signIn = AutofillSave.pendingSave(state(old = true), listOf(structure("alice", "current", "")), true, own)!!
        assertEquals("Sign-in form next to a sign-up form", "current", signIn.password)
        assertNull(signIn.oldPassword)
        assertEquals("http", AutofillSave.pendingSave(state(scheme = "HTTP"), listOf(structure("a", "b")), true, own)!!.webScheme)
    }

    @Test
    fun pendingSave_twoStepLogin_takesTheUsernameFromTheEarlierPage() {
        val page1 = listOf(Node(null, children = listOf(Node(userId, "alice"))))
        val page2 = listOf(Node(null, children = listOf(Node(passId, "pw"))))
        val save = AutofillSave.pendingSave(state(), listOf(page1, page2), true, context.packageName)!!
        assertEquals("alice", save.username)
        assertEquals("pw", save.password)

        // The field is still on page 2 but empty there: the earlier value counts.
        val page2WithEmptyUser = listOf(Node(null, children = listOf(Node(userId, ""), Node(passId, "pw"))))
        assertEquals("alice", AutofillSave.pendingSave(state(), listOf(page1, page2WithEmptyUser), true, context.packageName)!!.username)
    }

    @Test
    fun pendingSave_neverPrintsTheLogin() {
        val text = pending(username = "alice@example.com", password = "s3cret", old = "old-secret").toString()
        assertFalse(text.contains("alice@example.com"))
        assertFalse(text.contains("s3cret"))
        assertFalse(text.contains("old-secret"))
    }

    @Test
    fun saveSetup_trustRulesAndTheNeverSaveList() {
        val psl = TestPublicSuffixList.list
        val f = DetectedLoginFields(newId(), newId(), "login.bank.example", "https", LoginFormKind.LOGIN)
        fun setup(pkg: String?, fields: DetectedLoginFields<AutofillId> = f, verified: Boolean = false, never: Set<String> = emptySet()) =
            VaultAutofillService.saveSetupFor(context, pkg, fields, verified, psl, never, null, null)

        assertNotNull(setup("com.example.bankapp"))
        assertNotNull(setup("com.android.chrome", verified = true))
        assertNull("VaultPass itself", setup(context.packageName))
        assertNull("Unverified app with a browser's name", setup("com.android.chrome"))
        assertNull("A browser's own UI", setup("com.android.chrome", f.copy(webDomain = null), verified = true))
        assertNull(setup(null))
        assertNull(setup("com.example.bankapp", never = setOf("app:com.example.bankapp")))
        assertNull(setup("com.android.chrome", verified = true, never = setOf("web:bank.example")))
        assertNotNull("Another site of the list doesn't matter", setup("com.android.chrome", verified = true, never = setOf("web:other.example")))
        assertNull("No login field", setup("com.example.bankapp", DetectedLoginFields(null, null, null)))

        val state = AutofillSave.readState(setup("com.android.chrome", verified = true)!!.second)!!
        assertEquals("login.bank.example", state.webDomain)
        assertEquals(f.passwordId, state.fields.passwordId)
    }

    // --- New entry or update ---

    private fun pending(username: String = "alice", password: String = "new-pw", old: String? = null, domain: String? = "www.bank.example", scheme: String? = "https") =
        AutofillSave.PendingSave("com.android.chrome", domain, scheme, username, password, old)

    private fun entry(id: Int, username: String, password: String, failed: Boolean = false) =
        VaultEntry(id = id, title = "Bank $id", username = username, password = password, website = "https://bank.example", isDecryptionFailed = failed)

    @Test
    fun decide_newUpdateOrAlreadySaved() {
        val alice = entry(1, "Alice", "old-pw")
        val bob = entry(2, "bob", "bob-pw")
        assertEquals(SavePlan.New, AutofillSave.decide(emptyList(), pending()))
        assertEquals("Same username (case and spaces ignored)", SavePlan.Update(alice), AutofillSave.decide(listOf(bob, alice), pending(username = " alice ")))
        assertEquals(SavePlan.AlreadySaved, AutofillSave.decide(listOf(alice), pending(password = "old-pw")))
        assertEquals("Another username: a new entry", SavePlan.New, AutofillSave.decide(listOf(alice, bob), pending(username = "carol")))

        // Password-only form: the only entry for the site is updated; with several the user decides.
        assertEquals(SavePlan.Update(alice), AutofillSave.decide(listOf(alice), pending(username = "")))
        assertEquals(SavePlan.New, AutofillSave.decide(listOf(alice, bob), pending(username = "")))
        assertEquals(SavePlan.AlreadySaved, AutofillSave.decide(listOf(alice, bob), pending(username = "", password = "bob-pw")))

        // Change-password form: the entry holding the old password.
        assertEquals(SavePlan.Update(bob), AutofillSave.decide(listOf(alice, bob), pending(username = "", old = "bob-pw")))

        // The site's only entry without a username gets this login's username and password.
        val noName = entry(4, "", "old-pw")
        assertEquals(SavePlan.Update(noName), AutofillSave.decide(listOf(bob, noName), pending(username = "carol")))

        // Entries that can't be decrypted are never updated.
        assertEquals(SavePlan.New, AutofillSave.decide(listOf(entry(3, "alice", "x", failed = true)), pending()))
    }

    @Test
    fun newAndUpdatedEntries() {
        assertEquals("https://www.bank.example", AutofillSave.websiteFor(pending()))
        assertEquals("http://www.bank.example", AutofillSave.websiteFor(pending(scheme = "http")))
        assertEquals("androidapp://com.android.chrome", AutofillSave.websiteFor(pending(domain = null, scheme = null)))
        assertEquals("Unknown scheme (Android 8): bare host, never offered on http pages", "www.bank.example",
            AutofillSave.websiteFor(pending(scheme = null)))

        val created = AutofillSave.newEntry(pending(), " Bank ", " alice ", now = 42L)
        assertEquals("Bank", created.title)
        assertEquals("alice", created.username)
        assertEquals("new-pw", created.password)
        assertEquals(42L, created.timestamp)

        val updated = AutofillSave.updatedEntry(entry(1, "", "old-pw"), pending(), now = 7L)
        assertEquals("new-pw", updated.password)
        assertEquals("A blank username is filled from the form", "alice", updated.username)
        assertEquals(1, updated.id)
        assertEquals("alice", AutofillSave.updatedEntry(entry(1, "alice", "old"), pending(username = "other"), 7L).username)
    }

    @Test
    fun titlesAndNeverSaveKeys() {
        val psl = TestPublicSuffixList.list
        assertEquals("bank.example", AutofillSave.defaultTitle(pending(domain = "login.bank.example"), psl, null))
        assertEquals("Bank App", AutofillSave.defaultTitle(pending(domain = null), psl, "Bank App"))
        assertEquals("com.android.chrome", AutofillSave.defaultTitle(pending(domain = null), psl, null))

        assertEquals("web:bank.example", AutofillSave.neverSaveKey("com.android.chrome", "login.bank.example", psl))
        assertEquals("app:com.example.bankapp", AutofillSave.neverSaveKey("com.example.bankapp", null, psl))
    }

    @Test
    fun android8_neverReplacesAPasswordWithoutTheSaveScreen() {
        val alice = entry(1, "alice", "old-pw")
        assertEquals(AutofillSave.AutoSave.OPEN_APP, AutofillSave.autoSave(SavePlan.Update(alice), pending(), listOf(alice)))
        assertEquals(AutofillSave.AutoSave.INSERT, AutofillSave.autoSave(SavePlan.New, pending(), emptyList()))
        assertEquals(AutofillSave.AutoSave.ALREADY_SAVED, AutofillSave.autoSave(SavePlan.AlreadySaved, pending(), listOf(alice)))
        assertEquals("No username next to existing entries: the user decides", AutofillSave.AutoSave.OPEN_APP,
            AutofillSave.autoSave(SavePlan.New, pending(username = ""), listOf(alice, entry(2, "bob", "x"))))
    }

    // --- Hand-over to the save screen ---

    @Test
    fun store_handsOverByToken_andExpires() {
        var now = 1_000L
        AutofillSave.Store.clock = { now }
        try {
            val token = AutofillSave.Store.put(pending())
            assertTrue(token.matches(Regex("[0-9a-f]{32}")))
            assertEquals(pending(), AutofillSave.Store.peek(token))
            assertEquals("Peeking keeps it (screen rotation)", pending(), AutofillSave.Store.peek(token))
            AutofillSave.Store.remove(token)
            assertNull(AutofillSave.Store.peek(token))

            val old = AutofillSave.Store.put(pending())
            now += 10 * 60 * 1000L + 1
            assertNull("Expired after 10 minutes", AutofillSave.Store.peek(old))
        } finally {
            AutofillSave.Store.clock = { android.os.SystemClock.elapsedRealtime() }
        }
    }

    @Test
    fun saveIntent_carriesOnlyTheToken_andAFillInCantChangeIt() {
        val token = "0123456789abcdef0123456789abcdef"
        val intent = AutofillSave.createSaveIntent(context, token)
        assertEquals(com.example.ui.AutofillAuthActivity::class.java.name, intent.component?.className)
        assertEquals(token, AutofillSave.readSaveToken(intent))
        assertTrue("No login in the intent", intent.extras == null || intent.extras!!.isEmpty)
        intent.fillIn(Intent().apply { data = Uri.parse("vaultpass-autofill-save:ffffffffffffffffffffffffffffffff") }, 0)
        assertEquals(token, AutofillSave.readSaveToken(intent))
        assertNull(AutofillSave.readSaveToken(Intent().apply { data = Uri.parse("vaultpass-autofill-save:not-a-token") }))
        assertNull("Pick and unlock intents are not save intents", AutofillSave.readSaveToken(com.example.service.AutofillPick.createUnlockIntent(context)))
    }

    @Test
    fun neverSaveList_addAndRemove() = runBlocking {
        val settings = SettingsRepository(context)
        settings.addAutofillNeverSave("web:bank.example")
        settings.addAutofillNeverSave("app:com.example.bankapp")
        assertEquals(setOf("web:bank.example", "app:com.example.bankapp"), settings.autofillNeverSave.first())
        settings.removeAutofillNeverSave("web:bank.example")
        assertEquals(setOf("app:com.example.bankapp"), settings.autofillNeverSave.first())
        settings.removeAutofillNeverSave("app:com.example.bankapp")
    }
}
