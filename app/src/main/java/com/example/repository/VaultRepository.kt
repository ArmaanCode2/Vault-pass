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
    val allRawEntities: Flow<List<VaultEntryEntity>> = vaultDao.getAllEntries()

    val allEntries: Flow<List<VaultEntry>> = vaultDao.getAllEntries().map { entities ->
        entities.map { decryptEntity(it) }
    }.flowOn(Dispatchers.IO)

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
        vaultDao.updateEntry(encryptEntry(entry))
    }

    suspend fun deleteEntry(id: Int) = withContext(Dispatchers.IO) {
        vaultDao.deleteEntry(id)
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
            timestamp = entry.timestamp
        )
    }

    fun decryptLightweight(entity: VaultEntryEntity): VaultListEntry {
        val decryptedTitle = cryptoManager.decrypt(entity.titleEnc)
        return VaultListEntry(
            id = entity.id,
            title = if (entity.titleEnc.isNotEmpty() && decryptedTitle.isEmpty()) "[Decryption Failed]" else decryptedTitle,
            username = cryptoManager.decrypt(entity.usernameEnc),
            isFavorite = entity.isFavorite
        )
    }

    fun decryptEntity(entity: VaultEntryEntity): VaultEntry {
        val decryptedTitle = cryptoManager.decrypt(entity.titleEnc)
        
        // encrypt("") returns "", so if titleEnc is not empty but decryptedTitle is empty, decryption failed
        if (entity.titleEnc.isNotEmpty() && decryptedTitle.isEmpty()) {
            return VaultEntry(
                id = entity.id,
                title = "[Decryption Failed]",
                username = "",
                password = "",
                website = "",
                notes = "This entry could not be decrypted. The data might be corrupted or the encryption key was lost.",
                category = "Error",
                tags = emptyList(),
                customFields = emptyList(),
                isFavorite = entity.isFavorite,
                timestamp = entity.timestamp
            )
        }
        
        val tagsStr = cryptoManager.decrypt(entity.tagsEnc).takeIf { it.isNotEmpty() } ?: "[]"
        val customFieldsStr = cryptoManager.decrypt(entity.customFieldsEnc).takeIf { it.isNotEmpty() } ?: "[]"
        
        return VaultEntry(
            id = entity.id,
            title = decryptedTitle,
            username = cryptoManager.decrypt(entity.usernameEnc),
            password = cryptoManager.decrypt(entity.passwordEnc),
            website = cryptoManager.decrypt(entity.websiteEnc),
            notes = cryptoManager.decrypt(entity.notesEnc),
            category = cryptoManager.decrypt(entity.categoryEnc),
            tags = Json.decodeFromString(tagsStr),
            customFields = Json.decodeFromString(customFieldsStr),
            isFavorite = entity.isFavorite,
            timestamp = entity.timestamp
        )
    }
}
