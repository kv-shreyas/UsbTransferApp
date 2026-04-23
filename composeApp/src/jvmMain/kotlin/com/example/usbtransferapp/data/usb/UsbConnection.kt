package com.example.usbtransferapp.data.usb

import org.usb4java.Device
import org.usb4java.DeviceHandle
import org.usb4java.LibUsb
import java.nio.ByteBuffer
import java.nio.IntBuffer
import kotlin.experimental.or

class UsbConnection {

    private var handle: DeviceHandle? = null
    
    private val ENDPOINT_IN = 0x81.toByte()
    private val ENDPOINT_OUT = 0x01.toByte()

    // AOA Constants
    private val ACCESSORY_GET_PROTOCOL = 51
    private val ACCESSORY_SEND_STRING = 52
    private val ACCESSORY_START = 53

    fun switchToAoa(device: Device): Boolean {
        val tempHandle = DeviceHandle()
        if (LibUsb.open(device, tempHandle) != LibUsb.SUCCESS) return false

        try {
            // 1. Get Protocol
            val protocolBuffer = ByteBuffer.allocateDirect(2)
            val protocolResult = LibUsb.controlTransfer(
                tempHandle,
                (LibUsb.ENDPOINT_IN or LibUsb.REQUEST_TYPE_VENDOR).toByte(),
                ACCESSORY_GET_PROTOCOL.toByte(),
                0.toShort(),
                0.toShort(),
                protocolBuffer,
                2000
            )
            if (protocolResult < 0) return false
            val protocol = protocolBuffer.getShort(0)
            if (protocol < 1) return false

            // 2. Send Strings (Manufacturer, Model, etc.)
            val strings = arrayOf(
                "AndroidOpenAccessory", // Manufacturer
                "DataTransfer",         // Model
                "Handshake Demo",       // Description
                "1.0",                  // Version
                "http://example.com",   // URI
                "12345678"              // Serial
            )

            for (i in strings.indices) {
                val stringBuffer = ByteBuffer.allocateDirect(strings[i].length + 1)
                stringBuffer.put(strings[i].toByteArray())
                stringBuffer.put(0x00.toByte())

                val result = LibUsb.controlTransfer(
                    tempHandle,
                    (LibUsb.ENDPOINT_OUT or LibUsb.REQUEST_TYPE_VENDOR).toByte(),
                    ACCESSORY_SEND_STRING.toByte(),
                    0.toShort(),
                    i.toShort(),
                    stringBuffer,
                    2000
                )
                if (result < 0) return false
            }

            // 3. Start Accessory
            val startResult = LibUsb.controlTransfer(
                tempHandle,
                (LibUsb.ENDPOINT_OUT or LibUsb.REQUEST_TYPE_VENDOR).toByte(),
                ACCESSORY_START.toByte(),
                0.toShort(),
                0.toShort(),
                ByteBuffer.allocateDirect(0),
                2000
            )
            if (startResult < 0) return false

            return true
        } finally {
            LibUsb.close(tempHandle)
        }
    }

    fun open(device: Device): Boolean {
        handle = DeviceHandle()

        if (LibUsb.open(device, handle) != LibUsb.SUCCESS) return false

        // Attempt to detach kernel driver (only relevant on Linux)
        val result = LibUsb.detachKernelDriver(handle, 0)
        if (result != LibUsb.SUCCESS && result != LibUsb.ERROR_NOT_SUPPORTED && result != LibUsb.ERROR_NOT_FOUND) {
            // Optional: log error if it wasn't a "not supported" or "not found" error
        }
        
        if (LibUsb.claimInterface(handle, 0) != LibUsb.SUCCESS) return false
        
        return true
    }

    fun bulkRead(): ByteArray? {
        if (handle == null) return null
        
        // Increase buffer to handle ciphertext + IV + TAG + Header
        val buffer = ByteBuffer.allocateDirect(128 * 1024) 
        val transferred = IntBuffer.allocate(1)

        val result = LibUsb.bulkTransfer(
            handle,
            ENDPOINT_IN,
            buffer,
            transferred,
            10000 // 10s timeout for stability
        )

        if (result != LibUsb.SUCCESS) return null

        val size = transferred.get()
        if (size <= 0) return ByteArray(0)
        
        val data = ByteArray(size)
        buffer.get(data, 0, size)

        return data
    }

    fun bulkWrite(data: ByteArray): Boolean {
        if (handle == null) return false
        
        val buffer = ByteBuffer.allocateDirect(data.size)
        buffer.put(data)
        val transferred = IntBuffer.allocate(1)

        val result = LibUsb.bulkTransfer(
            handle,
            ENDPOINT_OUT,
            buffer,
            transferred,
            10000
        )

        return result == LibUsb.SUCCESS && transferred.get() == data.size
    }

    fun close() {
        handle?.let {
            LibUsb.releaseInterface(it, 0)
            LibUsb.close(it)
        }
        handle = null
    }
}
