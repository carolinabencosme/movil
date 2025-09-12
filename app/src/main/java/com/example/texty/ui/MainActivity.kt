package com.example.texty.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.example.texty.R
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNavigation)
        if (savedInstanceState == null) {
            replaceFragment(ChatListFragment())
        }

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_chats -> {
                    replaceFragment(ChatListFragment())
                    true
                }
                R.id.navigation_search -> {
                    replaceFragment(SearchUserFragment())
                    true
                }
                R.id.navigation_requests -> {
                    replaceFragment(FriendRequestsFragment())
                    true
                }
                R.id.navigation_profile -> {
                    replaceFragment(ProfileFragment())
                    true
                }
                else -> false
            }
        }
    }

    override fun onStart() {
        super.onStart()
        setOnlineStatus(true)
    }

    override fun onStop() {
        setOnlineStatus(false)
        super.onStop()
    }

    private fun setOnlineStatus(online: Boolean) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        Firebase.firestore.collection("users").document(uid).update("isOnline", online)
    }

    private fun replaceFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }
}
