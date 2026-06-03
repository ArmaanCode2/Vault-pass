package com.example.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature

import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object BiometricCryptoHelper {
    private const val KEY_NAME = "biometric_auth_key"
    private const val KEY_NAME_AES = "biometric_auth_aes_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    fun generateBiometricKey() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        // Generate a new key pair
        val keyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE
        )

        val builder = KeyGenParameterSpec.Builder(
            KEY_NAME,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
        .setDigests(KeyProperties.DIGEST_SHA256)
        .setUserAuthenticationRequired(true)
        .setInvalidatedByBiometricEnrollment(true) // Invalidates if a new fingerprint is added

        keyPairGenerator.initialize(builder.build())
        keyPairGenerator.generateKeyPair()
    }

    fun generateBiometricAesKey() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        if (!keyStore.containsAlias(KEY_NAME_AES)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val builder = KeyGenParameterSpec.Builder(
                KEY_NAME_AES,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)

            keyGenerator.init(builder.build())
            keyGenerator.generateKey()
        }
    }

    fun getEncryptCipherForBiometric(): Cipher? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)

            val secretKey = keyStore.getKey(KEY_NAME_AES, null) as? SecretKey ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            cipher
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun getDecryptCipherForBiometric(iv: ByteArray): Cipher? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)

            val secretKey = keyStore.getKey(KEY_NAME_AES, null) as? SecretKey ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            cipher
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun getSignatureForBiometric(): Signature? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)

            val privateKey = keyStore.getKey(KEY_NAME, null) as? PrivateKey
                ?: return null

            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initSign(privateKey)
            signature
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun verifySignature(challenge: ByteArray, signatureBytes: ByteArray): Boolean {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)

            val cert = keyStore.getCertificate(KEY_NAME) ?: return false
            val publicKey = cert.publicKey

            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initVerify(publicKey)
            signature.update(challenge)
            signature.verify(signatureBytes)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
