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

    private var softwareDek: SecretKey? = null

    fun injectSoftwareDek(dek: ByteArray) {
        softwareDek = SecretKeySpec(dek, "AES")
    }

    fun clearSoftwareDek() {
        softwareDek = null
    }

    fun getSoftwareDek(): ByteArray? {
        return softwareDek?.encoded
    }

    // Legacy AndroidKeyStore has been removed as all data is now encrypted with the software DEK.

    private val encryptCipher get() = Cipher.getInstance(TRANSFORMATION).apply {
        init(Cipher.ENCRYPT_MODE, getKey())
    }

    private fun getDecryptCipherForIv(iv: ByteArray): Cipher {
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(128, iv))
        }
    }

    private fun getKey(): SecretKey {
        return softwareDek ?: throw IllegalStateException("CryptoManager attempted to get key while vault is locked (softwareDek is null)")
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

    fun decrypt(encryptedText: String): String? {
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
            null
        }
    }

    companion object {
        private const val ALIAS = "vaultpass_keys"
        private const val ALGORITHM = KeyProperties.KEY_ALGORITHM_AES
        private const val BLOCK_MODE = KeyProperties.BLOCK_MODE_GCM
        private const val PADDING = KeyProperties.ENCRYPTION_PADDING_NONE
        private const val TRANSFORMATION = "$ALGORITHM/$BLOCK_MODE/$PADDING"

        fun wrapDekWithKek(dek: ByteArray, kek: ByteArray): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val secretKey = SecretKeySpec(kek, "AES")
            val iv = ByteArray(12)
            SecureRandom().nextBytes(iv)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            val encryptedData = cipher.doFinal(dek)
            val combined = iv + encryptedData
            return Base64.encodeToString(combined, Base64.NO_WRAP)
        }

        fun unwrapDekWithKek(wrappedBase64: String, kek: ByteArray): ByteArray? {
            return try {
                val combined = Base64.decode(wrappedBase64, Base64.NO_WRAP)
                val encryptedDataLength = 48 // 32 bytes DEK + 16 bytes GCM Tag
                if (combined.size <= encryptedDataLength) return null
                val ivLength = combined.size - encryptedDataLength
                val iv = combined.copyOfRange(0, ivLength)
                val encryptedData = combined.copyOfRange(ivLength, combined.size)
                
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val secretKey = SecretKeySpec(kek, "AES")
                cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
                cipher.doFinal(encryptedData)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

        fun encryptBackup(payload: String, password: String): ByteArray {
            val salt = ByteArray(16)
            SecureRandom().nextBytes(salt)
            val kek = com.example.security.PasswordHashHelper.deriveMasterKey(password, Base64.encodeToString(salt, Base64.NO_WRAP))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val secretKey = SecretKeySpec(kek, "AES")
            val iv = ByteArray(12)
            SecureRandom().nextBytes(iv)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            val encryptedData = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
            return salt + iv + encryptedData
        }

        fun decryptBackup(backupData: ByteArray, password: String): String? {
            return try {
                if (backupData.size <= 16 + 12) return null
                val salt = backupData.copyOfRange(0, 16)
                
                val kek = com.example.security.PasswordHashHelper.deriveMasterKey(password, Base64.encodeToString(salt, Base64.NO_WRAP))
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val secretKey = SecretKeySpec(kek, "AES")

                // Try 12-byte IV first
                try {
                    val iv = backupData.copyOfRange(16, 16 + 12)
                    val encryptedData = backupData.copyOfRange(16 + 12, backupData.size)
                    cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
                    val plainTextBytes = cipher.doFinal(encryptedData)
                    return String(plainTextBytes, Charsets.UTF_8)
                } catch (e: Exception) {
                    // Fallback to 16-byte IV
                    if (backupData.size > 16 + 16) {
                        val iv16 = backupData.copyOfRange(16, 16 + 16)
                        val encryptedData16 = backupData.copyOfRange(16 + 16, backupData.size)
                        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv16))
                        val plainTextBytes = cipher.doFinal(encryptedData16)
                        return String(plainTextBytes, Charsets.UTF_8)
                    }
                    throw e
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
}
