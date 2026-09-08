package com.example.domain.sync.diff

import com.example.domain.models.VaultEntry
import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
data class EntryManifestItem(
    val syncKey: String,
    val title: String,
    val username: String,
    val updatedAt: Long,
    val fullContentHash: String
)

@Serializable
data class SyncManifest(
    val deviceId: String,
    val generatedAt: Long,
    val items: List<EntryManifestItem>
)

object SyncEntryManifest {

    fun computeSyncKey(title: String, username: String): String =
        "${title.trim().lowercase()}|${username.trim().lowercase()}"

    fun computeContentHash(
        secret: String,
        url: String,
        notes: String,
        category: String?,
        tags: List<String>
    ): String {
        val sortedTags = tags.sorted().joinToString(",")
        val raw = "${secret}|${url}|${notes}|${category ?: ""}|${sortedTags}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun computeContentHash(entry: VaultEntry): String =
        computeContentHash(
            secret = entry.password,
            url = entry.website,
            notes = entry.notes,
            category = entry.category,
            tags = entry.tags
        )

    fun fromEntry(entry: VaultEntry): EntryManifestItem =
        EntryManifestItem(
            syncKey = computeSyncKey(entry.title, entry.username),
            title = entry.title,
            username = entry.username,
            updatedAt = entry.timestamp,
            fullContentHash = computeContentHash(entry)
        )

    fun createManifest(deviceId: String, entries: List<VaultEntry>): SyncManifest {
        val activeEntries = entries.filter { !it.isDeleted && !it.isDecryptionFailed }
        val items = activeEntries.map { fromEntry(it) }
        return SyncManifest(
            deviceId = deviceId,
            generatedAt = System.currentTimeMillis(),
            items = items
        )
    }
}
