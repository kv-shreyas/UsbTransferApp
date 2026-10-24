package com.example.secureqt.sdk.transport.desktop

import org.usb4java.ConfigDescriptor
import org.usb4java.Device
import org.usb4java.DeviceHandle
import org.usb4java.LibUsb
import java.nio.ByteBuffer
import kotlin.experimental.or

/**
 * Protocol utilities for Android Open Accessory (AOA) mode switching
 * and USB bulk endpoint resolution on Desktop USB Host.
 */
object DesktopAoaConnector {

    private const val ACCESSORY_GET_PROTOCOL = 51
    private const val ACCESSORY_SEND_STRING = 52
    private const val ACCESSORY_START = 53

    data class BulkEndpoints(val interfaceNumber: Int, val inAddr: Byte, val outAddr: Byte)

    /**
     * Sends the standard Android Open Accessory control transfers (requests 51, 52, 53)
     * to command an attached Android device to reboot into AOA Accessory mode.
     *
     * @param device libusb [Device] to be switched.
     * @param manufacturer Identification string sent to Android OS.
     * @param model Model string sent to Android OS (used to match accessory filter).
     * @return `true` if all control requests succeeded, `false` otherwise.
     */
    fun switchToAoa(
        device: Device,
        manufacturer: String = "AndroidOpenAccessory",
        model: String = "DataTransfer"
    ): Boolean {
        val tempHandle = DeviceHandle()
        val openResult = LibUsb.open(device, tempHandle)
        if (openResult != LibUsb.SUCCESS) return false

        try {
            if (LibUsb.kernelDriverActive(tempHandle, 0) == 1) {
                LibUsb.detachKernelDriver(tempHandle, 0)
            }

            // 1. Get Protocol Version
            val protocolBuffer = ByteBuffer.allocateDirect(2)
            val protocolResult = LibUsb.controlTransfer(
                tempHandle,
                LibUsb.ENDPOINT_IN or LibUsb.REQUEST_TYPE_VENDOR,
                ACCESSORY_GET_PROTOCOL.toByte(),
                0.toShort(),
                0.toShort(),
                protocolBuffer,
                2000
            )
            if (protocolResult < 0) return false
            protocolBuffer.rewind()
            val version = protocolBuffer.getShort()
            if (version < 1) return false

            // 2. Send Strings (Manufacturer, Model, Description, Version, URI, Serial)
            val strings = arrayOf(
                manufacturer,
                model,
                "SecureQT Transfer Session",
                "1.0",
                "https://github.com",
                "12345678"
            )

            for (i in strings.indices) {
                val data = strings[i].toByteArray(Charsets.UTF_8)
                val strBuf = ByteBuffer.allocateDirect(data.size + 1)
                strBuf.put(data)
                strBuf.put(0.toByte())
                strBuf.rewind()

                val res = LibUsb.controlTransfer(
                    tempHandle,
                    LibUsb.ENDPOINT_OUT or LibUsb.REQUEST_TYPE_VENDOR,
                    ACCESSORY_SEND_STRING.toByte(),
                    0.toShort(),
                    i.toShort(),
                    strBuf,
                    2000
                )
                if (res < 0) return false
            }

            // 3. Start Accessory
            val startRes = LibUsb.controlTransfer(
                tempHandle,
                LibUsb.ENDPOINT_OUT or LibUsb.REQUEST_TYPE_VENDOR,
                ACCESSORY_START.toByte(),
                0.toShort(),
                0.toShort(),
                ByteBuffer.allocateDirect(0),
                2000
            )
            return startRes >= 0
        } finally {
            LibUsb.close(tempHandle)
        }
    }

    /**
     * Resolves bulk IN and OUT endpoints by parsing the active config descriptor on [device].
     *
     * @param device libusb [Device] to inspect.
     * @return [BulkEndpoints] containing interface number and endpoint addresses, or null if not found.
     */
    fun findBulkEndpoints(device: Device): BulkEndpoints? {
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
                        return BulkEndpoints(i, inEp, outEp)
                    }
                }
            }
        } finally {
            LibUsb.freeConfigDescriptor(config)
        }
        return null
    }
}
