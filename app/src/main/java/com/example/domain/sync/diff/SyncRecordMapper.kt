package com.example.domain.sync.diff

import com.example.domain.models.CustomField
import com.example.domain.models.VaultEntry
import com.example.repository.SyncSnapshot
import com.vaultpass.synccore.CustomFieldRecord
import com.vaultpass.synccore.SyncRecord
import com.vaultpass.synccore.SyncRecords

/**
 * This device's side of a sync. [records] is what gets sent, [blockedIds] are entries that
 * can't be decrypted here (never sent, never touched), and [entriesBySyncId] holds the local
 * entries (live and in the recycle bin) for the review screen.
 */
class LocalSyncState(
    val records: List<SyncRecord>,
    val blockedIds: Set<String>,
    val entriesBySyncId: Map<String, VaultEntry>
)

object SyncRecordMapper {

    fun toRecord(entry: VaultEntry): SyncRecord = SyncRecord(
        syncId = entry.syncId,
        title = entry.title,
        username = entry.username,
        password = entry.password,
        url = entry.website,
        notes = entry.notes,
        category = entry.category,
        tags = entry.tags,
        customFields = entry.customFields.map { CustomFieldRecord(it.key, it.value) },
        favorite = entry.isFavorite,
        updatedAt = entry.timestamp
    )

    fun toVaultEntry(record: SyncRecord): VaultEntry = VaultEntry(
        syncId = record.syncId,
        title = record.title,
        username = record.username,
        password = record.password,
        website = record.url,
        notes = record.notes,
        category = record.category,
        tags = record.tags,
        customFields = record.customFields.map { CustomField(it.key, it.value) },
        isFavorite = record.favorite,
        timestamp = record.updatedAt
    )

    fun localState(snapshot: SyncSnapshot): LocalSyncState {
        val records = mutableListOf<SyncRecord>()
        val blocked = HashSet<String>()
        val entries = LinkedHashMap<String, VaultEntry>()
        val seen = HashSet<String>()

        for (entry in snapshot.active) {
            if (!SyncRecords.isValidSyncId(entry.syncId)) continue
            if (entry.isDecryptionFailed) {
                blocked += entry.syncId
            } else if (seen.add(entry.syncId)) {
                records += toRecord(entry)
                entries[entry.syncId] = entry
            }
        }
        for (entry in snapshot.recycleBin) {
            if (!SyncRecords.isValidSyncId(entry.syncId)) continue
            if (entry.isDecryptionFailed) {
                blocked += entry.syncId
            } else if (seen.add(entry.syncId)) {
                records += SyncRecords.tombstone(entry.syncId, entry.deletedAt ?: entry.timestamp)
                entries[entry.syncId] = entry
            }
        }
        for (tombstone in snapshot.tombstones) {
            if (SyncRecords.isValidSyncId(tombstone.syncId) && seen.add(tombstone.syncId)) {
                records += SyncRecords.tombstone(tombstone.syncId, tombstone.deletedAt)
            }
        }
        return LocalSyncState(records, blocked, entries)
    }
}
