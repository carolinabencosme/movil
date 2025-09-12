package com.example.projectandroid

import android.app.Application
import com.example.projectandroid.util.AppLogger
import com.google.android.material.color.DynamicColors
import android.os.Build

class ProjectApplication : Application() {
  override fun onCreate() {
    super.onCreate()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      DynamicColors.applyToActivitiesIfAvailable(this)
    }

    val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
      AppLogger.logError(this@ProjectApplication, throwable)
      defaultHandler?.uncaughtException(thread, throwable)
    }
  }
}
