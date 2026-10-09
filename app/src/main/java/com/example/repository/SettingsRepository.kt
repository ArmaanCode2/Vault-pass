package com.example.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import com.example.domain.security.SecurityStatsSummary

/** What reading master_hash found. A read error is never "no vault". */
enum class MasterHashRead { PRESENT, ABSENT, READ_ERROR }

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(
    private val context: Context,
    /** Clock + boot count used for the failed-unlock lockout. */
    val deviceClock: com.example.security.DeviceClock = com.example.security.AndroidDeviceClock(context)
) {

    companion object {
        val MASTER_HASH = stringPreferencesKey("master_hash")
        val MASTER_SALT = stringPreferencesKey("master_salt")
        val MASTER_KDF_VERSION = intPreferencesKey("master_kdf_version")
        val MASTER_KDF_ITERATIONS = intPreferencesKey("master_kdf_iterations")
        val MASTER_KDF_ALGORITHM = stringPreferencesKey("master_kdf_algorithm")
        val BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")
        val THEME_MODE = intPreferencesKey("theme_mode") // 0 = System, 1 = Light, 2 = Dark
        val ACCENT_COLOR = stringPreferencesKey("accent_color")
        val AUTO_LOCK_TIMER = longPreferencesKey("auto_lock_timer") // milliseconds
        val HIDE_PASSWORDS = booleanPreferencesKey("hide_passwords")
        val DISABLE_SCREENSHOTS = booleanPreferencesKey("disable_screenshots")
        val CLIPBOARD_CLEAR_TIMER = longPreferencesKey("clipboard_clear_timer")
        val CHECK_UPDATES_ON_OPEN = booleanPreferencesKey("check_updates_on_open")
        
        // Security Summary Stats
        val SECURITY_SCORE = intPreferencesKey("security_score")
        val SECURITY_TOTAL_PASSWORDS = intPreferencesKey("security_total_passwords")
        val SECURITY_STRONG_COUNT = intPreferencesKey("security_strong_count")
        val SECURITY_MEDIUM_COUNT = intPreferencesKey("security_medium_count")
        val SECURITY_WEAK_COUNT = intPreferencesKey("security_weak_count")
        val SECURITY_REUSED_COUNT = intPreferencesKey("security_reused_count")
        val SECURITY_MISSING_COUNT = intPreferencesKey("security_missing_count")
        val SECURITY_STATUS = stringPreferencesKey("security_status")
        val SECURITY_LAST_UPDATED = longPreferencesKey("security_last_updated")
        
        val FAILED_AUTH_ATTEMPTS = intPreferencesKey("failed_auth_attempts")
        val LAST_FAILED_AUTH_TIMESTAMP = longPreferencesKey("last_failed_auth_timestamp") // elapsedRealtime
        // Added in 2.7.0 (absent in 2.6.x state): boot count and lockout duration stored at the last failure.
        val LAST_FAILED_AUTH_BOOT_COUNT = intPreferencesKey("last_failed_auth_boot_count")
        val LAST_FAILED_AUTH_LOCKOUT_MS = longPreferencesKey("last_failed_auth_lockout_ms")
        
        private const val PREFS_NAME = "vaultpass_sync_prefs"
        private const val DEK_MP_WRAPPED_PREF = "dek_mp_wrapped"
        private const val DEK_BIO_WRAPPED_PREF = "dek_bio_wrapped"
        private const val PENDING_DEK_MP_WRAPPED_PREF = "pending_dek_mp_wrapped"
        private const val PENDING_DEK_BIO_WRAPPED_PREF = "pending_dek_bio_wrapped"
        private const val PENDING_DEK_MP_WRAPPED_V2_PREF = "pending_dek_mp_wrapped_v2"
        // Written only by builds before 2026-06-03 (legacy entry key). Read-only here, never written or deleted.
        private const val LEGACY_FALLBACK_KEY_PREF = "fallback_key"
    }

    suspend fun saveMasterPasswordData(hash: String, salt: String) {
        context.dataStore.edit { prefs ->
            prefs[MASTER_HASH] = hash
            prefs[MASTER_SALT] = salt
        }
    }

    private val data: Flow<Preferences> = context.dataStore.data.catch { exception ->
        if (com.example.BuildConfig.DEBUG) exception.printStackTrace()
        emit(emptyPreferences())
    }

    val masterPasswordHash: Flow<String?> = data.map { it[MASTER_HASH] }

    /**
     * Everything stored, read once. Unlike the flows here, a DataStore read error is thrown instead of
     * reading as "nothing stored", for callers (and tests) that must tell the two apart.
     *
     * Read through updateData with an identity transform: DataStore then reads the file under its write
     * lock (so never in the middle of a write) and writes nothing, because the data is unchanged. A plain
     * `data.first()` that races a write reads the file without the lock, and on the Windows test JVM the
     * file is briefly missing during the replace (Files.move with REPLACE_EXISTING deletes the target
     * first), which DataStore reports as empty preferences.
     */
    internal suspend fun readStoredPreferencesOrThrow(): Preferences = context.dataStore.updateData { it }

    /** Like [masterPasswordHash], but a read error stays a read error (decides Setup vs Lock, F17). */
    val masterHashRead: Flow<MasterHashRead> = context.dataStore.data
        .map { if (it[MASTER_HASH] != null) MasterHashRead.PRESENT else MasterHashRead.ABSENT }
        .catch { exception ->
            if (com.example.BuildConfig.DEBUG) exception.printStackTrace()
            emit(MasterHashRead.READ_ERROR)
        }
    val masterPasswordSalt: Flow<String?> = data.map { it[MASTER_SALT] }

    val masterKdfVersion: Flow<Int> = data.map { it[MASTER_KDF_VERSION] ?: 1 }
    val masterKdfIterations: Flow<Int> = data.map { it[MASTER_KDF_ITERATIONS] ?: 100000 }
    val masterKdfAlgorithm: Flow<String> = data.map { it[MASTER_KDF_ALGORITHM] ?: com.example.security.SecurityPolicy.CURRENT_KDF_ALGORITHM }

    suspend fun saveMasterKdfMetadata(version: Int, iterations: Int, algorithm: String) {
        context.dataStore.edit { prefs ->
            prefs[MASTER_KDF_VERSION] = version
            prefs[MASTER_KDF_ITERATIONS] = iterations
            prefs[MASTER_KDF_ALGORITHM] = algorithm
        }
    }

    suspend fun saveMasterPasswordAndKdfMetadata(hash: String, salt: String, version: Int, iterations: Int, algorithm: String) {
        context.dataStore.edit { prefs ->
            prefs[MASTER_HASH] = hash
            prefs[MASTER_SALT] = salt
            prefs[MASTER_KDF_VERSION] = version
            prefs[MASTER_KDF_ITERATIONS] = iterations
            prefs[MASTER_KDF_ALGORITHM] = algorithm
        }
    }

    /**
     * Removes the master password hash, salt and KDF metadata. Only for rolling back a vault
     * creation that failed before its key was saved (then no vault exists and Setup shows again).
     */
    suspend fun clearMasterPasswordAndKdfMetadata() {
        context.dataStore.edit { prefs ->
            prefs.remove(MASTER_HASH)
            prefs.remove(MASTER_SALT)
            prefs.remove(MASTER_KDF_VERSION)
            prefs.remove(MASTER_KDF_ITERATIONS)
            prefs.remove(MASTER_KDF_ALGORITHM)
        }
    }

    /** Everything [com.example.security.AuthLockout.remainingMs] needs, as stored. */
    val authLockoutState: Flow<com.example.security.AuthLockout.State> = data.map {
        com.example.security.AuthLockout.State(
            attempts = it[FAILED_AUTH_ATTEMPTS] ?: 0,
            failElapsedMs = it[LAST_FAILED_AUTH_TIMESTAMP] ?: 0L,
            bootCount = it[LAST_FAILED_AUTH_BOOT_COUNT],
            tierMs = it[LAST_FAILED_AUTH_LOCKOUT_MS]
        )
    }

    /**
     * Records a failed unlock: [elapsedRealtime] and [bootCount] (null when unknown) at the time
     * of the failure, plus the lockout duration that applies after it.
     */
    suspend fun incrementFailedAttempts(elapsedRealtime: Long, bootCount: Int?) {
        context.dataStore.edit { prefs ->
            val attempts = (prefs[FAILED_AUTH_ATTEMPTS] ?: 0) + 1
            prefs[FAILED_AUTH_ATTEMPTS] = attempts
            prefs[LAST_FAILED_AUTH_TIMESTAMP] = elapsedRealtime
            if (bootCount != null) {
                prefs[LAST_FAILED_AUTH_BOOT_COUNT] = bootCount
            } else {
                prefs.remove(LAST_FAILED_AUTH_BOOT_COUNT)
            }
            prefs[LAST_FAILED_AUTH_LOCKOUT_MS] = com.example.security.AuthLockout.tierForAttempts(attempts)
        }
    }

    suspend fun resetFailedAttempts() {
        context.dataStore.edit { prefs ->
            prefs.remove(FAILED_AUTH_ATTEMPTS)
            prefs.remove(LAST_FAILED_AUTH_TIMESTAMP)
            prefs.remove(LAST_FAILED_AUTH_BOOT_COUNT)
            prefs.remove(LAST_FAILED_AUTH_LOCKOUT_MS)
        }
    }

    val isBiometricEnabled: Flow<Boolean> = data.map { it[BIOMETRIC_ENABLED] ?: false }
    suspend fun setBiometricEnabled(enabled: Boolean) {
        context.dataStore.edit { it[BIOMETRIC_ENABLED] = enabled }
    }

    val themeMode: Flow<Int> = data.map { it[THEME_MODE] ?: 0 }
    suspend fun setThemeMode(mode: Int) {
        context.dataStore.edit { it[THEME_MODE] = mode }
    }

    val accentColor: Flow<String> = data.map { it[ACCENT_COLOR] ?: "BLUE" }
    suspend fun setAccentColor(color: String) {
        context.dataStore.edit { it[ACCENT_COLOR] = color }
    }

    val autoLockTimer: Flow<Long> = data.map { it[AUTO_LOCK_TIMER] ?: 60000L } // default 1 min
    suspend fun setAutoLockTimer(timer: Long) {
        context.dataStore.edit { it[AUTO_LOCK_TIMER] = timer }
    }

    val hidePasswordsByDefault: Flow<Boolean> = data.map { it[HIDE_PASSWORDS] ?: true }
    suspend fun setHidePasswordsByDefault(hide: Boolean) {
        context.dataStore.edit { it[HIDE_PASSWORDS] = hide }
    }

    // On unless the user turned it off: an install that never touched the switch is protected.
    val disableScreenshots: Flow<Boolean> = data.map { it[DISABLE_SCREENSHOTS] ?: true }
    suspend fun setDisableScreenshots(disable: Boolean) {
        context.dataStore.edit { it[DISABLE_SCREENSHOTS] = disable }
    }

    val clipboardClearTimer: Flow<Long> = data.map { it[CLIPBOARD_CLEAR_TIMER] ?: 30000L } // default 30s
    suspend fun setClipboardClearTimer(timer: Long) {
        context.dataStore.edit { it[CLIPBOARD_CLEAR_TIMER] = timer }
    }

    /** "Check for updates when the app opens". Off unless the user turns it on. */
    val checkUpdatesOnOpen: Flow<Boolean> = data.map { it[CHECK_UPDATES_ON_OPEN] ?: false }
    suspend fun setCheckUpdatesOnOpen(enabled: Boolean) {
        context.dataStore.edit { it[CHECK_UPDATES_ON_OPEN] = enabled }
    }

    val securityStatsSummary: Flow<SecurityStatsSummary?> = data.map { prefs ->
        if (!prefs.contains(SECURITY_SCORE)) return@map null
        SecurityStatsSummary(
            securityScore = prefs[SECURITY_SCORE] ?: 0,
            totalPasswords = prefs[SECURITY_TOTAL_PASSWORDS] ?: 0,
            strongPasswordCount = prefs[SECURITY_STRONG_COUNT] ?: 0,
            mediumPasswordCount = prefs[SECURITY_MEDIUM_COUNT] ?: 0,
            weakPasswordCount = prefs[SECURITY_WEAK_COUNT] ?: 0,
            reusedPasswordCount = prefs[SECURITY_REUSED_COUNT] ?: 0,
            missingPasswordCount = prefs[SECURITY_MISSING_COUNT] ?: 0,
            securityStatus = prefs[SECURITY_STATUS] ?: "Unknown",
            lastUpdatedTimestamp = prefs[SECURITY_LAST_UPDATED] ?: 0L
        )
    }

    suspend fun saveSecurityStatsSummary(summary: SecurityStatsSummary) {
        context.dataStore.edit { prefs ->
            prefs[SECURITY_SCORE] = summary.securityScore
            prefs[SECURITY_TOTAL_PASSWORDS] = summary.totalPasswords
            prefs[SECURITY_STRONG_COUNT] = summary.strongPasswordCount
            prefs[SECURITY_MEDIUM_COUNT] = summary.mediumPasswordCount
            prefs[SECURITY_WEAK_COUNT] = summary.weakPasswordCount
            prefs[SECURITY_REUSED_COUNT] = summary.reusedPasswordCount
            prefs[SECURITY_MISSING_COUNT] = summary.missingPasswordCount
            prefs[SECURITY_STATUS] = summary.securityStatus
            prefs[SECURITY_LAST_UPDATED] = summary.lastUpdatedTimestamp
        }
    }


    fun getDekMpWrappedSync(): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(DEK_MP_WRAPPED_PREF, null)
    }

    /** Written to disk before returning; false if that failed. */
    fun saveDekMpWrappedSync(wrappedBase64: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.edit().putString(DEK_MP_WRAPPED_PREF, wrappedBase64).commit()
    }

    /**
     * Removes the wrapped DEK. Only for rolling back a vault creation that failed (no entry is
     * encrypted with that key yet). Written to disk before returning; false if that failed.
     */
    fun removeDekMpWrappedSync(): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.edit().remove(DEK_MP_WRAPPED_PREF).commit()
    }

    fun getDekBioWrappedSync(): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(DEK_BIO_WRAPPED_PREF, null)
    }

    fun saveDekBioWrappedSync(wrappedBase64: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (wrappedBase64 == null) {
            prefs.edit().remove(DEK_BIO_WRAPPED_PREF).apply()
        } else {
            prefs.edit().putString(DEK_BIO_WRAPPED_PREF, wrappedBase64).apply()
        }
    }

    fun getPendingDekMpWrappedSync(): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(PENDING_DEK_MP_WRAPPED_PREF, null)
    }

    /** Written to disk before returning; false if that failed. */
    fun savePendingDekMpWrappedSync(wrappedBase64: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.edit().putString(PENDING_DEK_MP_WRAPPED_PREF, wrappedBase64).commit()
    }

    fun getPendingDekBioWrappedSync(): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(PENDING_DEK_BIO_WRAPPED_PREF, null)
    }

    fun savePendingDekBioWrappedSync(wrappedBase64: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PENDING_DEK_BIO_WRAPPED_PREF, wrappedBase64).apply()
    }

    /** Written to disk before returning; false if that failed. */
    fun clearPendingKeysSync(): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.edit()
            .remove(PENDING_DEK_MP_WRAPPED_PREF)
            .remove(PENDING_DEK_BIO_WRAPPED_PREF)
            .remove(PENDING_DEK_MP_WRAPPED_V2_PREF)
            .commit()
    }

    /** True when any wrapped vault key (final or pending) is stored: a vault exists on this device. */
    fun hasStoredVaultKeySync(): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return listOf(
            DEK_MP_WRAPPED_PREF,
            DEK_BIO_WRAPPED_PREF,
            PENDING_DEK_MP_WRAPPED_PREF,
            PENDING_DEK_BIO_WRAPPED_PREF,
            PENDING_DEK_MP_WRAPPED_V2_PREF
        ).any { prefs.contains(it) }
    }

    /** The legacy fallback entry key (base64), if an old build stored one. Read-only. */
    fun getLegacyFallbackKeySync(): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(LEGACY_FALLBACK_KEY_PREF, null)
    }

    fun getPendingDekMpWrappedV2Sync(): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(PENDING_DEK_MP_WRAPPED_V2_PREF, null)
    }

    fun savePendingDekMpWrappedV2Sync(wrappedBase64: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PENDING_DEK_MP_WRAPPED_V2_PREF, wrappedBase64).apply()
    }
}
