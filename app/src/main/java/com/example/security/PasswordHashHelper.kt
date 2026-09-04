package com.example.security

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object PasswordHashHelper {

    private const val KEY_LENGTH = 256
    const val KEK_DOMAIN = "vaultpass-kek-v1"
    const val AUTH_DOMAIN = "vaultpass-auth-v1"

    fun generateSalt(): String {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        return Base64.encodeToString(salt, Base64.NO_WRAP)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun deriveRootMasterKey(
        password: String,
        saltBase64: String,
        iterations: Int = 100000,
        algorithm: String = "PBKDF2WithHmacSHA256"
    ): ByteArray {
        val salt = Base64.decode(saltBase64, Base64.NO_WRAP)
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance(algorithm)
        return factory.generateSecret(spec).encoded
    }

    fun deriveKek(rootKey: ByteArray): ByteArray {
        return hmacSha256(rootKey, KEK_DOMAIN.toByteArray(Charsets.UTF_8))
    }

    fun deriveAuthHash(rootKey: ByteArray): String {
        val authBytes = hmacSha256(rootKey, AUTH_DOMAIN.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(authBytes, Base64.NO_WRAP)
    }

    fun deriveMasterKey(
        password: String,
        saltBase64: String,
        iterations: Int = 100000,
        algorithm: String = "PBKDF2WithHmacSHA256"
    ): ByteArray {
        val rootKey = deriveRootMasterKey(password, saltBase64, iterations, algorithm)
        try {
            return deriveKek(rootKey)
        } finally {
            Arrays.fill(rootKey, 0.toByte())
        }
    }

    fun hashPassword(
        password: String,
        saltBase64: String,
        iterations: Int = 100000,
        algorithm: String = "PBKDF2WithHmacSHA256"
    ): String {
        val rootKey = deriveRootMasterKey(password, saltBase64, iterations, algorithm)
        try {
            return deriveAuthHash(rootKey)
        } finally {
            Arrays.fill(rootKey, 0.toByte())
        }
    }

    fun deriveLegacyMasterKey(
        password: String,
        saltBase64: String,
        iterations: Int = 100000,
        algorithm: String = "PBKDF2WithHmacSHA256"
    ): ByteArray {
        return deriveRootMasterKey(password, saltBase64, iterations, algorithm)
    }

    fun hashPasswordLegacy(
        password: String,
        saltBase64: String,
        iterations: Int = 100000,
        algorithm: String = "PBKDF2WithHmacSHA256"
    ): String {
        val legacyKey = deriveLegacyMasterKey(password, saltBase64, iterations, algorithm)
        try {
            return Base64.encodeToString(legacyKey, Base64.NO_WRAP)
        } finally {
            Arrays.fill(legacyKey, 0.toByte())
        }
    }

    fun verifyPassword(
        password: String,
        saltBase64: String,
        hashBase64: String,
        iterations: Int = 100000,
        algorithm: String = "PBKDF2WithHmacSHA256"
    ): Boolean {
        val rootKey = deriveRootMasterKey(password, saltBase64, iterations, algorithm)
        try {
            val targetBytes = Base64.decode(hashBase64, Base64.NO_WRAP)

            // 1. Verify against new domain-separated auth hash
            val calculatedAuthHashBytes = hmacSha256(rootKey, AUTH_DOMAIN.toByteArray(Charsets.UTF_8))
            if (MessageDigest.isEqual(calculatedAuthHashBytes, targetBytes)) {
                return true
            }

            // 2. Fallback: verify against legacy hash
            return MessageDigest.isEqual(rootKey, targetBytes)
        } finally {
            Arrays.fill(rootKey, 0.toByte())
        }
    }
}
