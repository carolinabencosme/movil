package com.example.texty

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.texty.ui.MainActivity // TODO: cambia a tu ChatActivity si aplica
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MessagingService : FirebaseMessagingService() {

  companion object {
    private const val CHANNEL_ID = "messages"
    private const val TAG = "MessagingService"
  }

  override fun onMessageReceived(message: RemoteMessage) {
    // --- Datos que pueden venir en el payload de data (desde la Cloud Function) ---
    val data = message.data
    val roomId = data["roomId"] ?: data["chatId"]
    val messageId = data["messageId"]

    val title = data["senderName"]
      ?: message.notification?.title
      ?: getString(R.string.notification_generic_title)
    val defaultBody = getString(R.string.notification_generic_body)

    // Si no podemos publicar notificaciones, salimos silenciosamente
    if (!canPostNotifications()) return

    createChannelIfNeeded()

    val intent = Intent(this, MainActivity::class.java).apply {
      // TODO: si tienes ChatActivity, usa esa clase y pásale el chatId
      addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
      roomId?.let { putExtra("chatId", it) }
    }

    val requestCode = (roomId?.hashCode() ?: 0)
    val pendingIntent = PendingIntent.getActivity(
      this,
      requestCode,
      intent,
      PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
    )

    val builder = NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_chats) // Asegúrate de tener este drawable
      .setContentTitle(title)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setAutoCancel(true)
      .setContentIntent(pendingIntent)

    val notificationCounter = roomId?.let { NotificationCounter.getInstance(this).increment(it) }
    val contentText = if (notificationCounter != null) {
      resources.getQuantityString(
        R.plurals.notification_new_messages,
        notificationCounter,
        notificationCounter,
      )
    } else {
      defaultBody
    }

    builder
      .setContentText(contentText)
      .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))

    notificationCounter?.let { builder.setNumber(it) }

    val notification = builder.build()

    val notifyId = roomId?.hashCode() ?: messageId?.hashCode() ?: System.currentTimeMillis().toInt()
    try {
      NotificationManagerCompat.from(this).notify(notifyId, notification)
    } catch (se: SecurityException) {
      Log.w(TAG, "No se pudo mostrar la notificación (permiso no concedido)", se)
    }
  }

  override fun onNewToken(token: String) {
    super.onNewToken(token)
    val currentUser = Firebase.auth.currentUser ?: return

    // Guardamos como ARRAY por si el usuario usa múltiples dispositivos
    Firebase.firestore.collection("users")
      .document(currentUser.uid)
      .set(
        mapOf("fcmTokens" to FieldValue.arrayUnion(token)),
        SetOptions.merge()
      )
      .addOnFailureListener { e ->
        Log.w(TAG, "Error guardando token FCM", e)
      }
  }

  // --- Helpers ---

  private fun canPostNotifications(): Boolean {
    // Android 13+ requiere POST_NOTIFICATIONS
    if (Build.VERSION.SDK_INT >= 33) {
      val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
              PackageManager.PERMISSION_GRANTED
      if (!granted) return false
    }
    // Usuario pudo desactivar notificaciones del app
    return NotificationManagerCompat.from(this).areNotificationsEnabled()
  }

  private fun createChannelIfNeeded() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      val existing = manager.getNotificationChannel(CHANNEL_ID)
      if (existing == null) {
        val channel = NotificationChannel(
          CHANNEL_ID,
          "Mensajes",
          NotificationManager.IMPORTANCE_HIGH
        ).apply {
          description = "Notificaciones de nuevos mensajes"
        }
        manager.createNotificationChannel(channel)
      }
    }
  }
}
