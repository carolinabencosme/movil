package com.example.texty.model

/**
 * Represents a friend request between two users.
 */
data class FriendRequest(
    val id: String = "",
    val fromUid: String = "",
    val toUid: String = "",
    val status: String = "pending",
)
