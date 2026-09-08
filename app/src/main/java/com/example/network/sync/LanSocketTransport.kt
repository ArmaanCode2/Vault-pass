package com.example.network.sync

import android.content.Context
import com.example.domain.sync.models.PairedDevice
import com.example.domain.sync.models.SyncFrame
import com.example.domain.sync.models.SyncState
import com.example.repository.PairedDeviceRepository
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
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class QrPairingData(
    val deviceId: String,
    val deviceName: String,
    val pairingToken: String,
    val primaryIpAddress: String,
    val candidateIpAddresses: List<String> = listOf(primaryIpAddress),
    val port: Int = 53853
) {
    val token: String get() = pairingToken
    val ipAddress: String get() = primaryIpAddress

    constructor(
        deviceId: String,
        deviceName: String,
        token: String,
        ipAddress: String,
        port: Int = 53853
    ) : this(
        deviceId = deviceId,
        deviceName = deviceName,
        pairingToken = token,
        primaryIpAddress = ipAddress,
        candidateIpAddresses = listOf(ipAddress),
        port = port
    )
}

object Base64Util {
    fun encodeToString(bytes: ByteArray): String {
        return try {
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } catch (e: Throwable) {
            java.util.Base64.getEncoder().encodeToString(bytes)
        }
    }

    fun decode(str: String): ByteArray {
        val trimmed = str.trim()
        return try {
            android.util.Base64.decode(trimmed, android.util.Base64.NO_WRAP)
        } catch (e: Throwable) {
            java.util.Base64.getDecoder().decode(trimmed)
        }
    }
}

