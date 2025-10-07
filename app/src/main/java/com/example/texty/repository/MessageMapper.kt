package com.example.texty.repository

import com.example.texty.model.DecryptedMessage
import com.example.texty.model.EncryptionPayload
import com.example.texty.model.Message
import com.example.texty.model.MessageBody
import com.example.texty.model.SessionKeyInfo
import com.example.texty.util.MessageCrypto
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.SetOptions
import java.util.Locale

class MessageMapper(
    private val sessionKeyInfo: SessionKeyInfo?,
) {
    data class MessageDocument(
        val snapshot: DocumentSnapshot,
        val message: Message,
        val body: MessageBody?,
        val encryptionPayload: EncryptionPayload?,
        val encryptionMetadata: MessageCrypto.EncryptionMetadata?,
    )

    fun map(snapshot: DocumentSnapshot): MessageDocument? {
        val senderId = snapshot.getString("senderId") ?: return null
        val senderName = snapshot.getString("senderName") ?: ""
        val createdAt = snapshot.getTimestamp("createdAt")
        val readBy = snapshot.get("readBy") as? List<*> ?: emptyList<Any>()
        val readByStrings = readBy.mapNotNull { it as? String }
        val messageType = snapshot.getString("messageType") ?: deriveLegacyType(snapshot)
        val encryptionPayload = buildEncryptionPayload(snapshot)
        val encryptionMetadata = encryptionPayload?.let { payload ->
            MessageCrypto.EncryptionMetadata(
                senderId = senderId,
                messageType = messageType,
                readBy = readByStrings,
                schemeVersion = payload.schemeVersion,
                encryptionTarget = payload.encryptionTarget.ifEmpty { sessionKeyInfo?.encryptionTarget ?: "" },
            )
        }

        val decryptedResult = if (encryptionPayload != null && encryptionMetadata != null) {
            MessageCrypto.decrypt(sessionKeyInfo, encryptionPayload, encryptionMetadata)
        } else {
            null
        }

        val body = when {
            decryptedResult?.body != null -> decryptedResult.body
            encryptionPayload == null -> buildLegacyBody(snapshot)
            else -> null
        }

        val displayText = when {
            body != null -> buildDisplayText(body, messageType)
            decryptedResult != null && decryptedResult.body == null -> null
            else -> null
        }

        val decryptedMessage = body?.let {
            DecryptedMessage(
                body = it,
                displayText = displayText ?: "",
            )
        }

        val message = Message(
            id = snapshot.id,
            senderId = senderId,
            senderName = senderName,
            createdAt = createdAt,
            readBy = readByStrings,
            messageType = messageType,
            encryption = encryptionPayload,
            decrypted = decryptedMessage,
            decryptionError = decryptedResult?.body == null && encryptionPayload != null,
            requiresKeyResync = decryptedResult?.requiresResync == true || (sessionKeyInfo?.requiresReauth == true),
        )

        return MessageDocument(
            snapshot = snapshot,
            message = message,
            body = body,
            encryptionPayload = encryptionPayload,
            encryptionMetadata = encryptionMetadata,
        )
    }

    fun buildReadReceiptUpdate(
        document: MessageDocument,
        readerUid: String,
    ): Pair<Map<String, Any>, SetOptions>? {
        val currentReadBy = document.message.readBy.toMutableSet()
        if (!currentReadBy.add(readerUid)) {
            return null
        }
        val updatedReadBy = currentReadBy.toList().sorted()

        val payload = document.encryptionPayload
        val metadata = document.encryptionMetadata
        val body = document.body

        if (payload != null && metadata != null && body != null) {
            val newMetadata = metadata.copy(readBy = updatedReadBy)
            val result = MessageCrypto.reEncryptWithUpdatedReadReceipts(
                sessionKey = sessionKeyInfo,
                body = body,
                metadata = newMetadata,
            ) ?: return null

            val update = mapOf(
                "ciphertext" to result.payload.ciphertext,
                "nonce" to result.payload.nonce,
                "salt" to result.payload.salt,
                "schemeVersion" to result.payload.schemeVersion,
                "encryptionTarget" to result.payload.encryptionTarget,
                "readBy" to updatedReadBy,
            )
            return update to SetOptions.merge()
        }

        val fallbackUpdate = mapOf(
            "readBy" to updatedReadBy,
        )
        return fallbackUpdate to SetOptions.merge()
    }

    private fun buildEncryptionPayload(snapshot: DocumentSnapshot): EncryptionPayload? {
        val ciphertext = snapshot.getString("ciphertext") ?: return null
        val nonce = snapshot.getString("nonce") ?: return null
        val salt = snapshot.getString("salt") ?: return null
        val schemeVersion = snapshot.getLong("schemeVersion")?.toInt()
            ?: sessionKeyInfo?.schemeVersion
            ?: MessageCrypto.CURRENT_SCHEME_VERSION
        val encryptionTarget = snapshot.getString("encryptionTarget")
            ?: sessionKeyInfo?.encryptionTarget
            ?: ""
        return EncryptionPayload(
            ciphertext = ciphertext,
            nonce = nonce,
            salt = salt,
            schemeVersion = schemeVersion,
            encryptionTarget = encryptionTarget,
        )
    }

    private fun buildLegacyBody(snapshot: DocumentSnapshot): MessageBody? {
        val text = snapshot.getString("text")
        val imageUrl = snapshot.getString("imageUrl")
        if (text == null && imageUrl == null) {
            return null
        }
        val mimeType = imageUrl?.let { "media/image" }
        return MessageBody(
            text = text,
            attachmentUrl = imageUrl,
            attachmentMimeType = mimeType,
        )
    }

    private fun deriveLegacyType(snapshot: DocumentSnapshot): String {
        return when {
            !snapshot.getString("imageUrl").isNullOrEmpty() -> "media/image"
            !snapshot.getString("text").isNullOrEmpty() -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    private fun buildDisplayText(body: MessageBody, messageType: String?): String {
        val normalizedType = messageType?.lowercase(Locale.US) ?: ""
        return when {
            normalizedType.startsWith("text") -> body.text.orEmpty()
            normalizedType.contains("image") -> "Imagen"
            normalizedType.startsWith("media") -> "\uD83D\uDCCE Archivo cifrado"
            body.text != null -> body.text
            else -> "\uD83D\uDCCE Archivo cifrado"
        }
    }
}
