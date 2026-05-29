package com.example.domain.models

import kotlinx.serialization.Serializable

@Serializable
data class CustomField(
    val key: String,
    val value: String
)

@Serializable
data class VaultEntry(
    val id: Int = 0,
    val title: String = "",
    val username: String = "",
    val password: String = "",
    val website: String = "",
    val notes: String = "",
    val category: String = "Personal",
    val tags: List<String> = emptyList(),
    val customFields: List<CustomField> = emptyList(),
    val isFavorite: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
