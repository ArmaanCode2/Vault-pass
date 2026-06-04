package com.example.security

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PasswordHashHelper {

    private const val KEY_LENGTH = 256

    fun generateSalt(): String {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        return Base64.encodeToString(salt, Base64.NO_WRAP)
    }

    fun deriveMasterKey(password: String, saltBase64: String, iterations: Int = 100000, algorithm: String = "PBKDF2WithHmacSHA256"): ByteArray {
        val salt = Base64.decode(saltBase64, Base64.NO_WRAP)
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance(algorithm)
        return factory.generateSecret(spec).encoded
    }

    fun hashPassword(password: String, saltBase64: String, iterations: Int = 100000, algorithm: String = "PBKDF2WithHmacSHA256"): String {
        val salt = Base64.decode(saltBase64, Base64.NO_WRAP)
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance(algorithm)
        val hash = factory.generateSecret(spec).encoded
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    fun verifyPassword(password: String, saltBase64: String, hashBase64: String, iterations: Int = 100000, algorithm: String = "PBKDF2WithHmacSHA256"): Boolean {
        val calculatedHash = hashPassword(password, saltBase64, iterations, algorithm)
        return MessageDigest.isEqual(
            Base64.decode(calculatedHash, Base64.NO_WRAP),
            Base64.decode(hashBase64, Base64.NO_WRAP)
        )
    }
}
