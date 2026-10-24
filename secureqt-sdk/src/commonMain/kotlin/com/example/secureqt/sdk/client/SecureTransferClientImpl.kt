package com.example.secureqt.sdk.client

import com.example.secureqt.sdk.SecureQtSdk
import com.example.secureqt.sdk.channel.SecureChannel
import com.example.secureqt.sdk.model.SdkRemoteFile
import com.example.secureqt.sdk.protocol.CommandPayloadBuilder
import com.example.secureqt.sdk.protocol.StandardCommandIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

class SecureTransferClientImpl(private val channel: SecureChannel) : ISecureTransferClient {
    @Volatile private var isCancelled = false

    private val commandMutex = Mutex()
    private val CHUNK_SIZE = 256 * 1024

    override suspend fun listRemoteFiles(path: String): List<SdkRemoteFile> = commandMutex.withLock {
        withContext(Dispatchers.IO) {
            val result = mutableListOf<SdkRemoteFile>()
            val payload = CommandPayloadBuilder.buildWithString(StandardCommandIds.CMD_LIST_DIR, path)

            if (!channel.sendSecure(payload)) return@withContext emptyList()

            val countData = channel.receiveSecure() ?: return@withContext emptyList()
            if (countData.size == 4) {
                val count = ByteBuffer.wrap(countData).int
                for (i in 0 until count) {
                    val itemData = channel.receiveSecure() ?: break
                    val buffer = ByteBuffer.wrap(itemData)
                    if (buffer.remaining() < 1 + 8 + 4) break
                    val isDir = buffer.get().toInt() == 1
                    val size = buffer.long
                    val nameLen = buffer.int
                    if (buffer.remaining() < nameLen) break
                    val nameBytes = ByteArray(nameLen)
                    buffer.get(nameBytes)
                    val name = String(nameBytes, Charsets.UTF_8)
                    result.add(SdkRemoteFile(name, isDir, size, 0))
                }
            }
            result
        }
    }

