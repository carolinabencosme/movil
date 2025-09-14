package com.example.texty

import android.app.Application
import com.example.texty.util.AppLogger
import com.example.texty.util.FcmTokenManager
import com.google.android.material.color.DynamicColors
import android.os.Build
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase

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

    if (Firebase.auth.currentUser != null) {
      FcmTokenManager.refreshTokenWithRetry()
    }
  }
}
