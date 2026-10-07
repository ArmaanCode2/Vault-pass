package com.example.repository

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.example.data.VaultDao
import com.example.data.models.AutofillAppLinkEntity
import com.example.data.models.SyncTombstoneEntity
import com.example.data.models.VaultEntryEntity
import com.example.domain.models.CustomField
import com.example.domain.models.VaultEntry
import com.example.domain.models.VaultListEntry
import com.example.domain.models.VaultListPreview
import com.example.security.CryptoManager
import com.example.security.VaultLockedException
import com.example.service.AutofillCredentialMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.vaultpass.synccore.SyncRecords
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** What this device holds, as sync needs it: live entries, the recycle bin and removed entries. */
class SyncSnapshot(
    val active: List<VaultEntry>,
    val recycleBin: List<VaultEntry>,
    val tombstones: List<SyncTombstoneEntity>
)

/**
 * Rows (by id, with the exact stored value) that failed to decrypt under one key generation. A
 * new generation (lock, unlock, other key) forgets them all: they may open with that key.
 */
private class UndecryptableRows {
    private val rows = HashMap<Int, VaultEntryEntity>()
    private var generation = -1L

    @Synchronized
    fun contains(entity: VaultEntryEntity, gen: Long): Boolean = generation == gen && rows[entity.id] == entity

    /** After a refresh under [gen]: the unreadable rows are exactly [failed]. */
    @Synchronized
    fun update(gen: Long, failed: Map<Int, VaultEntryEntity>) {
        rows.clear()
        rows.putAll(failed)
        generation = gen
    }

    @Synchronized
    fun clear() {
        rows.clear()
        generation = -1L
    }

    @Synchronized
    fun size(): Int = rows.size
}

