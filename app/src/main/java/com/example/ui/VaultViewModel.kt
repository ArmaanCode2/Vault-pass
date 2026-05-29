package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.domain.models.VaultEntry
import com.example.repository.SettingsRepository
import com.example.repository.VaultRepository
import com.example.security.PasswordHashHelper
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class VaultViewModel(
    val vaultRepository: VaultRepository,
    val settingsRepository: SettingsRepository
) : ViewModel() {

    // Auth State
    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val entries: StateFlow<List<VaultEntry>> = combine(
        vaultRepository.allEntries,
        _searchQuery
    ) { all, query ->
        if (query.isBlank()) {
            all
        } else {
            val keywords = query.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }
            if (keywords.isEmpty()) {
                all
            } else {
                all.filter { entry ->
                    keywords.all { q ->
                        entry.title.lowercase().contains(q) ||
                        entry.username.lowercase().contains(q) ||
                        entry.website.lowercase().contains(q) ||
                        entry.notes.lowercase().contains(q) ||
                        entry.category.lowercase().contains(q) ||
                        entry.tags.any { t -> t.lowercase().contains(q) } ||
                        entry.customFields.any { f -> f.key.lowercase().contains(q) || f.value.lowercase().contains(q) }
                    }
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    fun addEntry(entry: VaultEntry) {
        viewModelScope.launch { vaultRepository.insertEntry(entry) }
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
