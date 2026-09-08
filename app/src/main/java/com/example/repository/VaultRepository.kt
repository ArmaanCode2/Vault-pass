package com.example.repository

import com.example.data.VaultDao
import com.example.data.models.VaultEntryEntity
import com.example.domain.models.CustomField
import com.example.domain.models.VaultEntry
import com.example.domain.models.VaultListEntry
import com.example.security.CryptoManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

class VaultRepository(
    private val vaultDao: VaultDao,
    val cryptoManager: CryptoManager
) {
    private val repoScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cacheMutex = Mutex()

    private val decryptedCacheMap = ConcurrentHashMap<Int, Pair<VaultEntryEntity, VaultEntry>>()
    private val recycleBinCacheMap = ConcurrentHashMap<Int, Pair<VaultEntryEntity, VaultEntry>>()

    private var activeEntriesJob: kotlinx.coroutines.Job? = null
    private var recycleBinJob: kotlinx.coroutines.Job? = null

    private val _decryptedEntries = MutableStateFlow<List<VaultEntry>>(emptyList())
    val decryptedEntries: StateFlow<List<VaultEntry>> = _decryptedEntries.asStateFlow()

    private val _recycleBinEntries = MutableStateFlow<List<VaultListEntry>>(emptyList())
    val recycleBinEntries: StateFlow<List<VaultListEntry>> = _recycleBinEntries.asStateFlow()

    val allEntries: Flow<List<VaultEntry>> = _decryptedEntries.asStateFlow()

    val allRawEntities = vaultDao.getAllEntries()

    private fun startCollectors() {
        activeEntriesJob?.cancel()
        recycleBinJob?.cancel()
        activeEntriesJob = repoScope.launch {
            vaultDao.getAllEntries().collect { entities ->
                refreshActiveCache(entities)
            }
        }
        recycleBinJob = repoScope.launch {
            vaultDao.getRecycleBinEntries().collect { entities ->
                refreshRecycleBinCache(entities)
            }
        }
    }

    init {
        startCollectors()
    }

    private suspend fun refreshActiveCache(entities: List<VaultEntryEntity>) = cacheMutex.withLock {
        if (cryptoManager.getSoftwareDek() == null) {
            decryptedCacheMap.clear()
            _decryptedEntries.value = emptyList()
            return@withLock
        }

        val currentIds = entities.map { it.id }.toSet()
        decryptedCacheMap.keys.retainAll(currentIds)

        val decryptedList = entities.map { entity ->
            val cached = decryptedCacheMap[entity.id]
            if (cached != null && cached.first == entity) {
                cached.second
            } else {
                val decrypted = decryptEntity(entity)
                decryptedCacheMap[entity.id] = Pair(entity, decrypted)
                decrypted
            }
        }
        _decryptedEntries.value = decryptedList
    }

    private suspend fun refreshRecycleBinCache(entities: List<VaultEntryEntity>) = cacheMutex.withLock {
        if (cryptoManager.getSoftwareDek() == null) {
            recycleBinCacheMap.clear()
            _recycleBinEntries.value = emptyList()
            return@withLock
        }

        val currentIds = entities.map { it.id }.toSet()
        recycleBinCacheMap.keys.retainAll(currentIds)

        val list = entities.map { entity ->
            val cached = recycleBinCacheMap[entity.id]
            val decrypted = if (cached != null && cached.first == entity) {
                cached.second
            } else {
                val dec = decryptEntity(entity)
                recycleBinCacheMap[entity.id] = Pair(entity, dec)
                dec
            }
            entryToVaultListEntry(decrypted)
        }
        _recycleBinEntries.value = list
    }

    suspend fun getAllEntriesSync(): List<VaultEntry> = withContext(Dispatchers.IO) {
        val cached = _decryptedEntries.value
        if (cached.isNotEmpty()) {
            cached
        } else {
            val entities = vaultDao.getAllEntriesSync()
            if (entities.isNotEmpty() && cryptoManager.getSoftwareDek() != null) {
                refreshActiveCache(entities)
                _decryptedEntries.value
            } else {
                entities.map { decryptEntity(it) }
            }
        }
    }

    fun injectSoftwareDek(dek: ByteArray) {
        cryptoManager.injectSoftwareDek(dek)
        startCollectors()
    }

    fun getSoftwareDek(): ByteArray? {
        return cryptoManager.getSoftwareDek()
    }

    fun clearSoftwareDek() {
        activeEntriesJob?.cancel()
        recycleBinJob?.cancel()
        cryptoManager.clearSoftwareDek()
        decryptedCacheMap.clear()
        recycleBinCacheMap.clear()
        _decryptedEntries.value = emptyList()
        _recycleBinEntries.value = emptyList()
    }

    suspend fun getEntryById(id: Int): VaultEntry? = withContext(Dispatchers.IO) {
        val entity = vaultDao.getEntryById(id) ?: return@withContext null
        decryptEntity(entity)
    }

    suspend fun insertEntry(entry: VaultEntry) = withContext(Dispatchers.IO) {
        vaultDao.insertEntry(encryptEntry(entry))
        if (cryptoManager.getSoftwareDek() != null) {
            refreshActiveCache(vaultDao.getAllEntriesSync())
        }
    }

    suspend fun insertEntries(entries: List<VaultEntry>) = withContext(Dispatchers.IO) {
        vaultDao.insertEntries(entries.map { encryptEntry(it) })
        if (cryptoManager.getSoftwareDek() != null) {
            refreshActiveCache(vaultDao.getAllEntriesSync())
        }
    }

    suspend fun updateEntry(entry: VaultEntry) = withContext(Dispatchers.IO) {
        if (entry.isDecryptionFailed) {
            throw IllegalStateException("Cannot update an entry that failed decryption. Preventing data loss.")
        }
        vaultDao.updateEntry(encryptEntry(entry))
        if (cryptoManager.getSoftwareDek() != null) {
            refreshActiveCache(vaultDao.getAllEntriesSync())
        }
    }

    suspend fun updateEntries(entries: List<VaultEntry>) = withContext(Dispatchers.IO) {
        val entities = entries.map {
            if (it.isDecryptionFailed) {
                throw IllegalStateException("Cannot update an entry that failed decryption. Preventing data loss.")
            }
            encryptEntry(it)
        }
        vaultDao.updateEntries(entities)
        if (cryptoManager.getSoftwareDek() != null) {
            refreshActiveCache(vaultDao.getAllEntriesSync())
        }
    }

    suspend fun deleteEntry(id: Int) = withContext(Dispatchers.IO) {
        vaultDao.softDeleteEntry(id, System.currentTimeMillis())
        if (cryptoManager.getSoftwareDek() != null) {
            refreshActiveCache(vaultDao.getAllEntriesSync())
        }
    }

    suspend fun permanentlyDeleteEntry(id: Int) = withContext(Dispatchers.IO) {
        vaultDao.permanentlyDeleteEntry(id)
        if (cryptoManager.getSoftwareDek() != null) {
            refreshActiveCache(vaultDao.getAllEntriesSync())
        }
    }

    suspend fun restoreEntry(id: Int) = withContext(Dispatchers.IO) {
        vaultDao.restoreEntry(id)
        if (cryptoManager.getSoftwareDek() != null) {
            refreshActiveCache(vaultDao.getAllEntriesSync())
        }
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

    fun entryToVaultListEntry(entry: VaultEntry): VaultListEntry {
        val credentials = listOfNotNull(
            entry.username.takeIf { it.isNotBlank() },
            entry.password.takeIf { it.isNotBlank() }
        ) + entry.customFields.map { it.value }.filter { it.isNotBlank() }

        val preview = if (credentials.size == 1) {
            "Hidden for privacy"
        } else {
            credentials.firstOrNull() ?: ""
        }

        return VaultListEntry(
            id = entry.id,
            title = entry.title,
            username = preview,
            isFavorite = entry.isFavorite,
            isDecryptionFailed = entry.isDecryptionFailed,
            isDeleted = entry.isDeleted,
            deletedAt = entry.deletedAt
        )
    }

    fun decryptLightweight(entity: VaultEntryEntity): VaultListEntry {
        return entryToVaultListEntry(decryptEntity(entity))
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
