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
import kotlinx.serialization.json.*

enum class AuthResult {
    SUCCESS,
    INVALID_PASSWORD,
    LOCKED_OUT
}

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

    private fun getLockoutDurationMs(attempts: Int): Long {
        return when {
            attempts >= 20 -> 15 * 60 * 1000L
            attempts >= 15 -> 5 * 60 * 1000L
            attempts >= 10 -> 60 * 1000L
            attempts >= 5 -> 30 * 1000L
            else -> 0L
        }
    }

    val lockoutEndTime: StateFlow<Long> = combine(
        settingsRepository.failedAuthAttempts,
        settingsRepository.lastFailedAuthTimestamp
    ) { attempts, lastAttemptTime ->
        val duration = getLockoutDurationMs(attempts)
        if (duration > 0) lastAttemptTime + duration else 0L
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

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

    suspend fun unlockWithPassword(password: String): AuthResult {
        if (_isUnlocking.value) return AuthResult.INVALID_PASSWORD
        
        val end = lockoutEndTime.value
        if (System.currentTimeMillis() < end) {
            return AuthResult.LOCKED_OUT
        }
        
        _isUnlocking.value = true
        return withContext(Dispatchers.Default) {
            try {
                val hash = masterHash.value ?: return@withContext AuthResult.INVALID_PASSWORD
                val salt = masterSalt.value ?: return@withContext AuthResult.INVALID_PASSWORD
                val iterations = settingsRepository.masterKdfIterations.firstOrNull() ?: 100000
                val algorithm = settingsRepository.masterKdfAlgorithm.firstOrNull() ?: "PBKDF2WithHmacSHA256"

                val isValid = PasswordHashHelper.verifyPassword(password, salt, hash, iterations, algorithm)
                if (isValid) {
                    val mpWrapped = settingsRepository.getDekMpWrappedSync()
                    if (mpWrapped != null) {
                        // User is migrated, unwrap DEK
                        val kek = PasswordHashHelper.deriveMasterKey(password, salt, iterations, algorithm)
                        var dek = com.example.security.CryptoManager.unwrapDekWithKek(mpWrapped, kek)
                        
                        // KDF MIGRATION CRASH RECOVERY
                        if (dek == null) {
                            val pendingV2 = settingsRepository.getPendingDekMpWrappedV2Sync()
                            if (pendingV2 != null) {
                                dek = com.example.security.CryptoManager.unwrapDekWithKek(pendingV2, kek)
                                if (dek != null) {
                                    // Recover split-brain: Finalize phase 3 quietly
                                    settingsRepository.saveDekMpWrappedSync(pendingV2)
                                    settingsRepository.clearPendingKeysSync()
                                }
                            }
                        }

                        if (dek != null) {
                            settingsRepository.resetFailedAttempts()
                            vaultRepository.injectSoftwareDek(dek)
                            _isUnlocked.value = true
                            
                            // Check if KDF Migration is needed
                            if (iterations < com.example.security.SecurityPolicy.CURRENT_KDF_ITERATIONS) {
                                launch { performKdfMigration(password) }
                            }
                            
                            return@withContext AuthResult.SUCCESS
                        }
                        settingsRepository.incrementFailedAttempts(System.currentTimeMillis())
                        return@withContext AuthResult.INVALID_PASSWORD
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
                    settingsRepository.resetFailedAttempts()
                    return@withContext AuthResult.SUCCESS
                }
                settingsRepository.incrementFailedAttempts(System.currentTimeMillis())
                return@withContext AuthResult.INVALID_PASSWORD
            } finally {
                _isUnlocking.value = false
            }
        }
    }

    private suspend fun performKdfMigration(password: String) {
        try {
            val targetIterations = com.example.security.SecurityPolicy.CURRENT_KDF_ITERATIONS
            val targetVersion = com.example.security.SecurityPolicy.CURRENT_KDF_VERSION
            val targetAlgorithm = com.example.security.SecurityPolicy.CURRENT_KDF_ALGORITHM

            // 1. Generate new salt & KEK
            val newSaltBase64 = PasswordHashHelper.generateSalt()
            val newKek = PasswordHashHelper.deriveMasterKey(password, newSaltBase64, targetIterations, targetAlgorithm)
            
            // 2. Generate new master hash
            val newHashBase64 = PasswordHashHelper.hashPassword(password, newSaltBase64, targetIterations, targetAlgorithm)
            
            // 3. Fetch active DEK
            val activeDek = vaultRepository.getSoftwareDek() ?: return
            
            // 4. Wrap DEK with new KEK
            val newDekMpWrapped = com.example.security.CryptoManager.wrapDekWithKek(activeDek, newKek)
            
            // 5. PHASE 1: PREPARE (SharedPreferences)
            settingsRepository.savePendingDekMpWrappedV2Sync(newDekMpWrapped)
            
            // 6. PHASE 2: COMMIT (DataStore)
            settingsRepository.saveMasterPasswordAndKdfMetadata(newHashBase64, newSaltBase64, targetVersion, targetIterations, targetAlgorithm)
            
            // 7. PHASE 3: FINALIZE (SharedPreferences)
            kotlinx.coroutines.delay(500)
            settingsRepository.saveDekMpWrappedSync(newDekMpWrapped)
            settingsRepository.clearPendingKeysSync()
            
        } catch (e: Exception) {
            e.printStackTrace()
            // Migration silently aborts, DataStore rolls back, no harm done.
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
            val targetIterations = com.example.security.SecurityPolicy.CURRENT_KDF_ITERATIONS
            val targetVersion = com.example.security.SecurityPolicy.CURRENT_KDF_VERSION
            val targetAlgorithm = com.example.security.SecurityPolicy.CURRENT_KDF_ALGORITHM

            // 1. Generate salt and hash for master password
            val salt = PasswordHashHelper.generateSalt()
            val hash = PasswordHashHelper.hashPassword(password, salt, targetIterations, targetAlgorithm)
            settingsRepository.saveMasterPasswordAndKdfMetadata(hash, salt, targetVersion, targetIterations, targetAlgorithm)
            
            // 2. Generate the Software DEK
            val newDek = ByteArray(32)
            java.security.SecureRandom().nextBytes(newDek)
            
            // 3. Wrap DEK with Master Password KEK
            val mpKek = PasswordHashHelper.deriveMasterKey(password, salt, targetIterations, targetAlgorithm)
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

    suspend fun generateTxtExportPayload(): ByteArray {
        val entries = getAllEntriesDecrypted()
        val builder = StringBuilder()
        for (entry in entries) {
            builder.appendLine("Title: ${entry.title}")
            builder.appendLine()
            builder.appendLine("Username:")
            builder.appendLine(entry.username)
            builder.appendLine()
            builder.appendLine("Password:")
            builder.appendLine(entry.password)
            builder.appendLine()
            if (entry.isFavorite) {
                builder.appendLine("Favorite:")
                builder.appendLine("Yes")
                builder.appendLine()
            }
            if (entry.website.isNotEmpty()) {
                builder.appendLine("Website:")
                builder.appendLine(entry.website)
                builder.appendLine()
            }
            if (entry.notes.isNotEmpty()) {
                builder.appendLine("Notes:")
                builder.appendLine(entry.notes)
                builder.appendLine()
            }
            if (entry.category != "Personal") {
                builder.appendLine("Category:")
                builder.appendLine(entry.category)
                builder.appendLine()
            }
            if (entry.tags.isNotEmpty()) {
                builder.appendLine("Tags:")
                builder.appendLine(entry.tags.joinToString(", "))
                builder.appendLine()
            }
            if (entry.customFields.isNotEmpty()) {
                for (field in entry.customFields) {
                    builder.appendLine("${field.key}:")
                    builder.appendLine(field.value)
                    builder.appendLine()
                }
            }
            builder.appendLine("---")
            builder.appendLine()
        }
        return builder.toString().toByteArray(Charsets.UTF_8)
    }

    suspend fun generateSimplifiedJsonExportPayload(): ByteArray {
        val entries = getAllEntriesDecrypted()
        val titleCounts = mutableMapOf<String, Int>()
        
        val rootObj = buildJsonObject {
            for (entry in entries) {
                var finalTitle = entry.title
                var count = titleCounts[finalTitle] ?: 0
                if (count > 0) {
                    do {
                        count++
                        finalTitle = "${entry.title} ($count)"
                    } while (titleCounts.containsKey(finalTitle))
                }
                titleCounts[finalTitle] = count.takeIf { it > 0 } ?: 1
                
                putJsonArray(finalTitle) {
                    add(entry.username)
                    add(entry.password)
                    
                    val hasMetadata = entry.isFavorite || entry.website.isNotEmpty() || entry.notes.isNotEmpty() || entry.tags.isNotEmpty() || entry.customFields.isNotEmpty() || entry.category != "Personal"
                    
                    if (hasMetadata) {
                        addJsonObject {
                            if (entry.isFavorite) put("isFavorite", true)
                            if (entry.website.isNotEmpty()) put("website", entry.website)
                            if (entry.category != "Personal") put("category", entry.category)
                            if (entry.notes.isNotEmpty()) put("notes", entry.notes)
                            if (entry.tags.isNotEmpty()) {
                                putJsonArray("tags") { entry.tags.forEach { add(it) } }
                            }
                            if (entry.customFields.isNotEmpty()) {
                                putJsonObject("customFields") {
                                    entry.customFields.forEach { put(it.key, it.value) }
                                }
                            }
                        }
                    }
                }
            }
        }
        return rootObj.toString().toByteArray(Charsets.UTF_8)
    }

    suspend fun generateVpexExportPayload(password: String): ByteArray {
        val jsonString = String(generateSimplifiedJsonExportPayload(), Charsets.UTF_8)
        val backupData = com.example.security.CryptoManager.encryptBackup(jsonString, password)
        val base64Backup = android.util.Base64.encodeToString(backupData, android.util.Base64.NO_WRAP)
        return base64Backup.toByteArray(Charsets.UTF_8)
    }

    fun decodeImportPayload(fileContent: String): Pair<List<VaultEntry>, Int> {
        if (fileContent.trimStart().startsWith("Title:")) {
            return decodeTxtImportPayload(fileContent)
        }
        return decodeSimplifiedJsonImportPayload(fileContent)
    }

    private fun decodeTxtImportPayload(text: String): Pair<List<VaultEntry>, Int> {
        val validEntries = mutableListOf<VaultEntry>()
        var localInvalidCount = 0
        
        val blocks = text.split("---")
        for (block in blocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            
            var title = ""
            var username = ""
            var password = ""
            var isFavorite = false
            var website = ""
            var notes = ""
            var category = "Personal"
            val tags = mutableListOf<String>()
            val customFields = mutableListOf<com.example.domain.models.CustomField>()
            
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                if (line.startsWith("Title:")) {
                    title = line.substringAfter("Title:").trim()
                } else if (line == "Username:" && i + 1 < lines.size) {
                    username = lines[i+1]
                    i++
                } else if (line == "Password:" && i + 1 < lines.size) {
                    password = lines[i+1]
                    i++
                } else if (line == "Favorite:" && i + 1 < lines.size) {
                    isFavorite = (lines[i+1] == "Yes")
                    i++
                } else if (line == "Website:" && i + 1 < lines.size) {
                    website = lines[i+1]
                    i++
                } else if (line == "Notes:" && i + 1 < lines.size) {
                    notes = lines[i+1]
                    i++
                } else if (line == "Category:" && i + 1 < lines.size) {
                    category = lines[i+1]
                    i++
                } else if (line == "Tags:" && i + 1 < lines.size) {
                    lines[i+1].split(",").forEach { tags.add(it.trim()) }
                    i++
                } else if (line.endsWith(":") && i + 1 < lines.size) {
                    val key = line.dropLast(1)
                    customFields.add(com.example.domain.models.CustomField(key = key, value = lines[i+1]))
                    i++
                }
                i++
            }
            
            if (title.isNotEmpty()) {
                validEntries.add(VaultEntry(
                    title = title,
                    username = username,
                    password = password,
                    isFavorite = isFavorite,
                    website = website,
                    notes = notes,
                    category = category,
                    tags = tags,
                    customFields = customFields
                ))
            } else {
                localInvalidCount++
            }
        }
        return Pair(validEntries, localInvalidCount)
    }

    private fun decodeSimplifiedJsonImportPayload(jsonString: String): Pair<List<VaultEntry>, Int> {
        val validEntries = mutableListOf<VaultEntry>()
        var localInvalidCount = 0
        try {
            val rootObj = kotlinx.serialization.json.Json.parseToJsonElement(jsonString).jsonObject
            for ((title, element) in rootObj) {
                try {
                    val array = element.jsonArray
                    val username = array.getOrNull(0)?.jsonPrimitive?.content ?: ""
                    val password = array.getOrNull(1)?.jsonPrimitive?.content ?: ""
                    
                    var isFavorite = false
                    var website = ""
                    var notes = ""
                    var category = "Personal"
                    val tags = mutableListOf<String>()
                    val customFields = mutableListOf<com.example.domain.models.CustomField>()
                    
                    val meta = array.getOrNull(2)?.jsonObject
                    if (meta != null) {
                        isFavorite = meta["isFavorite"]?.jsonPrimitive?.booleanOrNull ?: false
                        website = meta["website"]?.jsonPrimitive?.content ?: ""
                        notes = meta["notes"]?.jsonPrimitive?.content ?: ""
                        category = meta["category"]?.jsonPrimitive?.content ?: "Personal"
                        
                        meta["tags"]?.jsonArray?.forEach { tags.add(it.jsonPrimitive.content) }
                        
                        meta["customFields"]?.jsonObject?.forEach { (key, value) ->
                            customFields.add(com.example.domain.models.CustomField(key = key, value = value.jsonPrimitive.content))
                        }
                    } else if (array.size > 2) {
                        // Legacy support for older simple lists
                        for (i in 2 until array.size) {
                            customFields.add(com.example.domain.models.CustomField(key = "Field ${i - 1}", value = array[i].jsonPrimitive.content))
                        }
                    }
                    
                    validEntries.add(VaultEntry(
                        title = title,
                        username = username,
                        password = password,
                        website = website,
                        notes = notes,
                        category = category,
                        tags = tags,
                        customFields = customFields,
                        isFavorite = isFavorite
                    ))
                } catch (e: Exception) {
                    localInvalidCount++
                }
            }
        } catch (e: Exception) {
            // Legacy format fallback: VaultExportDto array
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
            } catch (e2: Exception) {
                localInvalidCount++
            }
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
