package com.example.projectandroid.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.projectandroid.R
import com.example.projectandroid.repository.UserRepository
import com.example.projectandroid.util.ErrorLogger
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase

class SearchUserActivity : AppCompatActivity() {
    private val userRepository = UserRepository()
    private lateinit var adapter: UserAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val auth = Firebase.auth
        if (auth.currentUser == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_search_user)

        adapter = UserAdapter { user ->
            val intent = Intent(this, ChatActivity::class.java).apply {
                putExtra("recipientUid", user.uid)
                putExtra("recipientName", user.displayName)
            }
            startActivity(intent)
        }

        val recycler = findViewById<RecyclerView>(R.id.recyclerUsers)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        val searchView = findViewById<SearchView>(R.id.searchView)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false
            override fun onQueryTextChange(newText: String?): Boolean {
                val q = newText ?: ""
                if (q.isBlank()) {
                    adapter.submitList(emptyList())
                } else {
                    userRepository.getUsersByDisplayName(q, onSuccess = { users ->
                        adapter.submitList(users)
                    }, onFailure = { e ->
                        ErrorLogger.log(this@SearchUserActivity, e)
                    })
                }
                return true
            }
        })
    }
}
