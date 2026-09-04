package com.example.security

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.repository.SettingsRepository
import com.example.repository.VaultRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class VaultSessionManager(
    val settingsRepository: SettingsRepository,
    val cryptoManager: CryptoManager,
    val vaultRepository: VaultRepository
) : DefaultLifecycleObserver {

    private val sessionScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var autoLockJob: Job? = null

    @Volatile
    private var isPerformingSystemOperation = false

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    companion object {
        @Volatile
        var instance: VaultSessionManager? = null
            internal set

        fun getInstance(
            settingsRepository: SettingsRepository,
            cryptoManager: CryptoManager,
            vaultRepository: VaultRepository
        ): VaultSessionManager {
            return instance ?: synchronized(this) {
                instance ?: VaultSessionManager(settingsRepository, cryptoManager, vaultRepository).also {
                    instance = it
                }
            }
        }
        fun resetForTesting() {
            instance = null
        }
    }

    init {
        instance = this
        try {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        } catch (e: Throwable) {
            // Ignore in testing environments where ProcessLifecycleOwner may not be initialized
        }
    }

    fun setPerformingSystemOperation(isPerforming: Boolean) {
        isPerformingSystemOperation = isPerforming
    }

    fun getPerformingSystemOperation(): Boolean = isPerformingSystemOperation

    fun setUnlocked(unlocked: Boolean) {
        _isUnlocked.value = unlocked
        if (!unlocked) {
            lock()
        }
    }

    fun lock() {
        _isUnlocked.value = false
        autoLockJob?.cancel()
        autoLockJob = null
        vaultRepository.clearSoftwareDek()
        cryptoManager.clearSoftwareDek()
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        if (isPerformingSystemOperation) return

        sessionScope.launch {
            val timeout = settingsRepository.autoLockTimer.firstOrNull() ?: 60000L
            if (timeout == 0L) {
                lock()
            } else if (timeout > 0L) {
                autoLockJob?.cancel()
                autoLockJob = launch {
                    delay(timeout)
                    lock()
                }
            }
        }
    }

    fun handleActivityStopped() {
        if (isPerformingSystemOperation) return

        sessionScope.launch {
            val timeout = settingsRepository.autoLockTimer.firstOrNull() ?: 60000L
            if (timeout == 0L) {
                autoLockJob?.cancel()
                lock()
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        autoLockJob?.cancel()
    }
}
