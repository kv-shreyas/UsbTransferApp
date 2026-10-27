package com.example.securequicktransferapp.domain.model.deviceinfo

data class ClientAppDetails(
    val appName: String,
    val packageName: String,
    val appVersion: String,
    val osVersion: String,
)

data class ClientDeviceSummary(
    val deviceId: String,
    val displayName: String,
    val isConnected: Boolean,
    val appDetails: ClientAppDetails? = null
)

data class DetectedDeviceSummary(
    val deviceId: String,
    val displayName: String,
    val isConnecting: Boolean = false
)

