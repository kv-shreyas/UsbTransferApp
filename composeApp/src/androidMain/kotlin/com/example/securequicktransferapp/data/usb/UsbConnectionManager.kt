package com.example.securequicktransferapp.data.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import javax.inject.Inject
import android.util.Log

class UsbConnectionManager @Inject constructor(
    private val wrapper: UsbManagerWrapper,
    private val usbLogger: com.example.securequicktransferapp.data.logging.UsbLogger
) : IUsbConnection {

    companion object {
        private const val TAG = "UsbConnManager"
    }

    private var connection: UsbDeviceConnection? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null
    private var currentInterface: android.hardware.usb.UsbInterface? = null


    fun connect(device: UsbDevice): Boolean {
        usbLogger.d(TAG, "Connecting to device: ${device.deviceName} (interfaces: ${device.interfaceCount})")
        connection = wrapper.usbManager.openDevice(device)

        if (connection == null) {
            usbLogger.e(TAG, "Failed to open device connection")
            return false
        }

        for (ifaceIdx in 0 until device.interfaceCount) {
            val iface = device.getInterface(ifaceIdx)
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) {
                        epIn = ep
                    } else {
                        epOut = ep
                    }
                }
            }
            if (epIn != null && epOut != null) {
                if (connection?.claimInterface(iface, true) == true) {
                    currentInterface = iface
                    endpointIn = epIn
                    endpointOut = epOut
                    usbLogger.i(TAG, "Claimed interface $ifaceIdx with Bulk IN=${epIn.address}, OUT=${epOut.address}")
                    return true
                } else {
                    usbLogger.w(TAG, "Failed to claim interface $ifaceIdx")
                }
            }
        }

        usbLogger.e(TAG, "Missing bulk endpoints across all ${device.interfaceCount} interfaces")
        return false
    }

    private val MAX_USBFS_BUFFER_SIZE = 16384

    override fun send(data: ByteArray): Int {
        var offset = 0
        while (offset < data.size) {
            val chunk = minOf(MAX_USBFS_BUFFER_SIZE, data.size - offset)
            val result = connection?.bulkTransfer(endpointOut, data, offset, chunk, 5000) ?: -1
            if (result < 0) {
                Log.e(TAG, "Failed to send data at offset $offset (result=$result)")
                return -1
            }
            offset += result
        }
        return data.size
    }

    override fun receive(maxSize: Int): ByteArray? {
        val chunk = minOf(MAX_USBFS_BUFFER_SIZE, maxSize)
        val buffer = ByteArray(chunk)
        val len = connection?.bulkTransfer(endpointIn, buffer, buffer.size, 5000) ?: -1
        return if (len > 0) {
            buffer.copyOf(len)
        } else {
            if (len < 0) Log.e(TAG, "Failed to receive data: len=$len")
            null
        }
    }

    override fun receive(buffer: ByteArray): Int {
        val chunk = minOf(MAX_USBFS_BUFFER_SIZE, buffer.size)
        return connection?.bulkTransfer(endpointIn, buffer, chunk, 5000) ?: -1
    }

    override fun receiveExact(size: Int): ByteArray? {
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val remaining = size - offset
            val chunk = minOf(MAX_USBFS_BUFFER_SIZE, remaining)
            val len = connection?.bulkTransfer(endpointIn, buffer, offset, chunk, 5000) ?: -1
            if (len <= 0) {
                Log.e(TAG, "Failed to receive exact bytes at offset $offset: len=$len")
                return null
            }
            offset += len
        }
        return buffer
    }

    override fun clearBuffer() {
        // Bulk endpoints don't have an easy "available()" so we just do a short read if data is there
        val temp = ByteArray(1024)
        while (true) {
            val len = connection?.bulkTransfer(endpointIn, temp, temp.size, 10) ?: -1
            if (len <= 0) break
        }
    }

    override fun disconnect() {
        try {
            currentInterface?.let { connection?.releaseInterface(it) }
            connection?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing connection", e)
        } finally {
            connection = null
            endpointIn = null
            endpointOut = null
            currentInterface = null
        }
    }

    override fun isConnected(): Boolean {
        return connection != null && endpointIn != null && endpointOut != null
    }
}