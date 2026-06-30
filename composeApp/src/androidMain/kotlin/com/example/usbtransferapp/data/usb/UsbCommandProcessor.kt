package com.example.usbtransferapp.data.usb

import android.content.Context
import android.os.Environment
import android.util.Log
import com.example.usbtransferapp.data.Packet
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import javax.inject.Inject

private const val TAG = "UsbCommandProcessor"

class UsbCommandProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataSource: UsbDataSource
) {

    suspend fun startListening() = withContext(Dispatchers.IO) {
        Log.d(TAG, "Command loop started - Sending READY signal")
        try {
            // Give desktop a moment to switch from write to read mode
            kotlinx.coroutines.delay(100)

            // Signal to desktop that we are ready for commands
            dataSource.sendSecure(byteArrayOf(Packet.TYPE_ACK))
            
            while (true) {
                val raw = dataSource.receiveSecure() ?: break
                processCommand(raw)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Command loop error", e)
        }
        Log.d(TAG, "Command loop stopped")
    }

    private suspend fun processCommand(data: ByteArray) {
        if (data.isEmpty()) return
        val buffer = ByteBuffer.wrap(data)
        
        val commandType = buffer.get()
        
        when (commandType) {
            0.toByte() -> handleList(buffer)
            1.toByte() -> handleReceive(buffer)
            2.toByte() -> handleFetch(buffer)
            3.toByte() -> handleFetchDir(buffer)
            else -> Log.e(TAG, "Unknown command type: $commandType")
        }
    }

    private suspend fun handleFetchDir(buffer: ByteBuffer) {
        val pathLen = buffer.getInt()
        val pathBytes = ByteArray(pathLen)
        buffer.get(pathBytes)
        val path = String(pathBytes)
        
        Log.d(TAG, "handleFetchDir: Path = $path")
        
        val dir = resolveFile(path)
        if (!dir.exists() || !dir.isDirectory) {
            Log.w(TAG, "handleFetchDir: Dir not found or is file: ${dir.absolutePath}")
            dataSource.sendSecure(ByteBuffer.allocate(8).putLong(0).array())
            return
        }
        
        val tempZip = File(context.cacheDir, "transfer_${dir.name}.zip")
        try {
            Log.d(TAG, "handleFetchDir: Zipping ${dir.absolutePath} to ${tempZip.absolutePath}")
            zipDirectory(dir, tempZip)
            
            val zipSize = tempZip.length()
            Log.d(TAG, "handleFetchDir: Sending ZIP of $zipSize bytes")
            dataSource.sendSecure(ByteBuffer.allocate(8).putLong(zipSize).array())
            
            val fis = FileInputStream(tempZip)
            fis.use { input ->
                val streamBuffer = ByteArray(16 * 1024)
                var read: Int
                while (input.read(streamBuffer).also { read = it } != -1) {
                    val chunk = if (read == streamBuffer.size) streamBuffer else streamBuffer.copyOf(read)
                    dataSource.sendSecure(chunk)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleFetchDir: Error zipping/sending directory", e)
            // Error already sent or size 0
        } finally {
            tempZip.delete()
        }
        Log.d(TAG, "handleFetchDir: Successfully sent ZIP of $path")
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
        Log.d(TAG, "Resolving path: '$path'")
        return when {
            path == "/" || path.isEmpty() -> context.filesDir
            path == "/sdcard" -> Environment.getExternalStorageDirectory()
            path.startsWith("/sdcard/") -> {
                val relativePath = path.removePrefix("/sdcard/")
                File(Environment.getExternalStorageDirectory(), relativePath)
            }
            path.startsWith("/") -> File(path)
            else -> File(context.filesDir, path)
        }
    }

    private suspend fun handleList(buffer: ByteBuffer) {
        val pathLen = buffer.getInt()
        val pathBytes = ByteArray(pathLen)
        buffer.get(pathBytes)
        val path = String(pathBytes)
        
        Log.d(TAG, "handleList: Requested Path = $path")
        
        val dir = resolveFile(path)
        Log.d(TAG, "handleList: Resolved Absolute Path = ${dir.absolutePath}")
        Log.d(TAG, "handleList: Exists = ${dir.exists()}, IsDirectory = ${dir.isDirectory}, CanRead = ${dir.canRead()}")
        
        val files = try {
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles() ?: run {
                    Log.e(TAG, "handleList: listFiles() returned null for ${dir.absolutePath}")
                    emptyArray()
                }
            } else {
                Log.w(TAG, "handleList: Path invalid or inaccessible: ${dir.absolutePath}")
                emptyArray()
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleList: Exception during listFiles", e)
            emptyArray()
        }
        
        Log.i(TAG, "handleList: Returning ${files.size} items for ${dir.absolutePath}")
        for (file in files) {
            Log.d(TAG, "handleList: Entry: ${if (file.isDirectory) "[DIR]" else "[FILE]"} ${file.name}")
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

    private suspend fun handleReceive(buffer: ByteBuffer) {
        val nameLen = buffer.getInt()
        val nameBytes = ByteArray(nameLen)
        buffer.get(nameBytes)
        val fileName = String(nameBytes)
        val fileSize = buffer.getLong()
        
        Log.d(TAG, "handleReceive: File = $fileName ($fileSize bytes)")
        
        var fos: FileOutputStream? = null
        try {
            val file = resolveFile(fileName)
            file.parentFile?.mkdirs()
            fos = FileOutputStream(file)
        } catch (e: Exception) {
            Log.e(TAG, "handleReceive: Failed to open FileOutputStream for $fileName. Sinking data to keep pipe clear.", e)
        }
        
        try {
            var received = 0L
            while (received < fileSize) {
                val chunk = dataSource.receiveSecure() ?: break
                fos?.write(chunk)
                received += chunk.size
            }
        } finally {
            fos?.close()
        }
        
        if (fos != null) {
            Log.d(TAG, "handleReceive: Successfully received $fileName")
        } else {
            Log.w(TAG, "handleReceive: Finished sinking $fileName (failed to save)")
        }
    }

    private suspend fun handleFetch(buffer: ByteBuffer) {
        val pathLen = buffer.getInt()
        val pathBytes = ByteArray(pathLen)
        buffer.get(pathBytes)
        val path = String(pathBytes)
        
        Log.d(TAG, "handleFetch: Path = $path")
        
        val file = resolveFile(path)
        if (!file.exists() || file.isDirectory) {
            Log.w(TAG, "handleFetch: File not found or is directory: ${file.absolutePath}")
            dataSource.sendSecure(ByteBuffer.allocate(8).putLong(0).array())
            return
        }
        
        Log.d(TAG, "handleFetch: Sending ${file.length()} bytes")
        dataSource.sendSecure(ByteBuffer.allocate(8).putLong(file.length()).array())
        
        val fis = FileInputStream(file)
        try {
            val streamBuffer = ByteArray(16 * 1024)
            var read: Int
            while (fis.read(streamBuffer).also { read = it } != -1) {
                val chunk = if (read == streamBuffer.size) streamBuffer else streamBuffer.copyOf(read)
                dataSource.sendSecure(chunk)
            }
        } finally {
            fis.close()
        }
        Log.d(TAG, "handleFetch: Successfully sent $path")
    }
}
