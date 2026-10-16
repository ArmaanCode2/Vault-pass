package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.domain.models.VaultEntry
import com.example.domain.sync.diff.SyncRecordMapper
import com.example.security.VaultSessionManager
import com.example.ui.VaultViewModel
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

/** F28: an entry in the recycle bin can't be opened, edited back to life or starred. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeletedEntryTest {

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

    private suspend fun insert(title: String, timestamp: Long = System.currentTimeMillis()): VaultEntry {
        repo.insertEntry(VaultEntry(title = title, password = "secret", timestamp = timestamp))
        return repo.decryptedEntries.value.first { it.title == title }
    }

    @Test
    fun aBinnedEntry_isNotFoundById_butTheDaoStillReadsIt() = runTest {
        val entry = insert("Mail")
        assertNotNull(repo.getEntryById(entry.id))

        repo.deleteEntry(entry.id)

        assertNull(repo.getEntryById(entry.id))
        assertNull(viewModel.getEntryById(entry.id))
        val stored = dao.getEntryById(entry.id)
        assertNotNull("Tombstones and the legacy upgrade still read binned rows", stored)
        assertTrue(stored!!.isDeleted)

        repo.restoreEntry(entry.id)
        assertEquals("Mail", repo.getEntryById(entry.id)?.title)
    }

    @Test
    fun anEditorSave_neverTakesAnEntryOutOfTheRecycleBin() = runTest {
        val entry = insert("Bank")
        repo.deleteEntry(entry.id)
        val deletedAt = dao.getEntryById(entry.id)!!.deletedAt!!

        // What the editor saves: a new VaultEntry, so isDeleted is false and deletedAt null.
        assertTrue(viewModel.updateEntrySync(VaultEntry(id = entry.id, syncId = entry.syncId, title = "Bank 2", password = "secret")))
        repo.updateEntries(listOf(VaultEntry(id = entry.id, syncId = entry.syncId, title = "Bank 3", password = "secret")))

        val stored = dao.getEntryById(entry.id)!!
        assertTrue("Still in the recycle bin", stored.isDeleted)
        assertEquals(deletedAt, stored.deletedAt)
        assertEquals(entry.syncId, stored.syncId)
        assertNull(repo.getEntryById(entry.id))
        assertTrue(repo.decryptedEntries.value.none { it.id == entry.id })

        // Sync still sends it as deleted, with the time it was deleted.
        val sent = SyncRecordMapper.localState(repo.getSyncSnapshot()).records
        assertEquals(listOf(SyncRecords.tombstone(entry.syncId, deletedAt)), sent)

        // Restoring it brings back the edit.
        repo.restoreEntry(entry.id)
        assertEquals("Bank 3", repo.getEntryById(entry.id)?.title)
    }

    @Test
    fun anEditorSave_keepsALiveEntryLive() = runTest {
        val entry = insert("Shop")
        assertTrue(viewModel.updateEntrySync(VaultEntry(id = entry.id, title = "Shop 2", password = "secret")))

        val stored = dao.getEntryById(entry.id)!!
        assertFalse(stored.isDeleted)
        assertNull(stored.deletedAt)
        assertEquals("Shop 2", repo.getEntryById(entry.id)?.title)
    }

    @Test
    fun favorite_persists_andBumpsTheTimestampSoItSyncs() = runTest {
        val entry = insert("Star me", timestamp = 1_000L)

        assertTrue(viewModel.setFavorite(entry.id, true))

        val starred = repo.getEntryById(entry.id)!!
        assertTrue(starred.isFavorite)
        assertTrue("Timestamp bumped", starred.timestamp > 1_000L)
        assertEquals("Content untouched", entry.copy(isFavorite = true, timestamp = starred.timestamp), starred)
        assertTrue(repo.decryptedEntries.value.single { it.id == entry.id }.isFavorite)
        val sent = SyncRecordMapper.localState(repo.getSyncSnapshot()).records.single()
        assertTrue(sent.favorite)
        assertEquals(starred.timestamp, sent.updatedAt)

        assertTrue(viewModel.setFavorite(entry.id, false))
        assertFalse(repo.getEntryById(entry.id)!!.isFavorite)
    }

    @Test
    fun favorite_isRefusedForBinnedOrMissingEntries_andWhileLocked() = runTest {
        val entry = insert("Binned", timestamp = 1_000L)
        repo.deleteEntry(entry.id)
        val before = dao.getEntryById(entry.id)

        assertFalse(viewModel.setFavorite(entry.id, true))
        assertFalse(viewModel.setFavorite(entry.id + 1000, true))
        assertEquals(before, dao.getEntryById(entry.id))

        val live = insert("Live", timestamp = 1_000L)
        val liveBefore = dao.getEntryById(live.id)
        viewModel.lock()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertFalse(viewModel.setFavorite(live.id, true))
        assertEquals(liveBefore, dao.getEntryById(live.id))
    }
}
