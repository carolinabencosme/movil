package com.example.texty.util

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.messaging.FirebaseMessaging

object FcmTokenManager {
  private const val TAG = "FcmTokenManager"

  fun refreshTokenWithRetry(retry: Int = 3) {
    FirebaseMessaging.getInstance().token
      .addOnSuccessListener { token ->
        sendTokenToFirestore(token, retry)
      }
      .addOnFailureListener { e ->
        Log.w(TAG, "Error obteniendo token FCM", e)
        if (retry > 0) {
          Handler(Looper.getMainLooper()).postDelayed({ refreshTokenWithRetry(retry - 1) }, 2000)
        }
      }
  }

  fun sendTokenToFirestore(token: String, retry: Int = 3) {
    val currentUser = Firebase.auth.currentUser ?: return
    Firebase.firestore.collection("users")
      .document(currentUser.uid)
      .set(
        mapOf("fcmTokens" to FieldValue.arrayUnion(token)),
        SetOptions.merge()
      )
      .addOnFailureListener { e ->
        Log.w(TAG, "Error guardando token FCM", e)
        if (retry > 0) {
          Handler(Looper.getMainLooper()).postDelayed({ sendTokenToFirestore(token, retry - 1) }, 2000)
        }
      }
  }
}
