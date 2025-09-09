package com.example.projectandroid.ui

import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.projectandroid.R
import com.example.projectandroid.model.Message
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class ChatActivity : AppCompatActivity() {

  private lateinit var recyclerView: RecyclerView
  private lateinit var adapter: ChatAdapter
  private lateinit var messageInput: EditText
  private lateinit var sendButton: View

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val auth = Firebase.auth
    if (auth.currentUser == null) {
      auth.signInAnonymously().addOnSuccessListener {
        initChat()
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

    adapter = ChatAdapter(currentUser.uid, mutableListOf())
    recyclerView.layoutManager = LinearLayoutManager(this).apply {
      stackFromEnd = true
    }
    recyclerView.adapter = adapter

    val ref = Firebase.firestore
      .collection("rooms")
      .document("general")
      .collection("messages")

    ref.orderBy("createdAt").addSnapshotListener { value, _ ->
      val messages = value?.documents?.mapNotNull { it.toObject(Message::class.java) } ?: return@addSnapshotListener
      adapter.submit(messages)
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
      ref.add(data)
      messageInput.text.clear()
    }
  }
}
