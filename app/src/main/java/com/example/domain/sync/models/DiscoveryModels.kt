package com.example.domain.sync.models

/** A paired device seen on the local network through an authentic beacon. */
data class DevicePresence(
    val deviceId: String,
    val deviceName: String,
    val ipAddress: String,
    val port: Int = 53853,
    val lastSeen: Long = System.currentTimeMillis()
)
