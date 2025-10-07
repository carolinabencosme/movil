package com.example.texty.model

// Describe una clave raíz guardada para un room.
data class SessionKeyInfo(
    val roomId: String,
    val ownerUid: String,
    val rootKey: ByteArray?,
    val schemeVersion: Int,
    val encryptionTarget: String,
    val requiresReauth: Boolean = false,
)
