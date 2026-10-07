package com.example.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.sync.models.PairedDevice
import com.vaultpass.synccore.PairKeyFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

private val Context.pairedDevicesDataStore: DataStore<Preferences> by preferencesDataStore(name = "vaultpass_paired_devices")

class PairedDeviceRepository(
    private val dataStore: DataStore<Preferences>
) {
    constructor(context: Context) : this(context.pairedDevicesDataStore)

    companion object {
        val PAIRED_DEVICES_JSON = stringPreferencesKey("paired_devices_json")
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }

    /**
     * DataStore runs the transform in the caller's context, so a write from the main thread holds
     * the store until the main thread gets to it. Writes run on IO and never wait for the UI.
     */
    private suspend fun edit(transform: suspend (MutablePreferences) -> Unit) {
        withContext(Dispatchers.IO) { dataStore.edit(transform) }
    }

    /** Only protocol v2 pairings count; v1 entries are dropped on the next write. */
    private fun decodeDevices(rawJson: String?): List<PairedDevice> =
        decodeAllDevices(rawJson).filter { PairKeyFormat.isCurrent(it.sharedSecret) }

    private fun decodeAllDevices(rawJson: String?): List<PairedDevice> {
        if (rawJson.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString<List<PairedDevice>>(rawJson)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Deletes pairings made with sync protocol v1, whose keys travelled over the network in
     * plaintext. Those devices must be paired again. Returns how many were removed.
     */
    suspend fun purgeLegacyPairings(): Int {
        var removed = 0
        edit { preferences ->
            val all = decodeAllDevices(preferences[PAIRED_DEVICES_JSON])
            val current = all.filter { PairKeyFormat.isCurrent(it.sharedSecret) }
            removed = all.size - current.size
            if (removed > 0) {
                preferences[PAIRED_DEVICES_JSON] = json.encodeToString(current)
            }
        }
        return removed
    }

    fun observePairedDevices(): Flow<List<PairedDevice>> =
        dataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .map { preferences ->
                decodeDevices(preferences[PAIRED_DEVICES_JSON])
            }

    suspend fun getPairedDevices(): List<PairedDevice> =
        observePairedDevices().first()

    suspend fun getPairedDevice(deviceId: String): PairedDevice? =
        getPairedDevices().firstOrNull { it.deviceId == deviceId }

    suspend fun savePairedDevice(device: PairedDevice) {
        edit { preferences ->
            val current = decodeDevices(preferences[PAIRED_DEVICES_JSON]).toMutableList()
            val index = current.indexOfFirst { it.deviceId == device.deviceId }
            if (index >= 0) {
                current[index] = device
            } else {
                current.add(device)
            }
            preferences[PAIRED_DEVICES_JSON] = json.encodeToString(current)
        }
    }

    suspend fun unpairDevice(deviceId: String) {
        edit { preferences ->
            val current = decodeDevices(preferences[PAIRED_DEVICES_JSON])
            val filtered = current.filter { it.deviceId != deviceId }
            preferences[PAIRED_DEVICES_JSON] = json.encodeToString(filtered)
        }
    }

    suspend fun updateLastSync(deviceId: String, timestamp: Long) {
        edit { preferences ->
            val current = decodeDevices(preferences[PAIRED_DEVICES_JSON])
            val updated = current.map {
                if (it.deviceId == deviceId) it.copy(lastSyncAt = timestamp) else it
            }
            preferences[PAIRED_DEVICES_JSON] = json.encodeToString(updated)
        }
    }

    suspend fun clearAllPairedDevices() {
        edit { preferences ->
            preferences.remove(PAIRED_DEVICES_JSON)
        }
    }
}
