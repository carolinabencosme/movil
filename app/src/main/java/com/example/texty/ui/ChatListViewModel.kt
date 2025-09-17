package com.example.texty.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.texty.model.ChatRoom
import com.example.texty.model.EncryptionPayload
import com.example.texty.model.SessionKeyInfo
import com.example.texty.repository.SessionKeyRepository
import com.example.texty.util.MessageCrypto
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ChatListViewModel : ViewModel() {

    private val sessionKeyRepository = SessionKeyRepository()

    private val _rooms = MutableLiveData<List<ChatRoom>>()
    val rooms: LiveData<List<ChatRoom>> = _rooms

    private val _loading = MutableLiveData<Boolean>()
    val loading: LiveData<Boolean> = _loading

    private val _error = MutableLiveData<Exception?>()
    val error: LiveData<Exception?> = _error

    private var roomsListener: ListenerRegistration? = null
    private val userStateListeners = mutableMapOf<String, ListenerRegistration>()

    private val baseRooms = mutableMapOf<String, ChatRoom>()
    private val summaryStates = mutableMapOf<String, RoomSummaryState>()
    private val sessionCache = mutableMapOf<String, SessionKeyInfo>()
    private val purgedRooms = mutableSetOf<String>()

    private var currentUserUid: String? = null

    fun startListening(currentUserUid: String) {
        if (roomsListener != null) return
        this.currentUserUid = currentUserUid
        _loading.value = true
        roomsListener = Firebase.firestore
            .collection("rooms")
            .whereArrayContains("participantIds", currentUserUid)
            .addSnapshotListener { value, error ->
                _loading.value = false
                if (error != null) {
                    _error.value = error
                    return@addSnapshotListener
                }

                val documents = value?.documents.orEmpty()
                val seenRoomIds = mutableSetOf<String>()

                documents.forEach { doc ->
                    val participantIds = (doc.get("participantIds") as? List<*>)
                        ?.mapNotNull { it as? String }
                        ?.distinct()
                        ?: emptyList()
                    val userNames = (doc.get("userNames") as? Map<*, *>)
                        ?.mapNotNull { (key, value) ->
                            if (key is String && value is String) key to value else null
                        }
                        ?.toMap()
                        ?: emptyMap()
                    val isGroup = doc.getBoolean("isGroup") ?: false
                    val groupName = doc.getString("groupName")
                    val updatedAt = doc.getTimestamp("updatedAt") ?: Timestamp.now()
                    val unreadCountsLong = (doc.get("unreadCounts") as? Map<*, *>) ?: emptyMap<Any?, Any?>()
                    val unreadCounts = unreadCountsLong.mapNotNull { (key, value) ->
                        val uid = key as? String ?: return@mapNotNull null
                        val count = when (value) {
                            is Number -> value.toInt()
                            else -> null
                        }
                        count?.let { uid to it }
                    }.toMap()

                    val room = ChatRoom(
                        id = doc.id,
                        participantIds = participantIds,
                        userNames = userNames,
                        isGroup = isGroup,
                        groupName = groupName,
                        lastMessagePreview = null,
                        updatedAt = updatedAt,
                        unreadCounts = unreadCounts,
                        summaryError = false,
                        summaryRequiresResync = false,
                    )

                    baseRooms[doc.id] = room
                    seenRoomIds.add(doc.id)

                    if (!purgedRooms.contains(doc.id) && doc.data?.containsKey("lastMessage") == true) {
                        purgedRooms.add(doc.id)
                        purgeLegacyLastMessage(doc.reference)
                    }

                    ensureUserStateListener(doc.id)
                }

                val removed = baseRooms.keys - seenRoomIds
                removed.forEach { roomId ->
                    baseRooms.remove(roomId)
                    summaryStates.remove(roomId)
                    sessionCache.remove(roomId)
                    userStateListeners.remove(roomId)?.remove()
                }

                publishRooms()
            }
    }

    override fun onCleared() {
        roomsListener?.remove()
        userStateListeners.values.forEach { it.remove() }
        userStateListeners.clear()
        super.onCleared()
    }

    private fun ensureUserStateListener(roomId: String) {
        val ownerUid = currentUserUid ?: return
        if (userStateListeners.containsKey(roomId)) return

        val listener = Firebase.firestore
            .collection("rooms")
            .document(roomId)
            .collection("userState")
            .document(ownerUid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    summaryStates[roomId] = RoomSummaryState(null, null, hasError = true, requiresResync = false)
                    publishRooms()
                    return@addSnapshotListener
                }

                if (snapshot == null || !snapshot.exists()) {
                    summaryStates.remove(roomId)
                    publishRooms()
                    return@addSnapshotListener
                }

                viewModelScope.launch(Dispatchers.IO) {
                    val summaryState = decryptSummary(roomId, snapshot, ownerUid)
                    if (summaryState == null) {
                        summaryStates.remove(roomId)
                    } else {
                        summaryStates[roomId] = summaryState
                    }
                    publishRooms()
                }
            }

        userStateListeners[roomId] = listener
    }

    private suspend fun decryptSummary(
        roomId: String,
        snapshot: DocumentSnapshot,
        ownerUid: String,
    ): RoomSummaryState? {
        val room = baseRooms[roomId] ?: return null
        val session = sessionCache[roomId] ?: run {
            val peerUid = if (room.isGroup) null else room.participantIds.firstOrNull { it != ownerUid }
            val info = try {
                sessionKeyRepository.loadSessionKey(
                    roomId = roomId,
                    ownerUid = ownerUid,
                    isGroup = room.isGroup,
                    peerUid = peerUid,
                )
            } catch (error: Exception) {
                _error.postValue(error)
                return null
            }
            sessionCache[roomId] = info
            info
        }

        val payload = buildSummaryPayload(snapshot) ?: return RoomSummaryState(
            preview = null,
            hasError = true,
            requiresResync = false,
        )

        val metadata = buildSummaryMetadata(snapshot, payload, session, ownerUid)
        val result = MessageCrypto.decrypt(session, payload, metadata)

        val previewText = result.body?.text
        val requiresResync = result.requiresResync || session.requiresReauth
        val hasError = previewText == null

        return RoomSummaryState(
            preview = previewText,
            hasError = hasError,
            requiresResync = requiresResync,
        )
    }

    private fun buildSummaryPayload(snapshot: DocumentSnapshot): EncryptionPayload? {
        val ciphertext = snapshot.getString("summaryCiphertext") ?: return null
        val nonce = snapshot.getString("summaryNonce") ?: return null
        val salt = snapshot.getString("summarySalt") ?: return null
        val schemeVersion = snapshot.getLong("summarySchemeVersion")?.toInt()
            ?: MessageCrypto.CURRENT_SCHEME_VERSION
        val target = snapshot.getString("summaryEncryptionTarget") ?: ""
        return EncryptionPayload(
            ciphertext = ciphertext,
            nonce = nonce,
            salt = salt,
            schemeVersion = schemeVersion,
            encryptionTarget = target,
        )
    }

    private fun buildSummaryMetadata(
        snapshot: DocumentSnapshot,
        payload: EncryptionPayload,
        session: SessionKeyInfo,
        ownerUid: String,
    ): MessageCrypto.EncryptionMetadata {
        val messageType = snapshot.getString("summaryMessageType") ?: "summary:text/plain"
        val senderId = snapshot.getString("summarySenderId") ?: ownerUid
        val readByRaw = snapshot.get("summaryReadBy") as? List<*>
        val readBy = readByRaw?.mapNotNull { it as? String }?.ifEmpty { listOf(ownerUid) }
            ?: listOf(ownerUid)
        val encryptionTarget = payload.encryptionTarget.ifEmpty { session.encryptionTarget }

        return MessageCrypto.EncryptionMetadata(
            senderId = senderId,
            messageType = messageType,
            readBy = readBy,
            schemeVersion = payload.schemeVersion,
            encryptionTarget = encryptionTarget,
        )
    }

    private fun purgeLegacyLastMessage(reference: DocumentReference) {
        reference
            .set(mapOf("lastMessage" to FieldValue.delete()), SetOptions.merge())
            .addOnFailureListener {
                // Ignore cleanup failures – a future sync will retry.
            }
    }

    private fun publishRooms() {
        val combined = baseRooms.values.map { room ->
            val summary = summaryStates[room.id]
            room.copy(
                lastMessagePreview = summary?.preview,
                summaryError = summary?.hasError == true,
                summaryRequiresResync = summary?.requiresResync == true,
            )
        }.sortedByDescending { it.updatedAt?.toDate()?.time ?: 0L }

        _rooms.postValue(combined)
    }

    private data class RoomSummaryState(
        val preview: String?,
        val hasError: Boolean,
        val requiresResync: Boolean,
    )
}
