package com.example.secureqt.sdk.transport.android

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.os.ParcelFileDescriptor
import com.example.secureqt.sdk.transport.IUsbTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Android USB transport implementation supporting both:
 * 1. Native USB Device bulk endpoints ([UsbDeviceConnection] with [UsbEndpoint])
 * 2. Standard Android Accessory file descriptors ([ParcelFileDescriptor])
 *
 * Slices outgoing writes into safe 16 KB bursts to prevent Linux kernel `usbfs`
 * buffer truncation and kernel EOVERFLOW stalls.
 */
class AndroidAoaTransport : IUsbTransport {

    companion object {
        const val MAX_USBFS_BURST_SIZE = 16384 // 16 KB safe physical burst
        const val DEFAULT_TIMEOUT_MS = 5000
    }

    private var connection: UsbDeviceConnection? = null
    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null
    private var claimedInterface: UsbInterface? = null

    private var pfd: ParcelFileDescriptor? = null
    private var fileInputStream: FileInputStream? = null
    private var fileOutputStream: FileOutputStream? = null

    /**
     * Constructs transport from native Android USB device bulk endpoints.
     */
    constructor(
        connection: UsbDeviceConnection,
        inEndpoint: UsbEndpoint,
        outEndpoint: UsbEndpoint,
        claimedInterface: UsbInterface? = null
    ) {
        this.connection = connection
        this.inEndpoint = inEndpoint
        this.outEndpoint = outEndpoint
        this.claimedInterface = claimedInterface
    }

    /**
     * Constructs transport from an opened [ParcelFileDescriptor] (e.g. from `UsbManager.openAccessory`).
     */
    constructor(pfd: ParcelFileDescriptor) {
        this.pfd = pfd
        this.fileInputStream = FileInputStream(pfd.fileDescriptor)
        this.fileOutputStream = FileOutputStream(pfd.fileDescriptor)
    }

    fun readSync(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset): Int {
        val stream = fileInputStream
        if (stream != null) {
            return try {
                stream.read(buffer, offset, length)
            } catch (_: Exception) {
                -1
            }
        }

        val conn = connection ?: return -1
        val epIn = inEndpoint ?: return -1

        val chunk = minOf(MAX_USBFS_BURST_SIZE, length)
        return conn.bulkTransfer(epIn, buffer, offset, chunk, DEFAULT_TIMEOUT_MS)
    }

    override suspend fun read(buffer: ByteArray): Int = withContext(Dispatchers.IO) {
        readSync(buffer, 0, buffer.size)
    }

    fun writeSync(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset): Boolean {
        val stream = fileOutputStream
        if (stream != null) {
            return try {
                // Slice in 16KB bursts for stream consistency
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

        val conn = connection ?: return false
        val epOut = outEndpoint ?: return false

        // 16 KB UsbFS burst slicing loop
        var currentOffset = offset
        val endOffset = offset + length
        while (currentOffset < endOffset) {
            val sliceLen = minOf(MAX_USBFS_BURST_SIZE, endOffset - currentOffset)
            val bytesWritten = conn.bulkTransfer(epOut, buffer, currentOffset, sliceLen, DEFAULT_TIMEOUT_MS)
            if (bytesWritten <= 0) {
                return false
            }
            currentOffset += bytesWritten
        }
        return true
    }

    override suspend fun write(buffer: ByteArray): Boolean = withContext(Dispatchers.IO) {
        writeSync(buffer, 0, buffer.size)
    }

    override fun isConnected(): Boolean {
        if (pfd != null) {
            return try { pfd?.fileDescriptor?.valid() == true } catch (_: Exception) { false }
        }
        return connection != null && inEndpoint != null && outEndpoint != null
    }

    override fun clearBuffer() {
        val conn = connection
        val epIn = inEndpoint
        if (conn != null && epIn != null) {
            val discard = ByteArray(1024)
            while (true) {
                val len = conn.bulkTransfer(epIn, discard, discard.size, 10)
                if (len <= 0) break
            }
        }
    }

    /**
     * Closes the connection and releases any claimed USB interfaces.
     */
    fun close() {
        try {
            fileInputStream?.close()
            fileOutputStream?.close()
            pfd?.close()
        } catch (_: Exception) {}

        try {
            claimedInterface?.let { connection?.releaseInterface(it) }
            connection?.close()
        } catch (_: Exception) {}

        fileInputStream = null
        fileOutputStream = null
        pfd = null
        connection = null
        inEndpoint = null
        outEndpoint = null
        claimedInterface = null
    }
}
