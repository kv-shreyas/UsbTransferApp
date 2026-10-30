package com.example.secureqt.sdk

import com.example.secureqt.sdk.host.DesktopSessionState
import com.example.secureqt.sdk.host.DesktopUsbConnection
import com.example.secureqt.sdk.host.DesktopUsbDeviceManager
import com.example.secureqt.sdk.host.DesktopUsbSession
import com.example.secureqt.sdk.host.DesktopUsbSessionManager
import com.example.secureqt.sdk.host.StdoutLogger
import com.example.secureqt.sdk.host.UsbDriverSupport
import com.example.secureqt.sdk.logging.ILogger
import com.example.secureqt.sdk.transport.desktop.DesktopAoaConnector
import com.example.secureqt.sdk.transport.desktop.DesktopAoaTransport
import org.usb4java.Device
import org.usb4java.DeviceHandle

/**
 * Convenience desktop extensions for [SecureQtSdk] operating as USB Host.
 */

/**
 * Creates a session manager that discovers Android devices, tracks plug and unplug events,
 * and connects AOA devices automatically. Call `startPolling()` to begin and `close()` when done.
 *
 * @param deviceManager Device enumerator. Call `cleanup()` on it after closing the session manager.
 */
fun SecureQtSdk.createDesktopSessionManager(
    deviceManager: DesktopUsbDeviceManager = DesktopUsbDeviceManager(),
    logger: ILogger = StdoutLogger
): DesktopUsbSessionManager<DesktopUsbSession, DesktopSessionState> {
    return DesktopUsbSessionManager(deviceManager, logger) { device ->
        DesktopUsbSession(device, deviceManager, DesktopUsbConnection(logger = logger), logger)
    }
}

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
