package com.example

import android.app.Application
import com.example.di.AppContainer

class VaultPassApplication : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // First start after an in-app update was installed: drop the downloaded APK (and stale leftovers).
        // Runs on a background thread; the update engine waits for it before any update state is read.
        container.updateEngine.startStartupCleanup(container.applicationScope)
        // A copied secret whose clear was missed (process killed, alarm dropped by a force-stop).
        container.clipboardClearScheduler.sweep()
    }
}
