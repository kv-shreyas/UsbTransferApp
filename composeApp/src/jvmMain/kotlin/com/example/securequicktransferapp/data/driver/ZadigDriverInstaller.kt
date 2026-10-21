package com.example.securequicktransferapp.data.driver

import com.example.usbdriverinstaller.api.DriverInstallResult
import com.example.usbdriverinstaller.api.IUsbDriverInstaller
import com.example.usbdriverinstaller.api.UsbDriverInstallerFactory

/**
 * Top-level typealias preserving compatibility with existing code referencing
 * [DriverInstallResult] in the project.
 */
typealias DriverInstallResult = com.example.usbdriverinstaller.api.DriverInstallResult

/**
 * Project adapter delegating to the standalone [IUsbDriverInstaller] from `:usb-driver-installer`.
 */
object ZadigDriverInstaller : IUsbDriverInstaller by UsbDriverInstallerFactory.getDefaultInstaller()
