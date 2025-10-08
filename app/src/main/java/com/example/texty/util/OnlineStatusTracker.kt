package com.example.texty.util

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
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
    private const val RETRY_DELAY_MS = 3_000L

    private val auth = FirebaseAuth.getInstance()
    private val firestore = Firebase.firestore
    private val handler = Handler(Looper.getMainLooper())

    private var initialized = false
    private var currentUid: String? = null
    private var targetStatus: Boolean? = null
    private var lastReportedStatus: Boolean? = null
    private var isInForeground = false

    private var updateInFlight = false
    private var requestCounter = 0L
    private var activeRequestId: Long? = null
    private var retryRunnable: Runnable? = null

    /** Call once from [android.app.Application.onCreate] to start monitoring. */
    fun initialize() {
        if (initialized) return
        initialized = true

        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        auth.addAuthStateListener(this)

        isInForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(
            androidx.lifecycle.Lifecycle.State.STARTED
        )
        currentUid = auth.currentUser?.uid
        targetStatus = currentUid?.let { isInForeground }
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
            markOffline(previousUid)
        }

        currentUid = newUid
        targetStatus = newUid?.let { isInForeground }
        lastReportedStatus = null
        updateInFlight = false
        activeRequestId = null
        cancelRetry()
        syncPresence()
    }

    private fun syncPresence() {
        val uid = currentUid ?: run {
            targetStatus = null
            lastReportedStatus = null
            return
        }

        val desiredStatus = isInForeground
        targetStatus = desiredStatus

        if (updateInFlight) return
        if (lastReportedStatus == desiredStatus) return

        dispatchStatus(uid, desiredStatus)
    }

    private fun dispatchStatus(uid: String, online: Boolean) {
        updateInFlight = true
        val requestId = ++requestCounter
        activeRequestId = requestId
        cancelRetry()

        firestore.collection("users")
            .document(uid)
            .set(buildPresenceUpdate(online), SetOptions.merge())
            .addOnSuccessListener {
                if (currentUid == uid && activeRequestId == requestId) {
                    lastReportedStatus = online
                }
            }
            .addOnFailureListener { e ->
                if (currentUid == uid && activeRequestId == requestId) {
                    lastReportedStatus = null
                    scheduleRetry()
                }
                Log.w(TAG, "No se pudo actualizar el estado en línea para $uid", e)
            }
            .addOnCompleteListener { task ->
                if (activeRequestId != requestId) return@addOnCompleteListener

                updateInFlight = false
                activeRequestId = null

                if (task.isSuccessful) {
                    val nextUid = currentUid
                    val desired = targetStatus
                    if (nextUid != null && desired != null && desired != lastReportedStatus) {
                        dispatchStatus(nextUid, desired)
                    }
                }
            }
    }

    private fun scheduleRetry() {
        if (retryRunnable != null) return

        val runnable = Runnable {
            retryRunnable = null
            if (!updateInFlight) {
                syncPresence()
            }
        }

        retryRunnable = runnable
        handler.postDelayed(runnable, RETRY_DELAY_MS)
    }

    private fun cancelRetry() {
        retryRunnable?.let { handler.removeCallbacks(it) }
        retryRunnable = null
    }

    private fun markOffline(uid: String) {
        firestore.collection("users")
            .document(uid)
            .set(buildPresenceUpdate(false), SetOptions.merge())
            .addOnFailureListener { e ->
                Log.w(TAG, "No se pudo forzar estado offline para $uid", e)
            }
    }

    private fun buildPresenceUpdate(online: Boolean) = mapOf(
        "isOnline" to online,
        // Eliminamos el campo heredado "online" para evitar estados contradictorios.
        "online" to FieldValue.delete(),
    )
}
