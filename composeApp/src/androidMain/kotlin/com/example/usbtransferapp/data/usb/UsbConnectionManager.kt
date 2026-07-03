package com.example.usbtransferapp.data.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import javax.inject.Inject
import android.util.Log

class UsbConnectionManager @Inject constructor(
    private val wrapper: UsbManagerWrapper
) : IUsbConnection {

    private  val TAG = "UsbConnManager"
    private var connection: UsbDeviceConnection? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null
    private var currentInterface: android.hardware.usb.UsbInterface? = null


        fun connect(device: UsbDevice): Boolean {
            Log.d(TAG, "Connecting to device: ${device.deviceName}")
            val usbInterface = device.getInterface(0)
            connection = wrapper.usbManager.openDevice(device)

            if (connection == null) {
                Log.e(TAG, "Failed to open device connection")
                return false
            }

            if (connection?.claimInterface(usbInterface, true) != true) {
                Log.e(TAG, "Failed to claim interface 0")
                return false
            }

            currentInterface = usbInterface
            Log.d(TAG, "Interface 0 claimed. Scanning endpoints...")
            for (i in 0 until usbInterface.endpointCount) {
                val ep = usbInterface.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) {
                        endpointIn = ep
                        Log.d(TAG, "Found Bulk IN endpoint: ${ep.address}")
                    } else {
                        endpointOut = ep
                        Log.d(TAG, "Found Bulk OUT endpoint: ${ep.address}")
                    }
                }
            }

            if (endpointIn == null || endpointOut == null) {
                Log.e(TAG, "Missing bulk endpoints: IN=$endpointIn, OUT=$endpointOut")
                return false
            }

            Log.d(TAG, "Connection fully established")
            return true
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
    }