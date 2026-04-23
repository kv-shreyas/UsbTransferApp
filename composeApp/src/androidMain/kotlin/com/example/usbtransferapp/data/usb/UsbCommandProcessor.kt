package com.example.usbtransferapp.data.usb

import android.content.Context
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
        Log.d(TAG, "Command loop started")
        try {
            while (true) {
                val raw = dataSource.receiveSecure() ?: break
                val buffer = ByteBuffer.wrap(raw)
                
                // In our protocol, the first byte of the secure payload can be the sub-command
                // Or we can just use the Packet type. Let's use the first byte for sub-commands.
                // Actually, the Desktop sends TYPE_DATA for everything. 
                // Let's check how Desktop sends it.
                
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
        
        // Let's define types: 0: LIST, 1: SEND (Receive from Desktop), 2: FETCH (Send to Desktop)
        val commandType = buffer.get()
        
        when (commandType) {
            0.toByte() -> handleList(buffer)
            1.toByte() -> handleReceive(buffer)
            2.toByte() -> handleFetch(buffer)
            else -> Log.e(TAG, "Unknown command type: $commandType")
        }
    }

    private suspend fun handleList(buffer: ByteBuffer) {
        val pathLen = buffer.getInt()
        val pathBytes = ByteArray(pathLen)
        buffer.get(pathBytes)
        val path = String(pathBytes)
        
        Log.d(TAG, "List directory: $path")
        
        val dir = if (path == "/" || path.isEmpty()) context.filesDir else File(context.filesDir, path)
        val files = dir.listFiles() ?: emptyArray()
        
        val response = ByteBuffer.allocate(4)
        response.putInt(files.size)
        dataSource.sendSecure(response.array())
        
        for (file in files) {
            val nameBytes = file.name.toByteArray()
            val item = ByteBuffer.allocate(1 + 8 + 4 + nameBytes.size)
            item.put(if (file.isDirectory) 1.toByte() else 0.toByte())
            item.putLong(file.length())
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
        
        Log.d(TAG, "Receiving file: $fileName ($fileSize bytes)")
        
        val file = File(context.filesDir, fileName)
        val fos = FileOutputStream(file)
        
        var received = 0L
        while (received < fileSize) {
            val chunk = dataSource.receiveSecure() ?: break
            fos.write(chunk)
            received += chunk.size
        }
        fos.close()
        Log.d(TAG, "File received successfully: $fileName")
    }

    private suspend fun handleFetch(buffer: ByteBuffer) {
        val pathLen = buffer.getInt()
        val pathBytes = ByteArray(pathLen)
        buffer.get(pathBytes)
        val path = String(pathBytes)
        
        Log.d(TAG, "Fetching file: $path")
        
        val file = File(context.filesDir, path)
        if (!file.exists() || file.isDirectory) {
            dataSource.sendSecure(ByteBuffer.allocate(8).putLong(0).array())
            return
        }
        
        dataSource.sendSecure(ByteBuffer.allocate(8).putLong(file.length()).array())
        
        val fis = FileInputStream(file)
        val streamBuffer = ByteArray(16 * 1024)
        var read: Int
        while (fis.read(streamBuffer).also { read = it } != -1) {
            val chunk = if (read == streamBuffer.size) streamBuffer else streamBuffer.copyOf(read)
            dataSource.sendSecure(chunk)
        }
        fis.close()
        Log.d(TAG, "File sent successfully: $path")
    }
}
