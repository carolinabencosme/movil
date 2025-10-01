package com.example.texty.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.collection.LruCache
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.texty.R
import com.example.texty.NotificationCounter
import com.example.texty.model.MessageBody
import com.example.texty.model.SessionKeyInfo
import com.example.texty.model.User
import com.example.texty.repository.ChatRoomRepository
import com.example.texty.repository.FriendRequestRepository
import com.example.texty.repository.MessageMapper
import com.example.texty.repository.SessionKeyRepository
import com.example.texty.repository.UserRepository
import com.example.texty.util.AppLogger
import com.example.texty.util.AttachmentCrypto
import com.example.texty.util.ErrorLogger
import com.example.texty.util.MessageCrypto
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.*
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.text.DateFormat
import java.util.Date
import java.util.UUID

class ChatActivity : AppCompatActivity() {

  private lateinit var recyclerView: RecyclerView
  private lateinit var adapter: ChatAdapter
  private lateinit var messageInput: EditText
  private lateinit var sendButton: Button
  private lateinit var messageBanner: View
  private lateinit var messageBannerTitle: TextView
  private lateinit var messageBannerSubtitle: TextView
  private lateinit var messageBannerIcon: ImageView
  private lateinit var listenerRegistration: ListenerRegistration
  private lateinit var roomRef: DocumentReference
  private lateinit var messagesRef: CollectionReference

  private var isGroup: Boolean = false
  private var roomId: String? = null
  private var recipientUid: String? = null
  private var recipientName: String? = null
  private var groupName: String? = null
  private var participantIds: List<String> = emptyList()
  private var canManageMembers: Boolean = false
  private var triedAutoResync = false
  private val imageCache = object : LruCache<String, Bitmap>(20) {}
  private val MAX_DOWNLOAD_BYTES = 10L * 1024 * 1024 // 10 MB


  private val sessionKeyRepository = SessionKeyRepository()
  private val chatRoomRepository = ChatRoomRepository()
  private var sessionKeyInfo: SessionKeyInfo? = null
  private var messageMapper: MessageMapper? = null
  private val bannerHandler = Handler(Looper.getMainLooper())
  private var bannerHideRunnable: Runnable? = null

  private val userNameCache = mutableMapOf<String, String>()
  private var roomInfoRegistration: ListenerRegistration? = null


