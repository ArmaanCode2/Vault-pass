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
import com.example.domain.sync.diff.SyncEntryManifest
import com.example.domain.sync.diff.SyncMergeExecutor
import com.example.domain.sync.models.SyncEntryDto
import com.example.security.VaultSessionManager
import com.example.ui.VaultViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncDiffAndMergeTest {

    private lateinit var context: Context
    private lateinit var app: VaultPassApplication
    private lateinit var viewModel: VaultViewModel

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

    // --- Hash and Manifest Compatibility Tests ---

    @Test
    fun syncKey_normalizationAndTrimming() {
        val key1 = SyncEntryManifest.computeSyncKey("  Google  ", "  Armaan@Gmail.com  ")
        val key2 = SyncEntryManifest.computeSyncKey("google", "armaan@gmail.com")
        assertEquals("google|armaan@gmail.com", key1)
        assertEquals(key1, key2)
    }

    @Test
    fun contentHash_matchesDesktopAlgorithmByteForByte() {
        val password = "SuperSecretPassword123!"
        val website = "https://github.com"
        val notes = "Primary dev account"
        val category = "Work"
        val tags = listOf("developer", "git", "auth")

        // Manual SHA-256 calculation matching specification:
        // raw = "${secret}|${url}|${notes}|${category ?: ""}|${tags.sorted().joinToString(",")}"
        val sortedTags = "auth,developer,git"
        val raw = "$password|$website|$notes|$category|$sortedTags"
        val expectedHash = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        val actualHash = SyncEntryManifest.computeContentHash(
            secret = password,
            url = website,
            notes = notes,
            category = category,
            tags = tags
        )

        assertEquals(expectedHash, actualHash)
        assertEquals(64, actualHash.length) // 256 bits = 64 hex characters
    }

    @Test
    fun syncManifest_fromEntry_populatesAllFields() {
        val entry = VaultEntry(
            id = 5,
            title = "Amazon AWS",
            username = "admin@company.com",
            password = "AwsPassword456!",
            website = "https://aws.amazon.com",
            notes = "Root account",
            category = "Cloud",
            tags = listOf("devops", "aws")
        )

        val item = SyncEntryManifest.fromEntry(entry)
        assertEquals("amazon aws|admin@company.com", item.syncKey)
        assertEquals("Amazon AWS", item.title)
        assertEquals("admin@company.com", item.username)
        assertEquals(entry.timestamp, item.updatedAt)
        assertFalse(item.fullContentHash.isBlank())

        val manifest = SyncEntryManifest.createManifest("device-123", listOf(entry))
        assertEquals("device-123", manifest.deviceId)
        assertEquals(1, manifest.items.size)
        assertEquals(item.syncKey, manifest.items[0].syncKey)
    }

    // --- Diff Engine Tests ---

    @Test
    fun syncDiffEngine_classifiesAllCategoriesAccurately() {
        val localUnchanged = VaultEntry(
            id = 1,
            title = "GitHub",
            username = "alice",
            password = "passwordA",
            website = "https://github.com",
            notes = "same",
            category = "Work"
        )
        val localModified = VaultEntry(
            id = 2,
            title = "GitLab",
            username = "alice",
            password = "oldPassword",
            website = "https://gitlab.com",
            notes = "old notes",
            category = "Work"
        )
        val localOnly = VaultEntry(
            id = 3,
            title = "LocalOnlyApp",
            username = "alice",
            password = "localSecret"
        )

        val remoteUnchanged = localUnchanged.copy()
        val remoteModified = localModified.copy(
            password = "newUpdatedPassword",
            notes = "new notes from desktop"
        )
        val remoteOnly = VaultEntry(
            id = 99,
            title = "DesktopOnlyApp",
            username = "alice",
            password = "desktopSecret"
        )

        val localEntries = listOf(localUnchanged, localModified, localOnly)
        val remoteEntries = listOf(remoteUnchanged, remoteModified, remoteOnly)

        val diffResult = SyncDiffEngine.computeDiff(localEntries, remoteEntries)

        assertEquals(1, diffResult.newRemoteCount)
        assertEquals(1, diffResult.newLocalCount)
        assertEquals(1, diffResult.modifiedCount)
        assertEquals(1, diffResult.unchangedCount)
        assertEquals(4, diffResult.diffItems.size)

        val newRemoteItem = diffResult.diffItems.first { it.syncKey == "desktoponlyapp|alice" }
        assertEquals(EntrySyncCategory.NEW_REMOTE, newRemoteItem.category)
        assertTrue("New remote should default selected", newRemoteItem.isSelectedForSync)
        assertNull(newRemoteItem.localEntry)
        assertNotNull(newRemoteItem.remoteEntry)

        val newLocalItem = diffResult.diffItems.first { it.syncKey == "localonlyapp|alice" }
        assertEquals(EntrySyncCategory.NEW_LOCAL, newLocalItem.category)
        assertFalse("New local should not be auto-selected for import", newLocalItem.isSelectedForSync)

        val modifiedItem = diffResult.diffItems.first { it.syncKey == "gitlab|alice" }
        assertEquals(EntrySyncCategory.MODIFIED, modifiedItem.category)
        assertTrue("Modified should default selected", modifiedItem.isSelectedForSync)
        val passwordDiff = modifiedItem.fieldDiffs.first { it.fieldName == "Password" }
        assertEquals(FieldChangeType.MODIFIED, passwordDiff.changeType)
        assertEquals("oldPassword", passwordDiff.localValue)
        assertEquals("newUpdatedPassword", passwordDiff.remoteValue)

        val unchangedItem = diffResult.diffItems.first { it.syncKey == "github|alice" }
        assertEquals(EntrySyncCategory.UNCHANGED, unchangedItem.category)
        assertFalse("Unchanged should default deselected", unchangedItem.isSelectedForSync)
    }

    // --- Atomic Merge Executor Tests ---

    @Test
    fun syncMergeExecutor_insertsNewRemoteWithIdZero_andUpdatesModifiedWithExistingId() = runTest {
        val vaultRepo = app.container.vaultRepository

        // 1. Pre-insert an existing local entry to test MODIFIED update
        val existingEntry = VaultEntry(
            id = 0, // auto-generated
            title = "Netflix",
            username = "user@home.org",
            password = "OldNetflixPassword123!",
            notes = "Shared account"
        )
        vaultRepo.insertEntry(existingEntry)
        val initialEntries = vaultRepo.decryptedEntries.value
        assertEquals(1, initialEntries.size)
        val existingId = initialEntries[0].id
        assertTrue(existingId > 0)

        // 2. Prepare Diff Items:
        // Item A: MODIFIED Netflix entry (should keep existingId)
        val modifiedRemote = existingEntry.copy(
            id = 777, // remote arbitrary ID
            password = "NewNetflixPassword456!",
            notes = "Updated on Desktop"
        )
        val modifiedDiffItem = EntryDiffItem(
            syncKey = "netflix|user@home.org",
            category = EntrySyncCategory.MODIFIED,
            localEntry = initialEntries[0],
            remoteEntry = modifiedRemote,
            fieldDiffs = emptyList(),
            isSelectedForSync = true,
            editedEntry = null
        )

        // Item B: NEW_REMOTE Spotify entry (should get id = 0, then Room auto-generates)
        val newRemote = VaultEntry(
            id = 888,
            title = "Spotify",
            username = "music@home.org",
            password = "SpotifySecret789!"
        )
        val newRemoteDiffItem = EntryDiffItem(
            syncKey = "spotify|music@home.org",
            category = EntrySyncCategory.NEW_REMOTE,
            localEntry = null,
            remoteEntry = newRemote,
            fieldDiffs = emptyList(),
            isSelectedForSync = true,
            editedEntry = null
        )

        // 3. Execute atomic merge
        val mergeResult = SyncMergeExecutor.executeMerge(
            listOf(modifiedDiffItem, newRemoteDiffItem),
            vaultRepo
        )

        assertTrue(mergeResult.isSuccess)
        assertEquals(2, mergeResult.getOrNull())

        // 4. Verify merged contents in database
        val postMergeEntries = vaultRepo.decryptedEntries.value
        assertEquals(2, postMergeEntries.size)

        // Verify updated entry preserved local primary key
        val updatedLocal = postMergeEntries.first { it.title == "Netflix" }
        assertEquals(existingId, updatedLocal.id)
        assertEquals("NewNetflixPassword456!", updatedLocal.password)
        assertEquals("Updated on Desktop", updatedLocal.notes)

        // Verify newly inserted entry got fresh primary key
        val insertedSpotify = postMergeEntries.first { it.title == "Spotify" }
        assertNotEquals(888, insertedSpotify.id)
        assertTrue(insertedSpotify.id > 0)
        assertEquals("SpotifySecret789!", insertedSpotify.password)
    }

    @Test
    fun syncMergeExecutor_inlineEditingOverrideIsRespected() = runTest {
        val vaultRepo = app.container.vaultRepository

        val remoteEntry = VaultEntry(
            id = 100,
            title = "Dropbox",
            username = "box@test.com",
            password = "RemotePassword!",
            notes = "Remote notes"
        )

        // User edited the entry inline before merging
        val userEditedEntry = remoteEntry.copy(
            title = "Dropbox Pro",
            password = "CustomOverriddenPassword#999",
            notes = "User edited notes"
        )

        val diffItem = EntryDiffItem(
            syncKey = "dropbox|box@test.com",
            category = EntrySyncCategory.NEW_REMOTE,
            localEntry = null,
            remoteEntry = remoteEntry,
            fieldDiffs = emptyList(),
            isSelectedForSync = true,
            editedEntry = userEditedEntry // override present
        )

        val mergeResult = SyncMergeExecutor.executeMerge(listOf(diffItem), vaultRepo)
        assertTrue(mergeResult.isSuccess)
        assertEquals(1, mergeResult.getOrNull())

        val entries = vaultRepo.decryptedEntries.value
        val merged = entries.first { it.username == "box@test.com" }
        assertEquals("Dropbox Pro", merged.title)
        assertEquals("CustomOverriddenPassword#999", merged.password)
        assertEquals("User edited notes", merged.notes)
    }

    @Test
    fun syncMergeExecutor_unselectedItemsAreIgnored() = runTest {
        val vaultRepo = app.container.vaultRepository

        val unselectedDiffItem = EntryDiffItem(
            syncKey = "ignore|test",
            category = EntrySyncCategory.NEW_REMOTE,
            localEntry = null,
            remoteEntry = VaultEntry(title = "Ignored", username = "test"),
            fieldDiffs = emptyList(),
            isSelectedForSync = false // Unselected!
        )

        val result = SyncMergeExecutor.executeMerge(listOf(unselectedDiffItem), vaultRepo)
        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrNull())
        assertTrue(vaultRepo.decryptedEntries.value.isEmpty())
    }

    // --- Dual Serialization & DTO Compatibility Tests ---

    @Test
    fun syncEntryDto_fromVaultEntry_populatesDualKeysAndSerializes() {
        val entry = VaultEntry(
            id = 10,
            title = "GitHub",
            username = "octocat",
            password = "SecretPassword123!",
            website = "https://github.com",
            notes = "Work dev account",
            category = "Work",
            tags = listOf("code", "git"),
            customFields = listOf(CustomField("2FA", "OTP")),
            isFavorite = true,
            timestamp = 1700000000000L
        )

        val dto = SyncEntryDto.fromVaultEntry(entry)
        assertEquals("SecretPassword123!", dto.password)
        assertEquals("SecretPassword123!", dto.secret)
        assertEquals("https://github.com", dto.website)
        assertEquals("https://github.com", dto.url)
        assertEquals(1700000000000L, dto.timestamp)
        assertEquals(1700000000000L, dto.updatedAt)

        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(SyncEntryDto.serializer(), dto)

        assertTrue("JSON contains password", encoded.contains("\"password\":\"SecretPassword123!\""))
        assertTrue("JSON contains secret", encoded.contains("\"secret\":\"SecretPassword123!\""))
        assertTrue("JSON contains website", encoded.contains("\"website\":\"https://github.com\""))
        assertTrue("JSON contains url", encoded.contains("\"url\":\"https://github.com\""))
        assertTrue("JSON contains timestamp", encoded.contains("\"timestamp\":1700000000000"))
        assertTrue("JSON contains updatedAt", encoded.contains("\"updatedAt\":1700000000000"))
    }

    @Test
    fun syncEntryDto_toVaultEntry_parsesDesktopPayloadWithoutDataLoss() {
        val desktopJson = """
            {
                "id": 101,
                "title": "DesktopCreated",
                "username": "desktopUser",
                "secret": "DesktopSecretPassword999!",
                "url": "https://desktop-service.org",
                "notes": "Created from desktop app",
                "category": "Cloud",
                "tags": ["desktop", "sync"],
                "isFavorite": true,
                "updatedAt": 1725000000000
            }
        """.trimIndent()

        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
        val dto = json.decodeFromString(SyncEntryDto.serializer(), desktopJson)
        val vaultEntry = dto.toVaultEntry()

        assertEquals("DesktopCreated", vaultEntry.title)
        assertEquals("desktopUser", vaultEntry.username)
        assertEquals("DesktopSecretPassword999!", vaultEntry.password)
        assertEquals("https://desktop-service.org", vaultEntry.website)
        assertEquals(1725000000000L, vaultEntry.timestamp)
        assertTrue(vaultEntry.isFavorite)
        assertEquals(listOf("desktop", "sync"), vaultEntry.tags)
    }

    // --- Recycle Bin Defense Tests ---

    @Test
    fun syncDiffEngine_respectsRecycleBinDefense() {
        val remoteDeletedLocally = VaultEntry(
            title = "DeletedApp",
            username = "deletedUser",
            password = "deletedPass"
        )
        val remoteNew = VaultEntry(
            title = "BrandNewApp",
            username = "newUser",
            password = "newPass"
        )

        val deletedSyncKey = SyncEntryManifest.computeSyncKey("DeletedApp", "deletedUser")
        val recycleBinKeys = setOf(deletedSyncKey)

        val diffResult = SyncDiffEngine.computeDiff(
            localEntries = emptyList(),
            remoteEntries = listOf(remoteDeletedLocally, remoteNew),
            recycleBinKeys = recycleBinKeys
        )

        assertEquals(1, diffResult.newRemoteCount)
        assertEquals(1, diffResult.deletedLocalCount)

        val deletedItem = diffResult.diffItems.first { it.syncKey == deletedSyncKey }
        assertEquals(EntrySyncCategory.DELETED_LOCAL, deletedItem.category)
        assertFalse("Item in recycle bin must NOT be auto-selected", deletedItem.isSelectedForSync)

        val newItem = diffResult.diffItems.first { it.syncKey == SyncEntryManifest.computeSyncKey("BrandNewApp", "newUser") }
        assertEquals(EntrySyncCategory.NEW_REMOTE, newItem.category)
        assertTrue("Item not in recycle bin should be auto-selected", newItem.isSelectedForSync)
    }

    // --- Favorite Field Diffing Test ---

    @Test
    fun syncDiffEngine_detectsFavoriteFieldDifference() {
        val local = VaultEntry(
            id = 1,
            title = "StarMe",
            username = "starUser",
            password = "samePassword",
            isFavorite = false
        )
        val remote = local.copy(isFavorite = true)

        val diffResult = SyncDiffEngine.computeDiff(
            localEntries = listOf(local),
            remoteEntries = listOf(remote)
        )

        assertEquals(1, diffResult.modifiedCount)
        val item = diffResult.diffItems.first()
        assertEquals(EntrySyncCategory.MODIFIED, item.category)
        assertTrue(item.isSelectedForSync)

        val favDiff = item.fieldDiffs.first { it.fieldName == "Favorite" }
        assertEquals(FieldChangeType.MODIFIED, favDiff.changeType)
        assertEquals("false", favDiff.localValue)
        assertEquals("true", favDiff.remoteValue)
    }

    // --- Batch Merge Test ---

    @Test
    fun syncMergeExecutor_batchMergesMultipleEntriesAtomically() = runTest {
        val vaultRepo = app.container.vaultRepository

        // Pre-insert 2 entries to be modified
        val entry1 = VaultEntry(title = "App1", username = "user1", password = "p1")
        val entry2 = VaultEntry(title = "App2", username = "user2", password = "p2")
        vaultRepo.insertEntries(listOf(entry1, entry2))

        val current = vaultRepo.decryptedEntries.value
        val id1 = current.first { it.title == "App1" }.id
        val id2 = current.first { it.title == "App2" }.id

        val diffItems = listOf(
            // Modified 1
            EntryDiffItem(
                syncKey = "app1|user1",
                category = EntrySyncCategory.MODIFIED,
                localEntry = current.first { it.title == "App1" },
                remoteEntry = current.first { it.title == "App1" }.copy(password = "p1_updated"),
                fieldDiffs = emptyList(),
                isSelectedForSync = true
            ),
            // Modified 2
            EntryDiffItem(
                syncKey = "app2|user2",
                category = EntrySyncCategory.MODIFIED,
                localEntry = current.first { it.title == "App2" },
                remoteEntry = current.first { it.title == "App2" }.copy(password = "p2_updated"),
                fieldDiffs = emptyList(),
                isSelectedForSync = true
            ),
            // New remote 1
            EntryDiffItem(
                syncKey = "app3|user3",
                category = EntrySyncCategory.NEW_REMOTE,
                localEntry = null,
                remoteEntry = VaultEntry(title = "App3", username = "user3", password = "p3"),
                fieldDiffs = emptyList(),
                isSelectedForSync = true
            ),
            // New remote 2
            EntryDiffItem(
                syncKey = "app4|user4",
                category = EntrySyncCategory.NEW_REMOTE,
                localEntry = null,
                remoteEntry = VaultEntry(title = "App4", username = "user4", password = "p4"),
                fieldDiffs = emptyList(),
                isSelectedForSync = true
            )
        )

        val result = SyncMergeExecutor.executeMerge(diffItems, vaultRepo)
        assertTrue(result.isSuccess)
        assertEquals(4, result.getOrNull())

        val merged = vaultRepo.decryptedEntries.value
        assertEquals(4, merged.size)
        assertEquals("p1_updated", merged.first { it.id == id1 }.password)
        assertEquals("p2_updated", merged.first { it.id == id2 }.password)
        assertNotNull(merged.find { it.title == "App3" && it.password == "p3" })
        assertNotNull(merged.find { it.title == "App4" && it.password == "p4" })
    }

    @Test
    fun syncMergeExecutor_deletedLocalSelectedIsReinserted() = runTest {
        val vaultRepo = app.container.vaultRepository

        val deletedDiffItem = EntryDiffItem(
            syncKey = "reinsert|user",
            category = EntrySyncCategory.DELETED_LOCAL,
            localEntry = null,
            remoteEntry = VaultEntry(title = "Reinsert", username = "user", password = "pass"),
            fieldDiffs = emptyList(),
            isSelectedForSync = true
        )

        val result = SyncMergeExecutor.executeMerge(listOf(deletedDiffItem), vaultRepo)
        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull())
        val entries = vaultRepo.decryptedEntries.value
        assertEquals(1, entries.size)
        assertEquals("Reinsert", entries[0].title)
    }
}
