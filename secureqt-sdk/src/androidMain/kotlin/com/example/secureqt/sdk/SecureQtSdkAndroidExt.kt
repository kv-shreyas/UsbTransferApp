package com.example.secureqt.sdk

import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.example.secureqt.sdk.channel.SecureChannel
import com.example.secureqt.sdk.transport.android.AndroidAoaConnector
import com.example.secureqt.sdk.transport.android.AndroidAoaTransport

/**
 * Convenience Android extensions for [SecureQtSdk].
 */

/**
 * Creates an [AndroidAoaTransport] for an attached [UsbDevice].
 */
fun SecureQtSdk.createAndroidDeviceTransport(usbManager: UsbManager, device: UsbDevice): AndroidAoaTransport? {
    return AndroidAoaConnector.connectDevice(usbManager, device)
}

/**
 * Creates an [AndroidAoaTransport] for an attached [UsbAccessory].
 */
fun SecureQtSdk.createAndroidAccessoryTransport(usbManager: UsbManager, accessory: UsbAccessory): AndroidAoaTransport? {
    return AndroidAoaConnector.connectAccessory(usbManager, accessory)
}

/**
 * Connects to a [UsbDevice] attached in accessory mode, claims endpoints,
 * and creates a ready-to-use [SecureChannel].
 */
fun SecureQtSdk.connectAndroidDevice(usbManager: UsbManager, device: UsbDevice): SecureChannel? {
    val transport = createAndroidDeviceTransport(usbManager, device) ?: return null
    return createChannel(transport)
}

/**
 * Connects to an [UsbAccessory] passed via the `USB_ACCESSORY_ATTACHED` intent,
 * opens the descriptor, and creates a ready-to-use [SecureChannel].
 */
fun SecureQtSdk.connectAndroidAccessory(usbManager: UsbManager, accessory: UsbAccessory): SecureChannel? {
    val transport = createAndroidAccessoryTransport(usbManager, accessory) ?: return null
    return createChannel(transport)
}
