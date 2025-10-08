package com.example.texty.util

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

object OnlineStatusTracker : DefaultLifecycleObserver, FirebaseAuth.AuthStateListener {

    private const val TAG = "OnlineStatusTracker"

    private var initialized = false
    private var currentUid: String? = null
    private var lastReportedStatus: Boolean? = null
    private var isInForeground = false

    fun initialize() {
        if (initialized) return
        initialized = true

        val processLifecycle = ProcessLifecycleOwner.get().lifecycle
        processLifecycle.addObserver(this)
        FirebaseAuth.getInstance().addAuthStateListener(this)

        isInForeground = processLifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        currentUid = FirebaseAuth.getInstance().currentUser?.uid
        syncPresence()
    }

    override fun onStart(owner: LifecycleOwner) {
        isInForeground = true
        syncPresence()
    }

    override fun onStop(owner: LifecycleOwner) {
        isInForeground = false
        syncPresence()
    }

    override fun onAuthStateChanged(auth: FirebaseAuth) {
        val newUid = auth.currentUser?.uid
        val prevUid = currentUid
        if (prevUid != null && prevUid != newUid) setOnlineStatus(prevUid, false)

        currentUid = newUid
        lastReportedStatus = null
        syncPresence()
    }

    private fun syncPresence() {
        val uid = currentUid ?: run { lastReportedStatus = null; return }
        val desired = isInForeground
        if (lastReportedStatus == desired) return
        setOnlineStatus(uid, desired)
    }

    private fun setOnlineStatus(uid: String, online: Boolean) {
        Firebase.firestore.collection("users").document(uid)
            .set(
                mapOf(
                    "isOnline" to online,
                    "online" to online, // compat con UI que lea 'online'
                    "lastActive" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )
            .addOnSuccessListener { lastReportedStatus = online }
            .addOnFailureListener {
                Log.w(TAG, "No se pudo actualizar online para $uid", it)
                lastReportedStatus = null
            }
    }
}
