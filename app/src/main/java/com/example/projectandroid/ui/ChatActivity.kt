package com.example.projectandroid.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.example.projectandroid.R
import com.example.projectandroid.model.Message
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.example.projectandroid.util.AppLogger
import com.example.projectandroid.util.ErrorLogger
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.ktx.storage

class ChatActivity : AppCompatActivity() {

  private lateinit var recyclerView: RecyclerView
  private lateinit var adapter: ChatAdapter
  private lateinit var messageInput: TextInputEditText
  private lateinit var sendButton: MaterialButton
  private lateinit var listenerRegistration: ListenerRegistration

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val auth = Firebase.auth
    val currentUser = auth.currentUser
    if (currentUser == null) {
      startActivity(Intent(this, LoginActivity::class.java))
      finish()
      return
    }

    val recipientUid = intent.getStringExtra("recipientUid")
    val recipientName = intent.getStringExtra("recipientName")
    if (recipientUid.isNullOrBlank() || recipientName.isNullOrBlank()) {
      finish()
      return
    }

    initChat(currentUser.uid, recipientUid, recipientName)
  }

  private val pickImageLauncher = registerForActivityResult(
    ActivityResultContracts.GetContent()
  ) { uri: Uri? ->
    val currentUser = Firebase.auth.currentUser ?: return@registerForActivityResult
    val recipientUid = intent.getStringExtra("recipientUid") ?: return@registerForActivityResult
    val recipientName = intent.getStringExtra("recipientName") ?: return@registerForActivityResult
    val roomId = listOf(currentUser.uid, recipientUid).sorted().joinToString("_")
    if (uri != null) {
      sendImageMessage(roomId, currentUser.uid, recipientUid, recipientName, uri)
    }
  }

  private fun initChat(currentUid: String, recipientUid: String, recipientName: String) {
    setContentView(R.layout.activity_chat)

    val sendImageButton = findViewById<MaterialButton>(R.id.buttonSendImage)
    sendImageButton.setOnClickListener {
      pickImageLauncher.launch("image/*")
    }


    val toolbar = findViewById<MaterialToolbar>(R.id.topAppBar)
    setSupportActionBar(toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    supportActionBar?.title = recipientName

    recyclerView = findViewById(R.id.recyclerView)
    messageInput = findViewById(R.id.editMessage)
    sendButton = findViewById(R.id.buttonSend)

    adapter = ChatAdapter(currentUid)
    recyclerView.layoutManager = LinearLayoutManager(this).apply {
      stackFromEnd = true
    }
    recyclerView.adapter = adapter


    val roomId = listOf(currentUid, recipientUid).sorted().joinToString("_")
    val ref = Firebase.firestore
      .collection("rooms")
      .document(roomId)
      .collection("messages")

    listenerRegistration =
      ref.orderBy("createdAt").addSnapshotListener { value, error ->
        if (error != null) {
          AppLogger.logError(this, error)
          return@addSnapshotListener
        }
        val messages = value?.documents?.mapNotNull { it.toObject(Message::class.java) } ?: return@addSnapshotListener
        adapter.submitList(messages)
        if (adapter.itemCount > 0) recyclerView.smoothScrollToPosition(adapter.itemCount - 1)
      }

    sendButton.setOnClickListener {
      val text = messageInput.text.toString().trim()
      if (text.isEmpty()) return@setOnClickListener

      val data = mapOf(
        "senderId" to currentUid,
        "senderName" to (Firebase.auth.currentUser?.displayName ?: ""),
        "text" to text,
        "createdAt" to FieldValue.serverTimestamp(),
      )

      // Agregar el mensaje a Firestore
      ref.add(data).addOnFailureListener { e -> ErrorLogger.log(this, e) }

      // Actualizar el resumen del chat
      val roomData = mapOf(
        "participantIds" to listOf(currentUid, recipientUid),
        "userNames" to mapOf(
          currentUid to (Firebase.auth.currentUser?.displayName ?: ""),
          recipientUid to recipientName
        ),
        "lastMessage" to text,
        "updatedAt" to FieldValue.serverTimestamp()
      )
      Firebase.firestore.collection("rooms").document(roomId)
        .set(roomData, SetOptions.merge())

      messageInput.text?.clear()
    }


    /*sendButton.setOnClickListener {
      val text = messageInput.text.toString().trim()
      if (text.isEmpty()) return@setOnClickListener

      val data = mapOf(
        "senderId" to currentUid,
        "senderName" to (Firebase.auth.currentUser?.displayName ?: ""),
        "text" to text,
        "createdAt" to FieldValue.serverTimestamp(),
      )
      ref.add(data).addOnFailureListener { e -> AppLogger.logError(this, e) }
      ref.add(data).addOnFailureListener { e -> ErrorLogger.log(this, e) }

      //nuevo

      val roomData = mapOf(
        "participantIds" to listOf(currentUid, recipientUid),
        "userNames" to mapOf(
          currentUid to (Firebase.auth.currentUser?.displayName ?: ""),
          recipientUid to recipientName
        ),
        "lastMessage" to text,
        "updatedAt" to FieldValue.serverTimestamp()
      )
      Firebase.firestore.collection("rooms").document(roomId)
        .set(roomData, com.google.firebase.firestore.SetOptions.merge())

      messageInput.text?.clear()
    }*/
  }

  override fun onDestroy() {
    if (::listenerRegistration.isInitialized) {
      listenerRegistration.remove()
    }
    super.onDestroy()
  }

  override fun onSupportNavigateUp(): Boolean {
    finish()
    return true
  }

  private fun sendImageMessage(roomId: String, currentUid: String, recipientUid: String, recipientName: String, imageUri: Uri) {
    val storageRef = Firebase.storage.reference
      .child("chat_images/$roomId/${System.currentTimeMillis()}.jpg")

    storageRef.putFile(imageUri)
      .addOnSuccessListener {
        storageRef.downloadUrl.addOnSuccessListener { downloadUrl ->
          val data = mapOf(
            "senderId" to currentUid,
            "senderName" to (Firebase.auth.currentUser?.displayName ?: ""),
            "imageUrl" to downloadUrl.toString(),
            "createdAt" to FieldValue.serverTimestamp()
          )

          Firebase.firestore.collection("rooms")
            .document(roomId)
            .collection("messages")
            .add(data)

          val roomData = mapOf(
            "participantIds" to listOf(currentUid, recipientUid),
            "userNames" to mapOf(
              currentUid to (Firebase.auth.currentUser?.displayName ?: ""),
              recipientUid to recipientName
            ),
            "lastMessage" to "Imagen",
            "updatedAt" to FieldValue.serverTimestamp()
          )

          Firebase.firestore.collection("rooms").document(roomId)
            .set(roomData, SetOptions.merge())
        }
      }
      .addOnFailureListener { e -> ErrorLogger.log(this, e) }
  }

}
