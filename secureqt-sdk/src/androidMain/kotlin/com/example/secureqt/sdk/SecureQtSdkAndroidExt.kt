package com.example.secureqt.sdk

import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import com.example.secureqt.sdk.transport.android.AndroidAoaTransport

/**
 * Convenience Android extensions for [SecureQtSdk] operating in USB Client (Accessory) mode.
 */

/**
 * Creates an [AndroidAoaTransport] directly for an attached [UsbAccessory].
 */
fun SecureQtSdk.createAndroidAccessoryTransport(
    usbManager: UsbManager,
    accessory: UsbAccessory
): AndroidAoaTransport? {
    return AndroidAoaTransport.open(usbManager, accessory)
}
