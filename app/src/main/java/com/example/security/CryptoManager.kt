package com.example.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom
import com.example.repository.SettingsRepository

class CryptoManager(private val settingsRepository: SettingsRepository) {

    private var useFallback = false
    private var fallbackKey: SecretKey? = null

    private val keyStore: KeyStore? = try {
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
        }
    } catch (e: Exception) {
        e.printStackTrace()
        useFallback = true
        null
    }

    private val encryptCipher get() = Cipher.getInstance(TRANSFORMATION).apply {
        init(Cipher.ENCRYPT_MODE, getKey())
    }

    private fun getDecryptCipherForIv(iv: ByteArray): Cipher {
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(128, iv))
        }
    }

    private fun getKey(): SecretKey {
        if (useFallback) {
            if (fallbackKey == null) {
                val savedKey = settingsRepository.getFallbackKeySync()
                if (savedKey != null) {
                    val bytes = Base64.decode(savedKey, Base64.NO_WRAP)
                    fallbackKey = SecretKeySpec(bytes, "AES")
                } else {
                    val bytes = ByteArray(32)
                    SecureRandom().nextBytes(bytes)
                    settingsRepository.saveFallbackKeySync(Base64.encodeToString(bytes, Base64.NO_WRAP))
                    fallbackKey = SecretKeySpec(bytes, "AES")
                }
            }
            return fallbackKey!!
        }

        return try {
            val existingKey = keyStore?.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry
            existingKey?.secretKey ?: createKey()
        } catch (e: Exception) {
            e.printStackTrace()
            // If Keystore throws during getting key, fallback
            useFallback = true
            
            val savedKey = settingsRepository.getFallbackKeySync()
            if (savedKey != null) {
                val bytes = Base64.decode(savedKey, Base64.NO_WRAP)
                fallbackKey = SecretKeySpec(bytes, "AES")
            } else {
                val bytes = ByteArray(32)
                SecureRandom().nextBytes(bytes)
                settingsRepository.saveFallbackKeySync(Base64.encodeToString(bytes, Base64.NO_WRAP))
                fallbackKey = SecretKeySpec(bytes, "AES")
            }
            return fallbackKey!!
        }
    }

    private fun createKey(): SecretKey {
        return try {
            KeyGenerator.getInstance(ALGORITHM, "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(
                        ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(BLOCK_MODE)
                        .setEncryptionPaddings(PADDING)
                        .setUserAuthenticationRequired(false)
                        .setRandomizedEncryptionRequired(true)
                        .setKeySize(256)
                        .build()
                )
            }.generateKey()
        } catch (e: Exception) {
            e.printStackTrace()
            useFallback = true
            
            val savedKey = settingsRepository.getFallbackKeySync()
            if (savedKey != null) {
                val bytes = Base64.decode(savedKey, Base64.NO_WRAP)
                fallbackKey = SecretKeySpec(bytes, "AES")
            } else {
                val bytes = ByteArray(32)
                SecureRandom().nextBytes(bytes)
                settingsRepository.saveFallbackKeySync(Base64.encodeToString(bytes, Base64.NO_WRAP))
                fallbackKey = SecretKeySpec(bytes, "AES")
            }
            return fallbackKey!!
        }
    }

    fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        return try {
            val cipher = encryptCipher
            val iv = cipher.iv
            val encryptedData = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            
            val combined = iv + encryptedData
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    fun decrypt(encryptedText: String): String {
        if (encryptedText.isEmpty()) return ""
        return try {
            val combined = Base64.decode(encryptedText, Base64.NO_WRAP)
            if (combined.size < 12) throw IllegalArgumentException("Invalid payload length")
            
            val iv = combined.copyOfRange(0, 12)
            val encryptedData = combined.copyOfRange(12, combined.size)
            
            val cipher = getDecryptCipherForIv(iv)
            val plainTextBytes = cipher.doFinal(encryptedData)
            String(plainTextBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    companion object {
        private const val ALIAS = "vaultpass_keys"
        private const val ALGORITHM = KeyProperties.KEY_ALGORITHM_AES
        private const val BLOCK_MODE = KeyProperties.BLOCK_MODE_GCM
        private const val PADDING = KeyProperties.ENCRYPTION_PADDING_NONE
        private const val TRANSFORMATION = "$ALGORITHM/$BLOCK_MODE/$PADDING"
    }
}
