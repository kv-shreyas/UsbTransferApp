package com.example.securequicktransferapp.data.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AoaHostSwitcher @Inject constructor(
    private val usbManagerWrapper: UsbManagerWrapper,
    private val usbLogger: com.example.securequicktransferapp.data.logging.UsbLogger
) {
    companion object {
        private const val TAG = "AoaHostSwitcher"

        // AOA Protocol Control Transfer Request Types
        private const val ACCESSORY_GET_PROTOCOL = 51
        private const val ACCESSORY_SEND_STRING = 52
        private const val ACCESSORY_START = 53

        // USB Request Type for vendor control transfers
        private const val USB_DIR_IN = 0x80
        private const val USB_DIR_OUT = 0x00
        private const val USB_TYPE_VENDOR = 0x40

        // AOA identification strings - MUST exactly match what Desktop and Android filters expect
        private const val MANUFACTURER = "AndroidOpenAccessory"
        private const val MODEL = "DataTransfer"
        private const val DESCRIPTION = "Handshake Demo"
        private const val VERSION = "1.0"
        private const val URI = "http://example.com"
        private const val SERIAL = "12345678"

        /**
         * Returns true if the device is already in Android Open Accessory (AOA) mode.
         * Google VID is 0x18D1, and AOA PIDs range from 0x2D00 to 0x2D05 (covering Accessory, ADB, Audio combinations).
         */
        fun isAoaDevice(device: UsbDevice?): Boolean {
            if (device == null) return false
            return device.vendorId == 0x18D1 && (device.productId in 0x2D00..0x2D05)
        }
    }

    /**
     * Checks if the connected UsbDevice supports Android Open Accessory (AOA) protocol without triggering a switch.
     */
    fun isAoaSupported(device: UsbDevice): Boolean {
        if (isAoaDevice(device)) return true

        val connection = usbManagerWrapper.openDevice(device) ?: return false
        return try {
            val protocolBuffer = ByteArray(2)
            val protocolResult = connection.controlTransfer(
                USB_DIR_IN or USB_TYPE_VENDOR,
                ACCESSORY_GET_PROTOCOL,
                0,
                0,
                protocolBuffer,
                2,
                3000
            )
            if (protocolResult < 0) return false
            val protocol = (protocolBuffer[0].toInt() and 0xFF) or ((protocolBuffer[1].toInt() and 0xFF) shl 8)
            protocol >= 1
        } catch (e: Exception) {
            false
        } finally {
            try { connection.close() } catch (e: Exception) {}
        }
    }

    /**
     * Attempts to switch the given USB device into AOA (Android Open Accessory) mode.
     * After this call succeeds, the remote Android device will disconnect from USB and re-enumerate as an accessory.
     */
    fun switchToAoaMode(device: UsbDevice): Boolean {
        if (isAoaDevice(device)) {
            usbLogger.i(TAG, "switchToAoaMode: Device ${device.productName} is already in AOA mode.")
            return true
        }
        usbLogger.i(TAG, "switchToAoaMode: Initiating AOA mode switch for device ${device.productName} (VID: ${String.format("%04X", device.vendorId)}, PID: ${String.format("%04X", device.productId)})")
        val connection = usbManagerWrapper.openDevice(device)
        if (connection == null) {
            usbLogger.e(TAG, "switchToAoaMode: Failed to open UsbDeviceConnection.")
            return false
        }

        try {
            // 1. Check AOA Protocol Version
            val protocolBuffer = ByteArray(2)
            val protocolResult = connection.controlTransfer(
                USB_DIR_IN or USB_TYPE_VENDOR,
                ACCESSORY_GET_PROTOCOL,
                0,
                0,
                protocolBuffer,
                2,
                5000
            )

            if (protocolResult < 0) {
                usbLogger.e(TAG, "switchToAoaMode: GET_PROTOCOL failed with error code $protocolResult")
                return false
            }

            val protocol = (protocolBuffer[0].toInt() and 0xFF) or ((protocolBuffer[1].toInt() and 0xFF) shl 8)
            usbLogger.d(TAG, "switchToAoaMode: Remote device supports AOA protocol version $protocol")
            if (protocol < 1) {
                usbLogger.e(TAG, "switchToAoaMode: Remote device protocol version (< 1) not supported.")
                return false
            }

            // 2. Send Identification Strings
            val strings = arrayOf(MANUFACTURER, MODEL, DESCRIPTION, VERSION, URI, SERIAL)
            for (i in strings.indices) {
                if (!sendAoaString(connection, i, strings[i])) {
                    usbLogger.e(TAG, "switchToAoaMode: Failed to send AOA string index $i (${strings[i]})")
                    return false
                }
            }

            // 3. Start Accessory
            val startResult = connection.controlTransfer(
                USB_DIR_OUT or USB_TYPE_VENDOR,
                ACCESSORY_START,
                0,
                0,
                ByteArray(0),
                0,
                5000
            )

            if (startResult < 0) {
                usbLogger.e(TAG, "switchToAoaMode: ACCESSORY_START failed with code $startResult")
                return false
            }

            usbLogger.i(TAG, "switchToAoaMode: AOA switch triggered successfully. Remote device should now reconnect as Accessory.")
            return true
        } catch (e: Exception) {
            usbLogger.e(TAG, "switchToAoaMode: Exception during AOA switch", e)
            return false
        } finally {
            try {
                connection.close()
            } catch (e: Exception) {
                usbLogger.w(TAG, "switchToAoaMode: Error closing temporary connection", e)
            }
        }
    }

    private fun sendAoaString(connection: UsbDeviceConnection, index: Int, value: String): Boolean {
        val bytes = value.toByteArray(Charsets.UTF_8) + byteArrayOf(0) // null-terminated C string
        val result = connection.controlTransfer(
            USB_DIR_OUT or USB_TYPE_VENDOR,
            ACCESSORY_SEND_STRING,
            0,
            index,
            bytes,
            bytes.size,
            5000
        )
        return result >= 0
    }
}
