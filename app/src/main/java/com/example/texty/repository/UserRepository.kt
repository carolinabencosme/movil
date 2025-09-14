package com.example.texty.repository

import com.example.texty.model.User
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

/**
 * Repository that encapsulates user related Firestore queries.
 *
 * @param firestore allows dependency injection for testing.
 */
class UserRepository(private val firestore: FirebaseFirestore = Firebase.firestore) {
    private val usersCollection = firestore.collection("users")

    fun getUsersByDisplayName(
        displayName: String,
        onSuccess: (List<User>) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        if (displayName.isBlank()) {
            onSuccess(emptyList())
            return
        }

        usersCollection
            .orderBy("displayName")
            .startAt(displayName)
            .endAt(displayName + '\uf8ff')
            .get()
            .addOnSuccessListener { result ->
                val users = result.documents.mapNotNull { it.toObject(User::class.java) }
                onSuccess(users)
            }
            .addOnFailureListener { exception ->
                onFailure(exception)
            }
    }

    fun getFriends(uid: String, onSuccess: (List<User>) -> Unit, onFailure: (Exception) -> Unit) {
        Firebase.firestore.collection("users").document(uid).get()
            .addOnSuccessListener { snapshot ->
                val friendIds = snapshot.get("friends") as? List<String> ?: emptyList()
                if (friendIds.isEmpty()) {
                    onSuccess(emptyList())
                    return@addOnSuccessListener
                }

                Firebase.firestore.collection("users")
                    .whereIn("uid", friendIds)
                    .get()
                    .addOnSuccessListener { result ->
                        val users = result.toObjects(User::class.java)
                        onSuccess(users)
                    }
                    .addOnFailureListener(onFailure)
            }
            .addOnFailureListener(onFailure)
    }

}
