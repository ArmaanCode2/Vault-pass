package com.example.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.VaultPassApplication
import com.example.domain.sync.models.SyncFrame
import com.example.domain.sync.models.SyncState
import com.example.network.sync.AndroidPairKeyProtector
import com.example.network.sync.LanSocketTransport
import com.example.network.sync.QrPairingData
import com.example.repository.PairedDeviceRepository
import com.example.security.CryptoManager
import com.vaultpass.synccore.HandshakeInitiator
import com.vaultpass.synccore.HandshakeMode
import com.vaultpass.synccore.Hex
import com.vaultpass.synccore.SyncCrypto
import com.vaultpass.synccore.SyncError
import com.vaultpass.synccore.SyncTimeouts
import com.vaultpass.synccore.WireV2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * End-to-end checks of LAN sync protocol v2 on the phone, over real sockets, against a scripted
 * desktop built from the shared protocol core.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncSessionSecurityTest {

    private val port = 54881

    private lateinit var repository: PairedDeviceRepository
    private lateinit var cryptoManager: CryptoManager
    private lateinit var protector: AndroidPairKeyProtector
    private lateinit var scope: CoroutineScope
    private lateinit var transport: LanSocketTransport
    private val emittedFrames = CopyOnWriteArrayList<SyncFrame>()

    @Before
    fun setUp() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>() as VaultPassApplication
        repository = PairedDeviceRepository(app)
        repository.clearAllPairedDevices()
        cryptoManager = CryptoManager(app.container.settingsRepository)
        cryptoManager.injectSoftwareDek(SyncCrypto.randomBytes(32))
        protector = AndroidPairKeyProtector(cryptoManager)
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        transport = newTransport(port)
        emittedFrames.clear()
        scope.launch { transport.receiveFrames().collect { emittedFrames.add(it) } }
        transport.startServer()
    }

    @After
    fun tearDown() {
        transport.stopServer()
        scope.cancel()
    }

    private fun newTransport(
        listenPort: Int,
        timeoutMs: Int? = null,
        keepAliveMs: Long = LanSocketTransport.KEEP_ALIVE_MS
    ) = LanSocketTransport(
        context = ApplicationProvider.getApplicationContext(),
        localDeviceId = "phone-under-test",
        localDeviceName = "Pixel Under Test",
        pairedDeviceRepository = repository,
        scope = scope,
        pairKeyProtector = protector,
        listenPort = listenPort,
        firstFrameTimeoutMs = timeoutMs ?: SyncTimeouts.HANDSHAKE_MS,
        idleReadTimeoutMs = timeoutMs ?: SyncTimeouts.IDLE_MS,
        keepAliveMs = keepAliveMs
    )

    // --- Devices that must get nothing ---

    @Test
    fun v1Peer_isDroppedWithoutReply() {
        connect().use { socket ->
            val bytes = """{"type":"SyncRequest","deviceId":"old-pc","deviceName":"Old"}""".toByteArray()
            DataOutputStream(socket.getOutputStream()).apply {
                writeByte(0)
                writeInt(bytes.size)
                write(bytes)
                flush()
            }
            assertEquals("Phone must not answer", 0, drainUntilClosed(socket).size)
        }
        assertNull(transport.pendingSyncRequest.value)
    }

    @Test
    fun unknownDevice_getsNoHandshakeReply() = runBlocking {
        pairTestDesktop(repository, protector, "desktop-1", "Work PC")
        connect().use { socket ->
            sendHello(socket, HandshakeMode.SYNC, SyncCrypto.randomBytes(32))
            assertEquals(0, drainUntilClosed(socket).size)
        }
        assertNull(transport.pendingSyncRequest.value)
    }

    @Test
    fun pairingHandshakes_areNeverAnswered() {
        // The phone pairs only by scanning a QR code; it never answers pairing requests.
        connect().use { socket ->
            sendHello(socket, HandshakeMode.PAIR, SyncCrypto.randomBytes(32))
            assertEquals(0, drainUntilClosed(socket).size)
        }
    }

    @Test
    fun lockedVault_refusesSyncHandshakes() = runBlocking {
        val key = pairTestDesktop(repository, protector, "desktop-1", "Work PC")
        cryptoManager.clearSoftwareDek()
        connect().use { socket ->
            sendHello(socket, HandshakeMode.SYNC, key)
            assertEquals(0, drainUntilClosed(socket).size)
        }
    }

    // --- Syncs started by a paired desktop ---

    @Test
    fun pairedDesktop_getsThePhoneVaultOnlyAfterApproval() = runBlocking {
        val key = pairTestDesktop(repository, protector, "desktop-1", "Work PC")
        ScriptedDesktop.connect(port, key).use { desktop ->
            desktop.send(SyncFrame.SyncRequest("desktop-1", "Work PC"))
            assertTrue(waitUntil { transport.pendingSyncRequest.value != null })
            assertEquals(LanSocketTransport.SyncRole.RESPONDER, transport.syncRole)

            // Not approved yet: nothing may go out.
            assertFalse(transport.canSendVaultData())
            assertFalse(transport.sendFrame(SyncFrame.PayloadBatch("phone-vault")))
            desktop.socket.soTimeout = 700
            try {
                desktop.receive()
                fail("Nothing should arrive before approval")
            } catch (expected: SocketTimeoutException) {
            }
            desktop.socket.soTimeout = 5000

            assertTrue(transport.acceptSync())
            assertEquals(SyncFrame.SyncAcceptance("phone-under-test", true), desktop.receive())

            desktop.send(SyncFrame.PayloadBatch("desktop-vault"))
            assertTrue(waitUntil { emittedFrames.contains(SyncFrame.PayloadBatch("desktop-vault")) })
            assertTrue(transport.sendFrame(SyncFrame.PayloadBatch("phone-vault")))
            assertEquals(SyncFrame.PayloadBatch("phone-vault"), desktop.receive())
        }
    }

    @Test
    fun vaultDataBeforeApproval_endsTheSession() = runBlocking {
        val key = pairTestDesktop(repository, protector, "desktop-1", "Work PC")
        ScriptedDesktop.connect(port, key).use { desktop ->
            desktop.send(SyncFrame.SyncRequest("desktop-1", "Work PC"))
            assertTrue(waitUntil { transport.pendingSyncRequest.value != null })
            desktop.send(SyncFrame.PayloadBatch("injected"))
            assertEquals(0, drainUntilClosed(desktop.socket).size)
        }
        assertTrue(waitUntil { transport.pendingSyncRequest.value == null })
        assertTrue(emittedFrames.none { it is SyncFrame.PayloadBatch })
    }

    @Test
    fun syncRequestClaimingAnotherDevice_isRejected() = runBlocking {
        val key = pairTestDesktop(repository, protector, "desktop-1", "Work PC")
        pairTestDesktop(repository, protector, "desktop-2", "Laptop")
        ScriptedDesktop.connect(port, key).use { desktop ->
            desktop.send(SyncFrame.SyncRequest("desktop-2", "Laptop"))
            assertEquals(0, drainUntilClosed(desktop.socket).size)
        }
        assertNull(transport.pendingSyncRequest.value)
    }

    @Test
    fun secondDevice_cantTakeOverAnActiveSession() = runBlocking {
        val key = pairTestDesktop(repository, protector, "desktop-1", "Work PC")
        ScriptedDesktop.connect(port, key).use { desktop ->
            desktop.send(SyncFrame.SyncRequest("desktop-1", "Work PC"))
            assertTrue(waitUntil { transport.pendingSyncRequest.value != null })

            connect().use { intruder -> assertEquals(0, drainUntilClosed(intruder).size) }
            assertNotNull("The real session is untouched", transport.pendingSyncRequest.value)
            assertTrue(transport.acceptSync())
            assertEquals(SyncFrame.SyncAcceptance("phone-under-test", true), desktop.receive())
        }
    }

    @Test
    fun silentPeer_isDroppedAfterTheTimeout_andFreesTheConnectionSlot() = runBlocking {
        val key = pairTestDesktop(repository, protector, "desktop-1", "Work PC")
        val quickPort = port + 1
        val quick = newTransport(quickPort, timeoutMs = 400)
        quick.startServer()
        try {
            val started = System.currentTimeMillis()
            connect(quickPort).use { silent -> drainUntilClosed(silent) }
            assertTrue("Silent peer should be dropped quickly", System.currentTimeMillis() - started < 4000)

            // The single connection slot is free again: a paired desktop is served.
            ScriptedDesktop.connect(quickPort, key).use { desktop ->
                desktop.send(SyncFrame.SyncRequest("desktop-1", "Work PC"))
                assertTrue(waitUntil { quick.pendingSyncRequest.value != null })
            }
        } finally {
            quick.stopServer()
        }
    }

    // --- Syncs started on the phone ---

    @Test
    fun phoneStartedSync_completesWithThePairedDesktop() = runBlocking {
        ServerSocket(0).use { fakeDesktop ->
            val key = pairTestDesktop(repository, protector, "desktop-4", "Desk", "127.0.0.1", fakeDesktop.localPort)
            val device = repository.getPairedDevice("desktop-4")!!
            val start = scope.async { transport.startOutgoingSync(device) }

            ScriptedDesktop.accept(fakeDesktop, pairKey = key)!!.use { desktop ->
                assertEquals(LanSocketTransport.OutgoingSyncResult.STARTED, start.await())
                assertEquals(SyncFrame.SyncRequest("phone-under-test", "Pixel Under Test"), desktop.receive())

                desktop.send(SyncFrame.SyncAcceptance("desktop-4", true))
                assertTrue(waitUntil { emittedFrames.contains(SyncFrame.SyncAcceptance("desktop-4", true)) })
                assertTrue(transport.canSendVaultData())
                assertTrue(transport.sendFrame(SyncFrame.PayloadBatch("phone-vault")))
                assertEquals(SyncFrame.PayloadBatch("phone-vault"), desktop.receive())
            }
        }
    }

    @Test
    fun phoneStartedSync_toAnImpostor_fails() = runBlocking {
        ServerSocket(0).use { impostor ->
            pairTestDesktop(repository, protector, "desktop-4", "Desk", "127.0.0.1", impostor.localPort)
            val device = repository.getPairedDevice("desktop-4")!!
            val start = scope.async { transport.startOutgoingSync(device) }

            // The impostor doesn't hold the pair key, so it can't complete the handshake.
            assertNull(ScriptedDesktop.accept(impostor, pairKey = SyncCrypto.randomBytes(32)))
            assertEquals(LanSocketTransport.OutgoingSyncResult.FAILED, start.await())
            assertFalse(transport.isConnected)
        }
    }

    // --- Sync session v3: plan, decision and confirmation frames ---

    @Test
    fun aPlanBeforeApproval_isRefused_asAProtocolError() = runBlocking {
        val key = pairTestDesktop(repository, protector, "desktop-1", "Work PC")
        ScriptedDesktop.connect(port, key).use { desktop ->
            desktop.send(SyncFrame.SyncRequest("desktop-1", "Work PC"))
            assertTrue(waitUntil { transport.pendingSyncRequest.value != null })
            desktop.send(SyncFrame.MergePlanBatch("[]"))
            assertEquals(0, drainUntilClosed(desktop.socket).size)
        }
        assertTrue(waitUntil { !transport.isConnected })
        assertEquals(SyncError.PROTOCOL, transport.lastFailure())
        assertTrue(emittedFrames.none { it is SyncFrame.MergePlanBatch })
    }

    @Test
    fun theApprover_neverSendsAPlan_andAnswersOnlyACompletePlan_once() = runBlocking {
        val key = pairTestDesktop(repository, protector, "desktop-1", "Work PC")
        ScriptedDesktop.connect(port, key).use { desktop ->
            desktop.send(SyncFrame.SyncRequest("desktop-1", "Work PC"))
            assertTrue(waitUntil { transport.pendingSyncRequest.value != null })
            assertTrue(transport.acceptSync())
            assertEquals(SyncFrame.SyncAcceptance("phone-under-test", true), desktop.receive())

            assertFalse("Only the device that started the sync plans it", transport.sendFrame(SyncFrame.MergePlanBatch("[]")))
            assertFalse("No plan, nothing to decide", transport.sendFrame(SyncFrame.MergePlanDecision(true)))

            desktop.send(SyncFrame.MergePlanBatch("[]", part = 0, last = false))
            assertTrue(waitUntil { emittedFrames.count { it is SyncFrame.MergePlanBatch } == 1 })
            assertFalse("Half a plan can't be answered", transport.sendFrame(SyncFrame.MergePlanDecision(true)))

            desktop.send(SyncFrame.MergePlanBatch("[]", part = 1, last = true, expectedFingerprint = "fp"))
            assertTrue(waitUntil { emittedFrames.count { it is SyncFrame.MergePlanBatch } == 2 })
            assertTrue(transport.sendFrame(SyncFrame.MergePlanDecision(true)))
            assertEquals(SyncFrame.MergePlanDecision(true), desktop.receive())
            assertNull("A normal session has no failure", transport.lastFailure())

            // The plan is complete: another part is refused.
            desktop.send(SyncFrame.MergePlanBatch("[]", part = 2, last = true))
            assertEquals(0, drainUntilClosed(desktop.socket).size)
        }
        assertTrue(waitUntil { !transport.isConnected })
        assertEquals(SyncError.PROTOCOL, transport.lastFailure())
    }

    @Test
    fun theApprover_refusesADecision() = runBlocking {
        val key = pairTestDesktop(repository, protector, "desktop-1", "Work PC")
        ScriptedDesktop.connect(port, key).use { desktop ->
            desktop.send(SyncFrame.SyncRequest("desktop-1", "Work PC"))
            assertTrue(waitUntil { transport.pendingSyncRequest.value != null })
            assertTrue(transport.acceptSync())
            desktop.receive()
            desktop.send(SyncFrame.MergePlanDecision(true))
            assertEquals(0, drainUntilClosed(desktop.socket).size)
        }
        assertTrue(waitUntil { !transport.isConnected })
        assertEquals(SyncError.PROTOCOL, transport.lastFailure())
        assertTrue(emittedFrames.none { it is SyncFrame.MergePlanDecision })
    }

    @Test
    fun syncFinished_isAcceptedOnce() = runBlocking {
        val key = pairTestDesktop(repository, protector, "desktop-1", "Work PC")
        ScriptedDesktop.connect(port, key).use { desktop ->
            desktop.send(SyncFrame.SyncRequest("desktop-1", "Work PC"))
            assertTrue(waitUntil { transport.pendingSyncRequest.value != null })
            assertTrue(transport.acceptSync())
            desktop.receive()

            desktop.send(SyncFrame.SyncFinished("", "fingerprint", 0))
            assertTrue(waitUntil { emittedFrames.any { it is SyncFrame.SyncFinished } })
            assertTrue(transport.sendFrame(SyncFrame.SyncFinished("", "fingerprint", 0)))
            assertEquals(SyncFrame.SyncFinished("", "fingerprint", 0), desktop.receive())

            desktop.send(SyncFrame.SyncFinished("", "fingerprint", 0))
            assertEquals(0, drainUntilClosed(desktop.socket).size)
        }
        assertTrue(waitUntil { !transport.isConnected })
        assertEquals(SyncError.PROTOCOL, transport.lastFailure())
        assertEquals(1, emittedFrames.count { it is SyncFrame.SyncFinished })
    }

    @Test
    fun aCodedCancel_isKeptAsTheReasonTheSessionEnded() = runBlocking {
        val key = pairTestDesktop(repository, protector, "desktop-1", "Work PC")
        ScriptedDesktop.connect(port, key).use { desktop ->
            desktop.send(SyncFrame.SyncRequest("desktop-1", "Work PC"))
            assertTrue(waitUntil { transport.pendingSyncRequest.value != null })
            desktop.send(SyncFrame.CancelSync("too much", SyncError.TOO_MUCH_DATA.code))
            assertEquals(0, drainUntilClosed(desktop.socket).size)
        }
        assertTrue(waitUntil { !transport.isConnected })
        assertEquals(SyncError.TOO_MUCH_DATA, transport.lastFailure())
    }

    @Test
    fun aPeerFallingSilentMidSession_endsItAsTimedOut() = runBlocking {
        val key = pairTestDesktop(repository, protector, "desktop-1", "Work PC")
        val quickPort = port + 2
        val quick = newTransport(quickPort, timeoutMs = 400)
        quick.startServer()
        try {
            ScriptedDesktop.connect(quickPort, key).use { desktop ->
                desktop.send(SyncFrame.SyncRequest("desktop-1", "Work PC"))
                assertTrue(waitUntil { quick.pendingSyncRequest.value != null })
                // Then nothing: the idle limit ends the session and says why.
                assertTrue(waitUntil { !quick.isConnected })
            }
            assertEquals(SyncError.TIMED_OUT, quick.lastFailure())
        } finally {
            quick.stopServer()
        }
    }

    @Test
    fun theReviewer_sendsItsPlanOnlyAfterAcceptance_andTakesOneDecision() = runBlocking {
        ServerSocket(0).use { fakeDesktop ->
            val key = pairTestDesktop(repository, protector, "desktop-4", "Desk", "127.0.0.1", fakeDesktop.localPort)
            val device = repository.getPairedDevice("desktop-4")!!
            val start = scope.async { transport.startOutgoingSync(device) }

            ScriptedDesktop.accept(fakeDesktop, pairKey = key)!!.use { desktop ->
                assertEquals(LanSocketTransport.OutgoingSyncResult.STARTED, start.await())
                desktop.receive()
                assertFalse("Not before the desktop agreed", transport.sendFrame(SyncFrame.MergePlanBatch("[]")))
                assertFalse("Only the approver answers a plan", transport.sendFrame(SyncFrame.MergePlanDecision(true)))

                desktop.send(SyncFrame.SyncAcceptance("desktop-4", true))
                assertTrue(waitUntil { transport.canSendVaultData() })
                assertTrue(transport.sendFrame(SyncFrame.MergePlanBatch("[]", part = 0, last = true, expectedFingerprint = "fp")))
                assertEquals(SyncFrame.MergePlanBatch("[]", 0, true, "fp"), desktop.receive())

                desktop.send(SyncFrame.MergePlanDecision(true))
                assertTrue(waitUntil { emittedFrames.contains(SyncFrame.MergePlanDecision(true)) })
                desktop.send(SyncFrame.MergePlanDecision(true))
                assertEquals(0, drainUntilClosed(desktop.socket).size)
            }
            assertTrue(waitUntil { !transport.isConnected })
            assertEquals(SyncError.PROTOCOL, transport.lastFailure())
            assertEquals(1, emittedFrames.count { it is SyncFrame.MergePlanDecision })
        }
    }

    @Test
    fun theReviewer_refusesADecisionBeforeItsPlan_andAPlanFromThePeer() = runBlocking {
        for (intruding in listOf<SyncFrame>(SyncFrame.MergePlanDecision(true), SyncFrame.MergePlanBatch("[]"))) {
            ServerSocket(0).use { fakeDesktop ->
                val key = pairTestDesktop(repository, protector, "desktop-4", "Desk", "127.0.0.1", fakeDesktop.localPort)
                val device = repository.getPairedDevice("desktop-4")!!
                val start = scope.async { transport.startOutgoingSync(device) }

                ScriptedDesktop.accept(fakeDesktop, pairKey = key)!!.use { desktop ->
                    assertEquals(LanSocketTransport.OutgoingSyncResult.STARTED, start.await())
                    desktop.receive()
                    desktop.send(SyncFrame.SyncAcceptance("desktop-4", true))
                    desktop.send(intruding)
                    assertEquals(0, drainUntilClosed(desktop.socket).size)
                }
                assertTrue(waitUntil { !transport.isConnected })
                assertEquals(intruding::class.simpleName, SyncError.PROTOCOL, transport.lastFailure())
            }
        }
        assertTrue(emittedFrames.none { it is SyncFrame.MergePlanDecision || it is SyncFrame.MergePlanBatch })
    }

    // --- Keep-alive ---

    @Test
    fun keepAlive_holdsAQuietSyncOpenPastTheIdleLimit_andNeverReachesTheApp() = runBlocking {
        val key = pairTestDesktop(repository, protector, "desktop-1", "Work PC")
        val quickPort = port + 3
        val quick = newTransport(quickPort, timeoutMs = 1_500, keepAliveMs = 300L)
        val quickFrames = CopyOnWriteArrayList<SyncFrame>()
        scope.launch { quick.receiveFrames().collect { quickFrames.add(it) } }
        quick.startServer()
        try {
            ScriptedDesktop.connect(quickPort, key).use { desktop ->
                val fromPhone = desktop.collectInBackground()
                desktop.send(SyncFrame.SyncRequest("desktop-1", "Work PC"))
                assertTrue(waitUntil { quick.pendingSyncRequest.value != null })
                assertTrue(quick.acceptSync())
                desktop.keepAlive(300L)

                // Nobody sends anything real for three times the idle limit.
                Thread.sleep(4_500)
                assertTrue("The session must survive a long quiet review", quick.isConnected)
                assertNull(quick.lastFailure())
                assertTrue("The phone sends keep-alives", fromPhone.count { it is SyncFrame.KeepAlive } >= 5)

                // And then it completes normally.
                desktop.send(SyncFrame.SyncFinished("", "fingerprint", 0))
                assertTrue(waitUntil { quickFrames.any { it is SyncFrame.SyncFinished } })
                assertTrue(quick.sendFrame(SyncFrame.SyncFinished("", "fingerprint", 0)))
                assertTrue(waitUntil { fromPhone.any { it is SyncFrame.SyncFinished } })
            }
            assertTrue("Keep-alives are consumed by the transport", quickFrames.none { it is SyncFrame.KeepAlive })
        } finally {
            quick.stopServer()
        }
    }

    @Test
    fun keepAlive_isNeverSentOrAcceptedWhilePairing() = runBlocking {
        val quickPort = port + 4
        val quick = newTransport(quickPort, timeoutMs = 5_000, keepAliveMs = 100L)
        val qrSecret = SyncCrypto.randomBytes(32)
        try {
            ServerSocket(0).use { fakeDesktop ->
                val start = scope.async { quick.pairWithDesktop(qr(qrSecret, fakeDesktop.localPort)) }
                ScriptedDesktop.accept(fakeDesktop, pairingSecret = qrSecret)!!.use { desktop ->
                    assertTrue(start.await() is LanSocketTransport.PairingStart.Started)
                    assertTrue(desktop.receive() is SyncFrame.PairingInfo)
                    assertFalse(quick.sendFrame(SyncFrame.KeepAlive(1L)))

                    // Many keep-alive intervals pass: none may arrive on a pairing connection.
                    desktop.socket.soTimeout = 1_000
                    try {
                        desktop.receive()
                        fail("Nothing should arrive while the desktop decides")
                    } catch (expected: SocketTimeoutException) {
                    }
                    desktop.socket.soTimeout = 5_000

                    desktop.send(SyncFrame.KeepAlive(1L))
                    assertEquals(0, drainUntilClosed(desktop.socket).size)
                }
            }
            assertTrue(waitUntil { !quick.isConnected })
            assertEquals(SyncError.PROTOCOL, quick.lastFailure())
            assertTrue(repository.getPairedDevices().isEmpty())
        } finally {
            quick.stopServer()
        }
    }

    @Test
    fun keepAlive_beforeTheHandshake_getsNoReply() {
        connect().use { socket ->
            val bytes = """{"type":"KeepAlive","sentAt":1}""".toByteArray()
            DataOutputStream(socket.getOutputStream()).apply {
                writeByte(0)
                writeInt(bytes.size)
                write(bytes)
                flush()
            }
            assertEquals("Phone must not answer", 0, drainUntilClosed(socket).size)
        }
        assertFalse(transport.isConnected)
        assertTrue(emittedFrames.isEmpty())
    }

    // --- Pairing (the phone scans the desktop's QR code) ---

    @Test
    fun pairWithDesktop_storesTheSealedPairKey_andHangsUp() = runBlocking {
        val qrSecret = SyncCrypto.randomBytes(32)
        ServerSocket(0).use { fakeDesktop ->
            val start = scope.async { transport.pairWithDesktop(qr(qrSecret, fakeDesktop.localPort)) }
            ScriptedDesktop.accept(fakeDesktop, pairingSecret = qrSecret)!!.use { desktop ->
                assertTrue(start.await() is LanSocketTransport.PairingStart.Started)
                assertEquals(SyncFrame.PairingInfo("phone-under-test", "Pixel Under Test"), desktop.receive())

                desktop.send(SyncFrame.PairingAcceptance("desktop-9", true))
                assertTrue(waitUntil { transport.syncState.value == SyncState.PAIRED })

                val saved = repository.getPairedDevice("desktop-9")!!
                assertEquals("Studio PC", saved.deviceName)
                assertArrayEquals(desktop.channel.pairKey, protector.open(saved.sharedSecret))
                assertFalse("Stored sealed", saved.sharedSecret.contains(Hex.encode(desktop.channel.pairKey!!)))

                // Pairing done: the phone frees its connection slot.
                assertTrue(waitUntil { !transport.isConnected })
                assertEquals(-1, desktop.socket.getInputStream().read())
            }
        }
    }

    @Test
    fun pairWithDesktop_declined_isReported() = runBlocking {
        val qrSecret = SyncCrypto.randomBytes(32)
        ServerSocket(0).use { fakeDesktop ->
            val start = scope.async { transport.pairWithDesktop(qr(qrSecret, fakeDesktop.localPort)) }
            ScriptedDesktop.accept(fakeDesktop, pairingSecret = qrSecret)!!.use { desktop ->
                assertTrue(start.await() is LanSocketTransport.PairingStart.Started)
                desktop.receive()
                desktop.send(SyncFrame.PairingAcceptance("desktop-9", false))
                assertTrue(waitUntil { transport.syncState.value == SyncState.ERROR })
            }
        }
        assertEquals("Pairing was declined on the desktop.", transport.lastError.value)
        assertTrue(repository.getPairedDevices().isEmpty())
    }

    @Test
    fun pairWithDesktop_answerFromAnotherDevice_isRefused() = runBlocking {
        val qrSecret = SyncCrypto.randomBytes(32)
        ServerSocket(0).use { fakeDesktop ->
            val start = scope.async { transport.pairWithDesktop(qr(qrSecret, fakeDesktop.localPort)) }
            ScriptedDesktop.accept(fakeDesktop, pairingSecret = qrSecret)!!.use { desktop ->
                assertTrue(start.await() is LanSocketTransport.PairingStart.Started)
                desktop.receive()
                desktop.send(SyncFrame.PairingAcceptance("some-other-desktop", true))
                assertTrue(waitUntil { transport.syncState.value == SyncState.ERROR })
            }
        }
        assertTrue(repository.getPairedDevices().isEmpty())
    }

    @Test
    fun pairWithDesktop_withTheWrongQrSecret_fails() = runBlocking {
        ServerSocket(0).use { fakeDesktop ->
            val start = scope.async { transport.pairWithDesktop(qr(SyncCrypto.randomBytes(32), fakeDesktop.localPort)) }
            assertNull(ScriptedDesktop.accept(fakeDesktop, pairingSecret = SyncCrypto.randomBytes(32)))
            assertTrue(start.await() is LanSocketTransport.PairingStart.Failed)
        }
        assertTrue(repository.getPairedDevices().isEmpty())
    }

    // --- Audit Step 1.8: nothing secret on the wire ---

    @Test
    fun captureOfAFullPairingAndSync_containsNoSecrets() = runBlocking {
        val qrSecret = SyncCrypto.randomBytes(32)
        ServerSocket(0).use { fakeDesktop ->
            RecordingProxy(fakeDesktop.localPort).use { proxy ->
                // Pairing, through the proxy.
                val pairing = scope.async { transport.pairWithDesktop(qr(qrSecret, proxy.port)) }
                val pairKey = ScriptedDesktop.accept(fakeDesktop, pairingSecret = qrSecret)!!.use { desktop ->
                    assertTrue(pairing.await() is LanSocketTransport.PairingStart.Started)
                    desktop.receive()
                    desktop.send(SyncFrame.PairingAcceptance("desktop-9", true))
                    assertTrue(waitUntil { transport.syncState.value == SyncState.PAIRED })
                    desktop.channel.pairKey!!
                }
                assertTrue(waitUntil { !transport.isConnected })

                // A full sync started on the phone, through the proxy (the stored port is the proxy's).
                val device = repository.getPairedDevice("desktop-9")!!
                val sync = scope.async { transport.startOutgoingSync(device) }
                ScriptedDesktop.accept(fakeDesktop, pairKey = pairKey)!!.use { desktop ->
                    assertEquals(LanSocketTransport.OutgoingSyncResult.STARTED, sync.await())
                    desktop.receive()
                    desktop.send(SyncFrame.SyncAcceptance("desktop-9", true))
                    assertTrue(waitUntil { transport.canSendVaultData() })
                    assertTrue(transport.sendFrame(SyncFrame.PayloadBatch("""[{"password":"Phone-Only-Password-987"}]""")))
                    assertTrue(desktop.receive() is SyncFrame.PayloadBatch)
                    desktop.send(SyncFrame.PayloadBatch("""[{"password":"Desktop-Only-Password-123"}]"""))
                    assertTrue(waitUntil { emittedFrames.any { it is SyncFrame.PayloadBatch } })
                }

                val captured = proxy.captured()
                assertTrue("The capture should contain the whole exchange", captured.size > 500)
                // Positive control: the (public) handshake hellos are in the capture.
                assertTrue(captured.containsSequence("\"mode\":\"pair\"".toByteArray()))
                assertTrue(captured.containsSequence("\"mode\":\"sync\"".toByteArray()))
                val forbidden = mapOf(
                    "QR secret" to qrSecret,
                    "QR secret (hex)" to Hex.encode(qrSecret).toByteArray(),
                    "pair key" to pairKey,
                    "pair key (hex)" to Hex.encode(pairKey).toByteArray(),
                    "phone password" to "Phone-Only-Password-987".toByteArray(),
                    "desktop password" to "Desktop-Only-Password-123".toByteArray(),
                    "phone device id" to "phone-under-test".toByteArray(),
                    "phone name" to "Pixel Under Test".toByteArray()
                )
                for ((what, bytes) in forbidden) {
                    assertFalse("The capture contains the $what", captured.containsSequence(bytes))
                }
            }
        }
    }

    // --- Helpers ---

    private fun qr(secret: ByteArray, port: Int) = QrPairingData(
        deviceId = "desktop-9",
        deviceName = "Studio PC",
        pairingSecret = secret,
        primaryIpAddress = "127.0.0.1",
        candidateIpAddresses = listOf("127.0.0.1"),
        port = port
    )

    private fun connect(targetPort: Int = port): Socket = ScriptedDesktop.connectWithRetry(targetPort)

    private fun sendHello(socket: Socket, mode: HandshakeMode, secret: ByteArray) {
        WireV2.write(DataOutputStream(socket.getOutputStream()), WireV2.TYPE_HELLO, HandshakeInitiator(mode, secret).helloBytes)
    }

    /** Reads until the phone closes the connection and returns everything received. */
    private fun drainUntilClosed(socket: Socket): ByteArray {
        val buffer = ByteArrayOutputStream()
        val input = socket.getInputStream()
        val chunk = ByteArray(4096)
        while (true) {
            val read = try {
                input.read(chunk)
            } catch (e: SocketTimeoutException) {
                fail("Connection was not closed by the phone")
                -1
            } catch (e: IOException) {
                -1 // connection reset also counts as closed
            }
            if (read < 0) break
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    private fun waitUntil(timeoutMs: Long = 5000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }
}
