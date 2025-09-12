package com.example.texty.util

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    private const val TAG = "AppLogger"
    private const val MAX_LOG_FILES = 20
    private const val MAX_LOG_STORAGE_BYTES = 20L * 1024 * 1024 // 20 MB

    fun logError(context: Context, throwable: Throwable) {
        try {
            val logsDir = File(context.filesDir, "logs").apply { mkdirs() }
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(logsDir, "$timestamp.log")
            file.printWriter().use { pw ->
                throwable.printStackTrace(pw)
            }
            val logFiles = logsDir.listFiles { f -> f.extension == "log" }?.sortedBy { it.lastModified() }?.toMutableList() ?: mutableListOf()
            Log.i(TAG, "Existing logs: ${logFiles.joinToString { it.name }}")
            var totalSize = logFiles.sumOf { it.length() }
            while (logFiles.size > MAX_LOG_FILES || totalSize > MAX_LOG_STORAGE_BYTES) {
                val oldest = logFiles.removeAt(0)
                totalSize -= oldest.length()
                oldest.delete()
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

    fun shareLogs(context: Context) {
        try {
            val logsDir = File(context.filesDir, "logs")
            val files = logsDir.listFiles { file -> file.extension == "log" } ?: emptyArray()
            if (files.isEmpty()) return
            val uris = ArrayList<Uri>()
            for (file in files) {
                val uri = FileProvider.getUriForFile(
                    context,
                    context.packageName + ".fileprovider",
                    file
                )
                uris.add(uri)
            }
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/plain"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(
                    intent,
                    context.getString(com.example.texty.R.string.share_logs)
                )
            )
        } catch (_: Exception) {
            // Ignore share failures
        }
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

