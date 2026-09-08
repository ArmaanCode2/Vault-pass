package com.example.security

import android.util.Base64
import android.widget.Toast
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.example.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BiometricPromptManager(
    private val activity: FragmentActivity
) {

    fun showBiometricPrompt(
        settingsRepository: SettingsRepository,
        title: String = "Unlock VaultPass",
        subtitle: String = "Log in using your biometric credential",
        negativeButtonText: String = "Use Master Password",
        onSuccess: suspend (dek: ByteArray) -> Unit,
        onError: ((String) -> Unit)? = null
    ) {
        activity.lifecycleScope.launch {
            val dekBioWrapped = settingsRepository.getDekBioWrappedSync()
            if (dekBioWrapped == null) {
                val message = "Biometric unlock is not set up. Please use Master Password."
                if (onError != null) {
                    onError(message)
                } else {
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            var cryptoObject: BiometricPrompt.CryptoObject? = null
            val combined = Base64.decode(dekBioWrapped, Base64.NO_WRAP)
            if (combined.size > 12) {
                val iv = combined.copyOfRange(0, 12)
                val cipher = BiometricCryptoHelper.getDecryptCipherForBiometric(iv)
                if (cipher != null) {
                    cryptoObject = BiometricPrompt.CryptoObject(cipher)
                }
            }

            if (cryptoObject == null) {
                val message = "Biometric key missing or invalidated. Please use Master Password."
                if (onError != null) {
                    onError(message)
                } else {
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            val executor = ContextCompat.getMainExecutor(activity)
            val biometricPrompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        activity.lifecycleScope.launch(Dispatchers.Default) {
                            try {
                                val cipher = result.cryptoObject?.cipher
                                if (cipher != null) {
                                    val encryptedData = combined.copyOfRange(12, combined.size)
                                    val dek = cipher.doFinal(encryptedData)
                                    onSuccess(dek)
                                }
                            } catch (e: Exception) {
                                if (com.example.BuildConfig.DEBUG) e.printStackTrace()
                                withContext(Dispatchers.Main) {
                                    val errorMsg = "Authentication error: ${e.message}"
                                    if (onError != null) {
                                        onError(errorMsg)
                                    } else {
                                        Toast.makeText(activity, errorMsg, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                            onError?.invoke(errString.toString())
                        }
                    }
                }
            )

            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setNegativeButtonText(negativeButtonText)
                .build()

            biometricPrompt.authenticate(promptInfo, cryptoObject)
        }
    }
}
