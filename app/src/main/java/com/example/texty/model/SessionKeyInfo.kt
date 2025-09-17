package com.example.texty.model

data class SessionKeyInfo(
    val roomId: String,
    val ownerUid: String,
    val rootKey: ByteArray?,
    val schemeVersion: Int,
    val encryptionTarget: String,
    val requiresReauth: Boolean = false,
)
