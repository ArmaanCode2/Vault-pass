package com.example

import com.example.security.PasswordHashHelper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CryptoRoundtripTest {

    @Test
    fun `domain-separated KEK and AuthHash are different`() {
        val password = "TestPassword123!"
        val salt = PasswordHashHelper.generateSalt()
        val kek = PasswordHashHelper.deriveMasterKey(password, salt)
        val authHash = PasswordHashHelper.hashPassword(password, salt)

        // KEK is raw bytes, authHash is Base64-encoded HMAC output
        // They MUST be different (domain separation)
        val kekBase64 = android.util.Base64.encodeToString(kek, android.util.Base64.NO_WRAP)
        assertNotEquals(kekBase64, authHash)
    }

    @Test
    fun `verifyPassword succeeds with correct password`() {
        val password = "MySecurePassword!"
        val salt = PasswordHashHelper.generateSalt()
        val hash = PasswordHashHelper.hashPassword(password, salt)

        assertTrue(PasswordHashHelper.verifyPassword(password, salt, hash))
    }

    @Test
    fun `verifyPassword fails with wrong password`() {
        val password = "MySecurePassword!"
        val salt = PasswordHashHelper.generateSalt()
        val hash = PasswordHashHelper.hashPassword(password, salt)

        assertFalse(PasswordHashHelper.verifyPassword("WrongPassword", salt, hash))
    }
}
