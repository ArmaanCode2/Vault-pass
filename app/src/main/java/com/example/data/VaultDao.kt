package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.models.AutofillAppLinkEntity
import com.example.data.models.SyncTombstoneEntity
import com.example.data.models.VaultEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultDao {
    @Query("SELECT * FROM vault_entries WHERE isDeleted = 0 ORDER BY timestamp DESC")
    fun getAllEntries(): Flow<List<VaultEntryEntity>>

    @Query("SELECT * FROM vault_entries WHERE isDeleted = 0 ORDER BY timestamp DESC")
    suspend fun getAllEntriesSync(): List<VaultEntryEntity>

    @Query("SELECT * FROM vault_entries WHERE id = :id")
    suspend fun getEntryById(id: Int): VaultEntryEntity?

    /** Every row, recycle bin included. */
    @Query("SELECT * FROM vault_entries")
    suspend fun getAllEntitiesIncludingDeletedSync(): List<VaultEntryEntity>

    @Query("SELECT COUNT(*) FROM vault_entries")
    suspend fun countAllEntities(): Int

    /** Returns the number of rows updated. */
    @Update
    suspend fun updateEntriesCounted(entries: List<VaultEntryEntity>): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: VaultEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntries(entries: List<VaultEntryEntity>)

    @Update
    suspend fun updateEntry(entry: VaultEntryEntity)

    @Update
    suspend fun updateEntries(entries: List<VaultEntryEntity>)

    @Query("DELETE FROM vault_entries WHERE id = :id")
    suspend fun permanentlyDeleteEntry(id: Int)

    @Query("UPDATE vault_entries SET isDeleted = 1, deletedAt = :timestamp WHERE id = :id")
    suspend fun softDeleteEntry(id: Int, timestamp: Long)

    @Query("UPDATE vault_entries SET isDeleted = 0, deletedAt = NULL WHERE id = :id")
    suspend fun restoreEntry(id: Int)

    @Query("SELECT * FROM vault_entries WHERE isDeleted = 1 ORDER BY deletedAt DESC")
    fun getRecycleBinEntries(): Flow<List<VaultEntryEntity>>

    @Query("DELETE FROM vault_entries WHERE isDeleted = 1 AND deletedAt <= :cutoffTimestamp")
    suspend fun deleteOldRecycleBinEntries(cutoffTimestamp: Long)

    @Query("SELECT * FROM vault_entries WHERE isDeleted = 1 AND deletedAt <= :cutoffTimestamp")
    suspend fun getOldRecycleBinEntries(cutoffTimestamp: Long): List<VaultEntryEntity>

    @Query("SELECT * FROM vault_entries WHERE isDeleted = 1 ORDER BY deletedAt DESC")
    suspend fun getRecycleBinEntriesSync(): List<VaultEntryEntity>

    // --- Sync ---

    /** Includes entries in the recycle bin. */
    @Query("SELECT * FROM vault_entries WHERE syncId = :syncId LIMIT 1")
    suspend fun getEntryBySyncId(syncId: String): VaultEntryEntity?

    @Query("SELECT syncId FROM vault_entries WHERE id = :id")
    suspend fun getSyncIdById(id: Int): String?

    @Query("UPDATE vault_entries SET syncId = :newSyncId WHERE syncId = :oldSyncId")
    suspend fun renameSyncId(oldSyncId: String, newSyncId: String): Int

    @Query("UPDATE vault_entries SET isDeleted = 1, deletedAt = :timestamp WHERE syncId = :syncId AND isDeleted = 0")
    suspend fun softDeleteBySyncId(syncId: String, timestamp: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTombstone(tombstone: SyncTombstoneEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTombstones(tombstones: List<SyncTombstoneEntity>)

    @Query("DELETE FROM sync_tombstones WHERE syncId = :syncId")
    suspend fun deleteTombstone(syncId: String)

    @Query("SELECT * FROM sync_tombstones")
    suspend fun getAllTombstones(): List<SyncTombstoneEntity>

    // --- Autofill app links (local only: never synced, exported or backed up) ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAppLink(link: AutofillAppLinkEntity)

    @Query("SELECT syncId FROM autofill_app_links WHERE packageName = :packageName")
    suspend fun getLinkedSyncIds(packageName: String): List<String>

    @Query("DELETE FROM autofill_app_links WHERE syncId = :syncId")
    suspend fun deleteAppLinksForSyncId(syncId: String)

    /** A link already held by [newSyncId] for the same package replaces the moved one (no constraint error). */
    @Query("UPDATE OR REPLACE autofill_app_links SET syncId = :newSyncId WHERE syncId = :oldSyncId")
    suspend fun renameAppLinks(oldSyncId: String, newSyncId: String): Int

    @Query("SELECT * FROM autofill_app_links")
    suspend fun getAllAppLinks(): List<AutofillAppLinkEntity>
}
