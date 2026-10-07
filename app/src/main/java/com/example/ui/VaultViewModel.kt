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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

enum class AuthResult {
    SUCCESS,
    INVALID_PASSWORD,
    LOCKED_OUT,
    /** Right password, but the old vault couldn't be upgraded. Nothing was changed; not a failed attempt. */
    MIGRATION_FAILED,
    /** Right password, but the vault was locked again while it opened (e.g. the app went to the background). It stays locked; not a failed attempt. */
    INTERRUPTED
}

enum class SetupResult {
    CREATED,
    /** A vault (wrapped key or entries) already exists: nothing was overwritten (F17). */
    VAULT_EXISTS,
    /** Another setup is still running (double submit). */
    IN_PROGRESS,
    /** The vault key couldn't be saved. Nothing is kept (Setup shows again); not unlocked. */
    FAILED
}

/** First screen: Setup only when there is provably no vault; a read error never looks like "no vault". */
enum class VaultLaunchState {
    SETUP,
    LOCK,
    /** Settings can't be read, or master_hash is missing while vault data exists. */
    UNREADABLE;

    companion object {
        fun decide(hash: com.example.repository.MasterHashRead, vaultDataExists: Boolean): VaultLaunchState = when (hash) {
            com.example.repository.MasterHashRead.PRESENT -> LOCK
            com.example.repository.MasterHashRead.READ_ERROR -> UNREADABLE
            com.example.repository.MasterHashRead.ABSENT -> if (vaultDataExists) UNREADABLE else SETUP
        }
    }
}

/** Shortest password a .vpex export accepts (the export dialog asks for the same). */
const val EXPORT_PASSWORD_MIN_LENGTH = 8

/** An export file and how many entries it left out because they can't be decrypted. */
class ExportPayload(val bytes: ByteArray, val skippedEntries: Int)

private sealed class MigrationOutcome {
    /** Unlocked; [unreadableEntries] rows were left untouched because they couldn't be read. */
    class Unlocked(val unreadableEntries: Int) : MigrationOutcome()
    object Failed : MigrationOutcome()
    /** Upgraded (leaving [unreadableEntries] rows untouched), but a lock ran while it did: the vault stays locked. */
    class Interrupted(val unreadableEntries: Int) : MigrationOutcome()
}

