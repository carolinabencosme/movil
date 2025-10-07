package com.example.texty

import android.app.Application
import com.example.texty.util.AppLogger
import com.google.android.material.color.DynamicColors
import android.os.Build

// Inicializa configuración global de la app.
class TextyApplication : Application() {
  override fun onCreate() {
    super.onCreate()

    // Activa colores dinámicos en Android 12+.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      DynamicColors.applyToActivitiesIfAvailable(this)
    }

    val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
    // Registra logger para errores no controlados.
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
      AppLogger.logError(this@TextyApplication, throwable)
      defaultHandler?.uncaughtException(thread, throwable)
    }
  }
}
