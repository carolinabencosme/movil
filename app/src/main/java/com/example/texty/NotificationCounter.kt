package com.example.texty

import android.content.Context
import android.content.SharedPreferences

class NotificationCounter private constructor(context: Context) {

  private val prefs: SharedPreferences =
    context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

  fun increment(roomId: String): Int {
    val key = keyFor(roomId)
    val updated = prefs.getInt(key, 0) + 1
    prefs.edit().putInt(key, updated).apply()
    return updated
  }

  fun clear(roomId: String) {
    prefs.edit().remove(keyFor(roomId)).apply()
  }

  private fun keyFor(roomId: String): String = "room_$roomId"

  companion object {
    private const val PREF_NAME = "notification_counts"

    @Volatile private var instance: NotificationCounter? = null

    fun getInstance(context: Context): NotificationCounter {
      return instance ?: synchronized(this) {
        instance ?: NotificationCounter(context).also { instance = it }
      }
    }
  }
}
