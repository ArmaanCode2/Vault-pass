package com.example.domain.sync.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Messages exchanged after the sync protocol v2 handshake. Every one of them travels inside an
 * encrypted, authenticated record (see com.vaultpass.synccore.SecureChannel).
 */
@Serializable
sealed class SyncFrame {
    /** Sent by the phone right after a pairing handshake: who it is. */
    @Serializable
    @SerialName("PairingInfo")
    data class PairingInfo(
        val deviceId: String,
        val deviceName: String
    ) : SyncFrame()

    @Serializable
    @SerialName("PairingAcceptance")
    data class PairingAcceptance(
        val deviceId: String,
        val isAccepted: Boolean
    ) : SyncFrame()

    @Serializable
    @SerialName("SyncRequest")
    data class SyncRequest(
        val deviceId: String,
        val deviceName: String
    ) : SyncFrame()

    @Serializable
    @SerialName("SyncAcceptance")
    data class SyncAcceptance(
        val deviceId: String,
        val isAccepted: Boolean
    ) : SyncFrame()

    /** One chunk of the sender's records (see SyncRecords.encodeChunks); [last] ends the vault. */
    @Serializable
    @SerialName("PayloadBatch")
    data class PayloadBatch(val encryptedBatchJson: String, val part: Int = 0, val last: Boolean = true) : SyncFrame()

    /**
     * One chunk of the reviewer's merge plan (see MergePlans.encodeChunks). The reviewer's vault
     * fingerprint after applying the plan travels in the last part only.
     */
    @Serializable
    @SerialName("MergePlanBatch")
    data class MergePlanBatch(
        val planJson: String,
        val part: Int = 0,
        val last: Boolean = true,
        val expectedFingerprint: String = ""
    ) : SyncFrame()

    /** The approver's answer to the plan; [code] carries a SyncError code when declined. */
    @Serializable
    @SerialName("MergePlanDecision")
    data class MergePlanDecision(val accepted: Boolean, val code: String = "") : SyncFrame()

    /** Sent by both devices once they applied the plan (or failed to): the result and the proof. */
    @Serializable
    @SerialName("SyncFinished")
    data class SyncFinished(val code: String = "", val fingerprint: String = "", val applied: Int = 0) : SyncFrame()

    /**
     * Sent periodically during a sync so a long review doesn't hit the idle limit. The transport
     * consumes it; it never reaches the sync screen.
     */
    @Serializable
    @SerialName("KeepAlive")
    data class KeepAlive(val sentAt: Long = 0L) : SyncFrame()

    @Serializable
    @SerialName("CancelSync")
    data class CancelSync(
        val reason: String,
        val code: String = ""
    ) : SyncFrame()
}

enum class SyncState {
    IDLE,
    CONNECTING,
    AWAITING_LOCAL_APPROVAL,
    WAITING_FOR_REMOTE_APPROVAL,
    PAIRED,
    SYNCING,
    ERROR
}
