package com.example.texty

import android.app.Application
import com.example.texty.util.AppLogger
import com.google.android.material.color.DynamicColors
import android.os.Build

class TextyApplication : Application() {
  override fun onCreate() {
    super.onCreate()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      DynamicColors.applyToActivitiesIfAvailable(this)
    }

    val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
      AppLogger.logError(this@TextyApplication, throwable)
      defaultHandler?.uncaughtException(thread, throwable)
    }
  }
}
