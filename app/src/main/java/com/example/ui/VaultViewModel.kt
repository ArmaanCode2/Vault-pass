package com.example.ui

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.domain.models.VaultEntry
import com.example.domain.models.VaultListEntry
import com.example.repository.SettingsRepository
import com.example.repository.VaultRepository
import com.example.security.PasswordHashHelper
import com.example.domain.security.SecurityAnalyzer
import com.example.domain.security.SecurityStats
import com.example.domain.security.SecurityStatsSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString



class VaultViewModel(
    val vaultRepository: VaultRepository,
    val settingsRepository: SettingsRepository
) : ViewModel(), DefaultLifecycleObserver {

    private var autoLockJob: kotlinx.coroutines.Job? = null
    private var isPerformingSystemOperation = false

    fun setPerformingSystemOperation(isPerforming: Boolean) {
        isPerformingSystemOperation = isPerforming
    }

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onCleared() {
        super.onCleared()
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        if (isPerformingSystemOperation) {
            return
        }
        // App backgrounded (delayed by 700ms from ProcessLifecycleOwner)
        viewModelScope.launch {
            val timeout = settingsRepository.autoLockTimer.firstOrNull() ?: 60000L
            if (timeout == 0L) {
                lock()
            } else if (timeout > 0L) {
                autoLockJob?.cancel()
                autoLockJob = launch {
                    kotlinx.coroutines.delay(timeout)
                    lock()
                }
            }
        }
    }

    fun handleActivityStopped() {
        if (isPerformingSystemOperation) return
        
        // Instantaneous check for "Immediately" auto-lock
        viewModelScope.launch {
            val timeout = settingsRepository.autoLockTimer.firstOrNull() ?: 60000L
            if (timeout == 0L) {
                autoLockJob?.cancel()
                lock()
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        // App foregrounded
        autoLockJob?.cancel()
    }

    // Auth State
    private val _isUnlocking = MutableStateFlow(false)
    val isUnlocking: StateFlow<Boolean> = _isUnlocking.asStateFlow()

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()



    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val allDecryptedEntries: StateFlow<List<VaultEntry>?> = _isUnlocked
        .flatMapLatest { unlocked ->
            if (unlocked) {
                vaultRepository.allEntries
            } else {
                kotlinx.coroutines.flow.flowOf(null)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var isRecalculating = false

    val securityStats: StateFlow<SecurityStatsSummary?> = kotlinx.coroutines.flow.combine(
        settingsRepository.securityStatsSummary,
        vaultRepository.allRawEntities
    ) { cachedStats, rawEntities ->
        if (rawEntities.isEmpty()) {
            com.example.domain.security.SecurityStatsSummary.Empty 
        } else if (cachedStats != null && rawEntities.size != cachedStats.totalPasswords) {
            if (!isRecalculating) {
                isRecalculating = true
                recalculateSecurityStats()
            }
            cachedStats.copy(securityStatus = "Analyzing...")
        } else {
            cachedStats
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val detailedSecurityStats: Flow<SecurityStats?> = allDecryptedEntries
        .filterNotNull()
        .map { SecurityAnalyzer.analyze(it) }
        .flowOn(Dispatchers.Default)

    fun recalculateSecurityStats() {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val entries = vaultRepository.getAllEntriesSync()
                if (entries.isEmpty()) {
                    settingsRepository.saveSecurityStatsSummary(com.example.domain.security.SecurityStatsSummary.Empty)
                    return@launch
                }
                val stats = SecurityAnalyzer.analyze(entries)
                val summary = SecurityStatsSummary(
                    securityScore = stats.securityScore,
                    totalPasswords = stats.totalPasswords,
                    strongPasswordCount = stats.strongPasswords,
                    mediumPasswordCount = stats.mediumPasswords,
                    weakPasswordCount = stats.weakPasswords,
                    reusedPasswordCount = stats.reusedPasswords,
                    missingPasswordCount = stats.missingPasswords,
                    securityStatus = stats.securityStatus,
                    lastUpdatedTimestamp = System.currentTimeMillis()
                )
                settingsRepository.saveSecurityStatsSummary(summary)
            } finally {
                isRecalculating = false
            }
        }
    }

    val weakEntriesList: StateFlow<List<WeakEntryData>?> = combine(
        allDecryptedEntries,
        detailedSecurityStats
    ) { decrypted, stats ->
        if (decrypted == null || stats == null) return@combine null
        decrypted.filter { stats.weakEntryIds.contains(it.id) }.map { full ->
            WeakEntryData(
                entry = VaultListEntry(full.id, full.title, full.username, full.isFavorite),
                score = stats.passwordScores[full.id] ?: 0,
                reasons = stats.passwordReasons[full.id] ?: emptyList()
            )
        }.sortedBy { it.score }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val reusedEntriesGroups: StateFlow<List<ReusedGroupData>?> = combine(
        allDecryptedEntries,
        detailedSecurityStats
    ) { decrypted, stats ->
        if (decrypted == null || stats == null) return@combine null
        
        val reusedDecrypted = decrypted.filter { stats.reusedEntryIds.contains(it.id) }
        val grouped = reusedDecrypted.groupBy { it.password }
        
        grouped.map { (pwd, entries) ->
            ReusedGroupData(
                passwordScore = stats.passwordScores[entries.first().id] ?: 0,
                entries = entries.map { VaultListEntry(it.id, it.title, it.username, it.isFavorite) }
            )
        }.sortedByDescending { it.entries.size }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val missingEntriesList: StateFlow<List<VaultListEntry>?> = combine(
        allDecryptedEntries,
        detailedSecurityStats
    ) { decrypted, stats ->
        if (decrypted == null || stats == null) return@combine null
        decrypted.filter { stats.missingEntryIds.contains(it.id) }.map { full ->
            VaultListEntry(full.id, full.title, full.username, full.isFavorite)
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val allLightweightEntries: StateFlow<List<VaultListEntry>?> = _isUnlocked
        .flatMapLatest { unlocked ->
            if (unlocked) {
                vaultRepository.allRawEntities
                    .map { list -> list.map { vaultRepository.decryptLightweight(it) } }
                    .flowOn(Dispatchers.Default)
            } else {
                kotlinx.coroutines.flow.flowOf(null)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val dashboardEntries: StateFlow<List<VaultListEntry>?> = combine(allLightweightEntries.filterNotNull(), allDecryptedEntries, _searchQuery) { lightEntities, decryptedEntities, query ->
        processDashboardEntries(lightEntities, decryptedEntities, query)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun processDashboardEntries(
        lightEntities: List<VaultListEntry>,
        decryptedEntities: List<VaultEntry>?,
        query: String
    ): List<VaultListEntry> {
        val list = lightEntities

        if (query.isBlank()) {
            return list
        }
        
        val keywords = query.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }
        
        // If full decryption hasn't finished yet in the background, fallback to lightweight matching
        if (decryptedEntities == null) {
            return list.filter { light ->
                keywords.all { q ->
                    light.title.lowercase().contains(q) || light.username.lowercase().contains(q)
                }
            }
        } else {
            val fullMatchIds = decryptedEntities.filter { full ->
                keywords.all { q ->
                    full.title.lowercase().contains(q) ||
                    full.username.lowercase().contains(q) ||
                    full.website.lowercase().contains(q) ||
                    full.notes.lowercase().contains(q) ||
                    full.category.lowercase().contains(q) ||
                    full.tags.any { t -> t.lowercase().contains(q) } ||
                    full.customFields.any { cf -> cf.value.lowercase().contains(q) }
                }
            }.map { it.id }.toSet()
            return list.filter { fullMatchIds.contains(it.id) }
        }
    }

    // First launch properties
    val masterHash = settingsRepository.masterPasswordHash.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val masterSalt = settingsRepository.masterPasswordSalt.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    
    val isFirstLaunch: StateFlow<Boolean?> = settingsRepository.masterPasswordHash.map { it == null }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private suspend fun performMigration(password: String, salt: String) {
        try {
            // 1. Generate new software DEK
            val newDek = ByteArray(32)
            java.security.SecureRandom().nextBytes(newDek)

            // 2. Wrap new DEK with Master Password KEK
            val mpKek = PasswordHashHelper.deriveMasterKey(password, salt)
            val dekMpWrapped = com.example.security.CryptoManager.wrapDekWithKek(newDek, mpKek)
            
            // 3. (Removed Biometric Wrapping in Background: Requires User Authentication)

            // 4. PREPARE PHASE: Save pending wrapped DEKs
            settingsRepository.savePendingDekMpWrappedSync(dekMpWrapped)

            // 5. COMMIT PHASE (DB): Translate Data
            // Read all current entries using OLD Keystore key (CryptoManager hasn't been injected yet)
            val currentEntries = vaultRepository.allEntries.first()
            
            // Inject new DEK
            vaultRepository.injectSoftwareDek(newDek)

            // Rewrite all entries to trigger encryption with new DEK
            vaultRepository.updateEntries(currentEntries)

            // 6. FINALIZE PHASE: Commit permanent keys and clear pending
            settingsRepository.saveDekMpWrappedSync(dekMpWrapped)
            settingsRepository.clearPendingKeysSync()
            
            recalculateSecurityStats()
            _isUnlocked.value = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun unlockWithPassword(password: String): Boolean {
        if (_isUnlocking.value) return false
        _isUnlocking.value = true
        return withContext(Dispatchers.Default) {
            try {
                val hash = masterHash.value ?: return@withContext false
                val salt = masterSalt.value ?: return@withContext false
                val isValid = PasswordHashHelper.verifyPassword(password, salt, hash)
                if (isValid) {
                    val mpWrapped = settingsRepository.getDekMpWrappedSync()
                    if (mpWrapped != null) {
                        // User is migrated, unwrap DEK
                        val kek = PasswordHashHelper.deriveMasterKey(password, salt)
                        val dek = com.example.security.CryptoManager.unwrapDekWithKek(mpWrapped, kek)
                        if (dek != null) {
                            vaultRepository.injectSoftwareDek(dek)
                            _isUnlocked.value = true
                            return@withContext true
                        }
                        return@withContext false
                    } else {
                        val pendingMpWrapped = settingsRepository.getPendingDekMpWrappedSync()
                        if (pendingMpWrapped != null) {
                            // MIGRATION CRASH RECOVERY
                            recoverMigration(password, salt, pendingMpWrapped)
                        } else {
                            // User has NOT migrated, trigger migration
                            performMigration(password, salt)
                        }
                    }
                    return@withContext true
                }
                return@withContext false
            } finally {
                _isUnlocking.value = false
            }
        }
    }

    private suspend fun recoverMigration(password: String, salt: String, pendingMpWrapped: String) {
        val kek = PasswordHashHelper.deriveMasterKey(password, salt)
        val dek = com.example.security.CryptoManager.unwrapDekWithKek(pendingMpWrapped, kek)
        if (dek != null) {
            vaultRepository.injectSoftwareDek(dek)
            
            val testEntry = vaultRepository.allRawEntities.first().firstOrNull()
            var dbUpdateSucceeded = true
            if (testEntry != null) {
                val decrypted = vaultRepository.decryptEntity(testEntry)
                if (decrypted.isDecryptionFailed) {
                    dbUpdateSucceeded = false
                }
            }

            if (dbUpdateSucceeded) {
                // DB was updated successfully. Finalize migration.
                settingsRepository.saveDekMpWrappedSync(pendingMpWrapped)
                val pendingBioWrapped = settingsRepository.getPendingDekBioWrappedSync()
                if (pendingBioWrapped != null) {
                    settingsRepository.saveDekBioWrappedSync(pendingBioWrapped)
                }
                settingsRepository.clearPendingKeysSync()
                _isUnlocked.value = true
            } else {
                // DB was NOT updated (transaction rolled back). 
                // Clear pending keys and start migration again.
                settingsRepository.clearPendingKeysSync()
                vaultRepository.clearSoftwareDek()
                performMigration(password, salt)
            }
        } else {
            // If we can't unwrap pending DEK, clear and restart
            settingsRepository.clearPendingKeysSync()
            performMigration(password, salt)
        }
    }
    
    suspend fun unlockWithBiometrics(challenge: ByteArray, signature: ByteArray): Boolean {
        if (_isUnlocking.value) return false
        _isUnlocking.value = true
        return withContext(Dispatchers.Default) {
            try {
                val isValid = com.example.security.BiometricCryptoHelper.verifySignature(challenge, signature)
                if (isValid) {
                    _isUnlocked.value = true
                }
                return@withContext isValid
            } finally {
                _isUnlocking.value = false
            }
        }
    }

    suspend fun unlockWithBiometrics(dek: ByteArray): Boolean {
        if (_isUnlocking.value) return false
        _isUnlocking.value = true
        return withContext(Dispatchers.Default) {
            try {
                vaultRepository.injectSoftwareDek(dek)
                _isUnlocked.value = true
                return@withContext true
            } finally {
                _isUnlocking.value = false
            }
        }
    }

    suspend fun wrapDekForBiometrics() {
        val softwareDek = vaultRepository.getSoftwareDek() ?: return
        val cipher = com.example.security.BiometricCryptoHelper.getEncryptCipherForBiometric()
        if (cipher != null) {
            val iv = cipher.iv
            val encryptedData = cipher.doFinal(softwareDek)
            val combined = iv + encryptedData
            val dekBioWrapped = android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP)
            settingsRepository.saveDekBioWrappedSync(dekBioWrapped)
        }
    }

    private var clipboardJob: kotlinx.coroutines.Job? = null

    fun copyToClipboard(context: android.content.Context, label: String, text: String) {
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        android.widget.Toast.makeText(context, "$label copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()

        clipboardJob?.cancel()
        val appContext = context.applicationContext
        clipboardJob = viewModelScope.launch {
            val delayMs = settingsRepository.clipboardClearTimer.first()
            if (delayMs > 0) {
                kotlinx.coroutines.delay(delayMs)
                val currentClip = clipboard.primaryClip
                if (currentClip != null && currentClip.itemCount > 0) {
                    val currentText = currentClip.getItemAt(0).text?.toString()
                    if (currentText == text) {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            clipboard.clearPrimaryClip()
                        } else {
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
                        }
                    }
                }
            }
        }
    }

    fun lock() {
        _isUnlocked.value = false
        vaultRepository.clearSoftwareDek()
        autoLockJob?.cancel()
    }

    fun setupMasterPassword(password: String) {
        viewModelScope.launch {
            // 1. Generate salt and hash for master password
            val salt = PasswordHashHelper.generateSalt()
            val hash = PasswordHashHelper.hashPassword(password, salt)
            settingsRepository.saveMasterPasswordData(hash, salt)
            
            // 2. Generate the Software DEK
            val newDek = ByteArray(32)
            java.security.SecureRandom().nextBytes(newDek)
            
            // 3. Wrap DEK with Master Password KEK
            val mpKek = PasswordHashHelper.deriveMasterKey(password, salt)
            val dekMpWrapped = com.example.security.CryptoManager.wrapDekWithKek(newDek, mpKek)
            
            // 4. Save the wrapped DEK
            settingsRepository.saveDekMpWrappedSync(dekMpWrapped)
            
            // 5. Inject the DEK so it's ready for immediate use
            vaultRepository.injectSoftwareDek(newDek)
            
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
        viewModelScope.launch { 
            vaultRepository.insertEntry(entry)
            recalculateSecurityStats()
        }
    }

    suspend fun addEntries(entries: List<VaultEntry>) {
        vaultRepository.insertEntries(entries)
        recalculateSecurityStats()
    }

    suspend fun generateExportPayload(password: String): ByteArray {
        val entries = getAllEntriesDecrypted()
        
        // Ensure unique titles since they are visually important, even in the new array format
        val titleCounts = mutableMapOf<String, Int>()
        val exportList = entries.map { entry ->
            var finalTitle = entry.title
            var count = titleCounts[finalTitle] ?: 0
            if (count > 0) {
                do {
                    count++
                    finalTitle = "${entry.title} ($count)"
                } while (titleCounts.containsKey(finalTitle))
            }
            titleCounts[finalTitle] = count.takeIf { it > 0 } ?: 1
            
            VaultExportDto(
                title = finalTitle,
                username = entry.username,
                password = entry.password,
                customFields = entry.customFields.map { it.value },
                isFavorite = entry.isFavorite
            )
        }
        
        val jsonString = kotlinx.serialization.json.Json.encodeToString(exportList)
        val backupData = com.example.security.CryptoManager.encryptBackup(jsonString, password)
        val base64Backup = android.util.Base64.encodeToString(backupData, android.util.Base64.NO_WRAP)
        return base64Backup.toByteArray(Charsets.UTF_8)
    }

    fun decodeImportPayload(jsonString: String): Pair<List<VaultEntry>, Int> {
        val validEntries = mutableListOf<VaultEntry>()
        var localInvalidCount = 0

        // 1. Try New Format
        try {
            val list = kotlinx.serialization.json.Json.decodeFromString<List<VaultExportDto>>(jsonString)
            for (dto in list) {
                val customFields = dto.customFields.mapIndexed { index, value ->
                    com.example.domain.models.CustomField(key = "Field ${index + 1}", value = value)
                }
                validEntries.add(VaultEntry(
                    title = dto.title,
                    username = dto.username,
                    password = dto.password,
                    customFields = customFields,
                    isFavorite = dto.isFavorite
                ))
            }
            return Pair(validEntries, localInvalidCount)
        } catch (e: Exception) {
            // Fallback to legacy map format
        }

        // 2. Try Legacy Format
        try {
            val map = kotlinx.serialization.json.Json.decodeFromString<Map<String, List<String>>>(jsonString)
            for ((key, values) in map) {
                if (values.isEmpty()) {
                    localInvalidCount++
                    continue
                }
                val username = values.getOrNull(0) ?: ""
                val password = values.getOrNull(1) ?: ""
                val customFields = mutableListOf<com.example.domain.models.CustomField>()
                if (values.size > 2) {
                    for (i in 2 until values.size) {
                        customFields.add(com.example.domain.models.CustomField(key = "Field ${i - 1}", value = values[i]))
                    }
                }
                validEntries.add(VaultEntry(
                    title = key,
                    username = username,
                    password = password,
                    customFields = customFields
                ))
            }
        } catch (e: Exception) {
            localInvalidCount++ // Total failure
        }
        
        return Pair(validEntries, localInvalidCount)
    }

    suspend fun getAllEntriesDecrypted(): List<VaultEntry> {
        return vaultRepository.allEntries.first()
    }

    suspend fun getEntryById(id: Int): VaultEntry? {
        return vaultRepository.getEntryById(id)
    }

    fun updateEntry(entry: VaultEntry) {
        viewModelScope.launch { 
            vaultRepository.updateEntry(entry)
            recalculateSecurityStats()
        }
    }

    fun deleteEntry(id: Int) {
        viewModelScope.launch { 
            vaultRepository.deleteEntry(id)
            recalculateSecurityStats()
        }
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

data class WeakEntryData(
    val entry: VaultListEntry,
    val score: Int,
    val reasons: List<String>
)

data class ReusedGroupData(
    val passwordScore: Int,
    val entries: List<VaultListEntry>
)

@kotlinx.serialization.Serializable
data class VaultExportDto(
    val title: String,
    val username: String,
    val password: String,
    val customFields: List<String> = emptyList(),
    val isFavorite: Boolean = false
)
