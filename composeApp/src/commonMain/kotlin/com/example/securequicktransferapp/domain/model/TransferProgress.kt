package com.example.securequicktransferapp.domain.model

data class TransferProgress(
    val isVisible: Boolean = false,
    val filename: String = "",
    val total: String = "",
    val percentage: Int = 0,
    val speed: String = "",
    val transferred: String = "",
    val eta: String = "",
    val elapsed: String = "",
    val statusMessage: String = "",
    val batchElapsed: String = "",
    val totalFiles: Int = 1,
    val currentFileIndex: Int = 1,
    val isComplete: Boolean = false,
    val queue: List<String> = emptyList()
)
