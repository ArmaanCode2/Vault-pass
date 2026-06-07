package com.example.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vault_entries")
data class VaultEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val titleEnc: String,
    val usernameEnc: String,
    val passwordEnc: String,
    val websiteEnc: String,
    val notesEnc: String,
    val categoryEnc: String,
    val tagsEnc: String, // JSON Array of strings (encrypted)
    val customFieldsEnc: String, // JSON Array of custom fields (encrypted)
    val isFavorite: Boolean,
    val timestamp: Long,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null
)
