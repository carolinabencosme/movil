package com.example.projectandroid.repository

import com.example.projectandroid.model.User
import com.google.firebase.firestore.*
import io.mockk.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UserRepositoryTest {

    @Test
    fun returnsEmptyListWhenDisplayNameBlank() {
        val firestore = mockk<FirebaseFirestore>(relaxed = true)
        val repository = UserRepository(firestore)
        var result: List<User>? = null
        repository.getUsersByDisplayName("", { result = it }, { fail("Should not fail") })
        assertTrue(result!!.isEmpty())
        verify { firestore wasNot Called }
    }

    @Test
    fun queriesFirestoreAndMapsUsers() {
        val firestore = mockk<FirebaseFirestore>()
        val collection = mockk<CollectionReference>()
        val query = mockk<Query>()
        val task = mockk<com.google.android.gms.tasks.Task<QuerySnapshot>>()

        every { firestore.collection("users") } returns collection
        every { collection.orderBy("displayName") } returns query
        every { query.startAt("John") } returns query
        every { query.endAt("John\uf8ff") } returns query
        every { query.get() } returns task

        val document = mockk<DocumentSnapshot>()
        every { document.toObject(User::class.java) } returns User(uid = "1", displayName = "John")
        val snapshot = mockk<QuerySnapshot>()
        every { snapshot.documents } returns listOf(document)

        every { task.addOnSuccessListener(any()) } answers {
            firstArg<com.google.android.gms.tasks.OnSuccessListener<QuerySnapshot>>().onSuccess(snapshot)
            task
        }
        every { task.addOnFailureListener(any()) } returns task

        val repository = UserRepository(firestore)
        var result: List<User>? = null
        repository.getUsersByDisplayName("John", { result = it }, { fail("Should not fail") })

        assertEquals(listOf(User(uid = "1", displayName = "John")), result)
    }
}

