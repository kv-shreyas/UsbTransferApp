package com.example.usbtransferapp.data.usb

import org.usb4java.*
import java.nio.ByteBuffer
import java.nio.IntBuffer
import kotlin.experimental.or

class UsbConnection {

    private var handle: DeviceHandle? = null
    
    // Default endpoints for AOA, but we will auto-detect them if possible
    private var endpointIn = 0x81.toByte()
    private var endpointOut = 0x01.toByte()

    private var currentInterface = 0

    // AOA Constants
    private val ACCESSORY_GET_PROTOCOL = 51
    private val ACCESSORY_SEND_STRING = 52
    private val ACCESSORY_START = 53

    fun switchToAoa(device: Device): Boolean {
        val tempHandle = DeviceHandle()
        if (LibUsb.open(device, tempHandle) != LibUsb.SUCCESS) return false

        try {
            // Detach kernel driver if necessary to ensure control transfers work
            if (LibUsb.kernelDriverActive(tempHandle, 0) == 1) {
                LibUsb.detachKernelDriver(tempHandle, 0)
            }

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
            protocolBuffer.rewind()
            val protocol = protocolBuffer.getShort()
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
                val data = strings[i].toByteArray()
                val stringBuffer = ByteBuffer.allocateDirect(data.size + 1)
                stringBuffer.put(data)
                stringBuffer.put(0x00.toByte())
                stringBuffer.rewind()

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

        val result = LibUsb.open(device, handle)
        if (result != LibUsb.SUCCESS) {
            println("[UsbConnection] Error: Failed to open device handle. Code: $result (${LibUsb.strError(result)})")
            return false
        }

        // Auto-detect endpoints and interface
        val endpointInfo = findAoaEndpoints(device)
        if (endpointInfo == null) {
            println("[UsbConnection] Error: Could not find AOA bulk endpoints.")
            LibUsb.close(handle)
            return false
        }
        
        val ifaceNum = endpointInfo.interfaceNumber
        currentInterface = ifaceNum
        endpointIn = endpointInfo.inAddr
        endpointOut = endpointInfo.outAddr
        
        println("[UsbConnection] Using Interface $ifaceNum, IN=${String.format("0x%02X", endpointIn)}, OUT=${String.format("0x%02X", endpointOut)}")

        // Enable auto-detach if supported, but explicitly detach kernel driver as fallback
        LibUsb.setAutoDetachKernelDriver(handle, true)
        if (LibUsb.kernelDriverActive(handle, ifaceNum) == 1) {
            val detachRes = LibUsb.detachKernelDriver(handle, ifaceNum)
            if (detachRes != LibUsb.SUCCESS) {
                println("[UsbConnection] Warning: Failed to detach kernel driver on iface $ifaceNum. Code: $detachRes")
            }
        }

        val claimResult = LibUsb.claimInterface(handle, ifaceNum)
        if (claimResult != LibUsb.SUCCESS) {
            println("[UsbConnection] Error: Failed to claim interface $ifaceNum. Code: $claimResult (${LibUsb.strError(claimResult)})")
            LibUsb.close(handle)
            handle = null
            return false
        }
        
        return true
    }

    data class AoaEndpoints(val interfaceNumber: Int, val inAddr: Byte, val outAddr: Byte)

    private fun findAoaEndpoints(device: Device): AoaEndpoints? {
        val desc = DeviceDescriptor()
        LibUsb.getDeviceDescriptor(device, desc)
        
        val config = ConfigDescriptor()
        if (LibUsb.getConfigDescriptor(device, 0, config) != LibUsb.SUCCESS) return null
        
        try {
            for (i in 0 until config.bNumInterfaces().toInt()) {
                val iface = config.iface()[i]
                for (j in 0 until iface.numAltsetting()) {
                    val alt = iface.altsetting()[j]
                    var inEp: Byte? = null
                    var outEp: Byte? = null
                    
                    for (k in 0 until alt.bNumEndpoints().toInt()) {
                        val ep = alt.endpoint()[k]
                        val addr = ep.bEndpointAddress()
                        val attr = ep.bmAttributes()
                        
                        if ((attr.toInt() and LibUsb.TRANSFER_TYPE_MASK.toInt()) == LibUsb.TRANSFER_TYPE_BULK.toInt()) {
                            if ((addr.toInt() and LibUsb.ENDPOINT_DIR_MASK.toInt()) == LibUsb.ENDPOINT_IN.toInt()) {
                                inEp = addr
                            } else {
                                outEp = addr
                            }
                        }
                    }
                    
                    if (inEp != null && outEp != null) {
                        return AoaEndpoints(i, inEp, outEp)
                    }
                }
            }
        } finally {
            LibUsb.freeConfigDescriptor(config)
        }
        return null
    }

    private fun detectEndpoints(device: Device) {
        // No longer used, replaced by findAoaEndpoints
    }

    fun bulkRead(): ByteArray? {
        if (handle == null) {
            println("[UsbConnection] Error: bulkRead failed - handle is null.")
            return null
        }
        
        val buffer = ByteBuffer.allocateDirect(300 * 1024) 
        val transferred = IntBuffer.allocate(1)

        val result = LibUsb.bulkTransfer(
            handle,
            endpointIn,
            buffer,
            transferred,
            20000 // 20s
        )

        if (result != LibUsb.SUCCESS) {
            println("[UsbConnection] Error: bulkRead failed. Code: $result (${LibUsb.strError(result)}), Endpoint: ${String.format("0x%02X", endpointIn)}")
            return null
        }

        val size = transferred.get()
        if (size < 0) return null
        
        val data = ByteArray(size)
        buffer.rewind()
        buffer.get(data, 0, size)

        return data
    }

    fun clearInputBuffer() {
        if (handle == null) return
        val buffer = ByteBuffer.allocateDirect(4096)
        val transferred = IntBuffer.allocate(1)
        var result: Int
        do {
            transferred.rewind() // Reset position
            // Very short timeout to just flush what's already there
            result = LibUsb.bulkTransfer(handle, endpointIn, buffer, transferred, 10)
        } while (result == LibUsb.SUCCESS && transferred.get(0) > 0)
    }

    fun bulkWrite(data: ByteArray): Boolean {
        if (handle == null) {
            println("[UsbConnection] Error: bulkWrite failed - handle is null.")
            return false
        }
        
        val buffer = ByteBuffer.allocateDirect(data.size)
        buffer.put(data)
        buffer.rewind()

        val transferred = IntBuffer.allocate(1)

        // Add small retry for robustness
        var attempt = 0
        var result: Int
        do {
            result = LibUsb.bulkTransfer(
                handle,
                endpointOut,
                buffer,
                transferred,
                20000 // 20s
            )
            if (result == LibUsb.SUCCESS) break
            attempt++
            if (attempt < 2) Thread.sleep(100)
        } while (attempt < 2)

        if (result != LibUsb.SUCCESS) {
            println("[UsbConnection] Error: bulkWrite failed after $attempt attempts. Code: $result (${LibUsb.strError(result)}), Endpoint: ${String.format("0x%02X", endpointOut)}")
            return false
        }

        val sent = transferred.get()
        if (sent != data.size) {
            println("[UsbConnection] Error: Incomplete write. Expected ${data.size} bytes, sent $sent bytes.")
            return false
        }

        return true
    }

    fun close() {
        handle?.let {
            LibUsb.releaseInterface(it, currentInterface)
            // Attempt to re-attach kernel driver to leave device in proper state
            if (LibUsb.kernelDriverActive(it, currentInterface) == 0) {
                LibUsb.attachKernelDriver(it, currentInterface)
            }
            
            // Force a USB port reset so the Android device drops out of Accessory mode.
            // This ensures that on the next connection attempt, the AOA switch triggers again,
            // which fires the necessary ATTACHED intents on the Android side to auto-connect.
            LibUsb.resetDevice(it)
            
            LibUsb.close(it)
        }
        handle = null
    }
}
