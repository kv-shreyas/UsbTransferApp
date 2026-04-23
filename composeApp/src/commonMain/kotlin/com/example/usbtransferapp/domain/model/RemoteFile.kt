package com.example.usbtransferapp.domain.model

data class RemoteFile(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val path: String
)
