package com.example.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.VaultPassApplication
import com.example.ui.screens.LockScreen
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AutofillAuthActivity : FragmentActivity() {

    private val viewModel: VaultViewModel by viewModels {
        val app = application as VaultPassApplication
        VaultViewModelFactory(app.container.vaultRepository, app.container.settingsRepository)
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

        onBackPressedDispatcher.addCallback(this) {
            setResult(RESULT_CANCELED)
            finish()
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

            val isUnlocked by viewModel.isUnlocked.collectAsStateWithLifecycle()

            LaunchedEffect(isUnlocked) {
                if (isUnlocked) {
                    val structure: android.app.assist.AssistStructure? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(android.view.autofill.AutofillManager.EXTRA_ASSIST_STRUCTURE, android.app.assist.AssistStructure::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(android.view.autofill.AutofillManager.EXTRA_ASSIST_STRUCTURE)
                    }

                    val app = application as VaultPassApplication
                    var fillResponse: android.service.autofill.FillResponse? = null
                    if (structure != null) {
                        withContext(Dispatchers.IO) {
                            try {
                                fillResponse = com.example.service.VaultAutofillService.buildResponseForStructure(
                                    this@AutofillAuthActivity,
                                    structure,
                                    app.container.vaultRepository,
                                    app.container.autofillDiagnosticsRepository
                                )
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }

                    if (fillResponse != null) {
                        val resultIntent = android.content.Intent().apply {
                            putExtra(android.view.autofill.AutofillManager.EXTRA_AUTHENTICATION_RESULT, fillResponse)
                        }
                        setResult(RESULT_OK, resultIntent)
                    } else {
                        setResult(RESULT_CANCELED)
                    }
                    finish()
                }
            }

            MyApplicationTheme(darkTheme = isDarkTheme, accentColor = accentColor) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LockScreen(
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
            subtitle = "Log in to autofill your credential",
            onSuccess = { dek -> viewModel.unlockWithBiometrics(dek) }
        )
    }
}
