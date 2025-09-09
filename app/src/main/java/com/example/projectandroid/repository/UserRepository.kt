package com.example.projectandroid.repository

import com.example.projectandroid.model.User
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class UserRepository {
    private val usersCollection = Firebase.firestore.collection("users")

    fun getUsersByDisplayName(displayName: String, onSuccess: (List<User>) -> Unit, onFailure: (Exception) -> Unit) {
        usersCollection.whereEqualTo("displayName", displayName)
            .get()
            .addOnSuccessListener { result ->
                val users = result.documents.mapNotNull { it.toObject(User::class.java) }
                onSuccess(users)
            }
            .addOnFailureListener { exception ->
                onFailure(exception)
            }
    }
}
