package com.example.texty.repository

import com.example.texty.model.FriendRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

/**
 * Repository handling friend request operations.
 */
class FriendRequestRepository(
    private val firestore: FirebaseFirestore = Firebase.firestore
) {
    private val requestsCollection = firestore.collection("friend_requests")
    private val usersCollection = firestore.collection("users")

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
        firestore.runBatch { batch ->
            batch.update(requestRef, "status", "accepted")
            batch.update(usersCollection.document(fromUid), "friends", FieldValue.arrayUnion(toUid))
            batch.update(usersCollection.document(toUid), "friends", FieldValue.arrayUnion(fromUid))
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
}
