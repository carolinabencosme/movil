package com.example.projectandroid.ui

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.projectandroid.R
import com.example.projectandroid.model.Message
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.example.projectandroid.util.ErrorLogger

class ChatActivity : AppCompatActivity() {

  private lateinit var recyclerView: RecyclerView
  private lateinit var adapter: ChatAdapter
  private lateinit var messageInput: EditText
  private lateinit var sendButton: View
  private lateinit var listenerRegistration: ListenerRegistration

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val auth = Firebase.auth
    if (auth.currentUser == null) {
      auth
        .signInAnonymously()
        .addOnSuccessListener { initChat() }
        .addOnFailureListener { e ->
          Toast.makeText(
            this,
            "No se pudo iniciar sesión: ${e.localizedMessage ?: "Revisa tu conexión"}. Inténtalo nuevamente",
            Toast.LENGTH_LONG,
          ).show()
        }
    } else {
      initChat()
    }
  }

  private fun initChat() {
    setContentView(R.layout.activity_chat)

    recyclerView = findViewById(R.id.recyclerView)
    messageInput = findViewById(R.id.editMessage)
    sendButton = findViewById(R.id.buttonSend)

    val currentUser = Firebase.auth.currentUser!!

    adapter = ChatAdapter(currentUser.uid)
    recyclerView.layoutManager = LinearLayoutManager(this).apply {
      stackFromEnd = true
    }
    recyclerView.adapter = adapter

    val ref = Firebase.firestore
      .collection("rooms")
      .document("general")
      .collection("messages")

    listenerRegistration =
      ref.orderBy("createdAt").addSnapshotListener { value, error ->
        if (error != null) {
          ErrorLogger.log(this, error)
          return@addSnapshotListener
        }
        val messages = value?.documents?.mapNotNull { it.toObject(Message::class.java) } ?: return@addSnapshotListener
        adapter.submitList(messages)
        recyclerView.scrollToPosition(adapter.itemCount - 1)
      }

    sendButton.setOnClickListener {
      val text = messageInput.text.toString().trim()
      if (text.isEmpty()) return@setOnClickListener

      val data = mapOf(
        "senderId" to currentUser.uid,
        "senderName" to (currentUser.displayName ?: ""),
        "text" to text,
        "createdAt" to FieldValue.serverTimestamp(),
      )
      ref.add(data).addOnFailureListener { e -> ErrorLogger.log(this, e) }
      messageInput.text.clear()
    }
  }

  override fun onDestroy() {
    if (::listenerRegistration.isInitialized) {
      listenerRegistration.remove()
    }
    super.onDestroy()
  }
}
