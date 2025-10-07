package com.example.texty

import android.content.Context
import android.content.SharedPreferences

/**
 * Gestiona un contador de notificaciones pendientes por sala de chat.
 */
class NotificationCounter private constructor(context: Context) {

  private val prefs: SharedPreferences =
    context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

  /**
   * Incrementa y devuelve el contador asociado a la sala indicada.
   */
  fun increment(roomId: String): Int {
    val key = keyFor(roomId)
    val updated = prefs.getInt(key, 0) + 1
    prefs.edit().putInt(key, updated).apply()
    return updated
  }

  /**
   * Borra el contador de una sala una vez que el usuario leyó los mensajes.
   */
  fun clear(roomId: String) {
    prefs.edit().remove(keyFor(roomId)).apply()
  }

  /**
   * Genera la clave única usada para guardar el contador en preferencias.
   */
  private fun keyFor(roomId: String): String = "room_$roomId"

  companion object {
    private const val PREF_NAME = "notification_counts"

    @Volatile private var instance: NotificationCounter? = null

    /**
     * Obtiene o crea la instancia única del contador para toda la app.
     */
    fun getInstance(context: Context): NotificationCounter {
      return instance ?: synchronized(this) {
        instance ?: NotificationCounter(context).also { instance = it }
      }
    }
  }
}
