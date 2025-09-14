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
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.example.texty.util.FcmTokenManager

class MessagingService : FirebaseMessagingService() {

  companion object {
    private const val CHANNEL_ID = "messages"
    private const val TAG = "MessagingService"
  }

  override fun onMessageReceived(message: RemoteMessage) {
    // --- Datos que pueden venir en el payload de data (desde la Cloud Function) ---
    val data = message.data
    val chatId = data["chatId"]
    val messageId = data["messageId"]

    // Título y cuerpo: preferimos data, luego notification, luego defaults
    val title = data["senderName"]
      ?: message.notification?.title
      ?: "Nuevo mensaje"
    val body = data["body"]
      ?: message.notification?.body
      ?: "📩 Tienes un nuevo mensaje"

    // Si no podemos publicar notificaciones, salimos silenciosamente
    if (!canPostNotifications()) return

    createChannelIfNeeded()

    val intent = Intent(this, MainActivity::class.java).apply {
      // TODO: si tienes ChatActivity, usa esa clase y pásale el chatId
      addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
      chatId?.let { putExtra("chatId", it) }
    }

    val requestCode = (chatId?.hashCode() ?: 0)
    val pendingIntent = PendingIntent.getActivity(
      this,
      requestCode,
      intent,
      PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
    )

    val notification = NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_chats) // Asegúrate de tener este drawable
      .setContentTitle(title)
      .setContentText(body)
      .setStyle(NotificationCompat.BigTextStyle().bigText(body))
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setAutoCancel(true)
      .setContentIntent(pendingIntent)
      .build()

    val notifyId = messageId?.hashCode() ?: System.currentTimeMillis().toInt()
    try {
      NotificationManagerCompat.from(this).notify(notifyId, notification)
    } catch (se: SecurityException) {
      Log.w(TAG, "No se pudo mostrar la notificación (permiso no concedido)", se)
    }
  }

  override fun onNewToken(token: String) {
    super.onNewToken(token)
    FcmTokenManager.sendTokenToFirestore(token)
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
