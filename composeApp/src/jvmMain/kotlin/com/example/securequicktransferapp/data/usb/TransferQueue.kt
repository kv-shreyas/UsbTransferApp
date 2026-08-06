package com.example.securequicktransferapp.data.usb

import com.example.secureqt.sdk.SecureQtSdk
import com.example.securequicktransferapp.domain.model.RemoteFile
import com.example.securequicktransferapp.domain.model.TransferItemStatus
import com.example.securequicktransferapp.domain.model.TransferProgress
import com.example.securequicktransferapp.domain.model.TransferQueueItem
import com.example.securequicktransferapp.domain.model.TransferType
import com.example.securequicktransferapp.domain.repo.UsbRepository
import com.example.securequicktransferapp.data.repo.TransferHistoryRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Per-device transfer queue manager that processes transfer items sequentially.
 * 
 * Each device gets its own TransferQueue instance, ensuring:
 * - Sequential processing within a single device (USB is a serial pipe)
 * - Non-blocking UI — user can browse and enqueue more items while transfers run
 * - Individual item cancellation, reordering, and status tracking
 * - Parallel execution across different devices (each device has its own queue)
 */
class TransferQueue(
    private val deviceId: String,
    private val sessionMutex: Mutex,
    private val repository: UsbRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val TAG = "TransferQueue[$deviceId]"

    private val _queue = MutableStateFlow<List<TransferQueueItem>>(emptyList())
    val queue: StateFlow<List<TransferQueueItem>> = _queue.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private var processingJob: Job? = null
    private var currentItemJob: Job? = null

    /**
     * Enqueues a single file/directory for sending (Host → Device).
     */
    fun enqueueSend(file: File, destinationPath: String, isDirectory: Boolean = file.isDirectory) {
        val item = TransferQueueItem(
            type = TransferType.SEND,
            localFile = file,
            destinationPath = destinationPath,
            isDirectory = isDirectory
        )
        _queue.value = _queue.value + item
        println("$TAG Enqueued SEND: ${file.name} → $destinationPath")
        ensureProcessing()
    }

    /**
     * Enqueues multiple files/directories for sending, flattening directories into individual items.
     */
    fun enqueueSendBatch(files: List<File>, destinationPath: String) {
        val items = mutableListOf<TransferQueueItem>()
        fun flatten(file: File, remoteParent: String) {
            if (file.isDirectory) {
                val remoteDir = if (remoteParent.endsWith("/")) remoteParent + file.name else "$remoteParent/${file.name}"
                items.add(TransferQueueItem(
                    type = TransferType.SEND,
                    localFile = file,
                    destinationPath = remoteDir,
                    isDirectory = true
                ))
                file.listFiles()?.forEach { flatten(it, remoteDir) }
            } else {
                items.add(TransferQueueItem(
                    type = TransferType.SEND,
                    localFile = file,
                    destinationPath = remoteParent,
                    isDirectory = false
                ))
            }
        }
        files.forEach { flatten(it, destinationPath) }
        _queue.value = _queue.value + items
        println("$TAG Enqueued SEND batch: ${items.size} items")
        ensureProcessing()
    }

    /**
     * Enqueues a single remote file/directory for fetching (Device → Host).
     */
    fun enqueueFetch(remoteFile: RemoteFile) {
        val downloadDir = File(System.getProperty("user.home"), "Downloads")
        val item = TransferQueueItem(
            type = TransferType.FETCH,
            remoteFile = remoteFile,
            destinationPath = downloadDir.absolutePath,
            isDirectory = remoteFile.isDirectory
        )
        _queue.value = _queue.value + item
        println("$TAG Enqueued FETCH: ${remoteFile.name}")
        ensureProcessing()
    }

    /**
     * Enqueues multiple remote files for fetching.
     */
    fun enqueueFetchBatch(remoteFiles: List<RemoteFile>) {
        val downloadDir = File(System.getProperty("user.home"), "Downloads")
        val items = remoteFiles.map { rf ->
            TransferQueueItem(
                type = TransferType.FETCH,
                remoteFile = rf,
                destinationPath = downloadDir.absolutePath,
                isDirectory = rf.isDirectory
            )
        }
        _queue.value = _queue.value + items
        println("$TAG Enqueued FETCH batch: ${items.size} items")
        ensureProcessing()
    }

    /**
     * Cancels a specific queued item by ID.
     * If it's currently being processed, cancels the active transfer.
     */
    fun cancelItem(itemId: String) {
        val currentQueue = _queue.value
        val item = currentQueue.find { it.id == itemId } ?: return

        if (item.status == TransferItemStatus.ACTIVE) {
            // Cancel the actively running transfer
            currentItemJob?.cancel()
            repository.cancelTransfer()
            updateItem(itemId) { it.copy(status = TransferItemStatus.CANCELLED) }
            println("$TAG Cancelled active transfer: ${item.displayName}")
        } else if (item.status == TransferItemStatus.PENDING) {
            updateItem(itemId) { it.copy(status = TransferItemStatus.CANCELLED) }
            println("$TAG Cancelled pending item: ${item.displayName}")
        }
    }

    /**
     * Cancels all pending and active items in the queue.
     */
    fun cancelAll() {
        currentItemJob?.cancel()
        try { repository.cancelTransfer() } catch (_: Exception) {}
        _queue.value = _queue.value.map { item ->
            when (item.status) {
                TransferItemStatus.PENDING, TransferItemStatus.ACTIVE ->
                    item.copy(status = TransferItemStatus.CANCELLED)
                else -> item
            }
        }
        println("$TAG Cancelled all items")
    }

    /**
     * Removes completed, failed, and cancelled items from the queue.
     */
    fun clearFinished() {
        _queue.value = _queue.value.filter {
            it.status == TransferItemStatus.PENDING || it.status == TransferItemStatus.ACTIVE
        }
    }

    /**
     * Moves an item to the front of the pending queue (priority boost).
     */
    fun moveToFront(itemId: String) {
        val currentQueue = _queue.value.toMutableList()
        val index = currentQueue.indexOfFirst { it.id == itemId && it.status == TransferItemStatus.PENDING }
        if (index <= 0) return
        
        val item = currentQueue.removeAt(index)
        // Insert after the last ACTIVE item (or at position 0)
        val insertPos = currentQueue.indexOfLast { it.status == TransferItemStatus.ACTIVE } + 1
        currentQueue.add(insertPos, item)
        _queue.value = currentQueue
        println("$TAG Moved to front: ${item.displayName}")
    }

    /**
     * Returns counts of items by status.
     */
    fun getCounts(): Triple<Int, Int, Int> {
        val q = _queue.value
        return Triple(
            q.count { it.status == TransferItemStatus.PENDING },
            q.count { it.status == TransferItemStatus.ACTIVE },
            q.count { it.status == TransferItemStatus.COMPLETED }
        )
    }

    // --- Internal Processing Loop ---

    private fun ensureProcessing() {
        if (_isProcessing.value) return
        processingJob = scope.launch {
            _isProcessing.value = true
            try {
                processLoop()
            } finally {
                _isProcessing.value = false
            }
        }
    }

    private suspend fun processLoop() {
        while (true) {
            val nextItem = _queue.value.firstOrNull { it.status == TransferItemStatus.PENDING }
                ?: break // No more pending items

            updateItem(nextItem.id) { it.copy(status = TransferItemStatus.ACTIVE, progress = 0) }

            currentItemJob = scope.launch {
                try {
                    when (nextItem.type) {
                        TransferType.SEND -> processSend(nextItem)
                        TransferType.FETCH -> processFetch(nextItem)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                println("$TAG Transfer cancelled for item ${nextItem.id}")
                updateItem(nextItem.id) { it.copy(status = TransferItemStatus.CANCELLED) }
                TransferHistoryRepository.addEntry(nextItem.copy(status = TransferItemStatus.CANCELLED), deviceId)
            } catch (e: Exception) {
                println("$TAG Error processing item ${nextItem.id}: ${e.message}")
                updateItem(nextItem.id) {
                    it.copy(
                        status = TransferItemStatus.FAILED,
                        error = e.message ?: "Unknown error"
                    )
                }
                TransferHistoryRepository.addEntry(nextItem.copy(status = TransferItemStatus.FAILED, error = e.message ?: "Unknown error"), deviceId)
            }
            }
            currentItemJob?.join()
        }
    }

    private suspend fun processSend(item: TransferQueueItem) {
        val file = item.localFile ?: throw IllegalStateException("No local file for SEND item")

        sessionMutex.withLock {
            if (file.isDirectory) {
                println("$TAG Creating folder: ${item.destinationPath}")
                repository.createFolder(item.destinationPath)
                updateItem(item.id) { it.copy(status = TransferItemStatus.COMPLETED, progress = 100) }
                TransferHistoryRepository.addEntry(item.copy(status = TransferItemStatus.COMPLETED), deviceId)
            } else {
                val startTime = System.currentTimeMillis()
                val fileSize = file.length()
                val totalStr = SecureQtSdk.Utils.formatSize(fileSize)

                updateItem(item.id) { it.copy(
                    startedAt = startTime,
                    total = totalStr,
                    transferred = "0 B",
                    speed = "Calculating...",
                    eta = "Calculating...",
                    elapsed = "0s"
                )}

                println("$TAG Sending: ${file.name} → ${item.destinationPath}")
                repository.sendFile(file, item.destinationPath, isDirectory = false, remoteFileName = item.remoteFileName).collect { progress ->
                    val now = System.currentTimeMillis()
                    val elapsedSec = (now - startTime) / 1000L
                    val transferredBytes = (fileSize * progress) / 100
                    val speedBps = if (elapsedSec > 0) (transferredBytes / elapsedSec) else 0L
                    val etaSec = if (speedBps > 0) ((fileSize - transferredBytes) / speedBps) else 0L

                    updateItem(item.id) { it.copy(
                        progress = progress,
                        speed = SecureQtSdk.Utils.formatSize(speedBps) + "/s",
                        elapsed = SecureQtSdk.Utils.formatTime(elapsedSec),
                        eta = if (speedBps > 0) SecureQtSdk.Utils.formatTime(etaSec) else "Calculating...",
                        transferred = SecureQtSdk.Utils.formatSize(transferredBytes),
                        total = totalStr
                    )}
                }
                val finalItem = item.copy(
                    status = TransferItemStatus.COMPLETED,
                    progress = 100,
                    eta = "Done",
                    total = totalStr,
                    elapsed = SecureQtSdk.Utils.formatTime((System.currentTimeMillis() - startTime) / 1000L)
                )
                updateItem(item.id) { finalItem }
                TransferHistoryRepository.addEntry(finalItem, deviceId)
                println("$TAG Sent successfully: ${file.name}")
            }
        }
    }

    private suspend fun processFetch(item: TransferQueueItem) {
        val remoteFile = item.remoteFile ?: throw IllegalStateException("No remote file for FETCH item")
        val downloadDir = File(item.destinationPath)
        downloadDir.mkdirs()
        val localFile = File(downloadDir, remoteFile.name)

        sessionMutex.withLock {
            val startTime = System.currentTimeMillis()
            val fileSize = remoteFile.size
            val totalStr = if (remoteFile.isDirectory) "Directory" else SecureQtSdk.Utils.formatSize(fileSize)

            updateItem(item.id) { it.copy(
                startedAt = startTime,
                total = totalStr,
                transferred = "0 B",
                speed = "Calculating...",
                eta = "Calculating...",
                elapsed = "0s"
            )}

            println("$TAG Fetching: ${remoteFile.name} (${remoteFile.size} bytes)")
            val flow = if (remoteFile.isDirectory) {
                repository.fetchDirectory(remoteFile.path, localFile)
            } else {
                repository.fetchFile(remoteFile.path, localFile)
            }

            flow.collect { progress ->
                val now = System.currentTimeMillis()
                val elapsedSec = (now - startTime) / 1000L
                val transferredBytes = if (remoteFile.isDirectory) 0L else (fileSize * progress) / 100
                val speedBps = if (elapsedSec > 0 && !remoteFile.isDirectory) (transferredBytes / elapsedSec) else 0L
                val etaSec = if (speedBps > 0) ((fileSize - transferredBytes) / speedBps) else 0L

                updateItem(item.id) { it.copy(
                    progress = progress,
                    speed = if (remoteFile.isDirectory) "Processing..." else SecureQtSdk.Utils.formatSize(speedBps) + "/s",
                    elapsed = SecureQtSdk.Utils.formatTime(elapsedSec),
                    eta = if (speedBps > 0) SecureQtSdk.Utils.formatTime(etaSec) else "Calculating...",
                    transferred = if (remoteFile.isDirectory) "Processing..." else SecureQtSdk.Utils.formatSize(transferredBytes),
                    total = totalStr
                )}
            }
            val finalItem = item.copy(
                status = TransferItemStatus.COMPLETED,
                progress = 100,
                eta = "Done",
                total = totalStr,
                elapsed = SecureQtSdk.Utils.formatTime((System.currentTimeMillis() - startTime) / 1000L)
            )
            updateItem(item.id) { finalItem }
            TransferHistoryRepository.addEntry(finalItem, deviceId)
            println("$TAG Fetched successfully: ${remoteFile.name} → ${localFile.absolutePath}")
        }
    }

    private fun updateItem(itemId: String, transform: (TransferQueueItem) -> TransferQueueItem) {
        _queue.value = _queue.value.map { if (it.id == itemId) transform(it) else it }
    }

    fun shutdown() {
        processingJob?.cancel()
        currentItemJob?.cancel()
    }
}
