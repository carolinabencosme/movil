package com.example.texty.repository

import android.util.Base64
import com.example.texty.model.FriendRequest
import com.example.texty.model.KeyBundle
import com.example.texty.model.OneTimePreKeyInfo
import com.example.texty.model.User
import com.example.texty.model.toKeyBundle
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Transaction
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/**
 * Repository handling friend request operations.
 */
class FriendRequestRepository(
    private val firestore: FirebaseFirestore = Firebase.firestore
) {
    private val requestsCollection = firestore.collection("friend_requests")
    private val usersCollection = firestore.collection("users")
    private val sessionsCollection = firestore.collection("sessions")

    fun sendRequest(
        fromUid: String,
        toUid: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        val data = mapOf(
            "fromUid" to fromUid,
            "toUid" to toUid,
            "status" to "pending",
        )
        requestsCollection.add(data)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener(onFailure)
    }
    fun acceptRequest(
        requestId: String,
        fromUid: String,
        toUid: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        val requestRef = requestsCollection.document(requestId)
        val fromRef = usersCollection.document(fromUid)
        val toRef = usersCollection.document(toUid)

        firestore.runTransaction { tx ->

            val requestSnap = tx.get(requestRef)
            val status = requestSnap.getString("status") ?: "pending"
            if (status != "pending") {
                throw IllegalStateException("La solicitud ya no está pendiente (status=$status)")
            }

            val fromSnapshot = tx.get(fromRef)
            val toSnapshot = tx.get(toRef)

            
            val sessionWriteSet = buildSessionWriteSet(
                fromUid = fromUid,
                fromRef = fromRef,
                fromSnapshot = fromSnapshot,
                toUid = toUid,
                toRef = toRef,
                toSnapshot = toSnapshot
            )


            applySessionWriteSet(tx, sessionWriteSet)


            tx.update(requestRef, "status", "accepted")
            tx.update(fromRef, "friends", FieldValue.arrayUnion(toUid))
            tx.update(toRef, "friends", FieldValue.arrayUnion(fromUid))

            null
        }.addOnSuccessListener { onSuccess() }
            .addOnFailureListener(onFailure)
    }


    fun rejectRequest(
        requestId: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        requestsCollection.document(requestId).delete()
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener(onFailure)
    }

    fun refreshSession(
        requesterUid: String,
        peerUid: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        val requesterRef = usersCollection.document(requesterUid)
        val peerRef = usersCollection.document(peerUid)

        firestore.runTransaction { transaction ->
            val requesterSnapshot = transaction.get(requesterRef)
            val peerSnapshot = transaction.get(peerRef)

            val sessionWriteSet = buildSessionWriteSet(
                fromUid = requesterUid,
                fromRef = requesterRef,
                fromSnapshot = requesterSnapshot,
                toUid = peerUid,
                toRef = peerRef,
                toSnapshot = peerSnapshot
            )

            applySessionWriteSet(transaction, sessionWriteSet)

            null
        }.addOnSuccessListener { onSuccess() }
            .addOnFailureListener(onFailure)
    }

    fun areFriends(uid1: String, uid2: String, onResult: (Boolean) -> Unit) {
        usersCollection.document(uid1).get()
            .addOnSuccessListener { doc ->
                val friends = doc.get("friends") as? List<*> ?: emptyList<Any>()
                onResult(friends.contains(uid2))
            }
            .addOnFailureListener { onResult(false) }
    }

    fun getIncomingRequests(
        uid: String,
        onSuccess: (List<FriendRequest>) -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        requestsCollection
            .whereEqualTo("toUid", uid)
            .whereEqualTo("status", "pending")
            .get()
            .addOnSuccessListener { result ->
                val list = result.documents.map { doc ->
                    doc.toObject(FriendRequest::class.java)!!.copy(id = doc.id)
                }
                onSuccess(list)
            }
            .addOnFailureListener(onFailure)
    }

    fun hasPendingRequest(
        fromUid: String,
        toUid: String,
        onResult: (String?) -> Unit,
    ) {
        requestsCollection
            .whereEqualTo("fromUid", fromUid)
            .whereEqualTo("toUid", toUid)
            .whereEqualTo("status", "pending")
            .limit(1)
            .get()
            .addOnSuccessListener { result ->
                onResult(result.documents.firstOrNull()?.id)
            }
            .addOnFailureListener { onResult(null) }
    }

    private fun buildSessionWriteSet(
        fromUid: String,
        fromRef: DocumentReference,
        fromSnapshot: DocumentSnapshot,
        toUid: String,
        toRef: DocumentReference,
        toSnapshot: DocumentSnapshot,
    ): SessionWriteSet {
        val fromUser = fromSnapshot.toObject(User::class.java)
            ?: throw IllegalStateException("User $fromUid does not exist")
        val toUser = toSnapshot.toObject(User::class.java)
            ?: throw IllegalStateException("User $toUid does not exist")

        val fromBundle = fromUser.toKeyBundle()
            ?: throw IllegalStateException("User $fromUid is missing a published key bundle")
        val toBundle = toUser.toKeyBundle()
            ?: throw IllegalStateException("User $toUid is missing a published key bundle")

        val preKeySelection = selectPreKeyForHandshake(
            fromUid = fromUid,
            fromRef = fromRef,
            fromUser = fromUser,
            fromBundle = fromBundle,
            toUid = toUid,
            toRef = toRef,
            toUser = toUser,
            toBundle = toBundle,
        )

        val requiresReauth = preKeySelection == null
        val roomId = buildDirectRoomId(fromUid, toUid)
        val handshakeEpochMs = System.currentTimeMillis()

        val documents = mapOf(
            fromUid to buildSessionDocumentData(
                roomId = roomId,
                ownerUid = fromUid,
                ownerBundle = fromBundle,
                peerUid = toUid,
                peerBundle = toBundle,
                consumedPreKey = preKeySelection?.preKey,
                consumedPreKeyOwner = preKeySelection?.ownerUid,
                requiresReauth = requiresReauth,
                handshakeEpochMs = handshakeEpochMs,
            ),
            toUid to buildSessionDocumentData(
                roomId = roomId,
                ownerUid = toUid,
                ownerBundle = toBundle,
                peerUid = fromUid,
                peerBundle = fromBundle,
                consumedPreKey = preKeySelection?.preKey,
                consumedPreKeyOwner = preKeySelection?.ownerUid,
                requiresReauth = requiresReauth,
                handshakeEpochMs = handshakeEpochMs,
            ),
        )

        val preKeyUpdate = preKeySelection?.let { selection ->
            PreKeyUpdate(
                ownerUid = selection.ownerUid,
                ownerRef = selection.ownerRef,
                remainingPreKeys = mapPreKeysForFirestore(selection.remainingPreKeys),
                consumedKeyId = selection.preKey.keyId,
            )
        }

        return SessionWriteSet(
            roomId = roomId,
            documents = documents,
            preKeyUpdate = preKeyUpdate,
            handshakeEpochMs = handshakeEpochMs,
            requiresReauth = requiresReauth,
        )
    }

    private fun applySessionWriteSet(
        transaction: Transaction,
        writeSet: SessionWriteSet,
    ) {
        val sessionRootRef = sessionsCollection.document(writeSet.roomId)

        // 1. 🔹 Lee todos los documentos primero
        val existingRoot = transaction.get(sessionRootRef)
        val participantSnapshots = writeSet.documents.keys.associateWith { ownerUid ->
            val participantRef = sessionRootRef
                .collection(SESSION_PARTICIPANT_COLLECTION)
                .document(ownerUid)
            participantRef to transaction.get(participantRef)
        }

        // 2. 🔹 Procesa los datos con base en los snapshots
        val participants = writeSet.documents.keys.sorted()
        val rootData = mutableMapOf<String, Any>(
            "roomId" to writeSet.roomId,
            "participants" to participants,
            "latestHandshakeEpochMs" to writeSet.handshakeEpochMs,
            "requiresReauth" to writeSet.requiresReauth,
            "updatedAt" to FieldValue.serverTimestamp(),
        )

        if (!existingRoot.exists()) {
            rootData["createdAt"] = FieldValue.serverTimestamp()
            rootData[SESSION_REFRESH_COUNT_FIELD] = 0L
        } else {
            val refreshCount = existingRoot.getLong(SESSION_REFRESH_COUNT_FIELD) ?: 0L
            rootData[SESSION_REFRESH_COUNT_FIELD] = refreshCount + 1
        }

        // 3. 🔹 Ahora sí haz los writes
        transaction.set(sessionRootRef, rootData, SetOptions.merge())

        writeSet.documents.forEach { (ownerUid, payload) ->
            val (participantRef, existingParticipant) = participantSnapshots[ownerUid]!!

            val data = mutableMapOf<String, Any?>().apply {
                putAll(payload)
                this["ownerUid"] = ownerUid
                this["updatedAt"] = FieldValue.serverTimestamp()

                if (!existingParticipant.exists()) {
                    this["createdAt"] = FieldValue.serverTimestamp()
                    this[SESSION_REFRESH_COUNT_FIELD] = 0L
                } else {
                    val participantRefreshCount =
                        existingParticipant.getLong(SESSION_REFRESH_COUNT_FIELD) ?: 0L
                    this[SESSION_REFRESH_COUNT_FIELD] = participantRefreshCount + 1
                }
            }

            data.entries.removeIf { it.value == null }
            transaction.set(participantRef, data, SetOptions.merge())
        }

        writeSet.preKeyUpdate?.let { update ->
            transaction.update(
                update.ownerRef,
                mapOf(
                    "oneTimePreKeys" to update.remainingPreKeys,
                    "consumedOneTimePreKeys" to FieldValue.arrayUnion(update.consumedKeyId),
                    "lastPreKeyConsumedAt" to FieldValue.serverTimestamp(),
                )
            )
        }
    }


    private fun selectPreKeyForHandshake(
        fromUid: String,
        fromRef: DocumentReference,
        fromUser: User,
        fromBundle: KeyBundle,
        toUid: String,
        toRef: DocumentReference,
        toUser: User,
        toBundle: KeyBundle,
    ): PreKeySelection? {
        val target = toUser.oneTimePreKeys.firstOrNull()
        if (target != null) {
            val remaining = toUser.oneTimePreKeys.filterNot { it.keyId == target.keyId }
            return PreKeySelection(
                ownerUid = toUid,
                ownerRef = toRef,
                preKey = target,
                remainingPreKeys = remaining,
            )
        }

        val fallback = fromUser.oneTimePreKeys.firstOrNull()
        if (fallback != null) {
            val remaining = fromUser.oneTimePreKeys.filterNot { it.keyId == fallback.keyId }
            return PreKeySelection(
                ownerUid = fromUid,
                ownerRef = fromRef,
                preKey = fallback,
                remainingPreKeys = remaining,
            )
        }

        return null
    }

    private fun buildSessionDocumentData(
        roomId: String,
        ownerUid: String,
        ownerBundle: KeyBundle,
        peerUid: String,
        peerBundle: KeyBundle,
        consumedPreKey: OneTimePreKeyInfo?,
        consumedPreKeyOwner: String?,
        requiresReauth: Boolean,
        handshakeEpochMs: Long,
    ): MutableMap<String, Any?> {
        val peerBundleMap = mutableMapOf<String, Any?>(
            "uid" to peerUid,
            "identityKeyId" to peerBundle.identityKeyId,
            "identityPublicKey" to peerBundle.identityPublicKey,
            "identitySignaturePublicKey" to peerBundle.identitySignaturePublicKey,
            "signedPreKeyId" to peerBundle.signedPreKeyId,
            "signedPreKey" to peerBundle.signedPreKey,
            "signedPreKeySignature" to peerBundle.signedPreKeySignature,
            "oneTimePreKeyCount" to peerBundle.oneTimePreKeys.size,
        )

        val data = mutableMapOf<String, Any?>(
            "roomId" to roomId,
            "ownerUid" to ownerUid,
            "peerUid" to peerUid,
            "peerBundle" to peerBundleMap,
            "peerBundleFingerprint" to fingerprintBundle(peerBundle),
            "rootKeyMaterial" to deriveRootKeyMaterial(
                ownerUid = ownerUid,
                ownerBundle = ownerBundle,
                peerUid = peerUid,
                peerBundle = peerBundle,
                consumedPreKey = consumedPreKey,
            ),
            "protocolVersion" to SESSION_PROTOCOL_VERSION,
            "requiresReauth" to requiresReauth,
            "handshakeEpochMs" to handshakeEpochMs,
            "peerSignedPreKeyId" to peerBundle.signedPreKeyId,
            "peerIdentityKeyId" to peerBundle.identityKeyId,
            "lastPeerPreKeyCount" to peerBundle.oneTimePreKeys.size,
        )

        consumedPreKey?.let {
            data["usedOneTimePreKeyId"] = it.keyId
            data["usedOneTimePreKeyOwner"] = consumedPreKeyOwner
            data["usedOneTimePreKeyPublic"] = it.publicKey
        }

        data.entries.removeIf { it.value == null }
        return data
    }

    private fun mapPreKeysForFirestore(
        preKeys: List<OneTimePreKeyInfo>,
    ): List<Map<String, Any>> =
        preKeys.sortedBy { it.keyId }.map {
            mapOf("keyId" to it.keyId, "publicKey" to it.publicKey)
        }

    private fun fingerprintBundle(bundle: KeyBundle): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(decodeBase64(bundle.identityPublicKey))
            digest.update(decodeBase64(bundle.identitySignaturePublicKey))
            digest.update(decodeBase64(bundle.signedPreKey))
            digest.update(bundle.signedPreKeyId.toString().toByteArray(StandardCharsets.UTF_8))
            Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
        } catch (error: Exception) {
            Base64.encodeToString(
                UUID.randomUUID().toString().toByteArray(StandardCharsets.UTF_8),
                Base64.NO_WRAP,
            )
        }
    }

    private fun deriveRootKeyMaterial(
        ownerUid: String,
        ownerBundle: KeyBundle,
        peerUid: String,
        peerBundle: KeyBundle,
        consumedPreKey: OneTimePreKeyInfo?,
    ): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(ownerUid.toByteArray(StandardCharsets.UTF_8))
            digest.update(peerUid.toByteArray(StandardCharsets.UTF_8))
            digest.update(decodeBase64(ownerBundle.identityPublicKey))
            digest.update(decodeBase64(ownerBundle.identitySignaturePublicKey))
            digest.update(decodeBase64(peerBundle.identityPublicKey))
            digest.update(decodeBase64(peerBundle.identitySignaturePublicKey))
            digest.update(decodeBase64(peerBundle.signedPreKey))
            consumedPreKey?.publicKey?.let { digest.update(decodeBase64(it)) }
            Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
        } catch (error: Exception) {
            Base64.encodeToString(
                UUID.randomUUID().toString().toByteArray(StandardCharsets.UTF_8),
                Base64.NO_WRAP,
            )
        }
    }

    private fun decodeBase64(value: String): ByteArray =
        Base64.decode(value, Base64.NO_WRAP)

    private fun buildDirectRoomId(uid1: String, uid2: String): String =
        listOf(uid1, uid2).sorted().joinToString(SESSION_ROOM_ID_DELIMITER)

    private data class SessionWriteSet(
        val roomId: String,
        val documents: Map<String, MutableMap<String, Any?>>, // ownerUid -> session payload
        val preKeyUpdate: PreKeyUpdate?,
        val handshakeEpochMs: Long,
        val requiresReauth: Boolean,
    )

    private data class PreKeyUpdate(
        val ownerUid: String,
        val ownerRef: DocumentReference,
        val remainingPreKeys: List<Map<String, Any>>,
        val consumedKeyId: Int,
    )

    private data class PreKeySelection(
        val ownerUid: String,
        val ownerRef: DocumentReference,
        val preKey: OneTimePreKeyInfo,
        val remainingPreKeys: List<OneTimePreKeyInfo>,
    )

    companion object {
        private const val SESSION_PROTOCOL_VERSION = 1
        private const val SESSION_ROOM_ID_DELIMITER = "_"
        private const val SESSION_PARTICIPANT_COLLECTION = "participants"
        private const val SESSION_REFRESH_COUNT_FIELD = "refreshCount"
    }
}
