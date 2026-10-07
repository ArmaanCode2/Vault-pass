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
import java.util.concurrent.atomic.AtomicLong
import com.example.repository.SettingsRepository

class CryptoManager(private val settingsRepository: SettingsRepository) {

    @Volatile
    private var softwareDek: SecretKey? = null
    @Volatile
    private var rawSoftwareDek: ByteArray? = null

    // Changes every time the key is injected or cleared, so work that started under one key can
    // tell the vault was locked (and maybe unlocked again) while it ran.
    private val generation = AtomicLong(0)

    // Key changes and keyState() snapshots: the generation and the key always change together.
    private val stateLock = Any()

    /** The vault key's generation and whether it is loaded, read together. */
    data class KeyState(val generation: Long, val hasKey: Boolean)

    /** Moves on every [injectSoftwareDek] and [clearSoftwareDek]. */
    val keyGeneration: Long
        get() = generation.get()

    /**
     * One consistent snapshot of [keyGeneration] and [hasKey]. Work that must notice a lock while
     * it ran captures this at the start and compares it at the end: equal means same key throughout.
     */
    fun keyState(): KeyState = synchronized(stateLock) {
        KeyState(generation.get(), softwareDek != null)
    }

    /** Loads [dek] as the vault key; see [loadSoftwareDek]. */
    fun injectSoftwareDek(dek: ByteArray) {
        loadSoftwareDek(dek)
    }

    /**
     * Loads [dek] as the vault key. Returns false, changing nothing (same generation), when that
     * exact key is already loaded: re-unlocking an open vault must not look like a lock to work
     * that is running under it.
     */
    fun loadSoftwareDek(dek: ByteArray): Boolean {
        synchronized(stateLock) {
            val current = rawSoftwareDek
            if (current != null && softwareDek != null && java.security.MessageDigest.isEqual(current, dek)) {
                return false
            }
            current?.let { java.util.Arrays.fill(it, 0.toByte()) }
            rawSoftwareDek = dek.clone()
            softwareDek = SecretKeySpec(rawSoftwareDek, "AES")
            generation.incrementAndGet()
            return true
        }
    }

    fun clearSoftwareDek() {
        synchronized(stateLock) {
            rawSoftwareDek?.let { java.util.Arrays.fill(it, 0.toByte()) }
            rawSoftwareDek = null
            softwareDek = null
            generation.incrementAndGet()
        }
    }

    /** A copy of the vault key (the caller zero-fills it), or null while locked. Never a half-cleared array. */
    fun getSoftwareDek(): ByteArray? = synchronized(stateLock) {
        rawSoftwareDek?.clone()
    }

    /** Tests: makes the cipher for [encrypt]; replaced to force an encryption failure. */
    @androidx.annotation.VisibleForTesting
    internal var encryptCipherFactory: () -> Cipher = { Cipher.getInstance(TRANSFORMATION) }

    /** True while the vault key is loaded (the vault is unlocked). */
    fun hasKey(): Boolean = softwareDek != null

    // Legacy AndroidKeyStore has been removed as all data is now encrypted with the software DEK.

    private fun getDecryptCipherForIv(iv: ByteArray): Cipher {
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(128, iv))
        }
    }

    private fun getKey(): SecretKey {
        return softwareDek ?: throw IllegalStateException("CryptoManager attempted to get key while vault is locked (softwareDek is null)")
    }

    /**
     * Encrypts [plainText] with the vault key. Never returns "" for non-empty input: throws
     * [VaultLockedException] while locked, and IllegalStateException if encryption fails.
     */
    fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        val key = softwareDek ?: throw VaultLockedException()
        return try {
            val cipher = encryptCipherFactory().apply {
                init(Cipher.ENCRYPT_MODE, key)
            }
            val iv = cipher.iv
            val encryptedData = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

            val combined = iv + encryptedData
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            if (com.example.BuildConfig.DEBUG) e.printStackTrace()
            throw IllegalStateException("Encryption failed", e)
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
            if (com.example.BuildConfig.DEBUG) e.printStackTrace()
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
                if (com.example.BuildConfig.DEBUG) e.printStackTrace()
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
                val saltStr = Base64.encodeToString(salt, Base64.NO_WRAP)
                
                fun tryDecryptWithKek(kek: ByteArray): String? {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    val secretKey = SecretKeySpec(kek, "AES")
                    return try {
                        val iv = backupData.copyOfRange(16, 16 + 12)
                        val encryptedData = backupData.copyOfRange(16 + 12, backupData.size)
                        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
                        val plainTextBytes = cipher.doFinal(encryptedData)
                        String(plainTextBytes, Charsets.UTF_8)
                    } catch (e: Exception) {
                        if (backupData.size > 16 + 16) {
                            try {
                                val iv16 = backupData.copyOfRange(16, 16 + 16)
                                val encryptedData16 = backupData.copyOfRange(16 + 16, backupData.size)
                                cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv16))
                                val plainTextBytes = cipher.doFinal(encryptedData16)
                                String(plainTextBytes, Charsets.UTF_8)
                            } catch (e2: Exception) {
                                null
                            }
                        } else null
                    }
                }

                val kek = com.example.security.PasswordHashHelper.deriveMasterKey(password, saltStr)
                var result = tryDecryptWithKek(kek)
                if (result == null) {
                    val legacyKek = com.example.security.PasswordHashHelper.deriveLegacyMasterKey(password, saltStr)
                    result = tryDecryptWithKek(legacyKek)
                }
                result
            } catch (e: Exception) {
                if (com.example.BuildConfig.DEBUG) e.printStackTrace()
                null
            }
        }
    }
}
