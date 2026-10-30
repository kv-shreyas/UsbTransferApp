package com.example.secureqt.sdk.host

import com.example.secureqt.sdk.SecureQtSdk
import com.example.secureqt.sdk.channel.SecureChannel
import com.example.secureqt.sdk.client.ISecureTransferClient
import com.example.secureqt.sdk.logging.ILogger
import kotlin.time.Duration.Companion.milliseconds

/**
 * Host-side connection flow for one device: finds it, switches it to AOA mode if needed,
 * opens the transport (waiting for a driver install if one is underway) and performs the
 * secure handshake. Use [client] for transfers once [connect] returns true.
 *
 * @param targetDeviceId Physical identifier to connect to, or null for the first Android device found.
 */
class DesktopAoaLink(
    private val deviceManager: DesktopUsbDeviceManager,
    val connection: DesktopUsbConnection = DesktopUsbConnection(),
    private val targetDeviceId: String? = null,
    private val logger: ILogger = StdoutLogger
) {

    private val TAG = "DesktopAoaLink"
    var isAoaMode: Boolean = false
        private set

    val channel: SecureChannel = SecureQtSdk.createChannel(connection)
    val client: ISecureTransferClient = SecureQtSdk.createTransferClient(channel)

    fun connect(): Boolean {
        isAoaMode = false
        logger.i(TAG, "Attempting to find Android device (targetDeviceId: $targetDeviceId)...")
        
        var device: org.usb4java.Device? = null
        for (i in 0 until 5) {
            device = if (targetDeviceId != null) {
                deviceManager.findDeviceById(targetDeviceId)
            } else {
                deviceManager.findAndroidDevice()
            }
            if (device != null) break
            Thread.sleep(200)
        }
        
        if (device == null) return false
        
        try {
            val desc = org.usb4java.DeviceDescriptor()
            org.usb4java.LibUsb.getDeviceDescriptor(device, desc)
            val vid = desc.idVendor().toInt() and 0xFFFF
            val pid = desc.idProduct().toInt() and 0xFFFF
            val isAlreadyAoa = pid in 0x2D00..0x2D05
            
            if (isAlreadyAoa) {
                isAoaMode = true
                if (!connection.open(device)) {
                    deviceManager.reportDriverIssue(vid, pid, "Android Device (AOA)")
                    if (deviceManager.isDriverInstalling()) {
                        logger.i(TAG, "WinUSB driver installation in progress for AOA device. Awaiting completion...")
                        val installed = kotlinx.coroutines.runBlocking { deviceManager.waitForDriverInstall() }
                        if (installed) {
                            logger.i(TAG, "Driver installation finished! Re-acquiring AOA device after PnP binding...")
                            Thread.sleep(1200)
                            val freshAccessory = deviceManager.findAccessory(targetDeviceId)
                            if (freshAccessory != null) {
                                try {
                                    if (connection.open(freshAccessory)) {
                                        logger.i(TAG, "Successfully opened AOA device after driver installation!")
                                    } else {
                                        logger.e(TAG, "Failed to open connection to AOA accessory after driver install.")
                                        return false
                                    }
                                } finally {
                                    deviceManager.releaseDevice(freshAccessory)
                                }
                            } else {
                                logger.e(TAG, "Could not find AOA device after driver install.")
                                return false
                            }
                        } else {
                            return false
                        }
                    } else {
                        return false
                    }
                }
            } else {
                // Pre-emptively kill any background adb.exe server so it cannot claim PID 0x2D01 composite endpoints
                connection.killAdbServerIfRunning()

                if (connection.switchToAoa(device)) {
                    var accessory: org.usb4java.Device? = null
                    logger.i(TAG, "Waiting up to 10s for device to reconnect in AOA mode (targetId: $targetDeviceId)...")
                    for (i in 0 until 50) {
                        Thread.sleep(200)
                        if (i % 10 == 0) {
                            connection.killAdbServerIfRunning()
                        }
                        accessory = deviceManager.findAccessory(targetDeviceId)
                        if (accessory != null) {
                            logger.i(TAG, "Device re-enumerated in AOA mode on attempt ${i + 1}!")
                            break
                        }
                    }
                    if (accessory == null) {
                        logger.w(TAG, "Device failed to reconnect in AOA mode within 10s. Please ensure: 1) Phone screen is UNLOCKED, 2) USB Tethering is DISABLED, 3) Accept the USB Accessory prompt on Android.")
                        // On Windows, if USB debugging is ON, the device re-enumerates as composite PID 0x2D01 (AOA + ADB), which may lack a WinUSB driver for Interface 0
                        deviceManager.reportDriverIssue(0x18D1, 0x2D01, "Android Open Accessory (ADB Composite)")
                        return false
                    }
                    isAoaMode = true
                    try {
                        // Allow Windows PnP to complete driver binding to the new USB device node
                        Thread.sleep(600)
                        if (!connection.open(accessory)) {
                            logger.e(TAG, "Failed to open connection to accessory in AOA mode.")
                            val aDesc = org.usb4java.DeviceDescriptor()
                            val accessoryPid = if (org.usb4java.LibUsb.getDeviceDescriptor(accessory, aDesc) == org.usb4java.LibUsb.SUCCESS) {
                                aDesc.idProduct().toInt() and 0xFFFF
                            } else {
                                0x2D01
                            }
                            deviceManager.reportDriverIssue(0x18D1, accessoryPid, "Android Open Accessory")
                            if (deviceManager.isDriverInstalling()) {
                                logger.i(TAG, "WinUSB driver installation in progress for AOA mode. Awaiting completion...")
                                val installed = kotlinx.coroutines.runBlocking { deviceManager.waitForDriverInstall() }
                                if (installed) {
                                    logger.i(TAG, "Driver installation finished! Re-acquiring AOA device after PnP binding...")
                                    Thread.sleep(1200)
                                    val freshAccessory = deviceManager.findAccessory(targetDeviceId)
                                    if (freshAccessory != null) {
                                        try {
                                            if (!connection.open(freshAccessory)) {
                                                logger.e(TAG, "Failed to open connection to accessory after driver install.")
                                                return false
                                            }
                                        } finally {
                                            deviceManager.releaseDevice(freshAccessory)
                                        }
                                    } else {
                                        logger.e(TAG, "Could not find AOA device after driver install.")
                                        return false
                                    }
                                } else {
                                    return false
                                }
                            } else {
                                return false
                            }
                        }
                    } finally {
                        deviceManager.releaseDevice(accessory)
                    }
                } else {
                    logger.w(TAG, "Failed to switch device to AOA mode. Ensure device is unlocked and file transfer/AOA is allowed.")
                    return false
                }
            }
        } finally {
            deviceManager.releaseDevice(device)
        }

        // Perform Desktop-side handshake sequence using the unified channel protocol
        try {
            return kotlinx.coroutines.runBlocking {
                val handshakeSuccess = channel.performHandshake(isInitiator = true)
                if (handshakeSuccess) {
                    client.sendDeviceId(targetDeviceId ?: "UnknownDevice")
                }
                handshakeSuccess
            }
        } catch (e: Exception) {
            logger.e(TAG, "Handshake failed", e)
            return false
        }
    }

    fun disconnect() {
        try {
            kotlinx.coroutines.runBlocking { 
                client.sendDisconnect()
                kotlinx.coroutines.delay(100.milliseconds) // Allow time for packet to traverse USB and be read by Android
            }
        } catch (e: Exception) {
            logger.w(TAG, "Error sending disconnect signal: ${e.message}")
        }
        connection.close()
    }

    fun checkPhysicalConnection(): Pair<Boolean, String?> {
        return if (targetDeviceId != null) {
            deviceManager.isDevicePhysicallyConnected(targetDeviceId)
        } else {
            deviceManager.isDevicePhysicallyConnected()
        }
    }
}
