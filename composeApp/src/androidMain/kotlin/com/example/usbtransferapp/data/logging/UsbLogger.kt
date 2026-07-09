package com.example.usbtransferapp.data.logging

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsbLogger @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    private val logFile: File? by lazy {
        try {
            // First try public Downloads directory so the user can easily view/copy it from File Manager or PC via USB MTP
            val publicDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val targetDir = if (publicDir != null && (publicDir.exists() || publicDir.mkdirs())) {
                publicDir
            } else {
                context.getExternalFilesDir(null) ?: context.filesDir
            }
            File(targetDir, "usb_transfer_debug.log").also { file ->
                if (!file.exists()) {
                    file.createNewFile()
                }
            }
        } catch (e: Exception) {
            Log.e("UsbLogger", "Failed to initialize log file in Downloads, trying fallback", e)
            try {
                val fallbackDir = context.getExternalFilesDir(null) ?: context.filesDir
                File(fallbackDir, "usb_transfer_debug.log").also { file ->
                    if (!file.exists()) {
                        file.createNewFile()
                    }
                }
            } catch (ex: Exception) {
                Log.e("UsbLogger", "Failed to initialize fallback log file", ex)
                null
            }
        }
    }

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val scope = CoroutineScope(Dispatchers.IO)

    fun log(level: String, tag: String, message: String, throwable: Throwable? = null) {
        val timeStr = dateFormat.format(Date())
        val formattedMsg = "[$timeStr] [$level] [$tag]: $message" + if (throwable != null) "\nException: ${throwable.stackTraceToString()}" else ""

        // Android logcat
        when (level) {
            "ERROR" -> Log.e(tag, message, throwable)
            "WARN" -> Log.w(tag, message, throwable)
            "DEBUG" -> Log.d(tag, message, throwable)
            else -> Log.i(tag, message, throwable)
        }

        // Update in-memory state flow (keep latest 300 lines for UI performance)
        synchronized(_logLines) {
            val updated = (_logLines.value + formattedMsg).takeLast(300)
            _logLines.value = updated
        }

        // Append to file asynchronously
        scope.launch {
            try {
                logFile?.let { file ->
                    PrintWriter(FileWriter(file, true)).use { out ->
                        out.println(formattedMsg)
                    }
                }
            } catch (e: Exception) {
                Log.e("UsbLogger", "Failed to write log to disk", e)
            }
        }
    }

    fun i(tag: String, message: String) = log("INFO", tag, message)
    fun d(tag: String, message: String) = log("DEBUG", tag, message)
    fun w(tag: String, message: String, throwable: Throwable? = null) = log("WARN", tag, message, throwable)
    fun e(tag: String, message: String, throwable: Throwable? = null) = log("ERROR", tag, message, throwable)

    fun getLogFilePath(): String {
        return logFile?.absolutePath ?: "Unavailable"
    }

    fun clearLogs() {
        synchronized(_logLines) {
            _logLines.value = emptyList()
        }
        scope.launch {
            try {
                logFile?.let { file ->
                    PrintWriter(FileWriter(file, false)).use { out ->
                        out.print("")
                    }
                }
            } catch (e: Exception) {
                Log.e("UsbLogger", "Failed to clear log file", e)
            }
        }
    }
}
