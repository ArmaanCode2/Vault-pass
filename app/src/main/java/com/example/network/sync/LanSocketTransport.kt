package com.example.network.sync

import android.content.Context
import com.example.domain.sync.models.PairedDevice
import com.example.domain.sync.models.SyncFrame
import com.example.domain.sync.models.SyncState
import com.example.repository.PairedDeviceRepository
import com.vaultpass.synccore.HandshakeMode
import com.vaultpass.synccore.Hex
import com.vaultpass.synccore.LegacyPeerException
import com.vaultpass.synccore.PairKeyProtector
import com.vaultpass.synccore.SecureChannel
import com.vaultpass.synccore.SyncCrypto
import com.vaultpass.synccore.SyncError
import com.vaultpass.synccore.SyncProtocolException
import com.vaultpass.synccore.SyncTimeouts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URLDecoder

/** Contents of a desktop's pairing QR code (sync protocol v2). */
class QrPairingData(
    val deviceId: String,
    val deviceName: String,
    /** One-time 32-byte secret; only the camera ever sees it. */
    val pairingSecret: ByteArray,
    val primaryIpAddress: String,
    val candidateIpAddresses: List<String> = listOf(primaryIpAddress),
    val port: Int = LanSocketTransport.DEFAULT_TCP_PORT
)

/**
 * LAN sync transport on sync protocol v2 (see com.vaultpass.synccore). Every connection starts
 * with an authenticated handshake, and every message after it is an encrypted record.
 *
 * The phone pairs by scanning the desktop's QR code (it never answers pairing requests), and both
 * starts and answers syncs with paired devices. Only one connection is served at a time.
 */
