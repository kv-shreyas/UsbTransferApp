package com.example.secureqt.sdk.util

import kotlin.math.log
import kotlin.math.pow

/**
 * Reusable utility for formatting bytes and time, replacing the duplicated
 * implementations in MainScreen.kt, MainViewModel.kt, and ClientServiceController.kt.
 */
object FormatUtils {
    
    /** Formats bytes into a human-readable string (e.g. "1.5 MB"). */
    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        if (bytes < 1024) return "$bytes B"
        val exp = (log(bytes.toDouble(), 1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1]
        return String.format("%.1f %sB", bytes / 1024.0.pow(exp.toDouble()), pre)
    }

    /** Formats seconds into a human-readable time string (e.g. "1m 30s"). */
    fun formatTime(seconds: Long): String {
        return if (seconds >= 60) {
            val min = seconds / 60
            val sec = seconds % 60
            "${min}m ${sec}s"
        } else {
            "${seconds}s"
        }
    }
}
