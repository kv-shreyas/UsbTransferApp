package com.example.securequicktransferapp.domain.model.deviceinfo

import java.text.DecimalFormat

data class ClientAppDetails(
    val appName: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val isDebuggable: Boolean,
    val dataDir: String,
    val cacheSizeBytes: Long
) {
    val formattedCacheSize: String get() = formatBytes(cacheSizeBytes)
}

data class ClientDeviceSummary(
    val deviceId: String,
    val displayName: String,
    val isConnected: Boolean
)

data class DetectedDeviceSummary(
    val deviceId: String,
    val displayName: String,
    val isConnecting: Boolean = false
)

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    val index = digitGroups.coerceIn(0, units.size - 1)
    val value = bytes / Math.pow(1024.0, index.toDouble())
    return DecimalFormat("#,##0.0#").format(value) + " " + units[index]
}
