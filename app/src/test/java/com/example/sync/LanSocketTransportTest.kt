package com.example.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.domain.sync.models.PairedDevice
import com.example.domain.sync.models.SyncFrame
import com.example.domain.sync.models.SyncState
import com.example.network.sync.Base64Util
import com.example.network.sync.LanSocketTransport
import com.example.network.sync.QrPairingData
import com.example.repository.PairedDeviceRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.AEADBadTagException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LanSocketTransportTest {

    private lateinit var context: Context
    private lateinit var repository: PairedDeviceRepository
    private val testScope = TestScope()

    @Before
    fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        repository = PairedDeviceRepository(context)
        repository.clearAllPairedDevices()
    }

    // --- AES-256-GCM Crypto Tests ---

    @Test
    fun aes256Gcm_encryptionAndDecryptionRoundTrip() {
        val sharedSecret = LanSocketTransport.generateRandomSharedSecret()
        assertNotNull(sharedSecret)
        assertEquals(32, Base64Util.decode(sharedSecret).size)

        val frame: SyncFrame = SyncFrame.PayloadBatch(
            encryptedBatchJson = "{\"entries\":[{\"id\":\"1\",\"title\":\"Google\"}]}"
        )

        val encrypted = LanSocketTransport.encryptFrame(frame, sharedSecret)
        assertTrue(encrypted.size > 28) // 12 IV + 16 Tag + payload

        val decrypted = LanSocketTransport.decryptFrame(encrypted, sharedSecret)
        assertEquals(frame, decrypted)
    }

    @Test
    fun aes256Gcm_differentFramesEncryptionRoundTrip() {
        val sharedSecret = LanSocketTransport.generateRandomSharedSecret()

        val manifestFrame: SyncFrame = SyncFrame.ManifestExchange(manifestJson = "{\"version\":1}")
        val encManifest = LanSocketTransport.encryptFrame(manifestFrame, sharedSecret)
        val decManifest = LanSocketTransport.decryptFrame(encManifest, sharedSecret)
        assertEquals(manifestFrame, decManifest)

        val cancelFrame: SyncFrame = SyncFrame.CancelSync(reason = "Sync timed out")
        val encCancel = LanSocketTransport.encryptFrame(cancelFrame, sharedSecret)
        val decCancel = LanSocketTransport.decryptFrame(encCancel, sharedSecret)
        assertEquals(cancelFrame, decCancel)
    }

    @Test(expected = Exception::class)
    fun aes256Gcm_tamperedCiphertext_failsDecryption() {
        val sharedSecret = LanSocketTransport.generateRandomSharedSecret()
        val frame: SyncFrame = SyncFrame.SyncRequest(deviceId = "dev-1", deviceName = "Phone")

        val encrypted = LanSocketTransport.encryptFrame(frame, sharedSecret)

        // Tamper with a byte in ciphertext
        encrypted[encrypted.size - 1] = (encrypted[encrypted.size - 1] + 1).toByte()

        // Should throw AEADBadTagException or SecurityException
        LanSocketTransport.decryptFrame(encrypted, sharedSecret)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aes256Gcm_dataTooShort_throwsIllegalArgument() {
        val sharedSecret = LanSocketTransport.generateRandomSharedSecret()
        val shortData = ByteArray(15) // less than 28 bytes required
        LanSocketTransport.decryptFrame(shortData, sharedSecret)
    }

    // --- QR Code URI Parsing Tests ---

    @Test
    fun qrCodeUriParser_validUrl_parsesAllFields() {
        val uri = "vaultpass://pair?deviceId=desktop-999&name=MacBook%20Pro&token=secureToken123&ip=192.168.1.120&port=53853"
        val data = LanSocketTransport.parseQrPairingUri(uri)

        assertNotNull(data)
        assertEquals("desktop-999", data?.deviceId)
        assertEquals("MacBook Pro", data?.deviceName)
        assertEquals("secureToken123", data?.token)
        assertEquals("192.168.1.120", data?.ipAddress)
        assertEquals(53853, data?.port)
    }

    @Test
    fun qrCodeUriParser_defaultPortAppliedWhenOmitted() {
        val uri = "vaultpass://pair?deviceId=desktop-888&name=Workstation&token=tokABC&ip=10.0.0.5"
        val data = LanSocketTransport.parseQrPairingUri(uri)

        assertNotNull(data)
        assertEquals(53853, data?.port)
    }

    @Test
    fun qrCodeUriParser_malformedUrls_returnNull() {
        assertNull("Missing scheme", LanSocketTransport.parseQrPairingUri("https://pair?deviceId=1&name=x&token=y&ip=z"))
        assertNull("Missing token", LanSocketTransport.parseQrPairingUri("vaultpass://pair?deviceId=1&name=x&ip=z"))
        assertNull("Missing ip and ips", LanSocketTransport.parseQrPairingUri("vaultpass://pair?deviceId=1&name=x&token=y"))
        assertNull("Missing deviceId", LanSocketTransport.parseQrPairingUri("vaultpass://pair?name=x&token=y&ip=z"))
        assertNull("Random string", LanSocketTransport.parseQrPairingUri("not_a_valid_pairing_qr_code"))
    }

    @Test
    fun qrCodeUriParser_withCandidateIps_parsesListCorrectly() {
        val uri = "vaultpass://pair?deviceId=dev-123&name=Desktop&token=tok&ip=192.168.1.100&ips=192.168.1.100,10.0.0.15,172.16.0.5&port=53853"
        val data = LanSocketTransport.parseQrPairingUri(uri)

        assertNotNull(data)
        assertEquals("192.168.1.100", data?.primaryIpAddress)
        assertEquals(listOf("192.168.1.100", "10.0.0.15", "172.16.0.5"), data?.candidateIpAddresses)
    }

    @Test
    fun qrCodeUriParser_fallbackToSingleIp_whenIpsMissing() {
        val uri = "vaultpass://pair?deviceId=dev-123&name=Desktop&token=tok&ip=192.168.1.100"
        val data = LanSocketTransport.parseQrPairingUri(uri)

        assertNotNull(data)
        assertEquals("192.168.1.100", data?.primaryIpAddress)
        assertEquals(listOf("192.168.1.100"), data?.candidateIpAddresses)
    }

    @Test
    fun qrCodeUriParser_supportsPairingTokenParamName() {
        val uri = "vaultpass://pair?deviceId=dev-123&name=Desktop&pairingToken=tok456&ip=192.168.1.100"
        val data = LanSocketTransport.parseQrPairingUri(uri)

        assertNotNull(data)
        assertEquals("tok456", data?.pairingToken)
        assertEquals("tok456", data?.token)
    }

    @Test
    fun connectToPeerCandidates_emptyCandidates_returnsFailure() = runTest {
        val transport = LanSocketTransport(
            context = context,
            localDeviceId = "mobile-dev-1",
            localDeviceName = "Pixel 8",
            pairedDeviceRepository = repository,
            scope = this
        )

        val result = transport.connectToPeerCandidates(emptyList())
        assertTrue(result.isFailure)
        assertEquals(SyncState.IDLE, transport.syncState.value)
    }

    @Test
    fun connectToPeerCandidates_allUnreachable_returnsFailureWithAggregatedErrors() = runTest {
        val transport = LanSocketTransport(
            context = context,
            localDeviceId = "mobile-dev-1",
            localDeviceName = "Pixel 8",
            pairedDeviceRepository = repository,
            scope = this
        )

        val result = transport.connectToPeerCandidates(listOf("127.0.0.1", "127.0.0.2"), targetPort = 59998)
        assertTrue(result.isFailure)
        assertEquals(SyncState.ERROR, transport.syncState.value)
        val msg = result.exceptionOrNull()?.message
        assertNotNull(msg)
        assertTrue(msg!!.contains("127.0.0.1"))
        assertTrue(msg.contains("127.0.0.2"))
    }

    @Test
    fun connectToPeerCandidates_candidateSucceeds_returnsSuccess() = runTest {
        val server = java.net.ServerSocket(0)
        val port = server.localPort
        try {
            val transport = LanSocketTransport(
                context = context,
                localDeviceId = "mobile-dev-1",
                localDeviceName = "Pixel 8",
                pairedDeviceRepository = repository,
                scope = this
            )

            val result = transport.connectToPeerCandidates(listOf("127.0.0.1"), targetPort = port)
            assertTrue(result.isSuccess)
            assertEquals("127.0.0.1", result.getOrNull())
            transport.disconnect()
        } finally {
            server.close()
        }
    }

    // --- Mutual Acceptance Handshake Tests ---

    @Test
    fun mutualPairingHandshake_bothAccept_transitionsToPairedAndPersists() = runTest {
        val transport = LanSocketTransport(
            context = context,
            localDeviceId = "mobile-dev-1",
            localDeviceName = "Pixel 8",
            pairedDeviceRepository = repository,
            scope = this
        )

        // 1. Receiver gets PairingRequest from Desktop
        val pairingRequest = SyncFrame.PairingRequest(
            deviceId = "desktop-pc-1",
            deviceName = "Arch Linux Desktop",
            pairingToken = "handshake-token-xyz"
        )
        transport.handleIncomingFrame(pairingRequest)

        assertEquals(SyncState.AWAITING_LOCAL_APPROVAL, transport.syncState.value)
        assertEquals(pairingRequest, transport.pendingPairingRequest.value)

        // 2. Local user accepts
        transport.acceptPairing(pairingRequest)

        // State becomes PAIRED and saves to repository
        assertEquals(SyncState.PAIRED, transport.syncState.value)
        assertNull(transport.pendingPairingRequest.value)

        // Verify device saved in repository
        val pairedList = repository.getPairedDevices()
        assertEquals(1, pairedList.size)
        val saved = pairedList[0]
        assertEquals("desktop-pc-1", saved.deviceId)
        assertEquals("Arch Linux Desktop", saved.deviceName)
        assertNotNull(saved.sharedSecret)
    }

    @Test
    fun mutualPairingHandshake_localDeclines_transitionsToIdle() = runTest {
        val transport = LanSocketTransport(
            context = context,
            localDeviceId = "mobile-dev-1",
            localDeviceName = "Pixel 8",
            pairedDeviceRepository = repository,
            scope = this
        )

        val pairingRequest = SyncFrame.PairingRequest(
            deviceId = "desktop-pc-2",
            deviceName = "Mac Studio",
            pairingToken = "token-123"
        )
        transport.handleIncomingFrame(pairingRequest)
        assertEquals(SyncState.AWAITING_LOCAL_APPROVAL, transport.syncState.value)

        // Local user declines
        transport.declinePairing(pairingRequest)
        assertEquals(SyncState.IDLE, transport.syncState.value)
        assertNull(transport.pendingPairingRequest.value)

        // Repository remains empty
        assertTrue(repository.getPairedDevices().isEmpty())
    }

    @Test
    fun mutualPairingHandshake_remoteDeclines_transitionsToIdle() = runTest {
        val transport = LanSocketTransport(
            context = context,
            localDeviceId = "mobile-dev-1",
            localDeviceName = "Pixel 8",
            pairedDeviceRepository = repository,
            scope = this
        )

        transport.setSyncStateForTesting(SyncState.WAITING_FOR_REMOTE_APPROVAL)

        // Remote peer sends rejection
        val rejection = SyncFrame.PairingAcceptance(
            deviceId = "desktop-pc-3",
            isAccepted = false
        )
        transport.handleIncomingFrame(rejection)

        assertEquals(SyncState.IDLE, transport.syncState.value)
        assertTrue(repository.getPairedDevices().isEmpty())
    }

    @Test
    fun mutualPairingHandshake_remoteAccepts_transitionsToPaired() = runTest {
        val transport = LanSocketTransport(
            context = context,
            localDeviceId = "mobile-dev-1",
            localDeviceName = "Pixel 8",
            pairedDeviceRepository = repository,
            scope = this
        )

        val qrData = QrPairingData(
            deviceId = "desktop-pc-4",
            deviceName = "Ubuntu Laptop",
            token = "token-abc-123",
            ipAddress = "192.168.1.80"
        )

        transport.activePeerDeviceId = qrData.deviceId
        transport.activePeerDeviceName = qrData.deviceName
        transport.activePeerIpAddress = qrData.ipAddress
        transport.activeSharedSecret = LanSocketTransport.deriveSharedSecretFromToken(qrData.token)
        transport.setSyncStateForTesting(SyncState.WAITING_FOR_REMOTE_APPROVAL)

        // Remote sends acceptance
        val acceptance = SyncFrame.PairingAcceptance(
            deviceId = "desktop-pc-4",
            isAccepted = true
        )
        transport.handleIncomingFrame(acceptance)

        assertEquals(SyncState.PAIRED, transport.syncState.value)
        val paired = repository.getPairedDevices()
        assertEquals(1, paired.size)
        assertEquals("desktop-pc-4", paired[0].deviceId)
        assertEquals("Ubuntu Laptop", paired[0].deviceName)
    }

    @Test
    fun sharedSecretDerivation_deterministicAndValidLength() {
        val token = "random-pairing-token-string-12345"
        val secret1 = LanSocketTransport.deriveSharedSecretFromToken(token)
        val secret2 = LanSocketTransport.deriveSharedSecretFromToken(token)

        assertEquals(secret1, secret2)
        val decoded = Base64Util.decode(secret1)
        assertEquals(32, decoded.size)
    }

    @Test
    fun incomingSyncRequest_whenDeviceIsPaired_transitionsToAwaitingLocalApproval() = runTest {
        val transport = LanSocketTransport(
            context = context,
            localDeviceId = "mobile-dev-1",
            localDeviceName = "Pixel 8",
            pairedDeviceRepository = repository,
            scope = this
        )

        val pairedDevice = PairedDevice(
            deviceId = "desktop-pc-99",
            deviceName = "Work Desktop",
            sharedSecret = "dGVzdC1zZWNyZXQta2V5LTMyLWJ5dGVzLXRoYXQtaXMtYQ==",
            ipAddress = "192.168.1.55",
            port = 53853
        )
        repository.savePairedDevice(pairedDevice)

        val syncRequest = SyncFrame.SyncRequest(
            deviceId = "desktop-pc-99",
            deviceName = "Work Desktop"
        )
        transport.handleIncomingFrame(syncRequest)

        assertEquals(SyncState.AWAITING_LOCAL_APPROVAL, transport.syncState.value)
        assertEquals(syncRequest, transport.pendingSyncRequest.value)
        assertEquals("dGVzdC1zZWNyZXQta2V5LTMyLWJ5dGVzLXRoYXQtaXMtYQ==", transport.activeSharedSecret)
        assertEquals("desktop-pc-99", transport.activePeerDeviceId)
        assertEquals("Work Desktop", transport.activePeerDeviceName)
    }

    @Test
    fun incomingSyncRequest_whenDeviceIsNotPaired_transitionsToIdleAndDisconnects() = runTest {
        val transport = LanSocketTransport(
            context = context,
            localDeviceId = "mobile-dev-1",
            localDeviceName = "Pixel 8",
            pairedDeviceRepository = repository,
            scope = this
        )

        val syncRequest = SyncFrame.SyncRequest(
            deviceId = "unpaired-desktop-99",
            deviceName = "Hacker PC"
        )
        transport.handleIncomingFrame(syncRequest)

        assertEquals(SyncState.IDLE, transport.syncState.value)
        assertNull(transport.pendingSyncRequest.value)
        assertNull(transport.activeSharedSecret)
    }

    @Test
    fun isConnected_reflectsInitialDisconnectedState() = runTest {
        val transport = LanSocketTransport(
            context = context,
            localDeviceId = "mobile-dev-1",
            localDeviceName = "Pixel 8",
            pairedDeviceRepository = repository,
            scope = this
        )
        assertFalse(transport.isConnected)
    }

    @Test
    fun incomingSyncAcceptance_whenDeclined_transitionsToIdleAndDisconnects() = runTest {
        val transport = LanSocketTransport(
            context = context,
            localDeviceId = "mobile-dev-1",
            localDeviceName = "Pixel 8",
            pairedDeviceRepository = repository,
            scope = this
        )
        transport.setSyncStateForTesting(SyncState.CONNECTING)

        val declinedFrame = SyncFrame.SyncAcceptance(
            deviceId = "remote-pc",
            isAccepted = false
        )
        transport.handleIncomingFrame(declinedFrame)

        assertEquals(SyncState.IDLE, transport.syncState.value)
        assertFalse(transport.isConnected)
    }

    @Test
    fun incomingCancelSync_transitionsToIdleAndDisconnects() = runTest {
        val transport = LanSocketTransport(
            context = context,
            localDeviceId = "mobile-dev-1",
            localDeviceName = "Pixel 8",
            pairedDeviceRepository = repository,
            scope = this
        )
        transport.setSyncStateForTesting(SyncState.PAIRING)

        val cancelFrame = SyncFrame.CancelSync(reason = "User aborted")
        transport.handleIncomingFrame(cancelFrame)

        assertEquals(SyncState.IDLE, transport.syncState.value)
        assertFalse(transport.isConnected)
    }

    @Test
    fun declineSync_disconnectsAndResetsState() = runTest {
        val transport = LanSocketTransport(
            context = context,
            localDeviceId = "mobile-dev-1",
            localDeviceName = "Pixel 8",
            pairedDeviceRepository = repository,
            scope = this
        )
        transport.setSyncStateForTesting(SyncState.AWAITING_LOCAL_APPROVAL)
        transport.declineSync()

        assertEquals(SyncState.IDLE, transport.syncState.value)
        assertFalse(transport.isConnected)
    }
}
