package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
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
}
