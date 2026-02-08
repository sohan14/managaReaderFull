package com.example.mangareader.utils

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Debug logger that writes to file for viewing in app
 */
object DebugLogger {
    
    private var logFile: File? = null
    private var isEnabled = true
    
    fun init(context: Context) {
        logFile = File(context.filesDir, "debug_log.txt")
    }
    
    fun log(tag: String, message: String) {
        if (!isEnabled) return
        
        try {
            val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            val logEntry = "$timestamp $tag: $message\n"
            
            logFile?.appendText(logEntry)
            
            // Also log to Android logcat
            android.util.Log.d(tag, message)
        } catch (e: Exception) {
            android.util.Log.e("DebugLogger", "Failed to write log", e)
        }
    }
    
    fun getAllLogs(): String {
        return try {
            if (logFile?.exists() == true) {
                logFile?.readText() ?: "No logs found."
            } else {
                "No log file found."
            }
        } catch (e: Exception) {
            "Error reading logs: ${e.message}"
        }
    }
    
    fun clearLogs() {
        try {
            logFile?.delete()
        } catch (e: Exception) {
            android.util.Log.e("DebugLogger", "Failed to clear logs", e)
        }
    }
}
