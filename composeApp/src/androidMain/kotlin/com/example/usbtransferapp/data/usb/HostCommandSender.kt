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
        usbLogger.i(TAG, "waitForClientReady: Waiting for TYPE_ACK (Ready signal) from remote client...")
        for (i in 0 until 40) { // Try up to 40 times (10 seconds total) for remote command processor startup
            val packet = dataSource.receiveRawPacket()
            if (packet != null && packet.type == Packet.TYPE_ACK) {
                usbLogger.i(TAG, "waitForClientReady: Received TYPE_ACK from client after ${(i + 1) * 250}ms. Client is ready!")
                return@withContext true
            }
            if (packet != null) {
                usbLogger.w(TAG, "waitForClientReady: Ignoring unexpected packet type ${packet.type} during startup")
            }
            delay(250)
        }
        usbLogger.w(TAG, "waitForClientReady: Timed out waiting for TYPE_ACK after 10s")
        false
    }

    suspend fun sendReadyAck(): Boolean = withContext(Dispatchers.IO) {
        dataSource.sendRawPacket(Packet.TYPE_ACK, ByteArray(0))
    }

    suspend fun listRemoteFiles(path: String): List<RemoteFile> = commandMutex.withLock {
        withContext(Dispatchers.IO + NonCancellable) {
            usbLogger.d(TAG, "listRemoteFiles: Requesting directory listing for '$path'")
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

            val countPayload = dataSource.receiveSecure()
            if (countPayload == null || countPayload.size < 4) {
                usbLogger.e(TAG, "listRemoteFiles: Failed to receive item count response for CMD_LIST")
                return@withContext emptyList()
            }

            val count = ByteBuffer.wrap(countPayload).int
            usbLogger.i(TAG, "listRemoteFiles: Remote reported $count items inside '$path'")

            val result = mutableListOf<RemoteFile>()
            for (i in 0 until count) {
                val itemPayload = dataSource.receiveSecure() ?: run {
                    usbLogger.e(TAG, "listRemoteFiles: Connection lost while reading item $i of $count")
                    break
                }
                val buffer = ByteBuffer.wrap(itemPayload)
                if (buffer.remaining() < 1 + 8 + 4) {
                    usbLogger.w(TAG, "listRemoteFiles: Item $i payload too short (${itemPayload.size} bytes)")
                    continue
                }
                val isDir = buffer.get().toInt() == 1
                val size = buffer.long
                val nameLen = buffer.int
                if (buffer.remaining() < nameLen) {
                    usbLogger.w(TAG, "listRemoteFiles: Item $i name payload truncated ($nameLen bytes requested, ${buffer.remaining()} remaining)")
                    continue
                }
                val nameBytes = ByteArray(nameLen)
                buffer.get(nameBytes)
                val name = String(nameBytes, Charsets.UTF_8)
                val fullPath = if (path.endsWith("/")) "$path$name" else "$path/$name"
                result.add(RemoteFile(name = name, isDirectory = isDir, size = size, path = fullPath))
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

                val rawChannel = Channel<ByteArray>(2)
                val encryptedChannel = Channel<ByteArray>(2)
                val totalSize = localFile.length()
                var sentBytes = 0L

                val readJob = launch(Dispatchers.IO) {
                    try {
                        FileInputStream(localFile).use { fis ->
                            val buffer = ByteArray(CHUNK_SIZE)
                            while (isActive) {
                                val bytesRead = fis.read(buffer)
                                if (bytesRead == -1) break
                                rawChannel.send(if (bytesRead == CHUNK_SIZE) buffer.clone() else buffer.copyOf(bytesRead))
                            }
                        }
                    } finally {
                        rawChannel.close()
                    }
                }

                val encryptJob = launch(Dispatchers.Default) {
                    try {
                        for (rawChunk in rawChannel) {
                            val encryptedChunk = dataSource.encryptData(rawChunk) ?: break
                            encryptedChannel.send(encryptedChunk)
                        }
                    } finally {
                        encryptedChannel.close()
                    }
                }

                var success = true
                for (encryptedChunk in encryptedChannel) {
                    if (!dataSource.sendRawPacket(Packet.TYPE_DATA, encryptedChunk)) {
                        success = false
                        break
                    }
                    sentBytes += CHUNK_SIZE
                    onProgress(if (totalSize > 0) (sentBytes.toFloat() / totalSize).coerceIn(0f, 1f) else 1f)
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

                val sizePayload = dataSource.receiveSecure()
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

                val encryptedChannel = Channel<ByteArray>(2)
                val decryptedChannel = Channel<ByteArray>(2)
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
                FileOutputStream(targetFile).use { fos ->
                    for (chunk in decryptedChannel) {
                        fos.write(chunk)
                        receivedBytes += chunk.size
                        onProgress(if (fileSize > 0) (receivedBytes.toFloat() / fileSize).coerceIn(0f, 1f) else 1f)
                    }
                }

                if (!success) {
                    if (targetFile.exists()) targetFile.delete()
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
            val resp = dataSource.receiveSecure()
            resp != null && resp.isNotEmpty() && resp[0].toInt() == 1
        }
    }

    suspend fun renameRemote(oldPath: String, newPath: String): Boolean = commandMutex.withLock {
        withContext(Dispatchers.IO + NonCancellable) {
            val oldBytes = oldPath.toByteArray(Charsets.UTF_8)
            val newBytes = newPath.toByteArray(Charsets.UTF_8)
            val payload = ByteBuffer.allocate(1 + 4 + oldBytes.size + 4 + newBytes.size)
                .put(CMD_RENAME)
                .putInt(oldBytes.size)
                .put(oldBytes)
                .putInt(newBytes.size)
                .put(newBytes)
                .array()

            if (!dataSource.sendSecure(payload)) return@withContext false
            val resp = dataSource.receiveSecure()
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
        send(100)
    }

    override suspend fun listDirectory(path: String): List<RemoteFile> = listRemoteFiles(path)

    override suspend fun deleteFile(remotePath: String): Boolean = deleteRemote(remotePath)

    override suspend fun renameFile(remotePath: String, newName: String): Boolean = renameRemote(remotePath, newName)

    override fun cancelTransfer() {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { dataSource.sendRawPacket(Packet.TYPE_CANCEL, ByteArray(0)) }
    }

    override fun checkPhysicalConnection(): Pair<Boolean, String?> = Pair(usbManagerWrapper.isUsbCablePhysicallyConnected(), null)
}
