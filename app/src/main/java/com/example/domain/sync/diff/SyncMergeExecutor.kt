package com.example.domain.sync.diff

import com.example.repository.VaultRepository
import com.vaultpass.synccore.MergePlans
import com.vaultpass.synccore.PlanAction
import com.vaultpass.synccore.PlanItem
import com.vaultpass.synccore.SyncRecord
import com.vaultpass.synccore.SyncRecords

object SyncMergeExecutor {

    /**
     * Applies the agreed merge plan in one database transaction. The plan is validated first: a
     * device never applies a plan it did not check. Returns how many items changed this vault.
     */
    suspend fun applyPlan(
        items: List<PlanItem>,
        vaultRepository: VaultRepository,
        now: Long = System.currentTimeMillis()
    ): Result<Int> = runCatching {
        MergePlans.validate(items)
        vaultRepository.runSyncMerge {
            var applied = 0
            for (item in items) {
                // Legacy entries adopt the agreed syncId first, whatever the action is.
                item.renameFrom?.let { from ->
                    if (from != item.syncId) vaultRepository.renameSyncId(from, item.syncId)
                }
                val changed = when (item.action) {
                    PlanAction.UPSERT -> {
                        val record = item.record ?: continue
                        vaultRepository.upsertSyncedEntry(
                            SyncRecordMapper.toVaultEntry(record).copy(id = 0, syncId = item.syncId)
                        )
                    }
                    PlanAction.RECYCLE -> vaultRepository.moveToRecycleBinBySyncId(item.syncId, item.deletedAt ?: now)
                    PlanAction.RENAME_ONLY -> false
                }
                if (changed) applied++
            }
            applied
        }
    }

    /**
     * What [local] looks like once [items] are applied, without touching the database. The reviewer
     * needs it to announce the fingerprint both vaults must end up with.
     */
    fun projectRecords(local: List<SyncRecord>, items: List<PlanItem>): List<SyncRecord> {
        val byId = LinkedHashMap<String, SyncRecord>()
        local.forEach { byId[it.syncId] = it }
        for (item in items) {
            item.renameFrom?.let { from ->
                if (from != item.syncId) byId.remove(from)?.let { byId[item.syncId] = it.copy(syncId = item.syncId) }
            }
            when (item.action) {
                PlanAction.UPSERT -> item.record?.let { byId[item.syncId] = it }
                PlanAction.RECYCLE -> byId[item.syncId]?.let {
                    byId[item.syncId] = SyncRecords.tombstone(item.syncId, item.deletedAt ?: it.updatedAt)
                }
                PlanAction.RENAME_ONLY -> Unit
            }
        }
        return byId.values.toList()
    }
}
