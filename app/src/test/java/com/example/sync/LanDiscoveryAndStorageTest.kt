package com.example.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.domain.sync.models.BeaconPayload
import com.example.domain.sync.models.PairedDevice
import com.example.domain.sync.models.SyncFrame
import com.example.domain.sync.models.SyncState
import com.example.network.sync.LanDiscoveryManager
import com.example.network.sync.ReverseSyncSignal
import com.example.repository.PairedDeviceRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LanDiscoveryAndStorageTest {

    private lateinit var context: Context
    private lateinit var repository: PairedDeviceRepository
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Before
    fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        repository = PairedDeviceRepository(context)
        repository.clearAllPairedDevices()
    }

    // --- Serialization Tests ---

    @Test
    fun pairedDevice_serializationRoundTrip_maintainsIntegrity() {
        val original = PairedDevice(
            deviceId = "device-uuid-1234",
            deviceName = "Armaan's Pixel 8",
            sharedSecret = "dGVzdC1zaGFyZWQtc2VjcmV0LWtleS0yNTYtYml0cw==",
            ipAddress = "192.168.1.50",
            port = 53853,
            pairedAt = 1717900000000L,
            lastSyncAt = 1717900500000L,
            isOnline = true
        )

        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<PairedDevice>(serialized)

        assertEquals(original, deserialized)
        assertTrue(serialized.contains("device-uuid-1234"))
        assertTrue(serialized.contains("Armaan's Pixel 8"))
    }

    @Test
    fun syncFrame_sealedClassSerialization_polymorphicRoundTrip() {
        val request: SyncFrame = SyncFrame.PairingRequest(
            deviceId = "dev-1",
            deviceName = "Phone",
            pairingToken = "token-999"
        )
        val requestJson = json.encodeToString(request)
        assertTrue("Discriminator should be PairingRequest without package name", requestJson.contains("PairingRequest"))
        assertFalse("Wire format must not leak Android package prefix", requestJson.contains("com.example"))
        val decodedRequest = json.decodeFromString<SyncFrame>(requestJson)
        assertEquals(request, decodedRequest)

        val acceptance: SyncFrame = SyncFrame.PairingAcceptance(
            deviceId = "dev-2",
            isAccepted = true
        )
        val acceptanceJson = json.encodeToString(acceptance)
        val decodedAcceptance = json.decodeFromString<SyncFrame>(acceptanceJson)
        assertEquals(acceptance, decodedAcceptance)

        val batch: SyncFrame = SyncFrame.PayloadBatch(
            encryptedBatchJson = "{\"encrypted\":\"data\"}"
        )
        val batchJson = json.encodeToString(batch)
        val decodedBatch = json.decodeFromString<SyncFrame>(batchJson)
        assertEquals(batch, decodedBatch)

        val cancel: SyncFrame = SyncFrame.CancelSync(reason = "User aborted")
        val cancelJson = json.encodeToString(cancel)
        val decodedCancel = json.decodeFromString<SyncFrame>(cancelJson)
        assertEquals(cancel, decodedCancel)
    }

    @Test
    fun syncState_enumCoverage() {
        assertEquals(9, SyncState.values().size)
        assertEquals(SyncState.IDLE, SyncState.valueOf("IDLE"))
        assertEquals(SyncState.SYNCING, SyncState.valueOf("SYNCING"))
    }

    // --- PairedDeviceRepository DataStore Tests ---

    @Test
    fun pairedDeviceRepository_addUpdateAndUnpair() = runTest {
        assertTrue("Initially empty", repository.getPairedDevices().isEmpty())

        val device1 = PairedDevice(
            deviceId = "dev-alpha",
            deviceName = "Laptop",
            sharedSecret = "secret-key-1"
        )
        val device2 = PairedDevice(
            deviceId = "dev-beta",
            deviceName = "Tablet",
            sharedSecret = "secret-key-2"
        )

        // 1. Add device 1
        repository.savePairedDevice(device1)
        var devices = repository.getPairedDevices()
        assertEquals(1, devices.size)
        assertEquals("dev-alpha", devices[0].deviceId)

        // 2. Add device 2
        repository.savePairedDevice(device2)
        devices = repository.getPairedDevices()
        assertEquals(2, devices.size)

        // 3. Update device 1 name
        val updatedDevice1 = device1.copy(deviceName = "Laptop Pro")
        repository.savePairedDevice(updatedDevice1)
        devices = repository.getPairedDevices()
        assertEquals("Should not increase size on update", 2, devices.size)
        assertEquals("Laptop Pro", devices.first { it.deviceId == "dev-alpha" }.deviceName)

        // 4. Update lastSyncAt
        val syncTimestamp = 9876543210L
        repository.updateLastSync("dev-alpha", syncTimestamp)
        devices = repository.getPairedDevices()
        assertEquals(syncTimestamp, devices.first { it.deviceId == "dev-alpha" }.lastSyncAt)

        // 5. Unpair device 2
        repository.unpairDevice("dev-beta")
        devices = repository.getPairedDevices()
        assertEquals(1, devices.size)
        assertEquals("dev-alpha", devices[0].deviceId)

        // 6. Observe via flow
        val flowList = repository.observePairedDevices().first()
        assertEquals(1, flowList.size)

        // 7. Clear all
        repository.clearAllPairedDevices()
        assertTrue("Should be empty after clear", repository.getPairedDevices().isEmpty())
    }

    // --- LanDiscoveryManager Tests ---

    @Test
    fun lanDiscoveryManager_beaconJsonParsing_validAndMalformed() {
        val testScope = TestScope()
        val manager = LanDiscoveryManager(
            context = context,
            deviceId = "local-device-id",
            deviceName = "Local Device",
            scope = testScope
        )

        val validBeaconJson = """{"deviceId":"remote-1","deviceName":"MacBook","port":53853,"type":"BEACON"}"""
        val parsed = manager.parseBeacon(validBeaconJson)
        assertNotNull(parsed)
        assertEquals("remote-1", parsed?.deviceId)
        assertEquals("MacBook", parsed?.deviceName)
        assertEquals(53853, parsed?.port)
        assertEquals("BEACON", parsed?.type)

        // Missing deviceId / malformed
        val malformedJson = """{"invalid_field":123}"""
        assertNull(manager.parseBeacon(malformedJson))

        // Wrong type
        val wrongTypeJson = """{"deviceId":"remote-1","deviceName":"MacBook","port":53853,"type":"OTHER"}"""
        assertNull(manager.parseBeacon(wrongTypeJson))

        // Invalid JSON string
        assertNull(manager.parseBeacon("NOT_JSON"))
    }

    @Test
    fun lanDiscoveryManager_handlesIncomingBeaconsAndIgnoresSelf() {
        val testScope = TestScope()
        val manager = LanDiscoveryManager(
            context = context,
            deviceId = "local-uuid",
            deviceName = "Local Device",
            scope = testScope
        )

        val remoteBeacon = """{"deviceId":"remote-uuid","deviceName":"Desktop PC","port":53853,"type":"BEACON"}"""
        manager.handleIncomingMessage(remoteBeacon, "192.168.1.100")

        val discovered = manager.discoveredDevices.value
        assertEquals(1, discovered.size)
        val presence = discovered["remote-uuid"]
        assertNotNull(presence)
        assertEquals("Desktop PC", presence?.deviceName)
        assertEquals("192.168.1.100", presence?.ipAddress)
        assertEquals(53853, presence?.port)
        assertTrue(manager.isDeviceOnline("remote-uuid"))

        // Self packet should be dropped
        val selfBeacon = """{"deviceId":"local-uuid","deviceName":"Local Device","port":53853,"type":"BEACON"}"""
        manager.handleIncomingMessage(selfBeacon, "192.168.1.101")
        assertEquals("Self beacon must not be added to discovered devices", 1, manager.discoveredDevices.value.size)
        assertFalse(manager.discoveredDevices.value.containsKey("local-uuid"))
    }

    @Test
    fun lanDiscoveryManager_peerTimeoutPruning() {
        val testScope = TestScope()
        val manager = LanDiscoveryManager(
            context = context,
            deviceId = "local-uuid",
            deviceName = "Local Device",
            scope = testScope
        )

        val remoteBeacon = """{"deviceId":"remote-uuid","deviceName":"Desktop PC","port":53853,"type":"BEACON"}"""
        manager.handleIncomingMessage(remoteBeacon, "192.168.1.100")
        assertEquals(1, manager.discoveredDevices.value.size)

        val initialPresence = manager.discoveredDevices.value["remote-uuid"]!!
        val baselineTime = initialPresence.lastSeen

        // 1. Within TTL (e.g. 5 seconds later)
        manager.pruneExpiredPeers(currentTime = baselineTime + 5_000L)
        assertEquals("Should not prune within 10s TTL", 1, manager.discoveredDevices.value.size)

        // 2. Exceeding TTL (e.g. 10.001 seconds later)
        manager.pruneExpiredPeers(currentTime = baselineTime + 10_001L)
        assertTrue("Expired peer should be pruned after 10s", manager.discoveredDevices.value.isEmpty())
        assertFalse(manager.isDeviceOnline("remote-uuid"))
    }

    @Test
    fun lanDiscoveryManager_localDeviceIdPersistence() {
        val id1 = LanDiscoveryManager.getOrCreateDeviceId(context)
        assertFalse(id1.isBlank())

        val id2 = LanDiscoveryManager.getOrCreateDeviceId(context)
        assertEquals("Should return the same persisted device ID", id1, id2)
    }

    @Test
    fun lanDiscoveryManager_networkUtilitiesDoNotThrow() {
        val testScope = TestScope()
        val manager = LanDiscoveryManager(
            context = context,
            deviceId = "local-uuid",
            deviceName = "Local Device",
            scope = testScope
        )

        // Call network utilities to ensure no unhandled exceptions
        val localIp = manager.getLocalIpAddress()
        // In Robolectric or host environment, it might be null or loopback-adjacent, but must not crash
        val broadcastAddresses = manager.getBroadcastAddresses()
        assertTrue("Must include at least fallback 255.255.255.255", broadcastAddresses.isNotEmpty())
        assertTrue(broadcastAddresses.contains(InetAddress.getByName("255.255.255.255")))
    }

    @Test
    fun lanDiscoveryManager_sendReverseConnectSignal_doesNotThrow() = runTest {
        val manager = LanDiscoveryManager(
            context = context,
            deviceId = "local-uuid",
            deviceName = "Local Device",
            scope = this
        )

        // Should complete safely without exception
        manager.sendReverseConnectSignal(
            desktopIp = "127.0.0.1",
            pairingToken = "test-token",
            mobileIp = "127.0.0.1"
        )
    }

    @Test
    fun pairedDeviceRepository_getPairedDevice_returnsCorrectDeviceOrNull() = runTest {
        val device1 = PairedDevice(
            deviceId = "dev-101",
            deviceName = "Work PC",
            sharedSecret = "secret-101",
            ipAddress = "192.168.1.101"
        )
        val device2 = PairedDevice(
            deviceId = "dev-102",
            deviceName = "Home PC",
            sharedSecret = "secret-102",
            ipAddress = "192.168.1.102"
        )
        repository.savePairedDevice(device1)
        repository.savePairedDevice(device2)

        val retrieved1 = repository.getPairedDevice("dev-101")
        assertNotNull(retrieved1)
        assertEquals("Work PC", retrieved1?.deviceName)

        val retrieved2 = repository.getPairedDevice("dev-102")
        assertNotNull(retrieved2)
        assertEquals("Home PC", retrieved2?.deviceName)

        val notFound = repository.getPairedDevice("dev-999")
        assertNull(notFound)
    }

    @Test
    fun reverseSyncSignal_serializationRoundTrip_matchesSpecification() {
        val signal = ReverseSyncSignal(
            type = "REVERSE_SYNC_REQUEST",
            deviceId = "desktop-abc",
            deviceName = "My Desktop",
            mobileIp = "192.168.1.75",
            mobilePort = 53853
        )
        val jsonStr = json.encodeToString(signal)
        assertTrue(jsonStr.contains("REVERSE_SYNC_REQUEST"))
        assertTrue(jsonStr.contains("192.168.1.75"))
        assertTrue(jsonStr.contains("mobilePort"))

        val decoded = json.decodeFromString<ReverseSyncSignal>(jsonStr)
        assertEquals(signal, decoded)
    }

    @Test
    fun lanDiscoveryManager_sendReverseSyncSignal_doesNotThrow() = runTest {
        val manager = LanDiscoveryManager(
            context = context,
            deviceId = "local-uuid",
            deviceName = "Local Device",
            scope = this
        )

        manager.sendReverseSyncSignal(
            desktopIp = "127.0.0.1",
            mobileIp = "127.0.0.1",
            mobilePort = 53853
        )
    }
}
