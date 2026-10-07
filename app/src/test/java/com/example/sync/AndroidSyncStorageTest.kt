package com.example.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.VaultPassApplication
import com.example.domain.sync.models.PairedDevice
import com.example.network.sync.AndroidPairKeyProtector
import com.example.repository.PairedDeviceRepository
import com.example.security.CryptoManager
import com.vaultpass.synccore.Hex
import com.vaultpass.synccore.PairKeyFormat
import com.vaultpass.synccore.SyncCrypto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pair keys are stored sealed with the vault key, and v1 pairings are dropped. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidSyncStorageTest {

    private lateinit var repository: PairedDeviceRepository
    private lateinit var cryptoManager: CryptoManager

    @Before
    fun setUp() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>() as VaultPassApplication
        repository = PairedDeviceRepository(app)
        repository.clearAllPairedDevices()
        cryptoManager = CryptoManager(app.container.settingsRepository)
        cryptoManager.injectSoftwareDek(SyncCrypto.randomBytes(32))
    }

    @Test
    fun protector_sealsWithTheVaultKey_andNeedsAnUnlockedVault() {
        val protector = AndroidPairKeyProtector(cryptoManager)
        val pairKey = SyncCrypto.randomBytes(32)

        val sealed = protector.seal(pairKey)!!
        assertTrue(PairKeyFormat.isCurrent(sealed))
        assertFalse("The key must not be stored in plaintext", sealed.contains(Hex.encode(pairKey)))
        assertArrayEquals(pairKey, protector.open(sealed))

        cryptoManager.clearSoftwareDek()
        assertNull(protector.seal(pairKey))
        assertNull(protector.open(sealed))
    }

    @Test
    fun protector_rejectsV1SecretsAndGarbage() {
        val protector = AndroidPairKeyProtector(cryptoManager)
        assertNull(protector.open("dGVzdC1zZWNyZXQ="))
        assertNull(protector.open("v2:"))
        assertNull(protector.open("v2:not-base64-ciphertext"))
        assertNull(protector.seal(ByteArray(16)))
    }

    @Test
    fun repository_hidesAndPurgesV1Pairings() = runBlocking {
        repository.savePairedDevice(PairedDevice(deviceId = "new-pc", deviceName = "New", sharedSecret = "v2:abcd"))
        // A pairing stored by a v1 build, next to the v2 one.
        injectLegacyEntry()
        assertEquals("v1 entries are invisible", listOf("new-pc"), repository.getPairedDevices().map { it.deviceId })
        assertNull(repository.getPairedDevice("old-pc"))

        assertEquals(1, repository.purgeLegacyPairings())
        assertEquals(0, repository.purgeLegacyPairings())
        assertEquals(listOf("new-pc"), repository.getPairedDevices().map { it.deviceId })
    }

    /** Stores a v1 pairing next to the existing entries, bypassing the v2 filter. */
    private suspend fun injectLegacyEntry() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val field = PairedDeviceRepository::class.java.getDeclaredField("dataStore").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val dataStore = field.get(PairedDeviceRepository(app)) as androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val listSerializer = kotlinx.serialization.builtins.ListSerializer(PairedDevice.serializer())
        dataStore.updateData { prefs ->
            val current = prefs[PairedDeviceRepository.PAIRED_DEVICES_JSON]
            val list = json.decodeFromString(listSerializer, current ?: "[]") +
                PairedDevice(deviceId = "old-pc", deviceName = "Old", sharedSecret = "dGVzdC1zZWNyZXQ=")
            prefs.toMutablePreferences().apply {
                this[PairedDeviceRepository.PAIRED_DEVICES_JSON] = json.encodeToString(listSerializer, list)
            }
        }
    }
}
