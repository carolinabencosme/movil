package com.example.texty.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.texty.R
import com.example.texty.model.MessageBody
import com.example.texty.model.SessionKeyInfo
import com.example.texty.repository.FriendRequestRepository
import com.example.texty.repository.MessageMapper
import com.example.texty.repository.SessionKeyRepository
import com.example.texty.util.AppLogger
import com.example.texty.util.AttachmentCrypto
import com.example.texty.util.ErrorLogger
import com.example.texty.util.MessageCrypto
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.UUID

class ChatActivity : AppCompatActivity() {

  private lateinit var recyclerView: RecyclerView
  private lateinit var adapter: ChatAdapter
  private lateinit var messageInput: EditText
  private lateinit var sendButton: Button
  private lateinit var listenerRegistration: ListenerRegistration
  private lateinit var roomRef: DocumentReference
  private lateinit var messagesRef: CollectionReference

  private var isGroup: Boolean = false
  private var roomId: String? = null
  private var recipientUid: String? = null
  private var recipientName: String? = null
  private var groupName: String? = null
  private var participantIds: List<String> = emptyList()

  private val sessionKeyRepository = SessionKeyRepository()
  private var sessionKeyInfo: SessionKeyInfo? = null
  private var messageMapper: MessageMapper? = null

  private val pickImageLauncher = registerForActivityResult(
    ActivityResultContracts.GetContent()
  ) { uri: Uri? ->
    val currentUser = Firebase.auth.currentUser ?: return@registerForActivityResult
    val activeRoomId = roomId ?: return@registerForActivityResult
    if (uri != null) {
      val targetRecipientUid = recipientUid
      val targetRecipientName = recipientName
      sendImageMessage(
        roomId = activeRoomId,
        currentUid = currentUser.uid,
        imageUri = uri,
        isGroup = isGroup,
        recipientUid = targetRecipientUid,
        recipientName = targetRecipientName
      )
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

  private fun initChat(currentUid: String, recipientUid: String?, title: String, isGroupChat: Boolean) {
    setContentView(R.layout.activity_chat)

    val sendImageButton = findViewById<MaterialButton>(R.id.buttonSendImage)
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

    val resolvedRoomId = if (isGroupChat) {
      roomId ?: throw IllegalStateException("roomId must be set for group chats")
    } else {
      listOf(currentUid, recipientUid!!).sorted().joinToString("_")
    }
    roomId = resolvedRoomId
    roomRef = Firebase.firestore.collection("rooms").document(resolvedRoomId)
    messagesRef = roomRef.collection("messages")

    sendButton.isEnabled = false
    sendImageButton.isEnabled = false

    sendImageButton.setOnClickListener { pickImageLauncher.launch("image/*") }

    lifecycleScope.launch {
      participantIds = resolveParticipantIds(
        isGroupChat = isGroupChat,
        currentUid = currentUid,
        peerUid = recipientUid,
      )

      purgeLegacyLastMessageField()

      val sessionInfo = loadSessionInfo(
        roomId = resolvedRoomId,
        ownerUid = currentUid,
        isGroup = isGroupChat,
        peerUid = recipientUid
      )
      sessionKeyInfo = sessionInfo
      messageMapper = MessageMapper(sessionInfo)

      if (sessionInfo?.requiresReauth == true) {
        Toast.makeText(this@ChatActivity, R.string.chat_session_requires_resync, Toast.LENGTH_LONG).show()
      }

      sendButton.isEnabled = true
      sendImageButton.isEnabled = true

      try {
        roomRef.update("unreadCounts.$currentUid", 0).await()
      } catch (_: Exception) {
        // Ignore failures when resetting unread counts.
      }

      startMessageListener(currentUid, resolvedRoomId)
    }

    sendButton.setOnClickListener {
      val text = messageInput.text.toString().trim()
      if (text.isEmpty()) return@setOnClickListener

      lifecycleScope.launch {
        sendEncryptedMessage(
          body = MessageBody(text = text),
          messageType = MESSAGE_TYPE_TEXT,
          preview = text,
          currentUid = currentUid,
          isGroupChat = isGroupChat,
          recipientUid = recipientUid,
          title = title,
        )
      }
    }
  }

  private suspend fun loadSessionInfo(
    roomId: String,
    ownerUid: String,
    isGroup: Boolean,
    peerUid: String?,
  ): SessionKeyInfo? {
    return try {
      sessionKeyRepository.loadSessionKey(
        roomId = roomId,
        ownerUid = ownerUid,
        isGroup = isGroup,
        peerUid = peerUid,
      )
    } catch (error: Exception) {
      AppLogger.logError(this, error)
      ErrorLogger.log(this, error)
      null
    }
  }

  private fun startMessageListener(currentUid: String, resolvedRoomId: String) {
    if (::listenerRegistration.isInitialized) {
      listenerRegistration.remove()
    }

    listenerRegistration =
      messagesRef.orderBy("createdAt").addSnapshotListener { value, error ->
        if (error != null) {
          AppLogger.logError(this, error)
          return@addSnapshotListener
        }

        val mapper = messageMapper ?: return@addSnapshotListener
        val documents = value?.documents ?: return@addSnapshotListener
        val mapped = documents.mapNotNull { mapper.map(it) }

        adapter.submitList(mapped.map { it.message })
        if (adapter.itemCount > 0) {
          recyclerView.post {
            recyclerView.smoothScrollToPosition(adapter.itemCount - 1)
          }
        }

        lifecycleScope.launch(Dispatchers.IO) {
          updateReadReceipts(mapped, currentUid)
        }
      }
  }

  private suspend fun updateReadReceipts(
    documents: List<MessageMapper.MessageDocument>,
    currentUid: String,
  ) {
    val mapper = messageMapper ?: return
    val unread = documents.filter { !it.message.readBy.contains(currentUid) }
    if (unread.isEmpty()) return

    val batch = Firebase.firestore.batch()
    var operations = 0
    unread.forEach { document ->
      val update = mapper.buildReadReceiptUpdate(document, currentUid)
      if (update != null) {
        batch.set(document.snapshot.reference, update.first, update.second)
        operations++
      }
    }

    if (operations == 0) return

    batch.update(roomRef, mapOf("unreadCounts.$currentUid" to 0))

    try {
      batch.commit().await()
    } catch (error: Exception) {
      AppLogger.logError(this, error)
      ErrorLogger.log(this, error)
    }
  }

  private suspend fun sendEncryptedMessage(
    body: MessageBody,
    messageType: String,
    preview: String,
    currentUid: String,
    isGroupChat: Boolean,
    recipientUid: String?,
    title: String,
  ) {
    val sessionInfo = sessionKeyInfo
    if (sessionInfo?.rootKey == null) {
      Toast.makeText(this, R.string.chat_session_missing_keys, Toast.LENGTH_LONG).show()
      return
    }

    val metadata = MessageCrypto.EncryptionMetadata(
      senderId = currentUid,
      messageType = messageType,
      readBy = listOf(currentUid),
      schemeVersion = sessionInfo.schemeVersion,
      encryptionTarget = sessionInfo.encryptionTarget,
    )

    val encryptionResult = try {
      withContext(Dispatchers.Default) {
        MessageCrypto.encrypt(sessionInfo, body, metadata)
      }
    } catch (error: Exception) {
      AppLogger.logError(this, error)
      ErrorLogger.log(this, error)
      Toast.makeText(this, R.string.chat_message_encrypt_error, Toast.LENGTH_SHORT).show()
      return
    }

    val messageData = hashMapOf<String, Any>(
      "senderId" to currentUid,
      "senderName" to (Firebase.auth.currentUser?.displayName ?: ""),
      "ciphertext" to encryptionResult.payload.ciphertext,
      "nonce" to encryptionResult.payload.nonce,
      "salt" to encryptionResult.payload.salt,
      "schemeVersion" to encryptionResult.payload.schemeVersion,
      "encryptionTarget" to encryptionResult.payload.encryptionTarget,
      "messageType" to messageType,
      "createdAt" to FieldValue.serverTimestamp(),
      "readBy" to encryptionResult.readBy,
    )

    val sanitizedPreview = preview.take(SUMMARY_MAX_LENGTH)

    val roomData = if (isGroupChat) {
      mutableMapOf<String, Any>(
        "updatedAt" to FieldValue.serverTimestamp(),
      )
    } else {
      val peerUid = recipientUid ?: return
      mutableMapOf<String, Any>(
        "participantIds" to listOf(currentUid, peerUid),
        "userNames" to mapOf(
          currentUid to (Firebase.auth.currentUser?.displayName ?: ""),
          peerUid to title,
        ),
        "updatedAt" to FieldValue.serverTimestamp(),
      )
    }

    roomData["lastMessage"] = FieldValue.delete()

    try {
      withContext(Dispatchers.IO) {
        roomRef.set(roomData, SetOptions.merge()).await()
        messagesRef.add(messageData).await()
      }
    } catch (error: Exception) {
      AppLogger.logError(this, error)
      ErrorLogger.log(this, error)
      Toast.makeText(this, R.string.chat_message_send_error, Toast.LENGTH_SHORT).show()
      return
    }

    val summaryParticipants = if (participantIds.isNotEmpty()) {
      participantIds
    } else {
      listOfNotNull(currentUid, recipientUid)
    }

    if (sanitizedPreview.isNotEmpty()) {
      try {
        updateEncryptedSummaries(
          participants = summaryParticipants,
          sessionInfo = sessionInfo,
          previewText = sanitizedPreview,
          senderId = currentUid,
          messageType = messageType,
        )
      } catch (error: Exception) {
        AppLogger.logError(this, error)
        ErrorLogger.log(this, error)
      }
    }

    if (messageType == MESSAGE_TYPE_TEXT) {
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
    val sessionInfo = sessionKeyInfo
    if (sessionInfo?.rootKey == null) {
      Toast.makeText(this, R.string.chat_session_missing_keys, Toast.LENGTH_LONG).show()
      return
    }

    lifecycleScope.launch {
      val mimeType = contentResolver.getType(imageUri) ?: MESSAGE_TYPE_IMAGE
      val placeholder = getString(R.string.chat_message_image_preview)
      val storagePath = "chat_attachments/$roomId/${System.currentTimeMillis()}-${UUID.randomUUID()}.bin"

      val encryptedAttachment = try {
        withContext(Dispatchers.IO) {
          val inputStream = contentResolver.openInputStream(imageUri)
          val plainBytes = inputStream?.use { it.readBytes() }
          if (plainBytes == null) {
            null
          } else {
            try {
              AttachmentCrypto.encryptAttachment(sessionInfo, plainBytes, storagePath, mimeType)
            } finally {
              plainBytes.fill(0)
            }
          }
        }
      } catch (error: Exception) {
        AppLogger.logError(this@ChatActivity, error)
        ErrorLogger.log(this@ChatActivity, error)
        Toast.makeText(this@ChatActivity, R.string.chat_message_send_error, Toast.LENGTH_SHORT).show()
        return@launch
      }

      if (encryptedAttachment == null) {
        Toast.makeText(this@ChatActivity, R.string.chat_message_send_error, Toast.LENGTH_SHORT).show()
        return@launch
      }

      try {
        withContext(Dispatchers.IO) {
          Firebase.storage.reference
            .child(encryptedAttachment.storagePath)
            .putBytes(encryptedAttachment.ciphertext)
            .await()
        }
      } catch (error: Exception) {
        AppLogger.logError(this@ChatActivity, error)
        ErrorLogger.log(this@ChatActivity, error)
        Toast.makeText(this@ChatActivity, R.string.chat_message_send_error, Toast.LENGTH_SHORT).show()
        return@launch
      } finally {
        encryptedAttachment.clearCiphertext()
      }

      val body = MessageBody(
        attachmentMimeType = mimeType,
        attachmentStoragePath = encryptedAttachment.storagePath,
        attachmentNonce = encryptedAttachment.nonceBase64,
        attachmentMac = encryptedAttachment.macBase64,
        attachmentSalt = encryptedAttachment.saltBase64,
        attachmentSize = encryptedAttachment.ciphertextSize.toLong(),
      )

      sendEncryptedMessage(
        body = body,
        messageType = MESSAGE_TYPE_IMAGE,
        preview = placeholder,
        currentUid = currentUid,
        isGroupChat = isGroup,
        recipientUid = recipientUid,
        title = recipientName ?: (groupName ?: placeholder),
      )
    }
  }

  private suspend fun resolveParticipantIds(
    isGroupChat: Boolean,
    currentUid: String,
    peerUid: String?,
  ): List<String> {
    if (!isGroupChat) {
      return listOfNotNull(currentUid, peerUid).distinct()
    }

    return try {
      val snapshot = withContext(Dispatchers.IO) { roomRef.get().await() }
      val ids = (snapshot.get("participantIds") as? List<*>)
        ?.mapNotNull { it as? String }
        ?.filter { it.isNotBlank() }
        ?.distinct()
        .orEmpty()
      if (ids.isEmpty()) listOf(currentUid) else ids
    } catch (error: Exception) {
      AppLogger.logError(this, error)
      ErrorLogger.log(this, error)
      listOf(currentUid)
    }
  }

  private suspend fun purgeLegacyLastMessageField() {
    try {
      withContext(Dispatchers.IO) {
        roomRef.set(mapOf("lastMessage" to FieldValue.delete()), SetOptions.merge()).await()
      }
    } catch (_: Exception) {
      // Cleanup is best-effort; failures will be retried on the next sync.
    }
  }

  private suspend fun updateEncryptedSummaries(
    participants: List<String>,
    sessionInfo: SessionKeyInfo,
    previewText: String,
    senderId: String,
    messageType: String,
  ) {
    val distinctParticipants = participants.filter { it.isNotBlank() }.distinct()
    if (distinctParticipants.isEmpty()) return

    val encryptedSummaries = withContext(Dispatchers.Default) {
      distinctParticipants.map { uid ->
        val metadata = MessageCrypto.EncryptionMetadata(
          senderId = senderId,
          messageType = SUMMARY_MESSAGE_PREFIX + messageType,
          readBy = listOf(uid),
          schemeVersion = sessionInfo.schemeVersion,
          encryptionTarget = sessionInfo.encryptionTarget,
        )
        val result = MessageCrypto.encrypt(sessionInfo, MessageBody(text = previewText), metadata)
        SummaryEncryption(uid = uid, metadata = metadata, result = result)
      }
    }

    withContext(Dispatchers.IO) {
      encryptedSummaries.forEach { summary ->
        val payload = summary.result.payload
        val data = hashMapOf<String, Any>(
          "summaryCiphertext" to payload.ciphertext,
          "summaryNonce" to payload.nonce,
          "summarySalt" to payload.salt,
          "summarySchemeVersion" to payload.schemeVersion,
          "summaryEncryptionTarget" to payload.encryptionTarget,
          "summaryMessageType" to summary.metadata.messageType,
          "summarySenderId" to summary.metadata.senderId,
          "summaryReadBy" to summary.metadata.readBy,
          "updatedAt" to FieldValue.serverTimestamp(),
        )
        roomRef.collection("userState")
          .document(summary.uid)
          .set(data, SetOptions.merge())
          .await()
      }
    }
  }

  private data class SummaryEncryption(
    val uid: String,
    val metadata: MessageCrypto.EncryptionMetadata,
    val result: MessageCrypto.EncryptionResult,
  )

  companion object {
    private const val MESSAGE_TYPE_TEXT = "text/plain"
    private const val MESSAGE_TYPE_IMAGE = "media/image"
    private const val SUMMARY_MAX_LENGTH = 140
    private const val SUMMARY_MESSAGE_PREFIX = "summary:"
  }
}
