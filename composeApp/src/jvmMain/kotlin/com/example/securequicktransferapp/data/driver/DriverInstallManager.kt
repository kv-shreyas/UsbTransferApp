package com.example.securequicktransferapp.data.driver

import com.example.usbdriverinstaller.api.IUsbDriverInstallManager
import com.example.usbdriverinstaller.api.UsbDriverInstallerFactory

/**
 * Top-level typealias preserving compatibility with existing code referencing
 * [DriverInstallState] in the project.
 */
typealias DriverInstallState = com.example.usbdriverinstaller.api.DriverInstallState

/**
 * Project adapter delegating to the standalone [IUsbDriverInstallManager] from `:usb-driver-installer`.
 */
class DriverInstallManager(
    private val delegate: IUsbDriverInstallManager = UsbDriverInstallerFactory.createManager()
) : IUsbDriverInstallManager by delegate
