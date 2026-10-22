package com.example.secureqt.sdk.transport.desktop

import org.usb4java.*
import java.nio.ByteBuffer
import kotlin.experimental.or

/**
 * Automates USB bus discovery, AOA protocol handshake, and endpoint configuration
 * for Desktop applications connecting to Android devices.
 */
object DesktopAoaConnector {

    private const val ACCESSORY_GET_PROTOCOL = 51
    private const val ACCESSORY_SEND_STRING = 52
    private const val ACCESSORY_START = 53

    // Standard Android Open Accessory Hardware IDs
    private const val GOOGLE_VID: Short = 0x18D1.toShort()
    private const val AOA_PID_1: Short = 0x2D00.toShort() // Accessory only
    private const val AOA_PID_2: Short = 0x2D01.toShort() // Accessory + ADB

    private val ANDROID_VENDOR_IDS = hashSetOf(
        0x18D1.toShort(), // Google
        0x04E8.toShort(), // Samsung
        0x2717.toShort(), // Xiaomi
        0x12D1.toShort(), // Huawei
        0x22D9.toShort(), // OPPO
        0x22B8.toShort(), // Motorola
        0x1004.toShort(), // LG
        0x0FCE.toShort(), // Sony
        0x0BB4.toShort(), // HTC
        0x2B49.toShort(), // Vivo
        0x05C6.toShort()  // Qualcomm
    )

    private var libusbInitialized = false

    @Synchronized
    fun ensureInitialized() {
        if (!libusbInitialized) {
            val result = LibUsb.init(null)
            if (result != LibUsb.SUCCESS) {
                throw IllegalStateException("Failed to initialize libusb: ${LibUsb.strError(result)}")
            }
            libusbInitialized = true
        }
    }

    /**
     * Scans for an attached Android device. If already in AOA mode, connects immediately.
     * If in standard MTP/PTP/Charge mode, commands it to switch to AOA mode, waits for re-enumeration,
     * and establishes the connection.
     *
     * @param manufacturer Identification string sent to Android OS
     * @param model Model string sent to Android OS (used to match intent filter)
     * @param timeoutMs Maximum wait time for AOA re-enumeration
     * @return An active [DesktopAoaTransport] or null if no device found
     */
    fun autoConnect(
        manufacturer: String = "AndroidOpenAccessory",
        model: String = "DataTransfer",
        timeoutMs: Long = 10000L
    ): DesktopAoaTransport? {
        ensureInitialized()

        // 1. Check if device is ALREADY in AOA mode
        val existingAoa = findAoaDevice()
        if (existingAoa != null) {
            val transport = connectToDevice(existingAoa)
            if (transport != null) return transport
        }

        // 2. Scan for candidate standard Android devices and switch to AOA
        val candidateDevice = findCandidateAndroidDevice()
        if (candidateDevice != null) {
            val switched = switchToAoa(candidateDevice, manufacturer, model)
            if (switched) {
                // Wait for USB re-enumeration (typically takes 500ms - 2500ms)
                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < timeoutMs) {
                    Thread.sleep(300)
                    val aoaDev = findAoaDevice()
                    if (aoaDev != null) {
                        val transport = connectToDevice(aoaDev)
                        if (transport != null) return transport
                    }
                }
            }
        }

        return null
    }

    /**
     * Connects directly to an AOA device and claims bulk endpoints.
     */
    fun connectToDevice(device: Device): DesktopAoaTransport? {
        val handle = DeviceHandle()
        var result = LibUsb.open(device, handle)

        if (result != LibUsb.SUCCESS) {
            // Retry a few times in case driver is still binding
            for (i in 1..8) {
                Thread.sleep(250)
                result = LibUsb.open(device, handle)
                if (result == LibUsb.SUCCESS) break
            }
        }

        if (result != LibUsb.SUCCESS) return null

        val endpoints = findBulkEndpoints(device)
        if (endpoints == null) {
            LibUsb.close(handle)
            return null
        }

        LibUsb.setAutoDetachKernelDriver(handle, true)
        if (LibUsb.kernelDriverActive(handle, endpoints.interfaceNumber) == 1) {
            LibUsb.detachKernelDriver(handle, endpoints.interfaceNumber)
        }

        val claimResult = LibUsb.claimInterface(handle, endpoints.interfaceNumber)
        if (claimResult != LibUsb.SUCCESS) {
            LibUsb.close(handle)
            return null
        }

        return DesktopAoaTransport(
            handle = handle,
            endpointIn = endpoints.inAddr,
            endpointOut = endpoints.outAddr,
            interfaceNumber = endpoints.interfaceNumber
        )
    }

    /**
     * Sends the three standard Android Open Accessory control transfers.
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
                (LibUsb.ENDPOINT_IN or LibUsb.REQUEST_TYPE_VENDOR).toByte(),
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
                    (LibUsb.ENDPOINT_OUT or LibUsb.REQUEST_TYPE_VENDOR).toByte(),
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
                (LibUsb.ENDPOINT_OUT or LibUsb.REQUEST_TYPE_VENDOR).toByte(),
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

    data class BulkEndpoints(val interfaceNumber: Int, val inAddr: Byte, val outAddr: Byte)

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

    private fun findAoaDevice(): Device? {
        val list = DeviceList()
        val count = LibUsb.getDeviceList(null, list)
        if (count < 0) return null

        try {
            for (device in list) {
                val desc = DeviceDescriptor()
                if (LibUsb.getDeviceDescriptor(device, desc) == LibUsb.SUCCESS) {
                    if (desc.idVendor() == GOOGLE_VID && (desc.idProduct() == AOA_PID_1 || desc.idProduct() == AOA_PID_2)) {
                        return device
                    }
                }
            }
        } finally {
            LibUsb.freeDeviceList(list, false)
        }
        return null
    }

    private fun findCandidateAndroidDevice(): Device? {
        val list = DeviceList()
        val count = LibUsb.getDeviceList(null, list)
        if (count < 0) return null

        try {
            for (device in list) {
                val desc = DeviceDescriptor()
                if (LibUsb.getDeviceDescriptor(device, desc) == LibUsb.SUCCESS) {
                    if (ANDROID_VENDOR_IDS.contains(desc.idVendor())) {
                        return device
                    }
                }
            }
        } finally {
            LibUsb.freeDeviceList(list, false)
        }
        return null
    }
}
