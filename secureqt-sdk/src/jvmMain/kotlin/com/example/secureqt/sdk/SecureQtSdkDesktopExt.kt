package com.example.secureqt.sdk

import com.example.secureqt.sdk.transport.desktop.DesktopAoaConnector
import com.example.secureqt.sdk.transport.desktop.DesktopAoaTransport
import org.usb4java.Device
import org.usb4java.DeviceHandle

/**
 * Convenience desktop extensions for [SecureQtSdk] operating as USB Host.
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
