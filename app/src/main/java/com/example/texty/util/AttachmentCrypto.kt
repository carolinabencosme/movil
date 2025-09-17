package com.example.texty.util

import android.util.Base64
import com.example.texty.model.MessageBody
import com.example.texty.model.SessionKeyInfo
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.XChaCha20Poly1305
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.ktx.storage
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.LinkedHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

object AttachmentCrypto {
    private const val KEY_SIZE_BYTES = 32
    private const val NONCE_SIZE_BYTES = 24
    private const val MAC_SIZE_BYTES = 16
    private const val SALT_SIZE_BYTES = 32
    private const val CACHE_CAPACITY = 8
    private const val DEFAULT_MAX_DOWNLOAD_BYTES = 15L * 1024 * 1024

    private val secureRandom = SecureRandom()
    private val cacheLock = Any()
    private val cache = object : LinkedHashMap<String, ByteArray>(CACHE_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean {
            val shouldRemove = size > CACHE_CAPACITY
            if (shouldRemove && eldest != null) {
                eldest.value.fill(0)
            }
            return shouldRemove
        }
    }

    data class AttachmentMetadata(
        val storagePath: String,
        val mimeType: String?,
        val nonce: ByteArray,
        val mac: ByteArray,
        val salt: ByteArray,
        val ciphertextSize: Long?,
    )

    data class EncryptedAttachment(
        val storagePath: String,
        val ciphertext: ByteArray,
        val nonceBase64: String,
        val macBase64: String,
        val saltBase64: String,
        val ciphertextSize: Int,
    ) {
        fun clearCiphertext() {
            ciphertext.fill(0)
        }
    }

    fun encryptAttachment(
        sessionInfo: SessionKeyInfo,
        plaintext: ByteArray,
        storagePath: String,
        mimeType: String?,
    ): EncryptedAttachment {
        val rootKey = sessionInfo.rootKey
            ?: throw IllegalStateException("Missing session root key for attachment encryption")

        val salt = ByteArray(SALT_SIZE_BYTES).apply { secureRandom.nextBytes(this) }
        val derivedKey = Hkdf.computeHkdf(
            "HmacSha256",
            rootKey,
            salt,
            buildHkdfInfo(sessionInfo, storagePath),
            KEY_SIZE_BYTES,
        )

        val nonceAndCiphertext = try {
            XChaCha20Poly1305(derivedKey).encrypt(
                plaintext,
                buildAssociatedData(sessionInfo, mimeType, storagePath),
            )
        } finally {
            derivedKey.fill(0)
        }

        val nonce = nonceAndCiphertext.copyOfRange(0, NONCE_SIZE_BYTES)
        val ciphertext = nonceAndCiphertext.copyOfRange(NONCE_SIZE_BYTES, nonceAndCiphertext.size)
        val mac = ciphertext.copyOfRange(ciphertext.size - MAC_SIZE_BYTES, ciphertext.size)
        nonceAndCiphertext.fill(0)

        val nonceBase64 = Base64.encodeToString(nonce, Base64.NO_WRAP)
        val macBase64 = Base64.encodeToString(mac, Base64.NO_WRAP)
        val saltBase64 = Base64.encodeToString(salt, Base64.NO_WRAP)

        nonce.fill(0)
        mac.fill(0)
        salt.fill(0)

        return EncryptedAttachment(
            storagePath = storagePath,
            ciphertext = ciphertext,
            nonceBase64 = nonceBase64,
            macBase64 = macBase64,
            saltBase64 = saltBase64,
            ciphertextSize = ciphertext.size,
        )
    }

    fun extractMetadata(body: MessageBody): AttachmentMetadata? {
        val storagePath = body.attachmentStoragePath ?: return null
        val nonceBase64 = body.attachmentNonce ?: return null
        val macBase64 = body.attachmentMac ?: return null
        val saltBase64 = body.attachmentSalt ?: return null

        return AttachmentMetadata(
            storagePath = storagePath,
            mimeType = body.attachmentMimeType,
            nonce = Base64.decode(nonceBase64, Base64.NO_WRAP),
            mac = Base64.decode(macBase64, Base64.NO_WRAP),
            salt = Base64.decode(saltBase64, Base64.NO_WRAP),
            ciphertextSize = body.attachmentSize,
        )
    }

