package com.example.texty.repository

import com.example.texty.model.User
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class ChatRoomRepository(
    private val firestore: FirebaseFirestore = Firebase.firestore
) {
    private val roomsCollection = firestore.collection("rooms")

    fun createGroup(
        creatorUid: String,
        groupName: String,
        members: List<User>,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val roomId = roomsCollection.document().id
        val participantIds = (members.map { it.uid } + creatorUid).distinct()
        val userNames = (members + User(uid = creatorUid, displayName = "Tú"))
            .associate { it.uid to it.displayName }

        val roomData = mapOf(
            "id" to roomId,
            "participantIds" to participantIds,
            "userNames" to userNames,
            "isGroup" to true,
            "groupName" to groupName,
            "lastMessage" to "",
            "updatedAt" to FieldValue.serverTimestamp()
        )

        roomsCollection.document(roomId)
            .set(roomData)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener(onFailure)
    }
}
