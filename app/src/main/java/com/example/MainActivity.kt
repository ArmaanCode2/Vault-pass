package com.example

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.ui.VaultViewModel
import com.example.ui.VaultViewModelFactory
import com.example.ui.screens.VaultApp
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.isSystemInDarkTheme

class MainActivity : FragmentActivity() {

    private val viewModel: VaultViewModel by viewModels {
        val app = application as VaultPassApplication
        VaultViewModelFactory(app.container.vaultRepository, app.container.settingsRepository)
    }


    private val updateController get() = (application as VaultPassApplication).container.updateController

    override fun onStop() {
        super.onStop()
        viewModel.handleActivityStopped()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // With focus the clipboard is readable: a clear that couldn't tell whose clip it was runs now.
        if (hasFocus) com.example.security.ClipboardClearScheduler.get(this).clearIfDue(inForeground = true)
    }

    override fun onResume() {
        super.onResume()
        // Foreground tracking for the update installer (commit, confirmation, "Install unknown apps" return).
        updateController.onActivityResumed(this)
    }

    override fun onPause() {
        updateController.onActivityPaused(this)
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Background check (only if turned on) or an already downloaded update; never blocks unlock.
        updateController.onAppOpen()

        // Secure from the first frame; cleared below only once the setting loads as off.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.settingsRepository.disableScreenshots.collect { disable ->
                    if (disable) {
                        window.setFlags(
                            WindowManager.LayoutParams.FLAG_SECURE,
                            WindowManager.LayoutParams.FLAG_SECURE
                        )
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
            }
        }

        setContent {
            val themeMode by viewModel.settingsRepository.themeMode.collectAsStateWithLifecycle(initialValue = 0)
            val accentColorName by viewModel.settingsRepository.accentColor.collectAsStateWithLifecycle(initialValue = "BLUE")
            
            val isDarkTheme = when (themeMode) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }
            
            val accentColor = try {
                com.example.ui.theme.AccentColor.valueOf(accentColorName)
            } catch (e: Exception) {
                com.example.ui.theme.AccentColor.BLUE
            }

            MyApplicationTheme(darkTheme = isDarkTheme, accentColor = accentColor) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    VaultApp(
                        viewModel = viewModel,
                        onShowBiometricPrompt = { showBiometricPrompt() }
                    )
                }
            }
        }
    }



    private val biometricPromptManager by lazy { com.example.security.BiometricPromptManager(this) }

    private fun showBiometricPrompt() {
        biometricPromptManager.showBiometricPrompt(
            settingsRepository = viewModel.settingsRepository,
            subtitle = "Log in using your biometric credential",
            onSuccess = { dek ->
                // False only when the vault was locked again while it opened.
                if (!viewModel.unlockWithBiometrics(dek)) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        android.widget.Toast.makeText(this@MainActivity, R.string.lock_interrupted, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }
}

