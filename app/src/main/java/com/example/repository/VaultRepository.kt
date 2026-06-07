package com.example.repository

import com.example.data.VaultDao
import com.example.data.models.VaultEntryEntity
import com.example.domain.models.CustomField
import com.example.domain.models.VaultEntry
import com.example.security.CryptoManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flowOn
import com.example.domain.models.VaultListEntry

class VaultRepository(
    private val vaultDao: VaultDao,
    private val cryptoManager: CryptoManager
) {
    val allEntries = vaultDao.getAllEntries()
        .map { list -> list.map { decryptEntity(it) } }
        .flowOn(Dispatchers.IO)

    val recycleBinEntries = vaultDao.getRecycleBinEntries()
        .map { list -> list.map { decryptLightweight(it) } }
        .flowOn(Dispatchers.IO)

    val allRawEntities = vaultDao.getAllEntries()

    suspend fun getAllEntriesSync(): List<VaultEntry> = withContext(Dispatchers.IO) {
        vaultDao.getAllEntriesSync().map { decryptEntity(it) }
    }

    fun injectSoftwareDek(dek: ByteArray) {
        cryptoManager.injectSoftwareDek(dek)
    }

    fun getSoftwareDek(): ByteArray? {
        return cryptoManager.getSoftwareDek()
    }

    fun clearSoftwareDek() {
        cryptoManager.clearSoftwareDek()
    }

    suspend fun getEntryById(id: Int): VaultEntry? = withContext(Dispatchers.IO) {
        val entity = vaultDao.getEntryById(id) ?: return@withContext null
        decryptEntity(entity)
    }

    suspend fun insertEntry(entry: VaultEntry) = withContext(Dispatchers.IO) {
        vaultDao.insertEntry(encryptEntry(entry))
    }

    suspend fun insertEntries(entries: List<VaultEntry>) = withContext(Dispatchers.IO) {
        vaultDao.insertEntries(entries.map { encryptEntry(it) })
    }

    suspend fun updateEntry(entry: VaultEntry) = withContext(Dispatchers.IO) {
        if (entry.isDecryptionFailed) {
            throw IllegalStateException("Cannot update an entry that failed decryption. Preventing data loss.")
        }
        vaultDao.updateEntry(encryptEntry(entry))
    }

    suspend fun updateEntries(entries: List<VaultEntry>) = withContext(Dispatchers.IO) {
        val entities = entries.map {
            if (it.isDecryptionFailed) {
                throw IllegalStateException("Cannot update an entry that failed decryption. Preventing data loss.")
            }
            encryptEntry(it)
        }
        vaultDao.updateEntries(entities)
    }

    suspend fun deleteEntry(id: Int) = withContext(Dispatchers.IO) {
        vaultDao.softDeleteEntry(id, System.currentTimeMillis())
    }

    suspend fun permanentlyDeleteEntry(id: Int) = withContext(Dispatchers.IO) {
        vaultDao.permanentlyDeleteEntry(id)
    }

    suspend fun restoreEntry(id: Int) = withContext(Dispatchers.IO) {
        vaultDao.restoreEntry(id)
    }

    suspend fun cleanupRecycleBin() = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - (7L * 24 * 60 * 60 * 1000L) // 7 days
        vaultDao.deleteOldRecycleBinEntries(cutoff)
    }

    private fun encryptEntry(entry: VaultEntry): VaultEntryEntity {
        return VaultEntryEntity(
            id = entry.id,
            titleEnc = cryptoManager.encrypt(entry.title),
            usernameEnc = cryptoManager.encrypt(entry.username),
            passwordEnc = cryptoManager.encrypt(entry.password),
            websiteEnc = cryptoManager.encrypt(entry.website),
            notesEnc = cryptoManager.encrypt(entry.notes),
            categoryEnc = cryptoManager.encrypt(entry.category),
            tagsEnc = cryptoManager.encrypt(Json.encodeToString(entry.tags)),
            customFieldsEnc = cryptoManager.encrypt(Json.encodeToString(entry.customFields)),
            isFavorite = entry.isFavorite,
            timestamp = entry.timestamp,
            isDeleted = entry.isDeleted,
            deletedAt = entry.deletedAt
        )
    }

    fun decryptLightweight(entity: VaultEntryEntity): VaultListEntry {
        val decryptedTitle = cryptoManager.decrypt(entity.titleEnc)
        val hasFailed = entity.titleEnc.isNotEmpty() && decryptedTitle == null
        
        val decryptedUsername = cryptoManager.decrypt(entity.usernameEnc) ?: ""
        val decryptedPassword = cryptoManager.decrypt(entity.passwordEnc) ?: ""
        val decryptedCustomFieldsStr = cryptoManager.decrypt(entity.customFieldsEnc) ?: "[]"
        
        val customFieldsList: List<CustomField> = try {
            Json.decodeFromString(decryptedCustomFieldsStr)
        } catch(e: Exception) { emptyList() }
        
        val credentials = listOfNotNull(
            decryptedUsername.takeIf { it.isNotBlank() },
            decryptedPassword.takeIf { it.isNotBlank() }
        ) + customFieldsList.map { it.value }.filter { it.isNotBlank() }
        
        val preview = if (credentials.size == 1) {
            "Hidden for privacy"
        } else {
            credentials.firstOrNull() ?: ""
        }
        
        return VaultListEntry(
            id = entity.id,
            title = if (hasFailed) "Decryption Failed" else decryptedTitle ?: "",
            username = preview,
            isFavorite = entity.isFavorite,
            isDecryptionFailed = hasFailed,
            isDeleted = entity.isDeleted,
            deletedAt = entity.deletedAt
        )
    }

    fun decryptEntity(entity: VaultEntryEntity): VaultEntry {
        val decryptedTitle = cryptoManager.decrypt(entity.titleEnc)
        val decryptedUsername = cryptoManager.decrypt(entity.usernameEnc)
        val decryptedPassword = cryptoManager.decrypt(entity.passwordEnc)
        val decryptedWebsite = cryptoManager.decrypt(entity.websiteEnc)
        val decryptedNotes = cryptoManager.decrypt(entity.notesEnc)
        val decryptedCategory = cryptoManager.decrypt(entity.categoryEnc)
        val decryptedTagsStr = cryptoManager.decrypt(entity.tagsEnc)
        val decryptedCustomFieldsStr = cryptoManager.decrypt(entity.customFieldsEnc)
        
        val hasFailure = (entity.titleEnc.isNotEmpty() && decryptedTitle == null) ||
                         (entity.usernameEnc.isNotEmpty() && decryptedUsername == null) ||
                         (entity.passwordEnc.isNotEmpty() && decryptedPassword == null) ||
                         (entity.websiteEnc.isNotEmpty() && decryptedWebsite == null) ||
                         (entity.notesEnc.isNotEmpty() && decryptedNotes == null) ||
                         (entity.categoryEnc.isNotEmpty() && decryptedCategory == null) ||
                         (entity.tagsEnc.isNotEmpty() && decryptedTagsStr == null) ||
                         (entity.customFieldsEnc.isNotEmpty() && decryptedCustomFieldsStr == null)
        
        if (hasFailure) {
            return VaultEntry(
                id = entity.id,
                title = "Decryption Failed",
                username = "",
                password = "",
                website = "",
                notes = "This entry could not be decrypted. The data might be corrupted or the encryption key was lost. Editing this entry has been disabled to prevent permanent data loss.",
                category = "Error",
                tags = emptyList(),
                customFields = emptyList(),
                isFavorite = entity.isFavorite,
                timestamp = entity.timestamp,
                isDecryptionFailed = true,
                isDeleted = entity.isDeleted,
                deletedAt = entity.deletedAt
            )
        }
        
        val tagsStr = decryptedTagsStr.takeIf { !it.isNullOrEmpty() } ?: "[]"
        val customFieldsStr = decryptedCustomFieldsStr.takeIf { !it.isNullOrEmpty() } ?: "[]"
        
        return VaultEntry(
            id = entity.id,
            title = decryptedTitle ?: "",
            username = decryptedUsername ?: "",
            password = decryptedPassword ?: "",
            website = decryptedWebsite ?: "",
            notes = decryptedNotes ?: "",
            category = decryptedCategory ?: "",
            tags = Json.decodeFromString(tagsStr),
            customFields = Json.decodeFromString(customFieldsStr),
            isFavorite = entity.isFavorite,
            timestamp = entity.timestamp,
            isDecryptionFailed = false,
            isDeleted = entity.isDeleted,
            deletedAt = entity.deletedAt
        )
    }
}
