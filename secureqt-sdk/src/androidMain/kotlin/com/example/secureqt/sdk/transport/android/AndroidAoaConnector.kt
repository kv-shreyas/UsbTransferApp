package com.example.secureqt.sdk.transport.android

import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager

/**
 * Automates USB connection, interface claiming, and endpoint configuration
 * on Android for both [UsbDevice] and [UsbAccessory].
 */
object AndroidAoaConnector {

    /**
     * Connects to a standard [UsbDevice] by opening the connection, scanning all interfaces
     * for bulk IN and OUT endpoints, claiming the interface, and returning an [AndroidAoaTransport].
     */
    fun connectDevice(usbManager: UsbManager, device: UsbDevice): AndroidAoaTransport? {
        val connection = usbManager.openDevice(device) ?: return null

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
                if (connection.claimInterface(iface, true)) {
                    return AndroidAoaTransport(
                        connection = connection,
                        inEndpoint = epIn,
                        outEndpoint = epOut,
                        claimedInterface = iface
                    )
                }
            }
        }

        connection.close()
        return null
    }

    /**
     * Connects to an [UsbAccessory] passed via the `USB_ACCESSORY_ATTACHED` system intent
     * by opening its [android.os.ParcelFileDescriptor].
     */
    fun connectAccessory(usbManager: UsbManager, accessory: UsbAccessory): AndroidAoaTransport? {
        val pfd = usbManager.openAccessory(accessory) ?: return null
        return AndroidAoaTransport(pfd)
    }
}
