package com.example.sync

import com.example.domain.sync.models.PairedDevice
import com.example.domain.sync.models.SyncFrame
import com.example.network.sync.AndroidPairKeyProtector
import com.example.repository.PairedDeviceRepository
import com.vaultpass.synccore.HandshakeMode
import com.vaultpass.synccore.MergePlans
import com.vaultpass.synccore.PlanItem
import com.vaultpass.synccore.SecureChannel
import com.vaultpass.synccore.SyncCrypto
import com.vaultpass.synccore.SyncRecord
import com.vaultpass.synccore.SyncRecords
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/** Forwards connections to [targetPort] and records every byte in both directions. */
class RecordingProxy(private val targetPort: Int) : AutoCloseable {
    private val server = ServerSocket(0)
    private val recorded = java.io.ByteArrayOutputStream()
    val port: Int get() = server.localPort

    init {
        kotlin.concurrent.thread(isDaemon = true) {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (e: Exception) {
                    break
                }
                val upstream = Socket("127.0.0.1", targetPort)
                pump(client, upstream)
                pump(upstream, client)
            }
        }
    }

    private fun pump(from: Socket, to: Socket) {
        kotlin.concurrent.thread(isDaemon = true) {
            val buffer = ByteArray(8192)
            try {
                val input = from.getInputStream()
                val output = to.getOutputStream()
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    synchronized(recorded) { recorded.write(buffer, 0, read) }
                    output.write(buffer, 0, read)
                    output.flush()
                }
            } catch (_: Exception) {
            } finally {
                try { to.shutdownOutput() } catch (_: Exception) {}
                try { from.close() } catch (_: Exception) {}
            }
        }
    }

    fun captured(): ByteArray = synchronized(recorded) { recorded.toByteArray() }

    override fun close() {
        server.close()
    }
}

fun ByteArray.containsSequence(part: ByteArray): Boolean {
    if (part.isEmpty() || part.size > size) return false
    outer@ for (i in 0..(size - part.size)) {
        for (j in part.indices) {
            if (this[i + j] != part[j]) continue@outer
        }
        return true
    }
    return false
}

/** Stores a paired desktop the way pairing does (key sealed with the vault key) and returns its pair key. */
suspend fun pairTestDesktop(
    repository: PairedDeviceRepository,
    protector: AndroidPairKeyProtector,
    deviceId: String,
    deviceName: String,
    ipAddress: String? = null,
    port: Int = 53853
): ByteArray {
    val pairKey = SyncCrypto.randomBytes(32)
    repository.savePairedDevice(
        PairedDevice(
            deviceId = deviceId,
            deviceName = deviceName,
            sharedSecret = protector.seal(pairKey)!!,
            ipAddress = ipAddress,
            port = port
        )
    )
    return pairKey
}

/** A scripted desktop speaking sync protocol v2, used to drive the phone over real sockets. */
class ScriptedDesktop(val socket: Socket, val channel: SecureChannel) : AutoCloseable {

    fun send(frame: SyncFrame) {
        channel.send(json.encodeToString(SyncFrame.serializer(), frame).toByteArray(Charsets.UTF_8))
    }

    fun receive(): SyncFrame =
        json.decodeFromString(SyncFrame.serializer(), String(channel.receive(), Charsets.UTF_8))

    override fun close() {
        socket.close()
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** Connects to the phone and runs the sync handshake as the initiator. */
        fun connect(port: Int, pairKey: ByteArray): ScriptedDesktop {
            val socket = connectWithRetry(port)
            return try {
                ScriptedDesktop(
                    socket,
                    SecureChannel.initiate(DataInputStream(socket.getInputStream()), DataOutputStream(socket.getOutputStream()), HandshakeMode.SYNC, pairKey)
                )
            } catch (e: Exception) {
                socket.close()
                throw e
            }
        }

        /**
         * Accepts the phone's connection on [server] and answers its handshake: pairing with
         * [pairingSecret] (the QR secret) or syncing with [pairKey]. Returns null if the phone
         * used a secret we don't hold.
         */
        fun accept(server: ServerSocket, pairingSecret: ByteArray? = null, pairKey: ByteArray? = null): ScriptedDesktop? {
            val socket = server.accept().apply { soTimeout = 5000 }
            val accepted = SecureChannel.accept(DataInputStream(socket.getInputStream()), DataOutputStream(socket.getOutputStream())) { mode ->
                val secret = if (mode == HandshakeMode.PAIR) pairingSecret else pairKey
                if (secret != null) listOf("desktop" to secret) else emptyList()
            }
            if (accepted == null) {
                socket.close()
                return null
            }
            return ScriptedDesktop(socket, accepted.channel)
        }

        fun connectWithRetry(port: Int): Socket {
            val deadline = System.currentTimeMillis() + 5000
            while (true) {
                try {
                    return Socket("127.0.0.1", port).apply { soTimeout = 5000 }
                } catch (e: Exception) {
                    if (System.currentTimeMillis() > deadline) throw e
                    Thread.sleep(50)
                }
            }
        }
    }
}

/** A desktop vault on the wire, in as many parts as [maxBytes] needs. */
fun ScriptedDesktop.sendRecords(records: List<SyncRecord>, maxBytes: Int = SyncRecords.DEFAULT_CHUNK_BYTES) {
    val chunks = SyncRecords.encodeChunks(records, maxBytes)
    chunks.forEachIndexed { i, chunk ->
        send(SyncFrame.PayloadBatch(chunk, part = i, last = i == chunks.lastIndex))
    }
}

/** The reviewed merge plan, with the fingerprint the phone must end up with. */
fun ScriptedDesktop.sendPlan(
    items: List<PlanItem>,
    expectedFingerprint: String = "",
    maxBytes: Int = SyncRecords.DEFAULT_CHUNK_BYTES
) {
    val chunks = MergePlans.encodeChunks(items, maxBytes)
    chunks.forEachIndexed { i, chunk ->
        val last = i == chunks.lastIndex
        send(SyncFrame.MergePlanBatch(chunk, part = i, last = last, expectedFingerprint = if (last) expectedFingerprint else ""))
    }
}

/** Sends KeepAlive every [intervalMs] like the real desktop does, until the connection closes. */
fun ScriptedDesktop.keepAlive(intervalMs: Long) {
    kotlin.concurrent.thread(isDaemon = true) {
        try {
            while (!socket.isClosed) {
                Thread.sleep(intervalMs)
                send(SyncFrame.KeepAlive(System.currentTimeMillis()))
            }
        } catch (_: Exception) {
        }
    }
}

/** Reads the phone's frames on a background thread (the test thread runs the main looper). */
fun ScriptedDesktop.collectInBackground(): List<SyncFrame> {
    val frames = CopyOnWriteArrayList<SyncFrame>()
    kotlin.concurrent.thread(isDaemon = true) {
        try {
            while (true) frames += receive()
        } catch (_: Exception) {
        }
    }
    return frames
}
