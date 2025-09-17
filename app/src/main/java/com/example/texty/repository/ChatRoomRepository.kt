package com.example.texty.repository

import android.content.Context
import android.util.Base64
import com.example.texty.crypto.KeyManager
import com.example.texty.model.KeyBundle
import com.example.texty.model.User
import com.google.crypto.tink.subtle.X25519
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Transaction
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class ChatRoomRepository(
    private val firestore: FirebaseFirestore = Firebase.firestore
) {
    private val roomsCollection = firestore.collection("rooms")
    private val random = SecureRandom()

    fun createGroup(
        context: Context,
        creatorUid: String,
        creatorDisplayName: String,
        groupName: String,
        members: List<User>,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        val keyManager = KeyManager(context)
        val keyGenerationResult = keyManager.ensureKeyBundle()
        val creatorBundle = keyGenerationResult.bundle
        val creatorPrivateKey = keyManager.getIdentityPrivateKey()
            ?: run {
                onFailure(IllegalStateException("Creator identity key is missing"))
                return
            }

        val roomId = roomsCollection.document().id
        val participantIds = (members.map { it.uid } + creatorUid).distinct()
        val userNames = (members + User(uid = creatorUid, displayName = creatorDisplayName))
            .associate { it.uid to it.displayName }

        val groupKeyMaterial = generateGroupSenderKey()

        val groupKeyWriteSet = try {
            buildEncryptedGroupKeyWriteSet(
                creatorUid = creatorUid,
                creatorDisplayName = creatorDisplayName,
                creatorBundle = creatorBundle,
                creatorPrivateIdentityKey = creatorPrivateKey,
                participants = members,
                groupKey = groupKeyMaterial,
            )
        } catch (error: Exception) {
            onFailure(error)
            return
        }

        val roomData = mapOf(
            "id" to roomId,
            "participantIds" to participantIds,
            "userNames" to userNames,
            "isGroup" to true,
            "groupName" to groupName,
            "lastMessage" to "",
            "updatedAt" to FieldValue.serverTimestamp(),
            "groupKeyVersion" to INITIAL_GROUP_KEY_VERSION,
            "groupKeyMaterialFingerprint" to groupKeyWriteSet.keyFingerprint,
        )

        firestore.runBatch { batch ->
            val roomRef = roomsCollection.document(roomId)
            batch.set(roomRef, roomData)

            val keyCollection = roomRef.collection(GROUP_KEYS_SUBCOLLECTION)
            groupKeyWriteSet.payloads.forEach { (uid, payload) ->
                val data = payload.toMutableMap()
                data["recipientUid"] = uid
                data["roomId"] = roomId
                data["senderUid"] = creatorUid
                data["keyVersion"] = INITIAL_GROUP_KEY_VERSION
                data["groupKeyFingerprint"] = groupKeyWriteSet.keyFingerprint
                data["createdAt"] = FieldValue.serverTimestamp()
                data["updatedAt"] = FieldValue.serverTimestamp()
                data.entries.removeIf { it.value == null }
                batch.set(keyCollection.document(uid), data, SetOptions.merge())
            }
        }.addOnSuccessListener { onSuccess() }
            .addOnFailureListener(onFailure)
    }

    fun rotateGroupKey(
        context: Context,
        roomId: String,
        initiatorUid: String,
        initiatorDisplayName: String,
        members: List<User>,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        val keyManager = KeyManager(context)
        val keyGenerationResult = keyManager.ensureKeyBundle()
        val initiatorBundle = keyGenerationResult.bundle
        val initiatorPrivateKey = keyManager.getIdentityPrivateKey()
            ?: run {
                onFailure(IllegalStateException("Initiator identity key is missing"))
                return
            }

        val groupKeyMaterial = generateGroupSenderKey()
        val groupKeyWriteSet = try {
            buildEncryptedGroupKeyWriteSet(
                creatorUid = initiatorUid,
                creatorDisplayName = initiatorDisplayName,
                creatorBundle = initiatorBundle,
                creatorPrivateIdentityKey = initiatorPrivateKey,
                participants = members,
                groupKey = groupKeyMaterial,
            )
        } catch (error: Exception) {
            onFailure(error)
            return
        }

        val roomRef = roomsCollection.document(roomId)

        firestore.runTransaction { transaction ->
            val snapshot = transaction.get(roomRef)
            val currentVersion = snapshot.getLong("groupKeyVersion")?.toInt()
                ?: INITIAL_GROUP_KEY_VERSION
            val newVersion = currentVersion + 1

            transaction.update(
                roomRef,
                mapOf(
                    "groupKeyVersion" to newVersion,
                    "groupKeyMaterialFingerprint" to groupKeyWriteSet.keyFingerprint,
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
            )

            val keyCollection = roomRef.collection(GROUP_KEYS_SUBCOLLECTION)
            groupKeyWriteSet.payloads.forEach { (uid, payload) ->
                val docRef = keyCollection.document(uid)
                val existing = transaction.get(docRef)
                val data = payload.toMutableMap()
                data["recipientUid"] = uid
                data["roomId"] = roomId
                data["senderUid"] = initiatorUid
                data["keyVersion"] = newVersion
                data["groupKeyFingerprint"] = groupKeyWriteSet.keyFingerprint
                data["updatedAt"] = FieldValue.serverTimestamp()
                if (!existing.exists()) {
                    data["createdAt"] = FieldValue.serverTimestamp()
                }
                data.entries.removeIf { it.value == null }
                transaction.set(docRef, data, SetOptions.merge())
            }

            null
        }.addOnSuccessListener { onSuccess() }
            .addOnFailureListener(onFailure)
    }

    private fun generateGroupSenderKey(): ByteArray = ByteArray(GROUP_KEY_SIZE).apply {
        random.nextBytes(this)
    }

    private fun buildEncryptedGroupKeyWriteSet(
        creatorUid: String,
        creatorDisplayName: String,
        creatorBundle: KeyBundle,
        creatorPrivateIdentityKey: ByteArray,
        participants: List<User>,
        groupKey: ByteArray,
    ): GroupKeyWriteSet {
        val creatorUser = User(
            uid = creatorUid,
            displayName = creatorDisplayName,
            identityPublicKey = creatorBundle.identityPublicKey,
            identitySignaturePublicKey = creatorBundle.identitySignaturePublicKey,
            signedPreKeyId = creatorBundle.signedPreKeyId,
            signedPreKey = creatorBundle.signedPreKey,
            signedPreKeySignature = creatorBundle.signedPreKeySignature,
            oneTimePreKeys = creatorBundle.oneTimePreKeys,
        )

        val participantList = (participants + creatorUser).distinctBy { it.uid }
        if (participantList.isEmpty()) {
            throw IllegalArgumentException("A group must contain at least one participant")
        }

        val payloads = mutableMapOf<String, MutableMap<String, Any?>>()
        participantList.forEach { participant ->
            if (participant.uid.isBlank()) {
                throw IllegalArgumentException("Participant uid cannot be blank")
            }

        val payload = encryptGroupKeyForParticipant(
                creatorDisplayName = creatorDisplayName,
                creatorBundle = creatorBundle,
                creatorPrivateIdentityKey = creatorPrivateIdentityKey,
                groupKey = groupKey,
                participant = participant,
            )
            payloads[participant.uid] = payload
        }

        return GroupKeyWriteSet(
            payloads = payloads,
            keyFingerprint = fingerprintGroupKey(groupKey),
        )
    }

    private fun encryptGroupKeyForParticipant(
        creatorDisplayName: String,
        creatorBundle: KeyBundle,
        creatorPrivateIdentityKey: ByteArray,
        groupKey: ByteArray,
        participant: User,
    ): MutableMap<String, Any?> {
        val recipientPublicKeyBase64 = participant.identityPublicKey
            ?: participant.signedPreKey
            ?: throw IllegalStateException("User ${participant.uid} lacks an identity or signed pre-key")

        val recipientPublicKey = decodeBase64(recipientPublicKeyBase64)
            ?: throw IllegalStateException("User ${participant.uid} has an invalid public key encoding")

        val sharedSecret = X25519.computeSharedSecret(creatorPrivateIdentityKey, recipientPublicKey)
        val symmetricKey = deriveSymmetricKey(
            sharedSecret = sharedSecret,
            senderIdentityKey = creatorBundle.identityPublicKey,
            recipientPublicKey = recipientPublicKeyBase64,
            recipientSignedPreKey = participant.signedPreKey,
        )

        val iv = ByteArray(GCM_IV_SIZE).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val secretKey = SecretKeySpec(symmetricKey, AES_ALGORITHM)
        val params = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, params)
        val ciphertext = cipher.doFinal(groupKey)

        val sharedSecretFingerprint = fingerprintSharedSecret(sharedSecret, recipientPublicKey)

        val encryptionMetadata = mutableMapOf<String, Any?>(
            "scheme" to GROUP_KEY_SCHEME,
            "hkdf" to "SHA-256",
            "senderIdentityKey" to creatorBundle.identityPublicKey,
            "recipientKey" to recipientPublicKeyBase64,
            "sharedSecretFingerprint" to sharedSecretFingerprint,
            "senderSignedPreKeyId" to creatorBundle.signedPreKeyId,
            "recipientSignedPreKeyId" to participant.signedPreKeyId,
        )

        participant.identitySignaturePublicKey?.let {
            encryptionMetadata["recipientIdentitySignatureKey"] = it
        }

        val recipientBundle = mutableMapOf<String, Any?>(
            "uid" to participant.uid,
            "identityPublicKey" to participant.identityPublicKey,
            "identitySignaturePublicKey" to participant.identitySignaturePublicKey,
            "signedPreKey" to participant.signedPreKey,
            "signedPreKeyId" to participant.signedPreKeyId,
            "oneTimePreKeyCount" to participant.oneTimePreKeys.size,
            "consumedOneTimePreKeys" to participant.consumedOneTimePreKeys,
        )

        participant.oneTimePreKeys.firstOrNull()?.let { preKey ->
            recipientBundle["candidateOneTimePreKeyId"] = preKey.keyId
            recipientBundle["candidateOneTimePreKeyPublic"] = preKey.publicKey
        }

        return mutableMapOf(
            "ciphertext" to Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            "initialisationVector" to Base64.encodeToString(iv, Base64.NO_WRAP),
            "encryption" to encryptionMetadata,
            "senderDisplayName" to creatorDisplayName,
            "recipientDisplayName" to participant.displayName,
            "recipientBundle" to recipientBundle,
            "sharedSecretFingerprint" to sharedSecretFingerprint,
            "groupKeyEncoding" to GROUP_KEY_ENCODING,
            "groupKeyCiphertextLength" to ciphertext.size,
        )
    }

    private fun deriveSymmetricKey(
        sharedSecret: ByteArray,
        senderIdentityKey: String,
        recipientPublicKey: String,
        recipientSignedPreKey: String?,
    ): ByteArray {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(sharedSecret)
            decodeBase64(senderIdentityKey)?.let { digest.update(it) }
            decodeBase64(recipientPublicKey)?.let { digest.update(it) }
            recipientSignedPreKey?.let { decodeBase64(it)?.let(digest::update) }
            digest.digest()
        } catch (error: Exception) {
            MessageDigest.getInstance("SHA-256")
                .digest(UUID.randomUUID().toString().toByteArray(StandardCharsets.UTF_8))
        }
    }

    private fun fingerprintSharedSecret(
        sharedSecret: ByteArray,
        recipientPublicKey: ByteArray,
    ): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(sharedSecret)
            digest.update(recipientPublicKey)
            Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
        } catch (error: Exception) {
            Base64.encodeToString(
                UUID.randomUUID().toString().toByteArray(StandardCharsets.UTF_8),
                Base64.NO_WRAP,
            )
        }
    }

    private fun fingerprintGroupKey(groupKey: ByteArray): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(groupKey)
            Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
        } catch (error: Exception) {
            Base64.encodeToString(
                UUID.randomUUID().toString().toByteArray(StandardCharsets.UTF_8),
                Base64.NO_WRAP,
            )
        }
    }

    private fun decodeBase64(value: String?): ByteArray? =
        value?.let {
            try {
                Base64.decode(it, Base64.NO_WRAP)
            } catch (error: IllegalArgumentException) {
                null
            }
        }

    private data class GroupKeyWriteSet(
        val payloads: Map<String, MutableMap<String, Any?>>, // uid -> encrypted payload
        val keyFingerprint: String,
    )

    companion object {
        private const val GROUP_KEYS_SUBCOLLECTION = "groupKeys"
        private const val INITIAL_GROUP_KEY_VERSION = 1
        private const val GROUP_KEY_SIZE = 32
        private const val GCM_IV_SIZE = 12
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val AES_ALGORITHM = "AES"
        private const val GROUP_KEY_SCHEME = "X25519-AES-GCM"
        private const val GROUP_KEY_ENCODING = "base64"
    }
}
