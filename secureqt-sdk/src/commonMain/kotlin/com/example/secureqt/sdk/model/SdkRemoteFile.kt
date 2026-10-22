package com.example.secureqt.sdk.model

data class SdkRemoteFile(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long
)