    override suspend fun sendFile(localFile: File, remotePath: String, remoteFileName: String?, onProgress: ((Long, Long) -> Unit)?): Boolean = commandMutex.withLock {
        isCancelled = false
        withContext(Dispatchers.IO + NonCancellable) {
            if (!localFile.exists() || !localFile.isFile) return@withContext false

            val finalFileName = remoteFileName ?: localFile.name
            val fullRemotePath = if (remotePath.endsWith("/")) remotePath + finalFileName else "$remotePath/$finalFileName"
            val header = CommandPayloadBuilder.buildFileStart(
                StandardCommandIds.CMD_SEND_FILE_START,
                fullRemotePath,
                localFile.length()
            )

            if (!channel.sendSecure(header)) return@withContext false

            val rawChannel = Channel<ByteArray>(32)
            val encryptedChannel = Channel<ByteArray>(32)
            val totalSize = localFile.length()
            var sentBytes = 0L

            val readJob = launch(Dispatchers.IO) {
                try {
                    FileInputStream(localFile).use { stream ->
                        while (isActive) {
                            if (isCancelled) break
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
                        val encryptedChunk = channel.encryptAndWrapData(rawChunk) ?: break
                        encryptedChannel.send(encryptedChunk)
                    }
                } finally {
                    encryptedChannel.close()
                }
            }

            var success = true
            var lastSentTime = 0L
            for (encryptedChunk in encryptedChannel) {
                if (isCancelled) { success = false; break }
                if (!channel.sendPrebuiltPacket(encryptedChunk)) {
                    success = false
                    break
                }
                
                // Exact original size: Total packet size minus header (5) and encryption overhead (28)
                val originalChunkSize = encryptedChunk.size - 33
                sentBytes += originalChunkSize 
                
                val now = System.currentTimeMillis()
                if (now - lastSentTime > 250L || sentBytes >= totalSize) {
                    lastSentTime = now
                    onProgress?.invoke(sentBytes, totalSize)
                }
            }
            
            readJob.cancel()
            encryptJob.cancel()

            if (success) {
                channel.sendRawPacket(SecureQtSdk.Packets.TYPE_EOF, ByteArray(0))
            } else {
                channel.sendRawPacket(SecureQtSdk.Packets.TYPE_CANCEL, ByteArray(0))
            }

            success
        }
    }

    override suspend fun sendDirectory(localDir: File, remotePath: String, onProgress: ((Long, Long) -> Unit)?): Boolean = commandMutex.withLock {
        isCancelled = false
        withContext(Dispatchers.IO + NonCancellable) {
            if (!localDir.exists() || !localDir.isDirectory) return@withContext false

            val fullRemotePath = if (remotePath.endsWith("/")) remotePath + localDir.name else "$remotePath/${localDir.name}"
            // Note: total size of ZIP stream is unknown beforehand, we send 0
            val header = CommandPayloadBuilder.buildFileStart(
                StandardCommandIds.CMD_SEND_DIR_ZIP_START,
                fullRemotePath,
                0L
            )

            if (!channel.sendSecure(header)) return@withContext false

            val rawChannel = Channel<ByteArray>(32)
            val encryptedChannel = Channel<ByteArray>(32)

            val readJob = launch(Dispatchers.IO) {
                try {
                    val pipedIn = java.io.PipedInputStream(CHUNK_SIZE)
                    val pipedOut = java.io.PipedOutputStream(pipedIn)
                    
                    launch(Dispatchers.IO) {
                        try {
                            java.util.zip.ZipOutputStream(pipedOut).use { zos ->
                                localDir.walkTopDown().forEach { file ->
                                    val zipFileName = file.absolutePath.substring(localDir.absolutePath.length).removePrefix("/")
                                    if (zipFileName.isEmpty()) return@forEach
                                    val entry = java.util.zip.ZipEntry(if (file.isDirectory) "$zipFileName/" else zipFileName)
                                    zos.putNextEntry(entry)
                                    if (file.isFile) {
                                        FileInputStream(file).use { it.copyTo(zos) }
                                    }
                                    zos.closeEntry()
                                }
                            }
                        } finally {
                            pipedOut.close()
                        }
                    }

                    while (isActive) {
                        if (isCancelled) break
                        val buffer = ByteArray(CHUNK_SIZE)
                        val bytesRead = pipedIn.read(buffer)
                        if (bytesRead == -1) break
                        rawChannel.send(if (bytesRead == CHUNK_SIZE) buffer else buffer.copyOf(bytesRead))
                    }
                } finally {
                    rawChannel.close()
                }
            }

            val encryptJob = launch(Dispatchers.Default) {
                try {
                    for (rawChunk in rawChannel) {
                        val encryptedChunk = channel.encryptAndWrapData(rawChunk) ?: break
                        encryptedChannel.send(encryptedChunk)
                    }
                } finally {
                    encryptedChannel.close()
                }
            }

            var success = true
            var sentBytes = 0L
            var lastSentTime = 0L
            for (encryptedChunk in encryptedChannel) {
                if (isCancelled) { success = false; break }
                if (!channel.sendPrebuiltPacket(encryptedChunk)) {
                    success = false
                    break
                }
                
                val originalChunkSize = encryptedChunk.size - 33
                sentBytes += originalChunkSize
                
                val now = System.currentTimeMillis()
                if (now - lastSentTime > 250L) {
                    lastSentTime = now
                    onProgress?.invoke(sentBytes, 0L)
                }
            }
            
            readJob.cancel()
            encryptJob.cancel()

            if (success) {
                channel.sendRawPacket(SecureQtSdk.Packets.TYPE_EOF, ByteArray(0))
            } else {
                channel.sendRawPacket(SecureQtSdk.Packets.TYPE_CANCEL, ByteArray(0))
            }
            success
        }
    }

    override suspend fun fetchFile(remotePath: String, localSaveDir: File, onProgress: ((Long, Long) -> Unit)?): Boolean = commandMutex.withLock {
        isCancelled = false
        withContext(Dispatchers.IO + NonCancellable) {
            val header = CommandPayloadBuilder.buildWithString(StandardCommandIds.CMD_FETCH_FILE_START, remotePath)

            if (!channel.sendSecure(header)) return@withContext false

            val fileInfoData = channel.receiveSecure() ?: return@withContext false
            if (fileInfoData.size < 8) return@withContext false // Must have at least 8 bytes for size
            val buffer = ByteBuffer.wrap(fileInfoData)
            val totalSize = buffer.long
            val fileName = remotePath.substringAfterLast('/')

            if (!localSaveDir.exists()) localSaveDir.mkdirs()
            val saveFile = File(localSaveDir, fileName)

            var receivedBytes = 0L
            val rawPacketChannel = Channel<ByteArray>(32)
            val decryptedChannel = Channel<ByteArray>(32)
            var success = true

            val receiveJob = launch(Dispatchers.IO) {
                try {
                    while (isActive) {
                        if (isCancelled) { success = false; break }
                        val packet = channel.receiveRawPacket() ?: break
                        if (packet.first == SecureQtSdk.Packets.TYPE_EOF) break
                        if (packet.first == SecureQtSdk.Packets.TYPE_CANCEL) {
                            success = false
                            break
                        }
                        if (packet.first == SecureQtSdk.Packets.TYPE_DATA) {
                            rawPacketChannel.send(packet.second)
                        }
                    }
                } finally {
                    rawPacketChannel.close()
                }
            }

            val decryptJob = launch(Dispatchers.Default) {
                try {
                    for (rawChunk in rawPacketChannel) {
                        val decrypted = channel.decryptData(rawChunk) ?: break
                        decryptedChannel.send(decrypted)
                    }
                } finally {
                    decryptedChannel.close()
                }
            }

            var lastReportTime = 0L
            FileOutputStream(saveFile).use { fos ->
                for (chunk in decryptedChannel) {
                    fos.write(chunk)
                    receivedBytes += chunk.size
                    val now = System.currentTimeMillis()
                    if (now - lastReportTime > 250L || receivedBytes >= totalSize) {
                        lastReportTime = now
                        onProgress?.invoke(receivedBytes, totalSize)
                    }
                }
            }

            if (!success && saveFile.exists()) saveFile.delete()
            receiveJob.cancel()
            decryptJob.cancel()
            success
        }
    }

    override suspend fun fetchRemoteDirectory(remotePath: String, localSaveDir: File, onProgress: ((Long, Long) -> Unit)?): Boolean = commandMutex.withLock {
        isCancelled = false
        withContext(Dispatchers.IO + NonCancellable) {
            val header = CommandPayloadBuilder.buildWithString(StandardCommandIds.CMD_FETCH_DIR_ZIP_START, remotePath)

            if (!channel.sendSecure(header)) return@withContext false

            val fileInfoData = channel.receiveSecure() ?: return@withContext false
            if (fileInfoData.size < 8) return@withContext false
            val buffer = ByteBuffer.wrap(fileInfoData)
            val totalSize = buffer.long
            val dirName = remotePath.substringAfterLast('/')

            if (!localSaveDir.exists()) localSaveDir.mkdirs()
            val targetDir = File(localSaveDir, dirName)
            if (!targetDir.exists()) targetDir.mkdirs()

            var receivedBytes = 0L
            val rawPacketChannel = Channel<ByteArray>(32)
            val decryptedChannel = Channel<ByteArray>(32)
            var success = true

            val receiveJob = launch(Dispatchers.IO) {
                try {
                    while (isActive) {
                        if (isCancelled) { success = false; break }
                        val packet = channel.receiveRawPacket() ?: break
                        if (packet.first == SecureQtSdk.Packets.TYPE_EOF) break
                        if (packet.first == SecureQtSdk.Packets.TYPE_CANCEL) {
                            success = false
                            break
                        }
                        if (packet.first == SecureQtSdk.Packets.TYPE_DATA) {
                            rawPacketChannel.send(packet.second)
                        }
                    }
                } finally {
                    rawPacketChannel.close()
                }
            }

            val decryptJob = launch(Dispatchers.Default) {
                try {
                    for (rawChunk in rawPacketChannel) {
                        val decrypted = channel.decryptData(rawChunk) ?: break
                        decryptedChannel.send(decrypted)
                    }
                } finally {
                    decryptedChannel.close()
                }
            }

            val pipedIn = java.io.PipedInputStream(CHUNK_SIZE)
            val pipedOut = java.io.PipedOutputStream(pipedIn)

            val unzipJob = launch(Dispatchers.IO) {
                try {
                    java.util.zip.ZipInputStream(pipedIn).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val destFile = File(targetDir, entry.name)
                            if (entry.isDirectory) {
                                destFile.mkdirs()
                            } else {
                                destFile.parentFile?.mkdirs()
                                FileOutputStream(destFile).use { zis.copyTo(it) }
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                } catch (e: Exception) {
                    success = false
                } finally {
                    pipedIn.close()
                }
            }

            var lastReportTime = 0L
            try {
                for (chunk in decryptedChannel) {
                    pipedOut.write(chunk)
                    receivedBytes += chunk.size
                    val now = System.currentTimeMillis()
                    if (now - lastReportTime > 250L) {
                        lastReportTime = now
                        onProgress?.invoke(receivedBytes, totalSize)
                    }
                }
            } finally {
                pipedOut.close()
            }

            if (!success) targetDir.deleteRecursively()
            receiveJob.cancel()
            decryptJob.cancel()
            success
        }
    }

    override suspend fun deleteFile(remotePath: String): Boolean = commandMutex.withLock {
        withContext(Dispatchers.IO) {
            val payload = CommandPayloadBuilder.buildWithString(StandardCommandIds.CMD_DELETE_FILE, remotePath)
            if (!channel.sendSecure(payload)) return@withContext false
            val response = channel.receiveSecure()
            response?.firstOrNull() == 1.toByte()
        }
    }

    override suspend fun renameFile(remotePath: String, newName: String): Boolean = commandMutex.withLock {
        withContext(Dispatchers.IO) {
            val payload = CommandPayloadBuilder.buildWithTwoStrings(StandardCommandIds.CMD_RENAME_FILE, remotePath, newName)
            if (!channel.sendSecure(payload)) return@withContext false
            val response = channel.receiveSecure()
            response?.firstOrNull() == 1.toByte()
        }
    }

    override suspend fun createFolder(remotePath: String): Boolean = commandMutex.withLock {
        withContext(Dispatchers.IO) {
            val payload = CommandPayloadBuilder.buildWithString(StandardCommandIds.CMD_CREATE_FOLDER, remotePath)
            if (!channel.sendSecure(payload)) return@withContext false
            val response = channel.receiveSecure()
            response?.firstOrNull() == 1.toByte()
        }
    }

    override suspend fun sendDeviceId(deviceId: String): Boolean = commandMutex.withLock {
        withContext(Dispatchers.IO) {
            val payload = CommandPayloadBuilder.buildWithString(StandardCommandIds.CMD_SET_DEVICE_ID, deviceId)
            channel.sendSecure(payload)
        }
    }

    override suspend fun sendDisconnect() {
        withContext(Dispatchers.IO) {
            val payload = CommandPayloadBuilder.buildSimple(StandardCommandIds.CMD_DISCONNECT)
            channel.sendSecure(payload)
            channel.disconnect()
        }
    }

    override fun cancelTransfer() {
        isCancelled = true
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { 
            channel.sendRawPacket(SecureQtSdk.Packets.TYPE_CANCEL, ByteArray(0)) 
        }
    }

    override suspend fun executeCustomCommand(commandId: Byte, payload: ByteArray): ByteArray? = commandMutex.withLock {
        withContext(Dispatchers.IO) {
            val fullPayload = if (payload.isEmpty()) {
                CommandPayloadBuilder.buildSimple(commandId)
            } else {
                ByteBuffer.allocate(1 + payload.size)
                    .put(commandId)
                    .put(payload)
                    .array()
            }
            if (!channel.sendSecure(fullPayload)) return@withContext null
            channel.receiveSecure()
        }
    }
}
