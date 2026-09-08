package com.example.domain.sync.diff

import com.example.domain.models.VaultEntry
import com.example.repository.VaultRepository

object SyncMergeExecutor {

    suspend fun executeMerge(
        diffItems: List<EntryDiffItem>,
        vaultRepository: VaultRepository
    ): Result<Int> = runCatching {
        val newEntries = mutableListOf<VaultEntry>()
        val updatedEntries = mutableListOf<VaultEntry>()

        for (item in diffItems) {
            if (!item.isSelectedForSync) continue

            when (item.category) {
                EntrySyncCategory.NEW_REMOTE,
                EntrySyncCategory.DELETED_LOCAL -> {
                    val target = item.editedEntry ?: item.remoteEntry ?: continue
                    val newEntry = target.copy(
                        id = 0,
                        timestamp = System.currentTimeMillis()
                    )
                    newEntries.add(newEntry)
                }
                EntrySyncCategory.MODIFIED -> {
                    val local = item.localEntry ?: continue
                    val target = item.editedEntry ?: item.remoteEntry ?: continue
                    val updatedEntry = target.copy(
                        id = local.id,
                        timestamp = System.currentTimeMillis()
                    )
                    updatedEntries.add(updatedEntry)
                }
                EntrySyncCategory.NEW_LOCAL,
                EntrySyncCategory.UNCHANGED -> {
                    // Nothing to merge to local Room database
                }
            }
        }

        if (newEntries.isNotEmpty()) {
            vaultRepository.insertEntries(newEntries)
        }
        if (updatedEntries.isNotEmpty()) {
            vaultRepository.updateEntries(updatedEntries)
        }

        newEntries.size + updatedEntries.size
    }
}
