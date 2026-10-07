package com.example.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.VaultPassApplication
import com.example.data.models.VaultEntryEntity
import com.example.domain.models.VaultEntry
import com.example.domain.sync.diff.SyncDiffEngine
import com.example.domain.sync.diff.SyncRecordMapper
import com.example.security.VaultSessionManager
import com.example.ui.VaultViewModel
import com.vaultpass.synccore.SyncRecord
import com.vaultpass.synccore.SyncRecords
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

/** Sync data model v2 storage: syncIds on entries, tombstones, and what this device sends. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncIdAndTombstoneTest {

    private lateinit var app: VaultPassApplication
    private lateinit var viewModel: VaultViewModel

    private val repo get() = app.container.vaultRepository
    private val dao get() = app.container.appDatabase.vaultDao()

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

    private suspend fun insert(title: String, syncId: String = ""): VaultEntry {
        repo.insertEntry(VaultEntry(title = title, syncId = syncId))
        return repo.decryptedEntries.value.first { it.title == title }
    }

    @Test
    fun newAndImportedEntries_getDistinctValidSyncIds() = runTest {
        val single = insert("Single")
        viewModel.addEntries((1..5).map { VaultEntry(title = "Imported $it") })

        val ids = repo.decryptedEntries.value.map { it.syncId }
        assertEquals(6, ids.size)
        assertEquals(6, ids.toSet().size)
        assertTrue(ids.all { SyncRecords.isValidSyncId(it) && it.length == 36 })
        assertTrue(ids.contains(single.syncId))
    }

    @Test
    fun editing_keepsTheSyncId() = runTest {
        val original = insert("Mail")

        // The edit screen passes the loaded syncId; an entry built without one keeps it too.
        assertTrue(viewModel.updateEntrySync(original.copy(title = "Mail 2")))
        assertTrue(viewModel.updateEntrySync(VaultEntry(id = original.id, title = "Mail 3")))
        assertTrue(viewModel.updateEntrySync(VaultEntry(id = original.id, title = "Mail 4", syncId = "some-other-id")))

        val edited = repo.getEntryById(original.id)!!
        assertEquals("Mail 4", edited.title)
        assertEquals(original.syncId, edited.syncId)
    }

    @Test
    fun insert_neverStealsAnotherEntrysSyncId() = runTest {
        val first = insert("First", syncId = "shared-id")
        val second = insert("Second", syncId = "shared-id")

        assertEquals("shared-id", first.syncId)
        assertNotEquals("shared-id", second.syncId)
        assertEquals(2, repo.decryptedEntries.value.size)
    }

    @Test
    fun deleteForever_writesATombstone_thatIsSentOnSync() = runTest {
        val entry = insert("Gone")
        repo.deleteEntry(entry.id)
        val deletedAt = dao.getEntryBySyncId(entry.syncId)!!.deletedAt!!
        repo.permanentlyDeleteEntry(entry.id)

        assertNull(dao.getEntryBySyncId(entry.syncId))
        val tombstone = dao.getAllTombstones().single()
        assertEquals(entry.syncId, tombstone.syncId)
        assertEquals("Keeps the time it was deleted", deletedAt, tombstone.deletedAt)

        val sent = SyncRecordMapper.localState(repo.getSyncSnapshot()).records
        assertEquals(listOf(SyncRecords.tombstone(entry.syncId, deletedAt)), sent)
    }

    @Test
    fun recycleBinPurge_writesTombstonesForPurgedEntriesOnly() = runTest {
        val old = insert("Old")
        val recent = insert("Recent")
        val eightDaysAgo = System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000
        dao.softDeleteEntry(old.id, eightDaysAgo)
        repo.deleteEntry(recent.id)

        repo.cleanupRecycleBin()

        assertNull(dao.getEntryBySyncId(old.syncId))
        assertNotNull(dao.getEntryBySyncId(recent.syncId))
        val tombstone = dao.getAllTombstones().single()
        assertEquals(old.syncId, tombstone.syncId)
        assertEquals(eightDaysAgo, tombstone.deletedAt)

        // Both travel as tombstones: one from the recycle bin, one from the tombstone table.
        val sent = SyncRecordMapper.localState(repo.getSyncSnapshot()).records
        assertEquals(setOf(old.syncId, recent.syncId), sent.map { it.syncId }.toSet())
        assertTrue(sent.all { it.deleted && it.title.isEmpty() && it.password.isEmpty() })
    }

    @Test
    fun insertingASyncIdAgain_removesItsTombstone() = runTest {
        val entry = insert("Phoenix")
        repo.permanentlyDeleteEntry(entry.id)
        assertEquals(1, dao.getAllTombstones().size)

        insert("Phoenix", syncId = entry.syncId)

        assertTrue(dao.getAllTombstones().isEmpty())
        assertEquals(entry.syncId, repo.decryptedEntries.value.single().syncId)
    }

    @Test
    fun entriesThatFailToDecrypt_areNeitherSentNorTouched() = runTest {
        insert("Fine", syncId = "fine-id")
        dao.insertEntry(
            VaultEntryEntity(
                titleEnc = "bm90IHJlYWxseSBlbmNyeXB0ZWQgZGF0YQ==",
                usernameEnc = "",
                passwordEnc = "",
                websiteEnc = "",
                notesEnc = "",
                categoryEnc = "",
                tagsEnc = "",
                customFieldsEnc = "",
                isFavorite = false,
                timestamp = 1L,
                syncId = "broken-id"
            )
        )

        val local = SyncRecordMapper.localState(repo.getSyncSnapshot())
        assertEquals(listOf("fine-id"), local.records.map { it.syncId })
        assertEquals(setOf("broken-id"), local.blockedIds)

        val remote = listOf(SyncRecord(syncId = "broken-id", title = "Overwrite me", updatedAt = Long.MAX_VALUE))
        val diff = SyncDiffEngine.computeDiff(local.records, remote, 0L, local.blockedIds, local.entriesBySyncId)
        assertTrue("A remote copy of a blocked entry is left out", diff.diffItems.none { it.syncKey == "broken-id" })

        // Even if asked directly, the merge refuses to overwrite it.
        assertFalse(repo.runSyncMerge { repo.upsertSyncedEntry(VaultEntry(syncId = "broken-id", title = "Overwrite me")) })
        assertEquals("bm90IHJlYWxseSBlbmNyeXB0ZWQgZGF0YQ==", dao.getEntryBySyncId("broken-id")!!.titleEnc)
    }
}