class VaultViewModel(
    val vaultRepository: VaultRepository,
    val settingsRepository: SettingsRepository,
    injectedSessionManager: com.example.security.VaultSessionManager? = null,
    private val deviceClock: com.example.security.DeviceClock = settingsRepository.deviceClock,
    private val legacyVaultKeys: com.example.security.LegacyVaultKeys = com.example.security.LegacyVaultKeys.forDevice(settingsRepository),
    /** Saves the wrapped DEK of a new vault; true only when it is on disk. Injectable for tests. */
    private val saveNewVaultKey: (String) -> Boolean = { settingsRepository.saveDekMpWrappedSync(it) }
) : ViewModel() {

    val sessionManager: com.example.security.VaultSessionManager = injectedSessionManager
        ?: com.example.security.VaultSessionManager.getInstance(settingsRepository, vaultRepository.cryptoManager, vaultRepository)

    fun setPerformingSystemOperation(isPerforming: Boolean) {
        sessionManager.setPerformingSystemOperation(isPerforming)
    }

    fun handleActivityStopped() {
        sessionManager.handleActivityStopped()
    }

    init {
        // Does nothing while locked (it needs the vault key to tell readable rows from unreadable ones).
        viewModelScope.launch { vaultRepository.cleanupRecycleBin() }
    }

    // Auth State
    private val _isUnlocking = MutableStateFlow(false)
    val isUnlocking: StateFlow<Boolean> = _isUnlocking.asStateFlow()
    private val unlockCallsLock = Any()
    private var unlockCalls = 0

    /** [isUnlocking] stays true while any unlock call of this view model runs (they wait on [com.example.security.VaultUnlockLock]). */
    private fun countUnlockCall(delta: Int) = synchronized(unlockCallsLock) {
        unlockCalls += delta
        _isUnlocking.value = unlockCalls > 0
    }

    /** The vault is open in this process: unlocked and its key in memory. */
    private fun isVaultOpen(): Boolean {
        if (!sessionManager.isUnlocked.value) return false
        val dek = vaultRepository.getSoftwareDek() ?: return false
        java.util.Arrays.fill(dek, 0.toByte())
        return true
    }

    /** Stored lockout data for the lock screen countdown (see [lockoutRemainingMs]). */
    val lockoutState: StateFlow<com.example.security.AuthLockout.State> = settingsRepository.authLockoutState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.example.security.AuthLockout.State.NONE)

    /** Lockout time left for [state] right now. Same function the unlock check uses. */
    fun lockoutRemainingMs(state: com.example.security.AuthLockout.State): Long =
        com.example.security.AuthLockout.remainingMs(state, deviceClock.elapsedRealtime(), deviceClock.bootCount())

    private suspend fun recordFailedAttempt() {
        settingsRepository.incrementFailedAttempts(deviceClock.elapsedRealtime(), deviceClock.bootCount())
    }

    val isUnlocked: StateFlow<Boolean> = sessionManager.isUnlocked

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val allDecryptedEntries: StateFlow<List<VaultEntry>?> = isUnlocked
        .flatMapLatest { unlocked ->
            if (unlocked) {
                vaultRepository.decryptedEntries
            } else {
                kotlinx.coroutines.flow.flowOf(null)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val isRecalculating = java.util.concurrent.atomic.AtomicBoolean(false)

    val securityStats: StateFlow<SecurityStatsSummary?> = kotlinx.coroutines.flow.combine(
        settingsRepository.securityStatsSummary,
        vaultRepository.allRawEntities
    ) { cachedStats, rawEntities ->
        if (rawEntities.isEmpty()) {
            com.example.domain.security.SecurityStatsSummary.Empty 
        } else if (cachedStats != null && rawEntities.size != cachedStats.totalPasswords) {
            if (isRecalculating.compareAndSet(false, true)) {
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

    /** Running [recalculateSecurityStats] jobs; each ends with a DataStore write. */
    private val securityStatsJobs: MutableSet<kotlinx.coroutines.Job> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Tests: waits until no background security-stats run (a DataStore write) is in progress. */
    @androidx.annotation.VisibleForTesting
    internal suspend fun awaitSecurityStatsRecalculation() {
        while (true) {
            // A snapshot via toArray: toList() can throw when a job finishes between its size check and read.
            val running = ArrayList(securityStatsJobs)
            if (running.isEmpty()) return
            running.forEach { it.join() }
        }
    }

    fun recalculateSecurityStats() {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                // While locked there is nothing to analyze: keep the last stats instead of saving empty ones.
                val crypto = vaultRepository.cryptoManager
                val start = crypto.keyState()
                if (!start.hasKey) return@launch
                val entries = vaultRepository.getAllEntriesSync()
                val summary = if (entries.isEmpty()) {
                    com.example.domain.security.SecurityStatsSummary.Empty
                } else {
                    val stats = SecurityAnalyzer.analyze(entries)
                    SecurityStatsSummary(
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
                }
                // The vault may have locked while this ran: what was read then is not the vault.
                if (crypto.keyState() != start) return@launch
                settingsRepository.saveSecurityStatsSummary(summary)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (com.example.BuildConfig.DEBUG) e.printStackTrace()
            } finally {
                isRecalculating.set(false)
            }
        }.also { job ->
            securityStatsJobs += job
            job.invokeOnCompletion { securityStatsJobs -= job }
        }
    }

    val weakEntriesList: StateFlow<List<WeakEntryData>?> = combine(
        allDecryptedEntries,
        detailedSecurityStats
    ) { decrypted, stats ->
        if (decrypted == null || stats == null) return@combine null
        decrypted.filter { stats.weakEntryIds.contains(it.id) }.map { full ->
            WeakEntryData(
                entry = vaultRepository.entryToVaultListEntry(full),
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
                entries = entries.map { vaultRepository.entryToVaultListEntry(it) }
            )
        }.sortedByDescending { it.entries.size }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val missingEntriesList: StateFlow<List<VaultListEntry>?> = combine(
        allDecryptedEntries,
        detailedSecurityStats
    ) { decrypted, stats ->
        if (decrypted == null || stats == null) return@combine null
        decrypted.filter { stats.missingEntryIds.contains(it.id) }.map { full ->
            vaultRepository.entryToVaultListEntry(full)
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val dashboardEntries: StateFlow<List<VaultListEntry>?> = combine(
        allDecryptedEntries,
        _searchQuery
    ) { decrypted, query ->
        if (decrypted == null) return@combine null
        val filtered = if (query.isBlank()) {
            decrypted
        } else {
            val keywords = query.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }
            decrypted.filter { entry ->
                keywords.all { q ->
                    entry.title.lowercase().contains(q) ||
                    entry.username.lowercase().contains(q) ||
                    entry.website.lowercase().contains(q) ||
                    entry.notes.lowercase().contains(q) ||
                    entry.category.lowercase().contains(q) ||
                    entry.tags.any { t -> t.lowercase().contains(q) } ||
                    entry.customFields.any { cf -> cf.value.lowercase().contains(q) }
                }
            }
        }
        filtered.map { vaultRepository.entryToVaultListEntry(it) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val recycleBinEntries: StateFlow<List<VaultListEntry>?> = isUnlocked
        .flatMapLatest { unlocked ->
            if (unlocked) {
                vaultRepository.recycleBinEntries
            } else {
                kotlinx.coroutines.flow.flowOf(null)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // First launch properties
    val masterHash = settingsRepository.masterPasswordHash.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val masterSalt = settingsRepository.masterPasswordSalt.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    
    private val launchCheck = MutableStateFlow(0)

    /** Setup, Lock, or "vault can't be read" (F17: a read error never shows Setup). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val launchState: StateFlow<VaultLaunchState?> = launchCheck
        .flatMapLatest {
            settingsRepository.masterHashRead
                .distinctUntilChanged()
                .mapLatest { read ->
                    val exists = read == com.example.repository.MasterHashRead.ABSENT && vaultDataExists()
                    VaultLaunchState.decide(read, exists)
                }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val isFirstLaunch: StateFlow<Boolean?> = launchState
        .map { state -> state?.let { it == VaultLaunchState.SETUP } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Reads the launch state again (after a read error). */
    fun retryLaunchCheck() {
        launchCheck.value++
    }

    /** A wrapped vault key or any entry row exists. Fails closed: an error counts as "exists". */
    private suspend fun vaultDataExists(): Boolean = try {
        settingsRepository.hasStoredVaultKeySync() || vaultRepository.hasAnyEntryRows()
    } catch (e: Exception) {
        if (com.example.BuildConfig.DEBUG) e.printStackTrace()
        true
    }

    private val _migrationUnreadableCount = MutableStateFlow<Int?>(null)

    /** Set once after a legacy-vault upgrade that left some entries untouched (they couldn't be read). */
    val migrationUnreadableCount: StateFlow<Int?> = _migrationUnreadableCount.asStateFlow()

    fun dismissMigrationNotice() {
        _migrationUnreadableCount.value = null
    }

    /**
     * Upgrades a vault from before the software DEK (F18), using the KDF parameters unlock used.
     * 1. Reads every row (recycle bin included) and decrypts it with the legacy keys. Nothing is
     *    written; if no row with content can be read, a legacy key exists but can't be loaded, or a
     *    decryption fails for any reason but a wrong key, it stops here (Failed, nothing changed).
     * 2. New DEK, wrapped with the KEK; readable rows re-encrypted in memory and checked.
     * 3. Saves the pending wrapped DEK (crash recovery, see [recoverMigration]).
     * 4. Writes the migrated rows in one transaction. Unreadable rows stay byte-for-byte as they
     *    are. On a rollback the pending key is removed again: back to the state before step 3.
     * 5. Finalizes the wrapped DEK, upgrades a pre-domain-separation auth hash, unlocks.
     * Legacy keys and legacy prefs are never deleted.
     */
    private suspend fun performMigration(password: String, salt: String, iterations: Int, algorithm: String, lockEpoch: Long): MigrationOutcome {
        // 1. Read and decrypt everything first.
        // A legacy key that exists but can't be loaded, or any decryption error other than a wrong
        // key, ends here (Failed, nothing written): migrating only what the other key opens would
        // switch the vault to the new key and leave the rest behind.
        val decrypted = try {
            val rows = vaultRepository.getAllRawEntitiesSync()
            // Nothing encrypted: no key needed, so an unusable legacy key can't block an empty vault.
            val keys = if (rows.any { com.example.security.LegacyVaultMigration.hasContent(it) }) legacyVaultKeys.load() else emptyList()
            com.example.security.LegacyVaultMigration.decryptAll(rows, keys)
        } catch (e: Exception) {
            if (com.example.BuildConfig.DEBUG) e.printStackTrace()
            return MigrationOutcome.Failed
        }
        if (!decrypted.canMigrate) return MigrationOutcome.Failed

        val newDek = ByteArray(32)
        var mpKek: ByteArray? = null
        var checkDek: ByteArray? = null
        try {
            // 2. New DEK, wrapped and re-encrypted rows, all in memory.
            java.security.SecureRandom().nextBytes(newDek)
            mpKek = PasswordHashHelper.deriveMasterKey(password, salt, iterations, algorithm)
            val dekMpWrapped = com.example.security.CryptoManager.wrapDekWithKek(newDek, mpKek)
            checkDek = com.example.security.CryptoManager.unwrapDekWithKek(dekMpWrapped, mpKek)
            if (checkDek == null || !checkDek.contentEquals(newDek)) return MigrationOutcome.Failed
            val migratedRows = com.example.security.LegacyVaultMigration.reencrypt(decrypted.readable, newDek)

            // 3. PREPARE: pending wrapped DEK, on disk before the database changes.
            if (!settingsRepository.savePendingDekMpWrappedSync(dekMpWrapped)) {
                settingsRepository.clearPendingKeysSync()
                return MigrationOutcome.Failed
            }

            // 4. COMMIT: every migrated row in one transaction.
            val committed = try {
                vaultRepository.replaceMigratedRows(decrypted.readable.map { it.original }, migratedRows)
                true
            } catch (e: Exception) {
                if (com.example.BuildConfig.DEBUG) e.printStackTrace()
                false
            }
            if (!committed) {
                // Make sure no row is under the new DEK before dropping its pending key.
                val check = try {
                    com.example.security.LegacyVaultMigration.checkWithDek(vaultRepository.getAllRawEntitiesSync(), newDek)
                } catch (e: Exception) {
                    null // Unknown: keep the pending key, the next unlock sorts it out.
                }
                if (check == null || check.anyContentReadable) return MigrationOutcome.Failed
                settingsRepository.clearPendingKeysSync()
                return MigrationOutcome.Failed
            }

            // 5. FINALIZE. If saving fails the pending key stays, and the next unlock finishes it.
            if (settingsRepository.saveDekMpWrappedSync(dekMpWrapped)) {
                settingsRepository.clearPendingKeysSync()
            }
            upgradeAuthHash(password, salt, iterations, algorithm)
            // The upgrade is stored either way; a lock that ran meanwhile keeps the vault locked.
            if (!sessionManager.openVault(newDek, lockEpoch)) return MigrationOutcome.Interrupted(decrypted.unreadable.size)
            vaultOpenedHook?.invoke()
            recalculateSecurityStats()
            return MigrationOutcome.Unlocked(decrypted.unreadable.size)
        } catch (e: Exception) {
            if (com.example.BuildConfig.DEBUG) e.printStackTrace()
            return MigrationOutcome.Failed
        } finally {
            java.util.Arrays.fill(newDek, 0.toByte())
            mpKek?.let { java.util.Arrays.fill(it, 0.toByte()) }
            checkDek?.let { java.util.Arrays.fill(it, 0.toByte()) }
        }
    }

    /**
     * Pre-2026-06 builds stored the raw PBKDF2 output as master_hash, from which the KEK can be
     * derived. After an upgrade, replace it with the domain-separated auth hash (same salt and KDF).
     * Failure is harmless: the old hash still verifies.
     */
    private suspend fun upgradeAuthHash(password: String, salt: String, iterations: Int, algorithm: String) {
        try {
            val authHash = PasswordHashHelper.hashPassword(password, salt, iterations, algorithm)
            if (settingsRepository.masterPasswordHash.firstOrNull() != authHash) {
                settingsRepository.saveMasterPasswordAndKdfMetadata(
                    authHash,
                    salt,
                    settingsRepository.masterKdfVersion.firstOrNull() ?: 1,
                    iterations,
                    algorithm
                )
            }
        } catch (e: Exception) {
            if (com.example.BuildConfig.DEBUG) e.printStackTrace()
        }
    }

    /**
     * Checks [password] and opens the vault, upgrading a legacy vault on the way. Runs under the
     * process-wide [com.example.security.VaultUnlockLock]: a second call (another screen, a double
     * submit) waits, and if the vault is open by then it only checks the password.
     */
    suspend fun unlockWithPassword(password: String): AuthResult {
        // Read before anything else: a lock from here on (even while this waits) keeps the vault locked.
        val lockEpoch = sessionManager.currentLockEpoch()
        countUnlockCall(1)
        try {
            return com.example.security.VaultUnlockLock.mutex.withLock { unlockWithPasswordLocked(password, lockEpoch) }
        } finally {
            countUnlockCall(-1)
        }
    }

    private suspend fun unlockWithPasswordLocked(password: String, lockEpoch: Long): AuthResult {
        return withContext(Dispatchers.Default) {
            // Computed fresh from storage, never from the lock screen's StateFlow.
            val lockout = settingsRepository.authLockoutState.first()
            if (lockoutRemainingMs(lockout) > 0) {
                return@withContext AuthResult.LOCKED_OUT
            }

            // One read of all four values, under DataStore's write lock. Separate unlocked reads can
            // race a background write (e.g. the security stats saved after another unlock) and see
            // the file mid-replace as empty, which turned a correct password into INVALID_PASSWORD.
            // A read error falls back to the flows, as before.
            val stored = try {
                settingsRepository.readStoredPreferencesOrThrow()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (com.example.BuildConfig.DEBUG) e.printStackTrace()
                null
            }
            val hash = (if (stored != null) stored[SettingsRepository.MASTER_HASH] else settingsRepository.masterPasswordHash.firstOrNull())
                ?: masterHash.value ?: return@withContext AuthResult.INVALID_PASSWORD
            val salt = (if (stored != null) stored[SettingsRepository.MASTER_SALT] else settingsRepository.masterPasswordSalt.firstOrNull())
                ?: masterSalt.value ?: return@withContext AuthResult.INVALID_PASSWORD
            val iterations = (if (stored != null) stored[SettingsRepository.MASTER_KDF_ITERATIONS] else settingsRepository.masterKdfIterations.firstOrNull())
                ?: 100000
            val algorithm = (if (stored != null) stored[SettingsRepository.MASTER_KDF_ALGORITHM] else settingsRepository.masterKdfAlgorithm.firstOrNull())
                ?: com.example.security.SecurityPolicy.CURRENT_KDF_ALGORITHM

            val isValid = PasswordHashHelper.verifyPassword(password, salt, hash, iterations, algorithm)
            if (isValid && isVaultOpen()) {
                // Another call opened (or upgraded) the vault while this one waited: nothing left to do.
                settingsRepository.resetFailedAttempts()
                return@withContext AuthResult.SUCCESS
            }
            if (isValid) {
                val mpWrapped = settingsRepository.getDekMpWrappedSync()
                if (mpWrapped != null) {
                    // User is migrated, unwrap DEK
                    val kek = PasswordHashHelper.deriveMasterKey(password, salt, iterations, algorithm)
                    var dek = com.example.security.CryptoManager.unwrapDekWithKek(mpWrapped, kek)
                    var legacyMigrated = false

                    // If domain-separated KEK fails, fallback to legacy KEK derivation
                    if (dek == null) {
                        val legacyKek = PasswordHashHelper.deriveLegacyMasterKey(password, salt, iterations, algorithm)
                        try {
                            dek = com.example.security.CryptoManager.unwrapDekWithKek(mpWrapped, legacyKek)
                            if (dek != null) {
                                legacyMigrated = true
                            }
                        } finally {
                            java.util.Arrays.fill(legacyKek, 0.toByte())
                        }
                    }
                    
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
                        if (legacyMigrated) {
                            // Transparently upgrade legacy vault: re-wrap with new domain-separated KEK & save AuthHash
                            val newWrapped = com.example.security.CryptoManager.wrapDekWithKek(dek, kek)
                            settingsRepository.saveDekMpWrappedSync(newWrapped)
                            val newAuthHash = PasswordHashHelper.hashPassword(password, salt, iterations, algorithm)
                            settingsRepository.saveMasterPasswordAndKdfMetadata(
                                newAuthHash,
                                salt,
                                settingsRepository.masterKdfVersion.firstOrNull() ?: 1,
                                iterations,
                                algorithm
                            )
                        }
                        java.util.Arrays.fill(kek, 0.toByte())

                        settingsRepository.resetFailedAttempts()
                        val opened = sessionManager.openVault(dek, lockEpoch)
                        java.util.Arrays.fill(dek, 0.toByte())
                        if (!opened) return@withContext AuthResult.INTERRUPTED
                        vaultOpenedHook?.invoke()
                        val unlockScope = this
                        // Opening the vault replaces the lock screen, which cancels the caller's
                        // scope: the steps after it must not be skipped (same order as before).
                        withContext(kotlinx.coroutines.NonCancellable) {
                            retryLegacyRows()
                            val cleanup = viewModelScope.launch(Dispatchers.Default) { vaultRepository.cleanupRecycleBin() }

                            // Check if KDF Migration is needed (F13: launched in the unlock's own scope, as before)
                            if (iterations < com.example.security.SecurityPolicy.CURRENT_KDF_ITERATIONS) {
                                unlockScope.launch { performKdfMigration(password) }
                            }

                            // A run dropped by a quick lock (it never saves while locked) is redone here.
                            recalculateSecurityStats()
                            cleanup.join()
                        }

                        return@withContext AuthResult.SUCCESS
                    }
                    java.util.Arrays.fill(kek, 0.toByte())
                    recordFailedAttempt()
                    return@withContext AuthResult.INVALID_PASSWORD
                } else {
                    val pendingMpWrapped = settingsRepository.getPendingDekMpWrappedSync()
                    val outcome = if (pendingMpWrapped != null) {
                        // MIGRATION CRASH RECOVERY
                        recoverMigration(password, salt, iterations, algorithm, pendingMpWrapped, lockEpoch)
                    } else {
                        // User has NOT migrated, trigger migration
                        performMigration(password, salt, iterations, algorithm, lockEpoch)
                    }
                    // The password was right: never a failed attempt. Never SUCCESS without unlocking.
                    // Upgraded, but locked again before or right after the vault opened: the data
                    // is migrated, so the next unlock just opens it (and shows the notice then).
                    val interruptedUnreadable = when {
                        outcome is MigrationOutcome.Interrupted -> outcome.unreadableEntries
                        outcome is MigrationOutcome.Unlocked && !sessionManager.isUnlocked.value -> outcome.unreadableEntries
                        else -> null
                    }
                    if (interruptedUnreadable != null) {
                        if (interruptedUnreadable > 0) _migrationUnreadableCount.value = interruptedUnreadable
                        settingsRepository.resetFailedAttempts()
                        return@withContext AuthResult.INTERRUPTED
                    }
                    if (outcome !is MigrationOutcome.Unlocked) {
                        return@withContext AuthResult.MIGRATION_FAILED
                    }
                    // The vault is open, which cancels the caller's scope (see above): finish anyway.
                    withContext(kotlinx.coroutines.NonCancellable) {
                        // After crash recovery, rows left under a legacy key may open now. (A fresh
                        // upgrade has just tried the same keys.)
                        val recovered = if (pendingMpWrapped != null) retryLegacyRows() else 0
                        val unreadable = outcome.unreadableEntries - recovered
                        if (unreadable > 0) {
                            _migrationUnreadableCount.value = unreadable
                        }
                        settingsRepository.resetFailedAttempts()
                        viewModelScope.launch(Dispatchers.Default) { vaultRepository.cleanupRecycleBin() }.join()
                    }
                    return@withContext AuthResult.SUCCESS
                }
            }
            recordFailedAttempt()
            return@withContext AuthResult.INVALID_PASSWORD
        }
    }

    /**
     * Rows the current DEK can't read (left under a legacy key by an earlier upgrade) are retried
     * with the legacy keys at every unlock. Rows that now fully decrypt are re-encrypted with the
     * DEK in one transaction; rows that still fail are not touched. On any error nothing is
     * written and the next unlock tries again. Never blocks the unlock. Returns the rows recovered.
     */
    private suspend fun retryLegacyRows(): Int {
        val dek = vaultRepository.getSoftwareDek() ?: return 0
        try {
            val unreadable = com.example.security.LegacyVaultMigration.unreadableWithDek(vaultRepository.getAllRawEntitiesSync(), dek)
            if (unreadable.isEmpty()) return 0
            val (originals, replacements) =
                com.example.security.LegacyVaultMigration.recoverWithLegacyKeys(unreadable, legacyVaultKeys.load(), dek)
            if (originals.isEmpty()) return 0
            vaultRepository.replaceMigratedRows(originals, replacements)
            recalculateSecurityStats()
            return originals.size
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (com.example.BuildConfig.DEBUG) e.printStackTrace()
            return 0
        } finally {
            java.util.Arrays.fill(dek, 0.toByte())
        }
    }

    private suspend fun performKdfMigration(password: String) {
        var newKek: ByteArray? = null
        var activeDek: ByteArray? = null
        try {
            val targetIterations = com.example.security.SecurityPolicy.CURRENT_KDF_ITERATIONS
            val targetVersion = com.example.security.SecurityPolicy.CURRENT_KDF_VERSION
            val targetAlgorithm = com.example.security.SecurityPolicy.CURRENT_KDF_ALGORITHM

            // 1. Generate new salt & KEK
            val newSaltBase64 = PasswordHashHelper.generateSalt()
            newKek = PasswordHashHelper.deriveMasterKey(password, newSaltBase64, targetIterations, targetAlgorithm)
            
            // 2. Generate new master hash
            val newHashBase64 = PasswordHashHelper.hashPassword(password, newSaltBase64, targetIterations, targetAlgorithm)
            
            // 3. Fetch active DEK
            activeDek = vaultRepository.getSoftwareDek() ?: return
            
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
            if (com.example.BuildConfig.DEBUG) e.printStackTrace()
            // Migration silently aborts, DataStore rolls back, no harm done.
        } finally {
            newKek?.let { java.util.Arrays.fill(it, 0.toByte()) }
            activeDek?.let { java.util.Arrays.fill(it, 0.toByte()) }
        }
    }

    /**
     * A pending wrapped DEK exists: an upgrade stopped between steps 3 and 5 of [performMigration].
     * If any row with content decrypts with the pending DEK, the transaction was committed: finish
     * it. Otherwise no row is under that DEK, and the upgrade runs again from the legacy keys
     * (which replaces the pending key only after every read has succeeded).
     */
    private suspend fun recoverMigration(
        password: String,
        salt: String,
        iterations: Int,
        algorithm: String,
        pendingMpWrapped: String,
        lockEpoch: Long
    ): MigrationOutcome {
        var dek: ByteArray? = null
        try {
            val pending = unwrapPendingDek(password, salt, iterations, algorithm, pendingMpWrapped)
                ?: return performMigration(password, salt, iterations, algorithm, lockEpoch)
            dek = pending.dek

            val check = try {
                com.example.security.LegacyVaultMigration.checkWithDek(vaultRepository.getAllRawEntitiesSync(), dek)
            } catch (e: Exception) {
                if (com.example.BuildConfig.DEBUG) e.printStackTrace()
                return MigrationOutcome.Failed
            }
            if (check.hasContent && !check.anyContentReadable) {
                // DB was NOT updated (transaction rolled back or never ran).
                return performMigration(password, salt, iterations, algorithm, lockEpoch)
            }

            // DB was updated (or holds nothing encrypted). Finalize migration. A pending key wrapped
            // with another KEK is re-wrapped first: unlock only derives the KEK from the stored parameters.
            val finalWrapped = if (pending.wrappedWithCurrentKek) {
                pendingMpWrapped
            } else {
                wrapWithCurrentKek(dek, password, salt, iterations, algorithm)
            }
            // If it can't be finalized the pending key stays, and the next unlock finishes it.
            if (finalWrapped != null && settingsRepository.saveDekMpWrappedSync(finalWrapped)) {
                val pendingBioWrapped = settingsRepository.getPendingDekBioWrappedSync()
                if (pendingBioWrapped != null) {
                    settingsRepository.saveDekBioWrappedSync(pendingBioWrapped)
                }
                settingsRepository.clearPendingKeysSync()
            }
            upgradeAuthHash(password, salt, iterations, algorithm)
            if (!sessionManager.openVault(dek, lockEpoch)) return MigrationOutcome.Interrupted(check.unreadableCount)
            vaultOpenedHook?.invoke()
            recalculateSecurityStats()
            return MigrationOutcome.Unlocked(check.unreadableCount)
        } finally {
            dek?.let { java.util.Arrays.fill(it, 0.toByte()) }
        }
    }

    private class PendingDek(val dek: ByteArray, val wrappedWithCurrentKek: Boolean)

    /**
     * Unwraps a pending DEK. Candidates in order: the KEK from the stored KDF parameters, the
     * 100k defaults (2.6.x wrote pending keys with those), then the legacy KEK (the raw PBKDF2 key,
     * before KEK domain separation) with each of those parameter sets.
     */
    private fun unwrapPendingDek(password: String, salt: String, iterations: Int, algorithm: String, wrapped: String): PendingDek? {
        val params = linkedSetOf(iterations to algorithm, 100000 to "PBKDF2WithHmacSHA256")
        val candidates = params.map { Triple(it.first, it.second, false) } + params.map { Triple(it.first, it.second, true) }
        candidates.forEachIndexed { index, (iter, alg, legacy) ->
            val kek = if (legacy) {
                PasswordHashHelper.deriveLegacyMasterKey(password, salt, iter, alg)
            } else {
                PasswordHashHelper.deriveMasterKey(password, salt, iter, alg)
            }
            try {
                com.example.security.CryptoManager.unwrapDekWithKek(wrapped, kek)?.let { return PendingDek(it, index == 0) }
            } finally {
                java.util.Arrays.fill(kek, 0.toByte())
            }
        }
        return null
    }

    /** [dek] wrapped with the KEK from the stored KDF parameters, checked; null on any failure. */
    private fun wrapWithCurrentKek(dek: ByteArray, password: String, salt: String, iterations: Int, algorithm: String): String? {
        val kek = PasswordHashHelper.deriveMasterKey(password, salt, iterations, algorithm)
        var check: ByteArray? = null
        return try {
            val wrapped = com.example.security.CryptoManager.wrapDekWithKek(dek, kek)
            check = com.example.security.CryptoManager.unwrapDekWithKek(wrapped, kek)
            if (check != null && check.contentEquals(dek)) wrapped else null
        } catch (e: Exception) {
            if (com.example.BuildConfig.DEBUG) e.printStackTrace()
            null
        } finally {
            java.util.Arrays.fill(kek, 0.toByte())
            check?.let { java.util.Arrays.fill(it, 0.toByte()) }
        }
    }

    /**
     * Opens the vault with the biometric-unwrapped [dek]. Returns false, leaving it locked, if the
     * vault was locked again while this ran.
     */
    suspend fun unlockWithBiometrics(dek: ByteArray): Boolean {
        // Read before anything else: a lock from here on (even while this waits) keeps the vault locked.
        val lockEpoch = sessionManager.currentLockEpoch()
        countUnlockCall(1)
        try {
            return com.example.security.VaultUnlockLock.mutex.withLock {
                withContext(Dispatchers.Default) {
                    if (isVaultOpen()) {
                        // Already open (e.g. autofill asked while the app is unlocked): nothing to load.
                        settingsRepository.resetFailedAttempts()
                        return@withContext true
                    }
                    val opened = sessionManager.openVault(dek, lockEpoch)
                    if (opened) vaultOpenedHook?.invoke()
                    // Opening the vault can cancel the caller's scope: the steps after it must not
                    // be skipped (same order as before).
                    withContext(kotlinx.coroutines.NonCancellable) {
                        settingsRepository.resetFailedAttempts()
                        if (opened) {
                            retryLegacyRows()
                            val cleanup = viewModelScope.launch(Dispatchers.Default) { vaultRepository.cleanupRecycleBin() }
                            // A run dropped by a quick lock (it never saves while locked) is redone here.
                            recalculateSecurityStats()
                            cleanup.join()
                        }
                    }
                    opened
                }
            }
        } finally {
            countUnlockCall(-1)
        }
    }

    suspend fun wrapDekForBiometrics() {
        val softwareDek = vaultRepository.getSoftwareDek() ?: return
        try {
            val cipher = com.example.security.BiometricCryptoHelper.getEncryptCipherForBiometric()
            if (cipher != null) {
                val iv = cipher.iv
                val encryptedData = cipher.doFinal(softwareDek)
                val combined = iv + encryptedData
                val dekBioWrapped = android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP)
                settingsRepository.saveDekBioWrappedSync(dekBioWrapped)
            }
        } finally {
            java.util.Arrays.fill(softwareDek, 0.toByte())
        }
    }

    private var clipboardJob: kotlinx.coroutines.Job? = null

    fun copyToClipboard(context: android.content.Context, label: String, text: String) {
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText(label, text)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = android.os.PersistableBundle().apply {
                putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard.setPrimaryClip(clip)
        android.widget.Toast.makeText(context, "$label copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()

        clipboardJob?.cancel()
        val appContext = context.applicationContext
        clipboardJob = viewModelScope.launch {
            val delayMs = settingsRepository.clipboardClearTimer.first()
            if (delayMs > 0) {
                kotlinx.coroutines.delay(delayMs)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    clipboard.clearPrimaryClip()
                } else {
                    val currentClip = clipboard.primaryClip
                    if (currentClip != null && currentClip.itemCount > 0) {
                        val currentText = currentClip.getItemAt(0).text?.toString()
                        if (currentText == text) {
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
                        }
                    }
                }
            }
        }
    }

    fun lock() {
        // Clears the key too (the session manager manages this repository, see getInstance). A
        // second clear here could undo an unlock that ran in between.
        sessionManager.lock()
        clearExportPassword()
        clipboardJob?.cancel()
    }

    private val setupInProgress = java.util.concurrent.atomic.AtomicBoolean(false)
    private val _isSettingUp = MutableStateFlow(false)
    val isSettingUp: StateFlow<Boolean> = _isSettingUp.asStateFlow()

    fun setupMasterPassword(password: String) {
        viewModelScope.launch {
            setupMasterPasswordSync(password)
        }
    }

    /**
     * Creates a new vault. Refuses (F17) when a wrapped vault key or any entry row exists, so an
     * existing vault is never overwritten; one setup at a time (double submit).
     */
    suspend fun setupMasterPasswordSync(password: String): SetupResult {
        if (!setupInProgress.compareAndSet(false, true)) return SetupResult.IN_PROGRESS
        _isSettingUp.value = true
        val lockEpoch = sessionManager.currentLockEpoch()
        try {
            return withContext(Dispatchers.Default) {
                // Same process-wide lock as unlock: no upgrade or other setup can run in between.
                com.example.security.VaultUnlockLock.mutex.withLock {
                    when {
                        vaultDataExists() -> SetupResult.VAULT_EXISTS
                        createVault(password, lockEpoch) -> SetupResult.CREATED
                        else -> SetupResult.FAILED
                    }
                }
            }
        } finally {
            _isSettingUp.value = false
            setupInProgress.set(false)
        }
    }

    /**
     * Writes a new vault's master hash, KDF metadata and wrapped DEK, then unlocks. If any of that
     * fails, what was written is rolled back (so the next launch shows Setup again), the DEK is
     * never put in memory, and it returns false. Caller holds [com.example.security.VaultUnlockLock]
     * and has checked that no vault exists.
     */
    private suspend fun createVault(password: String, lockEpoch: Long): Boolean {
        val targetIterations = com.example.security.SecurityPolicy.CURRENT_KDF_ITERATIONS
        val targetVersion = com.example.security.SecurityPolicy.CURRENT_KDF_VERSION
        val targetAlgorithm = com.example.security.SecurityPolicy.CURRENT_KDF_ALGORITHM

        val newDek = ByteArray(32)
        var mpKek: ByteArray? = null
        var checkDek: ByteArray? = null
        try {
            // 1. Generate salt and hash for master password
            val salt = PasswordHashHelper.generateSalt()
            val hash = PasswordHashHelper.hashPassword(password, salt, targetIterations, targetAlgorithm)
            settingsRepository.saveMasterPasswordAndKdfMetadata(hash, salt, targetVersion, targetIterations, targetAlgorithm)

            // 2. Generate the Software DEK and wrap it with the Master Password KEK
            java.security.SecureRandom().nextBytes(newDek)
            mpKek = PasswordHashHelper.deriveMasterKey(password, salt, targetIterations, targetAlgorithm)
            val dekMpWrapped = com.example.security.CryptoManager.wrapDekWithKek(newDek, mpKek)
            checkDek = com.example.security.CryptoManager.unwrapDekWithKek(dekMpWrapped, mpKek)
            if (checkDek == null || !checkDek.contentEquals(newDek)) {
                rollBackVaultCreation()
                return false
            }

            // 3. Save the wrapped DEK; only once it is on disk may the vault be used.
            if (!saveNewVaultKey(dekMpWrapped)) {
                rollBackVaultCreation()
                return false
            }

            // 4. Inject the DEK so it's ready for immediate use, unless a lock ran meanwhile:
            // then the new vault exists but stays locked.
            sessionManager.openVault(newDek, lockEpoch)
            return true
        } catch (e: kotlinx.coroutines.CancellationException) {
            rollBackVaultCreation()
            throw e
        } catch (e: Exception) {
            if (com.example.BuildConfig.DEBUG) e.printStackTrace()
            rollBackVaultCreation()
            return false
        } finally {
            java.util.Arrays.fill(newDek, 0.toByte())
            mpKek?.let { java.util.Arrays.fill(it, 0.toByte()) }
            checkDek?.let { java.util.Arrays.fill(it, 0.toByte()) }
        }
    }

    /**
     * Undoes a failed [createVault]: no DEK in memory, no wrapped DEK, no master hash or KDF
     * metadata, so the next launch shows Setup. Safe because no vault existed before (checked under
     * the lock) and nothing was encrypted with the new key. Best effort: a hash left behind with
     * no key and no rows still unlocks (as an empty vault) with the same password.
     */
    private suspend fun rollBackVaultCreation() {
        sessionManager.lock()
        try {
            settingsRepository.removeDekMpWrappedSync()
        } catch (e: Exception) {
            if (com.example.BuildConfig.DEBUG) e.printStackTrace()
        }
        try {
            withContext(kotlinx.coroutines.NonCancellable) {
                settingsRepository.clearMasterPasswordAndKdfMetadata()
            }
        } catch (e: Exception) {
            if (com.example.BuildConfig.DEBUG) e.printStackTrace()
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
            try {
                vaultRepository.insertEntry(entry)
                recalculateSecurityStats()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // E.g. the vault locked first: nothing was saved.
                if (com.example.BuildConfig.DEBUG) e.printStackTrace()
            }
        }
    }

    suspend fun addEntrySync(entry: VaultEntry): Boolean {
        return try {
            if (vaultRepository.getSoftwareDek() == null) return false
            vaultRepository.insertEntry(entry)
            recalculateSecurityStats()
            true
        } catch (e: Exception) {
            if (com.example.BuildConfig.DEBUG) e.printStackTrace()
            false
        }
    }

    suspend fun addEntries(entries: List<VaultEntry>) {
        vaultRepository.insertEntries(entries)
        vaultRepository.cleanupRecycleBin()
        recalculateSecurityStats()
    }

    /**
     * The export file for [format] ("txt", "json", anything else: password-protected .vpex) and how
     * many entries were left out because they can't be decrypted (their placeholder would come back
     * as a junk entry on import). Throws [com.example.security.VaultLockedException] while locked.
     */
    suspend fun generateExportPayload(format: String, password: String): ExportPayload {
        if (format != "txt" && format != "json") requireBackupPassword(password)
        val (entries, skipped) = exportableEntries()
        val bytes = when (format) {
            "txt" -> txtExport(entries)
            "json" -> jsonExport(entries)
            else -> vpexExport(jsonExport(entries), password)
        }
        return ExportPayload(bytes, skipped)
    }

    // The password for a .vpex export. Kept here, never in saved instance state, and only while
    // its dialog is shown or the file picker it was typed for is open; cleared after every attempt
    // and whenever the vault locks.
    private val _exportPassword = MutableStateFlow("")
    val exportPassword: StateFlow<String> = _exportPassword.asStateFlow()

    // True from the picker launch for the current password until the picker returns.
    @Volatile
    private var exportPickerPending = false

    init {
        // However the vault locks (lock(), auto-lock, the session manager directly), the password
        // goes. Every lock is a new epoch, so none is missed (isUnlocked could conflate one away).
        viewModelScope.launch {
            sessionManager.lockEpochs.collect { clearExportPassword() }
        }
    }

    fun setExportPassword(password: String) {
        _exportPassword.value = password
    }

    fun clearExportPassword() {
        exportPickerPending = false
        _exportPassword.value = ""
    }

    /** The file picker was opened for the current password: it must outlive the dialog until the picker returns. */
    fun onExportPickerLaunched() {
        exportPickerPending = true
    }

    /** The password dialog left the screen (closed, navigated away, or the screen was recreated). */
    fun onExportPasswordDialogGone() {
        if (!exportPickerPending) _exportPassword.value = ""
    }

    /** An export attempt: what happened, and how many unreadable entries were left out. */
    class ExportReport(val outcome: ExportOutcome, val skippedEntries: Int)

    /**
     * Exports the vault in [format] to [document] (see [ExportWriter]), with the password set by
     * [setExportPassword] for .vpex. The password is cleared afterwards, whatever happened.
     */
    suspend fun exportTo(document: ExportDocument, format: String): ExportReport {
        exportPickerPending = false
        val password = _exportPassword.value
        try {
            var skipped = 0
            val outcome = ExportWriter.export(document) {
                generateExportPayload(format, password).also { skipped = it.skippedEntries }.bytes
            }
            return ExportReport(outcome, skipped)
        } finally {
            // Only this attempt's password: one typed meanwhile for the next attempt stays.
            _exportPassword.compareAndSet(password, "")
        }
    }

    /** Tests: runs right after an unlock opened the vault (where the lock screen's scope goes away). */
    @androidx.annotation.VisibleForTesting
    @Volatile
    internal var vaultOpenedHook: (() -> Unit)? = null

    /** The entries an export writes, and how many undecryptable ones it leaves out. */
    private suspend fun exportableEntries(): Pair<List<VaultEntry>, Int> {
        val all = getAllEntriesDecrypted()
        val readable = all.filter { !it.isDecryptionFailed }
        return readable to (all.size - readable.size)
    }

    suspend fun generateTxtExportPayload(): ByteArray = txtExport(exportableEntries().first)

    private fun txtExport(entries: List<VaultEntry>): ByteArray {
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
                    builder.appendLine("Custom.${field.key}:")
                    builder.appendLine(field.value)
                    builder.appendLine()
                }
            }
            builder.appendLine("---")
            builder.appendLine()
        }
        return builder.toString().toByteArray(Charsets.UTF_8)
    }

    suspend fun generateSimplifiedJsonExportPayload(): ByteArray = jsonExport(exportableEntries().first)

    private fun jsonExport(entries: List<VaultEntry>): ByteArray {
        val usedTitles = mutableSetOf<String>()
        
        val rootObj = buildJsonObject {
            for (entry in entries) {
                var finalTitle = entry.title
                if (usedTitles.contains(finalTitle)) {
                    var counter = 2
                    while (usedTitles.contains("${entry.title} ($counter)")) {
                        counter++
                    }
                    finalTitle = "${entry.title} ($counter)"
                }
                usedTitles.add(finalTitle)
                
                putJsonArray(finalTitle) {
                    add(entry.username)
                    add(entry.password)
                    
                    addJsonObject {
                        put("title", entry.title)
                        put("originalTitle", entry.title)
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
        return rootObj.toString().toByteArray(Charsets.UTF_8)
    }

    suspend fun generateVpexExportPayload(password: String): ByteArray {
        requireBackupPassword(password)
        return vpexExport(generateSimplifiedJsonExportPayload(), password)
    }

    /** Never a backup "protected" by an empty or short password (e.g. one lost with the screen state). */
    private fun requireBackupPassword(password: String) {
        require(password.length >= EXPORT_PASSWORD_MIN_LENGTH) {
            "The backup password must be at least $EXPORT_PASSWORD_MIN_LENGTH characters."
        }
    }

    private fun vpexExport(json: ByteArray, password: String): ByteArray {
        val jsonString = String(json, Charsets.UTF_8)
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
                    val webLines = mutableListOf<String>()
                    var j = i + 1
                    val knownKeywords = setOf("Title:", "Username:", "Password:", "Favorite:",
                        "Website:", "Notes:", "Category:", "Tags:", "---")
                    while (j < lines.size && !knownKeywords.contains(lines[j]) &&
                           !lines[j].startsWith("Title:") && !lines[j].startsWith("Custom.")) {
                        webLines.add(lines[j])
                        j++
                    }
                    website = webLines.joinToString("\n")
                    i = j - 1
                } else if (line == "Notes:" && i + 1 < lines.size) {
                    val noteLines = mutableListOf<String>()
                    var j = i + 1
                    val knownKeywords = setOf("Title:", "Username:", "Password:", "Favorite:",
                        "Website:", "Notes:", "Category:", "Tags:", "---")
                    while (j < lines.size && !knownKeywords.contains(lines[j]) &&
                           !lines[j].startsWith("Title:") && !lines[j].startsWith("Custom.")) {
                        noteLines.add(lines[j])
                        j++
                    }
                    notes = noteLines.joinToString("\n")
                    i = j - 1
                } else if (line == "Category:" && i + 1 < lines.size) {
                    category = lines[i+1]
                    i++
                } else if (line == "Tags:" && i + 1 < lines.size) {
                    lines[i+1].split(",").forEach { tags.add(it.trim()) }
                    i++
                } else if (line.endsWith(":") && i + 1 < lines.size) {
                    val rawKey = line.dropLast(1)
                    val key = if (rawKey.startsWith("Custom.")) rawKey.removePrefix("Custom.") else rawKey
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

    internal fun decodeSimplifiedJsonImportPayload(jsonString: String): Pair<List<VaultEntry>, Int> {
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
                    var entryTitle = meta?.get("originalTitle")?.jsonPrimitive?.content
                        ?: meta?.get("title")?.jsonPrimitive?.content
                    if (entryTitle == null) {
                        val regex = Regex("""^(.*) \(\d+\)$""")
                        val match = regex.matchEntire(title)
                        entryTitle = if (match != null) match.groupValues[1] else title
                    }

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
                        title = entryTitle,
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

    /** For exports: throws [com.example.security.VaultLockedException] rather than return an empty list when locked. */
    suspend fun getAllEntriesDecrypted(): List<VaultEntry> {
        val crypto = vaultRepository.cryptoManager
        val start = crypto.keyState()
        if (!start.hasKey) throw com.example.security.VaultLockedException()
        val entries = vaultRepository.getAllEntriesSync()
        // Locked while reading: the list may be empty or partial, so it must not be exported.
        if (crypto.keyState() != start) throw com.example.security.VaultLockedException()
        return entries
    }

    suspend fun getEntryById(id: Int): VaultEntry? {
        return vaultRepository.getEntryById(id)
    }

    fun updateEntry(entry: VaultEntry) {
        viewModelScope.launch {
            try {
                vaultRepository.updateEntry(entry)
                recalculateSecurityStats()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // E.g. the vault locked first: nothing was saved.
                if (com.example.BuildConfig.DEBUG) e.printStackTrace()
            }
        }
    }

    suspend fun updateEntrySync(entry: VaultEntry): Boolean {
        return try {
            if (vaultRepository.getSoftwareDek() == null) return false
            vaultRepository.updateEntry(entry)
            recalculateSecurityStats()
            true
        } catch (e: Exception) {
            if (com.example.BuildConfig.DEBUG) e.printStackTrace()
            false
        }
    }

    fun deleteEntry(id: Int) {
        viewModelScope.launch { 
            vaultRepository.deleteEntry(id)
            recalculateSecurityStats()
        }
    }

    fun permanentlyDeleteEntry(id: Int) {
        viewModelScope.launch {
            vaultRepository.permanentlyDeleteEntry(id)
            recalculateSecurityStats()
        }
    }

    fun restoreEntry(id: Int) {
        viewModelScope.launch {
            vaultRepository.restoreEntry(id)
            recalculateSecurityStats()
        }
    }
}

class VaultViewModelFactory(
    private val vaultRepository: VaultRepository,
    private val settingsRepository: SettingsRepository,
    private val sessionManager: com.example.security.VaultSessionManager? = null
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(VaultViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return VaultViewModel(vaultRepository, settingsRepository, sessionManager) as T
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