class VaultRepository(
    private val vaultDao: VaultDao,
    val cryptoManager: CryptoManager,
    private val database: RoomDatabase
) {
    private val repoScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cacheMutex = Mutex()

    // Guards key changes against publishing decrypted data (see publishIfCurrent). Never held
    // while waiting on anything: lock() runs on the main thread and must stay instant.
    private val keyLock = Any()

    private val decryptedCacheMap = ConcurrentHashMap<Int, Pair<VaultEntryEntity, VaultEntry>>()
    private val recycleBinCacheMap = ConcurrentHashMap<Int, Pair<VaultEntryEntity, VaultEntry>>()

    // Rows that really don't decrypt under the current key (only recorded when the key was there
    // throughout), so each refresh shows their placeholder without decrypting them again.
    private val activeUndecryptable = UndecryptableRows()
    private val recycleBinUndecryptable = UndecryptableRows()

    private var activeEntriesJob: kotlinx.coroutines.Job? = null
    private var recycleBinJob: kotlinx.coroutines.Job? = null

    private val _decryptedEntries = MutableStateFlow<List<VaultEntry>>(emptyList())
    val decryptedEntries: StateFlow<List<VaultEntry>> = _decryptedEntries.asStateFlow()

    private val _recycleBinEntries = MutableStateFlow<List<VaultListEntry>>(emptyList())
    val recycleBinEntries: StateFlow<List<VaultListEntry>> = _recycleBinEntries.asStateFlow()

    val allEntries: Flow<List<VaultEntry>> = _decryptedEntries.asStateFlow()

    val allRawEntities = vaultDao.getAllEntries()

    private fun startCollectors() {
        activeEntriesJob?.cancel()
        recycleBinJob?.cancel()
        // A change only triggers a refresh; the refresh reads the rows itself (see refreshActiveCache).
        activeEntriesJob = repoScope.launch {
            vaultDao.getAllEntries().collect {
                refreshActiveCache()
            }
        }
        recycleBinJob = repoScope.launch {
            vaultDao.getRecycleBinEntries().collect {
                refreshRecycleBinCache()
            }
        }
    }

    init {
        startCollectors()
    }

    // The refreshes below decrypt into local results and publish them only if the vault key is
    // still the one they started with: a lock (or lock and unlock) while they ran publishes nothing.
    // They read the rows under cacheMutex, so the refresh that publishes last always read last: a
    // collector's refresh queued behind a write's own refresh can't publish the rows from before it.
    private suspend fun refreshActiveCache(): Unit = cacheMutex.withLock {
        val start = cryptoManager.keyState()
        if (!start.hasKey) {
            clearIfStillLocked(start.generation) {
                decryptedCacheMap.clear()
                _decryptedEntries.value = emptyList()
            }
            return@withLock
        }
        val gen = start.generation
        val entities = vaultDao.getAllEntriesSync()

        val currentIds = entities.map { it.id }.toSet()
        val decryptedNow = HashMap<Int, Pair<VaultEntryEntity, VaultEntry>>()
        val failedNow = HashMap<Int, VaultEntryEntity>()

        val decryptedList = entities.map { entity ->
            val cached = decryptedCacheMap[entity.id]
            if (cached != null && cached.first == entity) {
                cached.second
            } else {
                decryptForRefresh(entity, gen, activeUndecryptable, decryptedNow, failedNow)
            }
        }
        publishIfCurrent(gen) {
            decryptedCacheMap.keys.retainAll(currentIds)
            decryptedCacheMap.putAll(decryptedNow)
            activeUndecryptable.update(gen, failedNow)
            _decryptedEntries.value = decryptedList
        }
    }

    private suspend fun refreshRecycleBinCache(): Unit = cacheMutex.withLock {
        val start = cryptoManager.keyState()
        if (!start.hasKey) {
            clearIfStillLocked(start.generation) {
                recycleBinCacheMap.clear()
                _recycleBinEntries.value = emptyList()
            }
            return@withLock
        }
        val gen = start.generation
        val entities = vaultDao.getRecycleBinEntriesSync()

        val currentIds = entities.map { it.id }.toSet()
        val decryptedNow = HashMap<Int, Pair<VaultEntryEntity, VaultEntry>>()
        val failedNow = HashMap<Int, VaultEntryEntity>()

        val list = entities.map { entity ->
            val cached = recycleBinCacheMap[entity.id]
            val decrypted = if (cached != null && cached.first == entity) {
                cached.second
            } else {
                decryptForRefresh(entity, gen, recycleBinUndecryptable, decryptedNow, failedNow)
            }
            entryToVaultListEntry(decrypted)
        }
        publishIfCurrent(gen) {
            recycleBinCacheMap.keys.retainAll(currentIds)
            recycleBinCacheMap.putAll(decryptedNow)
            recycleBinUndecryptable.update(gen, failedNow)
            _recycleBinEntries.value = list
        }
    }

    /**
     * Decrypts [entity] for a refresh under key generation [gen]: readable rows go to [decryptedNow]
     * (cached once published), unreadable ones to [failedNow]. A row already known to be unreadable
     * under this key is not decrypted again.
     */
    private fun decryptForRefresh(
        entity: VaultEntryEntity,
        gen: Long,
        undecryptable: UndecryptableRows,
        decryptedNow: MutableMap<Int, Pair<VaultEntryEntity, VaultEntry>>,
        failedNow: MutableMap<Int, VaultEntryEntity>
    ): VaultEntry {
        refreshDecryptHook?.invoke()
        if (undecryptable.contains(entity, gen)) {
            failedNow[entity.id] = entity
            return failedPlaceholder(entity)
        }
        val decrypted = decryptEntity(entity)
        if (decrypted.isDecryptionFailed) failedNow[entity.id] = entity else decryptedNow[entity.id] = Pair(entity, decrypted)
        return decrypted
    }

    /**
     * Runs [apply] (which publishes decrypted data) only if the vault key is still the one of
     * generation [gen]; otherwise publishes nothing and returns false. Runs under the same lock as
     * [clearSoftwareDek], so nothing decrypted under an old key lands after the lock cleared it.
     */
    internal fun publishIfCurrent(gen: Long, apply: () -> Unit): Boolean {
        synchronized(keyLock) {
            val now = cryptoManager.keyState()
            if (now.generation != gen || !now.hasKey) return false
            apply()
            return true
        }
    }

    /** Tests: how many rows are remembered as not decrypting under the current key. */
    @androidx.annotation.VisibleForTesting
    internal fun knownUndecryptableCount(): Int = activeUndecryptable.size() + recycleBinUndecryptable.size()

    /** Tests: how many decrypted rows are cached (live list and recycle bin). */
    @androidx.annotation.VisibleForTesting
    internal fun cachedEntryCount(): Int = decryptedCacheMap.size + recycleBinCacheMap.size

    /** Tests: runs inside a refresh before each row it decrypts (e.g. to lock the vault mid-refresh). */
    @androidx.annotation.VisibleForTesting
    @Volatile
    internal var refreshDecryptHook: (() -> Unit)? = null

    /** The locked counterpart of [publishIfCurrent]: clears only if no key was injected since [gen]. */
    private fun clearIfStillLocked(gen: Long, clear: () -> Unit) {
        synchronized(keyLock) {
            val now = cryptoManager.keyState()
            if (now.generation == gen && !now.hasKey) clear()
        }
    }

    /** The decrypted live entries; empty while the vault is locked (never "Decryption Failed" placeholders). */
    suspend fun getAllEntriesSync(): List<VaultEntry> = withContext(Dispatchers.IO) {
        if (!cryptoManager.hasKey()) return@withContext emptyList()
        val cached = _decryptedEntries.value
        if (cached.isNotEmpty()) {
            cached
        } else {
            if (vaultDao.countAllEntities() > 0) {
                refreshActiveCache()
                _decryptedEntries.value
            } else {
                emptyList()
            }
        }
    }

    /** Every stored row as it is on disk (recycle bin included), without decrypting anything. */
    suspend fun getAllRawEntitiesSync(): List<VaultEntryEntity> = withContext(Dispatchers.IO) {
        vaultDao.getAllEntitiesIncludingDeletedSync()
    }

    /** True when the vault table has any row (recycle bin included). */
    suspend fun hasAnyEntryRows(): Boolean = withContext(Dispatchers.IO) {
        vaultDao.countAllEntities() > 0
    }

    /**
     * Legacy-vault upgrade: replaces the rows in [expected] with [replacements] (same ids) in one
     * transaction. Throws, changing nothing, if any of those rows changed since it was read.
     * A row deleted in the meantime is skipped. Other rows are not touched.
     */
    suspend fun replaceMigratedRows(
        expected: List<VaultEntryEntity>,
        replacements: List<VaultEntryEntity>
    ) = withContext(Dispatchers.IO) {
        require(expected.size == replacements.size) { "Row count mismatch" }
        database.withTransaction {
            val toWrite = ArrayList<VaultEntryEntity>(replacements.size)
            expected.forEachIndexed { index, original ->
                val replacement = replacements[index]
                check(replacement.id == original.id) { "Row id mismatch" }
                val current = vaultDao.getEntryById(original.id) ?: return@forEachIndexed
                check(current == original) { "Row ${original.id} changed during the upgrade" }
                toWrite += replacement
            }
            if (toWrite.isNotEmpty()) {
                val updated = vaultDao.updateEntriesCounted(toWrite)
                check(updated == toWrite.size) { "Updated $updated of ${toWrite.size} rows" }
            }
        }
        // Like every other write: the list shows the recovered rows as soon as this returns.
        if (cryptoManager.hasKey()) {
            refreshActiveCache()
        }
    }

    fun injectSoftwareDek(dek: ByteArray) {
        val changed = synchronized(keyLock) {
            cryptoManager.loadSoftwareDek(dek)
        }
        // The same key again (e.g. a second unlock of an open vault) changes nothing and keeps the
        // running collectors; only a new key, or collectors a lock stopped, start them.
        if (changed || activeEntriesJob?.isActive != true || recycleBinJob?.isActive != true) {
            startCollectors()
        }
    }

    fun getSoftwareDek(): ByteArray? {
        return cryptoManager.getSoftwareDek()
    }

    fun clearSoftwareDek() {
        synchronized(keyLock) {
            activeEntriesJob?.cancel()
            recycleBinJob?.cancel()
            cryptoManager.clearSoftwareDek()
            decryptedCacheMap.clear()
            recycleBinCacheMap.clear()
            activeUndecryptable.clear()
            recycleBinUndecryptable.clear()
            _decryptedEntries.value = emptyList()
            _recycleBinEntries.value = emptyList()
        }
    }

    suspend fun getEntryById(id: Int): VaultEntry? = withContext(Dispatchers.IO) {
        val entity = vaultDao.getEntryById(id) ?: return@withContext null
        decryptEntity(entity)
    }

    suspend fun insertEntry(entry: VaultEntry) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val prepared = withSyncIdForInsert(entry, emptySet())
            vaultDao.insertEntry(encryptEntry(prepared))
            vaultDao.deleteTombstone(prepared.syncId)
        }
        if (cryptoManager.hasKey()) {
            refreshActiveCache()
        }
    }

    suspend fun insertEntries(entries: List<VaultEntry>) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val used = HashSet<String>()
            val prepared = entries.map { entry ->
                withSyncIdForInsert(entry, used).also { used += it.syncId }
            }
            vaultDao.insertEntries(prepared.map { encryptEntry(it) })
            prepared.forEach { vaultDao.deleteTombstone(it.syncId) }
        }
        if (cryptoManager.hasKey()) {
            refreshActiveCache()
        }
    }

    suspend fun updateEntry(entry: VaultEntry) = withContext(Dispatchers.IO) {
        if (entry.isDecryptionFailed) {
            throw IllegalStateException("Cannot update an entry that failed decryption. Preventing data loss.")
        }
        vaultDao.updateEntry(encryptEntry(withStoredSyncId(entry)))
        if (cryptoManager.hasKey()) {
            refreshActiveCache()
        }
    }

    suspend fun updateEntries(entries: List<VaultEntry>) = withContext(Dispatchers.IO) {
        val entities = entries.map {
            if (it.isDecryptionFailed) {
                throw IllegalStateException("Cannot update an entry that failed decryption. Preventing data loss.")
            }
            encryptEntry(withStoredSyncId(it))
        }
        vaultDao.updateEntries(entities)
        if (cryptoManager.hasKey()) {
            refreshActiveCache()
        }
    }

    suspend fun deleteEntry(id: Int) = withContext(Dispatchers.IO) {
        vaultDao.softDeleteEntry(id, System.currentTimeMillis())
        if (cryptoManager.hasKey()) {
            refreshActiveCache()
        }
    }

    suspend fun permanentlyDeleteEntry(id: Int) = withContext(Dispatchers.IO) {
        database.withTransaction {
            vaultDao.getEntryById(id)?.let { entity ->
                tombstoneFor(entity, System.currentTimeMillis())?.let { vaultDao.insertTombstone(it) }
                if (entity.syncId.isNotEmpty()) vaultDao.deleteAppLinksForSyncId(entity.syncId)
            }
            vaultDao.permanentlyDeleteEntry(id)
        }
        if (cryptoManager.hasKey()) {
            refreshActiveCache()
        }
    }

    suspend fun restoreEntry(id: Int) = withContext(Dispatchers.IO) {
        vaultDao.restoreEntry(id)
        if (cryptoManager.hasKey()) {
            refreshActiveCache()
        }
    }

    /**
     * Permanently deletes recycle-bin rows older than 7 days, writing a sync tombstone for each.
     * Only rows that fully decrypt with the current vault key are deleted: a row it can't read
     * (e.g. one still under a legacy key) is kept and never tombstoned, so its data and the other
     * device's copy survive. While locked (no key) it does nothing. Returns the number deleted.
     */
    suspend fun cleanupRecycleBin(): Int = withContext(Dispatchers.IO) {
        val dek = cryptoManager.getSoftwareDek() ?: return@withContext 0
        try {
            val key = javax.crypto.spec.SecretKeySpec(dek, "AES")
            val now = System.currentTimeMillis()
            val cutoff = now - (7L * 24 * 60 * 60 * 1000L) // 7 days
            database.withTransaction {
                val expired = vaultDao.getOldRecycleBinEntries(cutoff)
                    .filter { com.example.security.LegacyVaultMigration.readableWith(it, key) }
                if (expired.isNotEmpty()) {
                    vaultDao.insertTombstones(expired.mapNotNull { tombstoneFor(it, now) })
                    expired.filter { it.syncId.isNotEmpty() }.forEach { vaultDao.deleteAppLinksForSyncId(it.syncId) }
                    expired.forEach { vaultDao.permanentlyDeleteEntry(it.id) }
                }
                expired.size
            }
        } finally {
            java.util.Arrays.fill(dek, 0.toByte())
        }
    }

    private fun newSyncId(): String = UUID.randomUUID().toString()

    /**
     * New entries get a fresh syncId. A given one is kept unless another row (or another entry
     * of the same batch) already uses it.
     */
    private suspend fun withSyncIdForInsert(entry: VaultEntry, usedInBatch: Set<String>): VaultEntry {
        if (SyncRecords.isValidSyncId(entry.syncId) && entry.syncId !in usedInBatch) {
            val holder = vaultDao.getEntryBySyncId(entry.syncId)
            if (holder == null || holder.id == entry.id) return entry
        } else if (entry.id != 0) {
            val stored = vaultDao.getSyncIdById(entry.id)
            if (stored != null && SyncRecords.isValidSyncId(stored) && stored !in usedInBatch) {
                return entry.copy(syncId = stored)
            }
        }
        return entry.copy(syncId = newSyncId())
    }

    /** Editing never changes an entry's syncId (only the sync merge renames it). */
    private suspend fun withStoredSyncId(entry: VaultEntry): VaultEntry {
        val stored = vaultDao.getSyncIdById(entry.id) ?: return entry
        return if (stored == entry.syncId) entry else entry.copy(syncId = stored)
    }

    private fun tombstoneFor(entity: VaultEntryEntity, now: Long): SyncTombstoneEntity? =
        if (SyncRecords.isValidSyncId(entity.syncId)) SyncTombstoneEntity(entity.syncId, entity.deletedAt ?: now) else null

    // --- Sync ---

    /** Throws [VaultLockedException] if the vault is locked, or locked while it was read. */
    suspend fun getSyncSnapshot(): SyncSnapshot = withContext(Dispatchers.IO) {
        // Rows read without the key would all look unreadable to the peer.
        val start = cryptoManager.keyState()
        if (!start.hasKey) throw VaultLockedException()
        val gen = start.generation
        val snapshot = SyncSnapshot(
            active = vaultDao.getAllEntriesSync().map { decryptCached(it, gen, decryptedCacheMap, activeUndecryptable) },
            recycleBin = vaultDao.getRecycleBinEntriesSync().map { decryptCached(it, gen, recycleBinCacheMap, recycleBinUndecryptable) },
            tombstones = vaultDao.getAllTombstones()
        )
        if (cryptoManager.keyState() != start) throw VaultLockedException()
        snapshot
    }

    private fun decryptCached(
        entity: VaultEntryEntity,
        gen: Long,
        cache: Map<Int, Pair<VaultEntryEntity, VaultEntry>>,
        undecryptable: UndecryptableRows
    ): VaultEntry {
        cache[entity.id]?.takeIf { it.first == entity }?.let { return it.second }
        if (undecryptable.contains(entity, gen)) return failedPlaceholder(entity)
        return decryptEntity(entity)
    }

    /**
     * Runs a sync merge in one database transaction and refreshes the entry list afterwards.
     * Inside [block], use only the sync methods below (they don't switch threads).
     * Throws [VaultLockedException], changing nothing, if the vault is locked before or while it runs.
     */
    suspend fun <T> runSyncMerge(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        val start = cryptoManager.keyState()
        if (!start.hasKey) throw VaultLockedException()
        val result = database.withTransaction {
            val value = block()
            // Locked (or locked and unlocked) meanwhile: throwing here rolls the whole merge back.
            if (cryptoManager.keyState() != start) throw VaultLockedException()
            value
        }
        if (cryptoManager.hasKey()) {
            refreshActiveCache()
        }
        result
    }

    /** Renames an entry's syncId; its autofill app links move with it (same transaction). */
    suspend fun renameSyncId(from: String, to: String): Boolean = database.withTransaction {
        val renamed = vaultDao.renameSyncId(from, to) > 0
        if (renamed) vaultDao.renameAppLinks(from, to)
        renamed
    }

    /**
     * Stores [entry] under its syncId: replaces the content of the row holding that syncId (taking
     * it out of the recycle bin), or inserts a new row. Returns false, changing nothing, when the
     * stored row can't be decrypted here; throws [VaultLockedException] when that is because the
     * vault is locked.
     */
    suspend fun upsertSyncedEntry(entry: VaultEntry): Boolean {
        require(SyncRecords.isValidSyncId(entry.syncId)) { "Invalid syncId" }
        val existing = vaultDao.getEntryBySyncId(entry.syncId)
        val restored = entry.copy(isDeleted = false, deletedAt = null, isDecryptionFailed = false)
        if (existing != null) {
            if (decryptEntity(existing).isDecryptionFailed) {
                if (!cryptoManager.hasKey()) throw VaultLockedException()
                return false
            }
            vaultDao.updateEntry(encryptEntry(restored.copy(id = existing.id)))
        } else {
            vaultDao.insertEntry(encryptEntry(restored.copy(id = 0)))
        }
        vaultDao.deleteTombstone(entry.syncId)
        return true
    }

    suspend fun moveToRecycleBinBySyncId(syncId: String, timestamp: Long): Boolean =
        vaultDao.softDeleteBySyncId(syncId, timestamp) > 0

    // --- Autofill app links (local only: never part of sync records, exports or backups) ---

    /**
     * Remembers that the native app [packageName] uses the entry [syncId]. Refused (false) for packages that may
     * not be linked (browsers, see [AutofillCredentialMatcher.canLinkPackage]) and when no entry has that syncId.
     */
    suspend fun linkAutofillApp(packageName: String, syncId: String): Boolean = withContext(Dispatchers.IO) {
        if (!AutofillCredentialMatcher.canLinkPackage(packageName) || syncId.isEmpty()) return@withContext false
        database.withTransaction {
            if (vaultDao.getEntryBySyncId(syncId) == null) return@withTransaction false
            vaultDao.insertAppLink(AutofillAppLinkEntity(packageName, syncId, System.currentTimeMillis()))
            true
        }
    }

    /** SyncIds linked to [packageName]; always empty for packages that may not be linked (browsers). */
    suspend fun linkedAutofillSyncIds(packageName: String): Set<String> = withContext(Dispatchers.IO) {
        if (!AutofillCredentialMatcher.canLinkPackage(packageName)) return@withContext emptySet()
        vaultDao.getLinkedSyncIds(packageName).toSet()
    }

    private fun encryptEntry(entry: VaultEntry): VaultEntryEntity {
        return VaultEntryEntity(
            id = entry.id,
            titleEnc = cryptoManager.encrypt(entry.title),
            usernameEnc = cryptoManager.encrypt(entry.username),
            passwordEnc = cryptoManager.encrypt(entry.password),
            websiteEnc = cryptoManager.encrypt(entry.website),
            notesEnc = cryptoManager.encrypt(entry.notes),
            categoryEnc = cryptoManager.encrypt(entry.category),
            tagsEnc = cryptoManager.encrypt(Json.encodeToString(entry.tags)),
            customFieldsEnc = cryptoManager.encrypt(Json.encodeToString(entry.customFields)),
            isFavorite = entry.isFavorite,
            timestamp = entry.timestamp,
            isDeleted = entry.isDeleted,
            deletedAt = entry.deletedAt,
            syncId = entry.syncId
        )
    }

    fun entryToVaultListEntry(entry: VaultEntry): VaultListEntry {
        return VaultListEntry(
            id = entry.id,
            title = entry.title,
            // Username, else website host, else "". Never a password or custom-field value.
            username = VaultListPreview.line(entry),
            isFavorite = entry.isFavorite,
            isDecryptionFailed = entry.isDecryptionFailed,
            isDeleted = entry.isDeleted,
            deletedAt = entry.deletedAt
        )
    }

    fun decryptLightweight(entity: VaultEntryEntity): VaultListEntry {
        return entryToVaultListEntry(decryptEntity(entity))
    }

    /** What a row that can't be decrypted shows instead of its content. */
    private fun failedPlaceholder(entity: VaultEntryEntity): VaultEntry = VaultEntry(
        id = entity.id,
        title = "Decryption Failed",
        username = "",
        password = "",
        website = "",
        notes = "This entry could not be decrypted. The data might be corrupted or the encryption key was lost. Editing this entry has been disabled to prevent permanent data loss.",
        category = "Error",
        tags = emptyList(),
        customFields = emptyList(),
        isFavorite = entity.isFavorite,
        timestamp = entity.timestamp,
        isDecryptionFailed = true,
        isDeleted = entity.isDeleted,
        deletedAt = entity.deletedAt,
        syncId = entity.syncId
    )

    fun decryptEntity(entity: VaultEntryEntity): VaultEntry {
        val decryptedTitle = cryptoManager.decrypt(entity.titleEnc)
        val decryptedUsername = cryptoManager.decrypt(entity.usernameEnc)
        val decryptedPassword = cryptoManager.decrypt(entity.passwordEnc)
        val decryptedWebsite = cryptoManager.decrypt(entity.websiteEnc)
        val decryptedNotes = cryptoManager.decrypt(entity.notesEnc)
        val decryptedCategory = cryptoManager.decrypt(entity.categoryEnc)
        val decryptedTagsStr = cryptoManager.decrypt(entity.tagsEnc)
        val decryptedCustomFieldsStr = cryptoManager.decrypt(entity.customFieldsEnc)
        
        val hasFailure = (entity.titleEnc.isNotEmpty() && decryptedTitle == null) ||
                         (entity.usernameEnc.isNotEmpty() && decryptedUsername == null) ||
                         (entity.passwordEnc.isNotEmpty() && decryptedPassword == null) ||
                         (entity.websiteEnc.isNotEmpty() && decryptedWebsite == null) ||
                         (entity.notesEnc.isNotEmpty() && decryptedNotes == null) ||
                         (entity.categoryEnc.isNotEmpty() && decryptedCategory == null) ||
                         (entity.tagsEnc.isNotEmpty() && decryptedTagsStr == null) ||
                         (entity.customFieldsEnc.isNotEmpty() && decryptedCustomFieldsStr == null)
        
        if (hasFailure) {
            return failedPlaceholder(entity)
        }
        
        val tagsStr = decryptedTagsStr.takeIf { !it.isNullOrEmpty() } ?: "[]"
        val customFieldsStr = decryptedCustomFieldsStr.takeIf { !it.isNullOrEmpty() } ?: "[]"
        
        return VaultEntry(
            id = entity.id,
            title = decryptedTitle ?: "",
            username = decryptedUsername ?: "",
            password = decryptedPassword ?: "",
            website = decryptedWebsite ?: "",
            notes = decryptedNotes ?: "",
            category = decryptedCategory ?: "",
            tags = Json.decodeFromString(tagsStr),
            customFields = Json.decodeFromString(customFieldsStr),
            isFavorite = entity.isFavorite,
            timestamp = entity.timestamp,
            isDecryptionFailed = false,
            isDeleted = entity.isDeleted,
            deletedAt = entity.deletedAt,
            syncId = entity.syncId
        )
    }
}
