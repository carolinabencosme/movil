package com.example.projectandroid.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.projectandroid.R
import com.example.projectandroid.model.ChatRoom
import com.example.projectandroid.util.AppLogger
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import com.google.android.material.appbar.MaterialToolbar
import java.util.Locale

class ChatListActivity : AppCompatActivity() {
    private val viewModel: ChatListViewModel by viewModels()
    private lateinit var adapter: ChatListAdapter
    private lateinit var searchView: SearchView
    private var allRooms: List<ChatRoom> = emptyList()

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
        val toolbar = findViewById<MaterialToolbar>(R.id.topAppBar)
        setSupportActionBar(toolbar)

        adapter = ChatListAdapter { room ->
            val intent = Intent(this, ChatActivity::class.java).apply {
                putExtra("recipientUid", room.contactUid)
                putExtra("recipientName", room.contactName)
            }
            startActivity(intent)
        }

        val recycler = findViewById<RecyclerView>(R.id.recyclerChats)
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)
        val placeholder = findViewById<TextView>(R.id.textPlaceholder)

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        recycler.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))

        searchView = findViewById(R.id.searchView)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false
            override fun onQueryTextChange(newText: String?): Boolean {
                filterRooms(newText ?: "")
                return true
            }
        })

        viewModel.loading.observe(this) { isLoading ->
            progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        }
        viewModel.rooms.observe(this) { list ->
            allRooms = list
            if (list.isEmpty()) {
                placeholder.visibility = View.VISIBLE
                recycler.visibility = View.GONE
            } else {
                placeholder.visibility = View.GONE
                recycler.visibility = View.VISIBLE
                filterRooms(searchView.query.toString())
            }
        }
        viewModel.error.observe(this) { e ->
            e?.let { AppLogger.logError(this, it) }
        }
        viewModel.startListening(currentUser.uid)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_chat_list, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_new_chat -> {
                startActivity(Intent(this, SearchUserActivity::class.java))
                true
            }
            R.id.action_logout -> {
                FirebaseAuth.getInstance().signOut()
                val intent = Intent(this, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
                true
            }
            R.id.action_share_logs -> {
                AppLogger.shareLogs(this)
                true
            }
            else -> super.onOptionsItemSelected(item)
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
}
