package com.example.texty.model

import com.google.firebase.Timestamp

/**
 * Represents a chat room with summary info for the list.
 */
/*data class ChatRoom(
    val id: String = "",
    val contactUid: String = "",
    val contactName: String = "",
    val lastMessage: String = "",
    val updatedAt: Timestamp = Timestamp.now(),
)*/

data class ChatRoom(
    val id: String = "",
    val participantIds: List<String> = emptyList(),
    val userNames: Map<String, String> = emptyMap(),
    val isGroup: Boolean = false,
    val groupName: String? = null,
    val lastMessage: String = "",
    val updatedAt: Timestamp? = null
)
