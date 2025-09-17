package com.example.texty.util

import android.util.Base64
import com.example.texty.model.EncryptionPayload
import com.example.texty.model.MessageBody
import com.example.texty.model.SessionKeyInfo
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.XChaCha20Poly1305
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import org.json.JSONObject

object MessageCrypto {
    const val CURRENT_SCHEME_VERSION = 1
    private const val KEY_SIZE_BYTES = 32
    private const val NONCE_SIZE_BYTES = 24
    private const val SALT_SIZE_BYTES = 32

    private val secureRandom = SecureRandom()

    data class EncryptionMetadata(
        val senderId: String,
        val messageType: String,
        val readBy: List<String>,
        val schemeVersion: Int,
        val encryptionTarget: String,
    )

    data class EncryptionResult(
        val payload: EncryptionPayload,
        val readBy: List<String>,
    )

    data class DecryptionResult(
        val body: MessageBody? = null,
        val errorMessage: String? = null,
        val requiresResync: Boolean = false,
    )

    fun encrypt(
        sessionKey: SessionKeyInfo,
        body: MessageBody,
        metadata: EncryptionMetadata,
    ): EncryptionResult {
        val rootKey = sessionKey.rootKey
            ?: throw IllegalStateException("Missing session root key for encryption")

        val salt = ByteArray(SALT_SIZE_BYTES).apply { secureRandom.nextBytes(this) }
        val derivedKey = Hkdf.computeHkdf(
            "HmacSha256",
            rootKey,
            salt,
            metadata.hkdfInfo(),
            KEY_SIZE_BYTES,
        )

        val nonceAndCiphertext = XChaCha20Poly1305(derivedKey).encrypt(
            body.toJsonBytes(),
            metadata.associatedData(),
        )

        val nonce = nonceAndCiphertext.copyOfRange(0, NONCE_SIZE_BYTES)
        val ciphertext = nonceAndCiphertext.copyOfRange(NONCE_SIZE_BYTES, nonceAndCiphertext.size)

        val payload = EncryptionPayload(
            ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            nonce = Base64.encodeToString(nonce, Base64.NO_WRAP),
            salt = Base64.encodeToString(salt, Base64.NO_WRAP),
            schemeVersion = metadata.schemeVersion,
            encryptionTarget = metadata.encryptionTarget,
        )

        return EncryptionResult(
            payload = payload,
            readBy = metadata.readBy,
        )
    }

    fun decrypt(
        sessionKey: SessionKeyInfo?,
        payload: EncryptionPayload,
        metadata: EncryptionMetadata,
    ): DecryptionResult {
        val rootKey = sessionKey?.rootKey
            ?: return DecryptionResult(
                body = null,
                errorMessage = "missing_session_key",
                requiresResync = true,
            )

        return try {
            val salt = Base64.decode(payload.salt, Base64.NO_WRAP)
            val nonce = Base64.decode(payload.nonce, Base64.NO_WRAP)
            val ciphertext = Base64.decode(payload.ciphertext, Base64.NO_WRAP)

            val derivedKey = Hkdf.computeHkdf(
                "HmacSha256",
                rootKey,
                salt,
                metadata.hkdfInfo(),
                KEY_SIZE_BYTES,
            )

            val nonceAndCiphertext = ByteArray(nonce.size + ciphertext.size)
            System.arraycopy(nonce, 0, nonceAndCiphertext, 0, nonce.size)
            System.arraycopy(ciphertext, 0, nonceAndCiphertext, nonce.size, ciphertext.size)

            val plaintext = XChaCha20Poly1305(derivedKey).decrypt(
                nonceAndCiphertext,
                metadata.associatedData(),
            )

            DecryptionResult(
                body = parseBody(plaintext),
                errorMessage = null,
                requiresResync = false,
            )
        } catch (error: GeneralSecurityException) {
            DecryptionResult(
                body = null,
                errorMessage = sanitizeErrorMessage(error),
                requiresResync = true,
            )
        } catch (error: Exception) {
            DecryptionResult(
                body = null,
                errorMessage = sanitizeErrorMessage(error),
                requiresResync = false,
            )
        }
    }

    fun reEncryptWithUpdatedReadReceipts(
        sessionKey: SessionKeyInfo?,
        body: MessageBody?,
        metadata: EncryptionMetadata,
    ): EncryptionResult? {
        val rootKey = sessionKey?.rootKey ?: return null
        val safeBody = body ?: return null
        return encrypt(
            sessionKey.copy(rootKey = rootKey),
            safeBody,
            metadata,
        )
    }

    private fun EncryptionMetadata.hkdfInfo(): ByteArray {
        val info = buildString {
            append("msg|")
            append(schemeVersion)
            append('|')
            append(encryptionTarget)
            append('|')
            append(messageType)
        }
        return info.toByteArray(StandardCharsets.UTF_8)
    }

    private fun EncryptionMetadata.associatedData(): ByteArray {
        val readReceipts = readBy.sorted().joinToString(",")
        val payload = buildString {
            append("ad|")
            append(schemeVersion)
            append('|')
            append(senderId)
            append('|')
            append(messageType)
            append('|')
            append(encryptionTarget)
            append('|')
            append(readReceipts)
        }
        return payload.toByteArray(StandardCharsets.UTF_8)
    }

    private fun MessageBody.toJsonBytes(): ByteArray {
        val json = JSONObject()
        if (text != null) json.put("text", text)
        if (attachmentUrl != null) json.put("attachmentUrl", attachmentUrl)
        if (attachmentMimeType != null) json.put("attachmentMimeType", attachmentMimeType)
        return json.toString().toByteArray(StandardCharsets.UTF_8)
    }

    private fun parseBody(bytes: ByteArray): MessageBody {
        val json = JSONObject(String(bytes, StandardCharsets.UTF_8))
        val text = if (json.has("text") && !json.isNull("text")) json.getString("text") else null
        val attachmentUrl = if (json.has("attachmentUrl") && !json.isNull("attachmentUrl")) {
            json.getString("attachmentUrl")
        } else {
            null
        }
        val attachmentMimeType = if (json.has("attachmentMimeType") && !json.isNull("attachmentMimeType")) {
            json.getString("attachmentMimeType")
        } else {
            null
        }
        return MessageBody(
            text = text,
            attachmentUrl = attachmentUrl,
            attachmentMimeType = attachmentMimeType,
        )
    }

    private fun sanitizeErrorMessage(error: Exception): String =
        error.javaClass.simpleName.ifEmpty { "crypto_error" }
}