    suspend fun downloadAndDecryptAttachment(
        metadata: AttachmentMetadata,
        sessionInfo: SessionKeyInfo,
        storage: FirebaseStorage = Firebase.storage,
    ): ByteArray {
        val cached = synchronized(cacheLock) {
            cache[metadata.storagePath]?.copyOf()
        }
        if (cached != null) {
            return cached
        }

        val ciphertext = withContext(Dispatchers.IO) {
            val reference = storage.reference.child(metadata.storagePath)
            val maxDownload = metadata.ciphertextSize?.coerceAtLeast(MAC_SIZE_BYTES.toLong())
                ?: DEFAULT_MAX_DOWNLOAD_BYTES
            reference.getBytes(maxDownload).await()
        }

        if (ciphertext.size < MAC_SIZE_BYTES) {
            ciphertext.fill(0)
            throw GeneralSecurityException("attachment_ciphertext_too_small")
        }

        metadata.ciphertextSize?.let { expectedSize ->
            if (expectedSize > Int.MAX_VALUE.toLong()) {
                ciphertext.fill(0)
                throw GeneralSecurityException("attachment_size_unsupported")
            }
            if (ciphertext.size != expectedSize.toInt()) {
                ciphertext.fill(0)
                throw GeneralSecurityException("attachment_size_mismatch")
            }
        }

        val expectedMac = metadata.mac
        val actualMac = ciphertext.copyOfRange(ciphertext.size - MAC_SIZE_BYTES, ciphertext.size)
        if (!actualMac.contentEquals(expectedMac)) {
            ciphertext.fill(0)
            actualMac.fill(0)
            throw GeneralSecurityException("attachment_mac_mismatch")
        }
        actualMac.fill(0)

        val derivedKey = Hkdf.computeHkdf(
            "HmacSha256",
            sessionInfo.rootKey
                ?: throw IllegalStateException("Missing session root key for attachment decryption"),
            metadata.salt,
            buildHkdfInfo(sessionInfo, metadata.storagePath),
            KEY_SIZE_BYTES,
        )

        val nonceAndCiphertext = ByteArray(NONCE_SIZE_BYTES + ciphertext.size)
        System.arraycopy(metadata.nonce, 0, nonceAndCiphertext, 0, NONCE_SIZE_BYTES)
        System.arraycopy(ciphertext, 0, nonceAndCiphertext, NONCE_SIZE_BYTES, ciphertext.size)

        val plaintext = try {
            XChaCha20Poly1305(derivedKey).decrypt(
                nonceAndCiphertext,
                buildAssociatedData(sessionInfo, metadata.mimeType, metadata.storagePath),
            )
        } catch (error: GeneralSecurityException) {
            throw error
        } finally {
            derivedKey.fill(0)
            nonceAndCiphertext.fill(0)
            ciphertext.fill(0)
        }

        val cachedCopy = plaintext.copyOf()
        synchronized(cacheLock) {
            cache[metadata.storagePath] = cachedCopy
        }

        plaintext.fill(0)

        return cachedCopy.copyOf()
    }

    fun clearCache() {
        synchronized(cacheLock) {
            cache.values.forEach { it.fill(0) }
            cache.clear()
        }
    }

    private fun buildHkdfInfo(sessionInfo: SessionKeyInfo, storagePath: String): ByteArray {
        val info = buildString {
            append("attachment|")
            append(sessionInfo.schemeVersion)
            append('|')
            append(sessionInfo.encryptionTarget)
            append('|')
            append(storagePath)
        }
        return info.toByteArray(StandardCharsets.UTF_8)
    }

    private fun buildAssociatedData(
        sessionInfo: SessionKeyInfo,
        mimeType: String?,
        storagePath: String,
    ): ByteArray {
        val payload = buildString {
            append("attachment-ad|")
            append(sessionInfo.encryptionTarget)
            append('|')
            append(mimeType ?: "")
            append('|')
            append(storagePath)
        }
        return payload.toByteArray(StandardCharsets.UTF_8)
    }
}
