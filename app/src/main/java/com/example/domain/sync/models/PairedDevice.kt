package com.example.domain.sync.models

import kotlinx.serialization.Serializable

@Serializable
data class PairedDevice(
    val deviceId: String,
    val deviceName: String,
    val sharedSecret: String, // Pair key sealed with the vault key ("v2:..."), see PairKeyProtector
    val ipAddress: String? = null,
    val port: Int = 53853,
    val pairedAt: Long = System.currentTimeMillis(),
    val lastSyncAt: Long = 0L,
    val isOnline: Boolean = false
)
