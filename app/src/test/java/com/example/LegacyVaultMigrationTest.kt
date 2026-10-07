package com.example

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import com.example.data.models.VaultEntryEntity
import com.example.domain.models.CustomField
import com.example.repository.MasterHashRead
import com.example.repository.SettingsRepository
import com.example.security.CryptoManager
import com.example.security.LegacyKeyUnusableException
import com.example.security.LegacyVaultKeys
import com.example.security.LegacyVaultMigration
import com.example.security.PasswordHashHelper
import com.example.security.SecurityPolicy
import com.example.security.VaultSessionManager
import com.example.ui.AuthResult
import com.example.ui.SetupResult
import com.example.ui.VaultLaunchState
import com.example.ui.VaultViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** F18 (legacy-vault upgrade) and F17 (setup never overwrites a vault). Keystore path is device-only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyVaultMigrationTest {

    private lateinit var app: VaultPassApplication
    private val settings get() = app.container.settingsRepository
    private val dao get() = app.container.appDatabase.vaultDao()
    private val prefs get() = app.getSharedPreferences("vaultpass_sync_prefs", Context.MODE_PRIVATE)

    private val password = "Legacy-Horse-42!"
    private lateinit var salt: String
    // Deliberately not the 100k default: migration must use the stored KDF parameters.
    private val iterations = 2000
    private val algorithm = "PBKDF2WithHmacSHA256"

    @Before
    fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        VaultSessionManager.resetForTesting()
        withContext(Dispatchers.IO) { app.container.appDatabase.clearAllTables() }
        app.container.vaultRepository.clearSoftwareDek()
        prefs.edit().clear().commit()
        settings.resetFailedAttempts()
        salt = PasswordHashHelper.generateSalt()
        // Pre-2026-06 builds stored the raw PBKDF2 output as master_hash.
        settings.saveMasterPasswordAndKdfMetadata(
            PasswordHashHelper.hashPasswordLegacy(password, salt, iterations, algorithm), salt, 1, iterations, algorithm
        )
    }

    @After
    fun tearDown() = runBlocking {
        // No background DataStore write of this test may overlap the next one.
        viewModels.forEach { it.awaitSecurityStatsRecalculation() }
        app.container.vaultRepository.clearSoftwareDek()
        VaultSessionManager.resetForTesting()
        settings.resetFailedAttempts()
    }

    // --- helpers ---

    private fun newAesKey(): SecretKey = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")

    private fun storeFallbackKey(key: SecretKey) {
        prefs.edit().putString("fallback_key", Base64.encodeToString(key.encoded, Base64.NO_WRAP)).commit()
    }

    /** Old CryptoManager format: base64(iv(12) || ciphertext+tag), "" for "". */
    private fun legacyEncrypt(plain: String, key: SecretKey): String {
        if (plain.isEmpty()) return ""
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return Base64.encodeToString(iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private data class Plain(
        val title: String,
        val username: String = "",
        val password: String = "",
        val website: String = "",
        val notes: String = "",
        val category: String = "Personal",
        val tags: List<String> = emptyList(),
        val custom: List<CustomField> = emptyList()
    )

    /** One key per field, in column order (title, username, password, website, notes, category, tags, custom). */
    private suspend fun insertLegacyRow(
        plain: Plain,
        keys: List<SecretKey>,
        isDeleted: Boolean = false,
        favorite: Boolean = false
    ): VaultEntryEntity {
        val k = if (keys.size == 1) List(8) { keys[0] } else keys
        val entity = VaultEntryEntity(
            titleEnc = legacyEncrypt(plain.title, k[0]),
            usernameEnc = legacyEncrypt(plain.username, k[1]),
            passwordEnc = legacyEncrypt(plain.password, k[2]),
            websiteEnc = legacyEncrypt(plain.website, k[3]),
            notesEnc = legacyEncrypt(plain.notes, k[4]),
            categoryEnc = legacyEncrypt(plain.category, k[5]),
            tagsEnc = legacyEncrypt(Json.encodeToString(plain.tags), k[6]),
            customFieldsEnc = legacyEncrypt(Json.encodeToString(plain.custom), k[7]),
            isFavorite = favorite,
            timestamp = System.currentTimeMillis(),
            isDeleted = isDeleted,
            deletedAt = if (isDeleted) System.currentTimeMillis() else null,
            syncId = UUID.randomUUID().toString()
        )
        dao.insertEntry(entity)
        return dao.getAllEntitiesIncludingDeletedSync().first { it.syncId == entity.syncId }
    }

    private suspend fun insertRawRow(entity: VaultEntryEntity): VaultEntryEntity {
        dao.insertEntry(entity)
        return dao.getAllEntitiesIncludingDeletedSync().first { it.syncId == entity.syncId }
    }

    private val viewModels = mutableListOf<VaultViewModel>()

    private fun viewModel(keys: LegacyVaultKeys? = null): VaultViewModel =
        if (keys == null) {
            VaultViewModel(app.container.vaultRepository, settings)
        } else {
            VaultViewModel(app.container.vaultRepository, settings, legacyVaultKeys = keys)
        }.also { viewModels += it }

    /**
     * The stored settings, read once after the view models' background DataStore writes (the security
     * stats recalculation started by a successful upgrade) have finished.
     *
     * Read under DataStore's write lock, and read errors are thrown, never read as "nothing stored".
     * The flows (masterPasswordHash etc.) read without the lock; one that raced a background write on the
     * Windows test JVM once found the file missing mid-replace and read a null master hash.
     */
    private suspend fun storedSettings(vararg active: VaultViewModel): Preferences {
        (active.toList() + viewModels).distinct().forEach { it.awaitSecurityStatsRecalculation() }
        return settings.readStoredPreferencesOrThrow()
    }

    private suspend fun allRows() = dao.getAllEntitiesIncludingDeletedSync().sortedBy { it.id }

    /** Every stored setting except the security-stats summary (a cache rewritten in the background). */
    private suspend fun dataStoreSnapshot(): Map<Preferences.Key<*>, Any> =
        storedSettings().asMap().filterKeys { !it.name.startsWith("security_") }

    private fun prefsSnapshot(): Map<String, Any?> = HashMap(prefs.all)

    private fun storedDek(): ByteArray {
        val wrapped = settings.getDekMpWrappedSync()
        assertNotNull("Wrapped DEK saved", wrapped)
        val kek = PasswordHashHelper.deriveMasterKey(password, salt, iterations, algorithm)
        val dek = CryptoManager.unwrapDekWithKek(wrapped!!, kek)
        assertNotNull("Wrapped with the KEK from the stored KDF parameters", dek)
        return dek!!
    }

    // --- F18: migration ---

    @Test
    fun fallbackKey_legacyEntriesReadableAfterUnlock() = runBlocking {
        val key = newAesKey()
        storeFallbackKey(key)
        val active = insertLegacyRow(
            Plain(
                "Bank", "alice", "s3cret!", "https://bank.example", "pin 1234", "Finance",
                listOf("money", "main"), listOf(CustomField("Account", "12-34"))
            ),
            listOf(key),
            favorite = true
        )
        val binned = insertLegacyRow(Plain("Old Mail", "bob", "hunter2"), listOf(key), isDeleted = true)
        settings.incrementFailedAttempts(1000L, 3)

        val vm = viewModel()
        assertEquals(AuthResult.SUCCESS, vm.unlockWithPassword(password))
        assertTrue("Unlocked", vm.isUnlocked.first())
        assertNull("Nothing left unread", vm.migrationUnreadableCount.value)
        assertNull("Successful unlock resets the counter", storedSettings(vm)[SettingsRepository.FAILED_AUTH_ATTEMPTS])

        val entries = app.container.vaultRepository.getAllEntriesSync()
        assertEquals(1, entries.size)
        val bank = entries.single()
        assertFalse(bank.isDecryptionFailed)
        assertEquals("Bank", bank.title)
        assertEquals("alice", bank.username)
        assertEquals("s3cret!", bank.password)
        assertEquals("https://bank.example", bank.website)
        assertEquals("pin 1234", bank.notes)
        assertEquals("Finance", bank.category)
        assertEquals(listOf("money", "main"), bank.tags)
        assertEquals(listOf(CustomField("Account", "12-34")), bank.customFields)
        assertTrue(bank.isFavorite)
        assertEquals(active.syncId, bank.syncId)
        assertEquals(active.timestamp, bank.timestamp)

        // Recycle-bin rows are migrated too.
        val bin = app.container.vaultRepository.decryptEntity(dao.getEntryById(binned.id)!!)
        assertFalse(bin.isDecryptionFailed)
        assertEquals("hunter2", bin.password)
        assertTrue(bin.isDeleted)

        // Keys: stored KDF parameters, pending cleared, legacy key kept, auth hash upgraded.
        val dek = storedDek()
        val crypto = CryptoManager(settings).apply { injectSoftwareDek(dek) }
        assertEquals("Bank", crypto.decrypt(dao.getEntryById(active.id)!!.titleEnc))
        assertNull(settings.getPendingDekMpWrappedSync())
        assertNotNull("Legacy fallback key is never deleted", prefs.getString("fallback_key", null))
        // Migration keeps the stored KDF parameters; only the auth hash changes. (The KDF upgrade to
        // CURRENT_KDF_ITERATIONS runs on the next unlock, inside unlockWithPassword.)
        val stored = storedSettings(vm)
        assertEquals(PasswordHashHelper.hashPassword(password, salt, iterations, algorithm), stored[SettingsRepository.MASTER_HASH])
        assertEquals(salt, stored[SettingsRepository.MASTER_SALT])
        assertEquals(iterations, stored[SettingsRepository.MASTER_KDF_ITERATIONS])
        assertEquals(algorithm, stored[SettingsRepository.MASTER_KDF_ALGORITHM])

        // Next unlock uses the normal path. Stored iterations are below CURRENT_KDF_ITERATIONS, so it also
        // runs the KDF upgrade (launched inside unlockWithPassword's scope, finished when it returns).
        vm.lock()
        assertEquals(AuthResult.SUCCESS, vm.unlockWithPassword(password))
        assertEquals("s3cret!", app.container.vaultRepository.getAllEntriesSync().single().password)
        val upgraded = storedSettings(vm)
        val newSalt = upgraded[SettingsRepository.MASTER_SALT]!!
        assertNotEquals("KDF upgrade uses a fresh salt", salt, newSalt)
        assertEquals(SecurityPolicy.CURRENT_KDF_ITERATIONS, upgraded[SettingsRepository.MASTER_KDF_ITERATIONS])
        assertEquals(SecurityPolicy.CURRENT_KDF_ALGORITHM, upgraded[SettingsRepository.MASTER_KDF_ALGORITHM])
        assertEquals(SecurityPolicy.CURRENT_KDF_VERSION, upgraded[SettingsRepository.MASTER_KDF_VERSION])
        assertEquals(
            PasswordHashHelper.hashPassword(password, newSalt, SecurityPolicy.CURRENT_KDF_ITERATIONS, SecurityPolicy.CURRENT_KDF_ALGORITHM),
            upgraded[SettingsRepository.MASTER_HASH]
        )
        val newKek = PasswordHashHelper.deriveMasterKey(
            password, newSalt, SecurityPolicy.CURRENT_KDF_ITERATIONS, SecurityPolicy.CURRENT_KDF_ALGORITHM
        )
        assertArrayEquals("Same DEK, re-wrapped with the upgraded KEK", dek, CryptoManager.unwrapDekWithKek(settings.getDekMpWrappedSync()!!, newKek))
        assertNull(settings.getPendingDekMpWrappedV2Sync())
    }

    @Test
    fun twoLegacyKeys_fieldsUnderEitherKeyInOneVault() = runBlocking {
        val keystoreLike = newAesKey()
        val fallback = newAesKey()
        val mixed = listOf(keystoreLike, fallback, keystoreLike, fallback, keystoreLike, fallback, keystoreLike, fallback)
        insertLegacyRow(Plain("Mixed", "u1", "p1", "w1", "n1", "Work", listOf("t"), listOf(CustomField("k", "v"))), mixed)
        insertLegacyRow(Plain("OnlyFallback", "u2", "p2"), listOf(fallback))
        insertLegacyRow(Plain("OnlyKeystore", "u3", "p3"), listOf(keystoreLike))

        val vm = viewModel(LegacyVaultKeys { listOf(keystoreLike, fallback) })
        assertEquals(AuthResult.SUCCESS, vm.unlockWithPassword(password))
        val byTitle = app.container.vaultRepository.getAllEntriesSync().associateBy { it.title }
        assertEquals(setOf("Mixed", "OnlyFallback", "OnlyKeystore"), byTitle.keys)
        assertTrue(byTitle.values.none { it.isDecryptionFailed })
        with(byTitle.getValue("Mixed")) {
            assertEquals("u1", username); assertEquals("p1", password); assertEquals("w1", website)
            assertEquals("n1", notes); assertEquals("Work", category); assertEquals(listOf("t"), tags)
            assertEquals(listOf(CustomField("k", "v")), customFields)
        }
        assertEquals("p2", byTitle.getValue("OnlyFallback").password)
        assertEquals("p3", byTitle.getValue("OnlyKeystore").password)
    }

    @Test
    fun emptyFields_stayEmpty() = runBlocking {
        val key = newAesKey()
        storeFallbackKey(key)
        // Old builds stored "" for empty values and when encryption failed.
        val row = insertRawRow(
            VaultEntryEntity(
                titleEnc = legacyEncrypt("Only title", key),
                usernameEnc = "", passwordEnc = "", websiteEnc = "", notesEnc = "", categoryEnc = "",
                tagsEnc = "", customFieldsEnc = "",
                isFavorite = false, timestamp = 1L, syncId = UUID.randomUUID().toString()
            )
        )
        val vm = viewModel()
        assertEquals(AuthResult.SUCCESS, vm.unlockWithPassword(password))
        val entry = app.container.vaultRepository.getAllEntriesSync().single()
        assertFalse(entry.isDecryptionFailed)
        assertEquals("Only title", entry.title)
        assertEquals("", entry.username)
        assertEquals("", entry.password)
        assertEquals(emptyList<String>(), entry.tags)
        val stored = dao.getEntryById(row.id)!!
        assertNotEquals(row.titleEnc, stored.titleEnc)
        assertEquals("", stored.usernameEnc)
        assertEquals("", stored.tagsEnc)
        assertEquals("", stored.customFieldsEnc)
    }

    @Test
    fun oneCorruptedRow_othersMigrated_corruptedRowBytesUnchanged() = runBlocking {
        val key = newAesKey()
        storeFallbackKey(key)
        insertLegacyRow(Plain("Good 1", "a", "pa"), listOf(key))
        insertLegacyRow(Plain("Good 2", "b", "pb"), listOf(key))
        // Password under a key this device no longer has; other fields fine.
        val lostKey = newAesKey()
        val corrupted = insertLegacyRow(Plain("Broken", "c", "pc"), listOf(key, key, lostKey, key, key, key, key, key))
        // Garbage that isn't even base64 of a ciphertext.
        val garbage = insertRawRow(corrupted.copy(id = 0, titleEnc = "%%not-base64%%", syncId = UUID.randomUUID().toString()))

        val vm = viewModel()
        assertEquals(AuthResult.SUCCESS, vm.unlockWithPassword(password))
        assertTrue(vm.isUnlocked.first())
        assertEquals("One-time notice with the count", 2, vm.migrationUnreadableCount.value)
        vm.dismissMigrationNotice()
        assertNull(vm.migrationUnreadableCount.value)

        assertEquals("Byte-for-byte unchanged", corrupted, dao.getEntryById(corrupted.id))
        assertEquals("Byte-for-byte unchanged", garbage, dao.getEntryById(garbage.id))

        val entries = app.container.vaultRepository.getAllEntriesSync()
        assertEquals(4, entries.size)
        val readable = entries.filter { !it.isDecryptionFailed }.associateBy { it.title }
        assertEquals(setOf("Good 1", "Good 2"), readable.keys)
        assertEquals("pa", readable.getValue("Good 1").password)
        assertEquals(2, entries.count { it.isDecryptionFailed })
    }

    private suspend fun assertMigrationFailedAndNothingChanged(vm: VaultViewModel) {
        settings.resetFailedAttempts()
        settings.incrementFailedAttempts(1000L, 3)
        settings.incrementFailedAttempts(2000L, 3)
        val rowsBefore = allRows()
        val dataStoreBefore = dataStoreSnapshot()
        val prefsBefore = prefsSnapshot()

        assertEquals(AuthResult.MIGRATION_FAILED, vm.unlockWithPassword(password))

        assertFalse("Not unlocked", vm.isUnlocked.first())
        assertNull("No DEK in memory", app.container.cryptoManager.getSoftwareDek())
        assertEquals("DB rows unchanged", rowsBefore, allRows())
        assertEquals("DataStore unchanged (failed-attempt counter included)", dataStoreBefore, dataStoreSnapshot())
        assertEquals(2, storedSettings()[SettingsRepository.FAILED_AUTH_ATTEMPTS])
        assertEquals("SharedPreferences unchanged (wrapped and pending keys included)", prefsBefore, prefsSnapshot())
        assertNull(settings.getDekMpWrappedSync())

        // Retrying fails the same way, still changing nothing.
        assertEquals(AuthResult.MIGRATION_FAILED, vm.unlockWithPassword(password))
        assertEquals(rowsBefore, allRows())
        assertEquals(prefsBefore, prefsSnapshot())
    }

    @Test
    fun everyRowUnreadable_migrationFailed_nothingChanged() = runBlocking {
        storeFallbackKey(newAesKey())
        val otherKey = newAesKey()
        insertLegacyRow(Plain("A", "a", "pa"), listOf(otherKey))
        insertLegacyRow(Plain("B", "b", "pb"), listOf(otherKey), isDeleted = true)
        assertMigrationFailedAndNothingChanged(viewModel())
        assertNull(settings.getPendingDekMpWrappedSync())
    }

    @Test
    fun noLegacyKey_withRows_migrationFailed_nothingChanged() = runBlocking {
        insertLegacyRow(Plain("A", "a", "pa"), listOf(newAesKey()))
        assertMigrationFailedAndNothingChanged(viewModel())
        assertMigrationFailedAndNothingChanged(viewModel(LegacyVaultKeys { emptyList() }))
    }

    @Test
    fun emptyVault_migrates() = runBlocking {
        val vm = viewModel()
        assertEquals(AuthResult.SUCCESS, vm.unlockWithPassword(password))
        assertTrue(vm.isUnlocked.first())
        storedDek()
        assertNull(settings.getPendingDekMpWrappedSync())
        assertTrue(vm.addEntrySync(com.example.domain.models.VaultEntry(title = "New")))
        assertEquals("New", app.container.vaultRepository.getAllEntriesSync().single().title)
    }

    @Test
    fun crashRecovery_transactionCommitted_finishesWithPendingKey() = runBlocking {
        val key = newAesKey()
        storeFallbackKey(key)
        // State after a crash between the DB commit and the finalize step.
        val dek = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val kek = PasswordHashHelper.deriveMasterKey(password, salt, iterations, algorithm)
        val pending = CryptoManager.wrapDekWithKek(dek, kek)
        assertTrue(settings.savePendingDekMpWrappedSync(pending))
        val legacy = insertLegacyRow(Plain("Done", "d", "pd"), listOf(key))
        val plainRows = LegacyVaultMigration.decryptAll(listOf(legacy), listOf(key)).readable
        dao.updateEntries(LegacyVaultMigration.reencrypt(plainRows, dek))
        val leftOver = insertLegacyRow(Plain("Unreadable", "x", "px"), listOf(newAesKey()))

        val vm = viewModel()
        assertEquals(AuthResult.SUCCESS, vm.unlockWithPassword(password))
        assertTrue(vm.isUnlocked.first())
        assertEquals(pending, settings.getDekMpWrappedSync())
        assertNull(settings.getPendingDekMpWrappedSync())
        assertEquals(1, vm.migrationUnreadableCount.value)
        assertEquals(leftOver, dao.getEntryById(leftOver.id))
        val readable = app.container.vaultRepository.getAllEntriesSync().filter { !it.isDecryptionFailed }
        assertEquals("pd", readable.single().password)
    }

    @Test
    fun crashRecovery_transactionNotCommitted_migratesAgain() = runBlocking {
        val key = newAesKey()
        storeFallbackKey(key)
        // A pending key whose DEK encrypts nothing (crash before or during the DB write).
        val kek = PasswordHashHelper.deriveMasterKey(password, salt, iterations, algorithm)
        val stalePending = CryptoManager.wrapDekWithKek(ByteArray(32).also { SecureRandom().nextBytes(it) }, kek)
        settings.savePendingDekMpWrappedSync(stalePending)
        insertLegacyRow(Plain("Legacy", "l", "pl"), listOf(key))

        val vm = viewModel()
        assertEquals(AuthResult.SUCCESS, vm.unlockWithPassword(password))
        assertTrue(vm.isUnlocked.first())
        assertNotEquals(stalePending, settings.getDekMpWrappedSync())
        assertNull(settings.getPendingDekMpWrappedSync())
        storedDek()
        assertEquals("pl", app.container.vaultRepository.getAllEntriesSync().single().password)
    }

    @Test
    fun crashRecovery_notCommitted_andNoLegacyKey_keepsEverything() = runBlocking {
        val kek = PasswordHashHelper.deriveMasterKey(password, salt, iterations, algorithm)
        val stalePending = CryptoManager.wrapDekWithKek(ByteArray(32).also { SecureRandom().nextBytes(it) }, kek)
        settings.savePendingDekMpWrappedSync(stalePending)
        insertLegacyRow(Plain("Legacy", "l", "pl"), listOf(newAesKey()))
        assertMigrationFailedAndNothingChanged(viewModel())
        assertEquals("Pending key left as it was", stalePending, settings.getPendingDekMpWrappedSync())
    }

    @Test
    fun migratedRowsWrite_isAllOrNothing() = runBlocking {
        val key = newAesKey()
        val a = insertLegacyRow(Plain("A", "a", "pa"), listOf(key))
        val b = insertLegacyRow(Plain("B", "b", "pb"), listOf(key))
        val plain = LegacyVaultMigration.decryptAll(listOf(a, b), listOf(key)).readable
        val dek = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val replacements = LegacyVaultMigration.reencrypt(plain, dek)
        // Row B changed after it was read: the whole write is rolled back, row A included.
        val staleB = b.copy(notesEnc = "changed-meanwhile")
        val failure = runCatching { app.container.vaultRepository.replaceMigratedRows(listOf(a, staleB), replacements) }
        assertTrue(failure.isFailure)
        assertEquals(a, dao.getEntryById(a.id))
        assertEquals(b, dao.getEntryById(b.id))

        app.container.vaultRepository.replaceMigratedRows(listOf(a, b), replacements)
        assertEquals(replacements, listOf(dao.getEntryById(a.id), dao.getEntryById(b.id)))
    }

    @Test
    fun wrongPassword_onLegacyVault_isStillInvalidPassword() = runBlocking {
        storeFallbackKey(newAesKey())
        val vm = viewModel()
        assertEquals(AuthResult.INVALID_PASSWORD, vm.unlockWithPassword("wrong password"))
        assertEquals(1, storedSettings(vm)[SettingsRepository.FAILED_AUTH_ATTEMPTS])
        assertNull(settings.getPendingDekMpWrappedSync())
        assertNull(settings.getDekMpWrappedSync())
    }

    // --- Legacy key selection ---

    @Test
    fun legacyKeys_perFieldSelection() {
        val k1 = newAesKey()
        val k2 = newAesKey()
        val underK2 = legacyEncrypt("hello", k2)
        assertEquals("hello", LegacyVaultKeys.decryptField(underK2, listOf(k1, k2)))
        assertEquals("hello", LegacyVaultKeys.decryptField(underK2, listOf(k2, k1)))
        assertNull(LegacyVaultKeys.decryptField(underK2, listOf(k1)))
        assertNull(LegacyVaultKeys.decryptField(underK2, emptyList()))
        assertEquals("", LegacyVaultKeys.decryptField("", emptyList()))
        assertNull(LegacyVaultKeys.decryptField("%%%", listOf(k1)))
        assertNull(LegacyVaultKeys.decryptField(Base64.encodeToString(ByteArray(10), Base64.NO_WRAP), listOf(k1)))
        assertNull(LegacyVaultKeys.fallbackKeyFrom(null))
        assertNull(LegacyVaultKeys.fallbackKeyFrom(Base64.encodeToString(ByteArray(7), Base64.NO_WRAP)))
        assertNotNull(LegacyVaultKeys.fallbackKeyFrom(Base64.encodeToString(k1.encoded, Base64.NO_WRAP)))
    }

    @Test
    fun legacyKeys_forDevice_readsFallbackOnly_andNeverCreatesAKey() {
        val before = prefsSnapshot()
        assertTrue(LegacyVaultKeys.forDevice(settings).load().isEmpty())
        assertEquals("Reading never writes a key", before, prefsSnapshot())

        val key = newAesKey()
        storeFallbackKey(key)
        val loaded = LegacyVaultKeys.forDevice(settings).load()
        assertEquals(1, loaded.size)
        assertArrayEquals(key.encoded, loaded.single().encoded)
        assertNull("No Keystore key appears", LegacyVaultKeys.readKeystoreKey())
        // A key source that fails is never read as "no key".
        assertThrows(IllegalStateException::class.java) { LegacyVaultKeys { throw IllegalStateException("boom") }.load() }
    }

    // --- M1: an unusable legacy key or a non-authentication error never leads to a partial upgrade ---

    /** A key store whose "vaultpass_keys" entry exists but can't be loaded (it needs a password). */
    private fun keyStoreWithUnloadableAlias(): KeyStore = KeyStore.getInstance("JCEKS").apply {
        load(null, null)
        setEntry(
            LegacyVaultKeys.KEYSTORE_ALIAS,
            KeyStore.SecretKeyEntry(newAesKey()),
            KeyStore.PasswordProtection("not-null".toCharArray())
        )
    }

    @Test
    fun keystoreKey_absentAliasIsNoKey_unloadableAliasThrows() {
        assertNull(LegacyVaultKeys.keystoreKeyFrom(KeyStore.getInstance("JCEKS").apply { load(null, null) }))
        assertThrows(LegacyKeyUnusableException::class.java) { LegacyVaultKeys.keystoreKeyFrom(keyStoreWithUnloadableAlias()) }
        val fallback = newAesKey()
        storeFallbackKey(fallback)
        val keys = LegacyVaultKeys.forDevice(settings) { LegacyVaultKeys.keystoreKeyFrom(keyStoreWithUnloadableAlias()) }
        assertThrows("Never just the fallback key", LegacyKeyUnusableException::class.java) { keys.load() }
    }

    @Test
    fun keystoreAliasExistsButUnloadable_migrationFailed_nothingChanged() = runBlocking {
        val fallback = newAesKey()
        storeFallbackKey(fallback)
        // The fallback key alone opens these rows: the old code migrated them and switched the vault
        // to the new key, stranding everything under the Keystore key.
        insertLegacyRow(Plain("A", "a", "pa"), listOf(fallback))
        insertLegacyRow(Plain("B", "b", "pb"), listOf(fallback), isDeleted = true)
        val keyStore = keyStoreWithUnloadableAlias()

        assertMigrationFailedAndNothingChanged(viewModel(LegacyVaultKeys.forDevice(settings) { LegacyVaultKeys.keystoreKeyFrom(keyStore) }))
        assertNull(settings.getPendingDekMpWrappedSync())
        assertNotNull("Legacy key kept", prefs.getString("fallback_key", null))
    }

    @Test
    fun nonAuthenticationDecryptError_migrationFailed_nothingChanged() = runBlocking {
        val fallback = newAesKey()
        insertLegacyRow(Plain("A", "a", "pa"), listOf(fallback))
        insertLegacyRow(Plain("B", "b", "pb"), listOf(fallback))
        // A key the cipher refuses (not a wrong key: an unusable one), tried before the working key.
        val unusable = SecretKeySpec(ByteArray(7), "AES")
        val field = legacyEncrypt("hello", fallback)
        assertThrows(LegacyKeyUnusableException::class.java) { LegacyVaultKeys.decryptField(field, listOf(unusable, fallback)) }
        assertNull("A wrong key is still just 'no fit'", LegacyVaultKeys.decryptField(field, listOf(newAesKey())))

        assertMigrationFailedAndNothingChanged(viewModel(LegacyVaultKeys { listOf(unusable, fallback) }))
        assertNull(settings.getPendingDekMpWrappedSync())
    }

    @Test
    fun emptyVault_migratesEvenWithAnUnusableLegacyKey() = runBlocking {
        val vm = viewModel(LegacyVaultKeys { throw LegacyKeyUnusableException("Keystore broken") })
        assertEquals(AuthResult.SUCCESS, vm.unlockWithPassword(password))
        assertTrue(vm.isUnlocked.first())
        assertEquals(32, storedDek().size)
    }

    @Test
    fun laterUnlock_reencryptsRowsTheLegacyKeysNowOpen_leavesOthersByteIdentical() = runBlocking {
        val fallback = newAesKey()
        val keystoreLike = newAesKey()
        insertLegacyRow(Plain("Migrated", "m", "pm"), listOf(fallback))
        // Left behind by an earlier upgrade that couldn't use the Keystore key.
        val leftBehind = insertLegacyRow(
            Plain("Left behind", "l", "pl", "https://l.example", "note", "Work", listOf("t"), listOf(CustomField("k", "v"))),
            listOf(keystoreLike, fallback, keystoreLike, fallback, keystoreLike, fallback, keystoreLike, fallback)
        )
        val lost = insertLegacyRow(Plain("Lost", "x", "px"), listOf(newAesKey()))

        val first = viewModel(LegacyVaultKeys { listOf(fallback) })
        assertEquals(AuthResult.SUCCESS, first.unlockWithPassword(password))
        assertEquals(2, first.migrationUnreadableCount.value)
        assertEquals(leftBehind, dao.getEntryById(leftBehind.id))
        val dek = storedDek()
        first.lock()

        // A later unlock where the key source fails: unlock still works, nothing is written.
        val failing = viewModel(LegacyVaultKeys { throw LegacyKeyUnusableException("Keystore busy") })
        assertEquals(AuthResult.SUCCESS, failing.unlockWithPassword(password))
        assertEquals(leftBehind, dao.getEntryById(leftBehind.id))
        assertEquals(lost, dao.getEntryById(lost.id))
        failing.lock()

        // A later unlock where the Keystore key is back.
        val later = viewModel(LegacyVaultKeys { listOf(keystoreLike, fallback) })
        assertEquals(AuthResult.SUCCESS, later.unlockWithPassword(password))
        assertEquals("Rows that still fail are byte-for-byte untouched", lost, dao.getEntryById(lost.id))
        val recovered = dao.getEntryById(leftBehind.id)!!
        assertNotEquals(leftBehind.titleEnc, recovered.titleEnc)
        assertEquals(leftBehind.syncId, recovered.syncId)
        assertEquals(leftBehind.timestamp, recovered.timestamp)
        with(app.container.vaultRepository.decryptEntity(recovered)) {
            assertFalse(isDecryptionFailed)
            assertEquals("Left behind", title); assertEquals("l", username); assertEquals("pl", password)
            assertEquals("https://l.example", website); assertEquals("note", notes); assertEquals("Work", category)
            assertEquals(listOf("t"), tags); assertEquals(listOf(CustomField("k", "v")), customFields)
        }
        val crypto = CryptoManager(settings).apply { injectSoftwareDek(dek) }
        assertEquals("Under the vault's own key", "pl", crypto.decrypt(recovered.passwordEnc))
        val entries = app.container.vaultRepository.getAllEntriesSync()
        assertEquals(setOf("Migrated", "Left behind"), entries.filter { !it.isDecryptionFailed }.map { it.title }.toSet())
        assertEquals(1, entries.count { it.isDecryptionFailed })
    }

    @Test
    fun aLockDuringTheUpgrade_keepsTheVaultLocked_butTheUpgradeAndItsNoticeStay() = runBlocking {
        val key = newAesKey()
        insertLegacyRow(Plain("Readable", "r", "pr"), listOf(key))
        insertLegacyRow(Plain("Lost", "x", "px"), listOf(newAesKey()))
        val vm = viewModel(LegacyVaultKeys { listOf(key) })

        // Hold the unlock lock so the lock below lands while the upgrade is in progress.
        val unlockLock = com.example.security.VaultUnlockLock.mutex
        unlockLock.lock()
        val unlocking = async(Dispatchers.Default) { vm.unlockWithPassword(password) }
        try {
            val deadline = System.currentTimeMillis() + 5_000
            while (!vm.isUnlocking.value && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertTrue(vm.isUnlocking.value)
            vm.lock()
        } finally {
            unlockLock.unlock()
        }

        assertEquals(AuthResult.INTERRUPTED, unlocking.await())
        assertFalse(vm.isUnlocked.first())
        assertNull("Locked means no key", app.container.cryptoManager.getSoftwareDek())
        assertNotNull("The upgrade itself is stored", settings.getDekMpWrappedSync())
        assertEquals("The partial-upgrade notice waits for the next unlock", 1, vm.migrationUnreadableCount.value)

        // The next unlock opens the upgraded vault (stored key) and the notice is still there to show.
        assertEquals(AuthResult.SUCCESS, vm.unlockWithPassword(password))
        assertTrue(vm.isUnlocked.first())
        assertEquals(1, vm.migrationUnreadableCount.value)
        assertNull("A right password is never a failed attempt", storedSettings(vm)[SettingsRepository.FAILED_AUTH_ATTEMPTS])
    }

    @Test
    fun theStepsAfterAnUpgrade_finishEvenThoughTheLockScreensScopeIsCancelled() = runBlocking {
        val key = newAesKey()
        insertLegacyRow(Plain("Readable", "r", "pr"), listOf(key))
        insertLegacyRow(Plain("Lost", "x", "px"), listOf(newAesKey()))
        val vm = viewModel(LegacyVaultKeys { listOf(key) })
        assertEquals(AuthResult.INVALID_PASSWORD, vm.unlockWithPassword("not the password"))

        // Like LockScreen: the vault opening removes the screen, which cancels its scope.
        val lockScreenScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Default + kotlinx.coroutines.Job())
        vm.vaultOpenedHook = { lockScreenScope.coroutineContext[kotlinx.coroutines.Job]!!.cancel() }
        val unlocking = lockScreenScope.launch { vm.unlockWithPassword(password) }
        unlocking.join()
        vm.vaultOpenedHook = null

        assertTrue(unlocking.isCancelled)
        assertTrue(vm.isUnlocked.first())
        assertEquals("The partial-upgrade notice is still set", 1, vm.migrationUnreadableCount.value)
        assertNull("Failed attempts reset", storedSettings(vm)[SettingsRepository.FAILED_AUTH_ATTEMPTS])
    }

    // --- M4: one unlock/upgrade at a time in the process ---

    @Test
    fun concurrentUnlocks_fromTwoViewModels_migrateOnce_bothUnlocked() = runBlocking {
        val key = newAesKey()
        insertLegacyRow(Plain("A", "a", "pa"), listOf(key))
        insertLegacyRow(Plain("B", "b", "pb"), listOf(key))
        val loads = java.util.concurrent.atomic.AtomicInteger()
        val keys = LegacyVaultKeys { loads.incrementAndGet(); listOf(key) }
        // MainActivity and AutofillAuthActivity each have their own view model.
        val main = viewModel(keys)
        val autofill = viewModel(keys)

        val results = listOf(
            async(Dispatchers.Default) { main.unlockWithPassword(password) },
            async(Dispatchers.Default) { autofill.unlockWithPassword(password) }
        ).map { it.await() }

        assertEquals(listOf(AuthResult.SUCCESS, AuthResult.SUCCESS), results)
        assertEquals("The upgrade ran once", 1, loads.get())
        assertTrue(main.isUnlocked.first())
        assertTrue(autofill.isUnlocked.first())
        assertFalse(main.isUnlocking.value)
        assertFalse(autofill.isUnlocking.value)
        val dek = storedDek()
        val crypto = CryptoManager(settings).apply { injectSoftwareDek(dek) }
        assertEquals(setOf("pa", "pb"), allRows().map { crypto.decrypt(it.passwordEnc) }.toSet())
        assertNull(settings.getPendingDekMpWrappedSync())

        // A call that finds the vault open still checks the password.
        assertEquals(AuthResult.INVALID_PASSWORD, autofill.unlockWithPassword("wrong password"))
    }

    // --- L1: a pending DEK wrapped with the legacy KEK ---

    @Test
    fun crashRecovery_pendingWrappedWithLegacyKek_recovers() = runBlocking {
        val key = newAesKey()
        storeFallbackKey(key)
        val dek = ByteArray(32).also { SecureRandom().nextBytes(it) }
        // Before KEK domain separation the KEK was the raw PBKDF2 output.
        val legacyKek = PasswordHashHelper.deriveLegacyMasterKey(password, salt, iterations, algorithm)
        val pending = CryptoManager.wrapDekWithKek(dek, legacyKek)
        assertNull(CryptoManager.unwrapDekWithKek(pending, PasswordHashHelper.deriveMasterKey(password, salt, iterations, algorithm)))
        assertTrue(settings.savePendingDekMpWrappedSync(pending))
        val legacy = insertLegacyRow(Plain("Done", "d", "pd"), listOf(key))
        dao.updateEntries(LegacyVaultMigration.reencrypt(LegacyVaultMigration.decryptAll(listOf(legacy), listOf(key)).readable, dek))

        val vm = viewModel()
        assertEquals(AuthResult.SUCCESS, vm.unlockWithPassword(password))
        assertTrue(vm.isUnlocked.first())
        assertEquals("pd", app.container.vaultRepository.getAllEntriesSync().single().password)
        assertNull(settings.getPendingDekMpWrappedSync())
        assertArrayEquals("Finalized under the KEK unlock derives", dek, storedDek())

        vm.lock()
        assertEquals("The finalized key works on the next unlock", AuthResult.SUCCESS, vm.unlockWithPassword(password))
        assertEquals("pd", app.container.vaultRepository.getAllEntriesSync().single().password)
    }

    // --- F17: setup guard and Setup-vs-Lock decision ---

    @Test
    fun setup_refusesWhenWrappedKeyExists() = runBlocking {
        settings.saveDekMpWrappedSync("existing-wrapped-dek")
        val hashBefore = storedSettings()[SettingsRepository.MASTER_HASH]
        assertNotNull(hashBefore)
        val vm = viewModel()
        assertEquals(SetupResult.VAULT_EXISTS, vm.setupMasterPasswordSync("Brand-New-Pass-99!"))
        assertEquals("existing-wrapped-dek", settings.getDekMpWrappedSync())
        assertEquals(hashBefore, storedSettings(vm)[SettingsRepository.MASTER_HASH])
        assertFalse(vm.isUnlocked.first())
    }

    @Test
    fun setup_refusesWhenPendingKeyExists() = runBlocking {
        settings.savePendingDekMpWrappedSync("pending-wrapped-dek")
        val vm = viewModel()
        assertEquals(SetupResult.VAULT_EXISTS, vm.setupMasterPasswordSync("Brand-New-Pass-99!"))
        assertEquals("pending-wrapped-dek", settings.getPendingDekMpWrappedSync())
        assertNull(settings.getDekMpWrappedSync())
    }

    @Test
    fun setup_refusesWhenVaultRowsExist() = runBlocking {
        val row = insertLegacyRow(Plain("Existing", "e", "pe"), listOf(newAesKey()), isDeleted = true)
        val vm = viewModel()
        assertEquals(SetupResult.VAULT_EXISTS, vm.setupMasterPasswordSync("Brand-New-Pass-99!"))
        assertEquals(row, dao.getEntryById(row.id))
        assertNull(settings.getDekMpWrappedSync())
        assertFalse(vm.isUnlocked.first())
    }

    @Test
    fun setup_freshInstall_works_andDoubleSubmitCommitsOnce() = runBlocking {
        val newPassword = "Brand-New-Pass-99!"
        val vm = viewModel()
        val first = async(Dispatchers.Default) { vm.setupMasterPasswordSync(newPassword) }
        val second = async(Dispatchers.Default) { vm.setupMasterPasswordSync(newPassword) }
        val results = listOf(first.await(), second.await())
        assertEquals("Exactly one setup creates the vault", 1, results.count { it == SetupResult.CREATED })
        assertTrue(results.all { it == SetupResult.CREATED || it == SetupResult.IN_PROGRESS || it == SetupResult.VAULT_EXISTS })
        assertTrue(vm.isUnlocked.first())
        assertNotNull(settings.getDekMpWrappedSync())

        vm.lock()
        assertEquals(AuthResult.SUCCESS, vm.unlockWithPassword(newPassword))
        assertTrue(vm.isUnlocked.first())
        assertEquals(SetupResult.VAULT_EXISTS, vm.setupMasterPasswordSync(newPassword))
    }

    @Test
    fun launchState_readErrorOrExistingDataNeverShowsSetup() {
        assertEquals(VaultLaunchState.LOCK, VaultLaunchState.decide(MasterHashRead.PRESENT, vaultDataExists = false))
        assertEquals(VaultLaunchState.LOCK, VaultLaunchState.decide(MasterHashRead.PRESENT, vaultDataExists = true))
        assertEquals(VaultLaunchState.UNREADABLE, VaultLaunchState.decide(MasterHashRead.READ_ERROR, vaultDataExists = false))
        assertEquals(VaultLaunchState.UNREADABLE, VaultLaunchState.decide(MasterHashRead.READ_ERROR, vaultDataExists = true))
        assertEquals(VaultLaunchState.UNREADABLE, VaultLaunchState.decide(MasterHashRead.ABSENT, vaultDataExists = true))
        assertEquals(VaultLaunchState.SETUP, VaultLaunchState.decide(MasterHashRead.ABSENT, vaultDataExists = false))
    }
}
