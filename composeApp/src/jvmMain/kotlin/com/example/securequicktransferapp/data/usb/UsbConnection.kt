package com.example.securequicktransferapp.data.usb

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

    // Reusable direct buffers to prevent severe GC thrashing and allocation overhead during transfers
    private val readBuffer = ByteBuffer.allocateDirect(300 * 1024)
    private val writeBuffer = ByteBuffer.allocateDirect(300 * 1024)
    private val transferredBuffer = IntBuffer.allocate(1)

    fun switchToAoa(device: Device): Boolean {
        val tempHandle = DeviceHandle()
        var openResult = LibUsb.open(device, tempHandle)
        if (openResult == LibUsb.ERROR_ACCESS) {
            killAdbServerIfRunning()
            for (retry in 1..4) {
                Thread.sleep(300)
                openResult = LibUsb.open(device, tempHandle)
                if (openResult == LibUsb.SUCCESS) break
            }
        }
        if (openResult != LibUsb.SUCCESS) {
            println("[UsbConnection] switchToAoa Error: LibUsb.open failed with code $openResult (${LibUsb.strError(openResult)})")
            return false
        }

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
            if (protocolResult < 0) {
                println("[UsbConnection] switchToAoa Error: Get Protocol controlTransfer failed: $protocolResult (${LibUsb.strError(protocolResult)})")
                return false
            }
            protocolBuffer.rewind()
            val protocol = protocolBuffer.getShort()
            if (protocol < 1) {
                println("[UsbConnection] switchToAoa Error: Protocol version too low ($protocol)")
                return false
            }

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
                if (result < 0) {
                    println("[UsbConnection] switchToAoa Error: Send String [$i] failed: $result (${LibUsb.strError(result)})")
                    return false
                }
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
            if (startResult < 0) {
                println("[UsbConnection] switchToAoa Error: Start Accessory failed: $startResult (${LibUsb.strError(startResult)})")
                return false
            }

            println("[UsbConnection] switchToAoa: Switch successful, device should reconnect in AOA mode.")
            return true
        } finally {
            LibUsb.close(tempHandle)
        }
    }

    fun isOpen(): Boolean = handle != null

    fun open(device: Device): Boolean {
        if (handle != null) {
            close()
        }
        handle = DeviceHandle()
        var result = LibUsb.open(device, handle)

        // On Windows, two transient failure codes are expected immediately after a driver change:
        //   -3  (ERROR_ACCESS)         — driver installed but another process briefly holds it
        //   -12 (ERROR_NOT_SUPPORTED)  — WinUSB driver just installed but PnP binding not yet complete
        // We retry with increasing patience: 16 × 500 ms = up to 8 seconds.
        if (result == LibUsb.ERROR_ACCESS || result == LibUsb.ERROR_NOT_SUPPORTED) {
            if (result == LibUsb.ERROR_ACCESS) killAdbServerIfRunning()
            println("[UsbConnection] open: code $result (${LibUsb.strError(result)}) — waiting for driver binding...")
            for (retry in 1..16) {
                Thread.sleep(500)
                result = LibUsb.open(device, handle)
                if (result == LibUsb.SUCCESS) {
                    println("[UsbConnection] open: driver bound on retry $retry")
                    break
                }
                if (result != LibUsb.ERROR_ACCESS && result != LibUsb.ERROR_NOT_SUPPORTED) break
            }
        }

        if (result != LibUsb.SUCCESS) {
            println("[UsbConnection] Error: Failed to open device handle. Code: $result (${LibUsb.strError(result)})")
            handle = null
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
            // -6 (Resource busy) is expected if the device is not yet in AOA mode and the kernel holds it.
            if (claimResult != LibUsb.ERROR_BUSY) {
                println("[UsbConnection] Warning: Failed to claim interface $ifaceNum. Code: $claimResult (${LibUsb.strError(claimResult)})")
            }
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

    private val readLock = Any()
    private val writeLock = Any()

    fun bulkRead(): ByteArray? {
        synchronized(readLock) {
        if (handle == null) {
            println("[UsbConnection] Error: bulkRead failed - handle is null.")
            return null
        }
        
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
                if (size > 0) {
                    // Data received despite timeout flag
                    println("[UsbConnection] Warning: bulkRead timed out but transferred $size bytes.")
                } else {
                    // Normal polling timeout waiting for incoming packets
                    return null
                }
            } else {
                println("[UsbConnection] Error: bulkRead failed. Code: $result (${LibUsb.strError(result)}), Endpoint: ${String.format("0x%02X", endpointIn)}")
                if (result == LibUsb.ERROR_NO_DEVICE || result == LibUsb.ERROR_IO || result == LibUsb.ERROR_PIPE) {
                    close()
                }
                return null
            }
        }

        if (size <= 0) return null
        
        val data = ByteArray(size)
        readBuffer.rewind()
        readBuffer.get(data, 0, size)

        return data
        }
    }

    fun clearInputBuffer() {
        if (handle == null) return
        val buffer = ByteBuffer.allocateDirect(16384)
        val transferred = IntBuffer.allocate(1)
        var result: Int
        do {
            transferred.rewind() // Reset position
            // Short timeout to flush what's already there without freezing
            result = LibUsb.bulkTransfer(handle, endpointIn, buffer, transferred, 50)
        } while (result == LibUsb.SUCCESS && transferred.get(0) > 0)
    }

    fun bulkWrite(data: ByteArray): Boolean {
        synchronized(writeLock) {
        if (handle == null) {
            println("[UsbConnection] Error: bulkWrite failed - handle is null.")
            return false
        }
        
        // If data is larger than our buffer, allocate a temporary one, otherwise use the fast reusable buffer
        val useTempBuffer = data.size > writeBuffer.capacity()
        val baseBuffer = if (useTempBuffer) ByteBuffer.allocateDirect(data.size) else writeBuffer
        
        if (!useTempBuffer) baseBuffer.clear()
        
        baseBuffer.put(data)
        baseBuffer.flip() 

        // usb4java often ignores position/limit and passes the raw buffer capacity to C.
        // We MUST use .slice() so the JNI layer sees exactly 'data.size' as the buffer's capacity!
        val buffer = baseBuffer.slice()

        transferredBuffer.clear()

        // Add small retry for robustness
        var attempt = 0
        var result: Int
        do {
            result = LibUsb.bulkTransfer(
                handle,
                endpointOut,
                buffer,
                transferredBuffer,
                20000 // 20s
            )
            if (result == LibUsb.SUCCESS) break
            attempt++
            if (attempt < 2) Thread.sleep(100)
        } while (attempt < 2)

        if (result != LibUsb.SUCCESS) {
            println("[UsbConnection] Error: bulkWrite failed after $attempt attempts. Code: $result (${LibUsb.strError(result)}), Endpoint: ${String.format("0x%02X", endpointOut)}")
            if (result == LibUsb.ERROR_NO_DEVICE || result == LibUsb.ERROR_IO || result == LibUsb.ERROR_PIPE) {
                close()
            }
            return false
        }

        val sent = transferredBuffer.get(0)
        if (sent != data.size) {
            println("[UsbConnection] Error: Incomplete write. Expected ${data.size} bytes, sent $sent bytes.")
            return false
        }

        return true
        }
    }

    fun close() {
        synchronized(writeLock) {
            synchronized(readLock) {
                handle?.let {
            try {
                LibUsb.releaseInterface(it, currentInterface)
            } catch (e: Exception) {}
            try {
                if (LibUsb.kernelDriverActive(it, currentInterface) == 0) {
                    LibUsb.attachKernelDriver(it, currentInterface)
                }
            } catch (e: Exception) {}
            try {
                LibUsb.close(it)
            } catch (e: Exception) {}
        }
        handle = null
            }
        }
    }

    private fun killAdbServerIfRunning() {
        try {
            val os = System.getProperty("os.name", "").lowercase()
            if (os.contains("win")) {
                val localAppData = System.getenv("LOCALAPPDATA")
                val adbFile = if (localAppData != null) java.io.File(localAppData, "Android\\Sdk\\platform-tools\\adb.exe") else null
                if (adbFile != null && adbFile.exists()) {
                    ProcessBuilder(adbFile.absolutePath, "kill-server").start().waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                } else {
                    ProcessBuilder("taskkill", "/F", "/IM", "adb.exe").start().waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
        } catch (_: Exception) {}
    }

    fun resetPort() {
        handle?.let {
            try {
                LibUsb.resetDevice(it)
            } catch (e: Exception) {}
        }
    }
}
