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
            // A manager is only reused for the repository it manages: it opens and locks that one.
            instance?.takeIf { it.vaultRepository === vaultRepository }?.let { return it }
            return synchronized(this) {
                instance?.takeIf { it.vaultRepository === vaultRepository }
                    ?: VaultSessionManager(settingsRepository, cryptoManager, vaultRepository).also {
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

    /**
     * Lock-only. Opening goes through [openVault], which loads the key and the unlocked flag
     * together and refuses if a lock ran meanwhile; setting the flag alone could say "unlocked"
     * with no key loaded.
     */
    fun setUnlocked(unlocked: Boolean) {
        require(!unlocked) { "Open the vault with openVault(dek, epoch)" }
        lock()
    }

    // lock() and openVault() run one at a time, so the vault is either open (key loaded and
    // unlocked) or locked (no key, not unlocked), never half of each.
    private val openLock = Any()

    // Changed only under openLock.
    private val _lockEpoch = MutableStateFlow(0L)

    /**
     * Moves on every [lock], whatever called it (auto-lock included). Unlike [isUnlocked], a
     * collector can't miss a lock that follows an unlock it hadn't seen yet: every lock is a new value.
     */
    val lockEpochs: StateFlow<Long> = _lockEpoch.asStateFlow()

    /**
     * Moves on every [lock]. An unlock reads it when it starts and passes it to [openVault]: a lock
     * that ran meanwhile (e.g. the app went to the background during the key derivation) wins.
     */
    fun currentLockEpoch(): Long = _lockEpoch.value

    /**
     * Loads [dek] and marks the vault unlocked, unless [lock] ran since [epoch] was read: then
     * nothing changes, the vault stays locked and it returns false.
     */
    fun openVault(dek: ByteArray, epoch: Long): Boolean {
        synchronized(openLock) {
            if (_lockEpoch.value != epoch) return false
            vaultRepository.injectSoftwareDek(dek)
            _isUnlocked.value = true
            return true
        }
    }

    fun lock() {
        synchronized(openLock) {
            _lockEpoch.value = _lockEpoch.value + 1
            autoLockJob?.cancel()
            autoLockJob = null
            _isUnlocked.value = false
            // Clears the key in the CryptoManager too, under the repository's key lock. A second clear
            // here, outside that lock, could undo an unlock that ran in between.
            vaultRepository.clearSoftwareDek()
        }
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
