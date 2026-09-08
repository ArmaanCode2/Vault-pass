package com.example.domain.sync.diff

import com.example.domain.models.VaultEntry

enum class EntrySyncCategory {
    NEW_REMOTE,
    NEW_LOCAL,
    MODIFIED,
    UNCHANGED,
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

data class EntryDiffItem(
    val syncKey: String,
    val category: EntrySyncCategory,
    val localEntry: VaultEntry?,
    val remoteEntry: VaultEntry?,
    val fieldDiffs: List<FieldDiff>,
    var isSelectedForSync: Boolean = true,
    var editedEntry: VaultEntry? = null
)

data class SyncDiffResult(
    val newRemoteCount: Int,
    val newLocalCount: Int,
    val modifiedCount: Int,
    val unchangedCount: Int,
    val deletedLocalCount: Int = 0,
    val diffItems: List<EntryDiffItem>
)
