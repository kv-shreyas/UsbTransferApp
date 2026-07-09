package com.example.usbtransferapp.data.usb

import android.content.Context
import android.os.Environment
import android.util.Log
import com.example.usbtransferapp.data.Packet
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import javax.inject.Inject

private const val TAG = "UsbCommandProcessor"

class UsbCommandProcessor @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dataSource: UsbDataSource,
    private val usbLogger: com.example.usbtransferapp.data.logging.UsbLogger
) {

    private var transferJob: kotlinx.coroutines.Job? = null

    suspend fun startListening(
        onReceiveStarted: (String) -> Unit = {},
        onReceiveProgress: (Float) -> Unit = {},
        onReceiveFinished: () -> Unit = {},
        onReceiveCancelled: () -> Unit = {},
        onReceiveError: (String) -> Unit = {},
        onDisconnectReceived: () -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        usbLogger.d(TAG, "Command loop started - Sending READY signal")
        try {
            kotlinx.coroutines.delay(100)
            dataSource.sendRawPacket(Packet.TYPE_ACK, ByteArray(0))
            
            while (isActive) {
                try {
                    val raw = dataSource.receiveSecure() ?: break
                    if (!processCommand(raw, onReceiveStarted, onReceiveProgress, onReceiveFinished, onReceiveCancelled, onReceiveError, onDisconnectReceived)) break
                } catch (e: UsbDataSource.TransferCancelledException) {
                    usbLogger.w(TAG, "Transfer cancelled by remote. Aborting current transfer job.")
                    transferJob?.cancel()
                    transferJob?.join()
                    transferJob = null
                }
            }
        } catch (e: Exception) {
            usbLogger.e(TAG, "Command loop error", e)
        }
        usbLogger.d(TAG, "Command loop stopped")
    }

    private suspend fun processCommand(
        data: ByteArray,
        onReceiveStarted: (String) -> Unit,
        onReceiveProgress: (Float) -> Unit,
        onReceiveFinished: () -> Unit,
        onReceiveCancelled: () -> Unit,
        onReceiveError: (String) -> Unit,
        onDisconnectReceived: () -> Unit
    ): Boolean {
        if (data.isEmpty()) return true
        val buffer = ByteBuffer.wrap(data)
        
        val commandType = buffer.get()
        
        when (commandType) {
            0.toByte() -> handleList(buffer)
            1.toByte() -> {
                try {
                    handleReceive(buffer, onReceiveStarted, onReceiveProgress, onReceiveFinished)
                } catch (e: UsbDataSource.TransferCancelledException) {
                    usbLogger.w(TAG, "Receive was cancelled by remote.")
                    withContext(Dispatchers.Main) { onReceiveCancelled() }
                } catch (e: Exception) {
                    usbLogger.e(TAG, "Receive error", e)
                    withContext(Dispatchers.Main) { onReceiveError(e.message ?: "Unknown error") }
                }
            }
            2.toByte() -> {
                transferJob = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    try { handleFetch(buffer) } catch(e: Exception) { usbLogger.e(TAG, "Fetch error", e) }
                }
            }
            3.toByte() -> {
                transferJob = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    try { handleFetchDir(buffer) } catch(e: Exception) { usbLogger.e(TAG, "FetchDir error", e) }
                }
            }
            4.toByte() -> {
                usbLogger.d(TAG, "Received DISCONNECT command.")
                withContext(Dispatchers.Main) { onDisconnectReceived() }
                return false
            }
            5.toByte() -> { // CMD_SEND_DIR
                try {
                    handleReceiveDir(buffer, onReceiveStarted, onReceiveProgress, onReceiveFinished)
                } catch (e: UsbDataSource.TransferCancelledException) {
                    usbLogger.w(TAG, "ReceiveDir was cancelled by remote.")
                    withContext(Dispatchers.Main) { onReceiveCancelled() }
                } catch (e: Exception) {
                    usbLogger.e(TAG, "ReceiveDir error", e)
                    withContext(Dispatchers.Main) { onReceiveError(e.message ?: "Unknown error") }
                }
            }
            6.toByte() -> {
                try { handleDelete(buffer) } catch(e: Exception) { usbLogger.e(TAG, "Delete error", e) }
            }
            7.toByte() -> {
                try { handleRename(buffer) } catch(e: Exception) { usbLogger.e(TAG, "Rename error", e) }
            }
            else -> usbLogger.e(TAG, "Unknown command type: $commandType")
        }
        return true
    }

    private suspend fun handleDelete(buffer: ByteBuffer) {
        val pathLen = buffer.getInt()
        val pathBytes = ByteArray(pathLen)
        buffer.get(pathBytes)
        val path = String(pathBytes)
        
        usbLogger.d(TAG, "handleDelete: Path = $path")
        val file = resolveFile(path)
        
        val success = try {
            if (file.exists()) {
                if (file.isDirectory) file.deleteRecursively() else file.delete()
            } else {
                false
            }
        } catch (e: Exception) {
            usbLogger.e(TAG, "handleDelete: Error deleting file", e)
            false
        }
        
        usbLogger.d(TAG, "handleDelete: Success = $success")
        dataSource.sendSecure(byteArrayOf(if (success) 1.toByte() else 0.toByte()))
    }

    private suspend fun handleRename(buffer: ByteBuffer) {
        val pathLen = buffer.getInt()
        val pathBytes = ByteArray(pathLen)
        buffer.get(pathBytes)
        val oldPath = String(pathBytes)
        
        val newNameLen = buffer.getInt()
        val newNameBytes = ByteArray(newNameLen)
        buffer.get(newNameBytes)
        val newName = String(newNameBytes)
        
        usbLogger.d(TAG, "handleRename: $oldPath to $newName")
        val file = resolveFile(oldPath)
        
        val success = try {
            if (file.exists()) {
                val newFile = java.io.File(file.parentFile, newName)
                file.renameTo(newFile)
            } else {
                false
            }
        } catch (e: Exception) {
            usbLogger.e(TAG, "handleRename: Error renaming file", e)
            false
        }
        
        usbLogger.d(TAG, "handleRename: Success = $success")
        dataSource.sendSecure(byteArrayOf(if (success) 1.toByte() else 0.toByte()))
    }

    private suspend fun handleFetchDir(buffer: ByteBuffer) {
        val pathLen = buffer.getInt()
        val pathBytes = ByteArray(pathLen)
        buffer.get(pathBytes)
        val path = String(pathBytes)
        
        usbLogger.d(TAG, "handleFetchDir: Path = $path")
        
        val dir = resolveFile(path)
        if (!dir.exists() || !dir.isDirectory) {
            usbLogger.w(TAG, "handleFetchDir: Dir not found or is file: ${dir.absolutePath}")
            dataSource.sendSecure(ByteBuffer.allocate(8).putLong(0).array())
            return
        }
        
        val tempZip = File(context.cacheDir, "transfer_${dir.name}.zip")
        try {
            usbLogger.d(TAG, "handleFetchDir: Zipping ${dir.absolutePath} to ${tempZip.absolutePath}")
            zipDirectory(dir, tempZip)
            
            val zipSize = tempZip.length()
            usbLogger.d(TAG, "handleFetchDir: Sending ZIP of $zipSize bytes")
            dataSource.sendSecure(ByteBuffer.allocate(8).putLong(zipSize).array())
            
            val fis = FileInputStream(tempZip)
            coroutineScope {
                fis.use { input ->
                    val chunkChannel = kotlinx.coroutines.channels.Channel<ByteArray>(2)
                    val encryptedChannel = kotlinx.coroutines.channels.Channel<ByteArray>(2)

                    // Worker 1: Disk Read
                    launch(Dispatchers.IO) {
                        try {
                            val streamBuffer = ByteArray(256 * 1024)
                            var read: Int
                            while (input.read(streamBuffer).also { read = it } != -1 && isActive) {
                                val chunk = if (read == streamBuffer.size) streamBuffer else streamBuffer.copyOf(read)
                                chunkChannel.send(chunk)
                            }
                        } finally {
                            chunkChannel.close()
                        }
                    }

                    // Worker 2: Encrypt
                    launch(Dispatchers.Default) {
                        try {
                            for (chunk in chunkChannel) {
                                val encrypted = dataSource.encryptData(chunk) ?: break
                                encryptedChannel.send(encrypted)
                            }
                        } finally {
                            encryptedChannel.close()
                        }
                    }

                    // Worker 3: USB Send (Main Coroutine)
                    for (encrypted in encryptedChannel) {
                        if (!dataSource.sendRawPacket(Packet.TYPE_DATA, encrypted)) break
                    }
                    dataSource.sendRawPacket(Packet.TYPE_EOF, ByteArray(0))
                }
            }
        } catch (e: Exception) {
            usbLogger.e(TAG, "handleFetchDir: Error zipping/sending directory", e)
            // Error already sent or size 0
        } finally {
            tempZip.delete()
        }
        usbLogger.d(TAG, "handleFetchDir: Successfully sent ZIP of $path")
    }

    private fun zipDirectory(dir: File, zipFile: File) {
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(zipFile)).use { zos ->
            dir.walkTopDown().forEach { file ->
                if (file.absolutePath == zipFile.absolutePath) return@forEach
                val entryName = file.absolutePath.removePrefix(dir.absolutePath).removePrefix("/")
                if (entryName.isNotEmpty()) {
                    val entry = java.util.zip.ZipEntry(if (file.isDirectory) "$entryName/" else entryName)
                    zos.putNextEntry(entry)
                    if (file.isFile) {
                        file.inputStream().use { it.copyTo(zos) }
                    }
                    zos.closeEntry()
                }
            }
        }
    }

    private fun resolveFile(path: String): File {
        usbLogger.d(TAG, "Resolving path: '$path'")
        val externalStorage = Environment.getExternalStorageDirectory()
        val cleanPath = path.trim()
        return when {
            cleanPath == "/" || cleanPath.isEmpty() || cleanPath == "/sdcard" -> externalStorage
            cleanPath.startsWith("/sdcard/") -> {
                val relativePath = cleanPath.removePrefix("/sdcard/").trimStart('/')
                if (relativePath.isEmpty()) externalStorage else File(externalStorage, relativePath)
            }
            cleanPath.startsWith("/") -> {
                val relativePath = cleanPath.removePrefix("/").trimStart('/')
                if (relativePath.isEmpty()) externalStorage else File(externalStorage, relativePath)
            }
            else -> File(externalStorage, cleanPath)
        }
    }

    private suspend fun handleList(buffer: ByteBuffer) {
        val pathLen = buffer.getInt()
        val pathBytes = ByteArray(pathLen)
        buffer.get(pathBytes)
        val path = String(pathBytes)
        
        usbLogger.d(TAG, "handleList: Requested Path = $path")
        
        val dir = resolveFile(path)
        usbLogger.d(TAG, "handleList: Resolved Absolute Path = ${dir.absolutePath}")
        usbLogger.d(TAG, "handleList: Exists = ${dir.exists()}, IsDirectory = ${dir.isDirectory}, CanRead = ${dir.canRead()}")
        
        val files = try {
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles() ?: run {
                    usbLogger.e(TAG, "handleList: listFiles() returned null for ${dir.absolutePath}")
                    emptyArray()
                }
            } else {
                usbLogger.w(TAG, "handleList: Path invalid or inaccessible: ${dir.absolutePath}")
                emptyArray()
            }
        } catch (e: Exception) {
            usbLogger.e(TAG, "handleList: Exception during listFiles", e)
            emptyArray()
        }
        
        usbLogger.i(TAG, "handleList: Returning ${files.size} items for ${dir.absolutePath}")
        for (file in files) {
            usbLogger.d(TAG, "handleList: Entry: ${if (file.isDirectory) "[DIR]" else "[FILE]"} ${file.name}")
        }
        
        val response = ByteBuffer.allocate(4)
        response.putInt(files.size)
        dataSource.sendSecure(response.array())
        
        for (file in files) {
            val nameBytes = file.name.toByteArray()
            val item = ByteBuffer.allocate(1 + 8 + 4 + nameBytes.size)
            item.put(if (file.isDirectory) 1.toByte() else 0.toByte())
            item.putLong(if (file.isDirectory) 0L else file.length())
            item.putInt(nameBytes.size)
            item.put(nameBytes)
            dataSource.sendSecure(item.array())
        }
    }

    private suspend fun handleReceive(
        buffer: ByteBuffer,
        onReceiveStarted: (String) -> Unit,
        onReceiveProgress: (Float) -> Unit,
        onReceiveFinished: () -> Unit
    ) = kotlinx.coroutines.coroutineScope {
        val nameLen = buffer.getInt()
        val nameBytes = ByteArray(nameLen)
        buffer.get(nameBytes)
        val fileName = String(nameBytes)
        val fileSize = buffer.getLong()
        
        usbLogger.d(TAG, "handleReceive: File = $fileName ($fileSize bytes)")
        withContext(Dispatchers.Main) { onReceiveStarted(fileName) }
        
        var fos: FileOutputStream? = null
        var file: File? = null
        try {
            file = resolveFile(fileName)
            file.parentFile?.mkdirs()
            fos = FileOutputStream(file)
        } catch (e: Exception) {
            usbLogger.e(TAG, "handleReceive: Failed to open FileOutputStream for $fileName. Sinking data to keep pipe clear.", e)
        }
        
        try {
            val rawPacketChannel = kotlinx.coroutines.channels.Channel<ByteArray>(2)
            val decryptedChannel = kotlinx.coroutines.channels.Channel<ByteArray>(2)

            // Worker 1: USB Receive
            launch(Dispatchers.IO) {
                try {
                    while (isActive) {
                        val packet = dataSource.receiveRawPacket() ?: break
                        if (packet.type == Packet.TYPE_CANCEL) throw UsbDataSource.TransferCancelledException()
                        if (packet.type != Packet.TYPE_DATA) break
                        rawPacketChannel.send(packet.payload)
                    }
                } finally {
                    rawPacketChannel.close()
                }
            }

            // Worker 2: Decrypt
            launch(Dispatchers.Default) {
                try {
                    for (payload in rawPacketChannel) {
                        val decrypted = dataSource.decryptData(payload) ?: break
                        decryptedChannel.send(decrypted)
                    }
                } finally {
                    decryptedChannel.close()
                }
            }

            // Worker 3: Disk Write (Main Coroutine)
            var received = 0L
            for (chunk in decryptedChannel) {
                fos?.write(chunk)
                received += chunk.size
                if (fileSize > 0) {
                    val progress = received.toFloat() / fileSize.toFloat()
                    withContext(Dispatchers.Main) { onReceiveProgress(progress) }
                }
                if (received >= fileSize) break
            }
        } catch (e: Exception) {
            fos?.close()
            fos = null
            file?.delete()
            throw e
        } finally {
            fos?.close()
        }
        
        if (fos != null) {
            usbLogger.d(TAG, "handleReceive: Successfully received $fileName")
            withContext(Dispatchers.Main) { onReceiveFinished() }
        } else {
            usbLogger.w(TAG, "handleReceive: Finished sinking $fileName (failed to save)")
        }
    }

    private suspend fun handleReceiveDir(
        buffer: ByteBuffer,
        onReceiveStarted: (String) -> Unit,
        onReceiveProgress: (Float) -> Unit,
        onReceiveFinished: () -> Unit
    ) = coroutineScope {
        val nameLen = buffer.getInt()
        val nameBytes = ByteArray(nameLen)
        buffer.get(nameBytes)
        val fileName = String(nameBytes)
        val folderName = fileName.removeSuffix(".zip")
        val fileSize = buffer.getLong()
        
        usbLogger.d(TAG, "handleReceiveDir: Folder = $folderName ($fileSize bytes)")
        withContext(Dispatchers.Main) { onReceiveStarted(folderName) }
        
        val targetDirectory = resolveFile(folderName)
        targetDirectory.mkdirs()
        
        val baseName = java.io.File(fileName).name
        val tempZip = File(context.cacheDir, baseName)
        var fos: FileOutputStream? = null
        try {
            fos = FileOutputStream(tempZip)
        } catch (e: Exception) {
            usbLogger.e(TAG, "handleReceiveDir: Failed to open FileOutputStream for temp zip", e)
        }
        
        try {
            val rawPacketChannel = kotlinx.coroutines.channels.Channel<ByteArray>(2)
            val decryptedChannel = kotlinx.coroutines.channels.Channel<ByteArray>(2)

            launch(Dispatchers.IO) {
                try {
                    while (isActive) {
                        val packet = dataSource.receiveRawPacket() ?: break
                        if (packet.type == Packet.TYPE_CANCEL) throw UsbDataSource.TransferCancelledException()
                        if (packet.type != Packet.TYPE_DATA) break
                        rawPacketChannel.send(packet.payload)
                    }
                } finally {
                    rawPacketChannel.close()
                }
            }

            launch(Dispatchers.Default) {
                try {
                    for (payload in rawPacketChannel) {
                        val decrypted = dataSource.decryptData(payload) ?: break
                        decryptedChannel.send(decrypted)
                    }
                } finally {
                    decryptedChannel.close()
                }
            }

            var received = 0L
            for (chunk in decryptedChannel) {
                fos?.write(chunk)
                received += chunk.size
                if (fileSize > 0) {
                    val progress = received.toFloat() / fileSize.toFloat()
                    withContext(Dispatchers.Main) { onReceiveProgress(progress) }
                }
                if (received >= fileSize) break
            }
        } catch (e: Exception) {
            fos?.close()
            fos = null
            tempZip.delete()
            throw e
        } finally {
            fos?.close()
        }
        
        if (fos != null) {
            usbLogger.d(TAG, "handleReceiveDir: Received temp zip, starting extraction...")
            try {
                withContext(Dispatchers.Main) { onReceiveStarted("Extracting $folderName...") }
                withContext(Dispatchers.IO) {
                    unzipFile(tempZip, targetDirectory)
                }
                usbLogger.d(TAG, "handleReceiveDir: Extracted successfully to ${targetDirectory.absolutePath}")
                withContext(Dispatchers.Main) { onReceiveFinished() }
            } catch (e: Exception) {
                usbLogger.e(TAG, "handleReceiveDir: Extraction failed", e)
            } finally {
                tempZip.delete()
            }
        }
    }

    private fun unzipFile(zipFile: File, targetDirectory: File) {
        java.util.zip.ZipInputStream(java.io.FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val file = File(targetDirectory, entry.name)
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    java.io.FileOutputStream(file).use { fos ->
                        zis.copyTo(fos)
                    }
                    android.media.MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private suspend fun handleFetch(buffer: ByteBuffer) = coroutineScope {
        val pathLen = buffer.getInt()
        val pathBytes = ByteArray(pathLen)
        buffer.get(pathBytes)
        val path = String(pathBytes)
        
        usbLogger.d(TAG, "handleFetch: Path = $path")
        
        val file = resolveFile(path)
        if (!file.exists() || file.isDirectory) {
            usbLogger.w(TAG, "handleFetch: File not found or is directory: ${file.absolutePath}")
            dataSource.sendSecure(ByteBuffer.allocate(8).putLong(0).array())
            return@coroutineScope
        }
        
        usbLogger.d(TAG, "handleFetch: Sending ${file.length()} bytes")
        dataSource.sendSecure(ByteBuffer.allocate(8).putLong(file.length()).array())
        
        val fis = FileInputStream(file)
        try {
            val chunkChannel = kotlinx.coroutines.channels.Channel<ByteArray>(2)
            val encryptedChannel = kotlinx.coroutines.channels.Channel<ByteArray>(2)

            // Worker 1: Disk Read
            launch(Dispatchers.IO) {
                try {
                    val streamBuffer = ByteArray(256 * 1024)
                    var read: Int
                    while (fis.read(streamBuffer).also { read = it } != -1 && isActive) {
                        val chunk = if (read == streamBuffer.size) streamBuffer else streamBuffer.copyOf(read)
                        chunkChannel.send(chunk)
                    }
                } finally {
                    chunkChannel.close()
                }
            }

            // Worker 2: Encrypt
            launch(Dispatchers.Default) {
                try {
                    for (chunk in chunkChannel) {
                        val encrypted = dataSource.encryptData(chunk) ?: break
                        encryptedChannel.send(encrypted)
                    }
                } finally {
                    encryptedChannel.close()
                }
            }

            // Worker 3: USB Send (Main Coroutine)
            for (encrypted in encryptedChannel) {
                if (!dataSource.sendRawPacket(Packet.TYPE_DATA, encrypted)) break
            }
            dataSource.sendRawPacket(Packet.TYPE_EOF, ByteArray(0))
        } finally {
            fis.close()
        }
        usbLogger.d(TAG, "handleFetch: Successfully sent $path")
    }
}
