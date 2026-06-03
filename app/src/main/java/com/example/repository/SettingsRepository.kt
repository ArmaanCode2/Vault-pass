package com.example.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import com.example.domain.security.SecurityStatsSummary

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    companion object {
        val MASTER_HASH = stringPreferencesKey("master_hash")
        val MASTER_SALT = stringPreferencesKey("master_salt")
        val BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")
        val THEME_MODE = intPreferencesKey("theme_mode") // 0 = System, 1 = Light, 2 = Dark
        val ACCENT_COLOR = stringPreferencesKey("accent_color")
        val AUTO_LOCK_TIMER = longPreferencesKey("auto_lock_timer") // milliseconds
        val HIDE_PASSWORDS = booleanPreferencesKey("hide_passwords")
        val DISABLE_SCREENSHOTS = booleanPreferencesKey("disable_screenshots")
        val CLIPBOARD_CLEAR_TIMER = longPreferencesKey("clipboard_clear_timer")
        
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
        
        private const val PREFS_NAME = "vaultpass_sync_prefs"
        private const val DEK_MP_WRAPPED_PREF = "dek_mp_wrapped"
        private const val DEK_BIO_WRAPPED_PREF = "dek_bio_wrapped"
        private const val PENDING_DEK_MP_WRAPPED_PREF = "pending_dek_mp_wrapped"
        private const val PENDING_DEK_BIO_WRAPPED_PREF = "pending_dek_bio_wrapped"
    }

    suspend fun saveMasterPasswordData(hash: String, salt: String) {
        context.dataStore.edit { prefs ->
            prefs[MASTER_HASH] = hash
            prefs[MASTER_SALT] = salt
        }
    }

    private val data: Flow<Preferences> = context.dataStore.data.catch { exception ->
        exception.printStackTrace()
        emit(emptyPreferences())
    }

    val masterPasswordHash: Flow<String?> = data.map { it[MASTER_HASH] }
    val masterPasswordSalt: Flow<String?> = data.map { it[MASTER_SALT] }

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

    val disableScreenshots: Flow<Boolean> = data.map { it[DISABLE_SCREENSHOTS] ?: false }
    suspend fun setDisableScreenshots(disable: Boolean) {
        context.dataStore.edit { it[DISABLE_SCREENSHOTS] = disable }
    }

    val clipboardClearTimer: Flow<Long> = data.map { it[CLIPBOARD_CLEAR_TIMER] ?: 30000L } // default 30s
    suspend fun setClipboardClearTimer(timer: Long) {
        context.dataStore.edit { it[CLIPBOARD_CLEAR_TIMER] = timer }
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

    fun saveDekMpWrappedSync(wrappedBase64: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(DEK_MP_WRAPPED_PREF, wrappedBase64).apply()
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

    fun savePendingDekMpWrappedSync(wrappedBase64: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PENDING_DEK_MP_WRAPPED_PREF, wrappedBase64).apply()
    }

    fun getPendingDekBioWrappedSync(): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(PENDING_DEK_BIO_WRAPPED_PREF, null)
    }

    fun savePendingDekBioWrappedSync(wrappedBase64: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PENDING_DEK_BIO_WRAPPED_PREF, wrappedBase64).apply()
    }

    fun clearPendingKeysSync() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(PENDING_DEK_MP_WRAPPED_PREF)
            .remove(PENDING_DEK_BIO_WRAPPED_PREF)
            .apply()
    }
}
