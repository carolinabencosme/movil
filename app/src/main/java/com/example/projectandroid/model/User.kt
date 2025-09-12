package com.example.projectandroid.model

/**
 * Represents a user profile stored in Firestore.
 */
data class User(
    val uid: String = "",
    val displayName: String = "",
    val photoUrl: String? = null,
    val isOnline: Boolean = false,
)
