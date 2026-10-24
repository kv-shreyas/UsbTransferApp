package com.example.securequicktransferapp.data.usb

import com.example.secureqt.sdk.SecureQtSdk
import com.example.secureqt.sdk.createDesktopTransport
import com.example.secureqt.sdk.findDesktopBulkEndpoints
import com.example.secureqt.sdk.switchDeviceToAoa
import com.example.secureqt.sdk.transport.IUsbTransport
import com.example.secureqt.sdk.transport.desktop.DesktopAoaTransport
import org.usb4java.Device
import org.usb4java.DeviceHandle
import org.usb4java.LibUsb

/**
 * Manages native USB lifecycle, driver binding retries, and AOA switching for Desktop.
 * Directly implements [IUsbTransport] by delegating to the SDK's [DesktopAoaTransport].
 */
class UsbConnection : IUsbTransport {

    private var handle: DeviceHandle? = null

    /** Active multiplatform transport provided by secureqt-sdk */
    var transport: DesktopAoaTransport? = null
        private set

    private var endpointIn = 0x81.toByte()
    private var endpointOut = 0x01.toByte()
    private var currentInterface = 0

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
        LibUsb.close(tempHandle)

        // Delegate AOA protocol negotiation (51/52/53 control transfers) to SDK facade
        return SecureQtSdk.switchDeviceToAoa(device)
    }

    fun isOpen(): Boolean = (transport?.isConnected() == true) || (handle != null)

    fun open(device: Device): Boolean {
        if (handle != null) {
            close()
        }
        handle = DeviceHandle()
        var result = LibUsb.open(device, handle)

        // On Windows, two transient failure codes are expected immediately after a driver change:
        //   -3  (ERROR_ACCESS)         — driver installed but another process briefly holds it
        //   -12 (ERROR_NOT_SUPPORTED)  — WinUSB driver just installed but PnP binding not yet complete
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

        // Auto-detect endpoints using SDK connector facade
        val endpointInfo = SecureQtSdk.findDesktopBulkEndpoints(device)
        if (endpointInfo == null) {
            println("[UsbConnection] Error: Could not find AOA bulk endpoints.")
            LibUsb.close(handle)
            handle = null
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
            if (claimResult != LibUsb.ERROR_BUSY) {
                println("[UsbConnection] Warning: Failed to claim interface $ifaceNum. Code: $claimResult (${LibUsb.strError(claimResult)})")
            }
            LibUsb.close(handle)
            handle = null
            return false
        }

        // Initialize SDK Desktop AOA transport using facade extension
        transport = SecureQtSdk.createDesktopTransport(handle!!, endpointIn, endpointOut, ifaceNum)
        return true
    }

    override suspend fun read(buffer: ByteArray): Int {
        return transport?.read(buffer) ?: -1
    }

    override suspend fun write(buffer: ByteArray): Boolean {
        return transport?.write(buffer) ?: false
    }

    override fun isConnected(): Boolean {
        return transport?.isConnected() ?: (handle != null)
    }

    override fun clearBuffer() {
        transport?.clearBuffer()
    }

    fun close() {
        transport?.close()
        transport = null
        handle?.let {
            try { LibUsb.releaseInterface(it, currentInterface) } catch (_: Exception) {}
            try {
                if (LibUsb.kernelDriverActive(it, currentInterface) == 0) {
                    LibUsb.attachKernelDriver(it, currentInterface)
                }
            } catch (_: Exception) {}
            try { LibUsb.close(it) } catch (_: Exception) {}
        }
        handle = null
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
            } catch (_: Exception) {}
        }
    }
}
