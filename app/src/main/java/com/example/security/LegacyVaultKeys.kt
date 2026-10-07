package com.example.security

import android.util.Base64
import com.example.repository.SettingsRepository
import java.security.KeyStore
import java.security.KeyStoreException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A legacy key exists but can't be used, or a legacy field failed to decrypt for a reason other
 * than a wrong key. The upgrade must stop and change nothing: retrying later may work, while
 * migrating only what the other key opens would leave the rest behind.
 */
class LegacyKeyUnusableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Read-only access to the keys that encrypted vault entries in builds before 2026-06-03 (before
 * the software DEK): the AndroidKeyStore key "vaultpass_keys" and the fallback AES key stored
 * base64 under "fallback_key" in the "vaultpass_sync_prefs" SharedPreferences. Those builds
 * switched between the two at runtime, so one vault can hold fields under either key.
 *
 * Never creates, replaces or deletes either key. [source] is injectable for tests.
 */
class LegacyVaultKeys(private val source: () -> List<SecretKey>) {

    /**
     * Every legacy key present on this device, possibly none. Throws (never "no key") when a key
     * exists but can't be loaded, e.g. [LegacyKeyUnusableException].
     */
    fun load(): List<SecretKey> = source()

    companion object {
        const val KEYSTORE_ALIAS = "vaultpass_keys"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val TAG_BITS = 128

        /** The Keystore key first (the old default), then the fallback key. */
        fun forDevice(
            settingsRepository: SettingsRepository,
            keystoreKey: () -> SecretKey? = ::readKeystoreKey
        ): LegacyVaultKeys = LegacyVaultKeys {
            listOfNotNull(keystoreKey(), fallbackKeyFrom(settingsRepository.getLegacyFallbackKeySync()))
        }

        /**
         * The Keystore key if the alias exists, null if it doesn't or this device has no Android
         * Keystore at all. Throws [LegacyKeyUnusableException] when the Keystore can't be read or the
         * alias exists but its key can't be loaded. Never generates a key (device-only path).
         */
        fun readKeystoreKey(): SecretKey? {
            val keyStore = try {
                KeyStore.getInstance("AndroidKeyStore")
            } catch (e: KeyStoreException) {
                return null // No Android Keystore provider (not a real device): no legacy Keystore key.
            }
            try {
                keyStore.load(null)
            } catch (e: Exception) {
                throw LegacyKeyUnusableException("Android Keystore can't be read", e)
            }
            return keystoreKeyFrom(keyStore)
        }

        /** The [KEYSTORE_ALIAS] key of a loaded [keyStore]; see [readKeystoreKey]. */
        fun keystoreKeyFrom(keyStore: KeyStore): SecretKey? {
            val exists = try {
                keyStore.containsAlias(KEYSTORE_ALIAS)
            } catch (e: Exception) {
                throw LegacyKeyUnusableException("Can't tell whether the legacy Keystore key exists", e)
            }
            if (!exists) return null
            val entry = try {
                keyStore.getEntry(KEYSTORE_ALIAS, null)
            } catch (e: Exception) {
                throw LegacyKeyUnusableException("The legacy Keystore key exists but can't be loaded", e)
            }
            return (entry as? KeyStore.SecretKeyEntry)?.secretKey
                ?: throw LegacyKeyUnusableException("The legacy Keystore entry is not a usable secret key")
        }

        /** Parses the stored fallback key; null if absent or not an AES key. */
        fun fallbackKeyFrom(base64: String?): SecretKey? {
            if (base64.isNullOrEmpty()) return null
            return try {
                val bytes = Base64.decode(base64, Base64.NO_WRAP)
                if (bytes.size == 16 || bytes.size == 24 || bytes.size == 32) SecretKeySpec(bytes, "AES") else null
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        /**
         * Decrypts one legacy field (base64 of iv(12) || ciphertext+tag) with the first of [keys]
         * that authenticates it. "" stays "": old builds stored "" for empty values and on errors.
         * Null when no key authenticates it (wrong key) or the value is malformed.
         * Throws [LegacyKeyUnusableException] on any other failure (an unusable key, a Keystore
         * error): that says nothing about whether the field is readable, so the caller must stop.
         */
        fun decryptField(stored: String, keys: List<SecretKey>): String? {
            if (stored.isEmpty()) return ""
            val combined = try {
                Base64.decode(stored, Base64.NO_WRAP)
            } catch (e: IllegalArgumentException) {
                return null
            }
            if (combined.size < IV_LENGTH + TAG_BITS / 8) return null
            val iv = combined.copyOfRange(0, IV_LENGTH)
            val encrypted = combined.copyOfRange(IV_LENGTH, combined.size)
            for (key in keys) {
                try {
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
                    return String(cipher.doFinal(encrypted), Charsets.UTF_8)
                } catch (e: Exception) {
                    // Only a failed tag check means "wrong key": then try the next one.
                    if (!isAuthenticationFailure(e)) {
                        throw LegacyKeyUnusableException("Legacy field decryption failed", e)
                    }
                }
            }
            return null
        }

        /** A GCM tag mismatch, thrown directly or wrapped (Keystore providers may wrap it). */
        fun isAuthenticationFailure(e: Throwable): Boolean {
            var current: Throwable? = e
            var depth = 0
            while (current != null && depth < 8) {
                if (current is AEADBadTagException) return true
                current = current.cause
                depth++
            }
            return false
        }
    }
}
