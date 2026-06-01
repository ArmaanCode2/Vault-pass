package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.domain.models.VaultEntry
import com.example.domain.models.VaultListEntry
import com.example.repository.SettingsRepository
import com.example.repository.VaultRepository
import com.example.security.PasswordHashHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VaultViewModel(
    val vaultRepository: VaultRepository,
    val settingsRepository: SettingsRepository
) : ViewModel() {

    // Auth State
    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val allDecryptedEntries: StateFlow<List<VaultEntry>?> = vaultRepository.allEntries
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    @OptIn(FlowPreview::class)
    val dashboardEntries: StateFlow<List<VaultListEntry>?> = combine(
        vaultRepository.allRawEntities,
        allDecryptedEntries,
        _searchQuery.debounce(250)
    ) { rawEntities, decryptedEntities, query ->
        if (query.isBlank()) {
            rawEntities.map { vaultRepository.decryptLightweight(it) }
        } else {
            val keywords = query.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }
            
            // If full decryption hasn't finished yet in the background, fallback to lightweight matching
            if (decryptedEntities == null) {
                rawEntities.mapNotNull { entity ->
                    val light = vaultRepository.decryptLightweight(entity)
                    val matchLight = keywords.all { q ->
                        light.title.lowercase().contains(q) || light.username.lowercase().contains(q)
                    }
                    if (matchLight) light else null
                }
            } else {
                decryptedEntities.filter { full ->
                    keywords.all { q ->
                        full.title.lowercase().contains(q) ||
                        full.username.lowercase().contains(q) ||
                        full.website.lowercase().contains(q) ||
                        full.notes.lowercase().contains(q) ||
                        full.category.lowercase().contains(q) ||
                        full.tags.any { t -> t.lowercase().contains(q) } ||
                        full.customFields.any { f -> f.key.lowercase().contains(q) || f.value.lowercase().contains(q) }
                    }
                }.map { full ->
                    VaultListEntry(full.id, full.title, full.username, full.isFavorite)
                }
            }
        }
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // First launch properties
    val masterHash = settingsRepository.masterPasswordHash.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val masterSalt = settingsRepository.masterPasswordSalt.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    
    val isFirstLaunch: StateFlow<Boolean?> = settingsRepository.masterPasswordHash.map { it == null }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun unlockWithPassword(password: String): Boolean {
        val hash = masterHash.value ?: return false
        val salt = masterSalt.value ?: return false
        val isValid = PasswordHashHelper.verifyPassword(password, salt, hash)
        if (isValid) {
            _isUnlocked.value = true
        }
        return isValid
    }
    
    fun unlockWithBiometrics() {
        _isUnlocked.value = true
    }

    fun lock() {
        _isUnlocked.value = false
    }

    fun setupMasterPassword(password: String) {
        viewModelScope.launch {
            val salt = PasswordHashHelper.generateSalt()
            val hash = PasswordHashHelper.hashPassword(password, salt)
            settingsRepository.saveMasterPasswordData(hash, salt)
            _isUnlocked.value = true
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setImporting(importing: Boolean) {
        _isImporting.value = importing
    }

    fun addEntry(entry: VaultEntry) {
        viewModelScope.launch { vaultRepository.insertEntry(entry) }
    }

    suspend fun addEntries(entries: List<VaultEntry>) {
        vaultRepository.insertEntries(entries)
    }

    suspend fun getAllEntriesDecrypted(): List<VaultEntry> {
        return vaultRepository.allEntries.first()
    }

    suspend fun getEntryById(id: Int): VaultEntry? {
        return vaultRepository.getEntryById(id)
    }

    fun updateEntry(entry: VaultEntry) {
        viewModelScope.launch { vaultRepository.updateEntry(entry) }
    }

    fun deleteEntry(id: Int) {
        viewModelScope.launch { vaultRepository.deleteEntry(id) }
    }
}

class VaultViewModelFactory(
    private val vaultRepository: VaultRepository,
    private val settingsRepository: SettingsRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(VaultViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return VaultViewModel(vaultRepository, settingsRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
