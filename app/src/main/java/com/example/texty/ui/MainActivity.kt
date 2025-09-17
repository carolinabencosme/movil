package com.example.texty.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.texty.R
import com.example.texty.repository.KeyRepository
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                sendFcmToken()
            }
        }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNavigation)
        if (savedInstanceState == null) {
            replaceFragment(ChatListFragment())
        }

        FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
            lifecycleScope.launch {
                KeyRepository.getInstance(applicationContext).ensureLocalKeys(uid)
            }
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
        requestNotificationPermission()
        FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
            lifecycleScope.launch {
                KeyRepository.getInstance(applicationContext).refreshOneTimePreKeysIfNeeded(uid)
            }
        }
    }

    override fun onStop() {
        setOnlineStatus(false)
        super.onStop()
    }

    private fun setOnlineStatus(online: Boolean) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        Firebase.firestore.collection("users").document(uid).update("isOnline", online)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    sendFcmToken()
                }
                else -> {
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            sendFcmToken()
        }
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
