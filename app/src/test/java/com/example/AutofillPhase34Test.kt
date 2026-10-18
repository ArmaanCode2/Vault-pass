package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.example.domain.models.CustomField
import com.example.domain.models.EntryWebsites
import com.example.domain.models.VaultEntry
import com.example.repository.AutofillHistoryItem
import com.example.repository.AutofillHistoryRepository
import com.example.repository.SettingsRepository
import com.example.security.CryptoManager
import com.example.service.AppSignatureCheck
import com.example.service.AppWebsites
import com.example.service.AutofillCredentialMatcher
import com.example.service.AutofillPick
import com.example.service.PackageSigners
import com.example.service.PasswordRules
import com.example.service.PasswordRulesList
import com.example.service.RelatedSites
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
import java.io.File

/** Phase 3 (generator, history, tile intents) and Phase 4 (extra websites, related sites, app signatures, app websites). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutofillPhase34Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun asset(name: String): File =
        listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name")).first { it.isFile }

    // --- Password rules ---

    @Test
    fun passwordRules_parseClassesAndCustomCharacters() {
        val rules = PasswordRules.parse("minlength: 8; maxlength: 16; required: lower, upper; required: digit; allowed: [-_.]; max-consecutive: 2;")
        assertEquals(8, rules.minLength)
        assertEquals(16, rules.maxLength)
        assertEquals(2, rules.required.size)
        assertEquals(2, rules.maxConsecutive)
        assertTrue('-' in rules.allowed && '_' in rules.allowed && '.' in rules.allowed)
        assertEquals("Default 20 within the site's maximum", 16, rules.length)

        // A custom class may hold ';' and ',' and end with ']' ("[...]]").
        val tricky = PasswordRules.parse("""required: digit; required: [- !"#$&'()*+,.:;<=>?@[^_`{|}~]]; allowed: lower, upper;""")
        val special = tricky.required[1]
        assertTrue(';' in special && ',' in special && ']' in special && '[' in special && '-' in special)
        assertFalse("Space is never generated", ' ' in special)
    }

    @Test
    fun passwordRules_generatedPasswordsFollowTheRules() {
        val rules = PasswordRules.parse("minlength: 10; maxlength: 12; required: upper; required: digit; required: [!#$]; allowed: lower; max-consecutive: 1;")
        repeat(200) {
            val password = rules.generate()
            assertEquals(12, password.length)
            assertTrue(password.any { it.isUpperCase() })
            assertTrue(password.any { it.isDigit() })
            assertTrue(password.any { it in "!#$" })
            assertTrue(password.all { it.isLetterOrDigit() || it in "!#$" })
            assertTrue(rules.respectsMaxConsecutive(password))
        }
        val plain = PasswordRules().generate()
        assertEquals(PasswordRules.DEFAULT_LENGTH, plain.length)
        assertTrue(plain.all { it.code in 0x21..0x7E })
    }

    @Test
    fun passwordRulesList_bundledListParses_andSubdomainsUseTheSitesRules() {
        val list = asset(PasswordRulesList.ASSET_NAME).inputStream().use { PasswordRulesList.parse(it) }
        val rules = list.forHost("access.service.gov.uk")
        assertNotNull(rules)
        assertEquals(10, rules!!.minLength)
        assertNotNull("A subdomain gets its site's rules", list.forHost("www.163.com"))
        assertNull(list.forHost("no-rules.example"))

        // Every rule in the bundled list yields a password that follows it.
        val json = kotlinx.serialization.json.Json.parseToJsonElement(asset(PasswordRulesList.ASSET_NAME).readText()) as kotlinx.serialization.json.JsonObject
        for (domain in json.keys) {
            val siteRules = list.forHost(domain)!!
            val password = siteRules.generate()
            siteRules.minLength?.let { assertTrue(domain, password.length >= it) }
            siteRules.maxLength?.let { assertTrue(domain, password.length <= it) }
            assertTrue(domain, siteRules.required.all { set -> password.any { it in set } })
            assertTrue(domain, password.all { it in siteRules.alphabet })
        }
    }

    // --- Autofill history ---

    @Test
    fun history_isEncrypted_keepsTheNewest_andIsUnreadableWhileLocked() = runBlocking {
        val settings = SettingsRepository(context)
        val crypto = CryptoManager(settings).apply { injectSoftwareDek(ByteArray(32) { it.toByte() }) }
        val history = AutofillHistoryRepository(settings, crypto)
        history.clear()
        repeat(AutofillHistoryRepository.MAX_ITEMS + 3) { n ->
            history.add(AutofillHistoryItem("site$n.example", "secret-$n", AutofillHistoryItem.Kind.GENERATED, n.toLong()))
        }
        val items = history.items()
        assertEquals(AutofillHistoryRepository.MAX_ITEMS, items.size)
        assertEquals("Newest first", "secret-${AutofillHistoryRepository.MAX_ITEMS + 2}", items.first().password)

        val blob = settings.autofillHistoryBlob.first()!!
        assertFalse("Stored encrypted", blob.contains("secret-") || blob.contains("site"))
        assertFalse(items.first().toString().contains("secret-"))

        crypto.clearSoftwareDek()
        assertTrue(history.items().isEmpty())

        // A history that can't be decrypted (another key) is never overwritten.
        val other = CryptoManager(settings).apply { injectSoftwareDek(ByteArray(32) { 7 }) }
        val otherHistory = AutofillHistoryRepository(settings, other)
        assertTrue(otherHistory.items().isEmpty())
        try {
            otherHistory.add(AutofillHistoryItem("x.example", "new", AutofillHistoryItem.Kind.GENERATED, 1L))
            org.junit.Assert.fail("Adding must not overwrite an unreadable history")
        } catch (expected: IllegalStateException) {
        }
        crypto.injectSoftwareDek(ByteArray(32) { it.toByte() })
        assertEquals(AutofillHistoryRepository.MAX_ITEMS, history.items().size)
        history.clear()
        assertTrue(history.items().isEmpty())
    }

    // --- Intents for generate, suggestions and the tile ---

    @Test
    fun generateAndSuggestionIntents_roundTrip() {
        val ids = listOf(View(context).autofillId, View(context).autofillId)
        val user = View(context).autofillId
        val generate = AutofillPick.GenerateRequest("com.android.chrome", "shop.example", "http", ids, user, null)
        val read = AutofillPick.readGenerateRequest(AutofillPick.createGenerateIntent(context, generate))!!
        assertEquals(generate, read)
        assertNull("A pick intent is not a generate intent", AutofillPick.readGenerateRequest(AutofillPick.createIntent(context, "com.x", null, null, null)))

        val suggestion = AutofillPick.Suggestion("sync-1", "adobe.com", AutofillPick.SuggestionKind.RELATED_SITE)
        val pick = AutofillPick.readRequest(
            AutofillPick.createIntent(context, "com.android.chrome", "www.behance.net", user, null, suggestion = suggestion, signatureChanged = true)
        )!!
        assertEquals(suggestion, pick.suggestion)
        assertTrue(pick.signatureChanged)
        assertNull(AutofillPick.readRequest(AutofillPick.createIntent(context, "com.x", null, null, null))!!.suggestion)

        val tile = AutofillPick.createQuickCopyIntent(context)
        assertTrue(AutofillPick.isQuickCopy(tile))
        assertNull(AutofillPick.readRequest(tile))
        assertFalse(AutofillPick.isQuickCopy(Intent().apply { data = Uri.parse("vaultpass-autofill-pick:com.x") }))
    }

    // --- Extra websites ---

    private val psl get() = TestPublicSuffixList.list

    private fun entry(website: String, vararg extra: String, id: Int = 1) = VaultEntry(
        id = id, title = "T$id", username = "u", password = "p", website = website, syncId = "s$id",
        customFields = listOf(CustomField("PIN", "1234")) + extra.map { CustomField(EntryWebsites.KEY, it) }
    )

    @Test
    fun extraWebsites_areStoredAsReservedCustomFields_andMatchLikeTheMainOne() {
        val e = entry("https://login.live.com", "outlook.com", "xbox.com")
        assertEquals(listOf("https://login.live.com", "outlook.com", "xbox.com"), EntryWebsites.all(e))
        assertEquals(listOf(CustomField("PIN", "1234")), EntryWebsites.otherFields(e.customFields))
        assertEquals(e.customFields, EntryWebsites.combine(EntryWebsites.otherFields(e.customFields), EntryWebsites.extra(e)))

        val chrome = "com.android.chrome"
        assertEquals(200, AutofillCredentialMatcher.calculateMatchScore(e, chrome, "www.xbox.com", psl, verifiedBrowser = true))
        assertEquals(150, AutofillCredentialMatcher.calculateMatchScore(e, chrome, "account.outlook.com", psl, verifiedBrowser = true))
        assertEquals(0, AutofillCredentialMatcher.calculateMatchScore(e, chrome, "evil.example", psl, verifiedBrowser = true))
        // The http rule holds per website: only an http:// one is offered on an http page.
        val http = entry("https://bank.example", "http://bank.example")
        assertEquals(200, AutofillCredentialMatcher.calculateMatchScore(http, chrome, "bank.example", psl, verifiedBrowser = true, webScheme = "http"))
        assertEquals(0, AutofillCredentialMatcher.calculateMatchScore(entry("https://bank.example"), chrome, "bank.example", psl, verifiedBrowser = true, webScheme = "http"))
        // androidapp:// as an extra website links the app.
        assertEquals(200, AutofillCredentialMatcher.calculateMatchScore(entry("https://bank.example", "androidapp://com.bank"), "com.bank", null, psl, verifiedBrowser = false))
        assertTrue(AutofillPick.search(listOf(e), "xbox").isNotEmpty())

        // The picker's site checks use every website too.
        assertNull(AutofillPick.siteMismatch(e, "www.xbox.com", psl))
        assertEquals("live.com", AutofillPick.siteMismatch(e, "evil.example", psl))
        assertEquals("", AutofillPick.siteMismatch(entry(""), "evil.example", psl))
        assertNull("An extra website alone is a website", AutofillPick.siteMismatch(entry("", "outlook.com"), "outlook.com", psl))
        val httpPick = AutofillPick.Request("com.android.chrome", null, null, "bank.example", webScheme = "http")
        assertFalse(AutofillPick.needsHttpConfirmation(httpPick, http))
        assertTrue(AutofillPick.needsHttpConfirmation(httpPick, entry("https://bank.example")))
    }

    // --- Related sites ---

    @Test
    fun relatedSites_sharedGroupsBothWays_fromToOneWay() {
        val list = asset(RelatedSites.ASSET_NAME).inputStream().use { RelatedSites.parse(it) }
        assertTrue("ebay.co.uk" in list.sourcesFor("www.ebay.com", "ebay.com"))
        assertTrue("ebay.com" in list.sourcesFor("www.ebay.co.uk", "ebay.co.uk"))
        // {"from": ["adobelogin.com", "behance.net"], "to": ["adobe.com"]}: logins of behance.net may be used on adobe.com only.
        assertTrue("behance.net" in list.sourcesFor("www.adobe.com", "adobe.com"))
        assertFalse("adobe.com" in list.sourcesFor("www.behance.net", "behance.net"))
        assertTrue(list.sourcesFor("unrelated.example", "unrelated.example").isEmpty())
        assertFalse("A site is never its own related site", "ebay.com" in list.sourcesFor("ebay.com", "ebay.com"))

        val ebay = entry("https://www.ebay.co.uk")
        val matches = AutofillCredentialMatcher.matchEntries(listOf(ebay), "com.android.chrome", "www.ebay.com", psl, verifiedBrowser = true, relatedSites = list)
        assertEquals(1, matches.size)
        assertEquals(AutofillCredentialMatcher.SCORE_RELATED_SITE, matches.single().score)
        assertEquals("ebay.co.uk", matches.single().relatedSite)
        assertTrue("Without the list nothing matches",
            AutofillCredentialMatcher.matchEntries(listOf(ebay), "com.android.chrome", "www.ebay.com", psl, verifiedBrowser = true).isEmpty())
        assertTrue("Apps never get related sites",
            AutofillCredentialMatcher.matchEntries(listOf(ebay), "com.ebay.app", "www.ebay.com", psl, verifiedBrowser = false, relatedSites = list).isEmpty())
        assertEquals("ebay.com>ebay.co.uk", RelatedSites.allowedPairKey("ebay.com", "ebay.co.uk"))
    }

    // --- App signatures and app websites ---

    @Test
    fun appSignature_trustOnFirstUse_rotation_andChange() {
        val a = PackageSigners(setOf("AA"), setOf("AA"))
        assertEquals(AppSignatureCheck.Result.OK to "AA", AppSignatureCheck.check(null, a))
        assertEquals(AppSignatureCheck.Result.OK to null, AppSignatureCheck.check("AA", a))
        // Rotated from AA to BB (BB proves AA): accepted, the pin moves to BB.
        assertEquals(AppSignatureCheck.Result.OK to "BB", AppSignatureCheck.check("AA", PackageSigners(setOf("BB"), setOf("AA", "BB"))))
        // Another app under the same name.
        assertEquals(AppSignatureCheck.Result.CHANGED to null, AppSignatureCheck.check("AA", PackageSigners(setOf("CC"), setOf("CC"))))
        // Fail closed: certificates that can't be read only pass while nothing is pinned.
        assertEquals(AppSignatureCheck.Result.CHANGED to null, AppSignatureCheck.check("AA", null))
        assertEquals(AppSignatureCheck.Result.UNKNOWN to null, AppSignatureCheck.check(null, null))
        assertEquals("AA,BB", AppSignatureCheck.pinOf(PackageSigners(setOf("BB", "AA"), emptySet())))
    }

    @Test
    fun appWebsites_readTheAppsAssetStatements() {
        val statements = """[
            {"relation": ["delegate_permission/common.get_login_creds"], "target": {"namespace": "web", "site": "https://www.amazon.in"}},
            {"include": "https://example.com/.well-known/assetlinks.json"},
            {"relation": ["delegate_permission/common.handle_all_urls"], "target": {"namespace": "android_app", "package_name": "x"}}
        ]"""
        assertEquals(setOf("www.amazon.in", "example.com"), AppWebsites.parseStatements(statements))
        assertTrue(AppWebsites.parseStatements("not json").isEmpty())
        assertTrue(AppWebsites.parseStatements("""{"site": "x"}""").isEmpty())
    }
}
