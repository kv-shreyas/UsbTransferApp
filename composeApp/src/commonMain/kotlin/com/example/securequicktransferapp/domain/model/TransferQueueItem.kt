package com.example.securequicktransferapp.domain.model

import java.io.File
import java.util.UUID

/**
 * Represents the type of a transfer operation.
 */
enum class TransferType {
    SEND,
    FETCH
}

/**
 * Represents the lifecycle status of a single queued transfer item.
 */
enum class TransferItemStatus {
    PENDING,
    ACTIVE,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * A single item in the transfer queue.
 *
 * @property id Unique identifier for this queue item.
 * @property type Whether this is a SEND (host -> device) or FETCH (device -> host) operation.
 * @property localFile The local file to send (for SEND type).
 * @property remoteFile The remote file to fetch (for FETCH type).
 * @property destinationPath The target path on the remote (for SEND) or local (for FETCH) side.
 * @property isDirectory Whether the item is a directory.
 * @property remoteFileName Override name for the file on the remote side.
 * @property status Current lifecycle status of this item.
 * @property progress Transfer progress percentage (0-100).
 * @property speed Current transfer speed string.
 * @property error Error message if the transfer failed.
 * @property addedAt Timestamp when the item was added to the queue.
 */
data class TransferQueueItem(
    val id: String = UUID.randomUUID().toString(),
    val type: TransferType,
    val localFile: File? = null,
    val remoteFile: RemoteFile? = null,
    val destinationPath: String,
    val isDirectory: Boolean = false,
    val remoteFileName: String = localFile?.name ?: remoteFile?.name ?: "",
    val status: TransferItemStatus = TransferItemStatus.PENDING,
    val progress: Int = 0,
    val speed: String = "",
    val elapsed: String = "",
    val eta: String = "",
    val transferred: String = "",
    val total: String = "",
    val error: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val startedAt: Long = 0L
) {
    val displayName: String
        get() = localFile?.name ?: remoteFile?.name ?: remoteFileName

    val displaySize: String
        get() = when {
            isDirectory -> "Directory"
            localFile != null -> com.example.secureqt.sdk.SecureQtSdk.Utils.formatSize(localFile.length())
            remoteFile != null -> com.example.secureqt.sdk.SecureQtSdk.Utils.formatSize(remoteFile.size)
            else -> "Unknown"
        }

    val fileSize: Long
        get() = when {
            localFile != null -> localFile.length()
            remoteFile != null -> remoteFile.size
            else -> 0L
        }
}
