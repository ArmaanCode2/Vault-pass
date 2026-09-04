package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.security.CryptoManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.SecureRandom
import android.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CryptoManagerTest {

    private lateinit var cryptoManager: CryptoManager
    private val rawDek = ByteArray(32)

    @Before
    fun setUp() {
        val app: VaultPassApplication = ApplicationProvider.getApplicationContext()
        cryptoManager = CryptoManager(app.container.settingsRepository)
        SecureRandom().nextBytes(rawDek)
        cryptoManager.injectSoftwareDek(rawDek)
    }

    @Test
    fun aesGcm_encryptDecrypt_roundtripSuccess() {
        val plainText = "SuperSecret_P@ssw0rd_2026!"
        val cipherText = cryptoManager.encrypt(plainText)
        assertNotNull(cipherText)
        assertTrue(cipherText.isNotEmpty())
        assertNotEquals(plainText, cipherText)

        val decrypted = cryptoManager.decrypt(cipherText)
        assertEquals(plainText, decrypted)
    }

    @Test
    fun aesGcm_encryptDecrypt_handlesEmptyString() {
        assertEquals("", cryptoManager.encrypt(""))
        assertEquals("", cryptoManager.decrypt(""))
    }

    @Test
    fun dekWrappingAndUnwrapping_roundtripSuccess() {
        val kek = ByteArray(32)
        val dek = ByteArray(32)
        SecureRandom().nextBytes(kek)
        SecureRandom().nextBytes(dek)

        val wrapped = CryptoManager.wrapDekWithKek(dek, kek)
        assertNotNull(wrapped)
        assertTrue(wrapped.isNotEmpty())

        val unwrapped = CryptoManager.unwrapDekWithKek(wrapped, kek)
        assertNotNull(unwrapped)
        assertArrayEquals(dek, unwrapped)
    }

    @Test
    fun dekUnwrapping_withWrongKek_returnsNull() {
        val kek1 = ByteArray(32)
        val kek2 = ByteArray(32)
        val dek = ByteArray(32)
        SecureRandom().nextBytes(kek1)
        SecureRandom().nextBytes(kek2)
        SecureRandom().nextBytes(dek)

        val wrapped = CryptoManager.wrapDekWithKek(dek, kek1)
        val unwrappedWithWrongKek = CryptoManager.unwrapDekWithKek(wrapped, kek2)
        assertNull(unwrappedWithWrongKek)
    }

    @Test
    fun backupEncryptionAndDecryption_roundtripSuccess() {
        val payload = """{"version":1,"entries":[{"title":"GitHub","password":"secret"}]}"""
        val password = "StrongBackupMasterPassword123!"

        val encryptedBytes = CryptoManager.encryptBackup(payload, password)
        assertNotNull(encryptedBytes)
        assertTrue(encryptedBytes.size > 28) // salt(16) + iv(12) + ciphertext

        val decrypted = CryptoManager.decryptBackup(encryptedBytes, password)
        assertEquals(payload, decrypted)
    }

    @Test
    fun backupDecryption_withWrongPassword_returnsNull() {
        val payload = """{"version":1,"entries":[]}"""
        val encryptedBytes = CryptoManager.encryptBackup(payload, "CorrectPassword123!")
        val decrypted = CryptoManager.decryptBackup(encryptedBytes, "WrongPassword456!")
        assertNull(decrypted)
    }

    @Test
    fun tamperedCiphertext_rejected() {
        val plainText = "TamperResistanceTest"
        val cipherText = cryptoManager.encrypt(plainText)
        val rawBytes = Base64.decode(cipherText, Base64.NO_WRAP)

        // 1. Tamper IV (first byte)
        val tamperedIvBytes = rawBytes.clone()
        tamperedIvBytes[0] = (tamperedIvBytes[0].toInt() xor 0xFF).toByte()
        val tamperedIvBase64 = Base64.encodeToString(tamperedIvBytes, Base64.NO_WRAP)
        assertNull(cryptoManager.decrypt(tamperedIvBase64))

        // 2. Tamper Tag (last byte)
        val tamperedTagBytes = rawBytes.clone()
        tamperedTagBytes[tamperedTagBytes.size - 1] = (tamperedTagBytes[tamperedTagBytes.size - 1].toInt() xor 0xFF).toByte()
        val tamperedTagBase64 = Base64.encodeToString(tamperedTagBytes, Base64.NO_WRAP)
        assertNull(cryptoManager.decrypt(tamperedTagBase64))

        // 3. Truncated payload (< 12 bytes)
        val truncatedBase64 = Base64.encodeToString(ByteArray(8), Base64.NO_WRAP)
        assertNull(cryptoManager.decrypt(truncatedBase64))
    }

    @Test
    fun lifecycle_clearSoftwareDek_clearsKeyMaterial() {
        val retrievedBefore = cryptoManager.getSoftwareDek()
        assertNotNull(retrievedBefore)
        assertArrayEquals(rawDek, retrievedBefore)

        cryptoManager.clearSoftwareDek()
        assertNull(cryptoManager.getSoftwareDek())

        val encrypted = cryptoManager.encrypt("ShouldFailWhenLocked")
        assertEquals("", encrypted)

        val decrypted = cryptoManager.decrypt("anyCiphertext")
        assertNull(decrypted)
    }
}
