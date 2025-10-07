package com.example.texty.model

import com.google.firebase.Timestamp

/**
 * Representa una sala de chat con participantes y metadatos de sincronización.
 */
data class ChatRoom(
    val id: String = "",
    val participantIds: List<String> = emptyList(),
    val userNames: Map<String, String> = emptyMap(),
    val isGroup: Boolean = false,
    val groupName: String? = null,
    val lastMessagePreview: String? = null,
    val updatedAt: Timestamp? = null,
    val unreadCounts: Map<String, Int> = emptyMap(),
    val summaryError: Boolean = false,
    val summaryRequiresResync: Boolean = false,
    val photoUrl: String? = null
)
