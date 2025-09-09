package com.example.projectandroid.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.projectandroid.R
import com.example.projectandroid.model.ChatRoom
import com.example.projectandroid.util.ErrorLogger
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import java.util.Locale

class ChatListActivity : AppCompatActivity() {
    private lateinit var adapter: ChatListAdapter
    private lateinit var searchView: SearchView
    private var allRooms: List<ChatRoom> = emptyList()
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

        setContentView(R.layout.activity_chat_list)

        adapter = ChatListAdapter { room ->
            val intent = Intent(this, ChatActivity::class.java).apply {
                putExtra("recipientUid", room.contactUid)
                putExtra("recipientName", room.contactName)
            }
            startActivity(intent)
        }

        val recycler = findViewById<RecyclerView>(R.id.recyclerChats)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        searchView = findViewById(R.id.searchView)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false
            override fun onQueryTextChange(newText: String?): Boolean {
                filterRooms(newText ?: "")
                return true
            }
        })

        listenerRegistration = Firebase.firestore
            .collection("rooms")
            .whereArrayContains("participantIds", currentUser.uid)
            .addSnapshotListener { value, error ->
                if (error != null) {
                    ErrorLogger.log(this, error)
                    return@addSnapshotListener
                }
                val list = value?.documents?.mapNotNull { doc ->
                    val participantIds = doc.get("participantIds") as? List<*>
                    val otherUid = participantIds?.firstOrNull { it != currentUser.uid } as? String
                    val userNames = doc.get("userNames") as? Map<*, *>
                    val contactName = userNames?.get(otherUid) as? String ?: ""
                    val lastMessage = doc.getString("lastMessage") ?: ""
                    val updatedAt = doc.getTimestamp("updatedAt")
                        ?: com.google.firebase.Timestamp.now()
                    if (otherUid == null) null else ChatRoom(
                        id = doc.id,
                        contactUid = otherUid,
                        contactName = contactName,
                        lastMessage = lastMessage,
                        updatedAt = updatedAt,
                    )
                } ?: emptyList()
                allRooms = list
                filterRooms(searchView.query.toString())
            }
    }

    private fun filterRooms(query: String) {
        if (query.isBlank()) {
            adapter.submitList(allRooms)
        } else {
            val lower = query.lowercase(Locale.getDefault())
            adapter.submitList(allRooms.filter { it.contactName.lowercase(Locale.getDefault()).contains(lower) })
        }
    }

    override fun onDestroy() {
        if (::listenerRegistration.isInitialized) {
            listenerRegistration.remove()
        }
        super.onDestroy()
    }
}
