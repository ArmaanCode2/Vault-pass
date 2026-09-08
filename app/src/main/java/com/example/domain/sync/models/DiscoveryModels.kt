package com.example.domain.sync.models

import kotlinx.serialization.Serializable

@Serializable
data class BeaconPayload(
    val deviceId: String,
    val deviceName: String,
    val port: Int = 53853,
    val type: String = "BEACON"
)

data class DevicePresence(
    val deviceId: String,
    val deviceName: String,
    val ipAddress: String,
    val port: Int = 53853,
    val lastSeen: Long = System.currentTimeMillis()
)
