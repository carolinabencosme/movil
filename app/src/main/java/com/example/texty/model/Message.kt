package com.example.texty.model

import com.google.firebase.Timestamp

// Modelo de mensaje con metadatos de cifrado.
data class Message(
    val id: String = "",
    val senderId: String = "",
    val senderName: String = "",
    val createdAt: Timestamp? = null,
    val readBy: List<String> = emptyList(),
    val messageType: String? = null,
    val encryption: EncryptionPayload? = null,
    val decrypted: DecryptedMessage? = null,
    val decryptionError: Boolean = false,
    val requiresKeyResync: Boolean = false,
)

// Contenedor de los campos cifrados del mensaje.
data class EncryptionPayload(
    val ciphertext: String = "",
    val nonce: String = "",
    val salt: String = "",
    val schemeVersion: Int = 1,
    val encryptionTarget: String = "",
)

// Resultado ya descifrado listo para mostrar.
data class DecryptedMessage(
    val body: MessageBody,
    val displayText: String,
)

// Estructura del contenido soportado en el mensaje.
data class MessageBody(
    val text: String? = null,
    val attachmentUrl: String? = null,
    val attachmentMimeType: String? = null,
    val attachmentStoragePath: String? = null,
    val attachmentNonce: String? = null,
    val attachmentMac: String? = null,
    val attachmentSalt: String? = null,
    val attachmentSize: Long? = null,
)
