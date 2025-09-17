package com.example.texty.model

/**
 * Represents a user profile stored in Firestore.
 */
data class User(
    val uid: String = "",
    val displayName: String = "",
    val photoUrl: String? = null,
    val isOnline: Boolean = false,
    val about: String = "",
    val phone: String = "",
    val friends: List<String> = emptyList(),
    val fcmTokens: List<String> = emptyList(),
    val identityPublicKey: String? = null,
    val identitySignaturePublicKey: String? = null,
    val signedPreKeyId: Int? = null,
    val signedPreKey: String? = null,
    val signedPreKeySignature: String? = null,
    val oneTimePreKeys: List<OneTimePreKeyInfo> = emptyList(),
    val consumedOneTimePreKeys: List<Int> = emptyList(),
)
