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
import com.example.R
import com.example.VaultPassApplication
import com.example.ui.screens.AutofillPickScreen
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

    /** The page structure the framework adds to the (mutable) authentication PendingIntent's fill-in extras. */
    private var assistStructure: android.app.assist.AssistStructure? = null

    /** Set when started from the "Search VaultPass…" dataset: the user picks one entry to fill. */
    private var pickRequest: com.example.service.AutofillPick.Request? = null
    private var pickInProgress = false

    /** Loaded in the background for pick mode on a web page (site comparison); null until then: exact hosts. */
    @Volatile
    private var publicSuffixList: com.example.service.PublicSuffixList? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // IntentCompat avoids the typed getParcelableExtra bug on API 33.
        assistStructure = androidx.core.content.IntentCompat.getParcelableExtra(
            intent,
            android.view.autofill.AutofillManager.EXTRA_ASSIST_STRUCTURE,
            android.app.assist.AssistStructure::class.java
        )
        pickRequest = com.example.service.AutofillPick.readRequest(intent)
        if (pickRequest?.webDomain != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                publicSuffixList = try {
                    com.example.service.PublicSuffixList.get(this@AutofillAuthActivity)
                } catch (e: Exception) {
                    null
                }
            }
        }
        val pickTargetLabel = pickRequest?.let { pickTargetLabel(it) }.orEmpty()
        if (assistStructure == null && pickRequest == null) {
            (application as VaultPassApplication).container.autofillDiagnosticsRepository
                .logError("Autofill authentication started without EXTRA_ASSIST_STRUCTURE; nothing can be filled.")
        }

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
            val pick = pickRequest

            LaunchedEffect(isUnlocked) {
                // Pick mode stays open after unlocking: the user chooses an entry first.
                if (isUnlocked && pick == null) {
                    val structure = assistStructure

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
                                if (com.example.BuildConfig.DEBUG) e.printStackTrace()
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
                    if (pick != null && isUnlocked) {
                        val entries by viewModel.allDecryptedEntries.collectAsStateWithLifecycle()
                        AutofillPickScreen(
                            entries = entries,
                            showRememberNote = com.example.service.AutofillPick.shouldSaveLink(pick, packageName),
                            targetLabel = pickTargetLabel,
                            confirmationFor = { entry -> mismatchConfirmation(pick, entry) },
                            onPick = { entry -> completePick(pick, entry) },
                            onCancel = {
                                setResult(RESULT_CANCELED)
                                finish()
                            }
                        )
                    } else {
                        LockScreen(
                            viewModel = viewModel,
                            onShowBiometricPrompt = { showBiometricPrompt() }
                        )
                    }
                }
            }
        }
    }

    /** "Filling on the website bank.com" / "Filling in the app Label (package)" for the top of the picker. */
    private fun pickTargetLabel(request: com.example.service.AutofillPick.Request): String {
        request.webDomain?.let { return getString(R.string.autofill_pick_target_site, it) }
        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(request.packageName, 0)).toString()
        } catch (e: Exception) {
            null
        }
        // The package name is always shown: an app can choose any label.
        val app = if (label.isNullOrBlank() || label == request.packageName) request.packageName else "$label (${request.packageName})"
        return getString(R.string.autofill_pick_target_app, app)
    }

    /** The question to ask before filling [entry] into a page of another site; null when no question is needed. */
    private fun mismatchConfirmation(
        request: com.example.service.AutofillPick.Request,
        entry: com.example.domain.models.VaultEntry
    ): String? {
        val pageHost = request.webDomain ?: return null
        val psl = publicSuffixList
        val entrySite = com.example.service.AutofillPick.siteMismatch(entry, pageHost, psl) ?: return null
        val pageSite = com.example.service.AutofillPick.siteOf(pageHost, psl) ?: pageHost
        return if (entrySite.isEmpty()) {
            getString(R.string.autofill_pick_mismatch_no_site, pageSite)
        } else {
            getString(R.string.autofill_pick_mismatch_message, entrySite, pageSite)
        }
    }

    /**
     * Returns a dataset filling the picked entry into the detected fields and, for a native app (never a browser),
     * remembers the choice so that app is offered the entry directly next time.
     */
    private fun completePick(request: com.example.service.AutofillPick.Request, entry: com.example.domain.models.VaultEntry) {
        if (pickInProgress) return
        val dataset = com.example.service.VaultAutofillService.buildEntryDataset(this, entry, request.usernameId, request.passwordId)
        if (dataset == null) {
            android.widget.Toast.makeText(this, R.string.autofill_pick_nothing_to_fill, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        pickInProgress = true
        val app = application as VaultPassApplication
        lifecycleScope.launch {
            if (com.example.service.AutofillPick.shouldSaveLink(request, packageName)) {
                try {
                    if (!app.container.vaultRepository.linkAutofillApp(request.packageName, entry.syncId)) {
                        app.container.autofillDiagnosticsRepository.log("Autofill link not saved for ${request.packageName}")
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    app.container.autofillDiagnosticsRepository.logError("Could not save autofill link: ${e.message}")
                }
            }
            val resultIntent = android.content.Intent().apply {
                putExtra(android.view.autofill.AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset)
            }
            setResult(RESULT_OK, resultIntent)
            finish()
        }
    }

    private val biometricPromptManager by lazy { com.example.security.BiometricPromptManager(this) }

    private fun showBiometricPrompt() {
        biometricPromptManager.showBiometricPrompt(
            settingsRepository = viewModel.settingsRepository,
            subtitle = "Log in to autofill your credential",
            onSuccess = { dek ->
                // False only when the vault was locked again while it opened.
                if (!viewModel.unlockWithBiometrics(dek)) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        android.widget.Toast.makeText(this@AutofillAuthActivity, R.string.lock_interrupted, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }
}
