package com.example.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Records that an entry was permanently removed, so sync can tell the other devices. */
@Entity(tableName = "sync_tombstones")
data class SyncTombstoneEntity(
    @PrimaryKey val syncId: String,
    val deletedAt: Long
)
