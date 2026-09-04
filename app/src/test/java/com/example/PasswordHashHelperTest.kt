package com.example

import android.util.Base64
import com.example.security.PasswordHashHelper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PasswordHashHelperTest {

    @Test
    fun pbkdf2_derivationDeterminism() {
        val password = "DeterminismTestPassword123!"
        val salt = PasswordHashHelper.generateSalt()

        val rootKey1 = PasswordHashHelper.deriveRootMasterKey(password, salt, iterations = 10000)
        val rootKey2 = PasswordHashHelper.deriveRootMasterKey(password, salt, iterations = 10000)
        assertArrayEquals("PBKDF2 root derivation must be deterministic", rootKey1, rootKey2)

        val kek1 = PasswordHashHelper.deriveMasterKey(password, salt, iterations = 10000)
        val kek2 = PasswordHashHelper.deriveMasterKey(password, salt, iterations = 10000)
        assertArrayEquals("KEK derivation must be deterministic", kek1, kek2)

        val hash1 = PasswordHashHelper.hashPassword(password, salt, iterations = 10000)
        val hash2 = PasswordHashHelper.hashPassword(password, salt, iterations = 10000)
        assertEquals("Auth hash derivation must be deterministic", hash1, hash2)
    }

    @Test
    fun pbkdf2_saltUniqueness() {
        val salts = (1..50).map { PasswordHashHelper.generateSalt() }.toSet()
        assertEquals("All 50 generated salts must be unique", 50, salts.size)

        val password = "SharedPassword123!"
        val salt1 = PasswordHashHelper.generateSalt()
        val salt2 = PasswordHashHelper.generateSalt()

        val kek1 = PasswordHashHelper.deriveMasterKey(password, salt1, iterations = 1000)
        val kek2 = PasswordHashHelper.deriveMasterKey(password, salt2, iterations = 1000)
        assertFalse("Different salts must produce different KEKs", kek1.contentEquals(kek2))

        val hash1 = PasswordHashHelper.hashPassword(password, salt1, iterations = 1000)
        val hash2 = PasswordHashHelper.hashPassword(password, salt2, iterations = 1000)
        assertNotEquals("Different salts must produce different auth hashes", hash1, hash2)
    }

    @Test
    fun domainSeparation_kekAndAuthHashAreCryptographicallyDistinct() {
        val password = "DomainSeparationMasterKey1!"
        val salt = PasswordHashHelper.generateSalt()

        val kek = PasswordHashHelper.deriveMasterKey(password, salt, iterations = 1000)
        val authHash = PasswordHashHelper.hashPassword(password, salt, iterations = 1000)
        val authHashBytes = Base64.decode(authHash, Base64.NO_WRAP)

        assertFalse("KEK bytes and AuthHash bytes must never match", kek.contentEquals(authHashBytes))
        assertEquals(32, kek.size)
        assertEquals(32, authHashBytes.size)
    }

    @Test
    fun verifyPassword_handlesCorrectAndIncorrectCredentials() {
        val password = "MasterVerificationPassword123!"
        val wrongPassword = "MasterVerificationPassword123"
        val salt = PasswordHashHelper.generateSalt()
        val hash = PasswordHashHelper.hashPassword(password, salt, iterations = 1000)

        assertTrue(
            "Valid credentials must verify successfully",
            PasswordHashHelper.verifyPassword(password, salt, hash, iterations = 1000)
        )

        assertFalse(
            "Incorrect password must fail verification",
            PasswordHashHelper.verifyPassword(wrongPassword, salt, hash, iterations = 1000)
        )

        val differentSalt = PasswordHashHelper.generateSalt()
        assertFalse(
            "Different salt must fail verification",
            PasswordHashHelper.verifyPassword(password, differentSalt, hash, iterations = 1000)
        )
    }

    @Test
    fun verifyPassword_backwardsCompatibilityWithLegacyHash() {
        val password = "LegacyPassword123!"
        val salt = PasswordHashHelper.generateSalt()
        val legacyHash = PasswordHashHelper.hashPasswordLegacy(password, salt, iterations = 1000)

        assertTrue(
            "Legacy hash must be recognized by verifyPassword fallback",
            PasswordHashHelper.verifyPassword(password, salt, legacyHash, iterations = 1000)
        )

        assertFalse(
            "Wrong password against legacy hash must fail",
            PasswordHashHelper.verifyPassword("IncorrectPass", salt, legacyHash, iterations = 1000)
        )
    }
}