class LanSocketTransport(
    private val context: Context,
    val localDeviceId: String,
    val localDeviceName: String,
    private val pairedDeviceRepository: PairedDeviceRepository,
    private val scope: CoroutineScope
) {
    companion object {
        const val DEFAULT_TCP_PORT = 53853
        const val FLAG_PLAINTEXT: Byte = 0
        const val FLAG_ENCRYPTED: Byte = 1
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val MAX_FRAME_SIZE = 10 * 1024 * 1024 // 10MB limit

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun generateRandomSharedSecret(): String {
            val randomBytes = ByteArray(32)
            SecureRandom().nextBytes(randomBytes)
            return Base64Util.encodeToString(randomBytes)
        }

        fun deriveSharedSecretFromToken(token: String): String {
            return try {
                val decoded = Base64Util.decode(token)
                if (decoded.size == 32) {
                    token
                } else {
                    val hash = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
                    Base64Util.encodeToString(hash)
                }
            } catch (e: Exception) {
                val hash = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
                Base64Util.encodeToString(hash)
            }
        }

        fun encryptFrame(frame: SyncFrame, sharedSecretBase64: String): ByteArray {
            val keyBytes = Base64Util.decode(sharedSecretBase64)
            require(keyBytes.size == 32) { "Shared secret must be 256 bits (32 bytes)" }

            val jsonString = json.encodeToString(SyncFrame.serializer(), frame)
            val plaintextBytes = jsonString.toByteArray(Charsets.UTF_8)

            val iv = ByteArray(GCM_IV_LENGTH)
            SecureRandom().nextBytes(iv)

            val cipher = Cipher.getInstance(AES_GCM)
            val keySpec = SecretKeySpec(keyBytes, "AES")
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

            val ciphertext = cipher.doFinal(plaintextBytes)
            val combined = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
            return combined
        }

        fun decryptFrame(data: ByteArray, sharedSecretBase64: String): SyncFrame {
            require(data.size >= GCM_IV_LENGTH + 16) { "Ciphertext data too short for AES-GCM" }
            val keyBytes = Base64Util.decode(sharedSecretBase64)
            require(keyBytes.size == 32) { "Shared secret must be 256 bits (32 bytes)" }

            val iv = ByteArray(GCM_IV_LENGTH)
            System.arraycopy(data, 0, iv, 0, GCM_IV_LENGTH)

            val ciphertextLength = data.size - GCM_IV_LENGTH
            val ciphertext = ByteArray(ciphertextLength)
            System.arraycopy(data, GCM_IV_LENGTH, ciphertext, 0, ciphertextLength)

            val cipher = Cipher.getInstance(AES_GCM)
            val keySpec = SecretKeySpec(keyBytes, "AES")
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

            val decryptedBytes = cipher.doFinal(ciphertext)
            val jsonString = String(decryptedBytes, Charsets.UTF_8)
            return json.decodeFromString(SyncFrame.serializer(), jsonString)
        }

        fun parseQrPairingUri(uriString: String): QrPairingData? {
            try {
                if (!uriString.startsWith("vaultpass://pair")) return null
                val queryIndex = uriString.indexOf('?')
                if (queryIndex == -1) return null
                val query = uriString.substring(queryIndex + 1)
                val params = query.split("&").associate { param ->
                    val parts = param.split("=", limit = 2)
                    val key = parts[0]
                    val value = if (parts.size > 1) {
                        URLDecoder.decode(parts[1], "UTF-8")
                    } else ""
                    key to value
                }
                val deviceId = params["deviceId"]?.takeIf { it.isNotBlank() } ?: return null
                val name = params["name"]?.takeIf { it.isNotBlank() } ?: return null
                val token = (params["pairingToken"] ?: params["token"])?.takeIf { it.isNotBlank() } ?: return null
                val ip = params["ip"]?.takeIf { it.isNotBlank() }
                val ipsRaw = params["ips"]?.takeIf { it.isNotBlank() }
                val parsedIps = ipsRaw?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
                val primaryIp = ip ?: parsedIps.firstOrNull() ?: return null
                val candidateIps = if (parsedIps.isNotEmpty()) {
                    if (parsedIps.contains(primaryIp)) parsedIps else listOf(primaryIp) + parsedIps
                } else {
                    listOf(primaryIp)
                }
                val port = params["port"]?.toIntOrNull() ?: DEFAULT_TCP_PORT
                return QrPairingData(
                    deviceId = deviceId,
                    deviceName = name,
                    pairingToken = token,
                    primaryIpAddress = primaryIp,
                    candidateIpAddresses = candidateIps,
                    port = port
                )
            } catch (e: Exception) {
                return null
            }
        }
    }

    private val _syncState = MutableStateFlow(SyncState.IDLE)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val _pendingPairingRequest = MutableStateFlow<SyncFrame.PairingRequest?>(null)
    val pendingPairingRequest: StateFlow<SyncFrame.PairingRequest?> = _pendingPairingRequest.asStateFlow()

    private val _pendingSyncRequest = MutableStateFlow<SyncFrame.SyncRequest?>(null)
    val pendingSyncRequest: StateFlow<SyncFrame.SyncRequest?> = _pendingSyncRequest.asStateFlow()

    private val _incomingFrames = MutableSharedFlow<SyncFrame>(extraBufferCapacity = 64)

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var clientSocket: Socket? = null
    private var socketJob: Job? = null
    private var outputStream: DataOutputStream? = null

    var activeSharedSecret: String? = null
    var activePeerDeviceId: String? = null
    var activePeerDeviceName: String? = null
    var activePeerIpAddress: String? = null
    var activePeerPort: Int = DEFAULT_TCP_PORT

    val isConnected: Boolean get() = clientSocket != null && clientSocket?.isClosed == false

    fun receiveFrames(): Flow<SyncFrame> = _incomingFrames.asSharedFlow()

    @Synchronized
    fun startServer(port: Int = DEFAULT_TCP_PORT) {
        if (serverSocket != null) return

        try {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(port))
            serverSocket = server

            serverJob = scope.launch(Dispatchers.IO) {
                while (isActive && !server.isClosed) {
                    try {
                        val socket = server.accept()
                        if (isConnected) {
                            try { socket.close() } catch (_: Exception) {}
                        } else {
                            handleConnectedSocket(socket)
                        }
                    } catch (e: SocketException) {
                        break
                    } catch (e: Exception) {
                        // ignore and continue
                    }
                }
            }
        } catch (e: Exception) {
            // Port in use or permission denied
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

    suspend fun connectToPeer(ipAddress: String, targetPort: Int = DEFAULT_TCP_PORT): Boolean =
        withContext(Dispatchers.IO) {
            disconnect()
            _syncState.value = SyncState.CONNECTING
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(ipAddress, targetPort), 3000)
                handleConnectedSocket(socket)
                activePeerIpAddress = ipAddress
                activePeerPort = targetPort
                true
            } catch (e: Exception) {
                try { socket.close() } catch (_: Exception) {}
                if (isActive) {
                    disconnect()
                    _syncState.value = SyncState.ERROR
                }
                false
            }
        }

    suspend fun connectToPeerCandidates(
        candidates: List<String>,
        targetPort: Int = DEFAULT_TCP_PORT
    ): Result<String> = withContext(Dispatchers.IO) {
        val distinctCandidates = candidates.filter { it.isNotBlank() }.distinct()
        if (distinctCandidates.isEmpty()) {
            disconnect()
            return@withContext Result.failure(Exception("No candidate IP addresses provided"))
        }

        disconnect()
        _syncState.value = SyncState.CONNECTING
        val errors = mutableListOf<String>()

        for (ip in distinctCandidates) {
            disconnectClientOnly()
            try {
                val socket = Socket()
                socket.reuseAddress = true
                socket.connect(InetSocketAddress(ip, targetPort), 2500)
                handleConnectedSocket(socket)
                activePeerIpAddress = ip
                activePeerPort = targetPort
                return@withContext Result.success(ip)
            } catch (e: Exception) {
                errors.add("$ip: ${e.javaClass.simpleName} (${e.message ?: "timeout"})")
            }
        }

        disconnect()
        _syncState.value = SyncState.ERROR
        Result.failure(Exception("Failed to connect to candidates:\n" + errors.joinToString("\n")))
    }

    @Synchronized
    private fun handleConnectedSocket(socket: Socket) {
        disconnectClientOnly()
        clientSocket = socket
        outputStream = DataOutputStream(socket.getOutputStream())

        socketJob = scope.launch(Dispatchers.IO) {
            val input = DataInputStream(socket.getInputStream())
            try {
                while (isActive && !socket.isClosed) {
                    val frame = readFrameFromStream(input, activeSharedSecret)
                    _incomingFrames.emit(frame)
                    handleIncomingFrame(frame)
                }
            } catch (e: SocketException) {
                // Connection closed
            } catch (e: Exception) {
                // Handle read error
            } finally {
                disconnectClientOnly()
                val currentState = _syncState.value
                if (currentState != SyncState.PAIRED && currentState != SyncState.IDLE) {
                    if (currentState == SyncState.WAITING_FOR_REMOTE_APPROVAL ||
                        currentState == SyncState.SYNCING ||
                        currentState == SyncState.CONNECTING) {
                        _syncState.value = SyncState.ERROR
                    } else {
                        _syncState.value = SyncState.IDLE
                    }
                }
            }
        }
    }

    suspend fun sendFrame(frame: SyncFrame, sharedSecret: String? = activeSharedSecret): Boolean =
        withContext(Dispatchers.IO) {
            val out = outputStream ?: return@withContext false
            try {
                synchronized(out) {
                    val effectiveSecret = if (frame is SyncFrame.SyncRequest) null else sharedSecret
                    writeFrameToStream(out, frame, effectiveSecret)
                }
                true
            } catch (e: Exception) {
                false
            }
        }

    private fun writeFrameToStream(out: DataOutputStream, frame: SyncFrame, secret: String?) {
        if (secret != null) {
            val payload = encryptFrame(frame, secret)
            out.writeByte(FLAG_ENCRYPTED.toInt())
            out.writeInt(payload.size)
            out.write(payload)
            out.flush()
        } else {
            val jsonStr = json.encodeToString(SyncFrame.serializer(), frame)
            val payload = jsonStr.toByteArray(Charsets.UTF_8)
            out.writeByte(FLAG_PLAINTEXT.toInt())
            out.writeInt(payload.size)
            out.write(payload)
            out.flush()
        }
    }

    private fun readFrameFromStream(input: DataInputStream, secret: String?): SyncFrame {
        val flag = input.readByte()
        val length = input.readInt()
        if (length < 0 || length > MAX_FRAME_SIZE) {
            throw IOException("Invalid frame length: $length")
        }
        val payload = ByteArray(length)
        input.readFully(payload)

        return when (flag) {
            FLAG_PLAINTEXT -> {
                val jsonStr = String(payload, Charsets.UTF_8)
                json.decodeFromString(SyncFrame.serializer(), jsonStr)
            }
            FLAG_ENCRYPTED -> {
                val s = secret ?: throw IllegalStateException("Received encrypted frame without shared secret")
                decryptFrame(payload, s)
            }
            else -> throw IOException("Unknown frame flag: $flag")
        }
    }

    // --- Mutual Acceptance Handshake ---

    suspend fun initiatePairing(qrData: QrPairingData): Boolean {
        activePeerDeviceId = qrData.deviceId
        activePeerDeviceName = qrData.deviceName
        if (activePeerIpAddress == null) {
            activePeerIpAddress = qrData.primaryIpAddress
        }
        activePeerPort = qrData.port
        activeSharedSecret = deriveSharedSecretFromToken(qrData.pairingToken)

        _syncState.value = SyncState.PAIRING

        val request = SyncFrame.PairingRequest(
            deviceId = localDeviceId,
            deviceName = localDeviceName,
            pairingToken = qrData.pairingToken
        )
        val sent = sendFrame(request, sharedSecret = null)
        if (sent) {
            _syncState.value = SyncState.WAITING_FOR_REMOTE_APPROVAL
        } else {
            _syncState.value = SyncState.ERROR
        }
        return sent
    }

    suspend fun acceptPairing(request: SyncFrame.PairingRequest) {
        val acceptance = SyncFrame.PairingAcceptance(
            deviceId = localDeviceId,
            isAccepted = true
        )
        sendFrame(acceptance, sharedSecret = null)
        _pendingPairingRequest.value = null

        completePairing()
    }

    suspend fun declinePairing(request: SyncFrame.PairingRequest) {
        val rejection = SyncFrame.PairingAcceptance(
            deviceId = localDeviceId,
            isAccepted = false
        )
        sendFrame(rejection, sharedSecret = null)
        delay(200L)
        _pendingPairingRequest.value = null
        _syncState.value = SyncState.IDLE
        disconnect()
    }

    suspend fun declineSync() {
        val rejection = SyncFrame.SyncAcceptance(
            deviceId = localDeviceId,
            isAccepted = false
        )
        sendFrame(rejection, sharedSecret = activeSharedSecret)
        delay(200L)
        disconnect()
    }

    suspend fun handleIncomingFrame(frame: SyncFrame) {
        when (frame) {
            is SyncFrame.PairingRequest -> {
                activePeerDeviceId = frame.deviceId
                activePeerDeviceName = frame.deviceName
                activeSharedSecret = deriveSharedSecretFromToken(frame.pairingToken)
                _pendingPairingRequest.value = frame
                _syncState.value = SyncState.AWAITING_LOCAL_APPROVAL
            }
            is SyncFrame.PairingAcceptance -> {
                if (frame.isAccepted) {
                    completePairing()
                } else {
                    _syncState.value = SyncState.IDLE
                    disconnect()
                }
            }
            is SyncFrame.SyncRequest -> {
                val pairedDevice = pairedDeviceRepository.getPairedDevice(frame.deviceId)
                if (pairedDevice != null) {
                    activeSharedSecret = pairedDevice.sharedSecret
                    activePeerDeviceId = frame.deviceId
                    activePeerDeviceName = frame.deviceName
                    _pendingSyncRequest.value = frame
                    _syncState.value = SyncState.AWAITING_LOCAL_APPROVAL
                } else {
                    sendFrame(SyncFrame.CancelSync(reason = "Device not paired"), sharedSecret = null)
                    _syncState.value = SyncState.IDLE
                    disconnect()
                }
            }
            is SyncFrame.SyncAcceptance -> {
                if (frame.isAccepted) {
                    _syncState.value = SyncState.SYNCING
                } else {
                    _syncState.value = SyncState.IDLE
                    disconnect()
                }
            }
            is SyncFrame.CancelSync -> {
                _syncState.value = SyncState.IDLE
                disconnect()
            }
            else -> {
                // Other frames passed to receiveFrames Flow
            }
        }
    }

    suspend fun completePairing() {
        val peerId = activePeerDeviceId ?: return
        val peerName = activePeerDeviceName ?: "Paired Device"
        val secret = activeSharedSecret ?: generateRandomSharedSecret()

        _syncState.value = SyncState.PAIRED

        val device = PairedDevice(
            deviceId = peerId,
            deviceName = peerName,
            sharedSecret = secret,
            ipAddress = activePeerIpAddress,
            port = activePeerPort,
            pairedAt = System.currentTimeMillis(),
            lastSyncAt = 0L,
            isOnline = true
        )
        pairedDeviceRepository.savePairedDevice(device)
    }

    fun setSyncStateForTesting(state: SyncState) {
        _syncState.value = state
    }

    fun setPendingPairingRequestForTesting(request: SyncFrame.PairingRequest?) {
        _pendingPairingRequest.value = request
    }

    @Synchronized
    private fun disconnectClientOnly() {
        socketJob?.cancel()
        socketJob = null
        try {
            outputStream?.close()
        } catch (e: Exception) {
            // ignore
        }
        outputStream = null
        try {
            clientSocket?.close()
        } catch (e: Exception) {
            // ignore
        }
        clientSocket = null
    }

    @Synchronized
    fun disconnect() {
        disconnectClientOnly()
        activeSharedSecret = null
        activePeerDeviceId = null
        activePeerDeviceName = null
        activePeerIpAddress = null
        _pendingPairingRequest.value = null
        _pendingSyncRequest.value = null
        if (_syncState.value != SyncState.PAIRED) {
            _syncState.value = SyncState.IDLE
        }
    }
}
