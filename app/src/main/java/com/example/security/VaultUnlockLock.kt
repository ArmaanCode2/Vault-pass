package com.example.security

import kotlinx.coroutines.sync.Mutex

/**
 * One unlock, legacy-vault upgrade or vault creation at a time in this process. Each screen
 * (MainActivity, AutofillAuthActivity) has its own view model, so a per-view-model flag can't
 * stop two of them from upgrading the same vault at once. Not reentrant: take it once per call.
 */
object VaultUnlockLock {
    val mutex = Mutex()
}
