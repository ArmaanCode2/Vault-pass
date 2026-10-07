package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.models.AutofillAppLinkEntity
import com.example.domain.models.VaultEntry
import com.example.domain.sync.diff.SyncRecordMapper
import com.example.security.VaultSessionManager
import com.example.service.AutofillCredentialMatcher
import com.example.ui.VaultViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** F3 apps: entries linked to native apps through "Search VaultPass…" (local table autofill_app_links). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutofillAppLinkTest {

    private lateinit var app: VaultPassApplication
    private lateinit var viewModel: VaultViewModel

    private val repo get() = app.container.vaultRepository
    private val dao get() = app.container.appDatabase.vaultDao()
    private val psl = TestPublicSuffixList.list

    private val bankApp = "com.example.bankapp"
    private val chrome = "com.android.chrome"

    @Before
    fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext<Context>() as VaultPassApplication
        VaultSessionManager.resetForTesting()
        withContext(Dispatchers.IO) {
            app.container.appDatabase.clearAllTables()
        }
        viewModel = VaultViewModel(app.container.vaultRepository, app.container.settingsRepository)
        viewModel.lock()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        viewModel.setupMasterPasswordSync("MasterPassword123!")
        assertTrue("Vault unlocked for tests", viewModel.isUnlocked.first())
    }

    private suspend fun insert(title: String, website: String = "", username: String = "user"): VaultEntry {
        repo.insertEntry(VaultEntry(title = title, username = username, password = "secret-$title", website = website))
        return repo.decryptedEntries.value.first { it.title == title }
    }

    /** What the autofill service offers [packageName] (and [webDomain]), using the stored links like the service does. */
    private suspend fun offered(packageName: String, webDomain: String? = null): List<String> =
        AutofillCredentialMatcher.matchEntries(
            entries = repo.getAllEntriesSync(),
            requestedPackage = packageName,
            requestedWebDomain = webDomain,
            publicSuffixList = psl,
            verifiedBrowser = packageName == chrome,
            linkedSyncIds = repo.linkedAutofillSyncIds(packageName)
        ).map { it.entry.title }

    @Test
    fun linkedPackage_isOfferedTheEntry_unlinkedPackageGetsNothing() = runTest {
        val bank = insert("My Bank")
        insert("Other")
        assertTrue(offered(bankApp).isEmpty())

        assertTrue(repo.linkAutofillApp(bankApp, bank.syncId))

        assertEquals(setOf(bank.syncId), repo.linkedAutofillSyncIds(bankApp))
        val matches = AutofillCredentialMatcher.matchEntries(
            repo.getAllEntriesSync(), bankApp, null, psl, verifiedBrowser = false, linkedSyncIds = repo.linkedAutofillSyncIds(bankApp)
        )
        assertEquals(listOf("My Bank"), matches.map { it.entry.title })
        assertEquals(AutofillCredentialMatcher.SCORE_LINKED_APP, matches.single().score)
        assertTrue("Another app is not offered the linked entry", offered("com.example.otherapp").isEmpty())
    }

    @Test
    fun androidAppWebsite_stillMatches_alongsideLinks() = runTest {
        insert("Declared", website = "androidapp://$bankApp")
        val picked = insert("Picked")
        repo.linkAutofillApp(bankApp, picked.syncId)

        assertEquals(setOf("Declared", "Picked"), offered(bankApp).toSet())
        assertTrue(offered("com.example.otherapp").isEmpty())
        assertTrue("androidapp:// needs the exact package", offered("$bankApp.evil").isEmpty())
    }

    @Test
    fun browserPackages_areNeverLinked_andStoredBrowserLinksAreIgnored() = runTest {
        val entry = insert("Shop", website = "https://shop.example.com")

        assertFalse(repo.linkAutofillApp(chrome, entry.syncId))
        assertFalse(repo.linkAutofillApp("org.mozilla.firefox", entry.syncId))
        assertTrue(dao.getAllAppLinks().isEmpty())

        // A row that got in anyway (e.g. written by hand) is never honoured for a browser.
        dao.insertAppLink(AutofillAppLinkEntity(chrome, entry.syncId, 1L))
        assertTrue(repo.linkedAutofillSyncIds(chrome).isEmpty())
        assertTrue(offered(chrome, "evil.com").isEmpty())
        assertTrue("Browser without a webDomain gets nothing", offered(chrome).isEmpty())
        // Even if a caller passed the ids, a browser request only matches on the domain.
        assertTrue(
            AutofillCredentialMatcher.matchEntries(repo.getAllEntriesSync(), chrome, "evil.com", psl, verifiedBrowser = true, linkedSyncIds = setOf(entry.syncId)).isEmpty()
        )
        assertEquals(listOf("Shop"), offered(chrome, "shop.example.com"))
    }

    @Test
    fun renameSyncId_movesTheLinks() = runTest {
        val entry = insert("Mail")
        repo.linkAutofillApp(bankApp, entry.syncId)
        repo.linkAutofillApp("com.example.mailapp", entry.syncId)
        // An orphan link already under the new id for the same package must not break the rename.
        dao.insertAppLink(AutofillAppLinkEntity(bankApp, "remote-mail-id", 1L))

        assertTrue(repo.runSyncMerge { repo.renameSyncId(entry.syncId, "remote-mail-id") })

        val links = dao.getAllAppLinks()
        assertEquals(setOf(bankApp to "remote-mail-id", "com.example.mailapp" to "remote-mail-id"), links.map { it.packageName to it.syncId }.toSet())
        assertEquals(listOf("Mail"), offered(bankApp))
        assertEquals(listOf("Mail"), offered("com.example.mailapp"))
    }

    @Test
    fun renameSyncId_ofAnUnknownEntry_leavesLinksAlone() = runTest {
        val entry = insert("Mail")
        repo.linkAutofillApp(bankApp, entry.syncId)
        assertFalse(repo.runSyncMerge { repo.renameSyncId("no-such-id", entry.syncId) })
        assertEquals(listOf(entry.syncId), dao.getLinkedSyncIds(bankApp))
    }

    @Test
    fun permanentDelete_removesTheEntrysLinks() = runTest {
        val gone = insert("Gone")
        val kept = insert("Kept")
        repo.linkAutofillApp(bankApp, gone.syncId)
        repo.linkAutofillApp(bankApp, kept.syncId)

        repo.deleteEntry(gone.id)
        assertEquals("Moving to the recycle bin keeps the link", 2, dao.getAllAppLinks().size)
        assertEquals("...but a binned entry is not offered", listOf("Kept"), offered(bankApp))

        repo.permanentlyDeleteEntry(gone.id)
        assertEquals(listOf(kept.syncId), dao.getLinkedSyncIds(bankApp))
    }

    @Test
    fun recycleBinCleanup_removesLinksOfPurgedEntriesOnly() = runTest {
        val old = insert("Old")
        val recent = insert("Recent")
        repo.linkAutofillApp(bankApp, old.syncId)
        repo.linkAutofillApp(bankApp, recent.syncId)
        dao.softDeleteEntry(old.id, System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000)
        repo.deleteEntry(recent.id)

        repo.cleanupRecycleBin()

        assertNull(dao.getEntryBySyncId(old.syncId))
        assertEquals(listOf(recent.syncId), dao.getLinkedSyncIds(bankApp))
    }

    @Test
    fun orphanLinks_areIgnored_andCannotBeCreated() = runTest {
        insert("Real")
        dao.insertAppLink(AutofillAppLinkEntity(bankApp, "ghost-sync-id", 1L))

        assertEquals(setOf("ghost-sync-id"), repo.linkedAutofillSyncIds(bankApp))
        assertTrue(offered(bankApp).isEmpty())
        assertFalse("No entry holds that syncId", repo.linkAutofillApp(bankApp, "another-ghost"))
        assertFalse(repo.linkAutofillApp(bankApp, ""))
    }

    @Test
    fun links_areNotInSyncRecordsOrExports() = runTest {
        val packageName = "com.example.linkedonlylocally"
        val entry = insert("Linked")
        assertTrue(repo.linkAutofillApp(packageName, entry.syncId))

        val records = SyncRecordMapper.localState(repo.getSyncSnapshot()).records
        assertEquals(1, records.size)
        assertFalse(records.toString().contains(packageName))
        assertFalse(String(viewModel.generateSimplifiedJsonExportPayload(), Charsets.UTF_8).contains(packageName))
        assertFalse(String(viewModel.generateTxtExportPayload(), Charsets.UTF_8).contains(packageName))
        assertFalse(viewModel.generateSimplifiedJsonExportPayload().isEmpty())
    }

    @Test
    fun appLabelE_hasNoRealMatches() = runTest {
        insert("e")
        insert("Netflix")
        insert("Google", website = "https://google.com")

        assertTrue(offered("com.e").isEmpty())
        assertTrue(offered("e").isEmpty())
    }
}
