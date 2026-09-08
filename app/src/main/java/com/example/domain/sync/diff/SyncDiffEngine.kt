package com.example.domain.sync.diff

import com.example.domain.models.VaultEntry

object SyncDiffEngine {

    fun computeDiff(
        localEntries: List<VaultEntry>,
        remoteEntries: List<VaultEntry>,
        recycleBinKeys: Set<String> = emptySet()
    ): SyncDiffResult {
        val activeLocals = localEntries.filter { !it.isDeleted && !it.isDecryptionFailed }
        val activeRemotes = remoteEntries.filter { !it.isDeleted && !it.isDecryptionFailed }

        val localMap = activeLocals.associateBy { SyncEntryManifest.computeSyncKey(it.title, it.username) }
        val remoteMap = activeRemotes.associateBy { SyncEntryManifest.computeSyncKey(it.title, it.username) }

        val allKeys = (localMap.keys + remoteMap.keys).toList().sorted()
        val diffItems = mutableListOf<EntryDiffItem>()

        for (key in allKeys) {
            val local = localMap[key]
            val remote = remoteMap[key]

            if (local == null && remote != null) {
                // Entry exists only on remote device
                val isInRecycleBin = key in recycleBinKeys
                val fieldDiffs = buildFieldDiffs(null, remote)
                diffItems.add(
                    EntryDiffItem(
                        syncKey = key,
                        category = if (isInRecycleBin) EntrySyncCategory.DELETED_LOCAL else EntrySyncCategory.NEW_REMOTE,
                        localEntry = null,
                        remoteEntry = remote,
                        fieldDiffs = fieldDiffs,
                        isSelectedForSync = !isInRecycleBin,
                        editedEntry = null
                    )
                )
            } else if (local != null && remote == null) {
                // Entry exists only on local phone
                val fieldDiffs = buildFieldDiffs(local, null)
                diffItems.add(
                    EntryDiffItem(
                        syncKey = key,
                        category = EntrySyncCategory.NEW_LOCAL,
                        localEntry = local,
                        remoteEntry = null,
                        fieldDiffs = fieldDiffs,
                        isSelectedForSync = false,
                        editedEntry = null
                    )
                )
            } else if (local != null && remote != null) {
                val fieldDiffs = buildFieldDiffs(local, remote)
                val hasDifferences = fieldDiffs.any { it.changeType == FieldChangeType.MODIFIED }

                val category = if (hasDifferences) {
                    EntrySyncCategory.MODIFIED
                } else {
                    EntrySyncCategory.UNCHANGED
                }

                diffItems.add(
                    EntryDiffItem(
                        syncKey = key,
                        category = category,
                        localEntry = local,
                        remoteEntry = remote,
                        fieldDiffs = fieldDiffs,
                        isSelectedForSync = (category == EntrySyncCategory.MODIFIED),
                        editedEntry = null
                    )
                )
            }
        }

        return SyncDiffResult(
            newRemoteCount = diffItems.count { it.category == EntrySyncCategory.NEW_REMOTE },
            newLocalCount = diffItems.count { it.category == EntrySyncCategory.NEW_LOCAL },
            modifiedCount = diffItems.count { it.category == EntrySyncCategory.MODIFIED },
            unchangedCount = diffItems.count { it.category == EntrySyncCategory.UNCHANGED },
            deletedLocalCount = diffItems.count { it.category == EntrySyncCategory.DELETED_LOCAL },
            diffItems = diffItems
        )
    }

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

        val localCustom = local?.customFields?.sortedBy { it.key }?.joinToString("; ") { "${it.key}:${it.value}" } ?: ""
        val remoteCustom = remote?.customFields?.sortedBy { it.key }?.joinToString("; ") { "${it.key}:${it.value}" } ?: ""
        addDiff("Custom Fields", localCustom, remoteCustom)

        return diffs
    }
}
