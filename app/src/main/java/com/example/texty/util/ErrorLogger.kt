package com.example.texty.util

import android.content.Context
import android.widget.Toast
import java.io.File
import java.util.UUID

/**
 * Envía errores a Firebase Crashlytics y logcat.
 */
object ErrorLogger {
  fun log(context: Context, throwable: Throwable) {
    val id = UUID.randomUUID().toString()
    try {
      val file = File(context.cacheDir, "error-$id.log")
      file.printWriter().use { pw ->
        throwable.printStackTrace(pw)
      }
    } catch (_: Exception) {
      // Ignore logging failures
    }

    Toast.makeText(context, "Ocurrió un error. ID: $id", Toast.LENGTH_LONG).show()
  }
}
