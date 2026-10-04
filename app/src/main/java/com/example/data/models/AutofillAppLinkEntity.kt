package com.example.data.models

import androidx.room.Entity

/**
 * Autofill: the user picked the entry [syncId] for the native app [packageName] through "Search VaultPass…",
 * so that app is offered the entry directly from then on. Local to this device: never part of sync records,
 * exports or backups. Browser packages are never linked.
 */
@Entity(tableName = "autofill_app_links", primaryKeys = ["packageName", "syncId"])
data class AutofillAppLinkEntity(
    val packageName: String,
    val syncId: String,
    val createdAt: Long
)
