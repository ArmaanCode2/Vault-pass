package com.example

import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.example.domain.security.SecurityAnalyzer
import com.example.security.CryptoManager
import com.example.security.PasswordHashHelper
import com.example.ui.AuthResult
import com.example.ui.VaultViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.SecureRandom

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecurityHardeningTest {

    private lateinit var app: VaultPassApplication
    private lateinit var viewModel: VaultViewModel

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        viewModel = VaultViewModel(app.container.vaultRepository, app.container.settingsRepository)
    }

    @Test
    fun domainSeparation_kekAndAuthHashAreCryptographicallyDistinct() {
        val password = "StrongMasterPassword123!"
        val salt = PasswordHashHelper.generateSalt()

        val kek = PasswordHashHelper.deriveMasterKey(password, salt)
        val authHashBase64 = PasswordHashHelper.hashPassword(password, salt)
        val authHashBytes = Base64.decode(authHashBase64, Base64.NO_WRAP)

        // Ensure raw KEK bytes != AuthHash bytes
        assertFalse("KEK and AuthHash must not be identical", kek.contentEquals(authHashBytes))

        // Create a random DEK and wrap with KEK
        val rawDek = ByteArray(32)
        SecureRandom().nextBytes(rawDek)
        val wrappedDek = CryptoManager.wrapDekWithKek(rawDek, kek)

        // Attempting to unwrap with AuthHash bytes must fail (return null)
        val unwrapAttempt = CryptoManager.unwrapDekWithKek(wrappedDek, authHashBytes)
        assertNull("Unwrapping DEK with AuthHash instead of KEK must fail", unwrapAttempt)
    }

    @Test
    fun legacyVaultMigration_transparentlyUpgradesToDomainSeparatedKeys() = runBlocking {
        val password = "LegacyPassword123!"
        val salt = PasswordHashHelper.generateSalt()
        val settings = app.container.settingsRepository
        val iterations = com.example.security.SecurityPolicy.CURRENT_KDF_ITERATIONS

        // 1. Emulate legacy setup: KEK and Hash both derived directly without domain separation
        val legacyKek = PasswordHashHelper.deriveLegacyMasterKey(password, salt, iterations)
        val legacyHash = PasswordHashHelper.hashPasswordLegacy(password, salt, iterations)

        val originalDek = ByteArray(32)
        SecureRandom().nextBytes(originalDek)
        val legacyWrappedDek = CryptoManager.wrapDekWithKek(originalDek, legacyKek)

        // Store legacy vault state
        settings.saveDekMpWrappedSync(legacyWrappedDek)
        settings.saveMasterPasswordAndKdfMetadata(legacyHash, salt, 1, iterations, "PBKDF2WithHmacSHA256")

        // 2. Login via VaultViewModel with the password
        val result = viewModel.unlockWithPassword(password)
        assertEquals("Legacy unlock should succeed", AuthResult.SUCCESS, result)
        assertTrue("Vault should be unlocked", viewModel.isUnlocked.first())

        // 3. Verify seamless migration:
        // Stored wrapped DEK should now be unwrappable with the new domain-separated KEK
        val currentWrappedDek = settings.getDekMpWrappedSync()
        assertNotNull("Wrapped DEK must exist", currentWrappedDek)

        val newKek = PasswordHashHelper.deriveMasterKey(password, salt, iterations)
        val unwrappedDek = CryptoManager.unwrapDekWithKek(currentWrappedDek!!, newKek)
        assertNotNull("Unwrapping with domain-separated KEK must succeed", unwrappedDek)
        assertArrayEquals("Unwrapped DEK must match original DEK", originalDek, unwrappedDek)

        // Unwrapping with legacy KEK must now fail
        val unwrapWithLegacy = CryptoManager.unwrapDekWithKek(currentWrappedDek, legacyKek)
        assertNull("Wrapped DEK should no longer be unwrappable with legacy KEK", unwrapWithLegacy)

        // Stored password hash must now match domain-separated AuthHash
        val expectedNewAuthHash = PasswordHashHelper.hashPassword(password, salt, iterations)
        val currentStoredHash = settings.masterPasswordHash.first()
        assertEquals("Stored master password hash must be migrated to domain-separated AuthHash", expectedNewAuthHash, currentStoredHash)
    }

    @Test
    fun cryptoManagerMemoryHygiene_zerosDekOnClear() {
        val cryptoManager = app.container.cryptoManager
        val testDek = ByteArray(32)
        SecureRandom().nextBytes(testDek)

        cryptoManager.injectSoftwareDek(testDek)
        val retrieved = cryptoManager.getSoftwareDek()
        assertNotNull("Software DEK should be present", retrieved)
        assertArrayEquals("Software DEK must match injected bytes", testDek, retrieved)

        // Clear and verify DEK is wiped
        cryptoManager.clearSoftwareDek()
        assertNull("Software DEK must be null after clearSoftwareDek()", cryptoManager.getSoftwareDek())
    }

    @Test
    fun masterPasswordHygiene_rejectsWeakPasswords() {
        val weak1 = "12345678"
        val weak2 = "password123"
        val weak3 = "qwerty"
        val strong = "Tr0ub4dor&3VaultPass!"

        assertTrue("12345678 should score < 40", SecurityAnalyzer.scorePassword(weak1) < 40)
        assertTrue("password123 should score < 40", SecurityAnalyzer.scorePassword(weak2) < 40)
        assertTrue("qwerty should score < 40", SecurityAnalyzer.scorePassword(weak3) < 40)

        val reasons1 = SecurityAnalyzer.getWeaknessReasons(weak1)
        assertTrue("Weakness reasons should be returned for 12345678", reasons1.isNotEmpty())

        val reasonsAdmin = SecurityAnalyzer.getWeaknessReasons("adminadmin")
        assertTrue("Weakness reasons should be returned for adminadmin", reasonsAdmin.isNotEmpty())

        assertTrue("Strong password should score >= 40", SecurityAnalyzer.scorePassword(strong) >= 40)
    }

    @Test
    fun manifestSecurity_cloudBackupDisabledAndNoQueryAllPackages() {
        val candidatePaths = listOf(
            File("app/src/main/AndroidManifest.xml"),
            File("AndroidManifest.xml"),
            File("../app/src/main/AndroidManifest.xml"),
            File("e:/antigravity/password manager/vaultpass/app/src/main/AndroidManifest.xml")
        )
        val manifestFile = candidatePaths.firstOrNull { it.exists() }
        assertNotNull("AndroidManifest.xml should be found", manifestFile)
        val content = manifestFile!!.readText()

        assertTrue("android:allowBackup must be false", content.contains("android:allowBackup=\"false\""))
        assertFalse("QUERY_ALL_PACKAGES must be removed", content.contains("android.permission.QUERY_ALL_PACKAGES"))
    }

    @Test
    fun backupExclusionRules_protectSensitiveVaultData() {
        val backupRulesCandidates = listOf(
            File("app/src/main/res/xml/backup_rules.xml"),
            File("res/xml/backup_rules.xml"),
            File("e:/antigravity/password manager/vaultpass/app/src/main/res/xml/backup_rules.xml")
        )
        val backupRulesFile = backupRulesCandidates.firstOrNull { it.exists() }
        assertNotNull("backup_rules.xml should be found", backupRulesFile)
        val backupContent = backupRulesFile!!.readText()

        assertTrue("backup_rules must exclude database", backupContent.contains("path=\"vaultpass_database\""))
        assertTrue("backup_rules must exclude sync prefs", backupContent.contains("path=\"vaultpass_sync_prefs.xml\""))
        assertTrue("backup_rules must exclude datastore", backupContent.contains("path=\"datastore/settings.preferences_pb\""))

        val dataExtractionCandidates = listOf(
            File("app/src/main/res/xml/data_extraction_rules.xml"),
            File("res/xml/data_extraction_rules.xml"),
            File("e:/antigravity/password manager/vaultpass/app/src/main/res/xml/data_extraction_rules.xml")
        )
        val dataExtractionFile = dataExtractionCandidates.firstOrNull { it.exists() }
        assertNotNull("data_extraction_rules.xml should be found", dataExtractionFile)
        val extractionContent = dataExtractionFile!!.readText()

        assertTrue("data_extraction_rules must exclude database in cloud-backup", extractionContent.contains("path=\"vaultpass_database\""))
        assertTrue("data_extraction_rules must exclude sync prefs", extractionContent.contains("path=\"vaultpass_sync_prefs.xml\""))
        assertTrue("data_extraction_rules must exclude datastore", extractionContent.contains("path=\"datastore/settings.preferences_pb\""))
    }
}
