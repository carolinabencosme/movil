package com.example.texty.util

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

/**
 * Tracks the online presence of the authenticated user and keeps Firestore updated in real time.
 *
 * This observer listens to both process level lifecycle events and FirebaseAuth state changes so
 * that the `isOnline` flag reflects whether the app is in foreground with a logged in user.
 */
object OnlineStatusTracker : DefaultLifecycleObserver, FirebaseAuth.AuthStateListener {

    private const val TAG = "OnlineStatusTracker"

    private var initialized = false
    private var currentUid: String? = null
    private var lastReportedStatus: Boolean? = null
    private var isInForeground = false

    /** Call once from [android.app.Application.onCreate] to start monitoring. */
    fun initialize() {
        if (initialized) return
        initialized = true

        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        FirebaseAuth.getInstance().addAuthStateListener(this)

        isInForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(
            androidx.lifecycle.Lifecycle.State.STARTED
        )
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
        val previousUid = currentUid

        if (previousUid != null && previousUid != newUid) {
            setOnlineStatus(previousUid, false)
        }

        currentUid = newUid
        lastReportedStatus = null
        syncPresence()
    }

    private fun syncPresence() {
        val uid = currentUid ?: run {
            lastReportedStatus = null
            return
        }

        val desiredStatus = isInForeground
        if (lastReportedStatus == desiredStatus) return

        setOnlineStatus(uid, desiredStatus)
        lastReportedStatus = desiredStatus
    }

    private fun setOnlineStatus(uid: String, online: Boolean) {
        Firebase.firestore.collection("users")
            .document(uid)
            .update("isOnline", online)
            .addOnFailureListener { e ->
                Log.w(TAG, "No se pudo actualizar el estado en línea para $uid", e)
            }
    }
}
