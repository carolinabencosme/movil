package com.example.projectandroid

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.projectandroid.ui.MainActivity
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MessagingService : FirebaseMessagingService() {

  override fun onMessageReceived(message: RemoteMessage) {
    val title = message.notification?.title ?: "Nuevo mensaje"
    val body = message.notification?.body ?: ""

    val intent = Intent(this, MainActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    val pendingIntent = PendingIntent.getActivity(
      this,
      0,
      intent,
      PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
    )

    val channelId = "messages"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        channelId,
        "Mensajes",
        NotificationManager.IMPORTANCE_HIGH
      )
      (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        .createNotificationChannel(channel)
    }

    val notification = NotificationCompat.Builder(this, channelId)
      .setSmallIcon(R.drawable.ic_chats)
      .setContentTitle(title)
      .setContentText(body)
      .setAutoCancel(true)
      .setContentIntent(pendingIntent)
      .build()

    NotificationManagerCompat.from(this).notify(0, notification)
  }

  override fun onNewToken(token: String) {
    super.onNewToken(token)

    val currentUser = Firebase.auth.currentUser ?: return
    Firebase.firestore.collection("users")
      .document(currentUser.uid)
      .update("fcmToken", token)
  }
}
