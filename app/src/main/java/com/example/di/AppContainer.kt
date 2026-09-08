package com.example.di

import android.content.Context
import com.example.data.AppDatabase
import com.example.network.sync.LanDiscoveryManager
import com.example.repository.PairedDeviceRepository
import com.example.repository.SettingsRepository
import com.example.repository.VaultRepository
import com.example.security.CryptoManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(private val context: Context) {
    val cryptoManager: CryptoManager by lazy {
        CryptoManager(settingsRepository)
    }
    
    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(context)
    }
    
    val appDatabase: AppDatabase by lazy {
        AppDatabase.getDatabase(context)
    }
    
    val vaultRepository: VaultRepository by lazy {
        VaultRepository(appDatabase.vaultDao(), cryptoManager)
    }
    
    val autofillDiagnosticsRepository: com.example.repository.AutofillDiagnosticsRepository by lazy {
        com.example.repository.AutofillDiagnosticsRepository()
    }

    val vaultSessionManager: com.example.security.VaultSessionManager by lazy {
        com.example.security.VaultSessionManager.getInstance(settingsRepository, cryptoManager, vaultRepository)
    }

    val pairedDeviceRepository: PairedDeviceRepository by lazy {
        PairedDeviceRepository(context)
    }

    val lanDiscoveryManager: LanDiscoveryManager by lazy {
        val deviceId = LanDiscoveryManager.getOrCreateDeviceId(context)
        val deviceName = android.os.Build.MODEL.takeIf { !it.isNullOrBlank() } ?: "Android Device"
        LanDiscoveryManager(
            context = context,
            deviceId = deviceId,
            deviceName = deviceName,
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        )
    }

    val lanSocketTransport: com.example.network.sync.LanSocketTransport by lazy {
        val deviceId = LanDiscoveryManager.getOrCreateDeviceId(context)
        val deviceName = android.os.Build.MODEL.takeIf { !it.isNullOrBlank() } ?: "Android Device"
        com.example.network.sync.LanSocketTransport(
            context = context,
            localDeviceId = deviceId,
            localDeviceName = deviceName,
            pairedDeviceRepository = pairedDeviceRepository,
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        )
    }
}


