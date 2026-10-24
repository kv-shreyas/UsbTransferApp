package com.example.secureqt.sdk.transport.android

import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import com.example.secureqt.sdk.transport.IUsbTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Android USB transport implementation for Android Open Accessory (AOA) Client mode.
 * Communicates with the Desktop USB Host via [ParcelFileDescriptor] obtained from `UsbManager.openAccessory`.
 *
 * Slices outgoing writes into safe 16 KB bursts to prevent Linux kernel `usbfs`
 * buffer truncation and kernel EOVERFLOW stalls.
 */
class AndroidAoaTransport(
    private var pfd: ParcelFileDescriptor?
) : IUsbTransport {

    companion object {
        const val MAX_USBFS_BURST_SIZE = 16384 // 16 KB safe physical burst

        /**
         * Opens an [AndroidAoaTransport] directly from an attached [UsbAccessory] descriptor.
         *
         * @return An active [AndroidAoaTransport] or null if the descriptor could not be opened.
         */
        fun open(usbManager: UsbManager, accessory: UsbAccessory): AndroidAoaTransport? {
            val pfd = usbManager.openAccessory(accessory) ?: return null
            return AndroidAoaTransport(pfd)
        }
    }

    private var fileInputStream: FileInputStream? = pfd?.let { FileInputStream(it.fileDescriptor) }
    private var fileOutputStream: FileOutputStream? = pfd?.let { FileOutputStream(it.fileDescriptor) }

    fun readSync(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset): Int {
        val stream = fileInputStream ?: return -1
        return try {
            stream.read(buffer, offset, length)
        } catch (_: Exception) {
            -1
        }
    }

    override suspend fun read(buffer: ByteArray): Int = withContext(Dispatchers.IO) {
        readSync(buffer, 0, buffer.size)
    }

    fun writeSync(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset): Boolean {
        val stream = fileOutputStream ?: return false
        return try {
            var currentOffset = offset
            val endOffset = offset + length
            while (currentOffset < endOffset) {
                val sliceLen = minOf(MAX_USBFS_BURST_SIZE, endOffset - currentOffset)
                stream.write(buffer, currentOffset, sliceLen)
                currentOffset += sliceLen
            }
            stream.flush()
            true
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun write(buffer: ByteArray): Boolean = withContext(Dispatchers.IO) {
        writeSync(buffer, 0, buffer.size)
    }

    override fun isConnected(): Boolean {
        return try {
            pfd?.fileDescriptor?.valid() == true
        } catch (_: Exception) {
            false
        }
    }

    override fun clearBuffer() {
        // Stream-based accessory transport does not maintain an unread kernel bulk queue
    }

    /**
     * Closes the accessory file descriptor and underlying streams.
     */
    fun close() {
        try {
            fileInputStream?.close()
            fileOutputStream?.close()
            pfd?.close()
        } catch (_: Exception) {}

        fileInputStream = null
        fileOutputStream = null
        pfd = null
    }
}
