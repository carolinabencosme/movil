package com.example.texty

import android.app.Application
import com.example.texty.util.AppLogger
import com.example.texty.util.OnlineStatusTracker
import com.google.android.material.color.DynamicColors
import android.os.Build

/**
 * Inicializa la configuración global que la app requiere antes de mostrar pantallas.
 */
class TextyApplication : Application() {

  /**
   * Configura colores dinámicos, el rastreador de estado en línea y el manejo de errores.
   */
  override fun onCreate() {
    super.onCreate()

    // Activa colores dinámicos en Android 12+.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      DynamicColors.applyToActivitiesIfAvailable(this)
    }

    OnlineStatusTracker.initialize()

    val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
    // Registra logger para errores no controlados.
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
      AppLogger.logError(this@TextyApplication, throwable)
      defaultHandler?.uncaughtException(thread, throwable)
    }
  }
}
