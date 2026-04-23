package com.example.usbtransferapp.data.usb

import android.hardware.usb.UsbAccessory
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.inject.Inject

class AoaConnectionManager @Inject constructor(
    private val wrapper: UsbManagerWrapper
) : IUsbConnection {

    private val TAG = "AoaConnManager"
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var inputStream: FileInputStream? = null
    private var outputStream: FileOutputStream? = null

    fun connect(accessory: UsbAccessory): Boolean {
        Log.d(TAG, "Connecting to accessory: $accessory")
        fileDescriptor = wrapper.openAccessory(accessory)
        
        if (fileDescriptor == null) {
            Log.e(TAG, "Failed to open accessory")
            return false
        }

        val fd = fileDescriptor!!.fileDescriptor
        inputStream = FileInputStream(fd)
        outputStream = FileOutputStream(fd)
        
        Log.d(TAG, "AOA Connection established")
        return true
    }

    override fun send(data: ByteArray): Int {
        return try {
            outputStream?.write(data)
            outputStream?.flush()
            data.size
        } catch (e: Exception) {
            Log.e(TAG, "Error sending data", e)
            -1
        }
    }

    override fun receive(maxSize: Int): ByteArray? {
        val buffer = ByteArray(maxSize)
        return try {
            val len = inputStream?.read(buffer) ?: -1
            if (len > 0) {
                buffer.copyOf(len)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error receiving data", e)
            null
        }
    }

    override fun receiveExact(size: Int): ByteArray? {
        val buffer = ByteArray(size)
        var offset = 0
        return try {
            while (offset < size) {
                val len = inputStream?.read(buffer, offset, size - offset) ?: -1
                if (len == -1) break
                offset += len
            }
            if (offset == size) buffer else null
        } catch (e: Exception) {
            Log.e(TAG, "Error receiving exact data", e)
            null
        }
    }

    fun close() {
        try {
            fileDescriptor?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing file descriptor", e)
        } finally {
            fileDescriptor = null
            inputStream = null
            outputStream = null
        }
    }
}
