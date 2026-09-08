package com.example.network.sync

import android.content.Context
import android.net.wifi.WifiManager
import com.example.domain.sync.models.BeaconPayload
import com.example.domain.sync.models.DevicePresence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
data class ReverseSyncSignal(
    val type: String = "REVERSE_SYNC_REQUEST",
    val deviceId: String = "",
    val deviceName: String = "",
    val mobileIp: String,
    val mobilePort: Int = LanDiscoveryManager.SYNC_TCP_PORT
)

class LanDiscoveryManager(
    private val context: Context,
    val deviceId: String,
    val deviceName: String,
    private val scope: CoroutineScope
) {
    companion object {
        const val DISCOVERY_PORT = 53852
        const val SYNC_TCP_PORT = 53853
        const val BEACON_INTERVAL_MS = 3_000L
        const val PEER_TTL_MS = 10_000L
        const val PING_MESSAGE = "PING"
        const val BEACON_TYPE = "BEACON"
        private const val MULTICAST_LOCK_TAG = "vaultpass_multicast_lock"
        private const val PREFS_NAME = "vaultpass_sync_prefs"
        private const val KEY_LOCAL_DEVICE_ID = "local_device_id"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun getOrCreateDeviceId(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            var id = prefs.getString(KEY_LOCAL_DEVICE_ID, null)
            if (id.isNullOrBlank()) {
                id = UUID.randomUUID().toString()
                prefs.edit().putString(KEY_LOCAL_DEVICE_ID, id).apply()
            }
            return id
        }
    }

    private val _discoveredDevices = MutableStateFlow<Map<String, DevicePresence>>(emptyMap())
    val discoveredDevices: StateFlow<Map<String, DevicePresence>> = _discoveredDevices.asStateFlow()

    private val isRunning = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var broadcastJob: Job? = null
    private var listenerJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private fun acquireMulticastLock() {
        try {
            if (multicastLock == null) {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                multicastLock = wifiManager?.createMulticastLock(MULTICAST_LOCK_TAG)?.apply {
                    setReferenceCounted(false)
                }
            }
            multicastLock?.let {
                if (!it.isHeld) {
                    it.acquire()
                }
            }
        } catch (e: Exception) {
            // Multicast lock failure shouldn't crash app
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
        } catch (e: Exception) {
            // Handle gracefully
        }
    }

    @Synchronized
    fun start() {
        if (isRunning.getAndSet(true)) return

        acquireMulticastLock()

        try {
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(DISCOVERY_PORT))
            }
        } catch (e: Exception) {
            // Port might be in use or network unavailable
        }

        startListenerLoop()
        startBroadcastLoop()
    }

    @Synchronized
    fun stop() {
        if (!isRunning.getAndSet(false)) return

        broadcastJob?.cancel()
        broadcastJob = null

        listenerJob?.cancel()
        listenerJob = null

        try {
            socket?.close()
        } catch (e: Exception) {
            // ignore
        }
        socket = null

        releaseMulticastLock()
    }

    private fun startBroadcastLoop() {
        broadcastJob = scope.launch(Dispatchers.IO) {
            while (isActive && isRunning.get()) {
                sendBeaconBroadcast()
                pruneExpiredPeers()
                delay(BEACON_INTERVAL_MS)
            }
        }
    }

    private fun startListenerLoop() {
        listenerJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(2048)
            while (isActive && isRunning.get()) {
                val currentSocket = socket ?: break
                if (currentSocket.isClosed) break
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    currentSocket.receive(packet)
                    val rawMessage = String(packet.data, packet.offset, packet.length, Charsets.UTF_8).trim()
                    val senderIp = packet.address?.hostAddress ?: continue
                    val senderPort = packet.port
                    handleIncomingMessage(rawMessage, senderIp, senderPort, packet.address)
                } catch (e: SocketException) {
                    // Socket closed during stop()
                    break
                } catch (e: Exception) {
                    // Continue listener loop
                }
            }
        }
    }

    fun createBeaconPayload(): BeaconPayload =
        BeaconPayload(
            deviceId = deviceId,
            deviceName = deviceName,
            port = SYNC_TCP_PORT,
            type = BEACON_TYPE
        )

    fun createBeaconJson(): String =
        json.encodeToString(BeaconPayload.serializer(), createBeaconPayload())

    private fun sendBeaconBroadcast() {
        val beaconJson = createBeaconJson()
        val data = beaconJson.toByteArray(Charsets.UTF_8)
        broadcastBytes(data)
    }

    private fun broadcastPing() {
        val data = PING_MESSAGE.toByteArray(Charsets.UTF_8)
        broadcastBytes(data)
    }

    private fun broadcastBytes(bytes: ByteArray) {
        val currentSocket = socket ?: return
        if (currentSocket.isClosed) return

        val targets = getBroadcastAddresses()
        for (target in targets) {
            try {
                val packet = DatagramPacket(bytes, bytes.size, target, DISCOVERY_PORT)
                currentSocket.send(packet)
            } catch (e: Exception) {
                // Ignore interface specific errors
            }
        }
    }

    fun handleIncomingMessage(
        rawMessage: String,
        senderIp: String,
        senderPort: Int = DISCOVERY_PORT,
        senderAddress: InetAddress? = null
    ) {
        if (rawMessage == PING_MESSAGE) {
            sendBeaconResponse(senderAddress ?: try { InetAddress.getByName(senderIp) } catch (e: Exception) { null })
            return
        }

        val beacon = parseBeacon(rawMessage) ?: return
        if (beacon.deviceId == deviceId) {
            // Ignore packets sent by ourselves
            return
        }

        _discoveredDevices.update { current ->
            val updated = current.toMutableMap()
            updated[beacon.deviceId] = DevicePresence(
                deviceId = beacon.deviceId,
                deviceName = beacon.deviceName,
                ipAddress = senderIp,
                port = beacon.port,
                lastSeen = System.currentTimeMillis()
            )
            updated
        }
    }

    private fun sendBeaconResponse(targetAddress: InetAddress?) {
        if (targetAddress == null) return
        val currentSocket = socket
        if (currentSocket != null && !currentSocket.isClosed) {
            try {
                val beaconBytes = createBeaconJson().toByteArray(Charsets.UTF_8)
                val packet = DatagramPacket(beaconBytes, beaconBytes.size, targetAddress, DISCOVERY_PORT)
                currentSocket.send(packet)
            } catch (e: Exception) {
                // Ignore response errors
            }
        }
    }

    fun parseBeacon(jsonString: String): BeaconPayload? {
        return try {
            val payload = json.decodeFromString<BeaconPayload>(jsonString)
            if (payload.type == BEACON_TYPE && payload.deviceId.isNotBlank()) {
                payload
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun pruneExpiredPeers(currentTime: Long = System.currentTimeMillis()) {
        _discoveredDevices.update { current ->
            current.filterValues { (currentTime - it.lastSeen) <= PEER_TTL_MS }
        }
    }

    fun refreshNow() {
        scope.launch(Dispatchers.IO) {
            val currentSocket = socket
            if (currentSocket != null && !currentSocket.isClosed) {
                broadcastPing()
            } else {
                try {
                    DatagramSocket().use { tempSocket ->
                        tempSocket.broadcast = true
                        val data = PING_MESSAGE.toByteArray(Charsets.UTF_8)
                        for (target in getBroadcastAddresses()) {
                            try {
                                val packet = DatagramPacket(data, data.size, target, DISCOVERY_PORT)
                                tempSocket.send(packet)
                            } catch (e: Exception) {
                                // Ignore target error
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore transient network errors
                }
            }
        }
    }

    fun isDeviceOnline(peerDeviceId: String): Boolean {
        val presence = _discoveredDevices.value[peerDeviceId] ?: return false
        return (System.currentTimeMillis() - presence.lastSeen) <= PEER_TTL_MS
    }

    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null

            // Prioritize Wi-Fi and local Ethernet adapters over cellular and VPN tunnels
            val sortedInterfaces = interfaces.sortedByDescending { iface ->
                val name = iface.name.lowercase()
                when {
                    name.startsWith("wlan") || name.startsWith("ap") -> 3
                    name.startsWith("eth") || name.startsWith("en") -> 2
                    name.startsWith("tun") || name.startsWith("ppp") -> 0
                    name.startsWith("rmnet") || name.startsWith("pdp") || name.startsWith("ccmni") -> 0
                    else -> 1
                }
            }

            for (networkInterface in sortedInterfaces) {
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                for (address in networkInterface.inetAddresses.toList()) {
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val hostAddress = address.hostAddress
                        if (!hostAddress.isNullOrBlank()) {
                            return hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return null
    }

    fun getBroadcastAddresses(): List<InetAddress> {
        val addresses = mutableListOf<InetAddress>()
        try {
            addresses.add(InetAddress.getByName("255.255.255.255"))
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return addresses
            for (networkInterface in interfaces.toList()) {
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                for (interfaceAddress in networkInterface.interfaceAddresses) {
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast != null) {
                        addresses.add(broadcast)
                    }
                }
            }
        } catch (e: Exception) {
            // Fallback to default broadcast
        }
        return addresses.distinct()
    }

    fun clearDiscoveredDevices() {
        _discoveredDevices.value = emptyMap()
    }

    suspend fun sendReverseConnectSignal(
        desktopIp: String,
        pairingToken: String,
        mobileIp: String,
        mobilePort: Int = SYNC_TCP_PORT
    ) = withContext(Dispatchers.IO) {
        try {
            val signalJson = """{"type":"REVERSE_PAIR_REQUEST","deviceId":"$deviceId","deviceName":"$deviceName","token":"$pairingToken","mobileIp":"$mobileIp","mobilePort":$mobilePort}"""
            val bytes = signalJson.toByteArray(Charsets.UTF_8)
            val currentSocket = socket
            if (currentSocket != null && !currentSocket.isClosed) {
                currentSocket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(desktopIp), DISCOVERY_PORT))
                currentSocket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT))
            } else {
                DatagramSocket().use { tempSocket ->
                    tempSocket.broadcast = true
                    tempSocket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(desktopIp), DISCOVERY_PORT))
                    tempSocket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT))
                }
            }
        } catch (_: Exception) {}
    }

    suspend fun sendReverseSyncSignal(
        desktopIp: String,
        mobileIp: String,
        mobilePort: Int = SYNC_TCP_PORT
    ) = withContext(Dispatchers.IO) {
        try {
            val signal = ReverseSyncSignal(
                type = "REVERSE_SYNC_REQUEST",
                deviceId = deviceId,
                deviceName = deviceName,
                mobileIp = mobileIp,
                mobilePort = mobilePort
            )
            val signalJson = json.encodeToString(signal)
            val bytes = signalJson.toByteArray(Charsets.UTF_8)
            val targets = (listOf(InetAddress.getByName(desktopIp)) + getBroadcastAddresses()).distinct()
            val currentSocket = socket
            if (currentSocket != null && !currentSocket.isClosed) {
                for (target in targets) {
                    try {
                        currentSocket.send(DatagramPacket(bytes, bytes.size, target, DISCOVERY_PORT))
                    } catch (_: Exception) {}
                }
            } else {
                DatagramSocket().use { tempSocket ->
                    tempSocket.broadcast = true
                    for (target in targets) {
                        try {
                            tempSocket.send(DatagramPacket(bytes, bytes.size, target, DISCOVERY_PORT))
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {}
    }
}
