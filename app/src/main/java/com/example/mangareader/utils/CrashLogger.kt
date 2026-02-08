package com.example.mangareader.utils

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * Handles crash logging and allows users to copy crash logs
 */
class CrashLogger(private val context: Context) {

    private val crashLogFile: File
        get() = File(context.filesDir, "crash_log.txt")

    init {
        // Set up global exception handler
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            logCrash(throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Log a crash to file
     */
    private fun logCrash(throwable: Throwable) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val stackTrace = getStackTraceString(throwable)
            
            val logEntry = """
                |===== CRASH LOG =====
                |Time: $timestamp
                |
                |$stackTrace
                |
                |====================
                |
            """.trimMargin()

            // Append to log file
            crashLogFile.appendText(logEntry)
            
            Log.e("CrashLogger", "Crash logged: ${throwable.message}")
        } catch (e: Exception) {
            Log.e("CrashLogger", "Failed to log crash", e)
        }
    }

    /**
     * Get all crash logs
     */
    fun getAllLogs(): String {
        return try {
            if (crashLogFile.exists()) {
                crashLogFile.readText()
            } else {
                "No crash logs found."
            }
        } catch (e: Exception) {
            "Error reading logs: ${e.message}"
        }
    }

    /**
     * Copy logs to clipboard
     */
    fun copyLogsToClipboard(): Boolean {
        return try {
            val logs = getAllLogs()
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Crash Logs", logs)
            clipboard.setPrimaryClip(clip)
            true
        } catch (e: Exception) {
            Log.e("CrashLogger", "Failed to copy logs", e)
            false
        }
    }

    /**
     * Clear all logs
     */
    fun clearLogs() {
        try {
            if (crashLogFile.exists()) {
                crashLogFile.delete()
            }
        } catch (e: Exception) {
            Log.e("CrashLogger", "Failed to clear logs", e)
        }
    }

    /**
     * Convert throwable to string
     */
    private fun getStackTraceString(throwable: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        return sw.toString()
    }

    companion object {
        @Volatile
        private var instance: CrashLogger? = null

        fun getInstance(context: Context): CrashLogger {
            return instance ?: synchronized(this) {
                instance ?: CrashLogger(context.applicationContext).also { instance = it }
            }
        }
    }
}
