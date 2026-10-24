package com.example.secureqt.sdk.transport.desktop

import com.example.secureqt.sdk.transport.IUsbTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.usb4java.DeviceHandle
import org.usb4java.LibUsb
import java.nio.ByteBuffer
import java.nio.IntBuffer

/**
 * High-performance desktop USB transport implementation for SecureQT SDK.
 * Communicates with Android devices running in AOA (Android Open Accessory) mode
 * over USB bulk IN and OUT endpoints using native libusb (via usb4java).
 */
class DesktopAoaTransport(
    val handle: DeviceHandle,
    val endpointIn: Byte,
    val endpointOut: Byte,
    val interfaceNumber: Int = 0
) : IUsbTransport {

    @Volatile
    private var connected: Boolean = true

    private val readBuffer: ByteBuffer = ByteBuffer.allocateDirect(300 * 1024)
    private val writeBuffer: ByteBuffer = ByteBuffer.allocateDirect(300 * 1024)
    private val transferredBuffer: IntBuffer = IntBuffer.allocate(1)

    private val readLock = Any()
    private val writeLock = Any()

    private fun readSync(buffer: ByteArray): Int {
        synchronized(readLock) {
            if (!connected || handle.pointer == 0L) return -1

            readBuffer.clear()
            transferredBuffer.clear()

            val result = LibUsb.bulkTransfer(
                handle,
                endpointIn,
                readBuffer,
                transferredBuffer,
                1000 // 1s polling timeout
            )

            val size = transferredBuffer.get(0)

            if (result != LibUsb.SUCCESS) {
                if (result == LibUsb.ERROR_TIMEOUT) {
                    return if (size > 0) {
                        readBuffer.rewind()
                        val bytesToCopy = minOf(size, buffer.size)
                        readBuffer.get(buffer, 0, bytesToCopy)
                        bytesToCopy
                    } else {
                        0 // Normal timeout waiting for data
                    }
                } else {
                    if (result == LibUsb.ERROR_NO_DEVICE || result == LibUsb.ERROR_IO || result == LibUsb.ERROR_PIPE) {
                        connected = false
                    }
                    return -1
                }
            }

            if (size <= 0) return 0

            readBuffer.rewind()
            val bytesToCopy = minOf(size, buffer.size)
            readBuffer.get(buffer, 0, bytesToCopy)
            return bytesToCopy
        }
    }

    override suspend fun read(buffer: ByteArray): Int = withContext(Dispatchers.IO) {
        readSync(buffer)
    }

    private fun writeSync(buffer: ByteArray): Boolean {
        synchronized(writeLock) {
            if (!connected || handle.pointer == 0L) return false

            val useTempBuffer = buffer.size > writeBuffer.capacity()
            val baseBuffer = if (useTempBuffer) ByteBuffer.allocateDirect(buffer.size) else writeBuffer

            if (!useTempBuffer) baseBuffer.clear()

            baseBuffer.put(buffer)
            baseBuffer.flip()

            // usb4java passes the direct buffer capacity to native C, so .slice() is critical
            val directSlice = baseBuffer.slice()
            transferredBuffer.clear()

            var attempt = 0
            var result: Int
            do {
                result = LibUsb.bulkTransfer(
                    handle,
                    endpointOut,
                    directSlice,
                    transferredBuffer,
                    15000 // 15s write timeout
                )
                if (result == LibUsb.SUCCESS) break
                attempt++
                if (attempt < 2) Thread.sleep(50)
            } while (attempt < 2)

            if (result != LibUsb.SUCCESS) {
                if (result == LibUsb.ERROR_NO_DEVICE || result == LibUsb.ERROR_IO || result == LibUsb.ERROR_PIPE) {
                    connected = false
                }
                return false
            }

            val sent = transferredBuffer.get(0)
            return sent == buffer.size
        }
    }

    override suspend fun write(buffer: ByteArray): Boolean = withContext(Dispatchers.IO) {
        writeSync(buffer)
    }

    override fun isConnected(): Boolean = connected && handle.pointer != 0L

    override fun clearBuffer() {
        if (!connected || handle.pointer == 0L) return
        try {
            val discardBuffer = ByteBuffer.allocateDirect(16384)
            val transferred = IntBuffer.allocate(1)
            var result: Int
            do {
                transferred.rewind()
                result = LibUsb.bulkTransfer(handle, endpointIn, discardBuffer, transferred, 50)
            } while (result == LibUsb.SUCCESS && transferred.get(0) > 0)
        } catch (_: Exception) {}
    }

    /**
     * Closes the device handle and releases USB interfaces.
     */
    fun close() {
        synchronized(writeLock) {
            synchronized(readLock) {
                if (connected && handle.pointer != 0L) {
                    try { LibUsb.releaseInterface(handle, interfaceNumber) } catch (_: Exception) {}
                    try {
                        if (LibUsb.kernelDriverActive(handle, interfaceNumber) == 0) {
                            LibUsb.attachKernelDriver(handle, interfaceNumber)
                        }
                    } catch (_: Exception) {}
                    try { LibUsb.close(handle) } catch (_: Exception) {}
                    connected = false
                }
            }
        }
    }
}