class LanSocketTransport(
    private val context: Context,
    val localDeviceId: String,
    val localDeviceName: String,
    private val pairedDeviceRepository: PairedDeviceRepository,
    private val scope: CoroutineScope,
    private val pairKeyProtector: PairKeyProtector? = null,
    private val listenPort: Int = DEFAULT_TCP_PORT,
    private val firstFrameTimeoutMs: Int = SyncTimeouts.HANDSHAKE_MS,
    private val idleReadTimeoutMs: Int = SyncTimeouts.IDLE_MS,
    private val keepAliveMs: Long = KEEP_ALIVE_MS
) {
    companion object {
        const val DEFAULT_TCP_PORT = 53853

        /** How often a sync session proves it is alive, well under the idle limit. */
        const val KEEP_ALIVE_MS = 30_000L

        private const val CONNECT_TIMEOUT_MS = 3000

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** Parses a v2 pairing QR code; returns null for anything else (including v1 codes). */
        fun parseQrPairingUri(uriString: String): QrPairingData? {
            val params = parsePairingParams(uriString) ?: return null
            if (params["v"] != "2") return null
            val secret = try {
                Hex.decode(params["secret"] ?: return null)
            } catch (e: IllegalArgumentException) {
                return null
            }
            if (secret.size != SyncCrypto.KEY_BYTES) return null
            val deviceId = params["deviceId"]?.takeIf { it.isNotBlank() } ?: return null
            val name = params["name"]?.takeIf { it.isNotBlank() } ?: return null
            val ip = params["ip"]?.takeIf { it.isNotBlank() }
            val parsedIps = params["ips"]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
            val primaryIp = ip ?: parsedIps.firstOrNull() ?: return null
            val candidateIps = when {
                parsedIps.isEmpty() -> listOf(primaryIp)
                parsedIps.contains(primaryIp) -> parsedIps
                else -> listOf(primaryIp) + parsedIps
            }
            val port = params["port"]?.let { it.toIntOrNull() ?: return null } ?: DEFAULT_TCP_PORT
            if (port !in 1..65535) return null
            return QrPairingData(deviceId, name, secret, primaryIp, candidateIps, port)
        }

        /** True for a pairing QR code from a desktop that still uses sync protocol v1. */
        fun isLegacyPairingUri(uriString: String): Boolean {
            val params = parsePairingParams(uriString) ?: return false
            return params["v"] != "2" && (params["token"] != null || params["pairingToken"] != null)
        }

        private fun parsePairingParams(uriString: String): Map<String, String>? {
            if (!uriString.startsWith("vaultpass://pair")) return null
            val queryIndex = uriString.indexOf('?')
            if (queryIndex == -1) return null
            return try {
                uriString.substring(queryIndex + 1).split("&").associate { param ->
                    val parts = param.split("=", limit = 2)
                    parts[0] to (if (parts.size > 1) URLDecoder.decode(parts[1], "UTF-8") else "")
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Which side of the current connection this device is on. */
    enum class SyncRole { NONE, INITIATOR, RESPONDER }

    enum class OutgoingSyncResult { STARTED, NOT_PAIRED, UNREACHABLE, FAILED }

    sealed class PairingStart {
        object Started : PairingStart()
        class Unreachable(val details: String) : PairingStart()
        class Failed(val details: String) : PairingStart()
    }

    private class Connection(
        val socket: Socket,
        val channel: SecureChannel,
        val mode: HandshakeMode,
        val role: SyncRole,
        /** The paired device on a sync connection; null on a pairing connection. */
        val peer: PairedDevice?,
        /** The QR code being paired with, on a pairing connection. */
        val qr: QrPairingData?,
        val peerIpAddress: String?
    )

    private val _syncState = MutableStateFlow(SyncState.IDLE)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val _pendingSyncRequest = MutableStateFlow<SyncFrame.SyncRequest?>(null)
    val pendingSyncRequest: StateFlow<SyncFrame.SyncRequest?> = _pendingSyncRequest.asStateFlow()

    /** Why the last pairing or connection failed, for the UI; null when there is nothing to report. */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _incomingFrames = MutableSharedFlow<SyncFrame>(extraBufferCapacity = 64)

    fun receiveFrames(): Flow<SyncFrame> = _incomingFrames.asSharedFlow()

    // --- Connection slot ---

    private val connectionLock = Any()

    @Volatile
    private var connection: Connection? = null

    /** A socket still in its handshake; it occupies the single connection slot. */
    @Volatile
    private var handshakeSocket: Socket? = null

    // --- Session state of the current connection (reset for every connection) ---

    @Volatile
    private var receivedPairingAcceptance = false

    @Volatile
    private var receivedSyncRequest = false

    @Volatile
    private var receivedSyncAcceptance = false

    @Volatile
    private var remoteAcceptedSync = false

    @Volatile
    private var incomingSyncApprovedLocally = false

    @Volatile
    private var planSent = false

    @Volatile
    private var planComplete = false

    @Volatile
    private var planReceived = false

    @Volatile
    private var decisionReceived = false

    @Volatile
    private var finishedReceived = false

    /** Sends KeepAlive frames while a sync session is open, so a slow review doesn't time out. */
    @Volatile
    private var keepAliveJob: Job? = null

    /** Why the current (or last) session ended; the UI turns it into a message the user reads. */
    @Volatile
    private var lastFailure: SyncError? = null

    /** Set when the listener could not take its port, so a sync can say why nothing arrives. */
    @Volatile
    private var bindFailure: String? = null

    val syncRole: SyncRole get() = connection?.role ?: SyncRole.NONE

    val isConnected: Boolean get() = connection != null

    /** The reason the last session ended, or null when it ended normally. */
    fun lastFailure(): SyncError? = lastFailure

    /** Details of the listener's bind failure, or null when it is listening. */
    fun listenerFailure(): String? = bindFailure

    /** Keeps the first reason a session ended: later failures are consequences of it. */
    private fun recordFailure(error: SyncError) {
        if (lastFailure == null) lastFailure = error
    }

    // --- Server ---

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    @Synchronized
    fun startServer(port: Int = listenPort) {
        if (serverSocket != null) return

        bindFailure = null
        try {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(port))
            serverSocket = server

            serverJob = scope.launch(Dispatchers.IO) {
                while (isActive && !server.isClosed) {
                    try {
                        val socket = server.accept()
                        // A failure on this one socket must not end the accept loop.
                        try {
                            socket.soTimeout = firstFrameTimeoutMs
                        } catch (e: Exception) {
                            closeQuietly(socket)
                            continue
                        }
                        launch { handleIncomingConnection(socket) }
                    } catch (e: SocketException) {
                        break
                    } catch (e: Exception) {
                        // ignore and continue
                    }
                }
            }
        } catch (e: Exception) {
            // Port in use or permission denied: remembered so a sync can report it.
            serverSocket = null
            bindFailure = "Port $port could not be opened on this device: ${e.javaClass.simpleName} (${e.message ?: "no details"})"
        }
    }

    @Synchronized
    fun stopServer() {
        serverJob?.cancel()
        serverJob = null
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // ignore
        }
        serverSocket = null
        disconnect()
    }

    private suspend fun handleIncomingConnection(socket: Socket) {
        synchronized(connectionLock) {
            if (connection != null || handshakeSocket != null) {
                closeQuietly(socket)
                return
            }
            handshakeSocket = socket
        }

        val accepted = try {
            val syncCandidates = loadSyncCandidates()
            SecureChannel.accept(DataInputStream(socket.getInputStream()), DataOutputStream(socket.getOutputStream())) { mode ->
                // The phone never answers pairing handshakes: pairing starts here, by scanning.
                if (mode == HandshakeMode.SYNC) syncCandidates else emptyList()
            }
        } catch (e: CancellationException) {
            releaseHandshakeSlot(socket)
            closeQuietly(socket)
            throw e
        } catch (e: Exception) {
            // Harmless: no session exists before the handshake, and a stranger gets no reply.
            null
        }

        if (accepted == null) {
            releaseHandshakeSlot(socket)
            closeQuietly(socket)
            return
        }

        val conn = Connection(
            socket, accepted.channel, accepted.channel.mode, SyncRole.RESPONDER,
            peer = accepted.peer, qr = null, peerIpAddress = socket.inetAddress?.hostAddress
        )
        if (!install(conn, socket)) return
        try {
            socket.soTimeout = idleReadTimeoutMs
        } catch (_: Exception) {}
        readLoop(conn)
    }

    /** Paired devices with their pair keys; needs an unlocked vault. */
    private suspend fun loadSyncCandidates(): List<Pair<PairedDevice, ByteArray>> {
        val protector = pairKeyProtector ?: return emptyList()
        return pairedDeviceRepository.getPairedDevices().mapNotNull { device ->
            protector.open(device.sharedSecret)?.let { device to it }
        }
    }

    // --- Pairing with a desktop ---

    /**
     * Connects to the desktop from [qr], runs the pairing handshake with the QR secret and
     * introduces this phone. The result of the desktop user's decision arrives later: the state
     * becomes PAIRED, or ERROR with [lastError] set.
     */
    suspend fun pairWithDesktop(qr: QrPairingData): PairingStart = withContext(Dispatchers.IO) {
        _lastError.value = null
        disconnect()
        _syncState.value = SyncState.CONNECTING

        val candidates = qr.candidateIpAddresses.filter { it.isNotBlank() }.distinct()
        val errors = mutableListOf<String>()
        var socket: Socket? = null
        var connectedIp: String? = null
        for (ip in candidates) {
            val attempt = Socket()
            synchronized(connectionLock) { handshakeSocket = attempt }
            try {
                attempt.connect(InetSocketAddress(ip, qr.port), CONNECT_TIMEOUT_MS)
                attempt.soTimeout = firstFrameTimeoutMs
                socket = attempt
                connectedIp = ip
                break
            } catch (e: Exception) {
                releaseHandshakeSlot(attempt)
                closeQuietly(attempt)
                errors.add("$ip: ${e.javaClass.simpleName} (${e.message ?: "timeout"})")
            }
        }
        if (socket == null) {
            _syncState.value = SyncState.ERROR
            return@withContext PairingStart.Unreachable(
                if (candidates.isEmpty()) "No IP addresses in the QR code" else errors.joinToString("\n")
            )
        }

        val channel = try {
            SecureChannel.initiate(
                DataInputStream(socket.getInputStream()),
                DataOutputStream(socket.getOutputStream()),
                HandshakeMode.PAIR,
                qr.pairingSecret
            )
        } catch (e: Exception) {
            releaseHandshakeSlot(socket)
            closeQuietly(socket)
            _syncState.value = SyncState.ERROR
            return@withContext PairingStart.Failed(
                "The desktop didn't accept this QR code. Show a new code on the desktop and scan it again."
            )
        }

        val conn = Connection(socket, channel, HandshakeMode.PAIR, SyncRole.INITIATOR, peer = null, qr = qr, peerIpAddress = connectedIp)
        if (!install(conn, socket)) {
            _syncState.value = SyncState.ERROR
            return@withContext PairingStart.Failed("Pairing was cancelled.")
        }
        try {
            socket.soTimeout = idleReadTimeoutMs
        } catch (_: Exception) {}
        _syncState.value = SyncState.WAITING_FOR_REMOTE_APPROVAL
        scope.launch(Dispatchers.IO) { readLoop(conn) }

        if (!sendFrame(SyncFrame.PairingInfo(deviceId = localDeviceId, deviceName = localDeviceName))) {
            closeConnection(conn)
            _syncState.value = SyncState.ERROR
            return@withContext PairingStart.Failed("Lost the connection to the desktop.")
        }
        PairingStart.Started
    }

    /** Stores the desktop after it accepted our pairing request, then hangs up. */
    private suspend fun completePairing(conn: Connection, acceptance: SyncFrame.PairingAcceptance) {
        val qr = conn.qr
        val sealed = conn.channel.pairKey?.let { pairKeyProtector?.seal(it) }
        if (qr == null || acceptance.deviceId != qr.deviceId || sealed == null) {
            _lastError.value = if (sealed == null) {
                "Could not store the pairing key. Unlock the vault and pair again."
            } else {
                "The desktop's answer didn't match its QR code."
            }
            _syncState.value = SyncState.ERROR
            closeConnection(conn)
            return
        }
        try {
            pairedDeviceRepository.savePairedDevice(
                PairedDevice(
                    deviceId = qr.deviceId,
                    deviceName = qr.deviceName,
                    sharedSecret = sealed,
                    ipAddress = conn.peerIpAddress,
                    port = qr.port,
                    pairedAt = System.currentTimeMillis(),
                    lastSyncAt = 0L,
                    isOnline = true
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _lastError.value = "Could not save the paired desktop: ${e.message}"
            _syncState.value = SyncState.ERROR
            closeConnection(conn)
            return
        }
        _syncState.value = SyncState.PAIRED
        // Pairing is done: free the connection slot right away.
        closeConnection(conn)
    }

    // --- Starting a sync ---

    /**
     * Connects to a paired device, runs the handshake with its pair key and sends a sync
     * request. The peer's SyncAcceptance then arrives on [receiveFrames].
     */
    suspend fun startOutgoingSync(device: PairedDevice): OutgoingSyncResult = withContext(Dispatchers.IO) {
        val ip = device.ipAddress?.takeIf { it.isNotBlank() } ?: return@withContext OutgoingSyncResult.UNREACHABLE
        val pairKey = pairKeyProtector?.open(device.sharedSecret) ?: return@withContext OutgoingSyncResult.NOT_PAIRED

        _lastError.value = null
        disconnect()
        lastFailure = null
        _syncState.value = SyncState.CONNECTING
        val socket = Socket()
        synchronized(connectionLock) { handshakeSocket = socket }
        try {
            socket.connect(InetSocketAddress(ip, device.port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = firstFrameTimeoutMs
        } catch (e: Exception) {
            releaseHandshakeSlot(socket)
            closeQuietly(socket)
            pairKey.fill(0)
            _syncState.value = SyncState.ERROR
            return@withContext OutgoingSyncResult.UNREACHABLE
        }

        val channel = try {
            SecureChannel.initiate(
                DataInputStream(socket.getInputStream()),
                DataOutputStream(socket.getOutputStream()),
                HandshakeMode.SYNC,
                pairKey
            )
        } catch (e: Exception) {
            recordFailure(failureFor(e))
            releaseHandshakeSlot(socket)
            closeQuietly(socket)
            _syncState.value = SyncState.ERROR
            return@withContext OutgoingSyncResult.FAILED
        } finally {
            pairKey.fill(0)
        }

        val conn = Connection(socket, channel, HandshakeMode.SYNC, SyncRole.INITIATOR, peer = device, qr = null, peerIpAddress = ip)
        if (!install(conn, socket)) {
            _syncState.value = SyncState.ERROR
            return@withContext OutgoingSyncResult.FAILED
        }
        try {
            socket.soTimeout = idleReadTimeoutMs
        } catch (_: Exception) {}
        _syncState.value = SyncState.WAITING_FOR_REMOTE_APPROVAL
        scope.launch(Dispatchers.IO) { readLoop(conn) }

        if (!sendFrame(SyncFrame.SyncRequest(deviceId = localDeviceId, deviceName = localDeviceName))) {
            closeConnection(conn)
            return@withContext OutgoingSyncResult.FAILED
        }
        OutgoingSyncResult.STARTED
    }

    // --- Receiving ---

    private suspend fun readLoop(conn: Connection) {
        try {
            while (true) {
                val bytes = conn.channel.receive()
                val frame = try {
                    json.decodeFromString(SyncFrame.serializer(), String(bytes, Charsets.UTF_8))
                } catch (e: Exception) {
                    throw SyncProtocolException("Malformed sync message")
                }
                if (!acceptInboundFrame(conn, frame)) {
                    recordFailure(SyncError.PROTOCOL)
                    throw SyncProtocolException("${frame::class.simpleName} is not allowed in this session")
                }
                // Receiving it already reset the read timeout; the sync screen never sees it.
                if (frame is SyncFrame.KeepAlive) continue
                // Update the session first: listeners react to the frame by checking it
                // (e.g. canSendVaultData() right after a SyncAcceptance).
                handleFrame(conn, frame)
                _incomingFrames.emit(frame)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The connection ends either way, but the reason is what the user gets told.
            recordFailure(failureFor(e))
        } finally {
            endConnection(conn)
        }
    }

    private fun failureFor(e: Exception): SyncError = when (e) {
        is LegacyPeerException -> SyncError.PEER_OUTDATED
        is SocketTimeoutException -> SyncError.TIMED_OUT
        is SyncProtocolException -> SyncError.PROTOCOL
        else -> SyncError.CONNECTION_LOST
    }

    /** What the peer may send, by connection mode, role and progress. Anything else ends the connection. */
    private fun acceptInboundFrame(conn: Connection, frame: SyncFrame): Boolean = when (conn.mode) {
        HandshakeMode.PAIR -> when (frame) {
            is SyncFrame.PairingAcceptance -> conn.role == SyncRole.INITIATOR && !receivedPairingAcceptance
            is SyncFrame.CancelSync -> true
            else -> false
        }
        HandshakeMode.SYNC -> when (frame) {
            is SyncFrame.SyncRequest ->
                conn.role == SyncRole.RESPONDER && !receivedSyncRequest && frame.deviceId == conn.peer?.deviceId
            is SyncFrame.SyncAcceptance -> conn.role == SyncRole.INITIATOR && !receivedSyncAcceptance
            is SyncFrame.PayloadBatch -> when (conn.role) {
                SyncRole.INITIATOR -> remoteAcceptedSync
                SyncRole.RESPONDER -> receivedSyncRequest && incomingSyncApprovedLocally
                SyncRole.NONE -> false
            }
            // Only the device that started the sync reviews it and sends the plan.
            is SyncFrame.MergePlanBatch ->
                conn.role == SyncRole.RESPONDER && incomingSyncApprovedLocally && !planComplete
            is SyncFrame.MergePlanDecision -> conn.role == SyncRole.INITIATOR && planSent && !decisionReceived
            is SyncFrame.SyncFinished -> conn.role != SyncRole.NONE && !finishedReceived
            is SyncFrame.KeepAlive -> conn.role != SyncRole.NONE
            is SyncFrame.CancelSync -> true
            else -> false
        }
    }

    private suspend fun handleFrame(conn: Connection, frame: SyncFrame) {
        when (frame) {
            is SyncFrame.PairingAcceptance -> {
                receivedPairingAcceptance = true
                if (frame.isAccepted) {
                    completePairing(conn, frame)
                } else {
                    _lastError.value = "Pairing was declined on the desktop."
                    _syncState.value = SyncState.ERROR
                    closeConnection(conn)
                }
            }
            is SyncFrame.SyncRequest -> {
                receivedSyncRequest = true
                _pendingSyncRequest.value = frame
                _syncState.value = SyncState.AWAITING_LOCAL_APPROVAL
            }
            is SyncFrame.SyncAcceptance -> {
                receivedSyncAcceptance = true
                if (frame.isAccepted) {
                    remoteAcceptedSync = true
                    _syncState.value = SyncState.SYNCING
                } else {
                    _syncState.value = SyncState.IDLE
                    closeConnection(conn)
                }
            }
            is SyncFrame.MergePlanBatch -> if (frame.last) {
                planComplete = true
                planReceived = true
            }
            is SyncFrame.MergePlanDecision -> decisionReceived = true
            is SyncFrame.SyncFinished -> finishedReceived = true
            is SyncFrame.CancelSync -> {
                // The peer ended it on purpose; a code says it was a failure on its side.
                if (frame.code.isNotEmpty()) recordFailure(SyncError.fromCode(frame.code))
                if (_syncState.value != SyncState.PAIRED) _syncState.value = SyncState.IDLE
                closeConnection(conn)
            }
            else -> {}
        }
    }

    // --- Sending ---

    /** Encrypts and sends [frame] if the current session allows it. */
    suspend fun sendFrame(frame: SyncFrame): Boolean = withContext(Dispatchers.IO) {
        val conn = connection ?: return@withContext false
        if (!mayOutbound(conn, frame)) return@withContext false
        try {
            conn.channel.send(json.encodeToString(SyncFrame.serializer(), frame).toByteArray(Charsets.UTF_8))
            if (frame is SyncFrame.MergePlanBatch && frame.last) planSent = true
            true
        } catch (e: Exception) {
            // The connection is broken; callers act on false, and the reason is kept for the UI.
            recordFailure(failureFor(e))
            false
        }
    }

    private fun mayOutbound(conn: Connection, frame: SyncFrame): Boolean = when (frame) {
        is SyncFrame.PairingInfo -> conn.mode == HandshakeMode.PAIR && conn.role == SyncRole.INITIATOR
        is SyncFrame.SyncRequest -> conn.mode == HandshakeMode.SYNC && conn.role == SyncRole.INITIATOR
        is SyncFrame.SyncAcceptance ->
            conn.mode == HandshakeMode.SYNC && conn.role == SyncRole.RESPONDER && receivedSyncRequest
        is SyncFrame.PayloadBatch -> canSendVaultData()
        is SyncFrame.MergePlanBatch ->
            conn.mode == HandshakeMode.SYNC && conn.role == SyncRole.INITIATOR && remoteAcceptedSync
        is SyncFrame.MergePlanDecision ->
            conn.mode == HandshakeMode.SYNC && conn.role == SyncRole.RESPONDER && planReceived
        is SyncFrame.SyncFinished -> conn.mode == HandshakeMode.SYNC && conn.role != SyncRole.NONE
        is SyncFrame.KeepAlive -> conn.mode == HandshakeMode.SYNC && conn.role != SyncRole.NONE
        is SyncFrame.CancelSync -> true
        is SyncFrame.PairingAcceptance -> false
    }

    // --- Sync session decisions ---

    /** True when this device may send its vault: a sync with a paired device that both sides agreed to. */
    fun canSendVaultData(): Boolean {
        val conn = connection ?: return false
        if (conn.mode != HandshakeMode.SYNC) return false
        return when (conn.role) {
            SyncRole.INITIATOR -> remoteAcceptedSync
            SyncRole.RESPONDER -> receivedSyncRequest && incomingSyncApprovedLocally
            SyncRole.NONE -> false
        }
    }

    /** Records the local user's approval of the pending incoming sync request. */
    fun approveIncomingSync(): Boolean {
        val conn = connection ?: return false
        if (conn.mode != HandshakeMode.SYNC || conn.role != SyncRole.RESPONDER) return false
        if (!receivedSyncRequest || _pendingSyncRequest.value == null) return false
        incomingSyncApprovedLocally = true
        _pendingSyncRequest.value = null
        return true
    }

    /** Approves the pending incoming sync and tells the peer. */
    suspend fun acceptSync(): Boolean {
        if (!approveIncomingSync()) return false
        _syncState.value = SyncState.SYNCING
        return sendFrame(SyncFrame.SyncAcceptance(deviceId = localDeviceId, isAccepted = true))
    }

    suspend fun declineSync() {
        _pendingSyncRequest.value = null
        sendFrame(SyncFrame.SyncAcceptance(deviceId = localDeviceId, isAccepted = false))
        delay(200L) // Let the answer reach the peer before closing.
        disconnect()
    }

    // --- Closing ---

    /** Makes [conn] the active connection unless disconnect() ran during its handshake. */
    private fun install(conn: Connection, socket: Socket): Boolean {
        val installed = synchronized(connectionLock) {
            if (handshakeSocket === socket) {
                handshakeSocket = null
                resetSession()
                // A new session starts with a clean slate: the old reason no longer applies.
                lastFailure = null
                connection = conn
                true
            } else {
                false
            }
        }
        if (!installed) {
            conn.channel.destroy()
            closeQuietly(socket)
        } else if (conn.mode == HandshakeMode.SYNC && conn.role != SyncRole.NONE) {
            startKeepAlive(conn)
        }
        return installed
    }

    private fun startKeepAlive(conn: Connection) {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch(Dispatchers.IO) {
            while (isActive && connection === conn) {
                delay(keepAliveMs)
                if (connection !== conn) break
                if (!sendFrame(SyncFrame.KeepAlive(System.currentTimeMillis()))) {
                    // sendFrame kept the reason; closing lets the read loop end the session.
                    if (connection === conn) closeConnection(conn)
                    break
                }
            }
        }
    }

    private fun releaseHandshakeSlot(socket: Socket) {
        synchronized(connectionLock) {
            if (handshakeSocket === socket) handshakeSocket = null
        }
    }

    /** Closes the connection's socket; its read loop then ends and cleans up. */
    private fun closeConnection(conn: Connection) {
        closeQuietly(conn.socket)
    }

    /** Called when a connection's read loop ends for any reason. */
    private fun endConnection(conn: Connection) {
        conn.channel.destroy()
        closeQuietly(conn.socket)
        val wasCurrent = synchronized(connectionLock) {
            if (connection === conn) {
                connection = null
                true
            } else {
                false
            }
        }
        if (!wasCurrent) return
        resetSession()
        _pendingSyncRequest.value = null
        when (_syncState.value) {
            SyncState.PAIRED, SyncState.ERROR, SyncState.IDLE -> {}
            // Hung up before the exchange finished.
            SyncState.WAITING_FOR_REMOTE_APPROVAL, SyncState.SYNCING, SyncState.CONNECTING -> _syncState.value = SyncState.ERROR
            else -> _syncState.value = SyncState.IDLE
        }
    }

    fun disconnect() {
        val conn: Connection?
        val pending: Socket?
        synchronized(connectionLock) {
            conn = connection
            pending = handshakeSocket
            connection = null
            handshakeSocket = null
            resetSession()
        }
        pending?.let { closeQuietly(it) }
        conn?.let {
            it.channel.destroy()
            closeQuietly(it.socket)
        }
        _pendingSyncRequest.value = null
        if (_syncState.value != SyncState.PAIRED) {
            _syncState.value = SyncState.IDLE
        }
    }

    private fun resetSession() {
        keepAliveJob?.cancel()
        keepAliveJob = null
        receivedPairingAcceptance = false
        receivedSyncRequest = false
        receivedSyncAcceptance = false
        remoteAcceptedSync = false
        incomingSyncApprovedLocally = false
        planSent = false
        planComplete = false
        planReceived = false
        decisionReceived = false
        finishedReceived = false
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: Exception) {}
    }
}