  private val pickImageLauncher = registerForActivityResult(
    ActivityResultContracts.GetContent()
  ) { uri: Uri? ->
    val currentUser = Firebase.auth.currentUser ?: return@registerForActivityResult
    val activeRoomId = roomId ?: return@registerForActivityResult
    if (uri != null) {
      sendImageMessage(
        roomId = activeRoomId,
        currentUid = currentUser.uid,
        imageUri = uri,
        isGroup = isGroup,
        recipientUid = recipientUid,
        recipientName = recipientName
      )
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val currentUser = Firebase.auth.currentUser
    if (currentUser == null) {
      startActivity(Intent(this, LoginActivity::class.java))
      finish()
      return
    }

    isGroup = intent.getBooleanExtra("isGroup", false)

    if (isGroup) {
      roomId = intent.getStringExtra("roomId")
      groupName = intent.getStringExtra("groupName")
      if (roomId.isNullOrBlank()) {
        val e = IllegalArgumentException("roomId extra is missing or blank")
        AppLogger.logError(this, e); ErrorLogger.log(this, e)
        finish(); return
      }
      initChat(currentUser.uid, null, groupName ?: getString(R.string.chat_group_default_name), true)
    } else {
      recipientUid = intent.getStringExtra("recipientUid")
      recipientName = intent.getStringExtra("recipientName")
      if (recipientUid.isNullOrBlank() || recipientName.isNullOrBlank()) {
        val e = IllegalArgumentException("recipientUid o recipientName extra faltante")
        AppLogger.logError(this, e); ErrorLogger.log(this, e)
        finish(); return
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

  private fun initChat(currentUid: String, peerUid: String?, title: String, isGroupChat: Boolean) {
    setContentView(R.layout.activity_chat)

    val sendImageButton = findViewById<MaterialButton>(R.id.buttonSendImage)
    sendImageButton.contentDescription = getString(R.string.chat_add_image)
    val toolbar = findViewById<MaterialToolbar>(R.id.topAppBar)
    setSupportActionBar(toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    supportActionBar?.title = title
    invalidateOptionsMenu()

    messageBanner = findViewById(R.id.messageBanner)
    messageBannerTitle = messageBanner.findViewById(R.id.messageBannerTitle)
    messageBannerSubtitle = messageBanner.findViewById(R.id.messageBannerSubtitle)
    messageBannerIcon = messageBanner.findViewById(R.id.messageBannerIcon)

    recyclerView = findViewById(R.id.recyclerView)
    messageInput = findViewById(R.id.editMessage)
    sendButton = findViewById(R.id.buttonSend)
    sendButton.setText(R.string.chat_action_send)

    //adapter = ChatAdapter(currentUid)
   /* adapter = ChatAdapter(currentUid) { msg, iv, tv ->
      bindAttachment(msg, iv, tv)
    }*/

    adapter = ChatAdapter(
      myUid = currentUid,
      onBindAttachment = { msg, iv, tv -> bindAttachment(msg, iv, tv) },
      isGroupChat = isGroupChat,
      resolveSenderName = { uid -> userNameCache[uid] } // fallback si no trae senderName
    )

    recyclerView.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
    recyclerView.adapter = adapter

    val resolvedRoomId = if (isGroupChat) {
      roomId ?: error("roomId must be set for group chats")
    } else {
      listOf(currentUid, peerUid!!).sorted().joinToString("_")
    }
    // ---- set refs UNA sola vez ----
    roomId = resolvedRoomId
    NotificationCounter.getInstance(applicationContext).clear(resolvedRoomId)
    NotificationManagerCompat.from(this).cancel(resolvedRoomId.hashCode())
    roomRef = Firebase.firestore.collection("rooms").document(resolvedRoomId)
    messagesRef = roomRef.collection("messages")
    roomInfoRegistration?.remove()
    roomInfoRegistration = roomRef.addSnapshotListener { snap, _ ->
      if (snap == null || !snap.exists()) return@addSnapshotListener

      val names = (snap.get("userNames") as? Map<*, *>)
        ?.mapNotNull { (k, v) -> if (k is String && v is String) k to v else null }
        ?.toMap()
        ?: emptyMap()
      userNameCache.clear()
      userNameCache.putAll(names)

      val ids = (snap.get("participantIds") as? List<*>)
        ?.mapNotNull { it as? String }
        ?.filter { it.isNotBlank() }
        ?.distinct()
        ?: emptyList()
      participantIds = ids

      val currentUid = Firebase.auth.currentUser?.uid
      if (currentUid != null) {
        val adminIds = (snap.get("adminIds") as? List<*>)
          ?.mapNotNull { it as? String }
          ?.filter { it.isNotBlank() }
          ?.distinct()
          ?: emptyList()
        val creatorUid = snap.getString("creatorUid")
        val canManageNow = adminIds.contains(currentUid) || (!creatorUid.isNullOrBlank() && creatorUid == currentUid) || (adminIds.isEmpty() && creatorUid.isNullOrBlank() && ids.firstOrNull() == currentUid)
        if (canManageNow != canManageMembers) {
          canManageMembers = canManageNow
          invalidateOptionsMenu()
        }
      }
    }

    triedAutoResync = false
    // --------------------------------

    // Deshabilita UI hasta tener sesión
    sendButton.isEnabled = false
    sendImageButton.isEnabled = false
    sendImageButton.setOnClickListener { pickImageLauncher.launch("image/*") }

    lifecycleScope.launch {
      participantIds = resolveParticipantIds(isGroupChat, currentUid, peerUid)
      purgeLegacyLastMessageField()

      // Asegura sesión válida
      val sessionInfo = if (isGroupChat) {
        loadSessionInfo(resolvedRoomId, currentUid, true, null)
      } else {
        ensureSessionForDirectChat(currentUid, peerUid!!)
      }
      sessionKeyInfo = sessionInfo
      messageMapper = MessageMapper(sessionInfo)

      if (sessionInfo?.requiresReauth == true) {
        Toast.makeText(this@ChatActivity, R.string.chat_session_requires_resync, Toast.LENGTH_LONG).show()
      }

      sendButton.isEnabled = true
      sendImageButton.isEnabled = true

      try { roomRef.update("unreadCounts.$currentUid", 0).await() } catch (_: Exception) {}

      // Arranca el listener después de tener sessionKeyInfo y messageMapper
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
          recipientUid = peerUid,
          title = title,
        )
      }
    }
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    if (isGroup) {
      menuInflater.inflate(R.menu.menu_chat_group, menu)
      return true
    }
    return super.onCreateOptionsMenu(menu)
  }

  override fun onPrepareOptionsMenu(menu: Menu): Boolean {
    if (isGroup) {
      menu.findItem(R.id.action_add_members)?.isVisible = canManageMembers
    }
    return super.onPrepareOptionsMenu(menu)
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    return when (item.itemId) {
      android.R.id.home -> {
        finish()
        true
      }
      R.id.action_add_members -> {
        if (canManageMembers) {
          showAddMembersDialog()
        }
        true
      }
      R.id.action_leave_group -> {
        showLeaveGroupConfirmation()
        true
      }
      else -> super.onOptionsItemSelected(item)
    }
  }

  private fun showLeaveGroupConfirmation() {
    val resolvedGroupName = groupName ?: getString(R.string.chat_group_default_name)
    MaterialAlertDialogBuilder(this)
      .setTitle(R.string.chat_leave_group_title)
      .setMessage(getString(R.string.chat_leave_group_message, resolvedGroupName))
      .setPositiveButton(R.string.chat_leave_group_confirm) { _, _ ->
        performLeaveGroup()
      }
      .setNegativeButton(R.string.cancel, null)
      .show()
  }

  private fun performLeaveGroup() {
    val currentUser = Firebase.auth.currentUser ?: return
    val activeRoomId = roomId ?: return

    chatRoomRepository.leaveGroup(
      context = this,
      roomId = activeRoomId,
      leaverUid = currentUser.uid,
      leaverDisplayName = currentUser.displayName ?: currentUser.uid,
      onSuccess = {
        runOnUiThread {
          Toast.makeText(this, R.string.chat_leave_group_success, Toast.LENGTH_SHORT).show()
          finish()
        }
      },
      onFailure = { error ->
        AppLogger.logError(this, error); ErrorLogger.log(this, error)
        runOnUiThread {
          Toast.makeText(this, R.string.chat_leave_group_error, Toast.LENGTH_LONG).show()
        }
      }
    )
  }

  private fun showAddMembersDialog() {
    val currentUser = Firebase.auth.currentUser ?: return
    val activeRoomId = roomId ?: return

    val existingParticipants = participantIds.toMutableSet()
    existingParticipants.add(currentUser.uid)

    UserRepository().getFriends(
      currentUser.uid,
      onSuccess = { friends ->
        val available = friends
          .filter { it.uid.isNotBlank() && !existingParticipants.contains(it.uid) }
          .sortedBy { it.displayName.ifBlank { it.uid }.lowercase() }

        runOnUiThread {
          if (available.isEmpty()) {
            Toast.makeText(this, R.string.chat_add_members_empty, Toast.LENGTH_SHORT).show()
            return@runOnUiThread
          }

          val dialogView = layoutInflater.inflate(R.layout.dialog_create_group, null)
          val recycler = dialogView.findViewById<RecyclerView>(R.id.recyclerFriends)
          val groupNameLayout = dialogView.findViewById<TextInputLayout>(R.id.groupNameLayout)
          val groupNameInput = dialogView.findViewById<TextInputEditText>(R.id.editGroupName)
          val searchInput = dialogView.findViewById<TextInputEditText>(R.id.editSearchFriends)

          groupNameLayout.isVisible = false
          groupNameInput.isVisible = false

          recycler.layoutManager = LinearLayoutManager(this)

          val selected = mutableSetOf<String>()

          val adapter = object : RecyclerView.Adapter<FriendViewHolder>() {
            private var filtered = available
            var onSelectionChanged: ((Boolean) -> Unit)? = null

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FriendViewHolder {
              val view = layoutInflater.inflate(R.layout.item_friend_checkbox, parent, false)
              return FriendViewHolder(view)
            }

            override fun getItemCount(): Int = filtered.size

            override fun onBindViewHolder(holder: FriendViewHolder, position: Int) {
              val friend = filtered[position]
              holder.checkBox.text = friend.displayName.ifBlank { friend.uid }
              holder.checkBox.setOnCheckedChangeListener(null)
              holder.checkBox.isChecked = selected.contains(friend.uid)
              holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selected.add(friend.uid) else selected.remove(friend.uid)
                onSelectionChanged?.invoke(selected.isNotEmpty())
              }
            }

            fun filter(query: String) {
              val lower = query.lowercase()
              filtered = if (lower.isBlank()) {
                available
              } else {
                available.filter {
                  it.displayName.contains(query, ignoreCase = true) || it.uid.contains(query, ignoreCase = true)
                }
              }
              notifyDataSetChanged()
            }

            fun getSelectedUsers(): List<User> = available.filter { selected.contains(it.uid) }
          }

          recycler.adapter = adapter

          val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chat_add_members_title)
            .setView(dialogView)
            .setPositiveButton(R.string.chat_add_members_confirm, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

          dialog.setOnShowListener {
            val addButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            addButton.isEnabled = false

            adapter.onSelectionChanged = { hasSelection ->
              addButton.isEnabled = hasSelection
            }

            addButton.setOnClickListener {
              val selectedUsers = adapter.getSelectedUsers()
              if (selectedUsers.isEmpty()) {
                showStatusMessage(R.string.error_group_members_required)
                return@setOnClickListener
              }

              addButton.isEnabled = false

              chatRoomRepository.addMembers(
                context = this,
                roomId = activeRoomId,
                initiatorUid = currentUser.uid,
                initiatorDisplayName = currentUser.displayName ?: currentUser.uid,
                newMembers = selectedUsers,
                onSuccess = {
                  runOnUiThread {
                    showStatusMessage(R.string.chat_add_members_success)
                    dialog.dismiss()
                  }
                },
                onFailure = { error ->
                  AppLogger.logError(this, error)
                  ErrorLogger.log(this, error)
                  runOnUiThread {
                    showStatusMessage(R.string.chat_add_members_error, Toast.LENGTH_LONG)
                    addButton.isEnabled = true
                  }
                },
              )
            }
          }

          searchInput.addTextChangedListener { text ->
            adapter.filter(text?.toString().orEmpty())
          }

          dialog.show()
        }
      },
      onFailure = { error ->
        AppLogger.logError(this, error)
        ErrorLogger.log(this, error)
        runOnUiThread {
          showStatusMessage(R.string.chat_add_members_error, Toast.LENGTH_LONG)
        }
      },
    )
  }

  private fun showStatusMessage(@StringRes messageResId: Int, duration: Int = Toast.LENGTH_SHORT) {
    if (isFinishing || isDestroyed) return
    Toast.makeText(applicationContext, messageResId, duration).show()
  }


  private suspend fun ensureSessionForDirectChat(currentUid: String, peerUid: String): SessionKeyInfo? {
    val rid = roomId ?: listOf(currentUid, peerUid).sorted().joinToString("_")
    var info = loadSessionInfo(rid, currentUid, false, peerUid)

    if (info?.rootKey == null || info.requiresReauth) {
      try {
        refreshSessionAwait(currentUid, peerUid)
        info = loadSessionInfo(rid, currentUid, false, peerUid)
        if (info?.rootKey == null) {
          delay(250) // pequeño margen para que persista participants/{ownerUid}
          info = loadSessionInfo(rid, currentUid, false, peerUid)
        }
      } catch (e: Exception) {
        AppLogger.logError(this, e); ErrorLogger.log(this, e)
      }
    }
    return info
  }


  // Envuelve FriendRequestRepository.refreshSession en una función suspend.
  private suspend fun refreshSessionAwait(currentUid: String, peerUid: String) =
    suspendCancellableCoroutine<Unit> { cont ->
      FriendRequestRepository().refreshSession(
        requesterUid = currentUid,
        peerUid = peerUid,
        onSuccess = { if (cont.isActive) cont.resume(Unit) },
        onFailure = { e -> if (cont.isActive) cont.resumeWithException(e) }
      )
    }

  private suspend fun loadSessionInfo(
    roomId: String,
    ownerUid: String,
    isGroup: Boolean,
    peerUid: String?,
  ): SessionKeyInfo? = try {
    sessionKeyRepository.loadSessionKey(roomId, ownerUid, isGroup, peerUid)
  } catch (e: Exception) {
    AppLogger.logError(this, e); ErrorLogger.log(this, e); null
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
                val docs = value?.documents ?: return@addSnapshotListener
                val mapped = docs.mapNotNull { mapper.map(it) }

                adapter.submitList(mapped.map { it.message })
                if (adapter.itemCount > 0) {
                    recyclerView.post { recyclerView.smoothScrollToPosition(adapter.itemCount - 1) }
                }

                // === AUTO-RESYNC: si alguno pide resincronizar, intenta negociar sesión y remapear ===
                val needResync = mapped.any { it.message.requiresKeyResync }
                if (needResync && !isGroup && !triedAutoResync) {
                    triedAutoResync = true
                    val me = Firebase.auth.currentUser?.uid ?: return@addSnapshotListener
                    val peer = recipientUid ?: return@addSnapshotListener

                    lifecycleScope.launch {
                        val newInfo = ensureSessionForDirectChat(me, peer)
                        if (newInfo?.rootKey != null) {
                            sessionKeyInfo = newInfo
                            messageMapper = MessageMapper(newInfo)
                            // Remapea inmediatamente con la nueva sesión
                            val remapped = docs.mapNotNull { messageMapper?.map(it) }
                            adapter.submitList(remapped.map { it.message })
                            if (adapter.itemCount > 0) {
                                recyclerView.post { recyclerView.smoothScrollToPosition(adapter.itemCount - 1) }
                            }
                        } else {
                            Toast.makeText(
                                this@ChatActivity,
                                R.string.chat_session_requires_resync,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                // === FIN AUTO-RESYNC ===

                // Read receipts
                lifecycleScope.launch(Dispatchers.IO) { updateReadReceipts(mapped, currentUid) }
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
    var ops = 0
    unread.forEach { doc ->
      val update = mapper.buildReadReceiptUpdate(doc, currentUid)
      if (update != null) { batch.set(doc.snapshot.reference, update.first, update.second); ops++ }
    }
    if (ops == 0) return
    batch.update(roomRef, mapOf("unreadCounts.$currentUid" to 0))

    try { batch.commit().await() } catch (e: Exception) {
      AppLogger.logError(this, e); ErrorLogger.log(this, e)

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
      withContext(Dispatchers.Default) { MessageCrypto.encrypt(sessionInfo, body, metadata) }
    } catch (e: Exception) {
      AppLogger.logError(this, e); ErrorLogger.log(this, e)
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
    val roomData: MutableMap<String, Any> =
      if (isGroupChat) {
        mutableMapOf("updatedAt" to FieldValue.serverTimestamp())
      } else {
        val peerUid = recipientUid ?: return
        mutableMapOf(
          "participantIds" to listOf(currentUid, peerUid),
          "userNames" to mapOf(
            currentUid to (Firebase.auth.currentUser?.displayName ?: ""),
            peerUid to title
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
    } catch (e: Exception) {
      AppLogger.logError(this, e); ErrorLogger.log(this, e)
      Toast.makeText(this, R.string.chat_message_send_error, Toast.LENGTH_SHORT).show()
      return
    }

    val summaryParticipants = if (participantIds.isNotEmpty()) participantIds
    else listOfNotNull(currentUid, recipientUid)

    if (sanitizedPreview.isNotEmpty()) {
      try {
        updateEncryptedSummaries(
          participants = summaryParticipants,
          sessionInfo = sessionInfo,
          previewText = sanitizedPreview,
          senderId = currentUid,
          messageType = messageType,
        )
      } catch (e: Exception) {
        AppLogger.logError(this, e); ErrorLogger.log(this, e)
      }
    }

    showMessageBanner(title, sanitizedPreview, messageType)

    if (messageType == MESSAGE_TYPE_TEXT) messageInput.text?.clear()
  }

  override fun onDestroy() {
    if (::listenerRegistration.isInitialized) listenerRegistration.remove()
    roomInfoRegistration?.remove()
    roomInfoRegistration = null
    bannerHideRunnable?.let { bannerHandler.removeCallbacks(it) }
    bannerHideRunnable = null
    super.onDestroy()
  }

  override fun onSupportNavigateUp(): Boolean { finish(); return true }

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
          val plain = inputStream?.use { it.readBytes() }
          if (plain == null) null else try {
            AttachmentCrypto.encryptAttachment(sessionInfo, plain, storagePath, mimeType)
          } finally { plain?.fill(0) }
        }
      } catch (e: Exception) {
        AppLogger.logError(this@ChatActivity, e); ErrorLogger.log(this@ChatActivity, e)
        Toast.makeText(this@ChatActivity, R.string.chat_message_send_error, Toast.LENGTH_SHORT).show()
        return@launch
      } ?: run {
        Toast.makeText(this@ChatActivity, R.string.chat_message_send_error, Toast.LENGTH_SHORT).show()
        return@launch
      }

      try {
        withContext(Dispatchers.IO) {
          Firebase.storage.reference.child(encryptedAttachment.storagePath)
            .putBytes(encryptedAttachment.ciphertext).await()
        }
      } catch (e: Exception) {
        AppLogger.logError(this@ChatActivity, e); ErrorLogger.log(this@ChatActivity, e)
        Toast.makeText(this@ChatActivity, R.string.chat_message_send_error, Toast.LENGTH_SHORT).show()
        return@launch
      } finally { encryptedAttachment.clearCiphertext() }

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

  private fun showMessageBanner(title: String, preview: String, messageType: String) {
    if (!::messageBanner.isInitialized) return

    val displayPreview = if (preview.isNotBlank()) preview else getString(R.string.chat_banner_message_sent)
    val timeText = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
    messageBannerTitle.text = title
    messageBannerSubtitle.text = getString(R.string.chat_banner_preview_with_time, displayPreview, timeText)

    val iconRes = when (messageType) {
      MESSAGE_TYPE_IMAGE -> R.drawable.baseline_add_photo_alternate_24
      else -> R.drawable.ic_send
    }
    messageBannerIcon.setImageResource(iconRes)

    bannerHideRunnable?.let { bannerHandler.removeCallbacks(it) }
    messageBanner.animate().cancel()

    if (messageBanner.visibility != View.VISIBLE) {
      messageBanner.alpha = 0f
      messageBanner.visibility = View.VISIBLE
    }

    messageBanner.animate().alpha(1f).setDuration(200).start()

    val hideRunnable = Runnable {
      messageBanner.animate()
        .alpha(0f)
        .setDuration(200)
        .withEndAction {
          messageBanner.visibility = View.GONE
          messageBanner.alpha = 1f
        }
        .start()
      bannerHideRunnable = null
    }

    bannerHideRunnable = hideRunnable
    bannerHandler.postDelayed(hideRunnable, 3000)
  }

  private fun bindAttachment(
    message: com.example.texty.model.Message,
    imageView: ImageView,
    messageText: TextView
  ) {
    val body = message.decrypted?.body ?: return
    val mime = body.attachmentMimeType ?: return
    if (!mime.startsWith("image")) return

    val storagePath = body.attachmentStoragePath ?: return

    imageCache.get(storagePath)?.let { bmp ->
      imageView.setImageBitmap(bmp)
      imageView.visibility = View.VISIBLE
      messageText.visibility = View.GONE
      return
    }

    lifecycleScope.launch {
      try {
        val session = sessionKeyInfo ?: return@launch

        // 🔹 Construir metadatos y descifrar usando la API correcta
        val metadata = AttachmentCrypto.extractMetadata(body) ?: return@launch
        val plainBytes = withContext(Dispatchers.IO) {
          AttachmentCrypto.downloadAndDecryptAttachment(metadata, session)
        }

        val bmp = withContext(Dispatchers.Default) {
          BitmapFactory.decodeByteArray(plainBytes, 0, plainBytes.size)
        } ?: return@launch

        imageCache.put(storagePath, bmp)
        imageView.setImageBitmap(bmp)
        imageView.visibility = View.VISIBLE
        messageText.visibility = View.GONE
      } catch (e: Exception) {
        AppLogger.logError(this@ChatActivity, e)
        // Si falla, dejamos el placeholder de texto
      }
    }
  }

  private suspend fun resolveParticipantIds(
    isGroupChat: Boolean,
    currentUid: String,
    peerUid: String?,
  ): List<String> {
    if (!isGroupChat) return listOfNotNull(currentUid, peerUid).distinct()
    return try {
      val snap = withContext(Dispatchers.IO) { roomRef.get().await() }
      val ids = (snap.get("participantIds") as? List<*>)?.mapNotNull { it as? String }
        ?.filter { it.isNotBlank() }?.distinct().orEmpty()
      if (ids.isEmpty()) listOf(currentUid) else ids
    } catch (e: Exception) {
      AppLogger.logError(this, e); ErrorLogger.log(this, e); listOf(currentUid)
    }
  }

  private suspend fun purgeLegacyLastMessageField() {
    try {
      withContext(Dispatchers.IO) {
        roomRef.set(mapOf("lastMessage" to FieldValue.delete()), SetOptions.merge()).await()
      }
    } catch (_: Exception) {}
  }

  private suspend fun updateEncryptedSummaries(
    participants: List<String>,
    sessionInfo: SessionKeyInfo,
    previewText: String,
    senderId: String,
    messageType: String,
  ) {
    val distinct = participants.filter { it.isNotBlank() }.distinct()
    if (distinct.isEmpty()) return

    val encryptedSummaries = withContext(Dispatchers.Default) {
      distinct.map { uid ->
        val meta = MessageCrypto.EncryptionMetadata(
          senderId = senderId,
          messageType = SUMMARY_MESSAGE_PREFIX + messageType,
          readBy = listOf(uid),
          schemeVersion = sessionInfo.schemeVersion,
          encryptionTarget = sessionInfo.encryptionTarget,
        )
        val res = MessageCrypto.encrypt(sessionInfo, MessageBody(text = previewText), meta)
        SummaryEncryption(uid = uid, metadata = meta, result = res)
      }
    }

    withContext(Dispatchers.IO) {
      encryptedSummaries.forEach { s ->
        val p = s.result.payload
        val data = hashMapOf<String, Any>(
          "summaryCiphertext" to p.ciphertext,
          "summaryNonce" to p.nonce,
          "summarySalt" to p.salt,
          "summarySchemeVersion" to p.schemeVersion,
          "summaryEncryptionTarget" to p.encryptionTarget,
          "summaryMessageType" to s.metadata.messageType,
          "summarySenderId" to s.metadata.senderId,
          "summaryReadBy" to s.metadata.readBy,
          "updatedAt" to FieldValue.serverTimestamp(),
        )
        roomRef.collection("userState").document(s.uid)
          .set(data, SetOptions.merge()).await()
      }
    }
  }

  private data class SummaryEncryption(
    val uid: String,
    val metadata: MessageCrypto.EncryptionMetadata,
    val result: MessageCrypto.EncryptionResult,
  )

  private class FriendViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    val checkBox: CheckBox = view.findViewById(R.id.checkBoxFriend)
  }

  companion object {
    private const val MESSAGE_TYPE_TEXT = "text/plain"
    private const val MESSAGE_TYPE_IMAGE = "media/image"
    private const val SUMMARY_MAX_LENGTH = 140
    private const val SUMMARY_MESSAGE_PREFIX = "summary:"
  }
}
