package com.example.projectandroid.model

import com.google.firebase.Timestamp

/**
 * Represents a chat room with summary info for the list.
 */
data class ChatRoom(
    val id: String = "",
    val contactUid: String = "",
    val contactName: String = "",
    val lastMessage: String = "",
    val updatedAt: Timestamp = Timestamp.now(),
)
