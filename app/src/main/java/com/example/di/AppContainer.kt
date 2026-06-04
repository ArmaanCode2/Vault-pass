package com.example.di

import android.content.Context
import com.example.data.AppDatabase
import com.example.repository.VaultRepository
import com.example.repository.SettingsRepository
import com.example.security.CryptoManager

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
}
