package com.example.secureqt.sdk

import com.example.secureqt.sdk.channel.SecureChannel
import com.example.secureqt.sdk.transport.desktop.DesktopAoaConnector
import com.example.secureqt.sdk.transport.desktop.DesktopAoaTransport
import org.usb4java.Device
import org.usb4java.DeviceHandle

/**
 * Convenience desktop extensions for [SecureQtSdk].
 */

/**
 * Creates a [DesktopAoaTransport] from an opened libusb handle and endpoint addresses.
 */
fun SecureQtSdk.createDesktopTransport(
    handle: DeviceHandle,
    inEp: Byte,
    outEp: Byte,
    ifaceNum: Int = 0
): DesktopAoaTransport {
    return DesktopAoaTransport(handle, inEp, outEp, ifaceNum)
}

/**
 * Finds bulk IN and OUT endpoints on a libusb [Device].
 */
fun SecureQtSdk.findDesktopBulkEndpoints(device: Device): DesktopAoaConnector.BulkEndpoints? {
    return DesktopAoaConnector.findBulkEndpoints(device)
}

/**
 * Sends standard AOA control transfers (requests 51, 52, 53) to command an Android device to reboot into AOA mode.
 */
fun SecureQtSdk.switchDeviceToAoa(
    device: Device,
    manufacturer: String = "AndroidOpenAccessory",
    model: String = "DataTransfer"
): Boolean {
    return DesktopAoaConnector.switchToAoa(device, manufacturer, model)
}

/**
 * Connects directly to an attached AOA device, claims its bulk interface, and returns an active [DesktopAoaTransport].
 */
fun SecureQtSdk.connectDesktopDevice(device: Device): DesktopAoaTransport? {
    return DesktopAoaConnector.connectToDevice(device)
}

/**
 * Scans the USB bus, switches the Android device to AOA mode if needed,
 * claims the bulk endpoints, and establishes a ready-to-use [SecureChannel].
 *
 * @param manufacturer Manufacturer identifier matching Android intent-filter
 * @param model Model identifier matching Android intent-filter
 * @param timeoutMs Timeout in milliseconds for device detection and AOA rebooting
 * @return A connected [SecureChannel] ready for handshake, or null if no device found
 */
fun SecureQtSdk.autoConnectDesktop(
    manufacturer: String = "AndroidOpenAccessory",
    model: String = "DataTransfer",
    timeoutMs: Long = 10000L
): SecureChannel? {
    val transport = DesktopAoaConnector.autoConnect(manufacturer, model, timeoutMs) ?: return null
    return createChannel(transport)
}
