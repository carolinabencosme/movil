package com.example.projectandroid.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    private const val TAG = "AppLogger"

    fun logError(context: Context, throwable: Throwable) {
        try {
            val logsDir = File(context.filesDir, "logs").apply { mkdirs() }
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(logsDir, "$timestamp.log")
            file.printWriter().use { pw ->
                throwable.printStackTrace(pw)
            }
        } catch (_: Exception) {
            // Ignore logging failures
        }
        Log.e(TAG, Log.getStackTraceString(throwable))
    }

    fun logInfo(tag: String, message: String, context: Context? = null) {
        Log.i(tag, message)
        context?.let { appendLog(it, "INFO", tag, message) }
    }

    fun logDebug(tag: String, message: String, context: Context? = null) {
        Log.d(tag, message)
        context?.let { appendLog(it, "DEBUG", tag, message) }
    }

    fun logWarn(tag: String, message: String, context: Context? = null) {
        Log.w(tag, message)
        context?.let { appendLog(it, "WARN", tag, message) }
    }

    fun logVerbose(tag: String, message: String, context: Context? = null) {
        Log.v(tag, message)
        context?.let { appendLog(it, "VERBOSE", tag, message) }
    }

    private fun appendLog(context: Context, level: String, tag: String, message: String) {
        try {
            val logsDir = File(context.filesDir, "logs").apply { mkdirs() }
            val timestamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            val file = File(logsDir, "$timestamp.log")
            file.appendText("${System.currentTimeMillis()} $level/$tag: $message\n")
        } catch (_: Exception) {
            // Ignore logging failures
        }
    }
}

