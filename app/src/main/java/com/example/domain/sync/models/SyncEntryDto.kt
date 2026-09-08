package com.example.domain.sync.models

import com.example.domain.models.CustomField
import com.example.domain.models.VaultEntry
import kotlinx.serialization.Serializable

@Serializable
data class SyncEntryDto(
    val id: Int = 0,
    val title: String = "",
    val username: String = "",
    val password: String? = null,
    val secret: String? = null,
    val website: String? = null,
    val url: String? = null,
    val notes: String = "",
    val category: String = "Personal",
    val tags: List<String> = emptyList(),
    val customFields: List<CustomField> = emptyList(),
    val isFavorite: Boolean = false,
    val timestamp: Long? = null,
    val updatedAt: Long? = null,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null
) {
    fun toVaultEntry(): VaultEntry {
        val resolvedPassword = password ?: secret.orEmpty()
        val resolvedWebsite = website ?: url.orEmpty()
        val resolvedTimestamp = timestamp ?: updatedAt ?: System.currentTimeMillis()
        return VaultEntry(
            id = id,
            title = title,
            username = username,
            password = resolvedPassword,
            website = resolvedWebsite,
            notes = notes,
            category = category,
            tags = tags,
            customFields = customFields,
            isFavorite = isFavorite,
            timestamp = resolvedTimestamp,
            isDeleted = isDeleted,
            deletedAt = deletedAt
        )
    }

    companion object {
        fun fromVaultEntry(entry: VaultEntry): SyncEntryDto {
            return SyncEntryDto(
                id = entry.id,
                title = entry.title,
                username = entry.username,
                password = entry.password,
                secret = entry.password,
                website = entry.website,
                url = entry.website,
                notes = entry.notes,
                category = entry.category,
                tags = entry.tags,
                customFields = entry.customFields,
                isFavorite = entry.isFavorite,
                timestamp = entry.timestamp,
                updatedAt = entry.timestamp,
                isDeleted = entry.isDeleted,
                deletedAt = entry.deletedAt
            )
        }
    }
}
