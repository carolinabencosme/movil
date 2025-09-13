package com.example.texty.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.texty.R
import com.example.texty.model.Message
import com.example.texty.repository.FriendRequestRepository
import com.example.texty.util.AppLogger
import com.example.texty.util.ErrorLogger
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage

class ChatActivity : AppCompatActivity() {

  private lateinit var recyclerView: RecyclerView
  private lateinit var adapter: ChatAdapter
  private lateinit var messageInput: EditText
  private lateinit var sendButton: Button
  private lateinit var listenerRegistration: ListenerRegistration

  private var isGroup: Boolean = false
  private var roomId: String? = null
  private var recipientUid: String? = null
  private var recipientName: String? = null
  private var groupName: String? = null

  private val pickImageLauncher = registerForActivityResult(
    ActivityResultContracts.GetContent()
  ) { uri: Uri? ->
    val currentUser = Firebase.auth.currentUser ?: return@registerForActivityResult
    if (uri != null) {
      if (isGroup) {
        val id = roomId ?: return@registerForActivityResult
        sendImageMessage(id, currentUser.uid, uri, true)
      } else {
        val recipientUid = recipientUid ?: return@registerForActivityResult
        val recipientName = recipientName ?: return@registerForActivityResult
        val id = listOf(currentUser.uid, recipientUid).sorted().joinToString("_")
        sendImageMessage(id, currentUser.uid, uri, false, recipientUid, recipientName)
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val auth = Firebase.auth
    val currentUser = auth.currentUser
    if (currentUser == null) {
      startActivity(Intent(this, LoginActivity::class.java))
      finish()
      return
    }

    isGroup = intent.getBooleanExtra("isGroup", false)

    if (isGroup) {
      // Datos de grupo
      roomId = intent.getStringExtra("roomId")
      groupName = intent.getStringExtra("groupName")

      if (roomId.isNullOrBlank()) {
        val error = IllegalArgumentException("roomId extra is missing or blank")
        AppLogger.logError(this, error)
        ErrorLogger.log(this, error)
        finish()
        return
      }

      initChat(currentUser.uid, null, groupName ?: "Grupo sin nombre", true)

    } else {
      // Datos de chat privado
      recipientUid = intent.getStringExtra("recipientUid")
      recipientName = intent.getStringExtra("recipientName")

      if (recipientUid.isNullOrBlank() || recipientName.isNullOrBlank()) {
        val error = IllegalArgumentException("recipientUid o recipientName extra faltante")
        AppLogger.logError(this, error)
        ErrorLogger.log(this, error)
        finish()
        return
      }

      FriendRequestRepository().areFriends(currentUser.uid, recipientUid!!) { isFriend ->
        if (isFriend) {
          initChat(currentUser.uid, recipientUid!!, recipientName!!, false)
        } else {
          Toast.makeText(this, R.string.error_not_friends, Toast.LENGTH_SHORT).show()
          finish()
        }
      }
    }
  }

  private fun initChat(currentUid: String, recipientUid: String?, title: String, isGroup: Boolean) {
    setContentView(R.layout.activity_chat)

    val sendImageButton = findViewById<MaterialButton>(R.id.buttonSendImage)
    sendImageButton.setOnClickListener {
      pickImageLauncher.launch("image/*")
    }

    val toolbar = findViewById<MaterialToolbar>(R.id.topAppBar)
    setSupportActionBar(toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    supportActionBar?.title = title

    recyclerView = findViewById(R.id.recyclerView)
    messageInput = findViewById(R.id.editMessage)
    sendButton = findViewById(R.id.buttonSend)

    adapter = ChatAdapter(currentUid)
    recyclerView.layoutManager = LinearLayoutManager(this).apply {
      stackFromEnd = true
    }
    recyclerView.adapter = adapter

    val id = if (isGroup) {
      roomId!!
    } else {
      listOf(currentUid, recipientUid!!).sorted().joinToString("_")
    }

    val ref = Firebase.firestore
      .collection("rooms")
      .document(id)
      .collection("messages")

    listenerRegistration =
      ref.orderBy("createdAt").addSnapshotListener { value, error ->
        if (error != null) {
          AppLogger.logError(this, error)
          return@addSnapshotListener
        }
        val messages = value?.documents?.mapNotNull { doc ->
          doc.toObject(Message::class.java)?.copy(id = doc.id)
        } ?: return@addSnapshotListener
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

      ref.add(data).addOnFailureListener { e ->
        AppLogger.logError(this, e)
        ErrorLogger.log(this, e)
      }

      val roomData = if (isGroup) {
        mapOf(
          "lastMessage" to text,
          "updatedAt" to FieldValue.serverTimestamp()
        )
      } else {
        mapOf(
          "participantIds" to listOf(currentUid, recipientUid!!),
          "userNames" to mapOf(
            currentUid to (Firebase.auth.currentUser?.displayName ?: ""),
            recipientUid to title
          ),
          "lastMessage" to text,
          "updatedAt" to FieldValue.serverTimestamp()
        )
      }

      Firebase.firestore.collection("rooms").document(id)
        .set(roomData, SetOptions.merge())

      messageInput.text?.clear()
    }
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

  private fun sendImageMessage(
    roomId: String,
    currentUid: String,
    imageUri: Uri,
    isGroup: Boolean,
    recipientUid: String? = null,
    recipientName: String? = null
  ) {
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

          val roomData = if (isGroup) {
            mapOf(
              "lastMessage" to "📷 Imagen",
              "updatedAt" to FieldValue.serverTimestamp()
            )
          } else {
            mapOf(
              "participantIds" to listOf(currentUid, recipientUid!!),
              "userNames" to mapOf(
                currentUid to (Firebase.auth.currentUser?.displayName ?: ""),
                recipientUid to recipientName
              ),
              "lastMessage" to "Imagen",
              "updatedAt" to FieldValue.serverTimestamp()
            )
          }

          Firebase.firestore.collection("rooms").document(roomId)
            .set(roomData, SetOptions.merge())
        }
      }
      .addOnFailureListener { e -> ErrorLogger.log(this, e) }
  }
}
