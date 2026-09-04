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


    override fun onStop() {
        super.onStop()
        viewModel.handleActivityStopped()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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



    private fun showBiometricPrompt() {
        lifecycleScope.launch {
            val dekBioWrapped = viewModel.settingsRepository.getDekBioWrappedSync()
            if (dekBioWrapped == null) {
                android.widget.Toast.makeText(
                    this@MainActivity,
                    "Biometric unlock is not set up. Please use Master Password and enable biometrics in Settings.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            var cryptoObject: BiometricPrompt.CryptoObject? = null
            val combined = android.util.Base64.decode(dekBioWrapped, android.util.Base64.NO_WRAP)
            if (combined.size > 12) {
                val iv = combined.copyOfRange(0, 12)
                val cipher = com.example.security.BiometricCryptoHelper.getDecryptCipherForBiometric(iv)
                if (cipher != null) {
                    cryptoObject = BiometricPrompt.CryptoObject(cipher)
                }
            }

            if (cryptoObject == null) {
                android.widget.Toast.makeText(
                    this@MainActivity,
                    "Biometric key missing or invalidated. Please use Master Password and re-enable in Settings.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            val executor = ContextCompat.getMainExecutor(this@MainActivity)
            val biometricPrompt = BiometricPrompt(this@MainActivity, executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        lifecycleScope.launch(Dispatchers.Default) {
                            try {
                                if (result.cryptoObject?.cipher != null) {
                                    val encryptedData = combined.copyOfRange(12, combined.size)
                                    val dek = result.cryptoObject!!.cipher!!.doFinal(encryptedData)
                                    viewModel.unlockWithBiometrics(dek)
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                                withContext(Dispatchers.Main) {
                                    android.widget.Toast.makeText(this@MainActivity, "Authentication error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                })

            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock VaultPass")
                .setSubtitle("Log in using your biometric credential")
                .setNegativeButtonText("Use Master Password")
                .build()

            biometricPrompt.authenticate(promptInfo, cryptoObject)
        }
    }
}

