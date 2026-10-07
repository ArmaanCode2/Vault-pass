package com.example.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.VaultPassApplication
import com.example.domain.models.CustomField
import com.example.domain.models.VaultEntry
import com.example.domain.sync.diff.EntryDiffItem
import com.example.domain.sync.diff.EntrySyncCategory
import com.example.domain.sync.diff.FieldChangeType
import com.example.domain.sync.diff.SyncDiffEngine
import com.example.domain.sync.diff.SyncMergeExecutor
import com.example.domain.sync.diff.SyncRecordMapper
import com.example.security.VaultSessionManager
import com.example.ui.VaultViewModel
import com.vaultpass.synccore.CustomFieldRecord
import com.vaultpass.synccore.EntryLimits
import com.vaultpass.synccore.ItemOutcome
import com.vaultpass.synccore.MergePlans
import com.vaultpass.synccore.PlanAction
import com.vaultpass.synccore.PlanInput
import com.vaultpass.synccore.PlanItem
import com.vaultpass.synccore.SyncChange
import com.vaultpass.synccore.SyncOutcomes
import com.vaultpass.synccore.SyncPlanner
import com.vaultpass.synccore.SyncProtocolException
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncDiffAndMergeTest {

    private lateinit var context: Context
    private lateinit var app: VaultPassApplication
    private lateinit var viewModel: VaultViewModel

    private val repo get() = app.container.vaultRepository
    private val dao get() = app.container.appDatabase.vaultDao()

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        app = context as VaultPassApplication
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

    private fun record(
        syncId: String,
        title: String,
        username: String = "alice",
        password: String = "pw",
        updatedAt: Long = 1_000L
    ) = SyncRecord(syncId = syncId, title = title, username = username, password = password, category = "Work", updatedAt = updatedAt)

    private fun categoryOf(change: SyncChange) = when (change) {
        SyncChange.NEW_REMOTE -> EntrySyncCategory.NEW_REMOTE
        SyncChange.NEW_LOCAL -> EntrySyncCategory.NEW_LOCAL
        SyncChange.UNCHANGED -> EntrySyncCategory.UNCHANGED
        SyncChange.REMOTE_CHANGED, SyncChange.LOCAL_CHANGED -> EntrySyncCategory.MODIFIED
        SyncChange.CONFLICT -> EntrySyncCategory.CONFLICT
        SyncChange.DELETED_REMOTE -> EntrySyncCategory.DELETED_REMOTE
        SyncChange.DELETED_LOCAL -> EntrySyncCategory.DELETED_LOCAL
    }

    private fun item(
        syncKey: String,
        change: SyncChange,
        outcome: ItemOutcome = SyncOutcomes.defaultFor(change),
        local: VaultEntry? = null,
        remote: VaultEntry? = null,
        edited: VaultEntry? = null,
        replace: String? = null
    ) = EntryDiffItem(
        syncKey = syncKey,
        change = change,
        category = categoryOf(change),
        localEntry = local,
        remoteEntry = remote,
        localRecord = local?.let { SyncRecordMapper.toRecord(it.copy(syncId = syncKey)) },
        remoteRecord = remote?.let { SyncRecordMapper.toRecord(it.copy(syncId = syncKey)) },
        fieldDiffs = emptyList(),
        choices = SyncOutcomes.choicesFor(change),
        outcome = outcome,
        needsChoice = SyncOutcomes.needsChoice(change),
        answered = true,
        editedEntry = edited,
        localSyncIdToReplace = replace
    )

    /** The reviewer's path: build the plan from the review, then apply it here. */
    private suspend fun merge(items: List<EntryDiffItem>, now: Long = System.currentTimeMillis()): Result<Int> =
        SyncMergeExecutor.applyPlan(MergePlans.build(items.map { it.toPlanInput() }, now), repo, now)

    // --- Mapping ---

    @Test
    fun customFieldsAndEveryField_roundTripThroughSyncRecords() {
        val entry = VaultEntry(
            syncId = "id-github",
            title = "GitHub",
            username = "octocat",
            password = "SecretPassword123!",
            website = "https://github.com",
            notes = "Work dev account",
            category = "Work",
            tags = listOf("code", "git"),
            customFields = listOf(CustomField("2FA", "OTP"), CustomField("PIN", "1234"), CustomField("2FA", "backup")),
            isFavorite = true,
            timestamp = 1700000000000L
        )
        val record = SyncRecordMapper.toRecord(entry)
        assertEquals(listOf(CustomFieldRecord("2FA", "OTP"), CustomFieldRecord("PIN", "1234"), CustomFieldRecord("2FA", "backup")), record.customFields)
        assertEquals("https://github.com", record.url)
        assertEquals(1700000000000L, record.updatedAt)
        assertTrue(record.favorite)

        val overTheWire = SyncRecords.encodeChunks(listOf(record)).flatMap { SyncRecords.decodeChunk(it) }.single()
        assertEquals(entry, SyncRecordMapper.toVaultEntry(overTheWire))
    }

    // --- Diff engine ---

    @Test
    fun diff_classifiesNewModifiedUnchangedAndLocalOnly() {
        val local = listOf(
            record("id-github", "GitHub"),
            record("id-gitlab", "GitLab", password = "oldPassword"),
            record("id-local", "LocalOnlyApp")
        )
        val remote = listOf(
            record("id-github", "GitHub", updatedAt = 5_000L),
            record("id-gitlab", "GitLab", password = "newUpdatedPassword", updatedAt = 2_000L),
            record("id-remote", "DesktopOnlyApp")
        )

        val diff = SyncDiffEngine.computeDiff(local, remote, lastSyncAt = 0L)

        assertEquals(1, diff.newRemoteCount)
        assertEquals(1, diff.newLocalCount)
        assertEquals(1, diff.modifiedCount)
        assertEquals(1, diff.unchangedCount)
        assertEquals(4, diff.diffItems.size)
        assertFalse(diff.hasUnansweredConflicts)

        val newRemote = diff.diffItems.first { it.syncKey == "id-remote" }
        assertEquals(EntrySyncCategory.NEW_REMOTE, newRemote.category)
        assertEquals(ItemOutcome.USE_REMOTE, newRemote.outcome)
        assertNull(newRemote.localEntry)
        assertEquals("id-remote", newRemote.remoteEntry?.syncId)

        val newLocal = diff.diffItems.first { it.syncKey == "id-local" }
        assertEquals(EntrySyncCategory.NEW_LOCAL, newLocal.category)
        assertEquals("Ours by default, and it goes to the other device", ItemOutcome.USE_LOCAL, newLocal.outcome)

        val modified = diff.diffItems.first { it.syncKey == "id-gitlab" }
        assertEquals(EntrySyncCategory.MODIFIED, modified.category)
        assertEquals(SyncChange.REMOTE_CHANGED, modified.change)
        assertEquals("The newer remote version wins by default", ItemOutcome.USE_REMOTE, modified.outcome)
        assertEquals(listOf(ItemOutcome.USE_REMOTE, ItemOutcome.USE_LOCAL, ItemOutcome.SKIP), modified.choices)
        val passwordDiff = modified.fieldDiffs.first { it.fieldName == "Password" }
        assertEquals(FieldChangeType.MODIFIED, passwordDiff.changeType)
        assertEquals("oldPassword", passwordDiff.localValue)
        assertEquals("newUpdatedPassword", passwordDiff.remoteValue)

        val unchanged = diff.diffItems.first { it.syncKey == "id-github" }
        assertEquals(EntrySyncCategory.UNCHANGED, unchanged.category)
        assertEquals(listOf(ItemOutcome.SKIP), unchanged.choices)
        assertEquals(ItemOutcome.SKIP, unchanged.outcome)
        assertEquals("Only the two real changes are offered", 3, diff.plannedChanges)
    }

    @Test
    fun diff_conflictsAndDeletions() {
        val lastSync = 10_000L
        val binned = VaultEntry(syncId = "id-binned", title = "Binned", username = "alice", isDeleted = true, deletedAt = 9_000L)
        val local = listOf(
            record("id-both", "Both", password = "mine", updatedAt = 11_000L),
            record("id-gone-there", "GoneThere"),
            SyncRecords.tombstone("id-binned", 9_000L),
            SyncRecords.tombstone("id-purged", 8_000L)
        )
        val remote = listOf(
            record("id-both", "Both", password = "theirs", updatedAt = 12_000L),
            SyncRecords.tombstone("id-gone-there", 11_000L),
            record("id-binned", "Binned", password = "still here", updatedAt = 5_000L),
            record("id-purged", "Purged", updatedAt = 5_000L)
        )

        val diff = SyncDiffEngine.computeDiff(local, remote, lastSync, localEntries = mapOf("id-binned" to binned))

        assertEquals(1, diff.conflictCount)
        assertEquals(1, diff.deletedRemoteCount)
        assertEquals(2, diff.deletedLocalCount)

        val conflict = diff.diffItems.first { it.syncKey == "id-both" }
        assertEquals(EntrySyncCategory.CONFLICT, conflict.category)
        assertTrue("A conflict must be answered by the user", conflict.needsChoice)
        assertFalse(conflict.answered)
        assertTrue(diff.hasUnansweredConflicts)

        val deletedThere = diff.diffItems.first { it.syncKey == "id-gone-there" }
        assertEquals(EntrySyncCategory.DELETED_REMOTE, deletedThere.category)
        assertEquals(ItemOutcome.DELETE_BOTH, deletedThere.outcome)
        assertNull(deletedThere.remoteEntry)
        assertEquals("GoneThere", deletedThere.localEntry?.title)

        val inBin = diff.diffItems.first { it.syncKey == "id-binned" }
        assertEquals(EntrySyncCategory.DELETED_LOCAL, inBin.category)
        assertEquals("Never brought back by default", ItemOutcome.DELETE_BOTH, inBin.outcome)
        assertEquals(binned, inBin.localEntry)

        val purged = diff.diffItems.first { it.syncKey == "id-purged" }
        assertEquals(EntrySyncCategory.DELETED_LOCAL, purged.category)
        assertNull(purged.localEntry)
        assertEquals("Purged", purged.remoteEntry?.title)
    }

    @Test
    fun diff_remoteDeletionWeEditedLater_keepsOurVersionByDefault() {
        val local = record("id-kept", "Kept", password = "edited later", updatedAt = 9_000L)
        val remote = SyncRecords.tombstone("id-kept", 5_000L)

        val item = SyncDiffEngine.computeDiff(listOf(local), listOf(remote), lastSyncAt = 0L).diffItems.single()
        assertEquals(EntrySyncCategory.DELETED_REMOTE, item.category)
        assertEquals(ItemOutcome.USE_LOCAL, item.outcome)
    }

    @Test
    fun diff_detectsAFavoriteOnlyChange() {
        val local = record("id-star", "StarMe").copy(favorite = false)
        val remote = local.copy(favorite = true, updatedAt = 2_000L)

        val diff = SyncDiffEngine.computeDiff(listOf(local), listOf(remote), lastSyncAt = 0L)

        val item = diff.diffItems.single()
        assertEquals(EntrySyncCategory.MODIFIED, item.category)
        assertEquals(ItemOutcome.USE_REMOTE, item.outcome)
        val favDiff = item.fieldDiffs.first { it.fieldName == "Favorite" }
        assertEquals(FieldChangeType.MODIFIED, favDiff.changeType)
        assertEquals("false", favDiff.localValue)
        assertEquals("true", favDiff.remoteValue)
    }

    @Test
    fun diff_detectsACustomFieldOnlyChange_butNotAReorder() {
        val local = record("id-cf", "Bank").copy(customFields = listOf(CustomFieldRecord("PIN", "1"), CustomFieldRecord("Q", "blue")))
        val reordered = local.copy(customFields = local.customFields.reversed(), updatedAt = 2_000L)
        assertEquals(EntrySyncCategory.UNCHANGED, SyncDiffEngine.computeDiff(listOf(local), listOf(reordered), 0L).diffItems.single().category)

        val changed = local.copy(customFields = listOf(CustomFieldRecord("PIN", "2"), CustomFieldRecord("Q", "blue")), updatedAt = 2_000L)
        val item = SyncDiffEngine.computeDiff(listOf(local), listOf(changed), 0L).diffItems.single()
        assertEquals(EntrySyncCategory.MODIFIED, item.category)
        val fieldDiff = item.fieldDiffs.first { it.fieldName == "Custom Fields" }
        assertEquals(FieldChangeType.MODIFIED, fieldDiff.changeType)
        assertEquals("PIN:1; Q:blue", fieldDiff.localValue)
        assertEquals("PIN:2; Q:blue", fieldDiff.remoteValue)
    }

    @Test
    fun diff_legacyMatch_keepsTheLocalIdToReplace() {
        val localEntry = VaultEntry(syncId = "zzz-local", title = "Bank", username = "alice", password = "pw")
        val diff = SyncDiffEngine.computeDiff(
            localRecords = listOf(SyncRecordMapper.toRecord(localEntry)),
            remoteRecords = listOf(record("aaa-remote", "bank", username = "ALICE", password = "pw2", updatedAt = localEntry.timestamp + 1)),
            lastSyncAt = 0L,
            localEntries = mapOf("zzz-local" to localEntry)
        )
        val item = diff.diffItems.single()
        assertEquals("aaa-remote", item.syncKey)
        assertEquals("zzz-local", item.localSyncIdToReplace)
        assertEquals(localEntry, item.localEntry)
        assertEquals("aaa-remote", item.remoteEntry?.syncId)
    }

    // --- Building the plan ---

    @Test
    fun plan_skippingALegacyMatchStillRenamesIt() {
        val items = MergePlans.build(
            listOf(item("aaa-remote", SyncChange.REMOTE_CHANGED, ItemOutcome.SKIP, replace = "zzz-local").toPlanInput()),
            now = 5_000L
        )
        val only = items.single()
        assertEquals(PlanAction.RENAME_ONLY, only.action)
        assertEquals("zzz-local", only.renameFrom)
        assertEquals("aaa-remote", only.syncId)
    }

    @Test
    fun plan_aBrokenPlanIsRefusedAndNothingIsApplied() = runTest {
        repo.insertEntry(VaultEntry(syncId = "id-safe", title = "Safe", password = "keep"))
        val broken = listOf(
            PlanItem(syncId = "id-safe", action = PlanAction.UPSERT, record = record("id-other", "Wrong")),
            PlanItem(syncId = "id-new", action = PlanAction.UPSERT, record = record("id-new", "New"))
        )

        val result = SyncMergeExecutor.applyPlan(broken, repo)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SyncProtocolException)
        assertEquals(listOf("id-safe"), repo.decryptedEntries.value.map { it.syncId })
        assertEquals("keep", repo.decryptedEntries.value.single().password)
    }

    @Test
    fun plan_repeatedEntriesAreRefused() {
        val twice = listOf(
            PlanItem("id-dup", PlanAction.UPSERT, record("id-dup", "One")),
            PlanItem("id-dup", PlanAction.UPSERT, record("id-dup", "Two"))
        )
        try {
            MergePlans.validate(twice)
            fail("A plan that repeats an entry must be refused")
        } catch (expected: SyncProtocolException) {
        }
    }

    // --- Applying the plan ---

    @Test
    fun merge_insertsNewRemoteUnderItsSyncId_andUpdatesModifiedInPlace() = runTest {
        repo.insertEntry(VaultEntry(syncId = "id-netflix", title = "Netflix", username = "user@home.org", password = "Old!"))
        val existing = repo.decryptedEntries.value.single()

        val remoteNetflix = existing.copy(id = 777, password = "New!", notes = "Updated on Desktop", timestamp = 5_000L)
        val remoteSpotify = VaultEntry(id = 888, syncId = "id-spotify", title = "Spotify", username = "music", password = "S!", timestamp = 6_000L)

        val result = merge(
            listOf(
                item("id-netflix", SyncChange.REMOTE_CHANGED, local = existing, remote = remoteNetflix),
                item("id-spotify", SyncChange.NEW_REMOTE, remote = remoteSpotify)
            )
        )
        assertEquals(2, result.getOrThrow())

        val entries = repo.decryptedEntries.value
        assertEquals(2, entries.size)
        val netflix = entries.first { it.syncId == "id-netflix" }
        assertEquals(existing.id, netflix.id)
        assertEquals("New!", netflix.password)
        assertEquals("Updated on Desktop", netflix.notes)
        assertEquals("Takes the other device's time", 5_000L, netflix.timestamp)

        val spotify = entries.first { it.syncId == "id-spotify" }
        assertNotEquals(888, spotify.id)
        assertEquals(6_000L, spotify.timestamp)
    }

    @Test
    fun merge_keepingThisDevicesVersionChangesNothingHere() = runTest {
        repo.insertEntry(VaultEntry(syncId = "id-mine", title = "Mine", password = "local", timestamp = 9_000L))
        val local = repo.decryptedEntries.value.single()
        val remote = local.copy(password = "theirs", timestamp = 4_000L)

        assertEquals(1, merge(listOf(item("id-mine", SyncChange.LOCAL_CHANGED, local = local, remote = remote))).getOrThrow())

        val after = repo.decryptedEntries.value.single()
        assertEquals("local", after.password)
        assertEquals(local.id, after.id)
        assertEquals(9_000L, after.timestamp)
    }

    @Test
    fun merge_inlineEditIsRespected_andKeepsSyncIdAndCustomFields() = runTest {
        val remote = VaultEntry(
            syncId = "id-dropbox",
            title = "Dropbox",
            username = "box@test.com",
            password = "RemotePassword!",
            customFields = listOf(CustomField("Recovery", "abc")),
            timestamp = 1_000L
        )
        val edited = remote.copy(title = "Dropbox Pro", password = "Custom#999")

        val before = System.currentTimeMillis()
        val result = merge(listOf(item("id-dropbox", SyncChange.NEW_REMOTE, remote = remote, edited = edited)))
        assertEquals(1, result.getOrThrow())

        val merged = repo.decryptedEntries.value.single()
        assertEquals("id-dropbox", merged.syncId)
        assertEquals("Dropbox Pro", merged.title)
        assertEquals("Custom#999", merged.password)
        assertEquals(listOf(CustomField("Recovery", "abc")), merged.customFields)
        assertTrue("An inline edit is a change made now", merged.timestamp >= before)
    }

    @Test
    fun merge_leaveUnchangedChangesNothing_exceptLegacyRenames() = runTest {
        repo.insertEntry(VaultEntry(syncId = "zzz-local", title = "Bank", username = "alice", password = "mine"))
        val local = repo.decryptedEntries.value.single()

        val result = merge(
            listOf(
                item(
                    "aaa-remote", SyncChange.REMOTE_CHANGED, ItemOutcome.SKIP,
                    local = local, remote = local.copy(syncId = "aaa-remote", password = "theirs"), replace = "zzz-local"
                ),
                item("id-ignored", SyncChange.NEW_REMOTE, ItemOutcome.SKIP, remote = VaultEntry(syncId = "id-ignored", title = "Ignored"))
            )
        )
        assertEquals(0, result.getOrThrow())

        val entries = repo.decryptedEntries.value
        assertEquals(1, entries.size)
        assertEquals("The legacy entry adopts the agreed id", "aaa-remote", entries[0].syncId)
        assertEquals(local.id, entries[0].id)
        assertEquals("mine", entries[0].password)
        assertNull(dao.getEntryBySyncId("zzz-local"))
    }

    @Test
    fun legacyMatches_endIdenticalOnTheApprover_whicheverIdSortsFirst() = runTest {
        // This device is the approver. Its entries predate syncIds, so the reviewer holds the same
        // entries under different ids, which sort before ours for Bank and Mail and after for Shop.
        repo.insertEntries(
            listOf(
                VaultEntry(syncId = "zzz-bank", title = "Bank", username = "alice", password = "same", category = "Work"),
                VaultEntry(syncId = "zzz-mail", title = "Mail", username = "alice", password = "old", category = "Work"),
                VaultEntry(syncId = "aaa-shop", title = "Shop", username = "alice", password = "same", category = "Work")
            )
        )
        val approverBefore = repo.decryptedEntries.value.map { SyncRecordMapper.toRecord(it) }
        val reviewerVault = listOf(
            record("aaa-bank", "Bank", password = "same", updatedAt = approverBefore.first { it.title == "Bank" }.updatedAt),
            record("aaa-mail", "Mail", password = "new", updatedAt = Long.MAX_VALUE / 2),
            record("zzz-shop", "Shop", password = "same", updatedAt = approverBefore.first { it.title == "Shop" }.updatedAt)
        )

        // The reviewer's side: plan against the approver's records and accept every default.
        val decisions = SyncPlanner.plan(reviewerVault, approverBefore, lastSyncAt = 0L)
        val plan = MergePlans.build(
            decisions.map {
                PlanInput(
                    syncId = it.syncId,
                    change = it.change,
                    outcome = SyncOutcomes.defaultFor(it.change),
                    local = it.local,
                    remote = it.remote,
                    localIdToReplace = it.localIdToReplace
                )
            },
            now = 5_000L
        )
        val reviewerAfter = SyncMergeExecutor.projectRecords(reviewerVault, plan)

        SyncMergeExecutor.applyPlan(plan, repo, now = 5_000L).getOrThrow()
        val approverAfter = repo.decryptedEntries.value.map { SyncRecordMapper.toRecord(it) }

        assertEquals(listOf("Bank", "Mail", "Shop"), approverAfter.map { it.title }.sorted())
        assertEquals(reviewerAfter.map { it.syncId }.sorted(), approverAfter.map { it.syncId }.sorted())
        assertEquals("new", approverAfter.single { it.title == "Mail" }.password)
        assertEquals(MergePlans.vaultFingerprint(reviewerAfter), MergePlans.vaultFingerprint(approverAfter))
    }

    @Test
    fun merge_renamesLegacyIds_andAppliesTheRemoteVersionToTheRenamedRow() = runTest {
        repo.insertEntry(VaultEntry(syncId = "zzz-local", title = "Bank", username = "alice", password = "mine"))
        val local = repo.decryptedEntries.value.single()

        val result = merge(
            listOf(
                item(
                    "aaa-remote", SyncChange.REMOTE_CHANGED,
                    local = local, remote = local.copy(syncId = "aaa-remote", password = "theirs"), replace = "zzz-local"
                )
            )
        )
        assertEquals(1, result.getOrThrow())

        val merged = repo.decryptedEntries.value.single()
        assertEquals(local.id, merged.id)
        assertEquals("aaa-remote", merged.syncId)
        assertEquals("theirs", merged.password)
    }

    @Test
    fun merge_deleteOnBoth_movesTheEntryToTheRecycleBin() = runTest {
        repo.insertEntries(
            listOf(
                VaultEntry(syncId = "id-delete", title = "DeleteMe"),
                VaultEntry(syncId = "id-keep", title = "KeepMe")
            )
        )
        val entries = repo.decryptedEntries.value
        val before = System.currentTimeMillis()

        val result = merge(
            listOf(
                item("id-delete", SyncChange.DELETED_REMOTE, local = entries.first { it.syncId == "id-delete" }),
                item(
                    "id-keep", SyncChange.DELETED_REMOTE, ItemOutcome.SKIP,
                    local = entries.first { it.syncId == "id-keep" }
                )
            )
        )
        assertEquals(1, result.getOrThrow())

        assertEquals(listOf("id-keep"), repo.decryptedEntries.value.map { it.syncId })
        val binned = dao.getEntryBySyncId("id-delete")!!
        assertTrue(binned.isDeleted)
        assertTrue(binned.deletedAt!! >= before)
    }

    @Test
    fun merge_upsertRestoresAnEntryFromTheRecycleBin_withoutADuplicate() = runTest {
        repo.insertEntry(VaultEntry(syncId = "id-back", title = "Back", password = "old"))
        val id = repo.decryptedEntries.value.single().id
        repo.deleteEntry(id)
        assertTrue(repo.decryptedEntries.value.isEmpty())

        val remote = VaultEntry(syncId = "id-back", title = "Back", password = "remote", timestamp = 4_000L)
        val result = merge(listOf(item("id-back", SyncChange.DELETED_LOCAL, ItemOutcome.USE_REMOTE, remote = remote)))
        assertEquals(1, result.getOrThrow())

        val restored = repo.decryptedEntries.value.single()
        assertEquals(id, restored.id)
        assertEquals("remote", restored.password)
        assertFalse(restored.isDeleted)
        assertNull(restored.deletedAt)
        assertTrue(dao.getRecycleBinEntriesSync().isEmpty())
    }

    @Test
    fun merge_upsertFromATombstone_insertsOnce_andDropsTheTombstone() = runTest {
        repo.insertEntry(VaultEntry(syncId = "id-purged", title = "Purged"))
        repo.permanentlyDeleteEntry(repo.decryptedEntries.value.single().id)
        assertEquals(listOf("id-purged"), dao.getAllTombstones().map { it.syncId })

        val remote = VaultEntry(syncId = "id-purged", title = "Purged", password = "remote", timestamp = 4_000L)
        val result = merge(listOf(item("id-purged", SyncChange.DELETED_LOCAL, ItemOutcome.USE_REMOTE, remote = remote)))
        assertEquals(1, result.getOrThrow())

        assertEquals("id-purged", repo.decryptedEntries.value.single().syncId)
        assertTrue(dao.getAllTombstones().isEmpty())
    }

    @Test
    fun merge_batchOfChanges() = runTest {
        repo.insertEntries(
            listOf(
                VaultEntry(syncId = "id-1", title = "App1", password = "p1"),
                VaultEntry(syncId = "id-2", title = "App2", password = "p2")
            )
        )
        val current = repo.decryptedEntries.value
        val app1 = current.first { it.syncId == "id-1" }
        val app2 = current.first { it.syncId == "id-2" }

        val result = merge(
            listOf(
                item("id-1", SyncChange.REMOTE_CHANGED, local = app1, remote = app1.copy(password = "p1_updated")),
                item("id-2", SyncChange.CONFLICT, ItemOutcome.USE_REMOTE, local = app2, remote = app2.copy(password = "p2_updated")),
                item("id-3", SyncChange.NEW_REMOTE, remote = VaultEntry(syncId = "id-3", title = "App3", password = "p3")),
                item("id-4", SyncChange.NEW_REMOTE, remote = VaultEntry(syncId = "id-4", title = "App4", password = "p4"))
            )
        )
        assertEquals(4, result.getOrThrow())

        val merged = repo.decryptedEntries.value
        assertEquals(4, merged.size)
        assertEquals("p1_updated", merged.first { it.id == app1.id }.password)
        assertEquals("p2_updated", merged.first { it.id == app2.id }.password)
        assertEquals("p3", merged.first { it.syncId == "id-3" }.password)
        assertEquals("p4", merged.first { it.syncId == "id-4" }.password)
    }

    @Test
    fun merge_endToEnd_andTheFingerprintTheReviewerPromised() = runTest {
        repo.insertEntries(
            listOf(
                VaultEntry(syncId = "id-same", title = "Same", password = "p", timestamp = 1_000L),
                VaultEntry(syncId = "id-old", title = "Old", password = "p", timestamp = 1_000L),
                VaultEntry(syncId = "id-mine", title = "Mine", password = "p", timestamp = 1_000L)
            )
        )
        val local = SyncRecordMapper.localState(repo.getSyncSnapshot())
        val remote = listOf(
            record("id-same", "Same", username = "", password = "p").copy(category = "Personal", updatedAt = 1_000L),
            record("id-old", "Old", username = "", password = "new").copy(category = "Personal", updatedAt = 2_000L),
            record("id-new", "New")
        )
        val diff = SyncDiffEngine.computeDiff(local.records, remote, 0L, local.blockedIds, local.entriesBySyncId)
        assertEquals(1, diff.unchangedCount)
        assertEquals(1, diff.modifiedCount)
        assertEquals(1, diff.newRemoteCount)
        assertEquals(1, diff.newLocalCount)

        val now = System.currentTimeMillis()
        val items = MergePlans.build(diff.diffItems.map { it.toPlanInput() }, now)
        val promised = MergePlans.vaultFingerprint(SyncMergeExecutor.projectRecords(local.records, items))

        // Our own entry is re-stored too, because the plan sends it to the other device.
        assertEquals(3, SyncMergeExecutor.applyPlan(items, repo, now).getOrThrow())

        val after = SyncRecordMapper.localState(repo.getSyncSnapshot()).records
        assertEquals(
            "What the reviewer promised is what it really holds",
            promised,
            MergePlans.vaultFingerprint(after)
        )
        assertEquals(setOf("id-same", "id-old", "id-new", "id-mine"), after.map { it.syncId }.toSet())
    }

    @Test
    fun fingerprint_tellsTwoDevicesApartAndAgreesWhenTheyMatch() {
        val one = listOf(record("id-a", "A"), record("id-b", "B"))
        val same = listOf(record("id-b", "B"), record("id-a", "A"))
        assertEquals(MergePlans.vaultFingerprint(one), MergePlans.vaultFingerprint(same))
        assertNotEquals(
            MergePlans.vaultFingerprint(one),
            MergePlans.vaultFingerprint(one + record("id-c", "C"))
        )
        assertEquals(
            "Tombstones are not part of the fingerprint",
            MergePlans.vaultFingerprint(one),
            MergePlans.vaultFingerprint(one + SyncRecords.tombstone("id-gone", 1L))
        )
    }

    // --- Field limits (shared with the desktop) ---

    @Test
    fun entriesAtTheSharedLimits_areStoredAndTravelWhole() = runTest {
        val entry = VaultEntry(
            syncId = "id-big",
            title = "T".repeat(EntryLimits.TITLE),
            username = "U".repeat(EntryLimits.USERNAME),
            password = "P".repeat(EntryLimits.PASSWORD),
            website = "W".repeat(EntryLimits.WEBSITE),
            notes = "N".repeat(EntryLimits.NOTES),
            tags = (1..EntryLimits.MAX_TAGS).map { "tag$it" },
            customFields = (1..EntryLimits.MAX_CUSTOM_FIELDS).map {
                CustomField("k$it".padEnd(EntryLimits.CUSTOM_FIELD_KEY, 'x'), "v$it".padEnd(EntryLimits.CUSTOM_FIELD_VALUE, 'y'))
            },
            timestamp = 1_000L
        )
        assertTrue(EntryLimits.fits(SyncRecordMapper.toRecord(entry)))

        repo.insertEntry(entry)
        val stored = repo.decryptedEntries.value.single()
        assertEquals(EntryLimits.TITLE, stored.title.length)
        assertEquals(EntryLimits.NOTES, stored.notes.length)
        assertEquals(EntryLimits.MAX_TAGS, stored.tags.size)
        assertEquals(EntryLimits.MAX_CUSTOM_FIELDS, stored.customFields.size)

        val overTheWire = SyncRecords.encodeChunks(listOf(SyncRecordMapper.toRecord(stored)))
            .flatMap { SyncRecords.decodeChunk(it) }
            .single()
        assertEquals(SyncRecordMapper.toRecord(stored), overTheWire)
    }

    @Test
    fun aFieldOverTheSharedLimit_isReported() {
        val tooLong = SyncRecordMapper.toRecord(
            VaultEntry(syncId = "id-x", title = "T".repeat(EntryLimits.TITLE + 1))
        )
        assertFalse(EntryLimits.fits(tooLong))
        assertEquals("Max ${EntryLimits.TITLE} characters", EntryLimits.tooLong(EntryLimits.TITLE))
    }
}
