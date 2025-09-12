package com.example.texty.ui

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.texty.R
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.*
import com.google.firebase.ktx.Firebase
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatActivityTest {

    @Before
    fun setup() {
        Intents.init()
        mockkObject(Firebase)
    }

    @After
    fun tearDown() {
        unmockkAll()
        Intents.release()
    }

    @Test
    fun redirectsToLoginWhenNoUser() {
        val auth: FirebaseAuth = mockk(relaxed = true)
        every { Firebase.auth } returns auth
        every { auth.currentUser } returns null

        ActivityScenario.launch(ChatActivity::class.java)
        intended(hasComponent(LoginActivity::class.java.name))
    }

    @Test
    fun sendMessageWritesToFirestore() {
        val auth: FirebaseAuth = mockk()
        val user: FirebaseUser = mockk()
        every { Firebase.auth } returns auth
        every { auth.currentUser } returns user
        every { user.uid } returns "123"
        every { user.displayName } returns "Tester"

        val firestore: FirebaseFirestore = mockk()
        every { Firebase.firestore } returns firestore

        val rooms: CollectionReference = mockk()
        val roomDoc: DocumentReference = mockk()
        val messages: CollectionReference = mockk()
        val query: Query = mockk()
        val registration: ListenerRegistration = mockk(relaxed = true)
        val task: com.google.android.gms.tasks.Task<DocumentReference> = mockk()

        every { firestore.collection("rooms") } returns rooms
        every { rooms.document(any()) } returns roomDoc
        every { roomDoc.collection("messages") } returns messages
        every { messages.orderBy("createdAt") } returns query
        every { query.addSnapshotListener(any()) } returns registration
        every { messages.add(any()) } returns task
        every { task.addOnFailureListener(any()) } returns task

        val intent = Intent().putExtra("recipientUid", "456").putExtra("recipientName", "Receiver")
        ActivityScenario.launch<ChatActivity>(intent).use {
            onView(withId(R.id.editMessage)).perform(replaceText("hello"), closeSoftKeyboard())
            onView(withId(R.id.buttonSend)).perform(click())
        }
        verify { messages.add(match { it["text"] == "hello" && it["senderId"] == "123" }) }
    }
}
