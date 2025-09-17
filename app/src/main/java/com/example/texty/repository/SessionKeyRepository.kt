package com.example.texty.repository

import android.util.Base64
import com.example.texty.model.SessionKeyInfo
import com.example.texty.util.MessageCrypto
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class SessionKeyRepository(
    private val firestore: FirebaseFirestore = Firebase.firestore,
) {
    suspend fun loadSessionKey(
        roomId: String,
        ownerUid: String,
        isGroup: Boolean,
        peerUid: String? = null,
    ): SessionKeyInfo {
        if (isGroup) {
            val groupKey = deriveGroupKeyMaterial(roomId)
            return SessionKeyInfo(
                roomId = roomId,
                ownerUid = ownerUid,
                rootKey = groupKey,
                schemeVersion = MessageCrypto.CURRENT_SCHEME_VERSION,
                encryptionTarget = "group:$roomId",
                requiresReauth = false,
            )
        }

        val participantDoc = firestore.collection("sessions")
            .document(roomId)
            .collection("participants")
            .document(ownerUid)
            .get()
            .await()

        if (!participantDoc.exists()) {
            return SessionKeyInfo(
                roomId = roomId,
                ownerUid = ownerUid,
                rootKey = null,
                schemeVersion = MessageCrypto.CURRENT_SCHEME_VERSION,
                encryptionTarget = "direct:${peerUid ?: "unknown"}",
                requiresReauth = true,
            )
        }

        val rootKeyMaterial = participantDoc.getString("rootKeyMaterial")
        val protocolVersion = participantDoc.getLong("protocolVersion")?.toInt()
            ?: MessageCrypto.CURRENT_SCHEME_VERSION
        val targetPeer = participantDoc.getString("peerUid") ?: peerUid ?: "unknown"
        val requiresReauth = participantDoc.getBoolean("requiresReauth") ?: false

        val rootKey = rootKeyMaterial?.let { decodeBase64(it) }

        return SessionKeyInfo(
            roomId = roomId,
            ownerUid = ownerUid,
            rootKey = rootKey,
            schemeVersion = protocolVersion,
            encryptionTarget = "direct:$targetPeer",
            requiresReauth = requiresReauth || rootKey == null,
        )
    }

    private fun decodeBase64(value: String): ByteArray =
        Base64.decode(value, Base64.NO_WRAP)

    private fun deriveGroupKeyMaterial(roomId: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("group".toByteArray(StandardCharsets.UTF_8))
        digest.update(roomId.toByteArray(StandardCharsets.UTF_8))
        return digest.digest()
    }
}
