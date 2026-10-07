package com.example.domain.sync.diff

import com.example.domain.models.VaultEntry
import com.vaultpass.synccore.ItemOutcome
import com.vaultpass.synccore.PlanInput
import com.vaultpass.synccore.SyncChange
import com.vaultpass.synccore.SyncRecord

enum class EntrySyncCategory {
    NEW_REMOTE,
    NEW_LOCAL,
    MODIFIED,
    UNCHANGED,
    /** Changed on both devices since the last sync. */
    CONFLICT,
    /** Deleted on the other device. */
    DELETED_REMOTE,
    /** Deleted on this device. */
    DELETED_LOCAL
}

enum class FieldChangeType {
    UNCHANGED,
    MODIFIED
}

data class FieldDiff(
    val fieldName: String,
    val localValue: String,
    val remoteValue: String,
    val changeType: FieldChangeType
)

/**
 * One entry of the sync review. [syncKey] is the entry's syncId. When a legacy entry was matched
 * by title and username, [localSyncIdToReplace] is its current syncId here, renamed to [syncKey]
 * when the plan is applied (whatever the user chooses). [outcome] is what the user picked from
 * [choices]; while [needsChoice] is true and [answered] is false, the merge waits for them.
 */
data class EntryDiffItem(
    val syncKey: String,
    val change: SyncChange,
    val category: EntrySyncCategory,
    val localEntry: VaultEntry?,
    val remoteEntry: VaultEntry?,
    val localRecord: SyncRecord?,
    val remoteRecord: SyncRecord?,
    val fieldDiffs: List<FieldDiff>,
    val choices: List<ItemOutcome>,
    val outcome: ItemOutcome,
    val needsChoice: Boolean = false,
    val answered: Boolean = !needsChoice,
    val editedEntry: VaultEntry? = null,
    val localSyncIdToReplace: String? = null
) {
    /** True when this item will change at least one of the two vaults. */
    val changesSomething: Boolean get() = outcome != ItemOutcome.SKIP

    fun toPlanInput(): PlanInput = PlanInput(
        syncId = syncKey,
        change = change,
        outcome = outcome,
        local = localRecord,
        remote = remoteRecord,
        edited = editedEntry?.let { SyncRecordMapper.toRecord(it.copy(syncId = syncKey)) },
        localIdToReplace = localSyncIdToReplace
    )
}

data class SyncDiffResult(
    val newRemoteCount: Int,
    val newLocalCount: Int,
    val modifiedCount: Int,
    val unchangedCount: Int,
    val deletedLocalCount: Int = 0,
    val diffItems: List<EntryDiffItem>,
    val conflictCount: Int = 0,
    val deletedRemoteCount: Int = 0
) {
    /** How many entries the merge button offers to change. */
    val plannedChanges: Int get() = diffItems.count { it.changesSomething }

    /** True while a conflict is still waiting for the user to choose. */
    val hasUnansweredConflicts: Boolean get() = diffItems.any { it.needsChoice && !it.answered }
}
