package com.example.projectandroid

import android.app.Application
import com.example.projectandroid.util.AppLogger

class ProjectApplication : Application() {
  override fun onCreate() {
    super.onCreate()

    val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
      AppLogger.logError(this@ProjectApplication, throwable)
      defaultHandler?.uncaughtException(thread, throwable)
    }
  }
}
