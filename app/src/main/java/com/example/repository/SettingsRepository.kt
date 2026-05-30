package com.example.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    companion object {
        val MASTER_HASH = stringPreferencesKey("master_hash")
        val MASTER_SALT = stringPreferencesKey("master_salt")
        val BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")
        val THEME_MODE = intPreferencesKey("theme_mode") // 0 = System, 1 = Light, 2 = Dark
        val AUTO_LOCK_TIMER = longPreferencesKey("auto_lock_timer") // milliseconds
        val HIDE_PASSWORDS = booleanPreferencesKey("hide_passwords")
        val DISABLE_SCREENSHOTS = booleanPreferencesKey("disable_screenshots")
        val CLIPBOARD_CLEAR_TIMER = longPreferencesKey("clipboard_clear_timer")
        
        private const val PREFS_NAME = "vaultpass_sync_prefs"
        private const val FALLBACK_KEY_PREF = "fallback_key"
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

    val autoLockTimer: Flow<Long> = data.map { it[AUTO_LOCK_TIMER] ?: 300000L } // default 5 mins
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

    // Synchronous access using SharedPreferences for the CryptoManager fallback key
    fun getFallbackKeySync(): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(FALLBACK_KEY_PREF, null)
    }

    fun saveFallbackKeySync(keyBase64: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(FALLBACK_KEY_PREF, keyBase64).apply()
    }
}
