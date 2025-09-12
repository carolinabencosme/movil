package com.example.texty.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.texty.model.ChatRoom
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class ChatListViewModel : ViewModel() {

    private val _rooms = MutableLiveData<List<ChatRoom>>()
    val rooms: LiveData<List<ChatRoom>> = _rooms

    private val _loading = MutableLiveData<Boolean>()
    val loading: LiveData<Boolean> = _loading

    private val _error = MutableLiveData<Exception?>()
    val error: LiveData<Exception?> = _error

    private var listenerRegistration: ListenerRegistration? = null

    fun startListening(currentUserUid: String) {
        if (listenerRegistration != null) return
        _loading.value = true
        listenerRegistration = Firebase.firestore
            .collection("rooms")
            .whereArrayContains("participantIds", currentUserUid)
            .addSnapshotListener { value, e ->
                _loading.value = false
                if (e != null) {
                    _error.value = e
                    return@addSnapshotListener
                }
                val list = value?.documents?.mapNotNull { doc ->
                    val participantIds = doc.get("participantIds") as? List<*>
                    val otherUid = participantIds?.firstOrNull { it != currentUserUid } as? String
                    val userNames = doc.get("userNames") as? Map<*, *>
                    val contactName = userNames?.get(otherUid) as? String ?: ""
                    val lastMessage = doc.getString("lastMessage") ?: ""
                    val updatedAt = doc.getTimestamp("updatedAt")
                        ?: com.google.firebase.Timestamp.now()
                    if (otherUid == null) null else ChatRoom(
                        id = doc.id,
                        contactUid = otherUid,
                        contactName = contactName,
                        lastMessage = lastMessage,
                        updatedAt = updatedAt,
                    )
                } ?: emptyList()
                _rooms.value = list
            }
    }

    override fun onCleared() {
        listenerRegistration?.remove()
        super.onCleared()
    }
}

