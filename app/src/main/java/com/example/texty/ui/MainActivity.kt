package com.example.texty.ui

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.example.texty.R
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.messaging.FirebaseMessaging

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }
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
        sendFcmToken()
    }

    override fun onStop() {
        setOnlineStatus(false)
        super.onStop()
    }

    private fun setOnlineStatus(online: Boolean) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        Firebase.firestore.collection("users").document(uid).update("isOnline", online)
    }

    private fun sendFcmToken() {
        FirebaseMessaging.getInstance().token
            .addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Log.w(TAG, "Fetching FCM registration token failed", task.exception)
                    return@addOnCompleteListener
                }

                val token = task.result
                val user = FirebaseAuth.getInstance().currentUser ?: return@addOnCompleteListener

                Firebase.firestore.collection("users")
                    .document(user.uid)
                    .set(
                        mapOf("fcmTokens" to FieldValue.arrayUnion(token)),
                        SetOptions.merge()
                    )
                    .addOnFailureListener { e ->
                        Log.w(TAG, "Error saving token FCM", e)
                    }
            }
    }

    private fun replaceFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }
}
