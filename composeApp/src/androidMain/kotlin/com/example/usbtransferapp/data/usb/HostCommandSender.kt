package com.example.usbtransferapp.data.usb

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.usbtransferapp.data.Packet
import com.example.usbtransferapp.domain.model.RemoteFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HostCommandSender @Inject constructor(
    private val dataSource: UsbDataSource,
    private val usbManagerWrapper: UsbManagerWrapper,
    @ApplicationContext private val context: Context,
    private val usbLogger: com.example.usbtransferapp.data.logging.UsbLogger
) : com.example.usbtransferapp.domain.repo.UsbRepository {
    companion object {
        private const val TAG = "HostCommandSender"
        private const val CMD_LIST: Byte = 0
        private const val CMD_SEND_FILE: Byte = 1
        private const val CMD_FETCH_FILE: Byte = 2
        private const val CMD_FETCH_DIR: Byte = 3
        private const val CMD_DISCONNECT: Byte = 4
        private const val CMD_SEND_DIR: Byte = 5
        private const val CMD_DELETE: Byte = 6
        private const val CMD_RENAME: Byte = 7
        private const val CHUNK_SIZE = 256 * 1024
    }

    private val commandMutex = Mutex()

    suspend fun waitForClientReady(): Boolean = withContext(Dispatchers.IO) {
        usbLogger.i(TAG, "waitForClientReady: Checking if remote device is in Client mode (waiting for TYPE_ACK up to 60s)...")
        for (i in 0 until 240) { // Try up to 240 times (60 seconds total) for remote device to enter Client mode and accept permission
            if (i % 2 == 0) {
                try {
                    dataSource.sendRawPacket(Packet.TYPE_ACK, ByteArray(0))
                } catch (e: Exception) {
                    usbLogger.w(TAG, "waitForClientReady: Failed to send ACK ping: ${e.message}")
                }
            }
            val packet = dataSource.receiveRawPacket()
            if (packet != null && packet.type == Packet.TYPE_ACK) {
                usbLogger.i(TAG, "waitForClientReady: Received TYPE_ACK from client after ${(i + 1) * 250}ms. Remote device confirmed in Client mode & ready!")
                return@withContext true
            }
            if (packet != null && packet.type == Packet.TYPE_DATA) {
                usbLogger.w(TAG, "waitForClientReady: Discarding stale TYPE_DATA packet (${packet.length} bytes) received before commands started")
            }
            if (packet != null && packet.type != Packet.TYPE_ACK && packet.type != Packet.TYPE_DATA) {
                usbLogger.w(TAG, "waitForClientReady: Ignoring unexpected packet type ${packet.type} during client status check")
            }
            if (i > 0 && i % 8 == 0) {
                usbLogger.i(TAG, "waitForClientReady: Checking remote status (${(i * 250) / 1000}s/60s)... Ensure 'Client' mode is selected and permission accepted on the other device.")
            }
            delay(250)
        }
        usbLogger.e(TAG, "waitForClientReady: Timed out after 60s waiting for remote device to enter Client mode.")
        false
    }

    suspend fun sendReadyAck(): Boolean = withContext(Dispatchers.IO) {
        dataSource.sendRawPacket(Packet.TYPE_ACK, ByteArray(0))
    }

    suspend fun listRemoteFiles(path: String): List<RemoteFile> = commandMutex.withLock {
        withContext(Dispatchers.IO) {
            usbLogger.i(TAG, "listRemoteFiles: Requesting directory listing for '$path'")
            val pathBytes = path.toByteArray(Charsets.UTF_8)
            val payload = ByteBuffer.allocate(1 + 4 + pathBytes.size)
                .put(CMD_LIST)
                .putInt(pathBytes.size)
                .put(pathBytes)
                .array()

            if (!dataSource.sendSecure(payload)) {
                usbLogger.e(TAG, "listRemoteFiles: Failed to send CMD_LIST")
                return@withContext emptyList()
            }

            val countData = withTimeoutOrNull(20000) { dataSource.receiveSecure() }
            if (countData == null || countData.size < 4) {
                usbLogger.e(TAG, "listRemoteFiles: Failed to receive directory count.")
                return@withContext emptyList()
            }
            val count = ByteBuffer.wrap(countData).int
            val result = mutableListOf<RemoteFile>()

            if (countData.size > 4 || count < 0 || count > 100000) {
                usbLogger.w(TAG, "listRemoteFiles: Received directory item payload instead of count packet (${countData.size} bytes, count=$count). Reading items dynamically with timeout...")
                val firstBuffer = ByteBuffer.wrap(countData)
                if (firstBuffer.remaining() >= 1 + 8 + 4) {
                    try {
                        val isDir = firstBuffer.get().toInt() == 1
                        val size = firstBuffer.long
                        val nameLen = firstBuffer.int
                        if (firstBuffer.remaining() >= nameLen) {
                            val nameBytes = ByteArray(nameLen)
                            firstBuffer.get(nameBytes)
                            val name = String(nameBytes, Charsets.UTF_8)
                            val fullPath = if (path.endsWith("/")) "$path$name" else "$path/$name"
                            result.add(RemoteFile(name = name, isDirectory = isDir, size = size, path = fullPath))
                        }
                    } catch (e: Exception) {
                        usbLogger.w(TAG, "listRemoteFiles: Could not parse first item: ${e.message}")
                    }
                }
                while (isActive) {
                    val itemData = withTimeoutOrNull(2500) { dataSource.receiveSecure() } ?: break
                    val buffer = ByteBuffer.wrap(itemData)
                    if (buffer.remaining() < 1 + 8 + 4) break
                    val isDir = buffer.get().toInt() == 1
                    val size = buffer.long
                    val nameLen = buffer.int
                    if (buffer.remaining() < nameLen) break
                    val nameBytes = ByteArray(nameLen)
                    buffer.get(nameBytes)
                    val name = String(nameBytes, Charsets.UTF_8)
                    val fullPath = if (path.endsWith("/")) "$path$name" else "$path/$name"
                    result.add(RemoteFile(name = name, isDirectory = isDir, size = size, path = fullPath))
                }
            } else {
                usbLogger.i(TAG, "listRemoteFiles: Discovered $count items inside '$path'.")
                for (i in 0 until count) {
                    val itemData = withTimeoutOrNull(10000) { dataSource.receiveSecure() } ?: run {
                        usbLogger.e(TAG, "listRemoteFiles: Connection lost while reading directory item $i of $count")
                        break
                    }
                    val buffer = ByteBuffer.wrap(itemData)
                    if (buffer.remaining() < 1 + 8 + 4) {
                        usbLogger.w(TAG, "listRemoteFiles: Item $i payload too short (${buffer.remaining()} bytes)")
                        break
                    }
                    val isDir = buffer.get().toInt() == 1
                    val size = buffer.long
                    val nameLen = buffer.int
                    if (buffer.remaining() < nameLen) {
                        usbLogger.w(TAG, "listRemoteFiles: Item $i name payload truncated ($nameLen requested, ${buffer.remaining()} remaining)")
                        break
                    }
                    val nameBytes = ByteArray(nameLen)
                    buffer.get(nameBytes)
                    val name = String(nameBytes, Charsets.UTF_8)
                    val fullPath = if (path.endsWith("/")) "$path$name" else "$path/$name"
                    result.add(RemoteFile(name = name, isDirectory = isDir, size = size, path = fullPath))
                }
            }

            usbLogger.i(TAG, "listRemoteFiles: Successfully listed ${result.size} items in '$path'")
            result
        }
    }

    suspend fun sendFile(localFile: File, remotePath: String, onProgress: suspend (Float) -> Unit = {}): Boolean = commandMutex.withLock {
        withContext(Dispatchers.IO + NonCancellable) {
            coroutineScope {
                usbLogger.i(TAG, "sendFile: Sending '${localFile.name}' (${localFile.length()} bytes) to '$remotePath'")
                if (!localFile.exists() || !localFile.isFile) return@coroutineScope false

                val fullRemotePath = if (remotePath.endsWith("/")) remotePath + localFile.name else "$remotePath/${localFile.name}"
                val fileNameBytes = fullRemotePath.toByteArray(Charsets.UTF_8)
                val header = ByteBuffer.allocate(1 + 4 + fileNameBytes.size + 8)
                    .put(CMD_SEND_FILE)
                    .putInt(fileNameBytes.size)
                    .put(fileNameBytes)
                    .putLong(localFile.length())
                    .array()

                if (!dataSource.sendSecure(header)) {
                    usbLogger.e(TAG, "sendFile: Failed to send CMD_SEND_FILE header")
                    return@coroutineScope false
                }

                val rawChannel = Channel<ByteArray>(32)
                val encryptedChannel = Channel<ByteArray>(32)
                val totalSize = localFile.length()
                var sentBytes = 0L

                val readJob = launch(Dispatchers.IO) {
                    try {
                        val fis: java.io.InputStream = if (SmartNavStorageResolver.isSmartNavPath(localFile.absolutePath)) {
                            SmartNavStorageResolver.openInputStream(context, localFile.absolutePath) ?: FileInputStream(localFile)
                        } else {
                            FileInputStream(localFile)
                        }
                        fis.use { stream ->
                            while (isActive) {
                                val buffer = ByteArray(CHUNK_SIZE)
                                val bytesRead = stream.read(buffer)
                                if (bytesRead == -1) break
                                rawChannel.send(if (bytesRead == CHUNK_SIZE) buffer else buffer.copyOf(bytesRead))
                            }
                        }
                    } finally {
                        rawChannel.close()
                    }
                }

                val encryptJob = launch(Dispatchers.Default) {
                    try {
                        for (rawChunk in rawChannel) {
                            val encryptedChunk = dataSource.encryptAndWrapData(rawChunk) ?: break
                            encryptedChannel.send(encryptedChunk)
                        }
                    } finally {
                        encryptedChannel.close()
                    }
                }

                var success = true
                var lastSentPercent = -1
                var lastSentTime = 0L
                for (encryptedChunk in encryptedChannel) {
                    if (!dataSource.sendPrebuiltPacket(encryptedChunk)) {
                        success = false
                        break
                    }
                    sentBytes += CHUNK_SIZE
                    val progress = if (totalSize > 0) (sentBytes.toFloat() / totalSize).coerceIn(0f, 1f) else 1f
                    val percent = (progress * 100).toInt()
                    val now = System.currentTimeMillis()
                    if (now - lastSentTime > 250L || sentBytes >= totalSize) {
                        lastSentPercent = percent
                        lastSentTime = now
                        onProgress(progress)
                    }
                }

                if (success) {
                    dataSource.sendRawPacket(Packet.TYPE_EOF, ByteArray(0))
                    usbLogger.i(TAG, "sendFile: Transfer completed successfully.")
                } else {
                    dataSource.sendRawPacket(Packet.TYPE_CANCEL, ByteArray(0))
                    usbLogger.e(TAG, "sendFile: Transfer failed during data stream.")
                }

                success
            }
        }
    }

    suspend fun fetchFile(remotePath: String, localSaveDir: File, onProgress: suspend (Float) -> Unit = {}): Boolean = commandMutex.withLock {
        withContext(Dispatchers.IO + NonCancellable) {
            coroutineScope {
                usbLogger.i(TAG, "fetchFile: Fetching '$remotePath' to '${localSaveDir.absolutePath}'")
                val pathBytes = remotePath.toByteArray(Charsets.UTF_8)
                val header = ByteBuffer.allocate(1 + 4 + pathBytes.size)
                    .put(CMD_FETCH_FILE)
                    .putInt(pathBytes.size)
                    .put(pathBytes)
                    .array()

                if (!dataSource.sendSecure(header)) {
                    usbLogger.e(TAG, "fetchFile: Failed to send CMD_FETCH_FILE header")
                    return@coroutineScope false
                }

                val sizePayload = withTimeoutOrNull(20000) { dataSource.receiveSecure() }
                if (sizePayload == null || sizePayload.size < 8) {
                    usbLogger.e(TAG, "fetchFile: Failed to receive file size response")
                    return@coroutineScope false
                }
                val fileSize = ByteBuffer.wrap(sizePayload).long
                val fileName = remotePath.substringAfterLast('/')
                val targetFile = if (localSaveDir.isDirectory || localSaveDir.name != fileName) {
                    File(localSaveDir, fileName)
                } else {
                    localSaveDir
                }

                val encryptedChannel = Channel<ByteArray>(32)
                val decryptedChannel = Channel<ByteArray>(32)
                var receivedBytes = 0L
                var success = true

                val readJob = launch(Dispatchers.IO) {
                    try {
                        while (isActive) {
                            val packet = dataSource.receiveRawPacket() ?: break
                            if (packet.type == Packet.TYPE_EOF) break
                            if (packet.type == Packet.TYPE_CANCEL) {
                                success = false
                                break
                            }
                            if (packet.type == Packet.TYPE_DATA) {
                                encryptedChannel.send(packet.payload)
                            }
                        }
                    } finally {
                        encryptedChannel.close()
                    }
                }

                val decryptJob = launch(Dispatchers.Default) {
                    try {
                        for (encryptedChunk in encryptedChannel) {
                            val decrypted = dataSource.decryptData(encryptedChunk)
                            if (decrypted != null) {
                                decryptedChannel.send(decrypted)
                            } else {
                                success = false
                                readJob.cancel()
                                break
                            }
                        }
                    } finally {
                        decryptedChannel.close()
                    }
                }

                if (!targetFile.parentFile?.exists()!!) targetFile.parentFile?.mkdirs()
                val fos: java.io.OutputStream = if (SmartNavStorageResolver.isSmartNavPath(targetFile.absolutePath)) {
                    SmartNavStorageResolver.openOutputStream(context, targetFile.absolutePath) ?: FileOutputStream(targetFile)
                } else {
                    FileOutputStream(targetFile)
                }
                var lastRecvPercent = -1
                var lastRecvTime = 0L
                fos.use { stream ->
                    for (chunk in decryptedChannel) {
                        stream.write(chunk)
                        receivedBytes += chunk.size
                        val progress = if (fileSize > 0) (receivedBytes.toFloat() / fileSize).coerceIn(0f, 1f) else 1f
                        val percent = (progress * 100).toInt()
                        val now = System.currentTimeMillis()
                        if (now - lastRecvTime > 250L || receivedBytes >= fileSize) {
                            lastRecvPercent = percent
                            lastRecvTime = now
                            onProgress(progress)
                        }
                    }
                }

                if (!success) {
                    if (targetFile.exists()) targetFile.delete()
                }
                success
            }
        }
    }

    suspend fun fetchRemoteDirectory(remotePath: String, localSaveDir: File, onProgress: suspend (Float) -> Unit = {}): Boolean = commandMutex.withLock {
        withContext(Dispatchers.IO + NonCancellable) {
            coroutineScope {
                usbLogger.i(TAG, "fetchRemoteDirectory: Fetching directory '$remotePath' as ZIP to '${localSaveDir.absolutePath}'")
                val pathBytes = remotePath.toByteArray(Charsets.UTF_8)
                val header = ByteBuffer.allocate(1 + 4 + pathBytes.size)
                    .put(CMD_FETCH_DIR)
                    .putInt(pathBytes.size)
                    .put(pathBytes)
                    .array()

                if (!dataSource.sendSecure(header)) {
                    usbLogger.e(TAG, "fetchRemoteDirectory: Failed to send CMD_FETCH_DIR header")
                    return@coroutineScope false
                }

                val sizePayload = withTimeoutOrNull(30000) { dataSource.receiveSecure() }
                if (sizePayload == null || sizePayload.size < 8) {
                    usbLogger.e(TAG, "fetchRemoteDirectory: Failed to receive directory ZIP size response")
                    return@coroutineScope false
                }
                val zipSize = ByteBuffer.wrap(sizePayload).long
                if (zipSize <= 0L) {
                    usbLogger.e(TAG, "fetchRemoteDirectory: Remote returned invalid ZIP size ($zipSize)")
                    return@coroutineScope false
                }

                val dirName = remotePath.substringAfterLast('/')
                val targetZip = if (localSaveDir.isDirectory || !localSaveDir.name.endsWith(".zip")) {
                    File(localSaveDir, "$dirName.zip")
                } else {
                    localSaveDir
                }

                val encryptedChannel = Channel<ByteArray>(32)
                val decryptedChannel = Channel<ByteArray>(32)
                var receivedBytes = 0L
                var success = true

                val readJob = launch(Dispatchers.IO) {
                    try {
                        while (isActive) {
                            val packet = dataSource.receiveRawPacket() ?: break
                            if (packet.type == Packet.TYPE_EOF) break
                            if (packet.type == Packet.TYPE_CANCEL) {
                                success = false
                                break
                            }
                            if (packet.type == Packet.TYPE_DATA) {
                                encryptedChannel.send(packet.payload)
                            }
                        }
                    } finally {
                        encryptedChannel.close()
                    }
                }

                val decryptJob = launch(Dispatchers.Default) {
                    try {
                        for (encryptedChunk in encryptedChannel) {
                            val decrypted = dataSource.decryptData(encryptedChunk)
                            if (decrypted != null) {
                                decryptedChannel.send(decrypted)
                            } else {
                                success = false
                                readJob.cancel()
                                break
                            }
                        }
                    } finally {
                        decryptedChannel.close()
                    }
                }

                if (!targetZip.parentFile?.exists()!!) targetZip.parentFile?.mkdirs()
                var lastZipPercent = -1
                var lastZipTime = 0L
                FileOutputStream(targetZip).use { fos ->
                    for (chunk in decryptedChannel) {
                        fos.write(chunk)
                        receivedBytes += chunk.size
                        val progress = (receivedBytes.toFloat() / zipSize).coerceIn(0f, 1f)
                        val percent = (progress * 100).toInt()
                        val now = System.currentTimeMillis()
                        if (now - lastZipTime > 250L || receivedBytes >= zipSize) {
                            lastZipPercent = percent
                            lastZipTime = now
                            onProgress(progress)
                        }
                    }
                }

                if (!success) {
                    if (targetZip.exists()) targetZip.delete()
                } else {
                    usbLogger.i(TAG, "fetchRemoteDirectory: Successfully saved '$dirName.zip' (${targetZip.length()} bytes)")
                }
                success
            }
        }
    }

    suspend fun deleteRemote(remotePath: String): Boolean = commandMutex.withLock {
        withContext(Dispatchers.IO + NonCancellable) {
            val pathBytes = remotePath.toByteArray(Charsets.UTF_8)
            val payload = ByteBuffer.allocate(1 + 4 + pathBytes.size)
                .put(CMD_DELETE)
                .putInt(pathBytes.size)
                .put(pathBytes)
                .array()

            if (!dataSource.sendSecure(payload)) return@withContext false
            val resp = withTimeoutOrNull(10000) { dataSource.receiveSecure() }
            resp != null && resp.isNotEmpty() && resp[0].toInt() == 1
        }
    }

    suspend fun renameRemote(remotePath: String, newName: String): Boolean = commandMutex.withLock {
        withContext(Dispatchers.IO + NonCancellable) {
            val pathBytes = remotePath.toByteArray(Charsets.UTF_8)
            val nameBytes = newName.toByteArray(Charsets.UTF_8)
            val payload = ByteBuffer.allocate(1 + 4 + pathBytes.size + 4 + nameBytes.size)
                .put(CMD_RENAME)
                .putInt(pathBytes.size)
                .put(pathBytes)
                .putInt(nameBytes.size)
                .put(nameBytes)
                .array()

            if (!dataSource.sendSecure(payload)) return@withContext false
            val resp = withTimeoutOrNull(10000) { dataSource.receiveSecure() }
            resp != null && resp.isNotEmpty() && resp[0].toInt() == 1
        }
    }

    suspend fun sendDisconnect() = withContext(Dispatchers.IO) {
        try {
            dataSource.sendSecure(byteArrayOf(CMD_DISCONNECT))
        } catch (e: Exception) {
            usbLogger.w(TAG, "Failed to send disconnect command", e)
        }
    }

    override val isAoaMode: Boolean get() = false

    override fun connect(): Boolean = true

    override fun disconnect() {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { sendDisconnect() }
        dataSource.disconnect()
    }

    override fun receiveStream(): kotlinx.coroutines.flow.Flow<ByteArray> = kotlinx.coroutines.flow.channelFlow {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val packet = dataSource.receiveRawPacket() ?: break
            if (packet.type == Packet.TYPE_DATA) {
                send(packet.payload)
            } else if (packet.type == Packet.TYPE_EOF) {
                break
            }
        }
    }

    override fun sendFile(file: File, destinationPath: String, isDirectory: Boolean, remoteFileName: String): kotlinx.coroutines.flow.Flow<Int> = kotlinx.coroutines.flow.channelFlow {
        sendFile(file, destinationPath) { prog ->
            send((prog * 100).toInt())
        }
    }

    override fun fetchFile(remotePath: String, localFile: File): kotlinx.coroutines.flow.Flow<Int> = kotlinx.coroutines.flow.channelFlow {
        fetchFile(remotePath, localFile) { prog ->
            send((prog * 100).toInt())
        }
    }

    override fun fetchDirectory(remotePath: String, localFile: File): kotlinx.coroutines.flow.Flow<Int> = kotlinx.coroutines.flow.channelFlow {
        fetchRemoteDirectory(remotePath, localFile) { prog ->
            send((prog * 100).toInt())
        }
    }

    override suspend fun listDirectory(path: String): List<RemoteFile> = listRemoteFiles(path)

    override suspend fun deleteFile(remotePath: String): Boolean = deleteRemote(remotePath)

    override suspend fun renameFile(remotePath: String, newName: String): Boolean = renameRemote(remotePath, newName)

    override fun cancelTransfer() {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { dataSource.sendRawPacket(Packet.TYPE_CANCEL, ByteArray(0)) }
    }

    override fun checkPhysicalConnection(): Pair<Boolean, String?> = Pair(usbManagerWrapper.isUsbCablePhysicallyConnected(), null)
}
