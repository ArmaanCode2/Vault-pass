package com.example.domain.sync.diff

import com.example.domain.models.VaultEntry
import com.vaultpass.synccore.SyncChange
import com.vaultpass.synccore.SyncOutcomes
import com.vaultpass.synccore.SyncPlanner
import com.vaultpass.synccore.SyncRecord

object SyncDiffEngine {

    /**
     * Builds the review list from [SyncPlanner.plan]. [localEntries] maps our syncIds to local
     * entries (recycle bin included) so the review can show what we have.
     */
    fun computeDiff(
        localRecords: List<SyncRecord>,
        remoteRecords: List<SyncRecord>,
        lastSyncAt: Long,
        blockedIds: Set<String> = emptySet(),
        localEntries: Map<String, VaultEntry> = emptyMap()
    ): SyncDiffResult {
        val decisions = SyncPlanner.plan(localRecords, remoteRecords, lastSyncAt, blockedIds)

        val diffItems = decisions.map { decision ->
            val localSyncId = decision.localIdToReplace ?: decision.syncId
            val localEntry = localEntries[localSyncId]
                ?: decision.local?.takeIf { !it.deleted }?.let { SyncRecordMapper.toVaultEntry(it) }
            val remoteEntry = decision.remote?.takeIf { !it.deleted }
                ?.let { SyncRecordMapper.toVaultEntry(it).copy(syncId = decision.syncId) }
            val category = when (decision.change) {
                SyncChange.NEW_REMOTE -> EntrySyncCategory.NEW_REMOTE
                SyncChange.NEW_LOCAL -> EntrySyncCategory.NEW_LOCAL
                SyncChange.UNCHANGED -> EntrySyncCategory.UNCHANGED
                SyncChange.REMOTE_CHANGED, SyncChange.LOCAL_CHANGED -> EntrySyncCategory.MODIFIED
                SyncChange.CONFLICT -> EntrySyncCategory.CONFLICT
                SyncChange.DELETED_REMOTE -> EntrySyncCategory.DELETED_REMOTE
                SyncChange.DELETED_LOCAL -> EntrySyncCategory.DELETED_LOCAL
            }
            val needsChoice = SyncOutcomes.needsChoice(decision.change)
            val outcome = if (decision.change == SyncChange.DELETED_REMOTE && decision.remote != null) {
                SyncOutcomes.defaultForRemoteDeletion(decision.local, decision.remote)
            } else {
                SyncOutcomes.defaultFor(decision.change)
            }
            EntryDiffItem(
                syncKey = decision.syncId,
                change = decision.change,
                category = category,
                localEntry = localEntry,
                remoteEntry = remoteEntry,
                localRecord = decision.local?.let { it.copy(syncId = decision.syncId) },
                remoteRecord = decision.remote,
                fieldDiffs = buildFieldDiffs(localEntry, remoteEntry),
                choices = SyncOutcomes.choicesFor(decision.change),
                outcome = outcome,
                needsChoice = needsChoice,
                answered = !needsChoice,
                editedEntry = null,
                localSyncIdToReplace = decision.localIdToReplace
            )
        }.sortedWith(compareBy({ displayTitle(it).lowercase() }, { it.syncKey }))

        return SyncDiffResult(
            newRemoteCount = diffItems.count { it.category == EntrySyncCategory.NEW_REMOTE },
            newLocalCount = diffItems.count { it.category == EntrySyncCategory.NEW_LOCAL },
            modifiedCount = diffItems.count { it.category == EntrySyncCategory.MODIFIED },
            unchangedCount = diffItems.count { it.category == EntrySyncCategory.UNCHANGED },
            deletedLocalCount = diffItems.count { it.category == EntrySyncCategory.DELETED_LOCAL },
            diffItems = diffItems,
            conflictCount = diffItems.count { it.category == EntrySyncCategory.CONFLICT },
            deletedRemoteCount = diffItems.count { it.category == EntrySyncCategory.DELETED_REMOTE }
        )
    }

    private fun displayTitle(item: EntryDiffItem): String =
        (item.remoteEntry ?: item.localEntry)?.title ?: ""

    private fun buildFieldDiffs(local: VaultEntry?, remote: VaultEntry?): List<FieldDiff> {
        val diffs = mutableListOf<FieldDiff>()

        fun addDiff(name: String, localVal: String?, remoteVal: String?) {
            val l = localVal ?: ""
            val r = remoteVal ?: ""
            val type = if (l == r) FieldChangeType.UNCHANGED else FieldChangeType.MODIFIED
            diffs.add(FieldDiff(name, l, r, type))
        }

        addDiff("Title", local?.title, remote?.title)
        addDiff("Username", local?.username, remote?.username)
        addDiff("Password", local?.password, remote?.password)
        addDiff("Website", local?.website, remote?.website)
        addDiff("Notes", local?.notes, remote?.notes)
        addDiff("Category", local?.category, remote?.category)
        addDiff("Favorite", local?.isFavorite?.toString(), remote?.isFavorite?.toString())

        val localTags = local?.tags?.sorted()?.joinToString(", ") ?: ""
        val remoteTags = remote?.tags?.sorted()?.joinToString(", ") ?: ""
        addDiff("Tags", localTags, remoteTags)

        // Same order as the content fingerprint, so equal fields never show as changed.
        fun customFields(entry: VaultEntry?) = entry?.customFields
            ?.sortedWith(compareBy({ it.key }, { it.value }))
            ?.joinToString("; ") { "${it.key}:${it.value}" } ?: ""
        addDiff("Custom Fields", customFields(local), customFields(remote))

        return diffs
    }
}
