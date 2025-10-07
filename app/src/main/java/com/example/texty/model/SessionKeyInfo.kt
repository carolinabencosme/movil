package com.example.texty.model

/**
 * Describe la clave raíz y metadatos de cifrado guardados para un room.
 */
data class SessionKeyInfo(
    val roomId: String,
    val ownerUid: String,
    val rootKey: ByteArray?,
    val schemeVersion: Int,
    val encryptionTarget: String,
    val requiresReauth: Boolean = false,
)
